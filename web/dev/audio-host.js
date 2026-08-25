// Minecraft's sound engine, on WebAudio.
//
// Java's SoundManager shadow hands us sound *event* ids
// ("minecraft:ui.button.click"); resolving those to files is normally
// SoundEngine's job, but the ogg files are not in the client JAR -- they live
// in the launcher's asset object store and are staged next to this page by the
// stageMinecraftAudio Gradle task, together with sounds.json.
//
// Kept out of webgpu-host.js so it can be exercised on its own
// (tests/audio.spec.ts, /audio-check.html) with no WebGPU device, no canvas and
// no Wasm image -- the repo's isolate-before-integrating rule.
globalThis.mcWebAudio = (() => {
  const ROOT = "mcweb-audio";
  const AUDIO_CACHE = "mcweb-dev-audio-v1";
  const NAMESPACE_SEPARATOR = ":";

  let ctx = null;
  let masterGain = null;
  let masterLimiter = null;
  /** Headroom the master bus keeps below full scale; see ensureContext. */
  const MASTER_HEADROOM = 0.7;
  // Audio decode is asynchronous from Java's point of view, but Chromium can
  // still spend a long synchronous slice in decodeAudioData while the menu
  // music is being prepared. Keep the normal experience enabled, with an
  // opt-in switch for bootstrap diagnostics and low-power hosts.
  let enabled = !new URLSearchParams(location.search).has("mcweb_no_audio");
  let soundDefinitions = null;
  let definitionsPromise = null;
  let nextHandle = 1;
  let playCount = 0;
  let missing = new Set();

  const categoryGains = new Map();
  const buffers = new Map();
  const decodedUrls = new Set();
  const preparedEvents = new Map();
  const probeLog = [];
  const live = new Map();
  const listener = {x: 0, y: 0, z: 0};

  function ensureContext() {
    if (!ctx) {
      ctx = new (globalThis.AudioContext || globalThis.webkitAudioContext)();
      masterGain = ctx.createGain();
      // Headroom. Web Audio sums sources and then hard-clips at full scale, and
      // Minecraft plays many at once — a bell (volume 1.0, close range) landing
      // on top of footsteps and block breaks was reported as ear-piercing and
      // audibly cutting out, which is that clip, not the sample.
      masterGain.gain.value = MASTER_HEADROOM;
      // A limiter behind the headroom, so a loud stack is compressed rather than
      // clipped. Fast attack catches transients (a bell, an anvil); the slower
      // release stops it pumping on continuous sound like rain.
      masterLimiter = ctx.createDynamicsCompressor();
      masterLimiter.threshold.value = -6;
      masterLimiter.knee.value = 0;
      masterLimiter.ratio.value = 20;
      masterLimiter.attack.value = 0.002;
      masterLimiter.release.value = 0.15;
      masterGain.connect(masterLimiter);
      masterLimiter.connect(ctx.destination);
    }
    if (ctx.state === "suspended") ctx.resume();
    return ctx;
  }

  function categoryGain(source) {
    ensureContext();
    let gain = categoryGains.get(source);
    if (!gain) {
      gain = ctx.createGain();
      gain.gain.value = 1.0;
      gain.connect(masterGain);
      categoryGains.set(source, gain);
    }
    return gain;
  }

  function loadDefinitions() {
    if (definitionsPromise) return definitionsPromise;
    definitionsPromise = loadAudioResponse(`/${ROOT}/minecraft/sounds.json`)
      .then((response) => {
        if (!response.ok) throw new Error(`sounds.json ${response.status}`);
        return response.json();
      })
      .then((json) => {
        soundDefinitions = json;
        console.log(`[mcweb-audio] sounds.json: ${Object.keys(json).length} events`);
        return json;
      })
      .catch((error) => {
        // Staging is optional: without it the game is simply silent rather
        // than broken, and the message says exactly which task to run.
        console.warn(
          "[mcweb-audio] no sounds.json; run ./gradlew stageMinecraftAudio for real audio",
          error
        );
        soundDefinitions = {};
        return soundDefinitions;
      });
    return definitionsPromise;
  }

  function shortName(identifier) {
    const index = identifier.indexOf(NAMESPACE_SEPARATOR);
    return index < 0 ? identifier : identifier.slice(index + 1);
  }

  function namespaceOf(identifier) {
    const index = identifier.indexOf(NAMESPACE_SEPARATOR);
    return index < 0 ? "minecraft" : identifier.slice(0, index);
  }

  // sounds.json entries hold a weighted list; each element is either a bare
  // path or {name, volume, pitch, weight, type}. type "event" redirects to
  // another event, which is how the music and several block groups are wired.
  function resolve(eventId, depth = 0) {
    if (!soundDefinitions || depth > 4) return null;
    const definition = soundDefinitions[shortName(eventId)];
    if (!definition || !Array.isArray(definition.sounds) || definition.sounds.length === 0) {
      return null;
    }

    const entries = definition.sounds.map((entry) =>
      typeof entry === "string" ? {name: entry} : entry
    );
    const total = entries.reduce((sum, entry) => sum + (entry.weight ?? 1), 0);
    let roll = Math.random() * total;
    let chosen = entries[entries.length - 1];
    for (const entry of entries) {
      roll -= entry.weight ?? 1;
      if (roll <= 0) {
        chosen = entry;
        break;
      }
    }

    if (chosen.type === "event") {
      return resolve(chosen.name, depth + 1);
    }

    return {
      url: `/${ROOT}/${namespaceOf(chosen.name)}/sounds/${shortName(chosen.name)}.ogg`,
      volume: chosen.volume ?? 1.0,
      pitch: chosen.pitch ?? 1.0
    };
  }

  function decode(url) {
    let pending = buffers.get(url);
    if (pending) return pending;
    pending = loadAudioResponse(url)
      .then((response) => {
        if (!response.ok) throw new Error(`${response.status}`);
        return response.arrayBuffer();
      })
      .then((bytes) => ensureContext().decodeAudioData(bytes))
      .then((buffer) => {
        decodedUrls.add(url);
        return buffer;
      })
      .catch((error) => {
        if (!missing.has(url)) {
          missing.add(url);
          console.warn("[mcweb-audio] missing", url, String(error));
        }
        return null;
      });
    buffers.set(url, pending);
    return pending;
  }

  async function loadAudioResponse(logicalPath, options = {}) {
    const cacheKey = new URL(logicalPath, globalThis.location?.origin || "http://localhost");
    const cacheable = !options.headers || !new Headers(options.headers).has("Range");
    if (cacheable && globalThis.caches?.open) {
      const cache = await globalThis.caches.open(AUDIO_CACHE);
      const cached = await cache.match(cacheKey);
      if (cached) return cached;
    }

    const fetcher = globalThis.mcWebDevFetchAudio;
    if (typeof fetcher !== "function") {
      // The local Node server owns the page and serves staged audio from the
      // same generated docroot. Keep the receiver hook for embedded callers,
      // but do not require a separate hosted receiver in this distribution.
      const response = await fetch(cacheKey.pathname, options);
      if (!response.ok) throw new Error(`${response.status}`);
      if (cacheable && globalThis.caches?.open) {
        const cache = await globalThis.caches.open(AUDIO_CACHE);
        await cache.put(cacheKey, response.clone());
      }
      return response;
    }
    const response = await fetcher(cacheKey.pathname, options);
    if (!response.ok) throw new Error(`${response.status}`);
    if (cacheable && globalThis.caches?.open) {
      const cache = await globalThis.caches.open(AUDIO_CACHE);
      await cache.put(cacheKey, response.clone());
    }
    return response;
  }

  /**
   * Resolves and decodes one event before the game loop starts.
   *
   * Chromium may spend a long main-thread slice entering/completing
   * decodeAudioData for menu music. The app shell awaits this one preparation
   * before loading WebGPU/Wasm, so the same selected sound is ready when
   * Minecraft asks for it and no live frame pays that first-decode cost.
   */
  async function preloadEvent(name) {
    if (!enabled || preparedEvents.has(name)) return null;
    await loadDefinitions();
    const resolved = resolve(name);
    if (!resolved) return null;
    const buffer = await decode(resolved.url);
    if (!buffer) return null;
    preparedEvents.set(name, { resolved, buffer });
    return resolved.url;
  }

  function playInstance(name, source, volume, pitch, looping) {
    if (!enabled) return 0;
    const handle = nextHandle++;
    const entry = {
      source: source || "master",
      volume,
      pitch,
      looping: !!looping,
      node: null,
      gain: null,
      panner: null,
      finished: false,
      startedAt: (ctx && ctx.currentTime) || 0
    };
    live.set(handle, entry);

    loadDefinitions()
      .then(() => {
        const prepared = preparedEvents.get(name);
        if (prepared) {
          preparedEvents.delete(name);
          entry.resolved = prepared.resolved;
          return prepared.buffer;
        }
        const resolved = resolve(name);
        if (!resolved) {
          entry.finished = true;
          if (!missing.has(name)) {
            missing.add(name);
            console.warn("[mcweb-audio] unresolved event", name);
          }
          return null;
        }
        entry.resolved = resolved;
        return decode(resolved.url);
      })
      .then((buffer) => {
        if (!buffer || entry.finished || entry.stopRequested) {
          entry.finished = true;
          return;
        }
        start(entry, buffer);
      })
      .catch(() => {
        entry.finished = true;
      });

    playCount++;
    return handle;
  }

  function start(entry, buffer) {
    const ac = ensureContext();
    const node = ac.createBufferSource();
    node.buffer = buffer;
    node.loop = !!entry.looping;
    node.playbackRate.value = Math.max(0.05, entry.pitch * (entry.resolved.pitch ?? 1.0));

    const gain = ac.createGain();
    gain.gain.value = Math.max(0, entry.volume) * (entry.resolved.volume ?? 1.0);

    let tail = gain;
    if (entry.position) {
      const panner = ac.createPanner();
      panner.panningModel = "HRTF";
      panner.distanceModel = "linear";
      // Minecraft's own rolloff: audible to 16 blocks for a unit-volume sound.
      panner.refDistance = 1;
      panner.maxDistance = 16;
      panner.positionX.value = entry.position.x;
      panner.positionY.value = entry.position.y;
      panner.positionZ.value = entry.position.z;
      gain.connect(panner);
      entry.panner = panner;
      tail = panner;
    }

    tail.connect(categoryGain(entry.source));
    node.connect(gain);
    node.onended = () => {
      entry.finished = true;
    };
    node.start();
    entry.node = node;
    entry.gain = gain;
  }

  function positionInstance(handle, x, y, z) {
    const entry = live.get(handle);
    if (!entry) return;
    entry.position = {x, y, z};
    if (entry.panner) {
      entry.panner.positionX.value = x;
      entry.panner.positionY.value = y;
      entry.panner.positionZ.value = z;
    }
  }

  function setListener(x, y, z) {
    listener.x = x;
    listener.y = y;
    listener.z = z;
    if (!ctx) return;
    const l = ctx.listener;
    if (l.positionX) {
      l.positionX.value = x;
      l.positionY.value = y;
      l.positionZ.value = z;
    } else if (l.setPosition) {
      l.setPosition(x, y, z);
    }
  }

  // True while decoding as well as while sounding: MusicManager treats a
  // false answer as "the track ended" and immediately starts another.
  function isPlaying(handle) {
    const entry = live.get(handle);
    if (!entry) return false;
    if (entry.finished) {
      live.delete(handle);
      return false;
    }
    return true;
  }

  function stopInstance(handle) {
    const entry = live.get(handle);
    if (!entry) return;
    entry.stopRequested = true;
    entry.finished = true;
    if (entry.node) {
      try {
        entry.node.stop();
      } catch (e) { /* already stopped */ }
    }
    live.delete(handle);
  }

  function stopAll() {
    for (const handle of Array.from(live.keys())) stopInstance(handle);
  }

  function setCategoryVolume(source, volume) {
    categoryGain(source || "master").gain.value = Math.max(0, Math.min(1, volume));
  }

  function setVolume(v) {
    // Scales the headroom rather than overwriting it: assigning the slider value
    // straight in would push the bus back to unity and reintroduce the clipping.
    if (masterGain) {
      masterGain.gain.value = Math.max(0, Math.min(1, v)) * MASTER_HEADROOM;
    }
  }

  function setEnabled(e) {
    enabled = e;
    if (!e) stopAll();
  }

  function isEnabled() { return enabled; }
  function state() { return ctx ? ctx.state : "uninitialized"; }
  function count() { return playCount; }
  function liveCount() { return live.size; }
  function resolvedCount() { return soundDefinitions ? Object.keys(soundDefinitions).length : 0; }

  // Argument-shape probes for the Java @JS bridge self-test. Each simply
  // echoes, so a wrong answer or a Java-side throw isolates the shape.
  function probe0() { return 1; }
  function probe1(name) { probeLog.push("probe1:" + name); }
  function probe2(name) { probeLog.push("probe2:" + name); return 2; }
  function probe3(name, volume) { probeLog.push("probe3:" + name + ":" + volume); return 3; }
  function probe4(name, source, volume, pitch, looping) {
    probeLog.push(`probe4:${name}:${source}:${volume}:${pitch}:${looping}`);
    return 4;
  }

  function diagnostics() {
    let sounding = 0;
    for (const entry of live.values()) if (entry.node) sounding++;
    const decodedBuffers = decodedUrls.size;
    return {
      events: resolvedCount(),
      plays: playCount,
      live: live.size,
      sounding,
      requested: buffers.size,
      decodedBuffers,
      preparedEvents: preparedEvents.size,
      missing: Array.from(missing),
      probeLog,
      contextState: state()
    };
  }

  return {
    playInstance, positionInstance, isPlaying, stopInstance, stopAll,
    setCategoryVolume, setListener, setVolume, setEnabled, isEnabled,
    state, count, liveCount, resolvedCount, diagnostics, ensureContext, loadDefinitions,
    preloadEvent,
    probe0, probe1, probe2, probe3, probe4
  };
})();

// Browsers keep an AudioContext suspended until a user gesture; the game's
// own click sounds are what play from then on.
document.addEventListener("pointerdown", () => {
  globalThis.mcWebAudio.ensureContext();
}, { once: true });
