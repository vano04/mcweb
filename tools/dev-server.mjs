import { appendFileSync, createReadStream, existsSync, mkdirSync, statSync } from "node:fs";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { dirname, extname, join, normalize } from "node:path";
import {
  handleMinecraftGatewayHttp,
  handleLauncherAccountsUpload,
  handleMinecraftGatewayUpgrade,
  minecraftGatewayInfo,
} from "./mc-relay.mjs";
import {
  runtimeAvailable,
  runtimeManifest as readRuntimeManifest,
} from "./runtime-manifest.mjs";
import {
  isLoopbackAddress,
  sameOriginRequest,
  trustedLauncherUploadPeer,
} from "./dev-server-policy.mjs";

const configuredRoot = process.env.MC_WEB_ROOT || null;
function sourceRoot() {
  return normalize(join(process.cwd(), configuredRoot || "web"));
}
function runtimeRoot() {
  if (configuredRoot) return sourceRoot();
  return normalize(join(process.cwd(), existsSync(join(process.cwd(), "build/web-graal/graal"))
    ? "build/web-graal" : "web"));
}
const port = Number(process.env.MC_WEB_PORT || 4173);
const host = process.env.MC_WEB_HOST || "127.0.0.1";
const canonicalImage = process.env.MCWEB_IMAGE || "minecraft-client";
if (!/^[a-z0-9.-]+$/i.test(canonicalImage)) {
  throw new Error(`invalid MCWEB_IMAGE ${canonicalImage}`);
}
const gatewayInfo = minecraftGatewayInfo();

// The self-hosted server may build the player's local image in place. Keep the
// browser-facing state deliberately small: build output can contain private
// installation paths, so never stream compiler logs or environment values to
// the page. The complete build log remains in the Node process terminal.
const buildState = {
  status: process.env.MCWEB_DISABLE_LOCAL_BUILD === "1" ? "unavailable" : "idle",
  message: process.env.MCWEB_DISABLE_LOCAL_BUILD === "1"
    ? "Local in-page builds are disabled; build from the terminal and restart the server."
    : "No local Wasm image build is running.",
  startedAt: null,
  finishedAt: null,
  child: null,
};

function publicBuildState() {
  return {
    status: buildState.status,
    message: buildState.message,
    startedAt: buildState.startedAt,
    finishedAt: buildState.finishedAt,
  };
}

function buildArgs() {
  const args = ["tools/build.mjs"];
  const mcDir = String(process.env.MCWEB_BUILD_MC_DIR || "").trim();
  if (mcDir) args.push("--mc-dir", mcDir);
  if (process.env.MCWEB_BUILD_NO_AUDIO === "1") args.push("--no-audio");
  args.push("--out", join(process.cwd(), "dist", "build"));
  return args;
}

function startLocalBuild() {
  if (process.env.MCWEB_DISABLE_LOCAL_BUILD === "1") return false;
  if (buildState.child) return false;
  buildState.status = "running";
  buildState.message = "Preparing the local Minecraft installation…";
  buildState.startedAt = new Date().toISOString();
  buildState.finishedAt = null;

  const child = spawn(process.execPath, buildArgs(), {
    cwd: process.cwd(),
    env: { ...process.env },
    stdio: ["ignore", "pipe", "pipe"],
  });
  buildState.child = child;

  const readProgress = (chunk) => {
    const text = String(chunk);
    // Only expose fixed, non-sensitive phase labels to the browser.
    if (/building image/i.test(text)) buildState.message = "Building the local Wasm image… this can take about 9 minutes.";
    else if (/staging audio/i.test(text)) buildState.message = "Staging local audio assets…";
    else if (/packaging/i.test(text)) buildState.message = "Packaging the local Wasm image…";
    else if (/validating local 26\.2 inputs/i.test(text)) buildState.message = "Validating the local Minecraft 26.2 installation…";
  };
  child.stdout.on("data", readProgress);
  child.stderr.on("data", readProgress);
  child.stdout.pipe(process.stdout);
  child.stderr.pipe(process.stderr);
  child.once("error", (error) => {
    buildState.status = "failed";
    buildState.message = error?.code === "ENOENT"
      ? "The local build command could not be started. Check the Node installation and server checkout."
      : "The local Wasm image build failed. Check the server terminal for details, then try again.";
    buildState.finishedAt = new Date().toISOString();
    buildState.child = null;
  });
  child.once("exit", (code, signal) => {
    if (buildState.child !== child) return;
    buildState.child = null;
    buildState.finishedAt = new Date().toISOString();
    if (code === 0) {
      buildState.status = "succeeded";
      buildState.message = "The local Wasm image is ready. Install it in this browser.";
    } else {
      buildState.status = "failed";
      buildState.message = signal
        ? "The local Wasm image build was stopped. Check the server terminal and try again."
        : "The local Wasm image build failed. Check the server terminal for details, then try again.";
    }
  });
  return true;
}

function runtimeManifest() {
  return readRuntimeManifest(runtimeRoot());
}

/**
 * Cache tag for the installed runtime, derived from the image that is actually
 * on disk.
 *
 * The browser caches the Wasm under `mcweb-runtime-<image>-<cacheTag>` and only
 * re-downloads when that name changes. A hand-written constant meant every
 * rebuild was invisible to anyone who had already pressed INSTALL RUNTIME: they
 * kept playing the old image and none of the fixes appeared, while a fresh
 * Playwright profile (no cache) always got the new one — so the bug reproduced
 * for the player and not in the harness.
 *
 * Size + mtime is enough to change on every rebuild and costs no hashing of a
 * 139 MB file. MCWEB_CACHE_TAG overrides it for a deployment that wants a
 * stable, human-chosen tag.
 */
function runtimeCacheTag() {
  if (process.env.MCWEB_CACHE_TAG) return process.env.MCWEB_CACHE_TAG;
  const root = runtimeRoot();
  try {
    const wasm = statSync(join(root, "graal", `${canonicalImage}.js.wasm`));
    return `${wasm.size.toString(36)}-${Math.floor(wasm.mtimeMs).toString(36)}`;
  } catch {
    // No image on disk yet: fall back to a stable build label so the page still
    // loads and reports the real "runtime missing" error instead of a config failure.
    return "build";
  }
}
const types = new Map([
  [".css", "text/css; charset=utf-8"],
  [".html", "text/html; charset=utf-8"],
  [".js", "text/javascript; charset=utf-8"],
  [".map", "application/json; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".svg", "image/svg+xml"],
  [".webmanifest", "application/manifest+json"],
  [".ogg", "audio/ogg"],
  [".wasm", "application/wasm"]
]);

/*
 * Where the watchdog Worker's snapshots land.
 *
 * A hang on this port kills every diagnostic at once, because all of them are
 * delivered *by* the thread that hung: console lines, `mcWebGpu.stages()`, and CDP
 * evaluate all need the browser's main thread, and so does Playwright's own input
 * dispatch. The watchdog runs in a Worker and reports over HTTP, so it is the one
 * channel that keeps working; this file is its output, readable while the page is
 * still frozen.
 */
const watchdogLog = normalize(join(process.cwd(), process.env.MC_WEB_WATCHDOG_LOG || "build/watchdog.log"));
let lastWatchdogConsoleKey = "";
let lastWatchdogSample = null;

/** Keep the full snapshot on disk while making the hosting terminal usable. */
function reportWatchdogState(body) {
  try {
    const sample = JSON.parse(body);
    lastWatchdogSample = sample;
    if (sample.started) {
      const key = `started:${sample.label || ""}`;
      if (key !== lastWatchdogConsoleKey) {
        lastWatchdogConsoleKey = key;
        process.stdout.write(`[watchdog] started ${sample.label || "MC-Web runtime"}\n`);
      }
      return;
    }
    const control = sample.control || {};
    const stage = String(sample.primary?.text || "").slice(0, 140);
    const key = [
      sample.label,
      !!sample.stalled,
      sample.memoryMiB,
      control.gcStopped,
      control.gcAgentRefused,
      control.gcRequested,
      control.allocationGrowFailures,
      stage,
    ].join("|");
    if (key === lastWatchdogConsoleKey) return;
    lastWatchdogConsoleKey = key;
    process.stdout.write(
      `[watchdog] ${sample.stalled ? "STALLED" : "ok"}`
        + ` heap=${sample.memoryMiB ?? "?"}MiB`
        + ` gc=${control.gcStopped ?? "?"}`
        + ` agentRequests=${control.gcAgentRefused ?? "?"}`
        + ` pending=${control.gcRequested ?? 0}`
        + ` growFailures=${control.allocationGrowFailures ?? "?"}`
        + (stage ? ` stage=${stage}` : "")
        + "\n"
    );
  } catch {
    process.stdout.write("[watchdog] malformed snapshot (full body retained in watchdog log)\n");
  }
}

const server = createServer((request, response) => {
  const pathname = decodeURIComponent(new URL(request.url, "http://localhost").pathname);

  response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
  response.setHeader("Cross-Origin-Embedder-Policy", "require-corp");
  response.setHeader("Cache-Control", "no-store");

  if (pathname === "/mcweb/config.json" && request.method === "GET") {
    response.setHeader("Content-Type", "application/json; charset=utf-8");
    response.writeHead(200);
    response.end(JSON.stringify({
      version: 1,
      auth: {
        mode: gatewayInfo.authMode,
        required: gatewayInfo.authMode === "online",
        provider: gatewayInfo.authProvider,
        interactive: gatewayInfo.interactiveAuth,
        sessionPath: "/mcweb/auth/session",
        launcherUploadPath: "/mcweb/auth/launcher-accounts",
        skinPath: "/mcweb/auth/minecraft/skin",
      },
      // Recomputed per request: the image can be rebuilt while the server runs.
      runtime: { image: canonicalImage, cacheTag: runtimeCacheTag() },
      gateway: {
        socketPath: "/mcweb/socket",
        identityPath: "/mcweb/identity",
        profileVerificationPath: "/mcweb/verify-profile-property",
        // Only used when a pack host refuses the browser's direct read.
        packProxyPath: "/mcweb/pack",
        allowedTargets: gatewayInfo.allowedTargets,
        allowAnyTarget: gatewayInfo.allowAnyTarget,
        explicitlyAllowedPrivateTargets: gatewayInfo.explicitlyAllowedPrivateTargets,
        targetPolicy: gatewayInfo.targetPolicy,
      },
    }));
    return;
  }

  if (pathname === "/healthz" && (request.method === "GET" || request.method === "HEAD")) {
    // Do not hash the Wasm on every health probe. The full manifest
    // endpoint hashes once per stat signature; health only needs file presence
    // and metadata.
    const available = runtimeAvailable(runtimeRoot());
    response.setHeader("Content-Type", "application/json; charset=utf-8");
    response.writeHead(available ? 200 : 503);
    if (request.method === "HEAD") response.end();
    else response.end(JSON.stringify({
      status: available ? "ok" : "degraded",
      runtime: available ? "available" : "missing",
      relay: "integrated",
    }));
    return;
  }

  if (pathname === "/mcweb/build/status" && request.method === "GET") {
    response.setHeader("Content-Type", "application/json; charset=utf-8");
    response.writeHead(200);
    response.end(JSON.stringify(publicBuildState()));
    return;
  }

  if (pathname === "/mcweb/build/start" && request.method === "POST") {
    if (process.env.MCWEB_DISABLE_LOCAL_BUILD === "1") {
      response.writeHead(503, { "Content-Type": "application/json; charset=utf-8" });
      response.end(JSON.stringify(publicBuildState()));
      return;
    }
    // Building consumes the self-host's local Minecraft installation and can
    // spend several minutes at high CPU/RAM. Accept this capability only from
    // a same-origin loopback browser, never from a forwarded/public caller.
    if (!trustedLauncherUploadPeer(request) || !sameOriginRequest(request)) {
      response.writeHead(403, { "Content-Type": "application/json; charset=utf-8" });
      response.end(JSON.stringify({ error: "local same-origin build requests only" }));
      return;
    }
    if (buildState.child) {
      response.writeHead(409, { "Content-Type": "application/json; charset=utf-8" });
      response.end(JSON.stringify(publicBuildState()));
      return;
    }
    startLocalBuild();
    response.setHeader("Content-Type", "application/json; charset=utf-8");
    response.writeHead(202);
    response.end(JSON.stringify(publicBuildState()));
    return;
  }

  if (pathname === "/mcweb/runtime-manifest" && request.method === "GET") {
    const manifest = runtimeManifest();
    if (!manifest) {
      response.writeHead(404, { "Content-Type": "application/json; charset=utf-8" });
      response.end(JSON.stringify({ error: "local build artifacts are not available; build the local Wasm image first" }));
      return;
    }
    response.setHeader("Content-Type", "application/json; charset=utf-8");
    response.writeHead(200);
    response.end(JSON.stringify(manifest));
    return;
  }

  if (pathname === "/mcweb/auth/launcher-accounts" && request.method === "POST") {
    // The account document is accepted only by the loopback self-host. It is
    // never a public upload endpoint, and the relay keeps the parsed token in
    // memory only after validating it against Minecraft Services.
    if (!trustedLauncherUploadPeer(request) || !sameOriginRequest(request)) {
      response.writeHead(403, { "Content-Type": "application/json; charset=utf-8" });
      response.end(JSON.stringify({ authenticated: false, error: "local same-origin launcher uploads only" }));
      return;
    }
    handleLauncherAccountsUpload(request, response).catch(() => {
      if (!response.headersSent) {
        response.writeHead(503, { "cache-control": "no-store", "content-type": "application/json" });
      }
      response.end(JSON.stringify({ authenticated: false, error: "the launcher account could not be validated" }));
    });
    return;
  }

  if (handleMinecraftGatewayHttp(request, response)) return;

  if (pathname === "/mcweb-watchdog") {
    response.setHeader("Access-Control-Allow-Origin", "*");
    if (request.method === "OPTIONS") {
      response.writeHead(204);
      response.end();
      return;
    }
    let body = "";
    request.on("data", (chunk) => {
      body += chunk;
    });
    request.on("end", () => {
      const line = `${new Date().toISOString()} ${body}\n`;
      try {
        mkdirSync(dirname(watchdogLog), { recursive: true });
        appendFileSync(watchdogLog, line);
      } catch {
        // Never let diagnostics break the server.
      }
      reportWatchdogState(body);
      response.writeHead(204);
      response.end();
    });
    return;
  }

  if (pathname === "/mcweb-watchdog-state") {
    response.setHeader("Access-Control-Allow-Origin", "*");
    response.setHeader("Content-Type", "application/json; charset=utf-8");
    response.writeHead(200);
    response.end(JSON.stringify(lastWatchdogSample));
    return;
  }

  // The local launcher keeps generated build bytes under /graal and staged
  // audio under /mcweb-audio in the generated docroot while its source host
  // modules live under /dev. Map those verified runtime paths explicitly so
  // the service worker/audio host can address them without adding generated
  // files to the source copy.
  const runtimeRequest = pathname.startsWith("/dev/graal/")
    || pathname.startsWith("/graal/")
    || pathname.startsWith("/mcweb-audio/");
  const relative = pathname.startsWith("/dev/graal/")
    ? `graal/${pathname.slice("/dev/graal/".length)}`
    : pathname === "/" ? "index.html" : pathname.slice(1);
  // Always serve the checked-in launcher shell from `web/`. Generated builds
  // can contain an older copied index.html, but they are only the source of
  // the hash-verified runtime pair under `graal/`.
  const root = runtimeRequest ? runtimeRoot() : sourceRoot();
  const file = normalize(join(root, relative));

  if (!file.startsWith(root) || !existsSync(file) || !statSync(file).isFile()) {
    response.writeHead(404, {"Content-Type": "text/plain; charset=utf-8"});
    response.end("Not found\n");
    return;
  }

  const headers = {
    "Content-Type": types.get(extname(file)) || "application/octet-stream",
    "Content-Length": statSync(file).size,
  };
  // runtime-sw.js is intentionally kept under /dev/, but the launcher shell
  // at / must be its controlled client. Scope expansion is explicit and only
  // applies to this fixed, source-controlled worker path.
  if (pathname === "/dev/runtime-sw.js") headers["Service-Worker-Allowed"] = "/";
  response.writeHead(200, headers);
  if (request.method === "HEAD") response.end();
  else createReadStream(file).pipe(response);
});

server.on("upgrade", (request, socket) => {
  const pathname = new URL(request.url, "http://localhost").pathname;
  if (pathname !== "/mcweb/socket") {
    socket.end("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
    return;
  }

  // A browser gateway is a same-origin capability. The target allowlist below
  // remains the second boundary, protecting the machine from arbitrary dials.
  const origin = request.headers.origin;
  let sameOrigin = false;
  try {
    const parsed = new URL(String(origin));
    sameOrigin = (parsed.protocol === "http:" || parsed.protocol === "https:")
      && parsed.host === request.headers.host;
  } catch {
    // RFC 6455 browser clients always send Origin. Reject raw/opaque callers on
    // the integrated endpoint; the standalone relay remains available to CLI
    // diagnostics that intentionally do not have a page origin.
  }
  if (!sameOrigin) {
    socket.end("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");
    return;
  }
  handleMinecraftGatewayUpgrade(request, socket);
});

server.listen(port, host, () => {
  const listeningPort = server.address().port;
  const shownHost = host === "0.0.0.0" ? "127.0.0.1" : host;
  console.log(`Minecraft Web port checkpoint: http://${shownHost}:${listeningPort}`);
  console.log(`canonical image: ${canonicalImage}`);
  console.log(`authentication mode: ${gatewayInfo.authMode}`);
  // The WebSocket/TCP compatibility endpoint is deliberately part of this one
  // app process. Do not advertise a second "relay" service to users.
  console.log(`Server-side Minecraft TCP bridge: ws://${shownHost}:${listeningPort}/mcweb/socket`);
  console.log(`allowed targets: ${gatewayInfo.allowedTargets.join(", ")}`);
  if (gatewayInfo.allowedTargets.length === 0) {
    console.warn("WARNING: invalid MC_RELAY_ALLOW; the Minecraft gateway is fail-closed");
  }
});
