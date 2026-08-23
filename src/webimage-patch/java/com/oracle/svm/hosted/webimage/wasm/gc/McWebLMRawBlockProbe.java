/*
 * MC-Web builder patch: measures whether the sweep reclaims raw (non-object) blocks.
 *
 * `WasmAllocation.allocateObject` is `doMalloc` followed by
 * `markAsObject(getOuterPointer(p))`. A per-agent allocation cache -- the fix for the
 * measured fact that allocation throughput is constant regardless of thread count
 * (LmAllocStress: 1/2/3/5 threads take 0.17/0.34/0.46/0.74 s for the same work each) --
 * necessarily parks blocks between those two steps, so they are allocated but carry no
 * object bit.
 *
 * If the collector treats such a block as garbage, every cached block is a
 * use-after-free. That is the most likely cause of the `RuntimeError: memory access out
 * of bounds` seen at image startup when the cache was first wired in, and it cannot be
 * settled by reading `WasmAllocation`, which is only available as bytecode.
 *
 * So measure it: take raw blocks, stamp every word, collect twice, and report how many
 * stamps survive. This lives in a patch class rather than a probe because probes are
 * compiled without access to `jdk.graal.compiler.word`.
 */
package com.oracle.svm.hosted.webimage.wasm.gc;

import com.oracle.svm.webimage.platform.WebImageWasmLMPlatform;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;

import jdk.graal.compiler.word.Word;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;

@Platforms(WebImageWasmLMPlatform.class)
public final class McWebLMRawBlockProbe {

    private static final int MAX_BLOCKS = 512;
    private static final int SCRATCH = 40960;

    private McWebLMRawBlockProbe() {
    }

    /**
     * Returns {@code intact * 1000 + corrupted}, or -1 if the blocks could not be taken.
     *
     * <p>Addresses are stashed in the control block rather than a Java array so the
     * measurement itself does not allocate between the malloc and the collection.
     */
    @WasmExport(value = "mcweb.probe.rawBlocks", comment = "Do raw non-object blocks survive a collection")
    public static int rawBlocksSurviveCollection(int blockCount, int blockBytes) {
        int count = blockCount > MAX_BLOCKS ? MAX_BLOCKS : blockCount;
        int taken = 0;
        for (int i = 0; i < count; i++) {
            Pointer block = WasmAllocation.malloc(Word.unsigned(blockBytes));
            if (block.isNull()) {
                break;
            }
            Pointer slot = Word.pointer(SCRATCH + i * 4);
            slot.writeInt(0, (int) block.rawValue());
            for (int off = 0; off + 4 <= blockBytes; off += 4) {
                block.writeInt(off, 0x5A5A0000 + i);
            }
            taken++;
        }
        if (taken == 0) {
            return -1;
        }

        System.gc();
        System.gc();

        int intact = 0;
        int corrupted = 0;
        for (int i = 0; i < taken; i++) {
            Pointer slot = Word.pointer(SCRATCH + i * 4);
            long raw = slot.readInt(0) & 0xffff_ffffL;
            Pointer block = Word.pointer(raw);
            boolean ok = true;
            for (int off = 0; off + 4 <= blockBytes; off += 4) {
                if (block.readInt(off) != 0x5A5A0000 + i) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                intact++;
            } else {
                corrupted++;
            }
        }
        return intact * 1000 + corrupted;
    }
}
