"use strict";

// Local development launcher bridge. A supported local launcher account file
// is normally read by the local Node process; an explicitly selected file may
// also cross this loopback page->Node boundary for the same validation.
// Tokens never enter a URL, DOM node, browser storage, or diagnostic, and the
// Node endpoint is rejected unless it is a same-origin loopback request.
const DEFAULT_TARGET_POLICY = "*";
const BUILD_CACHE = "mcweb-dev-build-v1";
const BUILD_URL_PREFIX = "/dev/graal/";
const BUILD_POINTER_URL = `${BUILD_URL_PREFIX}current.json`;
const BUILD_STATUS_URL = "/mcweb/build/status";
const BUILD_START_URL = "/mcweb/build/start";
const AUTH_UPLOAD_URL = "/mcweb/auth/launcher-accounts";
const MAX_AUTH_UPLOAD_BYTES = 1024 * 1024;
const STREAMING_LOADER_VERSION = "instantiate-streaming-v1";
const MAX_RUNTIME_BYTES = Object.freeze({
  "minecraft-client.js": 2 * 1024 * 1024,
  "minecraft-client.js.wasm": 220 * 1024 * 1024,
});

let memoryProfile = null;
let localServer = null;
let serverReady = false;
let verifiedRuntime = null;
let runtimeAvailable = false;
let installStarted = false;
let authUploadStarted = false;
let microsoftSignInStarted = false;
let buildState = { status: "idle", message: "" };

const markPhase = (phase, detail = "") => globalThis.mcWebDevDiagnostics?.mark?.(phase, detail);

function profileKey(value) { return String(value || "").replace(/-/g, "").toLowerCase(); }
function profileMatches(a, b) { return Boolean(a && b && profileKey(a.id) === profileKey(b.id) && a.name === b.name); }
function launcherProviderLabel(provider) {
  if (provider === "microsoft-browser") return "Microsoft sign-in";
  return provider === "prismlauncher" ? "PrismLauncher" : "official Minecraft Launcher";
}

function clearLocalAuthState(message = "Account: no signed-in Minecraft account found. Sign in through a supported launcher. Then return to this page.") {
  memoryProfile = null;
  globalThis.mcWebDevAuthAccepted = false;
  globalThis.mcWebDevProfile = null;
  localServer = null;
  globalThis.mcWebDevGateway = null;
  const result = document.getElementById("dev-result");
  if (result) result.hidden = true;
  setStatus("dev-status", message, "locked");
  setStatus("gateway-status", "Multiplayer: waiting for account verification.", "locked");
  updateRuntimeUi(Boolean(verifiedRuntime));
}

function setStatus(id, message, state = "") {
  const element = document.getElementById(id);
  if (!element) return;
  element.textContent = String(message);
  element.dataset.state = state;
}

function validManifest(manifest) {
  if (manifest?.version !== 1 || manifest.loader !== STREAMING_LOADER_VERSION
      || !Array.isArray(manifest.files) || manifest.files.length !== 2) return false;
  const names = new Set(manifest.files.map((entry) => entry?.name));
  return names.size === 2 && names.has("minecraft-client.js") && names.has("minecraft-client.js.wasm")
    && manifest.files.every((entry) => MAX_RUNTIME_BYTES[entry.name]
      && Number.isSafeInteger(entry.bytes) && entry.bytes > 0 && entry.bytes <= MAX_RUNTIME_BYTES[entry.name]
      && /^[0-9a-f]{64}$/i.test(entry.sha256));
}

async function sha256(bytes) {
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
    (value) => value.toString(16).padStart(2, "0")).join("");
}

async function fetchJson(path) {
  const response = await fetch(path, { cache: "no-store", credentials: "same-origin" });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || `${path} returned HTTP ${response.status}`);
  return body;
}

function publishLocalGateway(config, session) {
  const targets = Array.isArray(config?.gateway?.allowedTargets)
    ? config.gateway.allowedTargets.map((entry) => String(entry).trim().toLowerCase()) : [];
  const wildcard = config?.gateway?.allowAnyTarget === true
    && targets.length === 1 && targets[0] === DEFAULT_TARGET_POLICY;
  const explicitList = targets.length > 0 && !targets.includes(DEFAULT_TARGET_POLICY);
  if (config?.auth?.mode !== "online" || (!wildcard && !explicitList)
      || config?.gateway?.transport !== "cloudflare-worker"
      || config?.gateway?.relayOrigin !== "https://tcp.wasm.click"
      || config?.gateway?.relayConfigured !== true
      || session?.authenticated !== true || !profileMatches(memoryProfile, session.profile)) {
    localServer = null;
    globalThis.mcWebDevGateway = null;
    const reason = config?.gateway?.relayConfigured === false
      ? "set MC_RELAY_WORKER_SECRET before starting the local server"
      : "the account or Cloudflare relay policy is not ready";
    setStatus("gateway-status", `Multiplayer: ${reason}.`, "locked");
    return false;
  }
  const socket = new URL(String(config.gateway.socketPath || "/mcweb/socket"), location.href);
  if (socket.origin !== location.origin || socket.pathname !== "/mcweb/socket"
      || socket.search || socket.hash || socket.username || socket.password) {
    localServer = null;
    globalThis.mcWebDevGateway = null;
    setStatus("gateway-status", "Multiplayer: the local server returned an invalid connection path.", "locked");
    return false;
  }
  socket.protocol = location.protocol === "https:" ? "wss:" : "ws:";
  socket.search = "";
  socket.hash = "";
  const gateway = Object.freeze({
    ready: true, protocolVersion: 1, authMode: "online", provider: "node", localNode: true,
    socketUrl: socket.toString(), socketPath: socket.pathname,
    httpOrigin: location.origin, allowAnyTarget: wildcard, allowedTargets: [...targets],
    explicitlyAllowedPrivateTargets: Array.isArray(config.gateway.explicitlyAllowedPrivateTargets)
      ? [...config.gateway.explicitlyAllowedPrivateTargets] : [],
    profile: { ...session.profile },
  });
  localServer = gateway;
  globalThis.mcWebDevGateway = gateway;
  globalThis.mcWebConfig = { ...config, gateway: { ...config.gateway, ...gateway } };
  setStatus("receiver-status", "Local server: connected.", "accepted");
  setStatus("gateway-status", `Multiplayer: ready for ${wildcard ? "public servers" : targets.join(", ")}.`, "accepted");
  markPhase("local-node-connected");
  return true;
}

async function refreshLocalServer() {
  try {
    const config = await fetchJson("/mcweb/config.json");
    serverReady = true;
    setStatus("receiver-status", "Local server: connected.", "accepted");
    const signIn = document.getElementById("microsoft-sign-in");
    if (signIn && !microsoftSignInStarted) {
      signIn.disabled = config?.auth?.interactive !== true;
      signIn.textContent = config?.auth?.interactive === true
        ? "SIGN IN WITH MICROSOFT" : "MICROSOFT SIGN-IN NOT CONFIGURED";
    }
    const sessionResponse = await fetch("/mcweb/auth/session", {
      cache: "no-store", credentials: "same-origin",
    });
    const session = await sessionResponse.json().catch(() => ({}));
    if (!sessionResponse.ok || session?.authenticated !== true) {
      clearLocalAuthState();
      updateInstallAvailability();
      return false;
    }
    memoryProfile = Object.freeze({ ...session.profile });
    globalThis.mcWebDevAuthAccepted = true;
    globalThis.mcWebDevProfile = { ...memoryProfile };
    globalThis.mcWebConfig = config;
    publishLocalGateway(config, session);
    const result = document.getElementById("dev-result");
    const title = document.getElementById("dev-result-title");
    const detail = document.getElementById("dev-result-detail");
    const provider = launcherProviderLabel(session.provider);
    if (result) result.hidden = false;
    if (title) title.textContent = `Signed in as ${memoryProfile.name}`;
    if (detail) detail.textContent = `Minecraft Services confirmed that this ${provider} account owns Minecraft. The account token stays in the local server.`;
    setStatus("dev-status", `Account: verified through ${provider}.`, "accepted");
    updateRuntimeUi(Boolean(verifiedRuntime));
    updateInstallAvailability();
    return Boolean(localServer);
  } catch (error) {
    serverReady = false;
    clearLocalAuthState();
    setStatus("receiver-status", "Local server: not reachable. Start the server. Then reload this page.", "locked");
    setStatus("gateway-status", `Multiplayer: ${String(error?.message || error)}`, "locked");
    updateRuntimeUi(Boolean(verifiedRuntime));
    updateInstallAvailability();
    return false;
  }
}

async function uploadLauncherAccounts(file) {
  if (!file) return;
  clearLocalAuthState("Account: checking the selected launcher file.");
  if (!/\.json$/i.test(String(file.name || ""))) {
    setStatus("dev-status", "Account: choose launcher_accounts.json or PrismLauncher's accounts.json.", "locked");
    return;
  }
  if (!Number.isSafeInteger(file.size) || file.size <= 0 || file.size > MAX_AUTH_UPLOAD_BYTES) {
    setStatus("dev-status", "Account: the selected file is empty or larger than 1 MB.", "locked");
    return;
  }
  authUploadStarted = true;
  setStatus("dev-status", "Account: checking the selected launcher file.", "reading");
  markPhase("launcher-json-upload-started");
  try {
    const response = await fetch(AUTH_UPLOAD_URL, {
      method: "POST",
      cache: "no-store",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: await file.text(),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || body?.authenticated !== true) {
      throw new Error(body.error || "The local server could not use the selected launcher file.");
    }
    markPhase("launcher-json-upload-validated");
    await refreshLocalServer();
  } catch (error) {
    markPhase("launcher-json-upload-failed");
    clearLocalAuthState(`Account: ${String(error?.message || "the selected launcher file could not be verified")}`);
  } finally {
    authUploadStarted = false;
    const input = document.getElementById("launcher-accounts-file");
    if (input) input.value = "";
    updateInstallAvailability();
  }
}

function updateRuntimeUi(installed) {
  const install = document.getElementById("install-locally");
  const remove = document.getElementById("delete-local-install");
  const play = document.getElementById("dev-play-button");
  if (install) { install.hidden = installed; install.disabled = installed; install.textContent = "INSTALL IN THIS BROWSER"; }
  if (remove) { remove.hidden = !installed; remove.disabled = !installed; }
  if (play) {
    play.disabled = !installed || !globalThis.mcWebDevAuthAccepted || !localServer;
    play.textContent = "PLAY MINECRAFT";
  }
}

function updateInstallAvailability() {
  const button = document.getElementById("install-locally");
  if (button) button.disabled = !runtimeAvailable || installStarted || Boolean(verifiedRuntime);
  const buildButton = document.getElementById("build-locally");
  if (buildButton) {
    const unavailable = buildState.status === "unavailable";
    buildButton.hidden = unavailable;
    buildButton.disabled = unavailable || !serverReady || installStarted || buildState.status === "running";
  }
}

function updateBuildUi() {
  const button = document.getElementById("build-locally");
  const progress = document.getElementById("build-progress");
  const bar = document.getElementById("build-progress-bar");
  const label = document.getElementById("build-progress-label");
  const help = document.getElementById("build-help");
  const status = document.getElementById("build-status");
  const running = buildState.status === "running";
  if (progress) progress.hidden = !running;
  if (bar) bar.style.width = running ? "72%" : buildState.status === "succeeded" ? "100%" : "0%";
  if (label) {
    label.hidden = !running && buildState.status !== "failed";
    label.textContent = buildState.message || "";
  }
  if (help) help.hidden = buildState.status === "unavailable"
    || (runtimeAvailable && buildState.status !== "failed");
  if (button) {
    button.hidden = buildState.status === "unavailable";
    button.textContent = running ? "BUILDING GAME…"
      : buildState.status === "failed" ? "RETRY BUILD"
        : runtimeAvailable ? "REBUILD GAME" : "BUILD GAME FOR BROWSER";
  }
  if (status) {
    if (buildState.status === "unavailable") {
      setStatus("build-status", `Build: ${buildState.message || "the build button is unavailable. Build from the terminal, then restart the server."}`, "accepted");
    } else if (running) setStatus("build-status", `Build: ${buildState.message || "building the game for this browser."}`, "reading");
    else if (buildState.status === "failed") setStatus("build-status", `Build: ${buildState.message}`, "locked");
    else if (runtimeAvailable) setStatus("build-status", "Build: ready to install in this browser.", "accepted");
    else setStatus("build-status", "Build: none found. Click Build game for browser.", "locked");
  }
  updateInstallAvailability();
}

async function refreshBuildStatus() {
  try {
    buildState = await fetchJson(BUILD_STATUS_URL);
    const manifestResponse = await fetch("/mcweb/runtime-manifest", {
      cache: "no-store", credentials: "same-origin",
    });
    runtimeAvailable = manifestResponse.ok;
    if (runtimeAvailable && buildState.status === "failed") buildState = { ...buildState, status: "succeeded" };
  } catch (error) {
    runtimeAvailable = false;
    buildState = { status: "failed", message: String(error?.message || "the local build status is unavailable") };
  }
  updateBuildUi();
  return runtimeAvailable;
}

async function startLocalBuild() {
  if (!serverReady) throw new Error("The local server is not connected.");
  if (buildState.status === "running") return;
  const button = document.getElementById("build-locally");
  if (button) button.disabled = true;
  buildState = { status: "running", message: "preparing the local Minecraft installation" };
  updateBuildUi();
  markPhase("local-build-requested");
  try {
    const response = await fetch(BUILD_START_URL, {
      method: "POST", cache: "no-store", credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.error || `local build request returned HTTP ${response.status}`);
    buildState = body;
    updateBuildUi();
    markPhase("local-build-started");
  } catch (error) {
    buildState = { status: "failed", message: String(error?.message || error) };
    updateBuildUi();
    throw error;
  }
}

async function cacheRuntime(manifest, artifacts) {
  const cache = await caches.open(BUILD_CACHE);
  const version = `v-${manifest.files.map((entry) => entry.sha256.slice(0, 12)).join("-")}`;
  for (const entry of manifest.files) {
    const bytes = artifacts.get(entry.name);
    const contentType = entry.name.endsWith(".wasm") ? "application/wasm" : "text/javascript; charset=utf-8";
    await cache.put(new URL(`${BUILD_URL_PREFIX}${entry.name}`, location.origin), new Response(bytes, {
      headers: { "Content-Type": contentType, "Content-Length": String(entry.bytes), "Cache-Control": "no-store" },
    }));
  }
  const pointer = { version, manifest };
  await cache.put(new URL(BUILD_POINTER_URL, location.origin), new Response(JSON.stringify(pointer), {
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  }));
  return { version, manifest, source: "local-node" };
}

async function readCachedRuntime() {
  try {
    const cache = await caches.open(BUILD_CACHE);
    const response = await cache.match(new URL(BUILD_POINTER_URL, location.origin));
    if (!response) return null;
    const pointer = await response.json();
    if (!/^v-[0-9a-f-]{20,80}$/i.test(String(pointer?.version || "")) || !validManifest(pointer.manifest)) return null;
    for (const entry of pointer.manifest.files) {
      const cached = await cache.match(new URL(`${BUILD_URL_PREFIX}${entry.name}`, location.origin));
      if (!cached || Number(cached.headers.get("content-length")) !== entry.bytes) return null;
    }
    return { version: pointer.version, manifest: pointer.manifest, source: "cache" };
  } catch { return null; }
}

function markRuntimeInstalled(runtime, message = null) {
  verifiedRuntime = runtime;
  installStarted = true;
  globalThis.mcWebDevRuntime = Object.freeze({ version: runtime.version, manifest: runtime.manifest });
  globalThis.mcWebDevRuntimeReady = true;
  markPhase("build-verified", runtime.source || "local-node");
  updateRuntimeUi(true);
  setStatus("runtime-status", message || `Browser: installed ${runtime.version}.`, "accepted");
  const note = document.getElementById("dev-play-note");
  if (note) note.textContent = "This browser verified and stored the two build files. The files did not leave this computer.";
}

async function installLocally() {
  if (!serverReady) throw new Error("The local server is not connected.");
  if (!runtimeAvailable) throw new Error("Build the game for the browser first.");
  const button = document.getElementById("install-locally");
  installStarted = true;
  updateInstallAvailability();
  if (button) button.textContent = "INSTALLING IN BROWSER…";
  markPhase("local-build-install-requested");
  setStatus("runtime-status", "Browser: reading the local build details.", "reading");
  try {
    const manifest = await fetchJson("/mcweb/runtime-manifest");
    if (!validManifest(manifest)) throw new Error("local Node returned a non-allowlisted build manifest");
    const artifacts = new Map();
    for (const entry of manifest.files) {
      const response = await fetch(`/graal/${encodeURIComponent(entry.name)}`, { cache: "no-store" });
      if (!response.ok) throw new Error(`could not read ${entry.name} from the local Node process`);
      const bytes = await response.arrayBuffer();
      if (bytes.byteLength !== entry.bytes || await sha256(bytes) !== entry.sha256.toLowerCase()) throw new Error(`${entry.name} failed local hash verification`);
      artifacts.set(entry.name, bytes);
      markPhase("local-build-artifact-verified", entry.name);
    }
    markRuntimeInstalled(await cacheRuntime(manifest, artifacts), "Browser: build installed and verified.");
    markPhase("local-build-committed");
  } finally {
    installStarted = false;
    updateInstallAvailability();
    if (button && !verifiedRuntime) button.textContent = "INSTALL IN THIS BROWSER";
  }
}

async function deleteLocalInstall() {
  if (typeof globalThis.confirm !== "function" || !globalThis.confirm("Remove the installed build from this browser?")) return;
  await caches.delete(BUILD_CACHE);
  verifiedRuntime = null;
  installStarted = false;
  globalThis.mcWebDevRuntime = null;
  globalThis.mcWebDevRuntimeReady = false;
  markPhase("build-deleted");
  updateRuntimeUi(false);
  updateInstallAvailability();
  setStatus("runtime-status", "Browser: installed build removed.", "locked");
}

const packToggle = document.getElementById("pack-toggle");
const packBody = document.getElementById("pack-body");
const packDrop = document.getElementById("pack-drop");
const packList = document.getElementById("pack-list");
const packStatus = document.getElementById("pack-status");
const packFileInput = document.getElementById("pack-file-input");
const packDirInput = document.getElementById("pack-dir-input");
let packModule = null;

async function packs() {
  if (!packModule) {
    await new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = "/dev/resource-packs.js?v=20260823-local-packs";
      script.onload = resolve;
      script.onerror = () => reject(new Error("Could not load the pack manager"));
      document.body.append(script);
    });
    packModule = globalThis.mcWebResourcePacks;
  }
  return packModule;
}

const megabytes = (bytes) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;

async function refreshPackList() {
  const installed = await (await packs()).list();
  packList.replaceChildren(...installed.map((pack) => {
    const row = document.createElement("li");
    const label = document.createElement("strong");
    label.textContent = pack.name;
    const detail = document.createElement("span");
    detail.textContent = `${pack.files} files · ${megabytes(pack.bytes)}`;
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "pack-remove";
    remove.textContent = "REMOVE";
    remove.addEventListener("click", () => packTask(async () => {
      await (await packs()).remove(pack.name);
      packStatus.textContent = `Removed ${pack.name}.`;
    }));
    row.append(label, detail, remove);
    return row;
  }));
}

async function packTask(work) {
  for (const button of packBody.querySelectorAll("button")) button.disabled = true;
  try {
    await work();
  } catch (error) {
    packStatus.textContent = error?.message || String(error);
  } finally {
    for (const button of packBody.querySelectorAll("button")) button.disabled = false;
    await refreshPackList().catch(() => {});
  }
}

function reportInstalled(result) {
  packStatus.textContent =
    `Installed ${result.name}: ${result.files} files, ${megabytes(result.bytes)}.`;
}

function installOne(file) {
  return packTask(async () => {
    const api = await packs();
    reportInstalled(await api.install(file, (progress) => {
      if (progress.stage === "unpacking" && progress.total) {
        packStatus.textContent = `Unpacking ${progress.done || 0}/${progress.total}…`;
      } else if (progress.stage) {
        packStatus.textContent = `${progress.stage}…`;
      }
    }));
  });
}

async function readDroppedDirectory(entry, prefix, out) {
  const reader = entry.createReader();
  for (;;) {
    const batch = await new Promise((resolve, reject) =>
      reader.readEntries(resolve, reject));
    if (batch.length === 0) return out;
    for (const child of batch) {
      const path = `${prefix}${child.name}`;
      if (child.isDirectory) await readDroppedDirectory(child, `${path}/`, out);
      else {
        const file = await new Promise((resolve, reject) => child.file(resolve, reject));
        Object.defineProperty(file, "webkitRelativePath", { value: path });
        out.push(file);
      }
    }
  }
}

if (typeof document !== "undefined") {
  globalThis.mcWebDevAuthAccepted = false;
  globalThis.mcWebDevProfile = null;
  globalThis.mcWebDevGateway = null;
  globalThis.mcWebDevRuntimeReady = false;
  markPhase("dev-shell-ready");
  updateRuntimeUi(false);

  document.getElementById("install-locally")?.addEventListener("click", () => installLocally().catch((error) => {
    installStarted = false;
    updateInstallAvailability();
    setStatus("runtime-status", `Browser: ${String(error?.message || error)}`, "locked");
  }));
  document.getElementById("build-locally")?.addEventListener("click", () => startLocalBuild().catch((error) => {
    setStatus("build-status", `Build: ${String(error?.message || error)}`, "locked");
  }));
  document.getElementById("launcher-accounts-file")?.addEventListener("change", (event) => {
    const file = event.currentTarget.files?.[0] || null;
    void uploadLauncherAccounts(file);
  });
  document.getElementById("microsoft-sign-in")?.addEventListener("click", async (event) => {
    const button = event.currentTarget;
    microsoftSignInStarted = true;
    button.disabled = true;
    button.textContent = "OPENING MICROSOFT…";
    setStatus("dev-status", "Account: opening Microsoft sign-in.", "reading");
    markPhase("microsoft-sign-in-started");
    try {
      await globalThis.mcWebMicrosoftAuth.start();
    } catch (error) {
      microsoftSignInStarted = false;
      button.disabled = false;
      button.textContent = "SIGN IN WITH MICROSOFT";
      setStatus("dev-status", `Account: ${String(error?.message || error)}`, "locked");
      markPhase("microsoft-sign-in-failed");
    }
  });
  document.getElementById("delete-local-install")?.addEventListener("click", deleteLocalInstall);
  document.getElementById("clear-runtime-diagnostics")?.addEventListener("click", async () => {
    await globalThis.mcWebDevDiagnostics?.clear?.();
  });
  packToggle?.addEventListener("click", async () => {
    const open = packBody.hidden;
    packBody.hidden = !open;
    packToggle.setAttribute("aria-expanded", String(open));
    if (open) await packTask(refreshPackList);
  });

  packDrop?.addEventListener("dragover", (event) => {
    event.preventDefault();
    packDrop.classList.add("pack-drop-active");
  });
  packDrop?.addEventListener("dragleave", () => packDrop.classList.remove("pack-drop-active"));
  packDrop?.addEventListener("drop", async (event) => {
    event.preventDefault();
    packDrop.classList.remove("pack-drop-active");
    const entries = [...event.dataTransfer.items]
      .map((item) => item.webkitGetAsEntry?.())
      .filter(Boolean);
    for (const entry of entries) {
      if (entry.isDirectory) {
        await packTask(async () => {
          const files = await readDroppedDirectory(entry, `${entry.name}/`, []);
          reportInstalled(await (await packs()).installDirectory(files));
        });
      } else {
        await installOne(await new Promise((resolve, reject) => entry.file(resolve, reject)));
      }
    }
  });

  packFileInput?.addEventListener("change", async () => {
    for (const file of packFileInput.files) await installOne(file);
    packFileInput.value = "";
  });
  packDirInput?.addEventListener("change", async () => {
    const picked = packDirInput.files;
    if (picked.length > 0) {
      await packTask(async () => {
        reportInstalled(await (await packs()).installDirectory(picked));
      });
    }
    packDirInput.value = "";
  });
  document.getElementById("pack-pick-file")?.addEventListener("click", () => packFileInput.click());
  document.getElementById("pack-pick-dir")?.addEventListener("click", () => packDirInput.click());
  for (const button of document.querySelectorAll(".dev-copy")) {
    button.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(button.dataset.copy || "");
        button.textContent = "COPIED";
        setTimeout(() => { button.textContent = "COPY"; }, 1200);
      } catch { button.textContent = "SELECT"; }
    });
  }
  document.getElementById("dev-play-button")?.addEventListener("click", async (event) => {
    if (!globalThis.mcWebDevAuthAccepted || !globalThis.mcWebDevRuntimeReady || !localServer) return;
    const button = event.currentTarget;
    await globalThis.mcWebDevDiagnostics?.ready?.();
    button.disabled = true;
    button.textContent = "STARTING…";
    markPhase("play-requested");
    markPhase("boot-attempt-started");
    setStatus("runtime-status", "Browser: starting Minecraft.", "reading");
    try {
      await globalThis.mcWebRuntime?.start?.();
      markPhase("boot-healthy");
    } catch (error) {
      markPhase("boot-failed", String(error?.message || error));
      setStatus("runtime-status", `Browser: ${String(error?.message || error)}`, "locked");
      button.disabled = false;
      button.textContent = "PLAY MINECRAFT";
    }
  });

  (async () => {
    const params = new URLSearchParams(location.search);
    if (params.get("mcweb_auth") === "error") {
      setStatus("dev-status", `Account: ${params.get("message") || "Microsoft sign-in was cancelled."}`, "locked");
      history.replaceState(null, "", "/");
    } else if (params.get("mcweb_auth") === "success") {
      markPhase("microsoft-sign-in-completed");
      history.replaceState(null, "", "/");
    }
    verifiedRuntime = await readCachedRuntime();
    if (verifiedRuntime) markRuntimeInstalled(verifiedRuntime, "Browser: installed build restored and verified.");
    await globalThis.mcWebDevDiagnostics?.ready?.();
  })().catch(() => {});
  void refreshBuildStatus();
  void refreshLocalServer();
  setInterval(() => {
    void refreshLocalServer();
    void refreshBuildStatus();
  }, 2000);
}
