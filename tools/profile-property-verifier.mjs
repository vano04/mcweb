import crypto from "node:crypto";

export const DEFAULT_PUBLIC_KEYS_URL =
  "https://api.minecraftservices.com/publickeys";

const SIX_HOURS_MS = 6 * 60 * 60 * 1000;

/**
 * Verifies Mojang's signed GameProfile properties on behalf of the browser.
 *
 * Authlib normally performs this work with java.security after refreshing the
 * service key set every six hours. Web Image has neither the required crypto
 * implementation nor a background key-fetcher thread, while the localhost
 * relay already owns the online-mode crypto boundary. Keeping the same key
 * source, algorithm, and refresh interval here preserves authlib's trust
 * decision without exposing account credentials to the page.
 */
export class ProfilePropertyVerifier {
  constructor({
    publicKeysUrl = DEFAULT_PUBLIC_KEYS_URL,
    fetchImpl = globalThis.fetch,
    now = () => Date.now(),
    cacheMs = SIX_HOURS_MS,
  } = {}) {
    if (typeof fetchImpl !== "function") {
      throw new TypeError("ProfilePropertyVerifier requires fetch");
    }
    this.publicKeysUrl = String(publicKeysUrl);
    this.fetchImpl = fetchImpl;
    this.now = now;
    this.cacheMs = cacheMs;
    this.cachedKeys = null;
    this.cachedUntil = 0;
    this.pendingKeys = null;
  }

  /** Returns false for missing, malformed, or cryptographically invalid data. */
  async verify(value, signatureBase64) {
    if (typeof value !== "string" || typeof signatureBase64 !== "string"
        || signatureBase64.length === 0) {
      return false;
    }

    let signature;
    try {
      signature = Buffer.from(signatureBase64, "base64");
    } catch {
      return false;
    }
    if (signature.length === 0) return false;

    let keys;
    try {
      keys = await this.getKeys();
    } catch {
      return false;
    }

    const bytes = Buffer.from(value, "utf8");
    return keys.some((key) => {
      try {
        // This is the algorithm used by YggdrasilServicesKeyInfo in authlib.
        return crypto.verify("RSA-SHA1", bytes, key, signature);
      } catch {
        return false;
      }
    });
  }

  async getKeys() {
    const now = this.now();
    if (this.cachedKeys && now < this.cachedUntil) return this.cachedKeys;
    if (this.pendingKeys) return this.pendingKeys;

    this.pendingKeys = this.fetchKeys();
    try {
      const keys = await this.pendingKeys;
      this.cachedKeys = keys;
      this.cachedUntil = this.now() + this.cacheMs;
      return keys;
    } finally {
      this.pendingKeys = null;
    }
  }

  async fetchKeys() {
    const response = await this.fetchImpl(this.publicKeysUrl, {
      headers: { accept: "application/json" },
    });
    if (!response.ok) {
      throw new Error(`public key request returned HTTP ${response.status}`);
    }
    const payload = await response.json();
    const encodedKeys = Array.isArray(payload?.profilePropertyKeys)
      ? payload.profilePropertyKeys.map((entry) => entry?.publicKey)
      : [];
    const keys = encodedKeys.flatMap((encoded) => {
      if (typeof encoded !== "string" || encoded.length === 0) return [];
      try {
        if (encoded.includes("BEGIN PUBLIC KEY")) {
          return [crypto.createPublicKey(encoded)];
        }
        return [crypto.createPublicKey({
          key: Buffer.from(encoded, "base64"),
          format: "der",
          type: "spki",
        })];
      } catch {
        return [];
      }
    });
    if (keys.length === 0) {
      throw new Error("public key response contained no usable profile keys");
    }
    return keys;
  }
}
