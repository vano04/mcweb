"use strict";

/**
 * Remote-server transport: the page's half of multiplayer.
 *
 * A browser cannot open TCP, so the app origin exposes a WebSocket gateway that
 * terminates Minecraft's VarInt framing and speaks whole packet bodies. The
 * standard server embeds the bridge; a separate URL remains a diagnostic
 * override. This exposes those bodies to Java with
 * exactly the shape `mcWebServer` already uses for the server Worker —
 * `sendPacket64` / `drainPackets64`, the latter returning one batch of
 * 4-byte-big-endian length-prefixed frames — so the Java side is a transport
 * swap rather than a new protocol path.
 *
 * Inbound is queued and drained once per client frame for the same reason the
 * Worker path is: a callback per packet costs a Java string materialisation and
 * a wasm crossing each, and inbound runs at thousands of packets per second.
 */
globalThis.mcWebNet = globalThis.mcWebNet || (() => {
  let socket = null;
  let state = "idle";
  let lastError = null;
  let sent = 0;
  let received = 0;
  let launcherIdentity = null;
  /** Complete packet bodies waiting for the next drain. */
  const inbound = [];
  let inboundBytes = 0;
  /**
   * Minecraft's zlib layer, once the gateway hands it over.
   *
   * The gateway used to inflate every server packet and forward the expanded
   * body, which made the process serving MC-Web send 5.7x the bytes it
   * received on a world load -- 2.15 MB in, 12.18 MB out -- all of it egress
   * to the player. It now forwards the server's own frames untouched and the
   * page inflates them here, where `DecompressionStream` is native code. The
   * outbound direction is unchanged: it is a fraction of the traffic and the
   * gateway still compresses it.
   */
  let compressionThreshold = -1;
  /** The gateway's answer-on-our-behalf contract; see publishKeepAlive. */
  let keepAlive = null;
  /** Inflation is async; this keeps packets in the order they arrived. */
  let inboundChain = Promise.resolve();
  let inflated = 0;

  function readVarIntAt(bytes, start) {
    let value = 0;
    let shift = 0;
    let offset = start;
    while (offset < bytes.length) {
      const byte = bytes[offset++];
      value |= (byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return { value: value >>> 0, next: offset };
      shift += 7;
      if (shift > 35) break;
    }
    return null;
  }

  async function inflate(bytes, expected) {
    const stream = new Blob([bytes]).stream()
      .pipeThrough(new DecompressionStream("deflate"));
    const out = new Uint8Array(await new Response(stream).arrayBuffer());
    if (out.length !== expected) {
      throw new Error(`packet inflated to ${out.length} bytes, expected ${expected}`);
    }
    return out;
  }

  /**
   * One compressed frame: an uncompressed-size VarInt, then the body. A zero
   * size means the packet was under the server's threshold and is stored raw,
   * which is the common case and costs no inflate at all.
   */
  function acceptCompressed(frame) {
    const size = readVarIntAt(frame, 0);
    if (!size) {
      lastError = "truncated compressed packet";
      state = "error";
      return;
    }
    const body = frame.subarray(size.next);
    if (size.value === 0) {
      queueInbound(Promise.resolve(body));
      return;
    }
    inflated++;
    queueInbound(inflate(body, size.value));
  }

  function queueInbound(pending) {
    inboundChain = inboundChain.then(() => pending).then((bytes) => {
      inbound.push(bytes);
      inboundBytes += bytes.length + 4;
    }, (error) => {
      lastError = String(error?.message ?? error);
      state = "error";
    });
  }

  const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  const WILDCARD_TARGET_POLICY = "*";

  function configuredRelayOverride() {
    // The local Node process owns the page and the gateway. Query-string relay
    // overrides are intentionally unsupported so a copied page cannot widen
    // the local target policy.
    return null;
  }

  function sameOriginGatewayUrl() {
    const socketPath = globalThis.mcWebConfig?.gateway?.socketPath || "/mcweb/socket";
    const url = new URL(socketPath, globalThis.location?.href);
    if (url.origin !== globalThis.location?.origin || url.pathname !== "/mcweb/socket") {
      throw new Error("local Node gateway path is invalid");
    }
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    url.search = "";
    url.hash = "";
    return url.toString();
  }

  function gatewayUrl() {
    const configured = configuredRelayOverride();
    return configured ? new URL(configured, globalThis.location?.href).toString()
      : sameOriginGatewayUrl();
  }

  function targetKey(host, port) {
    let normalizedHost = String(host ?? "").trim().toLowerCase();
    if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
      normalizedHost = normalizedHost.slice(1, -1);
    }
    const normalizedPort = Number(port);
    if (!normalizedHost || normalizedHost.length > 253 || /[\u0000-\u0020/?#%\\]/.test(normalizedHost)
        || !Number.isInteger(normalizedPort)
        || normalizedPort < 1 || normalizedPort > 65535) {
      return null;
    }
    if (normalizedHost.includes(":")) {
      // IPv6 server entries use [addr]:port in MC_RELAY_ALLOW. The browser
      // still accepts an unbracketed address from Minecraft's server screen.
      if (!/^[0-9a-f:.]+$/i.test(normalizedHost)) return null;
      return `[${normalizedHost}]:${normalizedPort}`;
    }
    if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$/i.test(normalizedHost)) return null;
    return `${normalizedHost}:${normalizedPort}`;
  }

  function unsafeLiteralHost(host) {
    let value = String(host ?? "").trim().toLowerCase();
    if (value.startsWith("[") && value.endsWith("]")) value = value.slice(1, -1);
    if (value === "localhost" || value.endsWith(".localhost") || value.endsWith(".local")
        || value === "metadata" || value === "metadata.google.internal"
        || value === "instance-data" || value.endsWith(".internal")) return true;
    if (/^127\./.test(value) || value === "0.0.0.0" || value.startsWith("10.")
        || value.startsWith("192.168.") || /^172\.(?:1[6-9]|2\d|3[01])\./.test(value)
        || /^169\.254\./.test(value) || /^100\.(?:6[4-9]|[78]\d|9\d|1[01]\d|12[0-7])\./.test(value)
        || /^192\.0\.0\.|^192\.0\.2\.|^198\.(?:18|19)\.|^198\.51\.100\.|^203\.0\.113\./.test(value)) return true;
    return value === "::" || value === "::1" || /^(?:fc|fd|fe[89a-f]|ff)/i.test(value);
  }

  /** Mirrors the serving process's advertised policy for immediate UI feedback. */
  function targetAllowed(host, port) {
    const target = targetKey(host, port);
    if (!target) return false;
    const gateway = globalThis.mcWebConfig?.gateway;
    const targets = Array.isArray(gateway?.allowedTargets)
      ? gateway.allowedTargets.map((entry) => String(entry).trim().toLowerCase()) : [];
    const wildcard = gateway?.allowAnyTarget === true && targets.length === 1
      && targets[0] === WILDCARD_TARGET_POLICY;
    if (wildcard) {
      const privateTargets = Array.isArray(gateway?.explicitlyAllowedPrivateTargets)
        ? gateway.explicitlyAllowedPrivateTargets.map((entry) => String(entry).trim().toLowerCase()) : [];
      return !unsafeLiteralHost(host) || privateTargets.includes(target);
    }
    return targets.includes(target);
  }

  function relayHttpUrl(legacyPath) {
    const url = new URL(globalThis.mcWebConfig?.gateway?.httpOrigin
      || globalThis.location?.href, globalThis.location?.href);
    if (url.origin !== globalThis.location?.origin
        || !["ws:", "wss:", "http:", "https:"].includes(url.protocol)) {
      throw new Error(`refusing cross-origin gateway metadata URL: ${url.origin}`);
    }
    if (url.protocol === "ws:") url.protocol = "http:";
    if (url.protocol === "wss:") url.protocol = "https:";
    url.pathname = `/mcweb${legacyPath}`;
    url.search = "";
    url.hash = "";
    return url;
  }

  function socketUrl(_relayUrl, host, port) {
    // The local Node process is the only gateway in this distribution. Ignore
    // the legacy relay argument rather than allowing Java/query state to select
    // a different origin.
    const url = new URL(gatewayUrl(), globalThis.location?.href);
    if (url.protocol !== "ws:" && url.protocol !== "wss:") {
      throw new Error(`invalid Minecraft gateway protocol: ${url.protocol}`);
    }
    const normalizedPort = Number(port ?? 25565);
    if (!targetAllowed(host, normalizedPort)) {
      throw new Error(`Minecraft server target is not allowed: ${host}:${port}`);
    }
    url.searchParams.set("host", String(host).trim());
    url.searchParams.set("port", String(normalizedPort));
    return url.toString();
  }

  function openGatewaySocket(url, host, port) {
    return new WebSocket(url);
  }

  async function loadLauncherIdentity() {
    if (globalThis.mcWebConfig?.auth?.mode !== "online") return null;
    const authSession = globalThis.mcWebAuthGate?.session?.();
    const authenticated = authSession?.authenticated === true ? authSession.profile : null;
    if (authSession?.cached) return null;
    if (authenticated) {
      const id = String(authenticated.id || "").replace(/-/g, "").toLowerCase();
      const name = String(authenticated.name || "");
      if (/^[0-9a-f]{32}$/.test(id) && name.length >= 1 && name.length <= 16) {
        launcherIdentity = { name, id };
        console.log(`[mcweb-net] authenticated profile ${name}`);
        return launcherIdentity;
      }
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 200);
    try {
      const response = await fetch(relayHttpUrl("/identity"), {
        mode: "cors",
        cache: "no-store",
        signal: controller.signal,
      });
      if (!response.ok) return null;
      const value = await response.json();
      const name = typeof value?.name === "string" ? value.name : "";
      const id = typeof value?.id === "string"
        ? value.id.replace(/-/g, "").toLowerCase()
        : "";
      if (!value?.available || !/^[0-9a-f]{32}$/.test(id)
          || name.length < 1 || name.length > 16) {
        return null;
      }
      launcherIdentity = { name, id };
      console.log(`[mcweb-net] authenticated profile ${name}`);
      return launcherIdentity;
    } catch {
      return null;
    } finally {
      clearTimeout(timeout);
    }
  }

  // Start while WebGPU initialises. webgpu-host awaits this bounded promise
  // immediately before loading the Web Image script, so GameConfig sees the
  // authenticated UUID without adding more than 200 ms when no relay exists.
  const launcherIdentityReady = loadLauncherIdentity();

  function bytesToBase64(bytes) {
    let out = "";
    for (let i = 0; i < bytes.length; i += 3) {
      const a = bytes[i];
      const b = i + 1 < bytes.length ? bytes[i + 1] : 0;
      const c = i + 2 < bytes.length ? bytes[i + 2] : 0;
      out += B64[a >> 2];
      out += B64[((a & 3) << 4) | (b >> 4)];
      out += i + 1 < bytes.length ? B64[((b & 15) << 2) | (c >> 6)] : "=";
      out += i + 2 < bytes.length ? B64[c & 63] : "=";
    }
    return out;
  }

  function base64ToBytes(text) {
    const clean = text.replace(/[^A-Za-z0-9+/]/g, "");
    const out = new Uint8Array((clean.length * 3) >> 2);
    let o = 0;
    for (let i = 0; i < clean.length; i += 4) {
      const a = B64.indexOf(clean[i]);
      const b = B64.indexOf(clean[i + 1]);
      const c = i + 2 < clean.length ? B64.indexOf(clean[i + 2]) : -1;
      const d = i + 3 < clean.length ? B64.indexOf(clean[i + 3]) : -1;
      out[o++] = (a << 2) | (b >> 4);
      if (c >= 0) out[o++] = ((b & 15) << 4) | (c >> 2);
      if (d >= 0) out[o++] = ((c & 3) << 6) | d;
    }
    return out.subarray(0, o);
  }

  /**
   * @param relayUrl optional diagnostic override; same-origin is the default
   * @param host,port the Minecraft server the relay should dial
   */
  function connect(relayUrl, host, port) {
    disconnect();
    lastError = null;
    sent = 0;
    received = 0;
    inbound.length = 0;
    inboundBytes = 0;
    compressionThreshold = -1;
    keepAlive = null;
    inboundChain = Promise.resolve();
    inflated = 0;
    let url;
    state = "connecting";
    try {
      url = socketUrl(relayUrl, host, port);
      socket = openGatewaySocket(url, host, port);
    } catch (error) {
      lastError = String(error);
      state = "error";
      return { error: lastError };
    }
    const openedSocket = socket;
    openedSocket.binaryType = "arraybuffer";
    openedSocket.onopen = () => {
      if (socket !== openedSocket) return;
      state = "open";
      console.log(`[mcweb-net] connected via ${new URL(url).origin} to ${host}:${port}`);
    };
    openedSocket.onmessage = (event) => {
      if (socket !== openedSocket) return;
      // A text frame is the gateway's control channel, sent exactly once as
      // the connection enters play: from the next binary frame on, packets
      // arrive in the server's own compressed framing.
      if (typeof event.data === "string") {
        try {
          compressionThreshold = Number(JSON.parse(event.data)?.compression ?? -1);
          console.log(`[mcweb-net] compression handed to the page`
            + ` (threshold ${compressionThreshold})`);
        } catch (error) {
          console.warn("[mcweb-net] unreadable gateway control frame", error);
        }
        return;
      }
      const bytes = new Uint8Array(event.data);
      received++;
      if (compressionThreshold < 0) {
        inbound.push(bytes);
        inboundBytes += bytes.length + 4;
        return;
      }
      acceptCompressed(bytes);
    };
    openedSocket.onerror = () => {
      if (socket !== openedSocket) return;
      lastError = "websocket error";
      state = "error";
    };
    openedSocket.onclose = (event) => {
      if (socket !== openedSocket) return;
      socket = null;
      const reason = String(event.reason || "").trim();
      // The integrated gateway sends a standards-compliant close reason for
      // platform failures (for example, a server requiring a different auth
      // prototyping mode). Preserve it for Minecraft's disconnect screen.
      if (reason) {
        lastError = reason;
        state = "error";
      } else if (state !== "error") {
        state = "closed";
      }
      console.log(`[mcweb-net] closed code=${event.code}`
        + `${reason ? ` reason=${reason}` : ""} sent=${sent} received=${received}`);
    };
    return { ok: true };
  }

  function disconnect() {
    const closing = socket;
    socket = null;
    if (closing) {
      try { closing.close(); } catch { /* already closing */ }
    }
    if (state === "open" || state === "connecting") state = "closed";
  }

  /** One encoded packet body from Java. */
  function sendPacket64(base64) {
    if (!socket || state !== "open") return false;
    socket.send(base64ToBytes(String(base64)));
    sent++;
    return true;
  }

  /**
   * Hands the gateway the keep-alive id for the current protocol phase.
   *
   * Applying a large server resource pack blocks the client thread for tens of
   * seconds, and the keep-alive that arrives during it goes unanswered until
   * long after the server has given up. The gateway is the only participant
   * still running, so it answers on the client's behalf — but only Java knows
   * the id, and only for the phase it is currently in. Sent as a text frame;
   * binary frames stay packets.
   */
  function publishKeepAlive(kind, id, length) {
    if (!socket || state !== "open") return false;
    keepAlive = { ...(keepAlive || {}), [String(kind)]: Number(id), length: Number(length) };
    // Both directions are needed before the gateway can act, and they arrive
    // as two separate protocol installs.
    if (keepAlive.clientbound === undefined || keepAlive.serverbound === undefined) {
      return true;
    }
    socket.send(JSON.stringify({ keepAlive }));
    return true;
  }

  /**
   * Every queued packet as one length-prefixed blob, matching the server-Worker
   * transport byte for byte so `Connection`'s batch reader is shared.
   */
  function drainPackets64() {
    if (inbound.length === 0) return "";
    const blob = new Uint8Array(inboundBytes);
    let offset = 0;
    for (const packet of inbound) {
      blob[offset] = (packet.length >>> 24) & 0xff;
      blob[offset + 1] = (packet.length >>> 16) & 0xff;
      blob[offset + 2] = (packet.length >>> 8) & 0xff;
      blob[offset + 3] = packet.length & 0xff;
      blob.set(packet, offset + 4);
      offset += packet.length + 4;
    }
    inbound.length = 0;
    inboundBytes = 0;
    return bytesToBase64(blob);
  }

  /**
   * Asks the client to join a server. The Java side polls this from its frame
   * pump so the connect runs on the client thread, where the packet listeners
   * and screens live, rather than inside a DOM callback.
   */
  let pendingJoin = null;
  function join(relay, host, port, name) {
    const normalizedPort = Number(port ?? 25565);
    if (!targetAllowed(host, normalizedPort)) {
      return { ok: false, error: `Minecraft server target is not allowed: ${host}:${normalizedPort}` };
    }
    pendingJoin = JSON.stringify({
      relay: String(relay || gatewayUrl()),
      host: String(host ?? "127.0.0.1"),
      port: normalizedPort,
      name: String(name ?? "WebPlayer")
    });
    return { ok: true };
  }
  function consumeJoin() {
    const request = pendingJoin;
    pendingJoin = null;
    return request;
  }

  function info() {
    return {
      state, sent, received, queued: inbound.length, lastError,
      compressionThreshold, inflated, keepAlive,
      launcherIdentity, gateway: gatewayUrl(),
    };
  }

  // -------------------------------------------------------------------------
  // Server-list status ping
  //
  // Separate from the play connection on purpose: this is the server list's
  // MOTD/player-count/latency probe, it runs while no game is in progress, and
  // it must not disturb `socket` if one is. The relay gives every WebSocket its
  // own TCP session, so each ping is its own short-lived socket that closes as
  // soon as it has an answer.
  //
  // The exchange is the whole status protocol: handshake with next-state 1,
  // then a status request, then one JSON response. The relay already routes
  // next-state 1 to "status" and leaves such a session alone — no login
  // rewrite, no encryption — so nothing here needs the account.
  // -------------------------------------------------------------------------

  const PING_TIMEOUT_MS = 5000;
  let pingSeq = 0;
  const pingResults = [];

  const varInt = (valueIn) => {
    let value = valueIn >>> 0;
    const out = [];
    do {
      let byte = value & 0x7f;
      value >>>= 7;
      if (value !== 0) byte |= 0x80;
      out.push(byte);
    } while (value !== 0);
    return out;
  };
  const readVarInt = (bytes, start) => {
    let value = 0;
    let shift = 0;
    let offset = start;
    while (offset < bytes.length) {
      const byte = bytes[offset++];
      value |= (byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return { value, next: offset };
      shift += 7;
      if (shift > 35) break;
    }
    return null;
  };

  /**
   * Starts a ping and returns its id. Results are collected rather than
   * delivered by callback so Java can apply them on the client thread, where
   * the screens live, instead of inside a socket event.
   */
  function pingStatus(relayUrl, host, port, protocolVersion) {
    const id = ++pingSeq;
    const finishWith = (result) => pingResults.push(
      Object.assign({ id, host: String(host), port: Number(port) }, result));

    let ws;
    try {
      ws = openGatewaySocket(socketUrl(relayUrl, host, port), host, port);
    } catch (error) {
      finishWith({ ok: false, error: String(error) });
      return id;
    }
    ws.binaryType = "arraybuffer";

    let settled = false;
    let requestedAt = 0;
    const settle = (result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      finishWith(result);
      try { ws.close(); } catch { /* already closing */ }
    };
    const timer = setTimeout(() => settle({ ok: false, error: "timeout" }), PING_TIMEOUT_MS);

    ws.onopen = () => {
      const address = new TextEncoder().encode(String(host));
      ws.send(new Uint8Array([
        ...varInt(0x00),                       // intention
        ...varInt(Number(protocolVersion) | 0),
        ...varInt(address.length), ...address,
        (Number(port) >> 8) & 0xff, Number(port) & 0xff,
        ...varInt(1)                           // next state: status
      ]));
      requestedAt = performance.now();
      ws.send(new Uint8Array(varInt(0x00)));   // status request
    };

    ws.onmessage = (event) => {
      const bytes = new Uint8Array(event.data);
      const packetId = readVarInt(bytes, 0);
      if (!packetId || packetId.value !== 0x00) return;
      const length = readVarInt(bytes, packetId.next);
      if (!length) return settle({ ok: false, error: "malformed status response" });
      const json = new TextDecoder().decode(
        bytes.subarray(length.next, length.next + length.value));
      // Latency is the status round trip rather than a separate ping/pong
      // exchange: one fewer round trip and one fewer way to hang, and on the
      // only host this build may reach the difference is under a millisecond.
      settle({ ok: true, json, latencyMs: Math.max(0, Math.round(performance.now() - requestedAt)) });
    };
    ws.onerror = () => settle({ ok: false, error: "websocket error" });
    ws.onclose = () => settle({ ok: false, error: "closed before responding" });
    return id;
  }

  /** Everything finished since the last call, as JSON. Empty string if none. */
  function consumePingResults() {
    if (pingResults.length === 0) return "";
    const batch = JSON.stringify(pingResults);
    pingResults.length = 0;
    return batch;
  }

  return { connect, disconnect, sendPacket64, drainPackets64, publishKeepAlive,
    join, consumeJoin, state: () => state, info,
    gatewayUrl, targetAllowed,
    identity: () => launcherIdentity,
    identityReady: () => launcherIdentityReady,
    pingStatus, consumePingResults };
})();
