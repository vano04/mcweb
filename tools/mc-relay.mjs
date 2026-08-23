/**
 * Minecraft WebSocket <-> TCP bridge for talking to a real server from a browser.
 *
 * A browser cannot open a TCP socket, so multiplayer needs something to carry the
 * protocol. This bridge is deliberately thin: it terminates Minecraft's *framing*
 * and speaks to the page in whole packets, one binary WebSocket message each.
 *
 *   page -> bridge : one uncompressed packet body (packet id VarInt + payload)
 *   bridge -> server: VarInt length + that body
 *   server -> bridge: VarInt length + body
 *   bridge -> page : that body, as one binary message
 *
 * That is exactly the shape the port already uses internally — `PacketWire.encode`
 * produces the packet body, and `Connection` moves `byte[]` frames around — so the
 * Java side needs a transport swap rather than a protocol implementation.
 *
 * Framing lives here rather than in the image on purpose: once compression is
 * enabled a Minecraft stream is zlib, while the browser transport deliberately
 * exchanges uncompressed whole packets. The gateway terminates that compression
 * (and online-mode encryption) using Node's platform implementations.
 *
 * Usage:
 *   node tools/mc-relay.mjs [--port 25585]
 *   ws://127.0.0.1:25585/?host=play.example.net&port=25565
 */
import http from "node:http";
import net from "node:net";
import dns from "node:dns";
import crypto from "node:crypto";
import zlib from "node:zlib";
import { pathToFileURL } from "node:url";
import {
  DEFAULT_PUBLIC_KEYS_URL,
  ProfilePropertyVerifier,
} from "./profile-property-verifier.mjs";
import {
  LAUNCHER_ACCOUNTS_ENV,
  launcherAccountCandidates,
  parseLauncherDocument,
  readLauncherCredentialsCandidates,
  validateLauncherCredentials,
} from "./launcher-auth.mjs";

const WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
/**
 * How long the page may go silent before this process answers keep-alives for
 * it. A healthy client sends something many times a second; only a blocked one
 * -- applying a big resource pack -- is quiet for this long.
 */
const PAGE_STALL_MS = 5000;
const IS_MAIN = Boolean(process.argv[1])
  && import.meta.url === pathToFileURL(process.argv[1]).href;
const args = process.argv.slice(2);
const readFlag = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const PORT = Number(readFlag("port", process.env.MC_RELAY_PORT ?? 25585));
const requestedAuthMode = readFlag("auth-mode", process.env.MCWEB_AUTH_MODE ?? "online");
if (requestedAuthMode !== "online") {
  throw new Error(
    "local MC-Web requires an authenticated official Minecraft Launcher session; unauthenticated mode is not supported",
  );
}
const AUTH_MODE = "online";
const AUTH_PROVIDER = "official-launcher-or-prism";
/**
 * Explicit precedence: --launcher-accounts, MCWEB_LAUNCHER_ACCOUNTS, then the
 * platform's official launcher location. The selected path stays server-side.
 */
const ACCOUNT_PATHS = launcherAccountCandidates({
  override: readFlag("launcher-accounts", process.env[LAUNCHER_ACCOUNTS_ENV] ?? ""),
});
const AUTH_VALIDATION_CACHE_MS = 30_000;
let validatedCredentialsCache = null;
// A browser-selected launcher account document may replace the file
// discovered at startup for this process. The parsed credential is held only
// in memory; it is never written to disk, returned to the page, or logged.
let uploadedLauncherCredentials = null;
// A failed replacement upload invalidates the previous in-memory session and
// suppresses autodiscovery until a new document validates successfully. This
// prevents /auth/session from silently reviving the credential the user just
// replaced with a bad file.
let launcherUploadInvalidated = false;
const PUBLIC_KEYS_URL = readFlag(
  "publickeys",
  process.env.MC_PUBLIC_KEYS_URL ?? DEFAULT_PUBLIC_KEYS_URL,
);
/** `*` is the shareable default; exact host:port entries narrow it. */
export const DEFAULT_MINECRAFT_TARGETS = "*";

function normaliseHost(host) {
  let value = String(host ?? "").trim().toLowerCase();
  if (value.startsWith("[") && value.endsWith("]")) value = value.slice(1, -1);
  if (!value || value.length > 253 || /[\u0000-\u0020/?#%\\]/.test(value)) return null;
  if (net.isIP(value) === 6) return canonicalIpv6(value);
  if (net.isIP(value) === 4) return value;
  // Minecraft's server address is a DNS name or IP literal, not a URL, SRV
  // record, Unix socket, or shell expression. Keep the grammar deliberately
  // narrow so the relay never interprets user input as another protocol.
  if (value.startsWith(".") || value.endsWith(".") || value.includes("..")) return null;
  if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$/.test(value)) return null;
  return value;
}

/** Ports are a text capability boundary: only canonical decimal text is valid. */
function parseMinecraftPort(value) {
  const text = String(value ?? "");
  if (!/^[1-9][0-9]{0,4}$/.test(text)) return null;
  const port = Number(text);
  return port >= 1 && port <= 65535 ? port : null;
}

function normaliseEndpoint(host, port) {
  const normalizedHost = normaliseHost(host);
  const normalizedPort = parseMinecraftPort(port);
  if (!normalizedHost || normalizedPort === null) return null;
  const target = net.isIP(normalizedHost) === 6
    ? `[${normalizedHost}]:${normalizedPort}`
    : `${normalizedHost}:${normalizedPort}`;
  return { host: normalizedHost, port: normalizedPort, target };
}

function parseTargetEntry(entry) {
  const raw = String(entry ?? "").toLowerCase();
  if (!raw || raw === "*") return raw === "*" ? { wildcard: true } : null;
  if (/\s/.test(raw)) return null;
  let host;
  let port;
  if (raw.startsWith("[")) {
    const close = raw.indexOf("]");
    if (close < 0 || raw[close + 1] !== ":") return null;
    host = raw.slice(1, close);
    port = raw.slice(close + 2);
  } else {
    const split = raw.lastIndexOf(":");
    if (split <= 0 || raw.indexOf(":") !== split) return null;
    host = raw.slice(0, split);
    port = raw.slice(split + 1);
  }
  const endpoint = normaliseEndpoint(host, port);
  return endpoint ? { ...endpoint, wildcard: false } : null;
}

function ipv4Number(value) {
  if (net.isIP(value) !== 4) return null;
  return value.split(".").reduce((out, part) => (out * 256) + Number(part), 0) >>> 0;
}

function unsafeIpv4Number(value) {
  const first = value >>> 24;
  const second = (value >>> 16) & 0xff;
  return first === 0 || first === 10 || first === 127 || first >= 224
    || (first === 100 && second >= 64 && second <= 127)
    || (first === 169 && second === 254)
    || (first === 172 && second >= 16 && second <= 31)
    || (first === 192 && second === 0 && ((value >>> 8) & 0xff) === 0)
    || (first === 192 && second === 0 && ((value >>> 8) & 0xff) === 2)
    || (first === 192 && second === 168)
    || (first === 198 && second === 18)
    || (first === 198 && second === 19)
    || (first === 198 && second === 51 && ((value >>> 8) & 0xff) === 100)
    || (first === 203 && second === 0 && ((value >>> 8) & 0xff) === 113);
}

/** Parse a validated IPv6 literal into eight numeric 16-bit words. */
function ipv6Words(value) {
  if (net.isIP(value) !== 6) return null;
  const parts = value.toLowerCase().split("::");
  if (parts.length > 2) return null;

  const parseSide = (side) => {
    if (!side) return [];
    const pieces = side.split(":");
    const last = pieces[pieces.length - 1];
    if (last.includes(".")) {
      if (net.isIP(last) !== 4) return null;
      const number = ipv4Number(last);
      pieces.splice(pieces.length - 1, 1,
        (number >>> 16).toString(16), (number & 0xffff).toString(16));
    }
    if (pieces.some((piece) => !/^[0-9a-f]{1,4}$/.test(piece))) return null;
    return pieces.map((piece) => Number.parseInt(piece, 16));
  };

  const left = parseSide(parts[0]);
  const right = parseSide(parts.length === 2 ? parts[1] : "");
  if (!left || !right) return null;
  if (parts.length === 1) return left.length === 8 ? left : null;
  const missing = 8 - left.length - right.length;
  return missing >= 1 ? [...left, ...Array(missing).fill(0), ...right] : null;
}

function canonicalIpv6(value) {
  const words = ipv6Words(value);
  if (!words) return null;
  let bestStart = -1;
  let bestLength = 0;
  for (let start = 0; start < words.length;) {
    if (words[start] !== 0) {
      start++;
      continue;
    }
    let end = start;
    while (end < words.length && words[end] === 0) end++;
    if (end - start > bestLength && end - start >= 2) {
      bestStart = start;
      bestLength = end - start;
    }
    start = end;
  }
  if (bestLength === 8) return "::";
  if (bestStart < 0) return words.map((word) => word.toString(16)).join(":");
  const left = words.slice(0, bestStart).map((word) => word.toString(16)).join(":");
  const right = words.slice(bestStart + bestLength).map((word) => word.toString(16)).join(":");
  if (!left) return `::${right}`;
  if (!right) return `${left}::`;
  return `${left}::${right}`;
}

/** Addresses that must not be reached by a wildcard browser proxy. */
export function isUnsafeMinecraftAddress(address) {
  const value = String(address ?? "").trim().toLowerCase();
  const v4 = ipv4Number(value);
  if (v4 !== null) return unsafeIpv4Number(v4);

  const words = ipv6Words(value);
  if (!words) return false;
  const first = words[0];
  const second = words[1];
  const third = words[2];
  const compatible = words.slice(0, 6).every((word) => word === 0);
  const mapped = words.slice(0, 5).every((word) => word === 0) && words[5] === 0xffff;
  if (mapped) return unsafeIpv4Number(((words[6] << 16) | words[7]) >>> 0);
  // ::/96 is unspecified/IPv4-compatible space. Keep only IPv4-mapped
  // literals above; the deprecated compatible range is not a public target.
  if (compatible) return true;

  // ULA, link-local, deprecated site-local, and multicast ranges.
  if ((first & 0xfe00) === 0xfc00
      || (first & 0xffc0) === 0xfe80
      || (first & 0xffc0) === 0xfec0
      || (first & 0xff00) === 0xff00) return true;
  // Documentation, benchmarking, ORCHID, discard-only, and 6bone ranges.
  if ((first === 0x2001 && second === 0x0db8)
      || (first === 0x2001 && second === 0x0002 && third === 0)
      || (first === 0x2001 && (second & 0xfff0) === 0x0010)
      || (first === 0x2001 && (second & 0xfff0) === 0x0020)
      || (first === 0x0100 && second === 0 && third === 0 && words[3] === 0)
      || first === 0x3ffe) return true;
  return false;
}

export function isUnsafeMinecraftHost(host) {
  const normalized = normaliseHost(host);
  if (!normalized) return true;
  if (isUnsafeMinecraftAddress(normalized)) return true;
  return normalized === "localhost" || normalized.endsWith(".localhost")
    || normalized === "metadata" || normalized === "metadata.google.internal"
    || normalized === "instance-data" || normalized.endsWith(".internal")
    || normalized.endsWith(".local");
}

/**
 * Parses one local target capability for both the app config and TCP boundary.
 * `*` permits syntactically valid public targets. Exact `host:port` entries
 * narrow the policy and are also the explicit opt-in for a private destination.
 * Any mixed wildcard/list or malformed list fails closed.
 */
export function minecraftTargetPolicy(spec = DEFAULT_MINECRAFT_TARGETS) {
  const entries = String(spec).split(",");
  const hasEmptyEntry = entries.some((entry) => entry.length === 0);
  const parsed = hasEmptyEntry ? [] : entries.map(parseTargetEntry);
  const valid = !hasEmptyEntry && entries.length > 0 && parsed.every(Boolean)
    && (parsed.length === 1 || parsed.every((entry) => !entry.wildcard));
  const wildcard = valid && parsed.length === 1 && parsed[0].wildcard;
  const exact = new Set(valid && !wildcard ? parsed.map((entry) => entry.target) : []);
  const allowedTargets = valid ? (wildcard ? ["*"] : [...exact]) : [];
  return {
    valid,
    wildcard,
    allowedTargets,
    explicitlyAllowedPrivateTargets: [...exact].filter((target) => {
      const entry = parseTargetEntry(target);
      return entry && isUnsafeMinecraftHost(entry.host);
    }),
    allows(host, port) {
      const endpoint = normaliseEndpoint(host, port);
      if (!valid || !endpoint) return false;
      if (wildcard) return !isUnsafeMinecraftHost(endpoint.host);
      return exact.has(endpoint.target);
    },
    explicitlyAllowsPrivate(host, port) {
      const endpoint = normaliseEndpoint(host, port);
      return Boolean(valid && endpoint && exact.has(endpoint.target));
    },
  };
}

/** Validate policy, normalize the endpoint, and retain the original target name. */
export function resolveMinecraftTarget(host, port, policy = TARGET_POLICY) {
  const endpoint = normaliseEndpoint(host, port);
  if (!endpoint || !policy?.allows(host, port)) {
    throw new Error(`Minecraft server target is not allowed by MC_RELAY_ALLOW: ${host}:${port}`);
  }
  return { ...endpoint, redirected: false };
}

/** Resolve DNS once and refuse private/link-local/metadata results under `*`. */
async function lookupMinecraftAddress(endpoint, policy) {
  if (isUnsafeMinecraftHost(endpoint.host)) {
    if (!policy.explicitlyAllowsPrivate(endpoint.host, endpoint.port)) {
      throw new Error("wildcard policy refuses local, link-local, or metadata destinations");
    }
    if (net.isIP(endpoint.host)) return endpoint.host;
  }
  if (net.isIP(endpoint.host)) return endpoint.host;
  const results = await dns.promises.lookup(endpoint.host, { all: true, verbatim: true });
  if (!results.length) throw new Error("server hostname has no address");
  if (!policy.explicitlyAllowsPrivate(endpoint.host, endpoint.port)
      && results.some((entry) => isUnsafeMinecraftAddress(entry.address))) {
    throw new Error("wildcard policy refuses a hostname resolving to a local, link-local, or metadata address");
  }
  return results[0].address;
}

const TARGET_POLICY = minecraftTargetPolicy(readFlag(
  "allow",
  process.env.MC_RELAY_ALLOW ?? DEFAULT_MINECRAFT_TARGETS,
));
const profilePropertyVerifier = new ProfilePropertyVerifier({
  publicKeysUrl: PUBLIC_KEYS_URL,
});

// ---------------------------------------------------------------------------
// Minimal RFC 6455 server. No dependency is worth pulling in for two opcodes.
// ---------------------------------------------------------------------------

/** Encodes one unmasked binary frame (server -> client is never masked). */
function encodeFrame(payload, opcode = 0x2) {
  const length = payload.length;
  let header;
  if (length < 126) {
    header = Buffer.alloc(2);
    header[1] = length;
  } else if (length < 65536) {
    header = Buffer.alloc(4);
    header[1] = 126;
    header.writeUInt16BE(length, 2);
  } else {
    header = Buffer.alloc(10);
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(length), 2);
  }
  header[0] = 0x80 | opcode;
  return Buffer.concat([header, payload]);
}

/**
 * Pulls whole frames out of an accumulating buffer.
 * Returns {frames, rest}; continuation frames are reassembled by the caller.
 */
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

// ---------------------------------------------------------------------------
// Minecraft framing: VarInt length prefix.
// ---------------------------------------------------------------------------

function writeVarInt(value) {
  const bytes = [];
  let v = value >>> 0;
  do {
    let b = v & 0x7f;
    v >>>= 7;
    if (v !== 0) b |= 0x80;
    bytes.push(b);
  } while (v !== 0);
  return Buffer.from(bytes);
}

/** Reads a VarInt at `offset`; returns null when the buffer is short. */
function readVarInt(buffer, offset) {
  let value = 0;
  let shift = 0;
  let cursor = offset;
  for (;;) {
    if (cursor >= buffer.length) return null;
    if (shift > 35) throw new Error("VarInt too long");
    const b = buffer[cursor++];
    value |= (b & 0x7f) << shift;
    if ((b & 0x80) === 0) return { value: value >>> 0, next: cursor };
    shift += 7;
  }
}

// ---------------------------------------------------------------------------


// ---------------------------------------------------------------------------
// Online-mode session. The page never receives the Minecraft access token. The
// same process that serves the app uses the launcher's Microsoft-backed session
// for Mojang join, while exposing only the public profile name and UUID.
// ---------------------------------------------------------------------------

/**
 * Read only the public profile fields from the already validated in-memory
 * credential record. The gateway validates the launcher file before opening
 * the upstream socket, so the login hello can never be rewritten from an
 * unchecked local profile.
 */
function loadRequestCredentials() {
  const credentials = validatedCredentialsCache?.credentials;
  if (!credentials || credentials.expiresAt <= Date.now()) {
    return { error: "the official Minecraft Launcher session is no longer validated" };
  }
  return credentials;
}

/**
 * Revalidate the selected local launcher token against Minecraft Services.
 * This short in-memory cache avoids making two service calls for one browser
 * boot while ensuring a stale token cannot remain usable indefinitely. No
 * credential is written to disk, a cookie, a page response, or a log.
 */
async function loadValidatedCredentials() {
  const now = Date.now();
  if (validatedCredentialsCache
      && validatedCredentialsCache.validUntil > now
      && validatedCredentialsCache.credentials.expiresAt > now) {
    return validatedCredentialsCache.credentials;
  }
  if (launcherUploadInvalidated) {
    return { error: "the selected launcher account could not be validated", code: "upload-invalidated" };
  }
  const parsed = uploadedLauncherCredentials || readLauncherCredentialsCandidates(ACCOUNT_PATHS, { now });
  if (!parsed.ok) return { error: parsed.error, code: parsed.code };
  const validated = await validateLauncherCredentials(parsed, { now });
  if (!validated.ok) return { error: validated.error, code: validated.code };
  validatedCredentialsCache = {
    credentials: validated,
    validUntil: Math.min(validated.expiresAt, now + AUTH_VALIDATION_CACHE_MS),
  };
  return validated;
}

const MAX_LAUNCHER_UPLOAD_BYTES = 1024 * 1024;

async function readLauncherUpload(req) {
  let length = 0;
  const chunks = [];
  for await (const chunk of req) {
    length += chunk.length;
    if (length > MAX_LAUNCHER_UPLOAD_BYTES) {
      throw Object.assign(new Error("the launcher JSON file is too large"), { code: "too-large" });
    }
    chunks.push(chunk);
  }
  if (length === 0) throw Object.assign(new Error("the launcher JSON file is empty"), { code: "empty" });
  let value;
  try {
    value = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw Object.assign(new Error("the launcher JSON file is not valid JSON"), { code: "invalid-json" });
  }
  return value;
}

/**
 * Accept one exact supported local launcher document over the loopback app
 * boundary. Validation is performed before the credential enters the in-memory
 * gateway cache; only profile metadata and provider name are returned to the
 * browser.
 */
export async function handleLauncherAccountsUpload(req, res) {
  // Clear first, before reading or validating the replacement. A malformed,
  // expired, or rejected document must never leave the previous session live.
  uploadedLauncherCredentials = null;
  validatedCredentialsCache = null;
  launcherUploadInvalidated = true;
  let parsed;
  try {
    const value = await readLauncherUpload(req);
    parsed = parseLauncherDocument(value);
  } catch (error) {
    const status = error?.code === "too-large" ? 413 : 400;
    res.writeHead(status, { "cache-control": "no-store", "content-type": "application/json" });
    res.end(JSON.stringify({ authenticated: false, code: error?.code || "invalid-file", error: error?.message || "invalid launcher JSON" }));
    return;
  }
  if (!parsed.ok) {
    res.writeHead(400, { "cache-control": "no-store", "content-type": "application/json" });
    res.end(JSON.stringify({ authenticated: false, code: parsed.code, error: parsed.error }));
    return;
  }
  const validated = await validateLauncherCredentials(parsed);
  if (!validated.ok) {
    res.writeHead(401, { "cache-control": "no-store", "content-type": "application/json" });
    res.end(JSON.stringify({ authenticated: false, code: validated.code, error: validated.error }));
    return;
  }
  uploadedLauncherCredentials = validated;
  launcherUploadInvalidated = false;
  validatedCredentialsCache = {
    credentials: validated,
    validUntil: Math.min(validated.expiresAt, Date.now() + AUTH_VALIDATION_CACHE_MS),
  };
  res.writeHead(200, { "cache-control": "no-store", "content-type": "application/json" });
  res.end(JSON.stringify({
    authenticated: true,
    mode: "online",
    provider: validated.provider,
    profile: { name: validated.name, id: validated.id },
  }));
}

/** Minecraft's signed hex digest: two's complement, '-' prefix when negative. */
function mojangDigest(parts) {
  const hash = crypto.createHash("sha1");
  for (const part of parts) hash.update(part);
  let digest = hash.digest();
  const negative = (digest[0] & 0x80) !== 0;
  if (negative) {
    // Two's complement in place, then render without leading zeros.
    let carry = 1;
    for (let i = digest.length - 1; i >= 0; i--) {
      digest[i] = (~digest[i] & 0xff) + carry;
      carry = digest[i] > 0xff ? 1 : 0;
      digest[i] &= 0xff;
    }
  }
  const hex = digest.toString("hex").replace(/^0+/, "");
  return (negative ? "-" : "") + (hex === "" ? "0" : hex);
}

/** What the player must actually do when their Minecraft token is no longer good. */
function expiredSessionMessage(credentials) {
  const since = credentials?.expiresAt
    ? ` (expired ${Math.round((Date.now() - credentials.expiresAt) / 60000)} min ago)`
    : "";
  return `Your Minecraft session has expired${since}. Open the launcher that owns`
    + " this account and sign in again, then reconnect.";
}

async function joinSession(credentials, serverHash) {
  if (credentials.expiresAt <= Date.now()) throw new Error(expiredSessionMessage(credentials));
  const response = await fetch("https://sessionserver.mojang.com/session/minecraft/join", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      accessToken: credentials.token,
      selectedProfile: credentials.id.replace(/-/g, ""),
      serverId: serverHash
    })
  });
  // A rejected token is the ordinary failure here and it is indistinguishable
  // from a policy refusal in the status code alone, so say what to do about it.
  if (response.status === 401 || response.status === 403) {
    throw new Error(expiredSessionMessage(credentials));
  }
  if (response.status !== 204 && response.status !== 200) {
    throw new Error(`session join returned ${response.status} ${await response.text()}`);
  }
}

/** Reader over a packet body, for the few login packets the relay must parse. */
class BodyReader {
  constructor(buffer) { this.b = buffer; this.o = 0; }
  varInt() {
    let value = 0, shift = 0;
    for (;;) {
      const byte = this.b[this.o++];
      value |= (byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return value >>> 0;
      shift += 7;
    }
  }
  bytes() { const n = this.varInt(); const out = this.b.subarray(this.o, this.o + n); this.o += n; return out; }
  string() { return this.bytes().toString("utf8"); }
  bool() { return this.b[this.o++] !== 0; }
}

let nextId = 1;

const verificationHeaders = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-headers": "content-type",
  "cache-control": "no-store",
};

async function handleIdentity(req, res) {
  const credentials = await loadValidatedCredentials();
  const available = !credentials.error
    && typeof credentials.name === "string"
    && typeof credentials.id === "string";
  res.writeHead(available ? 200 : 404, {
    "access-control-allow-origin": "*",
    "cache-control": "no-store",
    "content-type": "application/json",
  });
  // The access token intentionally never crosses this localhost boundary.
  res.end(JSON.stringify(available
    ? { available: true, name: credentials.name, id: credentials.id }
    : { available: false }));
}

export async function handleAuthSession(req, res) {
  const credentials = await loadValidatedCredentials();
  const authenticated = !credentials.error
    && typeof credentials.name === "string"
    && typeof credentials.id === "string";
  res.writeHead(authenticated ? 200 : 401, {
    "cache-control": "no-store",
    "content-type": "application/json",
  });
  // Only live, service-validated public profile metadata crosses this boundary.
  // The launcher token remains in this Node process.
  res.end(JSON.stringify(authenticated ? {
    authenticated: true,
    mode: "online",
    provider: credentials.provider || AUTH_PROVIDER,
    profile: { name: credentials.name, id: credentials.id },
  } : {
    authenticated: false,
    mode: "online",
    provider: credentials.provider || AUTH_PROVIDER,
    error: "No authenticated official Minecraft Launcher session is available",
  }));
}

async function handleProfileSkin(req, res) {
  const credentials = await loadValidatedCredentials();
  if (credentials.error || !credentials.id) {
    res.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    res.end("No authenticated Minecraft skin\n");
    return;
  }
  let skinUrl = credentials.skinUrl;
  if (!skinUrl) {
    res.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    res.end("No active Minecraft skin\n");
    return;
  }
  const parsed = new URL(skinUrl || "");
  // Mojang's own texture property still carries `http://textures.minecraft.net`
  // and always has; rejecting the scheme rejected every real profile. The CDN
  // serves the same path over TLS, so upgrade rather than refuse. The host is
  // still pinned, which is the check that actually matters here.
  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") {
    throw new Error("profile returned an invalid Minecraft skin URL");
  }
  if (parsed.hostname !== "textures.minecraft.net") {
    throw new Error(`profile returned an unexpected skin host ${parsed.hostname}`);
  }
  parsed.protocol = "https:";
  const skinResponse = await fetch(parsed, { cache: "no-store" });
  if (!skinResponse.ok) throw new Error(`skin fetch returned ${skinResponse.status}`);
  const bytes = Buffer.from(await skinResponse.arrayBuffer());
  res.writeHead(200, {
    "content-type": skinResponse.headers.get("content-type") || "image/png",
    "content-length": bytes.length,
    "cache-control": "private, max-age=300",
  });
  res.end(bytes);
}

async function handleProfilePropertyVerification(req, res) {
  const chunks = [];
  let length = 0;
  for await (const chunk of req) {
    length += chunk.length;
    if (length > 1024 * 1024) {
      res.writeHead(413, {
        ...verificationHeaders,
        "content-type": "application/json",
      });
      res.end(JSON.stringify({ valid: false, error: "request too large" }));
      return;
    }
    chunks.push(chunk);
  }

  let property;
  try {
    property = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    res.writeHead(400, {
      ...verificationHeaders,
      "content-type": "application/json",
    });
    res.end(JSON.stringify({ valid: false, error: "invalid JSON" }));
    return;
  }

  // Missing/unsigned/malformed properties are deliberately indistinguishable
  // from a bad signature. The browser may only promote an explicit `true`.
  const valid = await profilePropertyVerifier.verify(
    property?.value,
    property?.signature,
  );
  res.writeHead(200, {
    ...verificationHeaders,
    "content-type": "application/json",
  });
  res.end(JSON.stringify({ valid }));
}

/** How much of a server's resource pack this process will relay. */
const MAX_PACK_BYTES = 512 * 1024 * 1024;

/**
 * Same-origin fallback for a server resource pack the page could not read
 * itself.
 *
 * The page always tries the pack host directly first, so this path carries
 * nothing when the host sends CORS headers. It exists because a browser cannot
 * read an opaque cross-origin response at all, and most Minecraft servers host
 * their packs somewhere that has never heard of CORS -- without this, those
 * servers are simply unplayable rather than merely relayed.
 */
async function handlePackProxy(req, res) {
  const target = new URL(req.url, "http://gateway").searchParams.get("url") || "";
  let parsed;
  try {
    parsed = new URL(target);
  } catch {
    res.writeHead(400, { "content-type": "text/plain; charset=utf-8" });
    res.end("pack url is not a URL\n");
    return;
  }
  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") {
    res.writeHead(400, { "content-type": "text/plain; charset=utf-8" });
    res.end(`refusing to relay a ${parsed.protocol} pack url\n`);
    return;
  }
  // Some pack hosts answer a bare fetch with a challenge page or a 403; a
  // browser-shaped request is what they are checking for.
  const upstream = await fetch(parsed, {
    redirect: "follow",
    headers: {
      "user-agent": "Minecraft Java/1.21 (MC-Web)",
      accept: "application/zip,application/octet-stream,*/*",
    },
  });
  if (!upstream.ok) throw new Error(`pack host returned ${upstream.status}`);
  const declared = Number(upstream.headers.get("content-length")) || 0;
  if (declared > MAX_PACK_BYTES) {
    throw new Error(`pack is ${declared} bytes, over the relay limit`);
  }
  // Deliberately no content-length: `fetch` transparently decodes a
  // content-encoded body, so the upstream length can disagree with what is
  // actually written here, and the page then sees a truncated zip.
  res.writeHead(200, {
    "content-type": upstream.headers.get("content-type") || "application/zip",
    "cache-control": "no-store",
  });
  if (req.method === "HEAD" || !upstream.body) {
    res.end();
    return;
  }
  let relayed = 0;
  for await (const chunk of upstream.body) {
    relayed += chunk.length;
    if (relayed > MAX_PACK_BYTES) {
      res.destroy();
      throw new Error("pack exceeded the relay limit mid-stream");
    }
    res.write(chunk);
  }
  res.end();
  console.log(`[pack] relayed ${relayed} bytes from ${parsed.host}`);
}

/**
 * Handles the gateway's small HTTP control surface.
 *
 * The namespaced routes are used when this gateway shares the app origin.
 * Legacy routes remain for the standalone CLI and existing diagnostics.
 * Returns true when it owned the request.
 */
export function handleMinecraftGatewayHttp(req, res) {
  const path = new URL(req.url, "http://gateway").pathname;
  if (path === "/mcweb/auth/minecraft/skin" && req.method === "GET") {
    handleProfileSkin(req, res).catch((error) => {
      res.writeHead(502, { "content-type": "text/plain; charset=utf-8" });
      res.end(`${error.message}\n`);
    });
    return true;
  }
  if (path === "/mcweb/auth/session" && req.method === "GET") {
    handleAuthSession(req, res).catch(() => {
      if (!res.headersSent) res.writeHead(503, { "cache-control": "no-store", "content-type": "application/json" });
      res.end(JSON.stringify({ authenticated: false, mode: "online", provider: AUTH_PROVIDER }));
    });
    return true;
  }
  if ((path === "/identity" || path === "/mcweb/identity")
      && req.method === "GET") {
    handleIdentity(req, res).catch(() => {
      if (!res.headersSent) res.writeHead(503, { "cache-control": "no-store", "content-type": "application/json" });
      res.end(JSON.stringify({ available: false }));
    });
    return true;
  }
  if ((path === "/verify-profile-property"
      || path === "/mcweb/verify-profile-property")
      && req.method === "OPTIONS") {
    res.writeHead(204, verificationHeaders);
    res.end();
    return true;
  }
  if (path === "/mcweb/pack" && (req.method === "GET" || req.method === "HEAD")) {
    handlePackProxy(req, res).catch((error) => {
      if (!res.headersSent) {
        res.writeHead(502, { "content-type": "text/plain; charset=utf-8" });
      }
      res.end(`${error.message}\n`);
    });
    return true;
  }
  if ((path === "/verify-profile-property"
      || path === "/mcweb/verify-profile-property")
      && req.method === "POST") {
    handleProfilePropertyVerification(req, res).catch((error) => {
      res.writeHead(503, {
        ...verificationHeaders,
        "content-type": "application/json",
      });
      res.end(JSON.stringify({ valid: false, error: error.message }));
    });
    return true;
  }
  return false;
}

/** Owns one already-routed WebSocket upgrade and its Minecraft TCP session. */
export function handleMinecraftGatewayUpgrade(req, socket) {
  const id = nextId++;
  const url = new URL(req.url, "http://gateway");
  const host = url.searchParams.get("host") ?? "127.0.0.1";
  const requestedPort = url.searchParams.get("port");
  const port = requestedPort === null ? 25565 : parseMinecraftPort(requestedPort);
  const endpoint = normaliseEndpoint(host, port);
  const target = endpoint?.target ?? `${host}:${port}`;
  const log = (...parts) => console.log(`[minecraft#${id}]`, ...parts);

  const key = req.headers["sec-websocket-key"];
  if (!key) {
    socket.destroy();
    return;
  }
  if (!TARGET_POLICY.allows(host, port)) {
    log(`refused ${target} (not in --allow)`);
    socket.end("HTTP/1.1 403 Forbidden\r\n\r\n");
    return;
  }
  const accept = crypto.createHash("sha1").update(key + WS_GUID).digest("base64");
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\n"
    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
    + `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
  );
  socket.setNoDelay(true);
  log(`open -> ${target}`);

  let upstream = null;
  const pendingUpstream = [];
  let pendingUpstreamBytes = 0;
  let wsBuffer = Buffer.alloc(0);
  let fragments = [];
  let fragmentOpcode = 0x2;
  let tcpBuffer = Buffer.alloc(0);
  let toServer = 0;
  let toPage = 0;
  // Ingress vs egress, so the cost of terminating compression here is a number
  // rather than an intuition: the relay inflates every server packet before
  // sending it on, so its egress is larger than its ingress by exactly the
  // compression ratio of the stream.
  let bytesFromServer = 0;
  let bytesToPage = 0;
  /*
   * Keep-alive takeover.
   *
   * Applying a large server resource pack blocks the page's single thread for
   * tens of seconds -- 32 s for one real server's 19091-file pack -- so the
   * keep-alive that arrives during it is not answered until long after the
   * server has closed the connection at its 30 s read timeout. This process is
   * the only participant still running, so it answers on the page's behalf.
   *
   * The page supplies the id (only it knows the per-phase protocol number) and
   * the takeover is conditional: while the page is answering normally its own
   * replies go through untouched, which keeps its latency display honest. Ids
   * this side has answered are remembered so the page's late duplicate reply --
   * arriving after the stall ends -- is dropped rather than confusing the
   * server with a stale token.
   */
  let keepAliveInbound = -1;
  let keepAliveOutbound = -1;
  let keepAliveLength = 0;
  let lastPageActivity = Date.now();
  let keepAlivesAnswered = 0;
  const answeredTokens = new Set();
  let closed = false;

  /**
   * Session state. The relay is a *terminating* proxy for login: it answers the
   * encryption request and the compression switch itself and never forwards
   * either to the page, because the browser image can do neither (no crypto, no
   * zlib) and because the page link is deliberately plaintext, uncompressed
   * whole packets.
   */
  const session = {
    phase: "handshake",   // handshake -> status | login -> play
    compression: -1,      // threshold, -1 while disabled
    encipher: null,       // AES/CFB8 towards the server
    decipher: null        // AES/CFB8 from the server
  };
  /** Serialises the async auth step against the sync socket callbacks. */
  let chain = Promise.resolve();

  const closePayload = (code, why) => {
    let reason = String(why || "");
    // RFC 6455 control frames are at most 125 bytes; the status code uses two.
    while (Buffer.byteLength(reason, "utf8") > 123) reason = reason.slice(0, -1);
    const status = Buffer.alloc(2);
    status.writeUInt16BE(code, 0);
    return Buffer.concat([status, Buffer.from(reason, "utf8")]);
  };

  const shutdown = (why, { notifyPage = true, code = 1011 } = {}) => {
    if (closed) return;
    closed = true;
    log(`close (${why}) packets page->server ${toServer}, server->page ${toPage}`);
    log(`bytes server->relay ${bytesFromServer}, relay->page ${bytesToPage}`
      + (bytesFromServer > 0
        ? ` (egress ${(bytesToPage / bytesFromServer).toFixed(2)}x ingress)` : ""));
    try {
      if (notifyPage && !socket.destroyed) {
        socket.end(encodeFrame(closePayload(code, why), 0x8));
      } else {
        socket.destroy();
      }
    } catch { /* already gone */ }
    try { upstream?.destroy(); } catch { /* already gone */ }
  };

  /** Writes raw bytes upstream, encrypting once the cipher is live. */
  const writeUpstream = (bytes) => {
    if (!upstream) {
      pendingUpstream.push(bytes);
      pendingUpstreamBytes += bytes.length;
      if (pendingUpstreamBytes > 1024 * 1024) {
        shutdown("too much data queued before DNS resolution");
      }
      return;
    }
    upstream.write(session.encipher ? session.encipher.update(bytes) : bytes);
  };

  /** Frames one packet body for the wire, compressing when enabled. */
  const frameForServer = (body) => {
    if (session.compression < 0) {
      return Buffer.concat([writeVarInt(body.length), body]);
    }
    if (body.length < session.compression) {
      const payload = Buffer.concat([writeVarInt(0), body]);
      return Buffer.concat([writeVarInt(payload.length), payload]);
    }
    const deflated = zlib.deflateSync(body);
    const payload = Buffer.concat([writeVarInt(body.length), deflated]);
    return Buffer.concat([writeVarInt(payload.length), payload]);
  };

  /** The page's control channel: JSON in a WebSocket text frame. */
  const handlePageControl = (body) => {
    let message;
    try {
      message = JSON.parse(body.toString("utf8"));
    } catch (error) {
      log(`ignoring unreadable control frame: ${error.message}`);
      return;
    }
    const wanted = message?.keepAlive;
    if (wanted && Number.isInteger(wanted.clientbound)
        && Number.isInteger(wanted.serverbound) && Number.isInteger(wanted.length)) {
      keepAliveInbound = wanted.clientbound;
      keepAliveOutbound = wanted.serverbound;
      keepAliveLength = wanted.length;
      answeredTokens.clear();
      log(`keep-alive in=${keepAliveInbound} out=${keepAliveOutbound}`
        + ` (${keepAliveLength} bytes)`);
    }
  };

  /**
   * Answers a keep-alive the page is too busy to handle.
   *
   * Only the shape the page told us about is touched, and only once the page
   * has gone quiet for longer than any healthy client ever is. Returns true
   * when the packet was consumed here and must not reach the page: the page
   * must never answer a token this side already used, or the server sees two
   * replies for one keep-alive.
   */
  const answerKeepAliveIfStalled = (body) => {
    if (keepAliveInbound < 0 || session.phase !== "play") return false;
    if (body.length !== keepAliveLength || body[0] !== keepAliveInbound) return false;
    if (Date.now() - lastPageActivity < PAGE_STALL_MS) return false;
    const token = body.subarray(1, keepAliveLength);
    answeredTokens.add(token.toString("hex"));
    // Bounded: a session cannot accumulate tokens faster than one per
    // keep-alive, and only while the page is unresponsive.
    if (answeredTokens.size > 64) {
      answeredTokens.delete(answeredTokens.values().next().value);
    }
    writeUpstream(frameForServer(
      Buffer.concat([Buffer.from([keepAliveOutbound]), token])));
    keepAlivesAnswered++;
    if (keepAlivesAnswered <= 3 || keepAlivesAnswered % 10 === 0) {
      log(`answered keep-alive #${keepAlivesAnswered} while the page was busy`
        + ` (${Math.round((Date.now() - lastPageActivity) / 1000)}s)`);
    }
    return true;
  };

  /** One whole packet body from the page. */
  const sendToServer = (bodyIn) => {
    let body = bodyIn;
    // A keep-alive this side already answered: the page was stalled, caught up,
    // and is now replying to a token the server has long since retired. Sending
    // it would be a second answer for one keep-alive.
    if (keepAliveOutbound >= 0 && body.length === keepAliveLength
        && body[0] === keepAliveOutbound
        && answeredTokens.delete(body.subarray(1, keepAliveLength).toString("hex"))) {
      return;
    }
    toServer++;
    if (session.phase === "handshake") {
      // Intention packet: id, protocol, address, port, next state.
      try {
        const r = new BodyReader(body);
        r.varInt(); r.varInt(); r.string();
        r.b.readUInt16BE(r.o); r.o += 2;
        session.phase = r.varInt() === 1 ? "status" : "login";
        log(`intent -> ${session.phase}`);
      } catch {
        session.phase = "login";
      }
      // Forward the intention as-is. Falling through would re-examine THIS
      // packet as a login Hello — its id is also 0x00 — and rewrite the
      // handshake into an identity packet, which the server closes on.
      writeUpstream(frameForServer(body));
      return;
    }
    if (AUTH_MODE === "online" && session.phase === "login"
        && readVarInt(body, 0)?.value === 0x00) {
      // Hello. Rewrite the identity to the authenticated account: an online
      // server looks the session up by NAME, and the browser client only knows
      // its browser-local placeholder profile. Doing it here keeps the client
      // unaware that authentication happened at the gateway.
      const credentials = loadRequestCredentials();
      if (!credentials.error) {
        const name = Buffer.from(credentials.name, "utf8");
        const uuid = Buffer.from(credentials.id.replace(/-/g, ""), "hex");
        body = Buffer.concat([writeVarInt(0x00), writeVarInt(name.length), name, uuid]);
        log(`hello rewritten as ${credentials.name}`);
      } else {
        log(`hello NOT rewritten: ${credentials.error}`);
      }
    }
    writeUpstream(frameForServer(body));
  };

  /**
   * Answers the server's encryption request: prove ownership of the account to
   * Mojang, hand the server the RSA-wrapped secret, then switch both directions
   * to AES/CFB8. The page sees none of it.
   */
  async function handleEncryptionRequest(body) {
    if (AUTH_MODE !== "online") {
      throw new Error("server requires online authentication; restart with MCWEB_AUTH_MODE=online");
    }
    const r = new BodyReader(body);
    r.varInt();                       // packet id
    const serverId = r.string();
    const publicKeyDer = Buffer.from(r.bytes());
    const verifyToken = Buffer.from(r.bytes());

    const credentials = await loadValidatedCredentials();
    if (credentials.error) throw new Error(
      credentials.code === "expired-token" ? expiredSessionMessage(credentials) : credentials.error,
    );

    const secret = crypto.randomBytes(16);
    const hash = mojangDigest([Buffer.from(serverId, "utf8"), secret, publicKeyDer]);
    await joinSession(credentials, hash);
    log(`authenticated as ${credentials.name}`);

    const publicKey = crypto.createPublicKey({ key: publicKeyDer, format: "der", type: "spki" });
    const wrap = (data) => crypto.publicEncrypt(
      { key: publicKey, padding: crypto.constants.RSA_PKCS1_PADDING }, data);
    const encSecret = wrap(secret);
    const encToken = wrap(verifyToken);
    const key = Buffer.concat([
      writeVarInt(0x01),
      writeVarInt(encSecret.length), encSecret,
      writeVarInt(encToken.length), encToken
    ]);
    // The key packet itself is still plaintext; everything after it is not.
    writeUpstream(frameForServer(key));
    session.encipher = crypto.createCipheriv("aes-128-cfb8", secret, secret);
    session.decipher = crypto.createDecipheriv("aes-128-cfb8", secret, secret);
    log("encryption enabled");
  }

  /** Decides what the page is allowed to see, and advances the phase. */
  async function handleServerBody(body) {
    if (session.phase === "login") {
      const id = readVarInt(body, 0)?.value;
      if (id === 0x01) { await handleEncryptionRequest(body); return; }
      if (id === 0x03) {
        // Set Compression. Swallowed: the page link stays uncompressed, so the
        // client must never enable it on its side.
        const r = new BodyReader(body);
        r.varInt();
        session.compression = r.varInt();
        log(`compression threshold ${session.compression}`);
        return;
      }
      if (id === 0x02) {
        session.phase = "play";
        socket.write(encodeFrame(Buffer.from(body)));
        bytesToPage += body.length;
        toPage++;
        // Hand the zlib layer to the page from the next frame on. WebSocket
        // messages are ordered, so this text frame is the exact boundary: the
        // Login Success above was the last inflated body, and everything after
        // it arrives in the server's own compressed framing.
        socket.write(encodeFrame(
          Buffer.from(JSON.stringify({ compression: session.compression }), "utf8"), 0x1));
        if (session.compression >= 0) {
          log(`compression handed to page (threshold ${session.compression})`);
        }
        return;
      }
    }
    socket.write(encodeFrame(Buffer.from(body)));
    bytesToPage += body.length;
    toPage++;
  }

  /**
   * Decodes the phase-dependent payload inside the ordered handler chain.
   * Set Compression changes the framing of the packet immediately after it,
   * and both frames may arrive in the same TCP read. Decoding every buffered
   * frame before handleServerBody runs would therefore use stale session state.
   */
  const decodeServerPayload = (payload) => {
    let body = payload;
    if (session.compression < 0) return body;
    // Compressed framing adds an uncompressed-size VarInt; zero means the
    // packet was below the threshold and is stored raw.
    const sizeField = readVarInt(body, 0);
    if (!sizeField) throw new Error("truncated compressed packet size");
    const declared = sizeField.value;
    body = body.subarray(sizeField.next);
    if (declared === 0) return body;
    let inflated;
    try {
      inflated = zlib.inflateSync(Buffer.from(body));
    } catch (error) {
      throw new Error(`inflate failed: ${error.message}`);
    }
    if (inflated.length !== declared) {
      throw new Error(
        `inflated packet length ${inflated.length} did not match ${declared}`,
      );
    }
    return inflated;
  };

  socket.on("data", (chunk) => {
    wsBuffer = Buffer.concat([wsBuffer, chunk]);
    let decoded;
    try {
      decoded = decodeFrames(wsBuffer);
    } catch (error) {
      shutdown(`bad ws frame: ${error.message}`);
      return;
    }
    wsBuffer = decoded.rest;
    for (const frame of decoded.frames) {
      if (frame.opcode === 0x8) {
        shutdown("page closed", { notifyPage: false, code: 1000 });
        return;
      }
      if (frame.opcode === 0x9) { socket.write(encodeFrame(frame.payload, 0xa)); continue; }
      if (frame.opcode === 0xa) continue;
      if (frame.opcode === 0x0 || frame.opcode === 0x1 || frame.opcode === 0x2) {
        if (frame.opcode !== 0x0) fragmentOpcode = frame.opcode;
        fragments.push(frame.payload);
        if (!frame.fin) continue;
        const body = fragments.length === 1 ? fragments[0] : Buffer.concat(fragments);
        fragments = [];
        if (body.length === 0) continue;
        // A text frame is the page's control channel; binary frames are packets.
        if (fragmentOpcode === 0x1) {
          handlePageControl(body);
          continue;
        }
        lastPageActivity = Date.now();
        sendToServer(body);
      }
    }
  });

  const attachUpstream = (connected) => {
    upstream = connected;
    upstream.setNoDelay(true);
    upstream.on("data", (chunk) => {
      tcpBuffer = Buffer.concat([
        tcpBuffer,
        session.decipher ? session.decipher.update(chunk) : chunk
      ]);
      for (;;) {
        let header;
        try {
          header = readVarInt(tcpBuffer, 0);
        } catch (error) {
          shutdown(`bad packet length: ${error.message}`);
          return;
        }
        if (!header) break;
        if (tcpBuffer.length - header.next < header.value) break;
        const payload = Buffer.from(
          tcpBuffer.subarray(header.next, header.next + header.value),
        );
        tcpBuffer = tcpBuffer.subarray(header.next + header.value);
        bytesFromServer += header.value;
        chain = chain
          .then(() => {
            if (closed) return undefined;
            // Once play begins the relay has nothing left to read, so the
            // server's compressed frame is forwarded byte for byte and the
            // page inflates it. Inflating here instead made this process send
            // 5.7x the bytes it received on a world load -- all of it egress.
            if (session.phase === "play" && session.compression >= 0) {
              // A keep-alive is far below the compression threshold, so it
              // travels as [VarInt 0][body] and can be recognised without
              // inflating anything.
              const size = readVarInt(payload, 0);
              if (size && size.value === 0
                  && answerKeepAliveIfStalled(payload.subarray(size.next))) {
                return undefined;
              }
              socket.write(encodeFrame(payload));
              bytesToPage += payload.length;
              toPage++;
              return undefined;
            }
            return handleServerBody(decodeServerPayload(payload));
          })
          .catch((error) => shutdown(`login failed: ${error.message}`));
      }
    });

    upstream.on("connect", () => log("upstream connected"));
    upstream.on("error", (error) => shutdown(`upstream error: ${error.message}`));
    // Let queued packet handlers publish a final vanilla disconnect packet
    // before closing the WebSocket. Destroying the page socket directly from the
    // TCP close event could race that Promise chain and discard the reason.
    upstream.on("close", () => {
      chain.finally(() => shutdown("upstream closed", { code: 1000 }));
    });
    for (const bytes of pendingUpstream.splice(0)) {
      upstream.write(session.encipher ? session.encipher.update(bytes) : bytes);
    }
    pendingUpstreamBytes = 0;
  };

  try {
    const resolved = resolveMinecraftTarget(host, port, TARGET_POLICY);
    // Validate the official Launcher's active account before opening the TCP
    // socket. This is deliberately required for status as well as login: the
    // local app exposes an authenticated-only gateway, and an offline-mode
    // destination must not become an authentication bypass.
    const authReady = AUTH_MODE === "online" ? loadValidatedCredentials() : Promise.resolve(null);
    // Pin DNS before opening TCP. Under the wildcard default, any local,
    // link-local, metadata, or mixed public/private DNS answer is refused;
    // an exact MC_RELAY_ALLOW entry is the explicit private-host opt-in.
    Promise.all([lookupMinecraftAddress(resolved, TARGET_POLICY), authReady]).then(([address, credentials]) => {
      if (closed) return;
      if (credentials?.error) throw new Error(credentials.error);
      attachUpstream(net.createConnection({
        host: address,
        port: resolved.port,
      }));
    }).catch((error) => shutdown(`connection refused: ${error.message}`, { code: 1008 }));
  } catch (error) {
    shutdown(`target resolution refused: ${error.message}`, { code: 1008 });
  }

  socket.on("error", (error) => shutdown(`page error: ${error.message}`, {
    notifyPage: false,
  }));
  socket.on("close", () => shutdown("page socket closed", { notifyPage: false }));
}

export function minecraftGatewayInfo() {
  return {
    authMode: AUTH_MODE,
    interactiveAuth: false,
    authProvider: AUTH_PROVIDER,
    allowedTargets: TARGET_POLICY.allowedTargets,
    allowAnyTarget: TARGET_POLICY.wildcard,
    explicitlyAllowedPrivateTargets: TARGET_POLICY.explicitlyAllowedPrivateTargets,
    targetPolicy: TARGET_POLICY.wildcard ? "wildcard-public" : "explicit-list",
    publicKeysUrl: PUBLIC_KEYS_URL,
  };
}

if (IS_MAIN) {
  const server = http.createServer((req, res) => {
    if (handleMinecraftGatewayHttp(req, res)) return;
    res.writeHead(426, { "content-type": "text/plain" });
    res.end("MC-Web Minecraft transport or profile verification only\n");
  });
  server.on("upgrade", handleMinecraftGatewayUpgrade);
  server.listen(PORT, "127.0.0.1", () => {
    const listeningPort = server.address().port;
    console.log(`Minecraft browser bridge listening on ws://127.0.0.1:${listeningPort}/`);
    console.log(`authentication mode: ${AUTH_MODE}`);
    console.log(`allowed targets: ${TARGET_POLICY.allowedTargets.join(", ")}`);
    if (TARGET_POLICY.allowedTargets.length === 0) {
      console.warn("WARNING: invalid MC_RELAY_ALLOW; the Minecraft gateway is fail-closed");
    }
    console.log(`profile property keys: ${PUBLIC_KEYS_URL} (cached for 6 hours)`);
  });
}
