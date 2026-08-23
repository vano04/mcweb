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
let buildState = { status: "idle", message: "" };

const markPhase = (phase, detail = "") => globalThis.mcWebDevDiagnostics?.mark?.(phase, detail);

function profileKey(value) { return String(value || "").replace(/-/g, "").toLowerCase(); }
function profileMatches(a, b) { return Boolean(a && b && profileKey(a.id) === profileKey(b.id) && a.name === b.name); }
function launcherProviderLabel(provider) {
  return provider === "prismlauncher" ? "PrismLauncher" : "the official Minecraft Launcher";
}

function clearLocalAuthState(message = "No supported Minecraft launcher account is available or validated.") {
  memoryProfile = null;
  globalThis.mcWebDevAuthAccepted = false;
  globalThis.mcWebDevProfile = null;
  localServer = null;
  globalThis.mcWebDevGateway = null;
  const result = document.getElementById("dev-result");
  if (result) result.hidden = true;
  setStatus("dev-status", message, "locked");
  setStatus("gateway-status", "Online test locked until a Launcher session passes Minecraft Services checks.", "locked");
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
      || session?.authenticated !== true || !profileMatches(memoryProfile, session.profile)) {
    localServer = null;
    globalThis.mcWebDevGateway = null;
    setStatus("gateway-status", "Online test disabled: the local Node session or target policy is not ready.", "locked");
    return false;
  }
  const socket = new URL(String(config.gateway.socketPath || "/mcweb/socket"), location.href);
  if (socket.origin !== location.origin || socket.pathname !== "/mcweb/socket"
      || socket.search || socket.hash || socket.username || socket.password) {
    localServer = null;
    globalThis.mcWebDevGateway = null;
    setStatus("gateway-status", "Online test disabled: the local Node gateway path is invalid.", "locked");
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
  setStatus("receiver-status", "Local Node process connected.", "accepted");
  setStatus("gateway-status", `Online gateway ready; target policy ${wildcard ? "* (public servers)" : targets.join(", ")}.`, "accepted");
  markPhase("local-node-connected");
  return true;
}

async function refreshLocalServer() {
  try {
    const config = await fetchJson("/mcweb/config.json");
    serverReady = true;
    setStatus("receiver-status", "Self-hosted Node server connected.", "accepted");
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
    if (title) title.textContent = `${provider} account recognised.`;
    if (detail) detail.textContent = `${memoryProfile.name} passed the live Minecraft Services entitlement and profile checks. The token remains in the local Node process.`;
    setStatus("dev-status", `${provider} session validated by the local Node process.`, "accepted");
    updateRuntimeUi(Boolean(verifiedRuntime));
    updateInstallAvailability();
    return Boolean(localServer);
  } catch (error) {
    serverReady = false;
    clearLocalAuthState();
    setStatus("receiver-status", "The self-hosted Node server is not reachable.", "locked");
    setStatus("gateway-status", String(error?.message || error), "locked");
    updateRuntimeUi(Boolean(verifiedRuntime));
    updateInstallAvailability();
    return false;
  }
}

async function uploadLauncherAccounts(file) {
  if (!file) return;
  clearLocalAuthState("Validating the selected Launcher JSON in the local Node process…");
  if (!/\.json$/i.test(String(file.name || ""))) {
    setStatus("dev-status", "Choose the official launcher_accounts.json or PrismLauncher accounts.json file.", "locked");
    return;
  }
  if (!Number.isSafeInteger(file.size) || file.size <= 0 || file.size > MAX_AUTH_UPLOAD_BYTES) {
    setStatus("dev-status", "The launcher JSON file is empty or larger than the local upload limit.", "locked");
    return;
  }
  authUploadStarted = true;
  setStatus("dev-status", "Validating the selected Launcher JSON in the local Node process…", "reading");
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
      throw new Error(body.error || "the selected Launcher JSON was not accepted");
    }
    markPhase("launcher-json-upload-validated");
    await refreshLocalServer();
  } catch (error) {
    markPhase("launcher-json-upload-failed");
    clearLocalAuthState(String(error?.message || "the selected Launcher JSON could not be validated"));
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
  if (install) { install.hidden = installed; install.disabled = installed; install.textContent = "INSTALL LOCALLY"; }
  if (remove) { remove.hidden = !installed; remove.disabled = !installed; }
  if (play) {
    play.disabled = !installed || !globalThis.mcWebDevAuthAccepted || !localServer;
    play.textContent = installed ? "PLAY LOCALLY" : "PLAY";
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
    button.textContent = running ? "BUILDING…"
      : buildState.status === "failed" ? "RETRY BUILD"
        : runtimeAvailable ? "REBUILD WASM IMAGE" : "BUILD WASM IMAGE";
  }
  if (status) {
    if (buildState.status === "unavailable") {
      setStatus("build-status", buildState.message || "Local in-page builds are disabled; build from the terminal and restart the server.", "accepted");
    } else if (running) setStatus("build-status", buildState.message || "Building the local Wasm image…", "reading");
    else if (buildState.status === "failed") setStatus("build-status", buildState.message, "locked");
    else if (runtimeAvailable) setStatus("build-status", "A local Wasm image is available on this self-hosted server.", "accepted");
    else setStatus("build-status", "No local Wasm image is available yet. Build it from this self-hosted server, then install it in this browser.", "locked");
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
    buildState = { status: "failed", message: String(error?.message || "Local build status is unavailable.") };
  }
  updateBuildUi();
  return runtimeAvailable;
}

async function startLocalBuild() {
  if (!serverReady) throw new Error("The self-hosted Node server is not connected.");
  if (buildState.status === "running") return;
  const button = document.getElementById("build-locally");
  if (button) button.disabled = true;
  buildState = { status: "running", message: "Preparing the local Minecraft installation…" };
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
  setStatus("runtime-status", message || `Local build verified as ${runtime.version}.`, "accepted");
  const note = document.getElementById("dev-play-note");
  if (note) note.textContent = "Build artifacts are hash-verified in this browser. Online tests use the same-origin local Node gateway and its configured target policy.";
}

async function installLocally() {
  if (!serverReady) throw new Error("The self-hosted Node server is not connected.");
  if (!runtimeAvailable) throw new Error("Build the local Wasm image first.");
  const button = document.getElementById("install-locally");
  installStarted = true;
  updateInstallAvailability();
  if (button) button.textContent = "INSTALLING…";
  markPhase("local-build-install-requested");
  setStatus("runtime-status", "Reading the locally served build manifest…", "reading");
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
    markRuntimeInstalled(await cacheRuntime(manifest, artifacts), "Local build verified and committed to this browser.");
    markPhase("local-build-committed");
  } finally {
    installStarted = false;
    updateInstallAvailability();
    if (button && !verifiedRuntime) button.textContent = "INSTALL LOCALLY";
  }
}

async function deleteLocalInstall() {
  if (typeof globalThis.confirm !== "function" || !globalThis.confirm("Delete the locally installed build from this browser?")) return;
  await caches.delete(BUILD_CACHE);
  verifiedRuntime = null;
  installStarted = false;
  globalThis.mcWebDevRuntime = null;
  globalThis.mcWebDevRuntimeReady = false;
  markPhase("build-deleted");
  updateRuntimeUi(false);
  updateInstallAvailability();
  setStatus("runtime-status", "Local build deleted.", "locked");
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
    setStatus("runtime-status", String(error?.message || error), "locked");
  }));
  document.getElementById("build-locally")?.addEventListener("click", () => startLocalBuild().catch((error) => {
    setStatus("build-status", String(error?.message || error), "locked");
  }));
  document.getElementById("launcher-accounts-file")?.addEventListener("change", (event) => {
    const file = event.currentTarget.files?.[0] || null;
    void uploadLauncherAccounts(file);
  });
  document.getElementById("delete-local-install")?.addEventListener("click", deleteLocalInstall);
  document.getElementById("clear-runtime-diagnostics")?.addEventListener("click", async () => {
    await globalThis.mcWebDevDiagnostics?.clear?.();
  });
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
    await globalThis.mcWebDevDiagnostics?.ready?.();
    event.currentTarget.disabled = true;
    event.currentTarget.textContent = "STARTING…";
    markPhase("play-requested");
    markPhase("boot-attempt-started");
    setStatus("runtime-status", "Activating the verified local build and starting Minecraft…", "reading");
    try {
      await globalThis.mcWebRuntime?.start?.();
      markPhase("boot-healthy");
    } catch (error) {
      markPhase("boot-failed", String(error?.message || error));
      setStatus("runtime-status", String(error?.message || error), "locked");
      event.currentTarget.disabled = false;
      event.currentTarget.textContent = "PLAY LOCALLY";
    }
  });

  (async () => {
    verifiedRuntime = await readCachedRuntime();
    if (verifiedRuntime) markRuntimeInstalled(verifiedRuntime, "Installed local build restored from this browser.");
    await globalThis.mcWebDevDiagnostics?.ready?.();
  })().catch(() => {});
  void refreshBuildStatus();
  void refreshLocalServer();
  setInterval(() => {
    void refreshLocalServer();
    void refreshBuildStatus();
  }, 2000);
}
