import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { createConnection } from "node:net";
import test from "node:test";
import { runInNewContext } from "node:vm";
import {
  sameOriginRequest,
  trustedLauncherUploadPeer,
} from "../tools/dev-server-policy.mjs";

const ROOT = new URL("..", import.meta.url).pathname;

function startServer(allow = "*", extraEnv = {}) {
  const child = spawn(process.execPath, ["tools/dev-server.mjs"], {
    cwd: ROOT,
    env: {
      ...process.env,
      MC_WEB_ROOT: "web",
      MC_WEB_PORT: "0",
      MCWEB_LAUNCHER_ACCOUNTS: "/private/tmp/mcweb-local-route-test-no-account.json",
      MC_RELAY_ALLOW: allow,
      ...extraEnv,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  return new Promise((resolve, reject) => {
    let output = "";
    const onData = (chunk) => {
      output += chunk;
      const match = output.match(/port checkpoint: http:\/\/127\.0\.0\.1:(\d+)/);
      if (match) {
        child.stdout.off("data", onData);
        resolve({ child, port: Number(match[1]), get output() { return output; } });
      }
    };
    child.stdout.on("data", onData);
    child.once("error", reject);
    child.once("exit", (code) => {
      if (code !== null && code !== 0) reject(new Error(`server exited ${code}: ${output}`));
    });
  });
}

test("launcher uploads require a loopback peer and same-origin request", () => {
  const loopback = {
    socket: { remoteAddress: "127.0.0.1" },
    headers: {
      host: "127.0.0.1:4199",
      origin: "http://127.0.0.1:4199",
    },
  };
  assert.equal(trustedLauncherUploadPeer(loopback), true);
  assert.equal(sameOriginRequest(loopback), true);
  assert.equal(trustedLauncherUploadPeer({
    ...loopback,
    socket: { remoteAddress: "::1" },
    headers: { host: "localhost:4199", origin: "http://localhost:4199" },
  }), true);

  assert.equal(trustedLauncherUploadPeer({
    ...loopback,
    socket: { remoteAddress: "172.18.0.2" },
  }), false);
  assert.equal(trustedLauncherUploadPeer({
    ...loopback,
    headers: { ...loopback.headers, host: "192.168.1.20:4199", origin: "http://192.168.1.20:4199" },
  }), false);
  assert.equal(sameOriginRequest({
    ...loopback,
    headers: { ...loopback.headers, origin: "http://evil.example" },
  }), false);
  assert.equal(trustedLauncherUploadPeer({
    ...loopback,
    socket: { remoteAddress: "8.8.8.8" },
  }), false);
  assert.equal(trustedLauncherUploadPeer({
    ...loopback,
    socket: { remoteAddress: "127.0.0.1" },
    headers: { host: "public.example:4199", origin: "http://public.example:4199" },
  }), false);
});

test("the local Node process serves the dev shell and namespaced auth gateway", async (t) => {
  const running = await startServer();
  t.after(() => running.child.kill("SIGTERM"));
  assert.match(running.output, /port checkpoint: http:\/\/127\.0\.0\.1:\d+/);
  const base = `http://localhost:${running.port}`;

  const page = await fetch(`${base}/`);
  assert.equal(page.status, 200);
  const html = await page.text();
  assert.match(html, /MC-WEB \/ SELF-HOSTED/);
  assert.doesNotMatch(html, /view-login|view-install|view-play/);
  assert.match(html, /Win<\/kbd>\+<kbd>R/);
  assert.ok(html.includes("%APPDATA%\\.minecraft\\launcher_accounts.json"));
  assert.match(html, /Go → Go to Folder/);
  assert.ok(html.includes("~/Library/Application Support/minecraft/launcher_accounts.json"));
  assert.ok(html.includes("~/Library/Application Support/PrismLauncher/accounts.json"));
  assert.ok(html.includes("~/.minecraft/launcher_accounts.json"));
  assert.match(html, /sends the selected file only to this loopback Node process/i);
  assert.match(html, /PrismLauncher/);
  assert.match(html, /formatVersion/);
  assert.match(html, /<input[^>]+type=["']file["']/i);
  assert.match(html, /BUILD GAME FOR BROWSER/);
  assert.match(html, /INSTALL IN THIS BROWSER/);
  assert.match(html, /PLAY MINECRAFT/);
  assert.match(html, /BUILD FROM THE TERMINAL/);
  assert.match(html, /GitHub:\s*<a href="https:\/\/github\.com\/vano04\/mcweb">https:\/\/github\.com\/vano04\/mcweb<\/a>/);

  const configResponse = await fetch(`${base}/mcweb/config.json`);
  assert.equal(configResponse.status, 200);
  const config = await configResponse.json();
  assert.equal(config.auth.mode, "online");
  assert.equal(config.auth.provider, "official-launcher-or-prism");
  assert.equal(config.auth.interactive, false);
  assert.equal(config.auth.microsoftClientId, null);
  assert.equal(config.auth.launcherUploadPath, "/mcweb/auth/launcher-accounts");
  assert.deepEqual(config.gateway.allowedTargets, ["*"]);
  assert.equal(config.gateway.allowAnyTarget, true);
  assert.equal(config.gateway.transport, "cloudflare-worker");
  assert.equal(config.gateway.relayOrigin, "https://tcp.wasm.click");
  assert.equal(config.gateway.relayConfigured, false);
  assert.equal("fixedTarget" in config.gateway, false);

  const health = await fetch(`${base}/healthz`);
  assert.equal(health.status, 503);
  assert.deepEqual(await health.json(), {
    status: "degraded",
    runtime: "missing",
    relay: "integrated",
  });

  const session = await fetch(`${base}/mcweb/auth/session`);
  assert.equal(session.status, 401);
  const sessionBody = await session.json();
  assert.equal(sessionBody.authenticated, false);
  assert.equal("accessToken" in sessionBody, false);

  const identity = await fetch(`${base}/mcweb/identity`);
  assert.equal(identity.status, 404);
  assert.deepEqual(await identity.json(), { available: false });

  const manifest = await fetch(`${base}/mcweb/runtime-manifest`);
  assert.equal(manifest.status, 404);
  assert.match(await manifest.text(), /build artifacts are not available/);

  const buildStatus = await fetch(`${base}/mcweb/build/status`);
  assert.equal(buildStatus.status, 200);
  assert.equal((await buildStatus.json()).status, "idle");

  const refusedBuild = await fetch(`${base}/mcweb/build/start`, {
    method: "POST",
    headers: { Origin: "http://evil.example", "Content-Type": "application/json" },
    body: "{}",
  });
  assert.equal(refusedBuild.status, 403);
  assert.match((await refusedBuild.json()).error, /local same-origin build requests only/);

  const unchangedBuildStatus = await fetch(`${base}/mcweb/build/status`);
  assert.equal((await unchangedBuildStatus.json()).status, "idle");

  const invalidUpload = await fetch(`${base}/mcweb/auth/launcher-accounts`, {
    method: "POST",
    headers: { Origin: base, "Content-Type": "application/json" },
    body: "{not-json",
  });
  assert.equal(invalidUpload.status, 400);
  assert.equal((await invalidUpload.json()).code, "invalid-json");

  const options = await fetch(`${base}/mcweb/verify-profile-property`, { method: "OPTIONS" });
  assert.equal(options.status, 204);
});

test("Microsoft PKCE config is loopback-only and exposes the exact SPA callback", async (t) => {
  const clientId = "1d50bf3c-09c8-4378-9a47-7a89e34eb140";
  const running = await startServer(undefined, { MCWEB_MS_CLIENT_ID: clientId });
  t.after(() => running.child.kill("SIGTERM"));
  const base = `http://localhost:${running.port}`;
  const config = await fetch(`${base}/mcweb/config.json`).then((response) => response.json());
  assert.equal(config.auth.interactive, true);
  assert.equal(config.auth.microsoftClientId, clientId);
  assert.equal(config.auth.microsoftRedirectUri, `${base}/auth/callback.html`);
  assert.equal(config.auth.microsoftCompletePath, "/mcweb/auth/microsoft/complete");

  const callback = await fetch(`${base}/auth/callback.html`);
  assert.equal(callback.status, 200);
  assert.match(await callback.text(), /Finishing Microsoft sign-in/);

  const rejected = await fetch(`${base}/mcweb/auth/microsoft/complete`, {
    method: "POST",
    headers: { "content-type": "application/json", origin: "http://evil.example" },
    body: "{}",
  });
  assert.equal(rejected.status, 403);
});

test("the production proxy can enable loopback Microsoft completion without owning the public client id", async (t) => {
  const running = await startServer(undefined, {
    MCWEB_BROWSER_AUTH_PROXY: "1",
    MC_RELAY_WORKER_AUTH: "minecraft-session",
  });
  t.after(() => running.child.kill("SIGTERM"));
  const base = `http://localhost:${running.port}`;
  const config = await fetch(`${base}/mcweb/config.json`).then((response) => response.json());
  assert.equal(config.auth.interactive, true);
  assert.equal(config.auth.microsoftClientId, null);
  assert.equal(config.auth.microsoftCompletePath, "/mcweb/auth/microsoft/complete");
  assert.equal(config.gateway.relayConfigured, true);
  const rejected = await fetch(`${base}/mcweb/auth/minecraft-session`, {
    method: "POST",
    headers: { origin: "http://evil.example", "content-type": "application/json" },
    body: "{}",
  });
  assert.equal(rejected.status, 403);
});

test("the local runtime boots verified cache artifacts without a service worker", async (t) => {
  const running = await startServer();
  t.after(() => running.child.kill("SIGTERM"));
  const base = `http://127.0.0.1:${running.port}`;

  const worker = await fetch(`${base}/dev/runtime-sw.js`);
  assert.equal(worker.status, 404);

  const bootstrapResponse = await fetch(`${base}/dev/runtime-bootstrap.js`);
  assert.equal(bootstrapResponse.status, 200);
  const bootstrap = await bootstrapResponse.text();
  assert.doesNotMatch(bootstrap, /serviceWorker\.register|did not take control/);
  assert.match(bootstrap, /URL\.createObjectURL\(await loader\.blob\(\)\)/);
  assert.match(bootstrap, /URL\.createObjectURL\(await wasm\.blob\(\)\)/);
  const [host, serverWorker] = await Promise.all([
    fetch(`${base}/dev/webgpu-host.js`).then((response) => response.text()),
    fetch(`${base}/dev/server-worker.js`).then((response) => response.text()),
  ]);
  assert.match(host, /localRuntimeUrls\?\.wasm/);
  assert.match(host, /const loaderPath\s*=\s*localRuntimeUrls\?\.loader/);
  assert.match(host, /script\.src\s*=\s*localRuntimeUrls\.loader/);
  assert.match(serverWorker, /loaderPath\s*\|\|\s*wasmPath\.replace/);

  const manifest = {
    version: 1,
    loader: "instantiate-streaming-v1",
    files: [
      { name: "minecraft-client.js", bytes: 1, sha256: "a".repeat(64) },
      { name: "minecraft-client.js.wasm", bytes: 2, sha256: "b".repeat(64) },
    ],
  };
  const pointer = { version: `v-${"a".repeat(20)}`, manifest };
  const cachedResponse = (value) => ({
    headers: { get: (name) => name.toLowerCase() === "content-length" ? String(value) : null },
    json: async () => pointer,
    blob: async () => new Blob([new Uint8Array(value)]),
  });
  const cache = {
    match: async (url) => String(url).endsWith("current.json")
      ? cachedResponse(0) : cachedResponse(String(url).endsWith(".wasm") ? 2 : 1),
  };
  let legacyWorkerRemoved = false;
  const serviceWorker = {
    getRegistrations: async () => [{
      active: { scriptURL: `${base}/dev/runtime-sw.js` },
      unregister: async () => { legacyWorkerRemoved = true; },
    }],
  };
  const elements = new Map();
  const element = (id) => elements.get(id) || elements.set(id, {
    hidden: false, textContent: "", dataset: {}, focus: () => {}, removeAttribute: () => {},
  }).get(id);
  const body = {
    append: (script) => queueMicrotask(() => script.onload?.()),
    dataset: {},
    style: {},
  };
  const context = {
    URL,
    URLSearchParams,
    queueMicrotask,
    setTimeout,
    clearTimeout,
    location: { origin: base },
    navigator: { serviceWorker },
    caches: { open: async () => cache },
    document: {
      body,
      createElement: () => ({}),
      getElementById: element,
    },
    mcWebDevAuthAccepted: true,
    mcWebDevProfile: { id: "player", name: "Player" },
    mcWebDevGateway: {
      ready: true,
      provider: "node",
      localNode: true,
      allowAnyTarget: true,
      allowedTargets: ["*"],
      profile: { id: "player", name: "Player" },
      socketUrl: `${base.replace("http", "ws")}/mcweb/socket`,
    },
    mcWebDevRuntimeReady: true,
    mcWebDevDiagnostics: { mark: (phase) => phases.push(phase) },
  };
  const phases = [];
  runInNewContext(bootstrap, context, { filename: "runtime-bootstrap.js" });
  const result = await Promise.race([
    context.mcWebRuntime.start().then(() => "resolved", (error) => `error:${error.message}`),
    new Promise((resolve) => setTimeout(() => resolve("timeout"), 1000)),
  ]);
  assert.equal(result, "resolved");
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(legacyWorkerRemoved, true);
  assert.match(context.mcWebDevRuntimeUrls.loader, /^blob:/);
  assert.match(context.mcWebDevRuntimeUrls.wasm, /^blob:/);
  assert.deepEqual(phases, ["play-requested", "build-activation-started", "build-cache-activated", "host-modules-loaded"]);
});

test("the game canvas uses the full safe viewport without a fixed aspect ratio", async (t) => {
  const running = await startServer();
  t.after(() => running.child.kill("SIGTERM"));
  const base = `http://127.0.0.1:${running.port}`;
  const [css, html] = await Promise.all([
    fetch(`${base}/site.css`).then((response) => response.text()),
    fetch(`${base}/`).then((response) => response.text()),
  ]);
  assert.match(css, /#minecraft-canvas[\s\S]*width:\s*var\(--mc-safe-width\)/);
  assert.match(css, /#minecraft-canvas[\s\S]*height:\s*var\(--mc-safe-height\)/);
  assert.doesNotMatch(css, /mc-canvas-aspect|aspect-ratio:\s*16\s*\/\s*9|1\.7777778/);
  assert.doesNotMatch(html, /data-default-aspect|data-game-viewport/);
});

test("the local config exposes the Cloudflare Worker transport without pinning a target", async (t) => {
  const running = await startServer();
  t.after(() => running.child.kill("SIGTERM"));
  const configResponse = await fetch(`http://127.0.0.1:${running.port}/mcweb/config.json`);
  assert.equal(configResponse.status, 200);
  const config = await configResponse.json();
  assert.deepEqual(config.gateway.allowedTargets, ["*"]);
  assert.equal(config.gateway.allowAnyTarget, true);
  assert.equal(config.gateway.targetPolicy, "wildcard-public");
  assert.equal(config.gateway.transport, "cloudflare-worker");
  assert.equal(config.gateway.relayOrigin, "https://tcp.wasm.click");
});

test("the integrated gateway rejects a cross-origin WebSocket before target policy", async (t) => {
  const running = await startServer();
  t.after(() => running.child.kill("SIGTERM"));
  const response = await new Promise((resolve, reject) => {
    const socket = createConnection({ host: "127.0.0.1", port: running.port });
    let body = "";
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error("timed out waiting for cross-origin upgrade rejection"));
    }, 2000);
    socket.on("connect", () => socket.write(
      `GET /mcweb/socket?host=play.example.net&port=25565 HTTP/1.1\r\n`
      + `Host: 127.0.0.1:${running.port}\r\n`
      + "Connection: Upgrade\r\nUpgrade: websocket\r\n"
      + "Origin: http://evil.example\r\n"
      + "Sec-WebSocket-Key: dGVzdC1rZXk=\r\nSec-WebSocket-Version: 13\r\n\r\n",
    ));
    socket.on("data", (chunk) => { body += chunk.toString(); });
    socket.on("error", reject);
    socket.on("close", () => { clearTimeout(timer); resolve(body); });
  });
  assert.match(String(response), /^HTTP\/1\.1 403 Forbidden/);
});

test("the local server can disable in-page rebuilds", async (t) => {
  const running = await startServer(undefined, { MCWEB_DISABLE_LOCAL_BUILD: "1" });
  t.after(() => running.child.kill("SIGTERM"));
  const base = `http://127.0.0.1:${running.port}`;

  const status = await fetch(`${base}/mcweb/build/status`);
  assert.equal(status.status, 200);
  assert.deepEqual(await status.json(), {
    status: "unavailable",
    message: "Local in-page builds are disabled; build from the terminal and restart the server.",
    startedAt: null,
    finishedAt: null,
  });

  const rebuild = await fetch(`${base}/mcweb/build/start`, {
    method: "POST",
    headers: { Origin: base, "Content-Type": "application/json" },
    body: "{}",
  });
  assert.equal(rebuild.status, 503);
  assert.equal((await rebuild.json()).status, "unavailable");
});
