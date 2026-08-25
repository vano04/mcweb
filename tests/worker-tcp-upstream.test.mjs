import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import { EventEmitter } from "node:events";
import {
  WORKER_RELAY_HANDSHAKE_TIMEOUT_MS,
  WORKER_RELAY_PENDING_WRITE_LIMIT,
  WorkerTcpUpstream,
} from "../tools/worker-tcp-upstream.mjs";

const SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

class FakeRequest extends EventEmitter {
  end() {}
  destroy() { this.destroyed = true; }
}

class FakeSocket extends EventEmitter {
  constructor() {
    super();
    this.writes = [];
    this.destroyed = false;
    this.noDelay = false;
  }

  setNoDelay(value) { this.noDelay = value; }
  write(bytes) { this.writes.push(Buffer.from(bytes)); return true; }
  end() { this.ended = true; }
  destroy() { this.destroyed = true; this.emit("close"); }
}

function serverFrame(payload, opcode = 0x2) {
  const bytes = Buffer.from(payload);
  if (bytes.length >= 126) throw new Error("test payload too large");
  return Buffer.concat([Buffer.from([0x80 | opcode, bytes.length]), bytes]);
}

function decodeClientFrame(frame) {
  let length = frame[1] & 0x7f;
  let cursor = 2;
  assert.equal(frame[1] & 0x80, 0x80);
  if (length === 126) {
    length = frame.readUInt16BE(cursor);
    cursor += 2;
  }
  const mask = frame.subarray(cursor, cursor + 4);
  cursor += 4;
  const payload = Buffer.alloc(length);
  for (let i = 0; i < length; i++) payload[i] = frame[cursor + i] ^ mask[i & 3];
  return { opcode: frame[0] & 0x0f, payload };
}

function makeHarness({
  handshakeTimeoutMs = WORKER_RELAY_HANDSHAKE_TIMEOUT_MS,
  host = "play.example.net",
  port = 25565,
  credential = SECRET,
  origin = "https://wasm.click",
  minecraftSession = null,
} = {}) {
  const requests = [];
  const socket = new FakeSocket();
  const options = [];
  const upstream = new WorkerTcpUpstream(
    "wss://tcp.wasm.click/mcweb/socket",
    host,
    port,
    credential,
    {
      requestImpl(requestOptions) {
        options.push(requestOptions);
        const request = new FakeRequest();
        requests.push(request);
        return request;
      },
      handshakeTimeoutMs,
      origin,
      minecraftSession,
    },
  );
  return { get request() { return requests.at(-1); }, requests, socket, options, upstream };
}

test("Worker upstream uses WSS, exact target query, and a header-only relay credential", async () => {
  const h = makeHarness();
  const events = [];
  h.upstream.on("connect", () => events.push("connect"));
  h.upstream.on("error", (error) => events.push(`error:${error.message}`));
  h.upstream.connect();
  const requestOptions = h.options[0];
  assert.equal(requestOptions.hostname, "tcp.wasm.click");
  assert.equal(requestOptions.port, 443);
  assert.equal(requestOptions.path, "/mcweb/socket?host=play.example.net&port=25565");
  assert.equal(requestOptions.headers.Authorization, `Bearer ${SECRET}`);
  assert.equal(requestOptions.headers.Origin, "https://wasm.click");
  assert.doesNotMatch(requestOptions.path, new RegExp(SECRET));
  assert.equal(requestOptions.headers.Host, "tcp.wasm.click");

  const key = requestOptions.headers["Sec-WebSocket-Key"];
  const accept = crypto.createHash("sha1").update(key + GUID).digest("base64");
  h.request.emit("upgrade", {
    statusCode: 101,
    headers: { "sec-websocket-accept": accept },
  }, h.socket, Buffer.alloc(0));
  assert.deepEqual(events, ["connect"]);

  const received = [];
  h.upstream.on("data", (bytes) => received.push(Buffer.from(bytes)));
  h.socket.emit("data", serverFrame([7, 8, 9]));
  assert.deepEqual(received, [Buffer.from([7, 8, 9])]);

  h.upstream.write(Buffer.from([1, 2, 3]));
  const sent = decodeClientFrame(h.socket.writes.at(-1));
  assert.equal(sent.opcode, 2);
  assert.deepEqual(sent.payload, Buffer.from([1, 2, 3]));
  h.upstream.destroy();
  assert.equal(h.socket.ended, true);
});

test("Worker upstream proves a Minecraft session without sending its token", async () => {
  const session = {
    profileId: "a".repeat(32), profileName: "VerifiedPlayer",
  };
  const h = makeHarness({
    credential: "", origin: "https://minecraft.wasm.click", minecraftSession: session,
  });
  h.upstream.connect();
  assert.equal(h.options[0].headers.Authorization, undefined);
  assert.equal(h.options[0].headers["X-MCWeb-Profile-Id"], session.profileId);
  assert.equal(h.options[0].headers["X-MCWeb-Profile-Name"], session.profileName);
  assert.equal(h.options[0].headers.Origin, "https://minecraft.wasm.click");
  const requestOptions = h.options[0];
  const accept = crypto.createHash("sha1")
    .update(requestOptions.headers["Sec-WebSocket-Key"] + GUID).digest("base64");
  h.request.emit("upgrade", {
    statusCode: 101,
    headers: { "sec-websocket-accept": accept },
  }, h.socket, Buffer.alloc(0));
  const serverId = `-${"b".repeat(39)}`;
  const authorized = h.upstream.authorizeMinecraftSession(serverId);
  const control = decodeClientFrame(h.socket.writes.at(-1));
  assert.equal(control.opcode, 1);
  assert.deepEqual(JSON.parse(control.payload.toString("utf8")), {
    type: "minecraft-session-proof",
    profileId: session.profileId,
    profileName: session.profileName,
    serverId,
  });
  h.socket.emit("data", serverFrame(Buffer.from(JSON.stringify({
    type: "minecraft-session-authorized",
  })), 0x1));
  await authorized;
});

test("Worker upstream fails closed on an invalid handshake", () => {
  const h = makeHarness();
  const errors = [];
  h.upstream.on("error", (error) => errors.push(error.message));
  h.upstream.connect();
  h.request.emit("upgrade", { statusCode: 200, headers: {} }, h.socket, Buffer.alloc(0));
  assert.match(errors[0], /handshake failed/);
  assert.equal(h.socket.destroyed, true);
});

test("Worker upstream reports only the relay's bounded authentication code", () => {
  const h = makeHarness();
  const errors = [];
  h.upstream.on("error", (error) => errors.push(error.message));
  h.upstream.connect();
  h.request.emit("response", {
    statusCode: 401,
    headers: { "x-mcweb-relay-auth": "profile-http-403" },
    resume() {},
  });
  assert.equal(errors[0], "worker relay HTTP rejection (401, profile-http-403)");
});

test("Worker upstream rejects non-WSS configuration before any request", async () => {
  const request = new FakeRequest();
  let called = false;
  const upstream = new WorkerTcpUpstream("http://tcp.wasm.click/mcweb/socket", "mc.belenko.dev", 25565, SECRET, {
    requestImpl() { called = true; return request; },
  });
  const errors = [];
  upstream.on("error", (error) => errors.push(error.message));
  upstream.connect();
  await new Promise((resolve) => queueMicrotask(resolve));
  assert.equal(called, false);
  assert.match(errors[0], /must use wss/);
});

test("Worker upstream accepts arbitrary valid targets and rejects malformed endpoints", () => {
  assert.doesNotThrow(
    () => new WorkerTcpUpstream("wss://tcp.wasm.click/mcweb/socket", "another.example", 25566, SECRET),
  );
  assert.throws(
    () => new WorkerTcpUpstream("wss://tcp.wasm.click/mcweb/socket", "bad host", 25565, SECRET),
    /valid Minecraft host and port/,
  );
  assert.throws(
    () => new WorkerTcpUpstream("wss://tcp.wasm.click/mcweb/socket", "play.example", 65536, SECRET),
    /valid Minecraft host and port/,
  );
});

test("Worker upstream bounds pre-handshake writes and destroys on overflow", () => {
  const h = makeHarness();
  const errors = [];
  h.upstream.on("error", (error) => errors.push(error.message));
  h.upstream.connect();
  // This is the bounded equivalent of a 20 MiB pre-handshake flood: the
  // existing gateway contract permits at most 1 MiB to queue before connect.
  assert.equal(h.upstream.write(Buffer.alloc(WORKER_RELAY_PENDING_WRITE_LIMIT)), true);
  assert.equal(h.upstream.write(Buffer.alloc(1)), false);
  assert.match(errors[0], /pre-handshake write buffer limit exceeded/);
  assert.equal(h.request.destroyed, true);
  assert.equal(h.upstream.pendingWriteBytes, 0);
});

test("Worker upstream destroys and emits on connection/handshake timeout", async () => {
  const h = makeHarness({ handshakeTimeoutMs: 10 });
  const errors = [];
  h.upstream.on("error", (error) => errors.push(error.message));
  h.upstream.connect();
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.match(errors[0], /connection\/handshake timeout/);
  assert.equal(h.request.destroyed, true);
  assert.equal(h.upstream.destroyed, true);
});

test("Worker upstream refuses a non-production host or path before sending the secret", async () => {
  for (const url of [
    "wss://evil.example/mcweb/socket",
    "wss://tcp.wasm.click/other",
  ]) {
    let called = false;
    const upstream = new WorkerTcpUpstream(url, "mc.belenko.dev", 25565, SECRET, {
      requestImpl() { called = true; return new FakeRequest(); },
    });
    const errors = [];
    upstream.on("error", (error) => errors.push(error.message));
    upstream.connect();
    await new Promise((resolve) => queueMicrotask(resolve));
    assert.equal(called, false);
    assert.match(errors[0], /tcp\.wasm\.click\/mcweb\/socket/);
  }
});
