/*
 * MC-Web builder patch: `@JS` support for the GraalVM Web Image WasmLM backend.
 *
 * Upstream `WebImageWasmLMNodeLowerer.lowerJSBody` returns `getStub(...)`, so on
 * WasmLM every `@JS`/`@JavaScriptBody` method compiles to a stub that yields
 * null ("TODO GR-42437"). Everything else the seam needs already exists and is
 * backend-agnostic:
 *
 *   - `WasmJSCounterparts.idForJSBody` creates the `jsbody.<owner>.<method>`
 *     function import and records it, and
 *   - `WebImageWasmGCCodeGen.emitJSBodyImports` shows the JS object shape the
 *     module expects at instantiation (`wasmImports.jsbody`).
 *
 * This class supplies the three LM halves (lowering, import signature, import
 * object). It lives in the compiler's own package so it can reach
 * `WebImageWasmNodeLowerer.lowerExpression` and `WebImageWasmCodeGen.getProviders`,
 * and is injected into the builder module with
 * `--patch-module org.graalvm.extraimage.builder=...`.
 *
 * The upstream call sites are rewritten by tools/webimage-patch/McWebImagePatcher.java.
 */
package com.oracle.svm.hosted.webimage.wasm.codegen;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.oracle.svm.hosted.webimage.codegen.JSCodeGenTool;
import com.oracle.svm.hosted.webimage.js.JSBody;
import com.oracle.svm.hosted.webimage.wasm.ast.Instruction;
import com.oracle.svm.hosted.webimage.wasm.ast.Instructions;
import com.oracle.svm.hosted.webimage.wasm.ast.id.WasmId;
import com.oracle.svm.hosted.webimage.wasmgc.types.WasmRefType;
import com.oracle.svm.webimage.JSKeyword;
import com.oracle.svm.webimage.platform.WebImageWasmGCPlatform;
import com.oracle.svm.webimage.platform.WebImageWasmLMPlatform;
import com.oracle.svm.webimage.wasm.types.WasmUtil;
import com.oracle.svm.webimage.wasm.types.WasmValType;

import jdk.graal.compiler.hightiercodegen.CodeBuffer;
import jdk.graal.compiler.hightiercodegen.Emitter;
import jdk.graal.compiler.hightiercodegen.IEmitter;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Signature;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.AnnotationAccess;
import org.graalvm.webimage.api.JS.Coerce;

public final class McWebLMJSBody {

    private enum CoercionKind {
        VOID,
        BOOLEAN,
        NUMBER,
        LONG,
        STRING
    }

    private record CoercionPlan(CoercionKind[] arguments, CoercionKind result) {
    }

    /**
     * Populated while methods are lowered, then consumed when bootstrap JavaScript is
     * emitted. Native-image runs each build in a fresh process; the concurrent map also
     * makes parallel method compilation deterministic.
     */
    private static final Map<String, CoercionPlan> COERCIONS = new ConcurrentHashMap<>();

    private McWebLMJSBody() {
    }

    /**
     * WasmLM cannot use Web Image's object/proxy conversion runtime: that runtime is
     * platform-restricted to WasmGC and its values are externrefs. For @JS.Coerce methods
     * whose contract consists only of primitives and Strings, use the raw Wasm signature
     * and perform the required boundary conversions in the generated import wrapper.
     */
    public static boolean effectiveRawCall(ResolvedJavaMethod method, boolean rawCall, boolean coercion) {
        return rawCall || isSupportedLMCoercion(method, coercion);
    }

    public static boolean effectiveCoercion(ResolvedJavaMethod method, boolean coercion) {
        return coercion && !isSupportedLMCoercion(method, coercion);
    }

    private static boolean isSupportedLMCoercion(ResolvedJavaMethod method, boolean coercion) {
        if (!coercion || !Platform.includedIn(WebImageWasmLMPlatform.class) ||
                        !AnnotationAccess.isAnnotationPresent(method, Coerce.class)) {
            return false;
        }
        coercionPlan(method);
        return true;
    }

    private static CoercionPlan coercionPlan(ResolvedJavaMethod method) {
        if (!method.isStatic()) {
            throw new IllegalStateException("WasmLM @JS.Coerce instance methods require Java-object proxy support: " +
                            method.format("%H.%n(%p)"));
        }
        Signature signature = method.getSignature();
        CoercionKind[] arguments = new CoercionKind[signature.getParameterCount(false)];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = coercionKind(signature.getParameterType(i, method.getDeclaringClass()), method);
        }
        CoercionKind result = coercionKind(signature.getReturnType(method.getDeclaringClass()), method);
        return new CoercionPlan(arguments, result);
    }

    private static CoercionKind coercionKind(JavaType type, ResolvedJavaMethod method) {
        return switch (type.getJavaKind()) {
            case Void -> CoercionKind.VOID;
            case Boolean -> CoercionKind.BOOLEAN;
            case Byte, Short, Char, Int, Float, Double -> CoercionKind.NUMBER;
            case Long -> CoercionKind.LONG;
            case Object -> {
                if ("Ljava/lang/String;".equals(type.getName())) {
                    yield CoercionKind.STRING;
                }
                throw new IllegalStateException("WasmLM @JS.Coerce currently supports only primitive and String values; " +
                                type.toJavaName() + " appears in " + method.format("%H.%n(%p)"));
            }
            default -> throw new IllegalStateException("Unsupported WasmLM @JS.Coerce kind " + type.getJavaKind() +
                            " in " + method.format("%H.%n(%p)"));
        };
    }

    /**
     * Replacement body for {@code WebImageWasmLMNodeLowerer.lowerJSBody}.
     *
     * Mirrors {@code WebImageWasmGCNodeLowerer.lowerJSBody} minus the WasmGC
     * externref conversions: on WasmLM a Java reference *is* an i32 pointer into
     * the linear memory, so arguments and results cross the import boundary as
     * plain wasm numbers and JavaScript addresses the heap directly.
     */
    public static Instruction lowerJSBody(WebImageWasmLMNodeLowerer lowerer, JSBody jsBody) {
        WebImageWasmProviders providers = lowerer.masm().wasmProviders;
        Instructions params = new Instructions();
        for (ValueNode param : jsBody.getArguments()) {
            params.add(lowerer.lowerExpression(param));
        }
        WasmId.Func target = providers.getJSCounterparts().idForJSBody(providers, jsBody);
        if (isSupportedLMCoercion(jsBody.getMethod(), AnnotationAccess.isAnnotationPresent(jsBody.getMethod(), Coerce.class))) {
            COERCIONS.put(((WasmId.FunctionImport) target).getDescriptor().name, coercionPlan(jsBody.getMethod()));
        }
        Instruction call = new Instruction.Call(target, params);
        call.setComment("@JS body: " + jsBody.getMethod().format("%H.%n(%p)"));
        return call;
    }

    /**
     * Replacement body for {@code WasmJSCounterparts.getArgumentType}, which maps
     * {@link JavaKind#Object} to {@code externref} unconditionally. That is only
     * correct on WasmGC; a WasmLM module has no {@code externref} values and
     * passes references as i32 pointers.
     */
    public static WasmValType argumentType(JavaKind argumentKind, WasmUtil util) {
        if (argumentKind == JavaKind.Object && Platform.includedIn(WebImageWasmGCPlatform.class)) {
            return WasmRefType.EXTERNREF;
        }
        return util.mapType(argumentKind);
    }

    /**
     * Emits {@code wasmImports.jsbody}, the import-object half of the seam. Called
     * from an injected {@code WebImageWasmLMCodeGen.emitBootstrapDefinitions}
     * override, after the upstream bootstrap definitions.
     *
     * Unlike the WasmGC variant this does not wrap the body in a
     * {@code conversion.handleJSError} catch: the LM backend has no JS-side
     * conversion runtime yet, so a throwing body propagates out of the wasm frame
     * instead of being converted into a Java exception.
     */
    public static void emitJSBodyImports(WebImageWasmProviders providers, JSCodeGenTool codeGenTool) {
        if (!COERCIONS.isEmpty()) {
            emitCoercionSupport(codeGenTool.getCodeBuffer());
        }
        Map<String, IEmitter> definitions = new TreeMap<>();
        for (Map.Entry<JSBody.JSCode, WasmId.FunctionImport> entry : providers.getJSCounterparts().getJsBodyFunctions().entrySet()) {
            JSBody.JSCode jsCode = entry.getKey();
            String importName = entry.getValue().getDescriptor().name;
            CoercionPlan coercion = COERCIONS.get(importName);
            definitions.put(importName, tool -> {
                CodeBuffer masm = tool.getCodeBuffer();
                if (coercion == null) {
                    emitPlainBody(masm, codeGenTool, jsCode);
                } else {
                    emitCoercedBody(masm, codeGenTool, jsCode, coercion);
                }
            });
        }

        codeGenTool.genResolvedVarAssignmentPrefix("wasmImports.jsbody");
        codeGenTool.genObject(definitions);
        codeGenTool.getCodeBuffer().emitInsEnd();
    }

    private static void emitCoercionSupport(CodeBuffer masm) {
        masm.emitText("function mcwebFromJavaString(value) {");
        masm.emitNewLine();
        masm.emitText("\tif (!value) return null;");
        masm.emitNewLine();
        masm.emitText("\tconst length = getExport('mcweb.string.length')(value);");
        masm.emitNewLine();
        masm.emitText("\tlet result = '';");
        masm.emitNewLine();
        masm.emitText("\tfor (let i = 0; i < length; i++) result += String.fromCharCode(getExport('mcweb.string.charAt')(value, i));");
        masm.emitNewLine();
        masm.emitText("\treturn result;");
        masm.emitNewLine();
        masm.emitText("}");
        masm.emitNewLine();
    }

    private static void emitPlainBody(CodeBuffer masm, JSCodeGenTool codeGenTool, JSBody.JSCode jsCode) {
        masm.emitText("(...args) => ");
        emitFunction(masm, codeGenTool, jsCode);
        masm.emitText(".call(...args)");
    }

    private static void emitCoercedBody(CodeBuffer masm, JSCodeGenTool codeGenTool, JSBody.JSCode jsCode, CoercionPlan plan) {
        masm.emitText("(...args) => {");
        masm.emitNewLine();
        if (plan.result() != CoercionKind.VOID) {
            masm.emitText("\tconst result = ");
        } else {
            masm.emitText("\t");
        }
        emitFunction(masm, codeGenTool, jsCode);
        masm.emitText(".call(args[0]");
        for (int i = 0; i < plan.arguments().length; i++) {
            masm.emitText(", ");
            emitFromJava(masm, plan.arguments()[i], i + 1);
        }
        masm.emitText(");");
        masm.emitNewLine();
        if (plan.result() != CoercionKind.VOID) {
            masm.emitText("\treturn ");
            emitToJava(masm, plan.result());
            masm.emitText(";");
            masm.emitNewLine();
        }
        masm.emitText("}");
    }

    private static void emitFunction(CodeBuffer masm, JSCodeGenTool codeGenTool, JSBody.JSCode jsCode) {
        masm.emitKeyword(JSKeyword.LPAR);
        masm.emitKeyword(JSKeyword.FUNCTION);
        masm.emitKeyword(JSKeyword.LPAR);
        codeGenTool.genCommaList(Arrays.stream(jsCode.getArgs()).map(Emitter::of).collect(Collectors.toList()));
        masm.emitKeyword(JSKeyword.RPAR);
        masm.emitScopeBegin();
        for (String line : jsCode.getBody().split("\n")) {
            masm.emitText(line);
            masm.emitNewLine();
        }
        masm.emitScopeEnd();
        masm.emitKeyword(JSKeyword.RPAR);
    }

    private static void emitFromJava(CodeBuffer masm, CoercionKind kind, int argumentIndex) {
        switch (kind) {
            case BOOLEAN -> masm.emitText("args[" + argumentIndex + "] !== 0");
            case STRING -> masm.emitText("mcwebFromJavaString(args[" + argumentIndex + "])");
            case NUMBER, LONG -> masm.emitText("args[" + argumentIndex + "]");
            case VOID -> throw new IllegalStateException("void @JS argument");
        }
    }

    private static void emitToJava(CodeBuffer masm, CoercionKind kind) {
        switch (kind) {
            case BOOLEAN -> masm.emitText("result ? 1 : 0");
            case NUMBER, LONG -> masm.emitText("result");
            case STRING -> masm.emitText("result == null ? 0 : toJavaString(String(result))");
            case VOID -> throw new IllegalStateException("void @JS result conversion");
        }
    }
}
