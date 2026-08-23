package dev.mcweb.graal;

/**
 * CPU-bound workload used to prove that a Worker pool built from one compiled
 * {@code WebAssembly.Module} achieves real parallelism.
 *
 * <p>Web Image has no Java threads (see
 * {@code docs/SESSION-2026-07-26-WASMLM-SHARED-HEAP.md}), so parallelism in the
 * browser can only come from running <em>several image instances</em>, one per
 * Worker, each with its own heap. This main is the per-instance unit of work:
 * it burns a deterministic amount of CPU inside Java and reports how long its
 * own instance took.
 *
 * <p>The measurement that matters is made by the host: running N of these
 * concurrently should take roughly as long as running one, whereas running
 * them sequentially takes N times as long. That difference is the proof that
 * the Workers are on different cores rather than sharing the page thread.
 *
 * <p>Args: {@code --rounds <n>} (default 6_000_000) and {@code --label <s>}.
 * The workload is integer-only and allocation-light on purpose, so the result
 * reflects scheduling rather than GC behaviour.
 */
public final class WorkerPoolProbeMain {
    private WorkerPoolProbeMain() {
    }

    public static void main(String[] args) {
        long rounds = 6_000_000L;
        String label = "worker";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--rounds".equals(args[i])) {
                rounds = Long.parseLong(args[i + 1]);
            } else if ("--label".equals(args[i])) {
                label = args[i + 1];
            }
        }

        BrowserThreadProbe.report(label + ".start", "ok", "rounds=" + rounds);
        long t0 = System.nanoTime();
        long checksum = burn(rounds);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        // The checksum is reported so the host can confirm every instance ran
        // the same workload, and so the loop cannot be optimised away.
        BrowserThreadProbe.report(label + ".done", "ok", "ms=" + ms + " checksum=" + checksum);
    }

    /** Deterministic integer mixing; returns a value that depends on every round. */
    private static long burn(long rounds) {
        long x = 0x9E3779B97F4A7C15L;
        for (long i = 0; i < rounds; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            x += i;
        }
        return x;
    }
}
