"use strict";

// Safari 26.6's WebTransport/Streams bindings are stricter about optional
// arguments than Chromium's bindings. Keep the calls that cross that boundary
// in one tiny, testable shim: an explicit undefined still means “no reason”,
// while satisfying WebKit builds that reject a zero-argument invocation.
(function installStreamCompat(global) {
  const closeTransport = (transport) => {
    if (transport && typeof transport.close === "function") transport.close({});
  };
  const cancelReader = (reader) => {
    if (!reader || typeof reader.cancel !== "function") return Promise.resolve();
    return reader.cancel(undefined);
  };
  const closeWriter = (writer) => {
    if (!writer || typeof writer.close !== "function") return Promise.resolve();
    return writer.close(undefined);
  };
  global.mcWebSafariStreams = Object.freeze({ closeTransport, cancelReader, closeWriter });
})(globalThis);
