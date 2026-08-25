#!/usr/bin/env node
// Install the local MC-Web build toolchain without administrator access.
//
// This file downloads the developer toolchain and, when building without an
// explicit --mc-dir, the public Minecraft 26.2 inputs from Mojang's official
// CDNs. Account files/tokens and generated image bytes are never fetched or
// packaged here. The build helper owns the verified Minecraft cache layout.
//
// Examples:
//   node tools/mcweb-install.mjs --dry-run
//   node tools/mcweb-install.mjs --build       # Mojang CDN inputs -> ~/.mcweb/minecraft
//   node tools/mcweb-install.mjs --mc-dir "$HOME/Library/Application Support/minecraft" --build
//   node tools/mcweb-install.mjs --verify
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { createReadStream, createWriteStream } from "node:fs";
import { mkdir, readdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { applyWindowsToolchain, loadWindowsToolchain } from "./windows-toolchain.mjs";

const NODE_VERSION = "24.19.0";
// Web Image was introduced in Oracle GraalVM 25.1. The public bootstrap is
// pinned to the current 25i2 archive (GraalVM 25.2.4, based on JDK 25.0.4).
const GRAALVM_VERSION = "25.2.4";
const GRAALVM_ARCHIVE = "25i2-25.0.4";
const MIN_GRAALVM_VERSION = [25, 1, 0];
const BINARYEN_VERSION = "131";
const LLVM_MINGW_VERSION = "20260616";
const MIN_NODE_MAJOR = 20;
const HOME = process.env.MCWEB_HOME || join(homedir(), ".mcweb");
const PROJECT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const argv = process.argv.slice(2);
const has = (name) => argv.includes(`--${name}`);
const flag = (name, fallback = null) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith("--") ? argv[i + 1] : fallback;
};
const say = (...args) => console.log("mcweb:", ...args);
const die = (message) => { console.error(`mcweb: ${message}`); process.exit(1); };
const exists = async (path) => !!(await stat(path).catch(() => null));
const exe = (name) => process.platform === "win32" ? `${name}.exe` : name;
const executableNames = (name) => process.platform === "win32"
  ? [`${name}.exe`, `${name}.cmd`, `${name}.bat`]
  : [name];
const hasExecutable = async (directory, name) => {
  for (const candidate of executableNames(name)) {
    if (await exists(join(directory, candidate))) return true;
  }
  return false;
};

function validateInstallerArgs() {
  const mcDir = flag("mc-dir");
  const downloadOnly = has("download-only");
  if (has("download") && mcDir) die("--download cannot be combined with --mc-dir");
  if (has("download") && has("local-only")) die("--download cannot be combined with --local-only");
  if (downloadOnly && mcDir) die("--download-only cannot be combined with --mc-dir");
  if (downloadOnly && has("local-only")) die("--download-only cannot be combined with --local-only");
  if (downloadOnly && (has("build") || has("run") || has("dry-run"))) {
    die("--download-only cannot be combined with --build, --run, or --dry-run");
  }
  if (has("dry-run") && (has("build") || has("run") || has("verify") || has("download"))) {
    die("--dry-run cannot be combined with --build, --run, --verify, or --download");
  }
  if (has("verify") && (has("build") || has("run"))) die("--verify cannot be combined with --build or --run");
  if (has("offline") && (mcDir || has("local-only"))) {
    die("--offline cannot be combined with --mc-dir or --local-only");
  }
}
validateInstallerArgs();

// The GDS 25i2 archive currently has no .sha256 object at its archive URL.
// These are the SHA-256 values published in the archive object metadata;
// keeping them here makes a clean-machine download fail closed if the bytes
// ever change. The Java version in the artifact name is 25.0.4; the GraalVM
// distribution itself is 25.2.4.
const GRAALVM_ARTIFACTS = Object.freeze({
  "macos-aarch64": {
    file: `graalvm-jdk-${GRAALVM_ARCHIVE}_macos-aarch64_bin.tar.gz`,
    sha256: "1b5937aa3076707459cfc815a1699761f943d2d1c9cbe03388e36d5e47eb27c3",
  },
  "linux-x64": {
    file: `graalvm-jdk-${GRAALVM_ARCHIVE}_linux-x64_bin.tar.gz`,
    sha256: "7100d99cbfec68b03b669cc60c7e8592bbcda1732e8eaebc460fe0b75849a894",
  },
  "linux-aarch64": {
    file: `graalvm-jdk-${GRAALVM_ARCHIVE}_linux-aarch64_bin.tar.gz`,
    sha256: "0bc65f9c36ae77bd83aad46a2b4de4b0ec97da1b4ac83fedb59e19f868873dee",
  },
  "windows-x64": {
    file: `graalvm-jdk-${GRAALVM_ARCHIVE}_windows-x64_bin.zip`,
    sha256: "2b41fffc94c4c7795bce0fdde8847ab1c894903cb20779aedb6ca8628aa9983a",
  },
});

const BINARYEN_ARTIFACTS = Object.freeze({
  "arm64-macos": "e441b48dc22163d209b4f05e44dc7210909b01237642b6c9ae48fd710a3ef83e",
  "x86_64-macos": "d209fadd8a894bdaf3bd3612a23c32a0af184d2f4a979b8c789e6e4f6a4de883",
  "aarch64-linux": "ba991f677edd9a21d2bc96c0144bc8ac5b112d4d98a3eb266e075e22e557df2a",
  "x86_64-linux": "b5bf1f0eaf17c63ee588ff7a5954dc8f6ce2c26989051c66f24dfe9ece3e46db",
  "arm64-windows": "e3eaed3d43bcbba867895e55f5e3e9fcfebf776bc4ed6ee59cae071f083cedb9",
  "x86_64-windows": "2f4edac1703a2f695254d6ff52ede03481e67db1f094915763d863158c17d9bc",
});

const LLVM_MINGW_ARTIFACTS = Object.freeze({
  x64: {
    file: `llvm-mingw-${LLVM_MINGW_VERSION}-ucrt-x86_64.zip`,
    sha256: "b9b68a4d276e16fa25802aaba458e4638f64b3884c290aaccdc2d87083b6ca35",
  },
  arm64: {
    file: `llvm-mingw-${LLVM_MINGW_VERSION}-ucrt-aarch64.zip`,
    sha256: "312593669435bd0bfc1a43ac3fba23c8b27e0610bade88b2738e5a01702a99ba",
  },
});

const GRAALVM_BASE = "https://gds.oracle.com/download/graal/25i2/archive";
const BINARYEN_BASE = `https://github.com/WebAssembly/binaryen/releases/download/version_${BINARYEN_VERSION}`;
const LLVM_MINGW_BASE = `https://github.com/mstorsjo/llvm-mingw/releases/download/${LLVM_MINGW_VERSION}`;
const GRAAL_OBJECT_HOST = "objectstorage.uk-london-1.oraclecloud.com";
const GRAAL_OBJECT_NAMESPACE = "lr0crfzcb4ml";
const GRAAL_OBJECT_BUCKET = "gds-artifacts";
// Oracle's GDS redirect includes an opaque object identifier.  Keep its
// shape constrained, but do not check in the identifier or a signed redirect
// token: either is vendor-controlled and can be rotated independently of the
// pinned archive and checksum.
const GRAAL_OBJECT_ID_PATTERN = "[A-Za-z0-9_-]{1,256}";

// Developer-tool downloads are deliberately a separate lane from the Minecraft
// CDN downloader.  These URLs are all fixed by this file; validation here also
// protects future callers from accidentally turning them into an arbitrary URL
// fetch primitive.  GitHub and Oracle may redirect to their own release hosts,
// so those hosts are allowed only after the initial URL has passed its exact
// vendor path check.
const TOOL_DOWNLOAD_ATTEMPTS = 4;
// GraalVM Web Image archives are large and Oracle's regional object-store
// response is chunked without a Content-Length. Keep a finite whole-transfer
// bound that works on a local network without making a download
// unbounded; callers/tests can still supply a tighter timeout.
const TOOL_DOWNLOAD_TIMEOUT_MS = 5 * 60_000;
const TOOL_RETRY_DELAY_MS = 250;
const TOOL_MAX_REDIRECTS = 5;
const TOOL_MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024 * 1024;
const TOOL_MAX_TEXT_BYTES = 1024 * 1024;
const TOOL_URL_RULES = Object.freeze({
  node: Object.freeze({
    hosts: new Set(["nodejs.org"]),
    redirectHosts: new Set(["nodejs.org"]),
    path: new RegExp(`^/dist/v${NODE_VERSION.replaceAll(".", "\\.")}/node-v${NODE_VERSION.replaceAll(".", "\\.")}-[a-z0-9-]+\\.(?:tar\\.gz|zip)$`),
  }),
  graal: Object.freeze({
    hosts: new Set(["gds.oracle.com"]),
    // The GDS URL normally serves directly.  Oracle's regional object-store
    // response is admitted only by graalObjectRedirectPath below; do not turn
    // this into a general Oracle object-storage allowlist.
    redirectHosts: new Set(["gds.oracle.com", "download.oracle.com", GRAAL_OBJECT_HOST]),
    path: /^\/download\/graal\/25i2\/archive\/graalvm-jdk-25i2-25\.0\.4_(?:macos-aarch64|linux-x64|linux-aarch64|windows-x64)_bin\.(?:tar\.gz|zip)$/,
    redirectPath: /^\/(?:graalvm|java)\//,
  }),
  binaryen: Object.freeze({
    hosts: new Set(["github.com"]),
    redirectHosts: new Set(["github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"]),
    path: new RegExp(`^/WebAssembly/binaryen/releases/download/version_${BINARYEN_VERSION}/binaryen-version_${BINARYEN_VERSION}-(?:${Object.keys(BINARYEN_ARTIFACTS).join("|")})\\.tar\\.gz(?:\\.sha256)?$`),
    redirectPath: /^\/github-production-release-asset\//,
  }),
  "llvm-mingw": Object.freeze({
    hosts: new Set(["github.com"]),
    redirectHosts: new Set(["github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"]),
    path: new RegExp(`^/mstorsjo/llvm-mingw/releases/download/${LLVM_MINGW_VERSION}/llvm-mingw-${LLVM_MINGW_VERSION}-ucrt-(?:x86_64|aarch64)\\.zip$`),
    redirectPath: /^\/github-production-release-asset\//,
  }),
});

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\\]\\]/g, "\\$&");
}

function graalObjectRedirectPath(pathname, expectedFile) {
  const file = String(expectedFile || "");
  if (!Object.values(GRAALVM_ARTIFACTS).some((artifact) => artifact.file === file)) return false;
  const escapedFile = escapeRegExp(file);
  return new RegExp(`^/p/${GRAAL_OBJECT_ID_PATTERN}/n/${GRAAL_OBJECT_NAMESPACE}/b/${GRAAL_OBJECT_BUCKET}/o/${GRAAL_OBJECT_ID_PATTERN}/${escapedFile}$`).test(pathname);
}

const PLATFORM_MATRIX = Object.freeze({
  "darwin-arm64": {
    node: "darwin-arm64", graal: "macos-aarch64", graalExt: "tar.gz", binaryen: "arm64-macos",
  },
  "darwin-x64": {
    binaryen: "x86_64-macos",
    unsupported: "receive-only: Oracle GraalVM 25i2 publishes no macOS x64 Web Image archive. Build on Apple Silicon, Linux, or Windows instead.",
  },
  "linux-x64": {
    node: "linux-x64", graal: "linux-x64", graalExt: "tar.gz", binaryen: "x86_64-linux",
  },
  "linux-arm64": {
    node: "linux-arm64", graal: "linux-aarch64", graalExt: "tar.gz", binaryen: "aarch64-linux",
  },
  "win32-x64": {
    node: "win-x64", graal: "windows-x64", graalExt: "zip", binaryen: "x86_64-windows",
  },
  "win32-arm64": {
    node: "win-arm64", graal: "windows-x64", graalExt: "zip", binaryen: "arm64-windows",
    emulated: "Windows ARM64 uses the pinned Windows x64 GraalVM builder under x64 emulation; Oracle does not publish a native ARM64 Web Image archive.",
  },
});

function normalizedWindowsArch(raw) {
  const value = String(raw || "").trim().toLowerCase();
  if (["amd64", "x64", "x86_64"].includes(value)) return "x64";
  if (["arm64", "aarch64"].includes(value)) return "arm64";
  return null;
}

// PROCESSOR_ARCHITEW6432 exposes the host architecture when a Windows x64
// process is running under emulation.  Prefer it over process.arch so an
// emulated x64 Node process still reports the ARM64 host and the deliberate
// Windows-x64 GraalVM mapping.  The Node archive itself remains selected by
// the process architecture because it must be runnable by this process.
function windowsHostArch(environment = process.env, executionArch = process.arch) {
  return normalizedWindowsArch(environment.PROCESSOR_ARCHITEW6432)
    || normalizedWindowsArch(environment.PROCESSOR_ARCHITECTURE)
    || normalizedWindowsArch(executionArch)
    || null;
}

function nodePlatform(platformName, executionArch) {
  if (platformName === "win32") {
    const arch = normalizedWindowsArch(executionArch);
    return arch === "arm64" ? "win-arm64" : arch === "x64" ? "win-x64" : null;
  }
  if (platformName === "darwin") return executionArch === "arm64" ? "darwin-arm64" : executionArch === "x64" ? "darwin-x64" : null;
  if (platformName === "linux") return executionArch === "arm64" ? "linux-arm64" : executionArch === "x64" ? "linux-x64" : null;
  return null;
}

function platformKey(platformName = process.platform, executionArch = process.arch, environment = process.env) {
  const arch = platformName === "win32"
    ? windowsHostArch(environment, executionArch)
    : normalizedWindowsArch(executionArch);
  return arch ? `${platformName}-${arch}` : `${platformName}-${executionArch}`;
}

function platformConfigFor(platformName = process.platform, executionArch = process.arch, environment = process.env) {
  const key = platformKey(platformName, executionArch, environment);
  const config = PLATFORM_MATRIX[key];
  if (!config) die(`unsupported host ${key}; supported hosts are ${Object.keys(PLATFORM_MATRIX).join(", ")}`);
  if (config.unsupported) die(`unsupported host ${key}: ${config.unsupported}`);
  const selectedNode = nodePlatform(platformName, executionArch) || config.node;
  return { key, ...config, node: selectedNode };
}

function hostConfig() {
  return platformConfigFor();
}

function printMatrix() {
  console.log("MC-Web local build platform matrix");
  for (const [key, config] of Object.entries(PLATFORM_MATRIX)) {
    if (config.unsupported) {
      console.log(`  ${key}: unsupported — ${config.unsupported}`);
      continue;
    }
    console.log(`  ${key}: Node ${config.node}, GraalVM ${config.graal}, Binaryen ${config.binaryen}`
      + (config.emulated ? ` (${config.emulated})` : ""));
  }
  console.log("  Versions: Node v24.19.0, Oracle GraalVM 25.2.4 (25i2), Binaryen 131");
  console.log("  Checksums: downloaded archives are checked against locked vendor-published SHA-256 values and Binaryen sidecars.");
}

function spawnArgv(command, args) {
  if (process.platform === "win32" && /\.(cmd|bat)$/i.test(command)) {
    return [process.env.ComSpec || "cmd.exe", ["/c", command, ...args]];
  }
  return [command, args];
}

function run(command, args, options = {}) {
  return new Promise((resolveRun, rejectRun) => {
    const [actual, actualArgs] = spawnArgv(command, args);
    const child = spawn(actual, actualArgs, { stdio: "inherit", ...options });
    child.on("error", rejectRun);
    child.on("exit", (code) => code === 0
      ? resolveRun()
      : rejectRun(new Error(`${command} exited ${code}`)));
  });
}

function capture(command, args) {
  return new Promise((resolveCapture) => {
    const [actual, actualArgs] = spawnArgv(command, args);
    const child = spawn(actual, actualArgs, { stdio: ["ignore", "pipe", "pipe"] });
    let output = "";
    child.stdout.on("data", (data) => { output += data; });
    child.stderr.on("data", (data) => { output += data; });
    child.on("exit", () => resolveCapture(output));
    child.on("error", () => resolveCapture(""));
  });
}

async function sha256(path) {
  const hash = createHash("sha256");
  await pipeline(createReadStream(path), hash);
  return hash.digest("hex");
}

class ToolPolicyError extends Error {}
class ToolFatalError extends Error {}
class ToolRetryError extends Error {}

function toolUrl(raw, kind, { redirect = false, expectedFile = null } = {}) {
  const policy = TOOL_URL_RULES[kind];
  if (!policy) throw new ToolPolicyError(`unknown developer-tool URL class: ${kind}`);
  let url;
  try {
    url = new URL(raw);
  } catch {
    throw new ToolPolicyError(`${kind} URL is not valid: ${raw}`);
  }
  const host = url.hostname.toLowerCase();
  const port = url.port;
  let pathname;
  try {
    pathname = decodeURIComponent(url.pathname);
  } catch {
    throw new ToolPolicyError(`${kind} URL has an invalid escaped path: ${raw}`);
  }
  if (url.protocol !== "https:" || port || url.username || url.password || url.hash) {
    throw new ToolPolicyError(`${kind} URL must use HTTPS, the default port, and no userinfo/fragment: ${raw}`);
  }
  if (!pathname || pathname.includes("\\")
      || pathname.split("/").slice(1).some((part) => !part || part === "." || part === "..")) {
    throw new ToolPolicyError(`${kind} URL has an unsafe path: ${raw}`);
  }
  if (!redirect) {
    if (!policy.hosts.has(host) || !policy.path.test(pathname) || url.search) {
      throw new ToolPolicyError(`${kind} URL is outside the pinned vendor path: ${raw}`);
    }
  } else {
    if (!policy.redirectHosts.has(host)) {
      throw new ToolPolicyError(`${kind} redirect leaves the approved vendor hosts: ${raw}`);
    }
    if (policy.hosts.has(host)) {
      if (!policy.path.test(pathname)) {
        throw new ToolPolicyError(`${kind} redirect has an unapproved vendor path: ${raw}`);
      }
    } else if (kind === "graal" && host === GRAAL_OBJECT_HOST) {
      if (url.search || !graalObjectRedirectPath(pathname, expectedFile)) {
        throw new ToolPolicyError(`${kind} redirect has an unapproved object-storage path: ${raw}`);
      }
    } else if (!policy.redirectPath?.test(pathname)) {
      throw new ToolPolicyError(`${kind} redirect has an unapproved release path: ${raw}`);
    }
  }
  return url.href;
}

function transientToolStatus(status) {
  return status === 408 || status === 429 || status >= 500;
}

async function waitBeforeRetry(attempt, delayMs) {
  if (delayMs > 0) await new Promise((resolveWait) => setTimeout(resolveWait, delayMs * 2 ** attempt));
}

// Consume one complete response attempt.  Redirects are manual so every hop
// is validated before it can be contacted; the timer remains live while the
// body is being consumed, not merely while headers are being received.
async function withToolResponse(raw, {
  kind,
  attempts = TOOL_DOWNLOAD_ATTEMPTS,
  timeoutMs = TOOL_DOWNLOAD_TIMEOUT_MS,
  retryDelayMs = TOOL_RETRY_DELAY_MS,
  consume,
}) {
  const requested = toolUrl(raw, kind);
  const expectedFile = basename(new URL(requested).pathname);
  let last = "";
  for (let attempt = 0; attempt < attempts; attempt++) {
    let current = requested;
    try {
      for (let hop = 0; hop <= TOOL_MAX_REDIRECTS; hop++) {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeoutMs);
        let response;
        try {
          response = await fetch(current, { signal: controller.signal, redirect: "manual" });
        } catch (error) {
          clearTimeout(timer);
          throw error;
        }
        const location = response.headers?.get("location");
        if (response.status >= 300 && response.status < 400) {
          clearTimeout(timer);
          if (!location) throw new ToolPolicyError(`${kind} redirect has no Location header`);
          if (hop === TOOL_MAX_REDIRECTS) throw new ToolPolicyError(`${kind} exceeded redirect limit`);
          current = toolUrl(new URL(location, current).href, kind, { redirect: true, expectedFile });
          continue;
        }
        if (!response.ok) {
          clearTimeout(timer);
          const ErrorType = transientToolStatus(response.status) ? ToolRetryError : ToolFatalError;
          const error = new ErrorType(`GET ${current} -> HTTP ${response.status}`);
          throw error;
        }
        const observed = response.url && response.url !== current
          ? toolUrl(response.url, kind, { redirect: true, expectedFile })
          : current;
        try {
          if (!response.body) throw new ToolRetryError(`${kind} response has no body`);
          return await consume({ response, url: observed });
        } finally {
          clearTimeout(timer);
        }
      }
      throw new ToolPolicyError(`${kind} exceeded redirect limit`);
    } catch (error) {
      if (error instanceof ToolPolicyError || error instanceof ToolFatalError) throw error;
      last = error?.cause?.code || error?.message || String(error);
      if (attempt < attempts - 1) await waitBeforeRetry(attempt, retryDelayMs);
    }
  }
  throw new Error(`GET ${requested} failed after ${attempts} attempts (${last})`);
}

function normalizedSha256(value, label) {
  const digest = String(value ?? "").toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(digest)) throw new ToolPolicyError(`${label} is not SHA-256`);
  return digest;
}

async function readBoundedText(response, maxBytes, kind) {
  const chunks = [];
  let received = 0;
  for await (const chunk of Readable.fromWeb(response.body)) {
    const bytes = Buffer.byteLength(chunk);
    received += bytes;
    if (received > maxBytes) throw new ToolPolicyError(`${kind} response exceeds ${maxBytes} bytes`);
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function sidecarChecksum(url, {
  kind = "binaryen",
  attempts = TOOL_DOWNLOAD_ATTEMPTS,
  timeoutMs = TOOL_DOWNLOAD_TIMEOUT_MS,
  retryDelayMs = TOOL_RETRY_DELAY_MS,
} = {}) {
  const text = await withToolResponse(url, {
    kind, attempts, timeoutMs, retryDelayMs,
    consume: ({ response }) => readBoundedText(response, TOOL_MAX_TEXT_BYTES, `${kind} checksum`),
  });
  return normalizedSha256(text.trim().split(/\s+/)[0], `${kind} checksum`);
}

async function download(url, destination, {
  kind,
  expectedSha256 = null,
  expectedBytes = null,
  maxBytes = TOOL_MAX_DOWNLOAD_BYTES,
  attempts = TOOL_DOWNLOAD_ATTEMPTS,
  timeoutMs = TOOL_DOWNLOAD_TIMEOUT_MS,
  retryDelayMs = TOOL_RETRY_DELAY_MS,
} = {}) {
  const wanted = expectedSha256 === null ? null : normalizedSha256(expectedSha256, `${kind} archive checksum`);
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 0) throw new ToolPolicyError("invalid developer-tool byte cap");
  if (expectedBytes !== null && (!Number.isSafeInteger(expectedBytes) || expectedBytes < 0 || expectedBytes > maxBytes)) {
    throw new ToolPolicyError(`${kind} expected size exceeds the developer-tool byte cap`);
  }
  await mkdir(dirname(destination), { recursive: true });
  if (wanted && await exists(destination)) {
    const existing = await stat(destination);
    if (existing.size <= maxBytes
        && (expectedBytes === null || existing.size === expectedBytes)
        && await sha256(destination) === wanted) {
      return false;
    }
  }

  return withToolResponse(url, {
    kind, attempts, timeoutMs, retryDelayMs,
    consume: async ({ response, url: finalUrl }) => {
      const temp = `${destination}.part-${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
      try {
        const hash = createHash("sha256");
        let received = 0;
        const meter = new Transform({
          transform(chunk, encoding, callback) {
            const bytes = Buffer.byteLength(chunk);
            if (received + bytes > maxBytes || (expectedBytes !== null && received + bytes > expectedBytes)) {
              callback(new ToolPolicyError(`${kind} response exceeds its allowed size`));
              return;
            }
            received += bytes;
            hash.update(chunk);
            callback(null, chunk);
          },
        });
        await pipeline(Readable.fromWeb(response.body), meter, createWriteStream(temp, { flags: "wx" }));
        if (expectedBytes !== null && received !== expectedBytes) {
          throw new ToolRetryError(`${kind} size mismatch for ${finalUrl}: expected ${expectedBytes}, got ${received}`);
        }
        const actual = hash.digest("hex");
        if (wanted && actual !== wanted) {
          throw new ToolRetryError(`${kind} checksum mismatch for ${finalUrl}: expected ${wanted}, got ${actual}`);
        }
        // Another process may have committed a valid cache entry while this
        // request was in flight.  Preserve that valid entry instead of
        // replacing it with an equivalent download.
        if (wanted && await exists(destination)) {
          const current = await stat(destination);
          if (current.size <= maxBytes
              && (expectedBytes === null || current.size === expectedBytes)
              && await sha256(destination) === wanted) {
            return false;
          }
        }
        await rename(temp, destination);
        return true;
      } finally {
        await rm(temp, { force: true }).catch(() => {});
      }
    },
  });
}

async function verify(path, { checksumUrl = null, expectedSha256 = null, kind = "binaryen" } = {}) {
  const wanted = expectedSha256?.toLowerCase() ?? await sidecarChecksum(checksumUrl, { kind });
  normalizedSha256(wanted, `${path} checksum`);
  if (checksumUrl) {
    const sidecar = await sidecarChecksum(checksumUrl, { kind });
    if (sidecar !== wanted) {
      throw new Error(`vendor checksum changed at ${checksumUrl}\n  expected ${wanted}\n  got      ${sidecar}`);
    }
  }
  const actual = await sha256(path);
  if (actual !== wanted) throw new Error(`checksum mismatch for ${path}\n  expected ${wanted}\n  got      ${actual}`);
  say(`verified ${path}`);
}

async function fetchAndExtract({ url, kind, checksumUrl = null, expectedSha256 = null, destination, archiveName }) {
  await mkdir(destination, { recursive: true });
  const archive = join(HOME, archiveName);
  say(`downloading ${archiveName}`);
  const wanted = expectedSha256?.toLowerCase() ?? await sidecarChecksum(checksumUrl, { kind });
  const downloaded = await download(url, archive, { kind, expectedSha256: wanted });
  try {
    if (checksumUrl) {
      const sidecar = await sidecarChecksum(checksumUrl, { kind });
      if (sidecar !== wanted) {
        throw new Error(`vendor checksum changed at ${checksumUrl}\n  expected ${wanted}\n  got      ${sidecar}`);
      }
    }
    say(`verified ${archive}`);
    await run(process.platform === "win32" ? "tar.exe" : "tar", [
      "-xf", archive, "-C", destination, "--strip-components=1",
    ]);
    if (process.platform === "darwin") {
      await run("xattr", ["-d", "-r", "com.apple.quarantine", destination]).catch(() => {});
    }
  } catch (error) {
    if (downloaded) await rm(archive, { force: true });
    throw error;
  }
  await rm(archive, { force: true });
}

function javaHomes(path) {
  if (!path) return [];
  const absolute = resolve(path.replace(/^~(?=$|[\\/])/, homedir()));
  return [absolute, join(absolute, "Contents", "Home"), join(absolute, "Home")];
}

function releaseValue(release, key) {
  return release.match(new RegExp(`^${key}="([^"]+)"$`, "m"))?.[1] ?? "";
}

function versionTuple(version) {
  const match = version.match(/^(\d+)\.(\d+)(?:\.(\d+))?/);
  return match ? [Number(match[1]), Number(match[2]), Number(match[3] || 0)] : null;
}

function atLeastVersion(version, minimum) {
  const tuple = versionTuple(version);
  if (!tuple) return false;
  for (let index = 0; index < minimum.length; index++) {
    if (tuple[index] !== minimum[index]) return tuple[index] > minimum[index];
  }
  return true;
}

function graalReleaseArchitecture(release) {
  return String(releaseValue(release, "OS_ARCH") || releaseValue(release, "OS_ARCHITECTURE") || "").trim().toLowerCase();
}

function graalArchitectureAllowed(release, artifactKey) {
  // Windows ARM64 deliberately reuses the Windows x64 archive under OS
  // emulation. Reject an ARM64 or unidentifiable JDK instead of silently
  // accepting a native toolchain that Oracle does not publish for Web Image.
  if (artifactKey !== "windows-x64") return true;
  return ["amd64", "x86_64", "x64"].includes(graalReleaseArchitecture(release));
}

async function usableGraalVm(candidate, { artifactKey = null } = {}) {
  for (const home of javaHomes(candidate)) {
    const releasePath = join(home, "release");
    if (!(await exists(releasePath))) continue;
    const release = await readFile(releasePath, "utf8").catch(() => "");
    const javaVersion = releaseValue(release, "JAVA_VERSION");
    const graalVersion = releaseValue(release, "GRAALVM_VERSION");
    if (!/Oracle/i.test(releaseValue(release, "IMPLEMENTOR"))) continue;
    if (!graalArchitectureAllowed(release, artifactKey)) continue;
    // Validate the GraalVM distribution version, not just JAVA_VERSION: the
    // public 25.2.4 archive is based on JDK 25.0.4.
    if (!atLeastVersion(graalVersion, MIN_GRAALVM_VERSION)) continue;
    const requiredPresent = await Promise.all([
      hasExecutable(join(home, "bin"), "java"),
      exists(join(home, "lib", "svm", "builder", "svm.jar")),
      exists(join(home, "lib", "svm", "tools", "svm-wasm", "builder", "svm-wasm.jar")),
      exists(join(home, "jmods", "org.graalvm.webimage.api.jmod")),
    ]);
    const nativeImagePresent = await Promise.all([
      hasExecutable(join(home, "bin"), "native-image"),
      hasExecutable(join(home, "lib", "svm", "bin"), "native-image"),
    ]);
    if (requiredPresent.every(Boolean) && nativeImagePresent.some(Boolean)) return home;
  }
  return null;
}

async function existingGraalVm(host) {
  const candidates = [process.env.GRAALVM_HOME, process.env.JAVA_HOME, join(HOME, "toolchain"), join(homedir(), "graalvm")];
  for (const root of [join(homedir(), "graalvm"), join(homedir(), "Library", "Java", "JavaVirtualMachines")]) {
    for (const entry of await readdir(root, { withFileTypes: true }).catch(() => [])) {
      if (entry.name.toLowerCase().includes("graal") || entry.name.toLowerCase().includes("jdk-25")) {
        candidates.push(join(root, entry.name));
      }
    }
  }
  for (const candidate of candidates) {
    const home = await usableGraalVm(candidate, { artifactKey: host.graal });
    if (home) return home;
  }
  return null;
}

async function existingBinaryen() {
  const system = await capture(exe("wasm-as"), ["--version"]);
  if (/^wasm-as version \d+/m.test(system)) return { bin: null, version: system.trim() };
  const bin = join(HOME, "binaryen", "bin");
  if (await exists(join(bin, exe("wasm-as")))) return { bin, version: (await capture(join(bin, exe("wasm-as")), ["--version"])).trim() };
  return null;
}

function nodeArchive(host) {
  const ext = process.platform === "win32" ? "zip" : "tar.gz";
  const file = `node-v${NODE_VERSION}-${host.node}.${ext}`;
  return {
    file,
    url: `https://nodejs.org/dist/v${NODE_VERSION}/${file}`,
    checksums: `https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt`,
  };
}

async function installGraal(host) {
  const destination = join(HOME, "toolchain");
  const artifact = GRAALVM_ARTIFACTS[host.graal];
  if (!artifact) die(`no Oracle GraalVM ${GRAALVM_VERSION} archive is published for ${host.graal}`);
  await fetchAndExtract({
    url: `${GRAALVM_BASE}/${artifact.file}`,
    kind: "graal",
    expectedSha256: artifact.sha256,
    destination,
    archiveName: artifact.file,
  });
  const home = await usableGraalVm(destination, { artifactKey: host.graal });
  if (!home) die(`Oracle GraalVM extracted but does not contain a supported Web Image JDK: ${destination}`);
  return home;
}

async function installBinaryen(host) {
  const destination = join(HOME, "binaryen");
  const file = `binaryen-version_${BINARYEN_VERSION}-${host.binaryen}.tar.gz`;
  const expectedSha256 = BINARYEN_ARTIFACTS[host.binaryen];
  if (!expectedSha256) die(`no Binaryen ${BINARYEN_VERSION} checksum is pinned for ${host.binaryen}`);
  await fetchAndExtract({
    url: `${BINARYEN_BASE}/${file}`,
    kind: "binaryen",
    checksumUrl: `${BINARYEN_BASE}/${file}.sha256`,
    expectedSha256,
    destination,
    archiveName: file,
  });
  const bin = join(destination, "bin");
  if (!(await exists(join(bin, exe("wasm-as"))))) die(`Binaryen extracted but wasm-as is missing: ${bin}`);
  return bin;
}

async function installWindowsToolchain() {
  if (process.platform !== "win32") return null;
  if (!has("force-download")) {
    try {
      const installed = await loadWindowsToolchain(HOME);
      if (installed) {
        say(`using llvm-mingw adapter at ${installed.msvcRoot}`);
        return installed;
      }
    } catch (error) {
      say(`repairing llvm-mingw adapter: ${error?.message || error}`);
    }
  }
  const architecture = normalizedWindowsArch(process.arch);
  const artifact = LLVM_MINGW_ARTIFACTS[architecture];
  if (!artifact) die(`no llvm-mingw toolchain is pinned for Windows ${process.arch}`);
  const destination = join(HOME, "llvm-mingw");
  const compiler = join(destination, "bin", "x86_64-w64-mingw32-clang.exe");
  if (!(await exists(compiler)) || has("force-download")) {
    await fetchAndExtract({
      url: `${LLVM_MINGW_BASE}/${artifact.file}`,
      kind: "llvm-mingw",
      expectedSha256: artifact.sha256,
      destination,
      archiveName: artifact.file,
    });
  } else {
    say(`using llvm-mingw ${LLVM_MINGW_VERSION} at ${destination}`);
  }
  await run(process.execPath, [
    join(PROJECT, "tools", "oss-toolchain.mjs"),
    "--home", HOME,
    "--llvm-dir", destination,
    "--node-dir", dirname(process.execPath),
  ], { cwd: PROJECT });
  const toolchain = await loadWindowsToolchain(HOME);
  if (!toolchain) die("llvm-mingw adapter did not publish windows-toolchain.json");
  return toolchain;
}

async function verifyNode() {
  const major = Number(process.versions.node.split(".")[0]);
  if (major < MIN_NODE_MAJOR) die(`Node ${MIN_NODE_MAJOR}+ is required; this process is ${process.version}`);
}

function officialLauncherGuide() {
  if (process.platform === "darwin") {
    return "Optional local fallback: pass $HOME/Library/Application Support/minecraft with --mc-dir after the official Launcher has fetched vanilla 26.2.";
  }
  if (process.platform === "win32") {
    return "Optional local fallback: pass %APPDATA%\\.minecraft with --mc-dir after the official Launcher has fetched vanilla 26.2.";
  }
  return "Optional local fallback: pass ~/.minecraft with --mc-dir after the official Launcher has fetched vanilla 26.2.";
}

async function writeEnvironment(home, binaryenBin) {
  await mkdir(HOME, { recursive: true });
  const quote = (value) => `"${value.replaceAll("\\", "\\\\").replaceAll("\"", "\\\"")}"`;
  await writeFile(join(HOME, "toolchain.env"), [
    `GRAALVM_HOME=${quote(home)}`,
    `JAVA_HOME=${quote(home)}`,
    binaryenBin ? `MCWEB_BINARYEN_HOME=${quote(binaryenBin)}` : "# wasm-as is already on PATH",
    "",
  ].join("\n"));
}

async function main() {
  if (has("platform-matrix")) printMatrix();
  const host = hostConfig();
  say(`host ${host.key}`);
  if (host.emulated) say(`note: ${host.emulated}`);
  await verifyNode();
  if (has("dry-run")) {
    const graalArtifact = GRAALVM_ARTIFACTS[host.graal];
    const binaryenFile = `binaryen-version_${BINARYEN_VERSION}-${host.binaryen}.tar.gz`;
    const binaryenSha256 = BINARYEN_ARTIFACTS[host.binaryen];
    const node = nodeArchive(host);
    say(`dry-run: no downloads or writes`);
    say(`Node: ${node.url} (checksum list ${node.checksums})`);
    if (graalArtifact) {
      say(`GraalVM ${GRAALVM_VERSION} (25i2): ${GRAALVM_BASE}/${graalArtifact.file}`);
      say(`  expected SHA-256: ${graalArtifact.sha256}`);
    }
    if (host.binaryen && binaryenSha256) {
      say(`Binaryen ${BINARYEN_VERSION}: ${BINARYEN_BASE}/${binaryenFile} (.sha256)`);
      say(`  expected SHA-256: ${binaryenSha256}`);
    }
    if (process.platform === "win32") {
      const llvm = LLVM_MINGW_ARTIFACTS[normalizedWindowsArch(process.arch)];
      say(`llvm-mingw ${LLVM_MINGW_VERSION}: ${LLVM_MINGW_BASE}/${llvm.file}`);
      say(`  expected SHA-256: ${llvm.sha256}`);
    }
    say(`install root: ${HOME}`);
    say("Minecraft 26.2 inputs: official Mojang CDN download into ~/.mcweb/minecraft when --build/--verify is selected.");
    say(`Local fallback: ${officialLauncherGuide()}`);
    return;
  }

  await mkdir(HOME, { recursive: true });
  let home = has("force-download") ? null : await existingGraalVm(host);
  if (home) say(`using Oracle GraalVM at ${home}`);
  else home = await installGraal(host);

  let binaryen = has("force-download") ? null : await existingBinaryen();
  let binaryenBin = binaryen?.bin ?? null;
  if (binaryen) say(`using ${binaryen.version}`);
  else binaryenBin = await installBinaryen(host);
  const windowsToolchain = await installWindowsToolchain();
  await writeEnvironment(home, binaryenBin);
  const pathEntries = [
    ...(process.platform === "win32" ? [join(home, "bin")] : []),
    ...(binaryenBin ? [binaryenBin] : []),
    process.env.PATH || "",
  ].filter(Boolean);
  const baseEnv = {
    ...process.env,
    GRAALVM_HOME: home,
    JAVA_HOME: home,
    PATH: pathEntries.join(process.platform === "win32" ? ";" : ":"),
    ...(binaryenBin ? { MCWEB_BINARYEN_HOME: binaryenBin } : {}),
  };
  const { env, graalExtraArgs } = applyWindowsToolchain(baseEnv, windowsToolchain);

  say(`toolchain ready: GRAALVM_HOME=${home}`);
  if (binaryenBin) say(`toolchain ready: MCWEB_BINARYEN_HOME=${binaryenBin}`);
  if (windowsToolchain) say(`toolchain ready: llvm-mingw ${windowsToolchain.compiler}`);
  const mcDir = flag("mc-dir");
  const cacheDir = resolve((flag("cache-dir", join(HOME, "minecraft")) || "").replace(/^~/, homedir()));
  const downloadOnly = has("download-only");
  const useDownload = has("download") || downloadOnly || (!mcDir && !has("local-only"));
  const buildArgs = ["tools/build.mjs", "--out", join(PROJECT, "dist", "build")];
  if (useDownload) buildArgs.push("--download", "--cache-dir", cacheDir);
  else if (mcDir) buildArgs.push("--mc-dir", resolve(mcDir.replace(/^~/, homedir())));
  else buildArgs.push("--local-only");
  if (has("no-audio")) buildArgs.push("--no-audio");
  if (has("offline")) buildArgs.push("--offline");
  if (graalExtraArgs) buildArgs.push("--graal-extra-args", graalExtraArgs);
  say(useDownload
    ? `validating Mojang CDN 26.2 inputs in ${cacheDir}`
    : "validating local 26.2 inputs");
  // A CDN-backed install gets a real input-only gate before a long image build.
  // Local --mc-dir validation remains a no-write dry-run.
  await run(process.execPath, [...buildArgs, useDownload ? "--download-only" : "--dry-run"], { cwd: PROJECT, env });
  if (has("verify")) return;
  if (has("build") || has("run")) {
    await run(process.execPath, buildArgs, { cwd: PROJECT, env });
  }
  if (has("run")) {
    await run(process.execPath, ["tools/dev-server.mjs"], {
      cwd: PROJECT,
      env: { ...env, MC_WEB_PORT: process.env.MC_WEB_PORT || "4199" },
    });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => die(error?.message || String(error)));
}

export {
  download,
  fetchAndExtract,
  platformConfigFor,
  sidecarChecksum,
  toolUrl,
};
