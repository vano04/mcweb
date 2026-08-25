"use strict";

/**
 * Browser HTTP seam for Mojang's SkinTextureDownloader.
 *
 * The Java side keeps Minecraft's content-addressed cache, PNG decode, legacy
 * skin conversion and TextureManager registration. This object does only what
 * HttpURLConnection cannot do in Web Image: asynchronously fetch bytes, then
 * return them to the Java future as base64.
 */
globalThis.mcWebSkinFetch = globalThis.mcWebSkinFetch || (() => {
  let resultHandler = null;
  let failureHandler = null;
  let signatureResultHandler = null;
  let signatureFailureHandler = null;
  let started = 0;
  let completed = 0;
  let failed = 0;
  let signatureStarted = 0;
  let signatureCompleted = 0;
  let signatureFailed = 0;
  let lastError = null;
  let lastHost = null;
  let lastSignatureError = null;

  const report = (marker) => globalThis.mcWebGpu?.reportProgress?.(marker);

  function normalizeUrl(raw) {
    const url = new URL(String(raw), globalThis.location?.href);
    const protocolAllowed = url.protocol === "http:" || url.protocol === "https:";
    const loopback = url.hostname === "localhost"
      || url.hostname === "127.0.0.1"
      || url.hostname === "::1"
      || url.hostname === "[::1]"
      || /^127\./.test(url.hostname);
    if (!protocolAllowed || (url.hostname !== "textures.minecraft.net" && !loopback)) {
      throw new Error(`refusing non-Minecraft texture URL: ${url.origin}`);
    }
    // Signed texture payloads may still carry Mojang's historical http URL.
    // The CDN serves HTTPS, and upgrading avoids mixed-content failures when
    // the port itself is hosted securely.
    if (url.hostname === "textures.minecraft.net") url.protocol = "https:";
    return url;
  }

  function bytesToBase64(bytes) {
    let binary = "";
    const chunkSize = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
    }
    return btoa(binary);
  }

  function profileVerifierUrl() {
    const page = new URL(
      String(globalThis.location?.href || globalThis.location?.origin || "http://127.0.0.1/"),
      "http://127.0.0.1/",
    );
    const path = globalThis.mcWebConfig?.gateway?.profileVerificationPath
      || "/mcweb/verify-profile-property";
    const url = new URL(path, page);
    if (url.origin !== page.origin || url.username || url.password
        || !["http:", "https:"].includes(url.protocol)) {
      throw new Error(`refusing non-local profile verifier: ${url.origin}`);
    }
    url.search = "";
    url.hash = "";
    return url;
  }

  async function start(id, rawUrl) {
    started++;
    try {
      const url = normalizeUrl(rawUrl);
      lastHost = url.hostname;
      report(`skin-fetch:start:${id}:${url.hostname}`);
      const response = await fetch(url, { mode: "cors", cache: "force-cache" });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const bytes = new Uint8Array(await response.arrayBuffer());
      completed++;
      lastError = null;
      report(`skin-fetch:ok:${id}:bytes=${bytes.byteLength}`);
      resultHandler?.(id, bytesToBase64(bytes));
    } catch (error) {
      failed++;
      lastError = String(error?.message ?? error);
      report(`skin-fetch:failed:${id}:${lastError}`);
      failureHandler?.(id, lastError);
    }
  }

  /**
   * Asks the self-hosted same-origin verifier to perform authlib's RSA-SHA1 property check.
   * Only an explicit cryptographic `true` reaches Java as secure; transport,
   * key-refresh, malformed-input, and signature failures all remain insecure.
   */
  async function verifyProfileProperty(id, value, signature) {
    signatureStarted++;
    try {
      const url = profileVerifierUrl();
      report(`skin-signature:start:${id}`);
      const response = await fetch(url, {
        method: "POST",
        mode: "cors",
        cache: "no-store",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          value: String(value),
          signature: String(signature),
        }),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const payload = await response.json();
      const valid = payload?.valid === true;
      signatureCompleted++;
      lastSignatureError = null;
      report(`skin-signature:ok:${id}:valid=${valid}`);
      signatureResultHandler?.(id, valid);
    } catch (error) {
      signatureFailed++;
      lastSignatureError = String(error?.message ?? error);
      report(`skin-signature:failed:${id}:${lastSignatureError}`);
      signatureFailureHandler?.(id, lastSignatureError);
    }
  }

  return {
    start,
    onResult(handler) { resultHandler = handler; },
    onFailure(handler) { failureHandler = handler; },
    verifyProfileProperty,
    onSignatureResult(handler) { signatureResultHandler = handler; },
    onSignatureFailure(handler) { signatureFailureHandler = handler; },
    info() {
      return {
        started, completed, failed, lastError, lastHost,
        signatureStarted, signatureCompleted, signatureFailed,
        lastSignatureError,
      };
    }
  };
})();
