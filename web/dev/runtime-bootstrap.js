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
  const STREAMING_LOADER_VERSION = "instantiate-streaming-v1";
  const LOOPBACK_HOST = /^(?:127(?:\.\d{1,3}){3}|localhost|::1|\[::1\])$/;
  const MAX_RUNTIME_BYTES = Object.freeze({
    "minecraft-client.js": 2 * 1024 * 1024,
    "minecraft-client.js.wasm": 220 * 1024 * 1024,
  });
  const HOST_FILES = [
    "persistent-storage.js",
    "resource-packs.js",
    "mc-net.js",
    "skin-fetch.js",
    "audio-host.js",
    "render-command-stream.js",
    "webgpu-texture-lifetime.js",
    "webgpu-frame-pacing.js",
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
    let quitting = false;
    globalThis.mcWebLifecycle = globalThis.mcWebLifecycle || {
      async quitToLauncher() {
        if (quitting) return;
        quitting = true;
        globalThis.mcWebLauncher?.status?.("Returning to launcher…");
        try {
          await globalThis.mcWebStorage?.flush?.();
        } catch (error) {
          console.warn("[mcweb] storage flush during quit failed", error);
        }
        const url = new URL(location.href);
        url.searchParams.delete("mcweb_auth");
        url.searchParams.delete("message");
        url.searchParams.delete("mcweb_launcher_preview");
        url.hash = "";
        if (typeof location.replace === "function") location.replace(url.href);
        else location.href = url.href;
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

  async function removeLegacyRuntimeWorker() {
    if (!("serviceWorker" in navigator) || typeof navigator.serviceWorker.getRegistrations !== "function") return;
    const registrations = await navigator.serviceWorker.getRegistrations().catch(() => []);
    for (const registration of registrations) {
      const script = registration.active?.scriptURL || registration.waiting?.scriptURL
        || registration.installing?.scriptURL || "";
      try {
        if (new URL(script, location.origin).pathname === "/dev/runtime-sw.js") await registration.unregister();
      } catch { /* Ignore unrelated or malformed browser registrations. */ }
    }
  }

  async function activateRuntime() {
    globalThis.mcWebDevDiagnostics?.mark?.("build-activation-started");
    const verified = await readVerifiedRuntime();
    const loader = await verified.cache.match(new URL(`${RUNTIME_URL_PREFIX}minecraft-client.js`, location.origin));
    const wasm = await verified.cache.match(new URL(`${RUNTIME_URL_PREFIX}minecraft-client.js.wasm`, location.origin));
    if (!loader || !wasm) throw new Error("verified local build artifacts are unavailable");
    globalThis.mcWebDevRuntimeUrls = Object.freeze({
      loader: URL.createObjectURL(await loader.blob()),
      wasm: URL.createObjectURL(await wasm.blob()),
    });
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
  void removeLegacyRuntimeWorker();
})();
