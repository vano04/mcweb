"use strict";

// Local launcher bridge. The page hash-verifies the build into Cache Storage;
// this module activates that verified pair and loads only source host modules.
// No generated runtime, audio, or launcher credential is present in this copy.
(() => {
  let started = false;
  const BUILD_CACHE = "mcweb-dev-build-v1";
  const RUNTIME_PREFIX = "/dev/";
  const RUNTIME_URL_PREFIX = "/dev/graal/";
  const RUNTIME_POINTER_URL = `${RUNTIME_URL_PREFIX}current.json`;
  const RUNTIME_WORKER_URL = "/dev/runtime-sw.js?v=20260822-local-build1";
  const STREAMING_LOADER_VERSION = "instantiate-streaming-v1";
  const LOOPBACK_HOST = /^(?:127(?:\.\d{1,3}){3}|localhost|::1|\[::1\])$/;
  const MAX_RUNTIME_BYTES = Object.freeze({
    "minecraft-client.js": 2 * 1024 * 1024,
    "minecraft-client.js.wasm": 220 * 1024 * 1024,
  });
  const HOST_FILES = [
    "persistent-storage.js",
    "mc-net.js",
    "audio-host.js",
    "render-command-stream.js",
    "webgpu-texture-lifetime.js",
    "input-mapping.js",
    "webgpu-host.js",
  ];

  function profile() {
    const value = globalThis.mcWebDevProfile;
    if (!globalThis.mcWebDevAuthAccepted || !value
        || typeof value.id !== "string" || typeof value.name !== "string") {
      throw new Error("the self-hosted server must validate the official Launcher session before Play");
    }
    return { id: value.id, name: value.name };
  }

  function profileKey(value) { return String(value || "").replace(/-/g, "").toLowerCase(); }

  function localGatewaySocketPath(candidate) {
    let page;
    let socket;
    try {
      page = new URL(String(globalThis.location?.href || globalThis.location?.origin || ""));
      socket = new URL(String(candidate?.socketUrl || ""));
    } catch {
      return null;
    }
    const expectedProtocol = page.protocol === "https:" ? "wss:"
      : page.protocol === "http:" ? "ws:" : null;
    if (!expectedProtocol || socket.protocol !== expectedProtocol
        || socket.hostname !== page.hostname || socket.port !== page.port
        || !LOOPBACK_HOST.test(socket.hostname) || socket.pathname !== "/mcweb/socket"
        || socket.search || socket.hash || socket.username || socket.password) return null;
    return socket.pathname;
  }

  function exactLocalGateway(player, candidate) {
    if (!candidate || candidate.ready !== true || candidate.provider !== "node"
        || candidate.localNode !== true) return null;
    const targets = Array.isArray(candidate.allowedTargets)
      ? candidate.allowedTargets.map((entry) => String(entry).trim().toLowerCase()) : [];
    const wildcard = candidate.allowAnyTarget === true
      && targets.length === 1 && targets[0] === "*";
    const explicitList = targets.length > 0 && !targets.includes("*");
    if ((!wildcard && !explicitList)
        || !candidate.profile || profileKey(candidate.profile.id) !== profileKey(player.id)
        || candidate.profile.name !== player.name) return null;
    const socketPath = localGatewaySocketPath(candidate);
    if (!socketPath) return null;
    return { socketPath, httpOrigin: location.origin,
      allowAnyTarget: wildcard, allowedTargets: [...targets],
      explicitlyAllowedPrivateTargets: Array.isArray(candidate.explicitlyAllowedPrivateTargets)
        ? [...candidate.explicitlyAllowedPrivateTargets] : [] };
  }

  function installGate() {
    const player = profile();
    const onlineGateway = exactLocalGateway(player, globalThis.mcWebDevGateway);
    const onlineReady = Boolean(onlineGateway);
    const gateway = onlineGateway || { socketPath: "/mcweb/socket", allowAnyTarget: false, allowedTargets: [] };
    globalThis.mcWebConfig = {
      version: 1,
      runtime: { image: "minecraft-client", cacheTag: "build" },
      auth: { mode: "online", status: onlineReady ? "local-gateway-ready" : "accepted-local-profile",
        interactive: false, localOnly: !onlineReady, online: onlineReady, serverProxy: onlineReady },
      gateway,
    };
    globalThis.mcWebAuthGate = {
      session: () => ({ authenticated: true, cached: false, mode: "online",
        localOnly: !onlineReady, online: onlineReady, profile: { ...player } }),
      loadConfig: async () => globalThis.mcWebConfig,
      waitForAccess: async () => ({ authenticated: true, cached: false, mode: "online",
        localOnly: !onlineReady, online: onlineReady, profile: { ...player } }),
    };
    globalThis.mcWebRuntimePrefix = RUNTIME_PREFIX;
    let hidden = false;
    globalThis.mcWebLauncher = {
      get hidden() { return hidden; },
      status(message) {
        const node = document.getElementById("runtime-status");
        if (node && !hidden) node.textContent = String(message);
      },
      stage(message) {
        const node = document.getElementById("runtime-progress-label");
        if (node && !hidden) { node.hidden = false; node.textContent = String(message); }
      },
      note(message) {
        const node = document.getElementById("runtime-progress-label");
        if (node && !hidden) { node.hidden = false; node.textContent = String(message); }
      },
      revealGame() {
        hidden = true;
        document.body.dataset.gameActive = "true";
        document.getElementById("minecraft-canvas")?.removeAttribute("hidden");
        document.getElementById("dev-shell")?.setAttribute("hidden", "");
        document.body.style.overflow = "hidden";
        document.getElementById("minecraft-canvas")?.focus({ preventScroll: true });
      },
    };
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

  async function readVerifiedRuntime() {
    const cache = await caches.open(BUILD_CACHE);
    const pointerResponse = await cache.match(new URL(RUNTIME_POINTER_URL, location.origin));
    if (!pointerResponse) throw new Error("install the verified local build before Play");
    const pointer = await pointerResponse.json();
    if (!/^v-[0-9a-f-]{20,80}$/i.test(String(pointer?.version || "")) || !validManifest(pointer.manifest)) {
      throw new Error("verified local build pointer is invalid");
    }
    for (const entry of pointer.manifest.files) {
      const cached = await cache.match(new URL(`${RUNTIME_URL_PREFIX}${entry.name}`, location.origin));
      if (!cached || Number(cached.headers.get("content-length")) !== entry.bytes) {
        throw new Error(`verified local build is missing ${entry.name}`);
      }
    }
    return { cache, version: pointer.version, manifest: pointer.manifest };
  }

  async function registerRuntimeWorker() {
    if (!("serviceWorker" in navigator)) throw new Error("this browser cannot activate the local runtime service worker");
    // The launcher shell lives at `/`, so the runtime worker must control that
    // page before it can serve the verified `/dev/graal/` cache entries.  The
    // worker itself stays namespaced under `/dev/`; the server opts this script
    // into the wider scope with `Service-Worker-Allowed: /`.
    const registration = await navigator.serviceWorker.register(RUNTIME_WORKER_URL, { scope: "/" });
    await registration.update().catch(() => {});
    await navigator.serviceWorker.ready;
    if (!navigator.serviceWorker.controller) {
      await new Promise((resolve) => {
        const timer = setTimeout(resolve, 1500);
        navigator.serviceWorker.addEventListener("controllerchange", () => { clearTimeout(timer); resolve(); }, { once: true });
      });
    }
    if (!navigator.serviceWorker.controller) throw new Error("the local runtime service worker did not take control of /dev");
  }

  async function activateRuntime() {
    globalThis.mcWebDevDiagnostics?.mark?.("build-activation-started");
    const verified = await readVerifiedRuntime();
    await registerRuntimeWorker();
    globalThis.mcWebDevRuntimeManifest = verified.manifest;
    globalThis.mcWebDevDiagnostics?.mark?.("build-cache-activated");
  }

  function loadScript(name) {
    return new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = `/dev/${name}?v=20260822-local-host1`;
      script.async = false;
      script.onload = resolve;
      script.onerror = () => reject(new Error(`could not load local host module ${name}`));
      document.body.append(script);
    });
  }

  async function start() {
    if (started) return;
    if (!globalThis.mcWebDevRuntimeReady) throw new Error("install and verify the local build before Play");
    started = true;
    try {
      globalThis.mcWebDevDiagnostics?.mark?.("play-requested");
      installGate();
      await activateRuntime();
      document.getElementById("minecraft-canvas")?.removeAttribute("hidden");
      document.getElementById("runtime-status").textContent = "Starting WebGPU and the locally verified Minecraft image…";
      for (const name of HOST_FILES) await loadScript(name);
      globalThis.mcWebDevDiagnostics?.mark?.("host-modules-loaded");
    } catch (error) {
      started = false;
      throw error;
    }
  }

  globalThis.mcWebRuntime = Object.freeze({ start });
})();
