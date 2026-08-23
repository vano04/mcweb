/*
 * MC-Web builder patch: small runtime helpers for WasmLM @JS.Coerce glue.
 *
 * A WasmLM Java reference reaches JavaScript as an i32 linear-memory address. The
 * generated glue can create Java strings with Web Image's existing `toJavaString`, but
 * Web Image has no inverse Java-String-to-JavaScript bridge on this backend. These
 * allocation-free exports provide that inverse without depending on a private String or
 * object layout.
 */
package com.oracle.svm.webimage.wasm;

import com.oracle.svm.webimage.platform.WebImageWasmLMPlatform;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;

import org.graalvm.nativeimage.Platforms;

@Platforms(WebImageWasmLMPlatform.class)
public final class McWebLMConversion {

    private McWebLMConversion() {
    }

    @WasmExport(value = "mcweb.string.length", comment = "Length of a Java String passed to JavaScript")
    public static int stringLength(String value) {
        return value.length();
    }

    @WasmExport(value = "mcweb.string.charAt", comment = "UTF-16 code unit of a Java String passed to JavaScript")
    public static char stringCharAt(String value, int index) {
        return value.charAt(index);
    }
}
