/*
 * MC-Web builder patch: per-agent VM thread-locals on the WasmLM backend.
 *
 * `WebImageWasmLMVMThreadSTFeature` maps every `FastThreadLocal` onto *one* pair of
 * arrays (`WebImageWasmVMThreadLocalSTSupport.objectThreadLocals` /
 * `primitiveThreadLocals`), and `WebImageWasmLMNodeLowerer.lowerThreadLocalHolder` emits
 * a constant reference to them, ignoring the `IsolateThread` argument entirely. With one
 * thread that is exactly right. With agents it means every thread shares one set of VM
 * thread-locals, so `Thread.currentThread()` inside an agent reports the primary's
 * thread - which Minecraft checks constantly (`Minecraft.isSameThread`).
 *
 * Here the holder becomes an inline marker load with the primary relocation as its
 * bootstrap fallback, resolved per agent from the shadow-stack carrier. Each agent gets
 * its own copy, seeded from the primary's, so inherited VM state stays sane; the agent
 * entry point then overwrites the `currentThread` slot with the thread it runs.
 *
 * The index of that slot is not hard-coded: `recordCurrentThreadIndex` reads it from the
 * thread-local infos while the image is being built, right after the feature assigns the
 * offsets.
 */
package com.oracle.svm.hosted.webimage.wasm.gc;

import java.util.List;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.threadlocal.VMThreadLocalInfo;
import com.oracle.svm.hosted.webimage.wasm.WebImageWasmVMThreadLocalSTSupport;

import jdk.graal.compiler.word.Word;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.word.Pointer;

public final class McWebLMThreadLocals {

    /** Must match McWebLMHeapLock's agent table size. */
    private static final int MAX_AGENTS = 16;

    /** Name given to the thread-local in `PlatformThreads`. */
    private static final String CURRENT_THREAD = "PlatformThreads.currentThread";

    /** Must match McWebLMHeapLock's carrier stack reservation. */
    private static final long AGENT_STACK_BYTES = 1L << 20;
    /** Marker at an aligned carrier stack low address. */
    private static final int AGENT_STACK_MARKER = 0x4d434147; // "MCAG"
    /** Raw holder pointers stored after the marker and carrier id. */
    private static final int OBJECT_HOLDER_OFFSET = 8;
    private static final int PRIMITIVE_HOLDER_OFFSET = 12;

    private McWebLMThreadLocals() {
    }

    /**
     * The per-agent state lives on `WebImageWasmVMThreadLocalSTSupport`, whose fields the
     * patcher extends, not in statics of this class. A static assigned by a build-time hook
     * is not part of the image heap unless the class is build-time initialized and the
     * field is declared as late-assigned; the singleton already has exactly that contract
     * (`@UnknownObjectField(availability = ReadyForCompilation.class)`) for the holders it
     * allocates in the same hook.
     *
     * `ImageSingletons.lookup` with a constant class is folded to a heap constant by SVM's
     * graph builder plugin, so this stays a plain field read on the hot path.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static WebImageWasmVMThreadLocalSTSupport support() {
        return ImageSingletons.lookup(WebImageWasmVMThreadLocalSTSupport.class);
    }

    // ---------------------------------------------------------------- runtime side

    /**
     * Returns the marker for the carrier containing the current Wasm shadow stack.
     * The carrier host already reserves and initializes this word for the identity
     * fast path, so holder lookup can use the same one aligned read without scanning
     * the agent table or calling {@code agentId()}.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer carrierMarker() {
        long sp = KnownIntrinsics.readStackPointer().rawValue();
        long low = sp & ~(AGENT_STACK_BYTES - 1L);
        Pointer marker = Word.pointer(low);
        return marker.readInt(0) == AGENT_STACK_MARKER ? marker : Word.nullPointer();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Object[] markerObjectHolder(Pointer marker) {
        long address = marker.readInt(OBJECT_HOLDER_OFFSET) & 0xffff_ffffL;
        if (address == 0) {
            return null;
        }
        Pointer pointer = Word.pointer(address);
        return (Object[]) pointer.toObjectNonNull();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static byte[] markerPrimitiveHolder(Pointer marker) {
        long address = marker.readInt(PRIMITIVE_HOLDER_OFFSET) & 0xffff_ffffL;
        if (address == 0) {
            return null;
        }
        Pointer pointer = Word.pointer(address);
        return (byte[]) pointer.toObjectNonNull();
    }

    /** Publishes the two long-lived holder arrays into an already initialized marker. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void publishMarkerHolders(Pointer marker, Object[] objects, byte[] primitives) {
        marker.writeInt(OBJECT_HOLDER_OFFSET, (int) Word.objectToUntrackedPointer(objects).rawValue());
        marker.writeInt(PRIMITIVE_HOLDER_OFFSET, (int) Word.objectToUntrackedPointer(primitives).rawValue());
    }

    /** Completes the primary marker after the allocator publishes its stack range. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static void publishPrimaryMarkerHolders(Pointer marker) {
        WebImageWasmVMThreadLocalSTSupport support = support();
        if (support.objectThreadLocals != null && support.primitiveThreadLocals != null) {
            publishMarkerHolders(marker, support.objectThreadLocals, support.primitiveThreadLocals);
        }
    }

    /**
     * Holder for the calling agent. Emitted by the patched `lowerThreadLocalHolder`, so it
     * runs on every VM thread-local access and must not allocate or be interrupted.
     *
     * <p>This is one of the hottest functions in the image - upstream lowers the holder to
     * a constant, and every access here pays a call instead - so the no-agent case is a
     * single linear-memory load and a branch, with no stack-pointer read and no table
     * scan (`agentIdOrPrimary`).
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @AlwaysInline("thread-local reads are generated in allocation and monitor hot paths")
    public static Object[] objectHolder() {
        Pointer marker = carrierMarker();
        if (marker.isNonNull()) {
            Object[] holder = markerObjectHolder(marker);
            if (holder != null) {
                return holder;
            }
        }
        WebImageWasmVMThreadLocalSTSupport support = support();
        Object[][] holders = support.mcwebObjectHolders;
        if (holders == null) {
            return support.objectThreadLocals;
        }
        // agentCount() inlines to the one control-page load. The primary-only image
        // must not enter agentIdOrPrimary(), whose stack-marker path is needed only
        // after a carrier has attached; thread-local access is frequent enough that
        // the distinction is visible in generated Wasm.
        int agent = McWebLMHeapLock.agentCount() == 0
                ? 0
                : McWebLMHeapLock.agentId();
        Object[] holder = holders[agent];
        return holder != null ? holder : support.objectThreadLocals;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @AlwaysInline("thread-local reads are generated in allocation and monitor hot paths")
    public static byte[] primitiveHolder() {
        Pointer marker = carrierMarker();
        if (marker.isNonNull()) {
            byte[] holder = markerPrimitiveHolder(marker);
            if (holder != null) {
                return holder;
            }
        }
        WebImageWasmVMThreadLocalSTSupport support = support();
        byte[][] holders = support.mcwebPrimitiveHolders;
        if (holders == null) {
            return support.primitiveThreadLocals;
        }
        int agent = McWebLMHeapLock.agentCount() == 0
                ? 0
                : McWebLMHeapLock.agentId();
        byte[] holder = holders[agent];
        return holder != null ? holder : support.primitiveThreadLocals;
    }

    /**
     * Gives an agent its own thread-locals, seeded from the primary's. Called on the
     * agent's own thread while it attaches, before it runs any Java thread, so allocation
     * here is safe.
     */
    public static void attach(int agent, long stackLow) {
        if (agent <= 0 || agent > MAX_AGENTS) {
            return;
        }
        WebImageWasmVMThreadLocalSTSupport support = support();
        Object[] templateObjects = support.objectThreadLocals;
        byte[] templatePrimitives = support.primitiveThreadLocals;
        if (support.mcwebObjectHolders == null || templateObjects == null || templatePrimitives == null) {
            return;
        }
        Object[] objects = support.mcwebObjectHolders[agent];
        if (objects == null) {
            objects = new Object[templateObjects.length];
            System.arraycopy(templateObjects, 0, objects, 0, templateObjects.length);
            support.mcwebObjectHolders[agent] = objects;
        }
        byte[] primitives = support.mcwebPrimitiveHolders[agent];
        if (primitives == null) {
            primitives = new byte[templatePrimitives.length];
            System.arraycopy(templatePrimitives, 0, primitives, 0, templatePrimitives.length);
            support.mcwebPrimitiveHolders[agent] = primitives;
        }
        if ((stackLow & (AGENT_STACK_BYTES - 1L)) == 0) {
            Pointer marker = Word.pointer(stackLow);
            if (marker.readInt(0) == AGENT_STACK_MARKER && marker.readInt(4) == agent) {
                publishMarkerHolders(marker, objects, primitives);
            }
        }
    }

    /** Makes `Thread.currentThread()` report {@code thread} on this agent. */
    public static void setCurrentThread(Thread thread) {
        int index = support().mcwebCurrentThreadIndex;
        if (index >= 0) {
            objectHolder()[index] = thread;
            McWebLMMonitors.publishCurrentThread(thread);
        }
    }

    /** Diagnostics: which slot holds `PlatformThreads.currentThread`, -1 if unknown. */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(value = "mcweb.tl.currentThreadIndex", comment = "Slot of PlatformThreads.currentThread")
    public static int currentThreadIndexExport() {
        return support().mcwebCurrentThreadIndex;
    }

    /** Diagnostics: 1 if this agent has its own holder, 0 if it is falling back to the primary's. */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(value = "mcweb.tl.privateHolder", comment = "Whether the calling agent has its own thread-locals")
    public static int privateHolderExport() {
        int agent = McWebLMHeapLock.agentId();
        Object[][] holders = support().mcwebObjectHolders;
        return agent > 0 && holders != null && holders[agent] != null ? 1 : 0;
    }

    public static Thread currentThreadOrNull() {
        int index = support().mcwebCurrentThreadIndex;
        return index < 0 ? null : (Thread) objectHolder()[index];
    }

    // ---------------------------------------------------------------- build side

    /**
     * Records the primary's holders and the current-thread slot index. Appended to
     * `WebImageWasmLMVMThreadSTFeature.beforeCompilation`, after the offsets are assigned.
     */
    public static void recordCurrentThreadIndex(List<VMThreadLocalInfo> infos) {
        WebImageWasmVMThreadLocalSTSupport support = ImageSingletons.lookup(WebImageWasmVMThreadLocalSTSupport.class);
        support.mcwebObjectHolders = new Object[MAX_AGENTS + 1][];
        support.mcwebPrimitiveHolders = new byte[MAX_AGENTS + 1][];
        support.mcwebObjectHolders[0] = support.objectThreadLocals;
        support.mcwebPrimitiveHolders[0] = support.primitiveThreadLocals;
        support.mcwebCurrentThreadIndex = -1;
        // The feature walks this same sorted list and hands out consecutive element
        // indices to the object thread-locals, so counting them gives the index without
        // reproducing the array-offset arithmetic.
        int index = 0;
        for (VMThreadLocalInfo info : infos) {
            if (!info.isObject) {
                continue;
            }
            if (CURRENT_THREAD.equals(info.name)) {
                support.mcwebCurrentThreadIndex = index;
                return;
            }
            index++;
        }
    }
}
