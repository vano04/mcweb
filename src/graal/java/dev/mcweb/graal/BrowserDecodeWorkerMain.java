package dev.mcweb.graal;

import dev.mcweb.graal.stb.PngDecoder;
import java.util.Base64;

/** Persistent Worker entry point for byte-oriented image decoding. */
public final class BrowserDecodeWorkerMain {
    private BrowserDecodeWorkerMain() {
    }

    public static void main(String[] args) {
        if (!BrowserDecodeWorkerTransport.isAvailable()) {
            throw new IllegalStateException("mcWebDecodeWorker transport is unavailable");
        }
        BrowserDecodeWorkerTransport.onRequest(BrowserDecodeWorkerMain::decodePng);
    }

    private static void decodePng(int id, String dataBase64, int desiredChannels) {
        try {
            byte[] data = Base64.getDecoder().decode(dataBase64);
            PngDecoder.Decoded decoded = PngDecoder.decode(data, desiredChannels);
            BrowserDecodeWorkerTransport.respond(
                    id,
                    decoded.width(),
                    decoded.height(),
                    decoded.comp(),
                    Base64.getEncoder().encodeToString(decoded.pixels())
            );
        } catch (Throwable failure) {
            BrowserDecodeWorkerTransport.fail(
                    id,
                    failure.getClass().getName(),
                    failure.getMessage() == null ? "" : failure.getMessage()
            );
        }
    }
}
