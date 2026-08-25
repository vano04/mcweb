package dev.mcweb.graal;

import dev.mcweb.graal.webgpu.BrowserGpu;

/**
 * Surfaces {@code RegistryDataLoader}'s per-entry failures.
 *
 * <p>When registry loading fails, Mojang throws a crash report carrying only
 * "Failed to parse &lt;id&gt; from pack &lt;pack&gt;" per entry and sends the
 * actual causes — the stack of each one — to {@code LOGGER.error}. Nothing in
 * this image can see a log line (SLF4J resolves to a provider that discards
 * them), so those causes were unreachable, and the brief report names every
 * failing registry without saying why any of them failed.</p>
 *
 * <p>An exact-counted ASM redirect points that one call here instead. This is a
 * diagnostic seam, not a behaviour change: the text is Mojang's own.</p>
 */
public final class BrowserRegistryDiagnostics {
    /** Stage markers are short; the report is long. Chunk it, but bound it. */
    private static final int CHUNK = 300;
    private static final int MAX_CHUNKS = 60;

    private BrowserRegistryDiagnostics() {
    }

    /**
     * Signature matches the call site's stack exactly — [Logger, String, Object]
     * — so the redirect is a straight opcode swap with no stack surgery.
     */
    public static void reportLoadErrors(
            final org.slf4j.Logger logger,
            final String message,
            final Object detail
    ) {
        String text;
        try {
            text = String.valueOf(detail);
        } catch (Throwable failure) {
            text = "<detail toString failed: " + failure.getClass().getName() + ">";
        }
        BrowserGpu.reportProgress("registry-errors:begin chars=" + text.length());
        int chunks = (text.length() + CHUNK - 1) / CHUNK;
        int emitted = Math.min(chunks, MAX_CHUNKS);
        for (int i = 0; i < emitted; i++) {
            int start = i * CHUNK;
            int end = Math.min(text.length(), start + CHUNK);
            BrowserGpu.reportProgress("registry-errors:" + (i + 1) + "/" + chunks + " "
                    + text.substring(start, end).replace('\n', '¶'));
        }
        BrowserGpu.reportProgress("registry-errors:end truncated=" + (chunks > emitted));
    }
}
