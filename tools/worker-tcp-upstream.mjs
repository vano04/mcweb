import http from "node:http";
import https from "node:https";
import crypto from "node:crypto";
import { EventEmitter } from "node:events";

const WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
export const WORKER_RELAY_URL = "wss://tcp.wasm.click/mcweb/socket";
export const WORKER_RELAY_HOSTNAME = "tcp.wasm.click";
export const WORKER_RELAY_PATH = "/mcweb/socket";
export const WORKER_RELAY_MIN_SECRET_LENGTH = 32;
export const WORKER_RELAY_PENDING_WRITE_LIMIT = 1024 * 1024;
export const WORKER_RELAY_HANDSHAKE_TIMEOUT_MS = 10_000;

function decodeFrames(buffer) {
  const frames = [];
  let offset = 0;
  for (;;) {
    if (buffer.length - offset < 2) break;
    const first = buffer[offset];
    const second = buffer[offset + 1];
    const fin = (first & 0x80) !== 0;
    const opcode = first & 0x0f;
    const masked = (second & 0x80) !== 0;
    let length = second & 0x7f;
    let cursor = offset + 2;
    if (length === 126) {
      if (buffer.length - cursor < 2) break;
      length = buffer.readUInt16BE(cursor);
      cursor += 2;
    } else if (length === 127) {
      if (buffer.length - cursor < 8) break;
      const big = buffer.readBigUInt64BE(cursor);
      if (big > 0x7fffffffn) throw new Error("frame too large");
      length = Number(big);
      cursor += 8;
    }
    let mask = null;
    if (masked) {
      if (buffer.length - cursor < 4) break;
      mask = buffer.subarray(cursor, cursor + 4);
      cursor += 4;
    }
    if (buffer.length - cursor < length) break;
    const payload = Buffer.from(buffer.subarray(cursor, cursor + length));
    if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
    frames.push({ fin, opcode, payload });
    offset = cursor + length;
  }
  return { frames, rest: buffer.subarray(offset) };
}

/** Encodes a client-to-Worker binary frame. RFC 6455 requires client masking. */
function encodeMaskedFrame(payload, opcode = 0x2) {
  const bytes = Buffer.from(payload);
  const length = bytes.length;
  let header;
  if (length < 126) {
    header = Buffer.alloc(2);
    header[1] = 0x80 | length;
  } else if (length < 65536) {
    header = Buffer.alloc(4);
    header[1] = 0x80 | 126;
    header.writeUInt16BE(length, 2);
  } else {
    header = Buffer.alloc(10);
    header[1] = 0x80 | 127;
    header.writeBigUInt64BE(BigInt(length), 2);
  }
  header[0] = 0x80 | opcode;
  const mask = crypto.randomBytes(4);
  const masked = Buffer.allocUnsafe(bytes.length);
  for (let index = 0; index < bytes.length; index++) {
    masked[index] = bytes[index] ^ mask[index & 3];
  }
  return Buffer.concat([header, mask, masked]);
}

/**
 * A small dependency-free RFC 6455 client used only for the local-gateway ->
 * Cloudflare leg. It presents the same `net.Socket`-like events used by the
 * existing gateway, but carries raw TCP bytes as binary WebSocket messages.
 * The Worker authenticates this handshake with either an operator secret or a
 * short-lived signed session proof. The player's Minecraft token stays local;
 * neither credential enters the URL or logs.
 */
export class WorkerTcpUpstream extends EventEmitter {
  constructor(workerUrl, targetHost, targetPort, relaySecret, {
    requestImpl = null,
    origin = "https://wasm.click",
    minecraftSession = null,
    handshakeTimeoutMs = WORKER_RELAY_HANDSHAKE_TIMEOUT_MS,
  } = {}) {
    super();
    let normalizedTargetHost = String(targetHost || "").trim().toLowerCase();
    if (normalizedTargetHost.startsWith("[") && normalizedTargetHost.endsWith("]")) {
      normalizedTargetHost = normalizedTargetHost.slice(1, -1);
    }
    const normalizedTargetPort = Number(targetPort);
    const validHost = normalizedTargetHost.length > 0
      && normalizedTargetHost.length <= 253
      && !/[\u0000-\u0020/?#%\\]/.test(normalizedTargetHost)
      && (/^[0-9a-f:.]+$/i.test(normalizedTargetHost)
        || /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$/i.test(normalizedTargetHost));
    if (!validHost || !Number.isInteger(normalizedTargetPort)
        || normalizedTargetPort < 1 || normalizedTargetPort > 65535) {
      throw new Error("worker relay target must be a valid Minecraft host and port");
    }
    this.workerUrl = String(workerUrl || "");
    this.targetHost = normalizedTargetHost;
    this.targetPort = normalizedTargetPort;
    this.relaySecret = String(relaySecret || "");
    this.requestImpl = requestImpl;
    this.origin = origin;
    this.minecraftSession = minecraftSession;
    this.sessionAuthorization = null;
    this.sessionAuthorizationTimer = null;
    this.handshakeTimeoutMs = Number.isFinite(Number(handshakeTimeoutMs))
      && Number(handshakeTimeoutMs) > 0
      ? Number(handshakeTimeoutMs) : WORKER_RELAY_HANDSHAKE_TIMEOUT_MS;
    this.request = null;
    this.socket = null;
    this.handshakeTimer = null;
    this.wsBuffer = Buffer.alloc(0);
    this.fragments = [];
    this.fragmentOpcode = 0x2;
    this.pendingWrites = [];
    this.pendingWriteBytes = 0;
    this.destroyed = false;
    this.connected = false;
    this.noDelay = true;
  }

  setNoDelay(value = true) {
    this.noDelay = Boolean(value);
    this.socket?.setNoDelay?.(this.noDelay);
    return this;
  }

  clearHandshakeTimeout() {
    if (this.handshakeTimer === null) return;
    clearTimeout(this.handshakeTimer);
    this.handshakeTimer = null;
  }

  connect() {
    let endpoint;
    try {
      endpoint = new URL(this.workerUrl);
      if (endpoint.protocol !== "wss:") {
        throw new Error("worker relay URL must use wss:");
      }
      if (endpoint.hostname !== WORKER_RELAY_HOSTNAME
          || endpoint.pathname !== WORKER_RELAY_PATH
          || (endpoint.port && endpoint.port !== "443")
          || endpoint.username || endpoint.password) {
        throw new Error("worker relay URL must be tcp.wasm.click/mcweb/socket");
      }
      if (endpoint.search || endpoint.hash) {
        throw new Error("worker relay URL must not contain query or fragment data");
      }
      endpoint.searchParams.set("host", this.targetHost);
      endpoint.searchParams.set("port", String(this.targetPort));
    } catch (error) {
      queueMicrotask(() => this.emit("error", error));
      return this;
    }
    if (!this.minecraftSession && this.relaySecret.length < WORKER_RELAY_MIN_SECRET_LENGTH) {
      queueMicrotask(() => this.emit("error", new Error("worker relay secret is missing or too short")));
      return this;
    }
    const requestImpl = this.requestImpl || (endpoint.protocol === "wss:" ? https.request : http.request);
    const port = endpoint.port ? Number(endpoint.port) : (endpoint.protocol === "wss:" ? 443 : 80);
    const key = crypto.randomBytes(16).toString("base64");
    const expectedAccept = crypto.createHash("sha1")
      .update(key + WS_GUID).digest("base64");
    this.handshakeTimer = setTimeout(() => {
      this.fail(new Error("worker relay connection/handshake timeout"));
    }, this.handshakeTimeoutMs);
    this.handshakeTimer.unref?.();
    let request;
    try {
      request = requestImpl({
        hostname: endpoint.hostname,
        port,
        path: `${endpoint.pathname}${endpoint.search}`,
        method: "GET",
        headers: {
          Host: endpoint.host,
          Upgrade: "websocket",
          Connection: "Upgrade",
          "Sec-WebSocket-Key": key,
          "Sec-WebSocket-Version": "13",
          // Session mode sends only public profile metadata. The real vanilla
          // server hash is proved over the upgraded control channel later.
          ...(this.minecraftSession ? {
            "X-MCWeb-Profile-Id": String(this.minecraftSession.profileId || ""),
            "X-MCWeb-Profile-Name": String(this.minecraftSession.profileName || ""),
          } : { Authorization: `Bearer ${this.relaySecret}` }),
          Origin: this.origin,
        },
      });
    } catch (error) {
      this.fail(error);
      return this;
    }
    if (!request || typeof request.once !== "function" || typeof request.end !== "function") {
      this.fail(new Error("worker relay request implementation is invalid"));
      return this;
    }
    this.request = request;
    request.once("upgrade", (response, socket, head) => {
      this.clearHandshakeTimeout();
      const accept = response.headers["sec-websocket-accept"];
      if (response.statusCode !== 101 || accept !== expectedAccept) {
        socket.destroy();
        this.fail(new Error(`worker relay handshake failed (${response.statusCode || "no status"})`));
        return;
      }
      if (this.destroyed) {
        socket.destroy();
        return;
      }
      this.socket = socket;
      this.connected = true;
      socket.setNoDelay?.(this.noDelay);
      socket.on("data", (chunk) => this.handleData(chunk));
      socket.on("error", (error) => this.fail(error));
      socket.on("close", () => this.emitClose());
      this.emit("connect");
      if (head?.length) this.handleData(head);
      const pending = this.pendingWrites.splice(0);
      this.pendingWriteBytes = 0;
      try {
        for (const bytes of pending) this.write(bytes);
      } catch (error) {
        this.fail(error);
      }
    });
    request.once("response", (response) => {
      // A normal HTTP response means the endpoint rejected the upgrade. Drain
      // it so Node can release the connection, then report a bounded error.
      response.resume?.();
      if (!this.connected) {
        const authCode = String(response.headers?.["x-mcweb-relay-auth"] || "");
        const safeAuthCode = /^[a-z0-9-]{1,64}$/.test(authCode) ? `, ${authCode}` : "";
        this.fail(new Error(`worker relay HTTP rejection (${response.statusCode || "unknown"}${safeAuthCode})`));
      }
    });
    request.once("error", (error) => this.fail(error));
    request.end();
    return this;
  }

  fail(error) {
    if (this.destroyed) return;
    this.emit("error", error instanceof Error ? error : new Error(String(error)));
    this.destroy();
  }

  handleData(chunk) {
    if (this.destroyed) return;
    this.wsBuffer = Buffer.concat([this.wsBuffer, chunk]);
    let decoded;
    try {
      decoded = decodeFrames(this.wsBuffer);
    } catch (error) {
      this.fail(new Error(`worker relay frame error: ${error.message}`));
      return;
    }
    this.wsBuffer = decoded.rest;
    for (const frame of decoded.frames) {
      if (frame.opcode === 0x8) {
        if (this.socket && !this.destroyed) {
          this.socket.write(encodeMaskedFrame(frame.payload, 0x8));
          this.socket.end();
        }
        this.emitClose();
        return;
      }
      if (frame.opcode === 0x9) {
        this.socket?.write(encodeMaskedFrame(frame.payload, 0xa));
        continue;
      }
      if (frame.opcode === 0xa) continue;
      if (frame.opcode !== 0x0 && frame.opcode !== 0x1 && frame.opcode !== 0x2) {
        this.fail(new Error("worker relay sent unsupported WebSocket opcode"));
        return;
      }
      if (frame.opcode !== 0x0) this.fragmentOpcode = frame.opcode;
      this.fragments.push(frame.payload);
      if (!frame.fin) continue;
      const payload = this.fragments.length === 1
        ? this.fragments[0] : Buffer.concat(this.fragments);
      const opcode = this.fragmentOpcode;
      this.fragments = [];
      if (opcode === 0x1) {
        let control;
        try { control = JSON.parse(payload.toString("utf8")); } catch { control = null; }
        if (control?.type !== "minecraft-session-authorized" || !this.sessionAuthorization) {
          this.fail(new Error("worker relay sent an invalid control frame"));
          return;
        }
        clearTimeout(this.sessionAuthorizationTimer);
        this.sessionAuthorizationTimer = null;
        const { resolve } = this.sessionAuthorization;
        this.sessionAuthorization = null;
        resolve();
        continue;
      }
      if (opcode !== 0x2) {
        this.fail(new Error("worker relay sent an unsupported data frame"));
        return;
      }
      this.emit("data", payload);
    }
  }

  authorizeMinecraftSession(serverId) {
    const hash = String(serverId || "");
    if (!this.minecraftSession || !/^-?[0-9a-f]{1,40}$/.test(hash)) {
      return Promise.reject(new Error("invalid Minecraft session proof request"));
    }
    if (!this.socket || !this.connected || this.destroyed) {
      return Promise.reject(new Error("worker relay is not connected for session proof"));
    }
    if (this.sessionAuthorization) {
      return Promise.reject(new Error("Minecraft session proof is already pending"));
    }
    const proof = JSON.stringify({
      type: "minecraft-session-proof",
      profileId: String(this.minecraftSession.profileId || "").replaceAll("-", "").toLowerCase(),
      profileName: String(this.minecraftSession.profileName || ""),
      serverId: hash,
    });
    return new Promise((resolve, reject) => {
      this.sessionAuthorization = { resolve, reject };
      this.sessionAuthorizationTimer = setTimeout(() => {
        if (!this.sessionAuthorization) return;
        this.sessionAuthorization = null;
        reject(new Error("worker relay Minecraft session proof timeout"));
        this.destroy();
      }, this.handshakeTimeoutMs);
      this.sessionAuthorizationTimer.unref?.();
      try { this.socket.write(encodeMaskedFrame(Buffer.from(proof, "utf8"), 0x1)); }
      catch (error) {
        clearTimeout(this.sessionAuthorizationTimer);
        this.sessionAuthorizationTimer = null;
        this.sessionAuthorization = null;
        reject(error);
      }
    });
  }

  write(bytes) {
    const payload = Buffer.from(bytes);
    if (this.destroyed) return false;
    if (!this.socket || !this.connected) {
      if (this.pendingWriteBytes + payload.length > WORKER_RELAY_PENDING_WRITE_LIMIT) {
        this.fail(new Error("worker relay pre-handshake write buffer limit exceeded"));
        return false;
      }
      this.pendingWrites.push(payload);
      this.pendingWriteBytes += payload.length;
      return true;
    }
    return this.socket.write(encodeMaskedFrame(payload));
  }

  emitClose() {
    if (this.destroyed) return;
    this.clearHandshakeTimeout();
    this.destroyed = true;
    this.connected = false;
    this.pendingWrites = [];
    this.pendingWriteBytes = 0;
    clearTimeout(this.sessionAuthorizationTimer);
    this.sessionAuthorizationTimer = null;
    this.sessionAuthorization?.reject(new Error("worker relay closed before Minecraft session proof"));
    this.sessionAuthorization = null;
    this.emit("close");
  }

  destroy() {
    if (this.destroyed) return this;
    this.clearHandshakeTimeout();
    this.destroyed = true;
    this.connected = false;
    this.pendingWrites = [];
    this.pendingWriteBytes = 0;
    clearTimeout(this.sessionAuthorizationTimer);
    this.sessionAuthorizationTimer = null;
    this.sessionAuthorization?.reject(new Error("worker relay closed before Minecraft session proof"));
    this.sessionAuthorization = null;
    try {
      if (this.socket && !this.socket.destroyed) {
        this.socket.write(encodeMaskedFrame(Buffer.alloc(0), 0x8));
        this.socket.end();
      }
    } catch { /* already gone */ }
    try { this.request?.destroy?.(); } catch { /* already gone */ }
    this.emit("close");
    return this;
  }
}
