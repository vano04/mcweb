/*
 * MC-Web builder patch: register the WasmLM thread entry point as a Wasm export.
 *
 * `WebImageGenerator.registerEntryPoints` discovers `@WasmExport` methods with
 * `ImageClassLoader.findAnnotatedMethods`, which walks the image classpath and the
 * builder's module path entries. Classes injected with `--patch-module` are in neither,
 * so `McWebLMThreads.run` - the function a thread agent calls to enter Java - would
 * never be exported. Register it explicitly instead.
 */
package com.oracle.svm.hosted.webimage.wasm.codegen;

import java.util.Map;

import com.oracle.svm.hosted.webimage.options.WebImageOptions;
import com.oracle.svm.util.ReflectionUtil;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMHeapLock;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMHeapPolicy;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMMark;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMMonitors;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMSafepoint;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMSweep;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMThreadLocals;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMTiming;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMTlab;
import com.oracle.svm.webimage.threads.McWebLMThreads;
import com.oracle.svm.webimage.wasm.McWebLMConversion;

public final class McWebLMThreadExports {

    private McWebLMThreadExports() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void register(Map entryPoints) {
        if (WebImageOptions.getBackend() != WebImageOptions.CompilerBackend.WASM) {
            return;
        }
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMThreads.class, "run", long.class), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMThreads.class, "currentThreadAddress"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMThreads.class, "droppedStartsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMSafepoint.class, "poll"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(
                        com.oracle.svm.hosted.webimage.wasm.gc.McWebLMRawBlockProbe.class,
                        "rawBlocksSurviveCollection", int.class, int.class), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapLock.class, "attachAgent", long.class, long.class), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMConversion.class, "stringLength", String.class), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMConversion.class, "stringCharAt", String.class, int.class), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapLock.class, "runningAgents"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapLock.class, "parkedAgentsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapLock.class, "stoppedCollectionsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapLock.class, "uncontendedCollectionsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapLock.class, "skippedCollectionsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapLock.class, "maxParkedAgentsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapLock.class, "agentRefusedCollectionsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapLock.class, "collectionRequestedExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapLock.class, "latchWaitsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMSafepoint.class, "gcDisabledExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "allocationProbesExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "fullScansExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "boundedMissesExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "deepSearchesExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "deepHitsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "truthLargestKiB"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "noProgressFreeMiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "noProgressHeapMiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "noProgressRequestKiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "noProgressProofKiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "lastProofKiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "noProgressTruthKiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "noProgressFreeBlocksExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "largestFreeKiB"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "growAttemptsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "growFailuresExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "noProgressExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "topologyEpochExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "maxRequestKiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "lastHeadKiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "lastCandidateKiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "failedSearchStateExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "oomCountExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "oomRequestKiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "oomAgentExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "oomSearchStateExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "oomGrownMiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "sizeClassHitsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "sizeClassBackupHitsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "sizeClassMissesExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "objectMiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "freeMiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "heapMiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMHeapPolicy.class, "objectPercentExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMonitors.class, "fallbackEntersExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMonitors.class, "peakEntriesExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMonitors.class, "liveEntriesExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMonitors.class, "slotsPerBucketExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMonitors.class, "enterThousandsExport"), null);
        // The monitor foreign-call targets need to be compiled roots. Registering them
        // here is the same mechanism `@WasmExport` discovery uses; they carry no
        // `@WasmExport`, so they are compiled but not exported.
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMonitors.class, "monitorEnterTarget", Object.class), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMonitors.class, "monitorExitTarget", Object.class), null);
        // The thread-local holder accessors are called from generated code, so they must
        // be compiled even though nothing in Java calls them.
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMThreadLocals.class, "objectHolder"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMThreadLocals.class, "primitiveHolder"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMThreadLocals.class, "currentThreadIndexExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMThreadLocals.class, "privateHolderExport"), null);
        // Thread-local allocation buffer health. `lockedFallbacks` is the one that
        // matters: a TLAB-eligible allocation that still took the global heap lock means
        // the buffer could not be refilled, which is the pre-TLAB behaviour.
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMTlab.class, "refillsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMTlab.class, "refillFailuresExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMTlab.class, "backoffSkipsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMTlab.class, "lockedFallbacksExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMTlab.class, "lastRegionKiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMTlab.class, "minRegionKiBExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMTlab.class, "shrinksExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(
                McWebLMTlab.class, "allocationBucketExport", int.class, int.class), null);
        // Top-down wall-clock accounting per agent. This is the instrument that says
        // whether the per-chunk gap is the runtime substrate or real Java compute.
        entryPoints.put(ReflectionUtil.lookupMethod(
                McWebLMTiming.class, "categoryMs", int.class, int.class), null);
        entryPoints.put(ReflectionUtil.lookupMethod(
                McWebLMTiming.class, "categoryCount", int.class, int.class), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMTiming.class, "wallMs", int.class), null);
        // Mark/sweep split. `heapWalks - collections` is the number of *mark* passes over
        // the whole block chain; upstream needs one per worklist overflow, and the point
        // of McWebLMMark is that it should now read zero.
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMark.class, "collectionsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMark.class, "heapWalksExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMark.class, "markMsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMark.class, "sweepMsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMark.class, "drainSeedsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMark.class, "overflowsExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMark.class, "peakDepthExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMMark.class, "stackAllocationsExport"), null);
        // The fused sweep: one walk of the block chain per collection instead of two.
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMSweep.class, "blocksExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMSweep.class, "freedExport"), null);
        entryPoints.put(ReflectionUtil.lookupMethod(McWebLMSweep.class, "runsExport"), null);
    }

}
