"use strict";

(() => {
  // Bump this marker with every host change so the console proves the live
  // version (the dev server is no-store, but tabs can sit on a cached copy).
  const MCWEB_BUILD = "2026-08-22-ios-streaming2"; // bump on every host change so the console proves the live version
  // The generated loader is exact-patched by graalWebImage to accept an
  // explicit Wasm URL. Bump this pair tag with every canonical image so an old
  // Service Worker can never mix a cached JS loader with a freshly served Wasm.
  const MCWEB_CACHE_TAG = "build-stream2";
  globalThis.mcWebHostBuild = MCWEB_BUILD;
  console.log("[mcweb-host] build " + MCWEB_BUILD);
  const canvas = document.getElementById("minecraft-canvas");
  const failure = document.getElementById("failure");

  // --- Boot-progress surface -------------------------------------------------
  // The lightweight launcher owns the visible boot experience. Keeping this
  // small interface here lets the long synchronous Web Image boot report its
  // real stages while the enchanting-glyph signal replaces the old spinner.
  const launcherOverlay = globalThis.mcWebLauncher;
  const markPhase = (phase, detail = "") => {
    try { return globalThis.mcWebDevDiagnostics?.mark?.(phase, detail); }
    catch { return null; }
  };
  // Loader instrumentation is deliberately best-effort: a diagnostics write
  // must never become a new reason for the generated Java image to fail.
  globalThis.mcWebStreamingPhase = (phase, detail = "") => {
    try { markPhase(String(phase), detail); } catch { /* diagnostics are optional */ }
  };
  // On-page diagnostic verdict — visible in a screenshot, no console needed.
  const probeVerdict = document.createElement("div");
  probeVerdict.id = "mcweb-probe-verdict";
  probeVerdict.style.cssText = "position:fixed;top:4px;left:4px;z-index:2147483647;background:rgba(180,0,0,0.92);color:#fff;font:12px/1.4 monospace;padding:6px 10px;max-width:520px;white-space:pre-wrap;pointer-events:none;border:1px solid #f44;border-radius:4px;";
  probeVerdict.textContent = "PROBE: waiting…";
  // Keep the diagnostic available without covering the real title UI during
  // normal runs. Add ?mcweb_debug when screenshot-visible probe evidence is
  // needed again.
  if (new URLSearchParams(location.search).has("mcweb_debug")) {
    document.body.appendChild(probeVerdict);
  }
  let mcbHidden = false;
  globalThis.mcWebBootOverlay = {
    status: (s) => { if (!mcbHidden) launcherOverlay?.status?.(s); },
    stage: (s) => { if (!mcbHidden) launcherOverlay?.stage?.(s); },
    note: (s) => { if (!mcbHidden) launcherOverlay?.note?.(s); },
    hide: () => {
      mcbHidden = true;
      launcherOverlay?.revealGame?.();
    },
    get hidden() { return mcbHidden; }
  };
  // DIAG: probe buffers declared BEFORE the console.log wrapper below. The wrapper
  // captures Java-side panorama/atlas/error lines into _javaErrBuf; declaring it here
  // (not ~1100 lines down) guarantees no temporal-dead-zone ReferenceError if any log
  // line emitted during early script evaluation matches the wrapper's filter.
  const _animRing = [];   // animate_sprite atlas-assembly: target + matrices + sprite per draw
  const _guiAtlasRing = []; // animate_sprite draws specifically targeting the GUI atlas
  const _animTargetCounts = new Map();
  const _javaErrBuf = []; // buffered [MC]/Exception/panorama/atlas log lines
  let _probe2Dumped = false;
  // DIAG: scatter probe (PROBE3). The previous animRing filled its 40 slots on the
  // PARTICLES atlas (40 sprites) and never observed the GUI atlas assembly, so it
  // could not localise the scattered shovel/globe/eye sprites. These two rings are
  // immune to that: _asmBad records any animate_sprite assembly draw whose render
  // target is NOT an atlas (a mis-targeted assembly writes sprites onto the screen
  // AND leaves the atlas empty -- one cause, both symptoms); _scatterDraws records
  // any draw onto the screen-sized main target that samples a per-sprite SOURCE
  // texture (widget/* etc.) rather than the atlas or a standalone title image.
  // ---- draw census (diagnostic, opt-in) --------------------------------
  // location exists in the GPU Worker too (WorkerLocation), and pre.js passes
  // the page's query string through when it starts the worker.
  const _drawCensus = (() => {
    try { return new URLSearchParams(location.search).has("mcweb_drawcensus"); }
    catch { return false; }
  })();
  try {
    console.log("[draw-census] enabled=" + _drawCensus
      + " search=" + (typeof location === "undefined" ? "no-location" : location.search));
  } catch {}
  const _drawCensusCounts = new Map();
  let _drawCensusAt = 0;
  const _framePassTrace = [];
  let _terrainVertsDumped = false;
  let _terrainDepthDumped = false;
  let _terrainUniformDumped = false;
  let _uniformSnapshotDumped = false;
  let _visibleSnapshotDumped = false;
  let _writeProbeDumped = false;
  const _depthStateLogged = new Set();
  const _blendLogged = new Set();
  function _noteDrawCensus(state, vertexCount, instanceCount) {
    if (state._traceEntry) {
      state._traceEntry.draws++;
      // Which pipelines ran in this pass, in pass order. If the sky pass runs
      // after the terrain pass and writes opaquely, terrain is painted over -
      // which looks exactly like "terrain never drew".
      const short = String((state.pipeline && state.pipeline.spec
        && state.pipeline.spec.label) || "?").replace("minecraft:pipeline/", "");
      state._traceEntry.pipes ||= new Set();
      if (state._traceEntry.pipes.size < 6) state._traceEntry.pipes.add(short);
      // A scissor that excludes the screen clips the draw away just as
      // effectively as never issuing it. Record the rect in force at the
      // first draw of the pass.
      if (state._traceEntry.scissor === undefined) {
        state._traceEntry.scissor = state._scissor ? state._scissor.join(",") : "none";
        state._traceEntry.area = state.area ? state.area.join(",") : "none";
      }
    }
    const label = state.pipeline && state.pipeline.spec
      ? (state.pipeline.spec.label || "?") : "no-pipeline";
    // tid, not just the label: two distinct render targets can share the name
    // "Main / Color", and the composite can only blit one of them.
    const key = (state._suppressed ? "SUPPRESSED " : "") + label
      + " -> " + (state._targetLabel || "?") + " " + (state._targetWH || "")
      + " tid=" + (state._targetTid ?? "?");
    const entry = _drawCensusCounts.get(key) || { draws: 0, verts: 0 };
    entry.draws++;
    entry.verts += (vertexCount || 0) * Math.max(1, instanceCount || 1);
    _drawCensusCounts.set(key, entry);
    const now = Date.now();
    if (now - _drawCensusAt < 10000) return;
    _drawCensusAt = now;
    const rows = [..._drawCensusCounts.entries()]
      .sort((a, b) => b[1].draws - a[1].draws).slice(0, 14);
    for (const [name, value] of rows) {
      console.log("[draw-census] " + value.draws + "x " + value.verts + "v " + name);
    }
    console.log("[draw-census] distinct=" + _drawCensusCounts.size
      + " presents=" + _presentCount
      + " sinceLastPresent=" + (_lastPresentAt ? (now - _lastPresentAt) + "ms" : "never"));
    _drawCensusCounts.clear();
  }

  const _scatterDraws = [];
  const _asmBad = [];
  let _probe3Dumped = false;
  const _SCATTER_OK = /atlas\/|mojangstudios|title\/minecraft\.png|title\/edition\.png|menu_background|panorama_overlay/;
  function _noteScatter(state) {
    if (_scatterDraws.length >= 16 || !state._targetLabel) return;
    const wh = state._targetWH || "";
    const w = parseInt(wh, 10) || 0;
    if (w <= 1500) return; // atlas / cube targets are <=1024; only the main target is wider
    if (/\/atlas\//.test(state._targetLabel)) return; // assembly draws onto an atlas are NOT screen scatter (they were the 8 false positives that jammed the cap)
    const sampled = [];
    for (const [nm, r] of state.resources) if (r && r.kind === "texture" && r._texEntry) sampled.push(r._texEntry._label || "?");
    if (!sampled.length) return;
    const anomaly = sampled.filter((l) => !_SCATTER_OK.test(l));
    if (!anomaly.length) return;
    const fam = state.pipeline && state.pipeline.spec ? shaderFamily(state.pipeline.spec.vertexShader || "") : "?";
    const u = state.resources.get("Projection"); let pm = null;
    if (u && u.kind === "uniform") { const b = objects.get(u.bufferHandle); if (b && b._shadow) { const f = new Float32Array(b._shadow.buffer, (b._shadow.byteOffset || 0) + u.offset, 16); pm = [f[0], f[5]]; } }
    _scatterDraws.push({ fam, tgt: state._targetLabel, tgtWH: wh, pm, anom: anomaly.map((l) => l.split("/").pop()) });
  }
  // Wrap console.log so the Java boot trace ([MC-INIT] / inline-task / [MC])
  // drives the overlay as well as DevTools.
  const originalConsoleLog = console.log.bind(console);
  console.log = (...values) => {
    originalConsoleLog(...values);
    try {
      const t = values.map((v) => (typeof v === "string" ? v : (v && v.message) ? v.message : JSON.stringify(v))).join(" ");
    if (/panorama|atlas|Exception|Failed|cube|reload/i.test(t) && !/^\[(TEX-|GUI-DRAW|PANO-|ANIM-|PROBE|mcweb)/.test(t) && !/^SLF4J/.test(t) && _javaErrBuf.length < 30) _javaErrBuf.push(t.slice(0, 220));
      if (mcbHidden) return;
      const m = t.match(/^\[MC-INIT\]\s*(.*)$/);
      if (m) { globalThis.mcWebBootOverlay.stage(m[1] || t); return; }
      const it = t.match(/inline-task#(\d+):(\S+)/);
      if (it) { globalThis.mcWebBootOverlay.status("Reloading assets… task #" + it[1]); globalThis.mcWebBootOverlay.note(it[2]); return; }
      if (/^\[MC\]/.test(t) && !/^SLF4J/.test(t)) globalThis.mcWebBootOverlay.note(t.slice(0, 96));
    } catch {}
  };
  const objects = new Map();
  let nextHandle = 1;
  let adapter;
  let device;
  let context;
  const diagnostics = {lastCall: "host setup", calls: {}, stages: [], stageMs: [], reloadProbe: []};

  // Canvas sizing. The backing store must track the CSS box, otherwise the
  // browser simply scales a stale framebuffer to the new element size -- that
  // is the "resizing stretches the image" symptom. Resizing it is only half the
  // fix: Minecraft also has to hear about it, or GUI scale, projection and
  // render targets all stay at the boot size. The dispatch goes through the
  // same single input bridge the keyboard and mouse use.
  globalThis.mcWebCanvas = {
    pending: true,

    targetSize() {
      const ratio = Math.min(devicePixelRatio || 1, 2);
      const rect = canvas.getBoundingClientRect();
      const cssWidth = rect.width || innerWidth;
      const cssHeight = rect.height || innerHeight;
      return [
        Math.max(1, Math.round(cssWidth * ratio)),
        Math.max(1, Math.round(cssHeight * ratio))
      ];
    },

    resizeBackingStore() {
      const [width, height] = this.targetSize();
      if (canvas.width === width && canvas.height === height) return false;
      canvas.width = width;
      canvas.height = height;
      if (context && device) {
        context.configure({
          device,
          format: "rgba8unorm",
          usage: GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.COPY_DST,
          alphaMode: "opaque"
        });
      }
      return true;
    },

    applyPendingResize() {
      if (!this.pending) return;
      const changed = this.resizeBackingStore();
      const bridge = globalThis.mcWebInput && globalThis.mcWebInput.bridge;
      if (!bridge) {
        // Stay armed: before the bridge exists, Window still reads the size
        // through glfwGetFramebufferSize, but a later resize must not be lost.
        return;
      }
      this.pending = false;
      if (changed) globalThis.mcWebInput.call("resize", canvas.width, canvas.height);
    }
  };

  addEventListener("resize", () => { globalThis.mcWebCanvas.pending = true; });
  if (globalThis.visualViewport) {
    globalThis.visualViewport.addEventListener("resize", () => {
      globalThis.mcWebCanvas.pending = true;
    });
  }

  /** Durable `evt:` gameplay-packet counters. The 4000-entry stage ring is
   *  evicted by the worldgen packet flood, so windowed counts read from the
   *  ring undercount to zero. Counters never evict. */
  const _evtCounts = new Map();

  /**
   * Markers emitted per packet, per frame or per tick. Everything else — boot
   * stages, screen changes, one-shot diagnostics — keeps the console line and
   * the on-page status text, which is what makes a stalled boot visible.
   */
  const _CHATTY_STAGE =
    /^(evt:|packet:|terrain:|gameplay-state:|threads:|chunk:|pumpslow:|levelload:|server:tick)/;
  const _logAllStages = new URLSearchParams(location.search).has("mcweb_log_all");

  const recordStage = (stage) => {
    const stages = diagnostics.stages;
    stages.push(stage);
    // Console delivery timestamps are useless for a blocking boot: every line
    // emitted while the main thread is busy is delivered in one burst when it
    // frees up, so a two-minute constructor looks instantaneous in DevTools.
    // Stamping here is the only way to attribute that time to a stage.
    diagnostics.stageMs.push(Math.round(performance.now()));
    if (stages.length > 4000) {
      stages.splice(0, stages.length - 4000);
      diagnostics.stageMs.splice(0, diagnostics.stageMs.length - 4000);
    }
    if (typeof stage === "string" && stage.startsWith("evt:")) {
      const row = _evtCounts.get(stage) || { n: 0, firstMs: 0, lastMs: 0, ts: [] };
      row.n++;
      const now = performance.now();
      if (!row.firstMs) row.firstMs = now;
      row.lastMs = now;
      // Keep the last 32 individual timestamps: per-hit latency correlation
      // needs each crossing's time, not just first/last.
      row.ts.push(Math.round(now * 100) / 100);
      if (row.ts.length > 32) row.ts.shift();
      _evtCounts.set(stage, row);
    }
  };

  const _recentCalls = [];
  let _renderCommandReplayDepth = 0;

  /** Call name plus only the args that locate a failure, kept short. */
  const _summarise = (name, detail) => {
    if (!detail) return name;
    if (name === "createBuffer") {
      return `createBuffer(${detail.label},sz=${detail.size},use=${detail.usage})`;
    }
    if (name === "writeBuffer") {
      const transport = detail.packedChars == null
        ? `b64=${detail.base64Length}`
        : `bytes=${detail.byteLength},chars=${detail.packedChars}`;
      return `writeBuffer(h=${detail.handle},@${detail.destinationOffset},${transport})`;
    }
    if (name === "writeBufferRaw") {
      return `writeBufferRaw(h=${detail.handle},@${detail.destinationOffset},bytes=${detail.byteLength})`;
    }
    if (name === "writeTextureRaw") {
      return `writeTextureRaw(h=${detail.handle},bytes=${detail.byteLength})`;
    }
    if (name === "reportProgress") return `reportProgress(${detail.stage ?? ""})`;
    return name;
  };

  // The dataset mirror is display-only (nothing reads it programmatically —
  // the failure ring and per-call counters live in _recentCalls/diagnostics
  // and stay exact). Coalescing it to "only when the call name changes" was
  // not enough: the render path alternates two call names per pass, so the
  // name changed on every call. It is time-throttled instead, which keeps the
  // on-page "what is the GPU doing" mirror useful during a stall.
  let _lastMarkCallDom = null;
  let _lastMarkCallDomMs = 0;
  let _markCallDomPending = false;
  const _perfNow = typeof performance !== "undefined"
    ? () => performance.now() : () => Date.now();
  const markCall = (name, detail = null) => {
    // One outer rpCommandStream call represents the Java/JS boundary. Its
    // internal operations must not perform thousands of diagnostic DOM writes
    // or masquerade as additional bridge crossings.
    if (_renderCommandReplayDepth !== 0) return;
    // The bare call name brackets a failure to a region but cannot say where
    // inside a loop it happened: a 128 MiB uber-buffer is seeded by ~170
    // identical writeBuffer chunks, and "died during writeBuffer" is true
    // whether it died on the first chunk (the big allocation) or the 90th (a
    // transient spike). Keeping the size/offset args distinguishes those.
    _recentCalls.push(_summarise(name, detail));
    if (_recentCalls.length > 60) _recentCalls.shift();
    diagnostics.lastCall = name;
    diagnostics.lastDetail = detail;
    diagnostics.calls[name] = (diagnostics.calls[name] || 0) + 1;
    // The DOM mirror exists so a human or a probe can read the last call
    // without a page evaluate; nothing consumes it at frame rate. Writing it
    // on every change of `name` did: a real server frame alternates
    // beginRenderPass with rpCommandStream ~84 times, so this wrote three
    // dataset properties — one of them a JSON.stringify of a render-pass
    // descriptor — about 12,700 times a second. Mirror on a timer instead and
    // the ring above stays exact.
    if (name !== _lastMarkCallDom) {
      _lastMarkCallDom = name;
      _markCallDomPending = true;
    }
    const now = _perfNow();
    if (_markCallDomPending && now - _lastMarkCallDomMs >= 250) {
      _lastMarkCallDomMs = now;
      _markCallDomPending = false;
      document.body.dataset.lastGpuCall = name;
      document.body.dataset.lastGpuCallCount = String(diagnostics.calls[name]);
      if (detail) document.body.dataset.lastGpuDetail = JSON.stringify(detail);
    }
  };

  // Expose the durable evt counters for probes that need windowed counts the
  // evicting ring cannot provide.
  globalThis.mcWebEvtCounts = () => Object.fromEntries(_evtCounts);

  const resolveBindingName = (value, kind) => {
    // Older staged images still pass their pre-interning Java String object.
    // Returning it untouched avoids the recursive Proxy string conversion that
    // caused the WasmLM stack overflow. Numeric IDs must resolve exactly.
    if (typeof value !== "number") return value;
    const name = globalThis.mcWebGpu?._bindingNames?.[value];
    if (typeof name !== "string") throw new Error(`unknown ${kind} binding name ${value}`);
    return name;
  };

  const setText = (id, value) => {
    const node = document.getElementById(id);
    if (node) node.textContent = value;
  };

  const fail = (error) => {
    const message = error?.stack || String(error);
    failure.hidden = false;
    failure.textContent = message;
    if (error?.code) {
      document.body.dataset.mcwebFailureCode = String(error.code);
    }
    setText("jar-status", "failed");
    setText("gpu-status", error?.message || String(error));
    markPhase("boot-failed", error?.code || "runtime-error");
    console.error(error);
  };

  // Frames flowing means the client is healthy; from then on, console noise
  // (notably headless-SwiftShader's "Instance dropped in popErrorScope") must
  // not re-show the failure overlay and hide the rendered canvas.
  let framesFlowing = false;
  let firstRafRecorded = false;
  let firstFrameReported = false;
  const originalConsoleError = console.error.bind(console);
  console.error = (...values) => {
    originalConsoleError(...values);
    if (framesFlowing) return;
    // Benign startup warnings (e.g. SLF4J NOP) must not mask a healthy run.
    const text = values.map((value) => String(value?.message ?? value)).join(" ");
    if (text.startsWith("SLF4J")) return;
    const firstError = values.find((value) => value instanceof Error);
    const message = values.map((value) => value?.stack || String(value)).join("\n");
    failure.hidden = false;
    failure.textContent = firstError?.stack || message;
    setText("jar-status", "runtime error");
  };

  const put = (object) => {
    const handle = nextHandle++;
    if (object && typeof object === "object") object._handle = handle;
    objects.set(handle, object);
    return handle;
  };

  // Java byte arrays cross the Web Image interop boundary as base64 text;
  // BufferSource conversion rejects the raw interop proxy.
  const base64ToBytes = (base64) => {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  };

  // WasmGC has no exported linear memory. Pack two payload bytes into each
  // UTF-16 code unit so Web Image's unavoidable Java-String conversion walks
  // 3/8 as many characters as Base64, then reconstruct the exact byte view.
  const packedTextToBytes = (text, byteLength) => {
    if (typeof text !== "string" || !Number.isInteger(byteLength) || byteLength < 0
        || text.length !== Math.ceil(byteLength / 2)) {
      throw new Error(`invalid packed upload length chars=${text?.length} bytes=${byteLength}`);
    }
    const bytes = new Uint8Array(byteLength);
    for (let i = 0; i < text.length; i++) {
      const pair = text.charCodeAt(i);
      const at = i * 2;
      bytes[at] = pair & 0xff;
      if (at + 1 < byteLength) bytes[at + 1] = pair >>> 8;
    }
    return bytes;
  };

  const get = (handle, kind) => {
    const object = objects.get(handle);
    if (!object) throw new Error(`Unknown ${kind} handle ${handle}`);
    return object;
  };

  const minecraftTextureUsage = (usage) => {
    let result = 0;
    if (usage & 1) result |= GPUTextureUsage.COPY_DST;
    if (usage & 2) result |= GPUTextureUsage.COPY_SRC;
    if (usage & 4) result |= GPUTextureUsage.TEXTURE_BINDING;
    if (usage & 8) result |= GPUTextureUsage.RENDER_ATTACHMENT;
    return result;
  };

  const minecraftBufferUsage = (usage) => {
    let result = 0;
    if (usage & 8) result |= GPUBufferUsage.COPY_DST;
    if (usage & 16) result |= GPUBufferUsage.COPY_SRC;
    if (usage & 32) result |= GPUBufferUsage.VERTEX;
    if (usage & 64) result |= GPUBufferUsage.INDEX;
    if (usage & 128) result |= GPUBufferUsage.UNIFORM;
    if (usage & 512) result |= GPUBufferUsage.INDIRECT;
    // Mojang permits MAP_WRITE|UNIFORM. WebGPU does not, so Java maps a
    // CPU shadow and flushes it into a COPY_DST buffer instead.
    if (usage & 2) result |= GPUBufferUsage.COPY_DST;
    if (usage & 1) result |= GPUBufferUsage.COPY_SRC;
    if (usage & 256) {
      // Mojang's USAGE_UNIFORM_TEXEL_BUFFER is a Vulkan concept with no WebGPU
      // equivalent; the nearest is a read-only storage buffer. Throwing here
      // aborted the whole render frame, and the throw only became reachable
      // once the PNG fix let atlas stitching complete. Approximating is
      // strictly better: if a shader really needs texel-buffer semantics its
      // pipeline fails to build and is skipped (a degraded feature), instead
      // of taking the entire frame -- and the client -- down with it.
      result |= GPUBufferUsage.STORAGE;
    }
    if (!result) throw new Error(`Unsupported Minecraft buffer usage ${usage}`);
    return result;
  };

  const minecraftFormat = (format) => {
    const formats = {
      RGBA8_UNORM: "rgba8unorm",
      RGBA8_SNORM: "rgba8snorm",
      RGBA8_UINT: "rgba8uint",
      RGBA8_SINT: "rgba8sint",
      BGRA8_UNORM: "bgra8unorm",
      R8_UNORM: "r8unorm",
      R8_SNORM: "r8snorm",
      R8_UINT: "r8uint",
      R8_SINT: "r8sint",
      RG8_UNORM: "rg8unorm",
      RG8_SNORM: "rg8snorm",
      RG8_UINT: "rg8uint",
      RG8_SINT: "rg8sint",
      RGBA16_UNORM: "rgba16unorm",
      RGBA16_SNORM: "rgba16snorm",
      RGBA16_UINT: "rgba16uint",
      RGBA16_SINT: "rgba16sint",
      RGBA16_FLOAT: "rgba16float",
      RGBA32_FLOAT: "rgba32float",
      RG16_FLOAT: "rg16float",
      RG32_FLOAT: "rg32float",
      R16_FLOAT: "r16float",
      R32_FLOAT: "r32float",
      RG11B10_FLOAT: "rg11b10ufloat",
      RGB10A2_UNORM: "rgb10a2unorm",
      RGB10A2_UINT: "rgb10a2uint",
      D32_FLOAT: "depth32float",
      D32_FLOAT_S8_UINT: "depth32float-stencil8",
      D24_UNORM_S8_UINT: "depth24plus-stencil8",
      D16_UNORM: "depth16unorm"
    };
    const mapped = formats[format];
    if (!mapped) throw new Error(`Minecraft texture format ${format} is not ported yet`);
    return mapped;
  };

  // ---------------------------------------------------------------------------
  // Pipelines: Mojang RenderPipeline spec -> GPURenderPipeline. Shaders are a
  // hand-written WGSL family keyed by vanilla core shader ID; the boot set
  // (title screen) is fully covered, unknown shaders yield invalid pipelines.
  // ---------------------------------------------------------------------------

  const UNIFORM_STRUCTS = {
    DynamicTransforms: `{
  ModelViewMat: mat4x4<f32>,
  ColorModulator: vec4<f32>,
  ModelOffset: vec3<f32>,
  TextureMat: mat4x4<f32>,
}`,
    Projection: `{
  ProjMat: mat4x4<f32>,
}`,
    // shaders/include/light.glsl. Mojang's std140 writer binds 28 bytes:
    // vec3 @0, 4-byte alignment gap, vec3 @16. Scalars preserve those exact
    // offsets without making WGSL round the required binding size to 32.
    Lighting: `{
  Light0X: f32,
  Light0Y: f32,
  Light0Z: f32,
  _lightingPad0: f32,
  Light1X: f32,
  Light1Y: f32,
  Light1Z: f32,
}`,
    // Mojang binds this block as its 40 bytes of member data. Keeping FogColor
    // as a WGSL vec4 gives the struct 16-byte alignment and rounds its required
    // binding size up to 48, invalidating every world render submit. Scalars
    // preserve the vec4's offsets while keeping the shader-visible size at 40.
    Fog: `{
  FogColorR: f32,
  FogColorG: f32,
  FogColorB: f32,
  FogColorA: f32,
  FogEnvironmentalStart: f32,
  FogEnvironmentalEnd: f32,
  FogRenderDistanceStart: f32,
  FogRenderDistanceEnd: f32,
  FogSkyEnd: f32,
  FogCloudsEnd: f32,
}`,
    // Per-section block for terrain (shaders/include/chunksection.glsl).
    // Terrain is the only family that carries its model-view here rather than
    // in DynamicTransforms, which is why the generic vertex fallback silently
    // used an identity matrix and clipped every chunk.
    ChunkSection: `{
  ModelViewMat: mat4x4<f32>,
  ChunkVisibility: f32,
  TextureSize: vec2<i32>,
  ChunkPosition: vec3<i32>,
}`,
    // core/rendertype_clouds.vsh: vec4 @0, vec3 @16, vec3 @32. As with
    // Lighting, scalar members keep Mojang's unrounded 44-byte binding valid.
    CloudInfo: `{
  CloudColorR: f32,
  CloudColorG: f32,
  CloudColorB: f32,
  CloudColorA: f32,
  CloudOffsetX: f32,
  CloudOffsetY: f32,
  CloudOffsetZ: f32,
  _cloudInfoPad0: f32,
  CellSizeX: f32,
  CellSizeY: f32,
  CellSizeZ: f32,
}`,
    // Declared with scalar members ONLY, deliberately. A vec3 would give the
    // struct 16-byte alignment, and WGSL rounds a struct's size up to its
    // alignment: 56 -> 64. Mojang allocates this buffer at the un-rounded 56,
    // and the bound range is capped at the buffer size, so a 64-byte shader
    // struct can never be satisfied -- every submit fails with "bound with size
    // 56 ... requires at least 64" and the screen freezes. All-scalar members
    // give alignment 4 and size exactly 56, while landing on the same offsets
    // std140 uses (ivec3 @0 +pad, vec3 @16 +pad, vec2 @32, then scalars).
    Globals: `{
  CameraBlockPosX: i32,
  CameraBlockPosY: i32,
  CameraBlockPosZ: i32,
  _globalsPad0: i32,
  CameraOffsetX: f32,
  CameraOffsetY: f32,
  CameraOffsetZ: f32,
  _globalsPad1: f32,
  ScreenSizeX: f32,
  ScreenSizeY: f32,
  GlintAlpha: f32,
  GameTime: f32,
  MenuBlurRadius: i32,
  UseRgss: i32,
}`,
    // Post-chain uniform blocks (declared in the JAR's post/*.fsh; the sizes
    // MUST match Mojang's std140 buffers or createBindGroup fails validation —
    // a binding smaller than the shader-declared struct is rejected). Layouts
    // verified against assets/minecraft/shaders/post/{box_blur,blit}.fsh.
    SamplerInfo: `{
  OutSize: vec2<f32>,
  InSize: vec2<f32>,
}`,
    BlurConfig: `{
  BlurDir: vec2<f32>,
  Radius: f32,
}`,
    BlitConfig: `{
  ColorModulate: vec4<f32>,
}`,
    // Sprite-atlas assembly (shaders/core/animate_sprite.vsh via
    // animation_sprite.glsl). TextureAtlas packs every sprite's pixels into a
    // big UBO, then draws each sprite into the atlas with this block: the
    // SpriteMatrix maps the unit quad to the sprite's atlas region.
    SpriteAnimationInfo: `{
  ProjectionMatrix: mat4x4<f32>,
  SpriteMatrix: mat4x4<f32>,
  UPadding: f32,
  VPadding: f32,
  MipMapLevel: i32,
}`
  };

  const VERTEX_GPU_FORMATS = {
    R32_FLOAT: {gpu: "float32", dim: 1, kind: "f"},
    RG32_FLOAT: {gpu: "float32x2", dim: 2, kind: "f"},
    RGB32_FLOAT: {gpu: "float32x4", dim: 4, kind: "f"},
    RGBA32_FLOAT: {gpu: "float32x4", dim: 4, kind: "f"},
    R16_FLOAT: {gpu: "float16x2", dim: 2, kind: "f"},
    RG16_FLOAT: {gpu: "float16x2", dim: 2, kind: "f"},
    RGB16_FLOAT: {gpu: "float16x4", dim: 4, kind: "f"},
    RGBA16_FLOAT: {gpu: "float16x4", dim: 4, kind: "f"},
    R8_UNORM: {gpu: "unorm8x2", dim: 2, kind: "f"},
    RG8_UNORM: {gpu: "unorm8x2", dim: 2, kind: "f"},
    RGB8_UNORM: {gpu: "unorm8x4", dim: 4, kind: "f"},
    RGBA8_UNORM: {gpu: "unorm8x4", dim: 4, kind: "f"},
    R8_SNORM: {gpu: "snorm8x2", dim: 2, kind: "f"},
    RG8_SNORM: {gpu: "snorm8x2", dim: 2, kind: "f"},
    RGB8_SNORM: {gpu: "snorm8x4", dim: 4, kind: "f"},
    RGBA8_SNORM: {gpu: "snorm8x4", dim: 4, kind: "f"},
    R8_UINT: {gpu: "uint8x2", dim: 2, kind: "u"},
    RG8_UINT: {gpu: "uint8x2", dim: 2, kind: "u"},
    RGBA8_UINT: {gpu: "uint8x4", dim: 4, kind: "u"},
    R16_UINT: {gpu: "uint16x2", dim: 2, kind: "u"},
    RG16_UINT: {gpu: "uint16x2", dim: 2, kind: "u"},
    RGBA16_UINT: {gpu: "uint16x4", dim: 4, kind: "u"},
    R32_UINT: {gpu: "uint32", dim: 1, kind: "u"},
    RG32_UINT: {gpu: "uint32x2", dim: 2, kind: "u"},
    RGBA32_UINT: {gpu: "uint32x4", dim: 4, kind: "u"},
    R8_SINT: {gpu: "sint8x2", dim: 2, kind: "i"},
    RG8_SINT: {gpu: "sint8x2", dim: 2, kind: "i"},
    RGBA8_SINT: {gpu: "sint8x4", dim: 4, kind: "i"},
    R16_SINT: {gpu: "sint16x2", dim: 2, kind: "i"},
    RG16_SINT: {gpu: "sint16x2", dim: 2, kind: "i"},
    RGBA16_SINT: {gpu: "sint16x4", dim: 4, kind: "i"},
    R32_SINT: {gpu: "sint32", dim: 1, kind: "i"},
    RG32_SINT: {gpu: "sint32x2", dim: 2, kind: "i"},
    RGBA32_SINT: {gpu: "sint32x4", dim: 4, kind: "i"}
  };

  const wgslScalar = (kind) => (kind === "u" ? "u32" : kind === "i" ? "i32" : "f32");
  const wgslVecType = (fmt) => {
    const info = VERTEX_GPU_FORMATS[fmt];
    if (!info) return null;
    if (info.dim === 1) return wgslScalar(info.kind);
    return `vec${info.dim}<${wgslScalar(info.kind)}>`;
  };

  const TOPOLOGY = {
    TRIANGLES: "triangle-list",
    QUADS: "triangle-list",
    TRIANGLE_FAN: "triangle-list",
    TRIANGLE_STRIP: "triangle-strip",
    LINES: "line-list",
    DEBUG_LINES: "line-list",
    DEBUG_LINE_STRIP: "line-strip",
    POINTS: "point-list"
  };

  const BLEND_FACTOR = {
    ZERO: "zero",
    ONE: "one",
    SRC_COLOR: "src",
    ONE_MINUS_SRC_COLOR: "one-minus-src",
    DST_COLOR: "dst",
    ONE_MINUS_DST_COLOR: "one-minus-dst",
    SRC_ALPHA: "src-alpha",
    ONE_MINUS_SRC_ALPHA: "one-minus-src-alpha",
    DST_ALPHA: "dst-alpha",
    ONE_MINUS_DST_ALPHA: "one-minus-dst-alpha",
    CONSTANT_COLOR: "constant",
    ONE_MINUS_CONSTANT_COLOR: "one-minus-constant",
    CONSTANT_ALPHA: "constant-alpha",
    ONE_MINUS_CONSTANT_ALPHA: "one-minus-constant-alpha",
    SRC_ALPHA_SATURATE: "src-alpha-saturated"
  };

  const BLEND_OP = {
    ADD: "add",
    SUBTRACT: "subtract",
    REVERSE_SUBTRACT: "reverse-subtract",
    MIN: "min",
    MAX: "max"
  };

  const _forceDepthAlways = (typeof location !== "undefined")
    && new URLSearchParams(location.search).get("mcweb_diag") === "depthalways";

  const COMPARE_OP = {
    ALWAYS_PASS: "always",
    LESS_THAN: "less",
    LESS_THAN_OR_EQUAL: "less-equal",
    EQUAL: "equal",
    NOT_EQUAL: "not-equal",
    GREATER_THAN_OR_EQUAL: "greater-equal",
    GREATER_THAN: "greater",
    NEVER_PASS: "never"
  };

  const shaderFamily = (id) => {
    const tail = id.replace(/^minecraft:/, "").replace(/^core\//, "");
    return tail;
  };

  // Attribute usage per family: which vertex element names the shader reads.
  const buildVs = (family, spec, groups, groupRemap) => {
    const format = spec.vertexFormats[0];
    if (!format) return null;
    // Shared with buildPipeline so the WGSL @locations and the GPU
    // shaderLocations line up (split RGB* elements consume two locations).
    const vlayout = compileVertexLayout(format);
    const byName = vlayout.byName;
    const inputMembers = vlayout.inputMembers;

    const get = (name, swizzle) => {
      const attr = byName.get(name);
      if (!attr) throw new Error(`shader ${family} needs missing attribute ${name}`);
      // A split 3-component element is read as vec2 + scalar; rebuild the vec3.
      const base = attr.split3
        ? `vec3<${attr.scalar}>(input.${attr.hiName}, input.${attr.loName})`
        : `input.${attr.name}`;
      if (!swizzle) return base;
      if (!attr.split3 && attr.dim === 1) return base;
      return `${base}.${swizzle}`;
    };

    const uniformLoc = new Map();
    groups.forEach((g, gi) => g.uniforms.forEach((u, ui) => {
      if (!uniformLoc.has(u.name)) uniformLoc.set(u.name, { g: groupRemap[gi].ng, b: groupRemap[gi].baseU + ui });
    }));
    // The @group/@binding (remapped) live on the module-scope declaration in
    // uniformDecl; the body just references that variable by name.
    const uniform = (name, member) => `${sanitizeName(name)}.${member}`;
    const hasUniform = (name) => uniformLoc.has(name);

    // Chrome's Tint rejects the diagonal-splat `mat4x4<f32>(1.0)` constructor,
    // so spell the identity out (only used when the pipeline lacks the block).
    const IDENTITY4 = "mat4x4<f32>(1.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0, 0.0,0.0,1.0,0.0, 0.0,0.0,0.0,1.0)";
    const mv = hasUniform("DynamicTransforms") ? uniform("DynamicTransforms", "ModelViewMat") : IDENTITY4;
    const proj = hasUniform("Projection") ? uniform("Projection", "ProjMat") : IDENTITY4;

    let varyings = "";
    let body = "";
    // Families whose clip position is not simply proj * mv * Position set this.
    let positionExpr = null;
    // Some dedicated atlas assembly shaders need an explicit clip-space
    // correction. Do not apply this to model rendering: item vertices and
    // their per-slot scissors are already expressed in the same top-left
    // target coordinates after the projection transform.
    let flipClipY = false;
    // Extra world-space translation folded into worldPos (terrain's chunk offset).
    let terrainOffsetExpr = "";
    switch (family) {
      case "position_color":
      case "text_background":
      case "gui":
        varyings = "  @location(0) vertexColor: vec4<f32>,\n";
        body = `  output.vertexColor = ${get("Color")};`;
        break;
      case "position_tex":
        varyings = "  @location(0) texCoord0: vec2<f32>,\n";
        body = `  output.texCoord0 = ${get("UV0", "xy")};`;
        break;
      case "position_tex_color":
        varyings = "  @location(0) texCoord0: vec2<f32>,\n  @location(1) vertexColor: vec4<f32>,\n";
        body = `  output.texCoord0 = ${get("UV0", "xy")};\n  output.vertexColor = ${get("Color")};`;
        break;
      case "text": {
        varyings = "  @location(0) vertexColor: vec4<f32>,\n  @location(1) texCoord0: vec2<f32>,\n";
        body = `  output.vertexColor = ${get("Color")};\n  output.texCoord0 = ${get("UV0", "xy")};`;
        break;
      }
      case "terrain": {
        // shaders/core/terrain.vsh:
        //   pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset
        //   gl_Position = ProjMat * ModelViewMat * vec4(pos, 1)
        // The chunk translation is what the generic fallback dropped. Lighting
        // is deferred to the fragment stage: the vertex shader would otherwise
        // need Sampler2 bound with VERTEX visibility, which this port's bind
        // group layouts do not grant.
        const cs = hasUniform("ChunkSection") ? "ChunkSection" : null;
        const gl = hasUniform("Globals") ? "Globals" : null;
        const mvTerrain = cs ? `${cs}.ModelViewMat` : IDENTITY4;
        const offset = (cs && gl)
          ? `vec3<f32>(${cs}.ChunkPosition - vec3<i32>(${gl}.CameraBlockPosX, `
            + `${gl}.CameraBlockPosY, ${gl}.CameraBlockPosZ)) `
            + `+ vec3<f32>(${gl}.CameraOffsetX, ${gl}.CameraOffsetY, ${gl}.CameraOffsetZ)`
          : "vec3<f32>(0.0)";
        varyings = "  @location(0) texCoord0: vec2<f32>,\n"
          + "  @location(1) vertexColor: vec4<f32>,\n"
          + "  @location(2) lightCoord: vec2<f32>,\n"
          + "  @location(3) sphericalVertexDistance: f32,\n"
          + "  @location(4) cylindricalVertexDistance: f32,\n";
        terrainOffsetExpr = ` + ${offset}`;
        positionExpr = `${proj} * ${mvTerrain} * vec4<f32>(worldPos, 1.0)`;
        body = `  output.texCoord0 = ${get("UV0", "xy")};
  output.vertexColor = ${get("Color")};
  output.lightCoord = clamp(
    vec2<f32>(${get("UV2", "xy")}) / 256.0 + vec2<f32>(0.03125),
    vec2<f32>(0.03125), vec2<f32>(0.96875));
  output.sphericalVertexDistance = length(worldPos);
  output.cylindricalVertexDistance = max(length(worldPos.xz), abs(worldPos.y));`;
        break;
      }
      case "block":
      case "particle":
      case "item":
      case "entity": {
        // Direct translations of core/{block,particle,item,entity}.vsh. Keep
        // texture sampling in the fragment stage because WebGPU bind-group
        // layouts expose Mojang's samplers there; carrying the integer texture
        // coordinates as varyings is equivalent and avoids widening every
        // sampler binding to VERTEX visibility.
        const isBlock = family === "block";
        const hasOverlay = (family === "item" || family === "entity") && byName.has("UV1");
        // entity.vsh has three lighting branches the port previously skipped
        // for entities (only items were shaded), leaving mobs uniformly
        // fullbright against AO-shaded terrain:
        //  - PER_FACE_LIGHTING: front/back colors from the normal, picked by
        //    gl_FrontFacing in the fragment stage (unculled translucent skins);
        //  - NO_CARDINAL_LIGHTING: plain Color (breeze wind);
        //  - default: minecraft_mix_light per vertex. Items always mix_light.
        const vsDefines = new Set();
        for (const raw of spec.defines || []) {
          const s = String(raw);
          const eq = s.indexOf("=");
          vsDefines.add(eq < 0 ? s : s.slice(0, eq));
        }
        const lightingInput = hasUniform("Lighting") && byName.has("Normal");
        const perFaceLighting = family === "entity"
          && vsDefines.has("PER_FACE_LIGHTING") && lightingInput;
        const cardinalLighting = (family === "item" && lightingInput)
          || (family === "entity" && lightingInput
            && !vsDefines.has("PER_FACE_LIGHTING")
            && !vsDefines.has("NO_CARDINAL_LIGHTING"));
        const parts = [
          "  @location(0) texCoord0: vec2<f32>,\n",
          "  @location(1) vertexColor: vec4<f32>,\n",
          "  @location(2) lightCoord: vec2<f32>,\n",
          "  @location(3) sphericalVertexDistance: f32,\n",
          "  @location(4) cylindricalVertexDistance: f32,\n"
        ];
        if (perFaceLighting) {
          parts.push("  @location(5) vertexColorBack: vec4<f32>,\n");
        }
        if (hasOverlay) {
          parts.push(`  @location(${perFaceLighting ? 6 : 5}) overlayCoord: vec2<f32>,\n`);
        }
        varyings = parts.join("");
        if (isBlock && hasUniform("DynamicTransforms")) {
          terrainOffsetExpr = ` + ${uniform("DynamicTransforms", "ModelOffset")}`;
        }
        const pieces = [
          `  output.texCoord0 = ${get("UV0", "xy")};`
        ];
        if (perFaceLighting) {
          pieces.push(`  let normal = normalize(${get("Normal", "xyz")});
  let lightX = dot(
    vec3<f32>(Lighting.Light0X, Lighting.Light0Y, Lighting.Light0Z), normal);
  let lightY = dot(
    vec3<f32>(Lighting.Light1X, Lighting.Light1Y, Lighting.Light1Z), normal);
  let rawVertexColor = ${get("Color")};
  let frontAccum = min(1.0, (max(lightX, 0.0) + max(lightY, 0.0)) * 0.6 + 0.4);
  let backAccum = min(1.0, (max(-lightX, 0.0) + max(-lightY, 0.0)) * 0.6 + 0.4);
  output.vertexColor = vec4<f32>(rawVertexColor.rgb * frontAccum, rawVertexColor.a);
  output.vertexColorBack = vec4<f32>(rawVertexColor.rgb * backAccum, rawVertexColor.a);`);
        } else if (cardinalLighting) {
          pieces.push(`  let normal = normalize(${get("Normal", "xyz")});
  let light0 = max(dot(
    vec3<f32>(Lighting.Light0X, Lighting.Light0Y, Lighting.Light0Z), normal), 0.0);
  let light1 = max(dot(
    vec3<f32>(Lighting.Light1X, Lighting.Light1Y, Lighting.Light1Z), normal), 0.0);
  let lightAccum = min(1.0, (light0 + light1) * 0.6 + 0.4);
  let rawVertexColor = ${get("Color")};
  output.vertexColor = vec4<f32>(rawVertexColor.rgb * lightAccum, rawVertexColor.a);`);
        } else {
          pieces.push(`  output.vertexColor = ${get("Color")};`);
        }
        pieces.push(
          byName.has("UV2")
            ? `  output.lightCoord = clamp(
    vec2<f32>(${get("UV2", "xy")}) / 256.0 + vec2<f32>(0.03125),
    vec2<f32>(0.03125), vec2<f32>(0.96875));`
            : "  output.lightCoord = vec2<f32>(0.5);",
          "  output.sphericalVertexDistance = length(worldPos);",
          "  output.cylindricalVertexDistance = max(length(worldPos.xz), abs(worldPos.y));"
        );
        if (hasOverlay) {
          pieces.push(`  output.overlayCoord = vec2<f32>(${get("UV1", "xy")});`);
        }
        body = pieces.join("\n");
        break;
      }
      case "panorama":
        varyings = "  @location(0) texCoord0: vec3<f32>,\n";
        body = `  output.texCoord0 = ${get("Position", "xyz")};`;
        break;
      case "position":
      case "sky":
      case "stars":
        varyings = "  @location(0) sphericalVertexDistance: f32,\n  @location(1) cylindricalVertexDistance: f32,\n";
        body = `  let modelPos = ${get("Position", "xyz")};
  output.sphericalVertexDistance = length(modelPos);
  output.cylindricalVertexDistance = max(length(modelPos.xz), abs(modelPos.y));`;
        break;
      default: {
        // Generic fallback so every registered pipeline compiles: Mojang's
        // startup precompile gate requires ALL pipelines to load, while the
        // title screen only draws a handful of families. All attributes are
        // declared (the vertex layout must match the buffer), Position is
        // transformed normally, and UV0/Color pass through when present.
        const parts = [];
        const pieces = [];
        if (byName.has("UV0")) {
          parts.push("  @location(0) texCoord0: vec2<f32>,\n");
          pieces.push(`  output.texCoord0 = ${get("UV0", "xy")};`);
        }
        if (byName.has("Color")) {
          parts.push(`  @location(${parts.length}) vertexColor: vec4<f32>,\n`);
          pieces.push(`  output.vertexColor = ${get("Color")};`);
        }
        varyings = parts.join("");
        body = pieces.join("\n");
        break;
      }
    }

    return `struct VsInput {
${inputMembers}
};

struct VsOutput {
  @builtin(position) position: vec4<f32>,
${varyings}};

@vertex
fn vs_main(input: VsInput) -> VsOutput {
  var output: VsOutput;
  let worldPos = ${get("Position", "xyz")}${terrainOffsetExpr};
  output.position = ${positionExpr || `${proj} * ${mv} * vec4<f32>(worldPos, 1.0)`};
${flipClipY ? "  output.position.y = -output.position.y;" : ""}
${body}
  return output;
}`;
  };

  const buildFs = (family, spec, groups, groupRemap, samplerMap) => {
    // ShaderDefines carries both flags ("IS_GUI") and values
    // ("ALPHA_CUTOUT=0.1"). Treating the serialized strings as a Set made
    // every value-bearing define look absent, most visibly turning transparent
    // foliage texels into opaque black pixels.
    const defines = new Map();
    for (const raw of spec.defines || []) {
      const split = String(raw).indexOf("=");
      if (split < 0) {
        defines.set(String(raw), true);
      } else {
        defines.set(String(raw).slice(0, split), String(raw).slice(split + 1));
      }
    }
    const hasDefine = (name) => defines.has(name);
    const numericDefine = (name, fallback) => {
      if (!defines.has(name)) return null;
      const parsed = Number.parseFloat(defines.get(name));
      return Number.isFinite(parsed) ? parsed : fallback;
    };
    const hasUniform = (name) => groups.some((g) => g.uniforms.some((u) => u.name === name));
    const hasSampler = (name) => groups.some((g) => g.samplers.includes(name));
    const colorMod = hasUniform("DynamicTransforms") ? "DynamicTransforms.ColorModulator" : "vec4<f32>(1.0)";

    // Sampler/texture @group/@binding come from the merged remap so they match
    // the pipeline layout after group folding.
    const samplerDecl = (name, cube) => {
      const loc = samplerMap.get(name);
      return `@group(${loc.g}) @binding(${loc.b}) var ${sanitizeName(name)}_s: sampler;
@group(${loc.g}) @binding(${loc.b + 1}) var ${sanitizeName(name)}: ${cube ? "texture_cube" : "texture_2d"}<f32>;`;
    };

    let samplers = "";
    for (const g of groups) {
      for (const s of g.samplers) {
        samplers += samplerDecl(s, family === "panorama") + "\n";
      }
    }

    // Must gate on the same inputs buildVs checks (Lighting uniform + Normal
    // attribute): a per-face FS expecting vertexColorBack while the VS did not
    // emit it fails WGSL interface validation at pipeline creation. Declared
    // before the switch because the entity case below selects face color on it.
    const surfacePerFace = family === "entity" && hasDefine("PER_FACE_LIGHTING")
      && hasUniform("Lighting")
      && (spec.vertexFormats[0]?.elements || []).some((e) => e.name === "Normal");
    let main = null;
    let genericVaryings = null;
    switch (family) {
      case "position_color":
      case "gui":
        main = `  var color = input.vertexColor;
  if (color.a == 0.0) {
    discard;
  }
  return color * ${colorMod};`;
        break;
      case "terrain": {
        // shaders/core/terrain.fsh. Mojang deliberately does not use a plain
        // filtered textureSample here: its derivative-aware sampleNearest
        // keeps nearby block texels crisp while still selecting mip levels for
        // distant faces. The generic sample was the source of the heavy blur.
        //   color = atlasSample * vertexColor * lightmap
        //   color = mix(FogColor * vec4(1,1,1,color.a), color, ChunkVisibility)
        //   discard when color.a < cutout, then apply distance fog.
        const atlas = hasSampler("Sampler0") ? sanitizeName("Sampler0") : null;
        const light = hasSampler("Sampler2") ? sanitizeName("Sampler2") : null;
        const cutout = numericDefine("ALPHA_CUTOUT", 0.1);
        const vis = hasUniform("ChunkSection") ? "ChunkSection.ChunkVisibility" : "1.0";
        const fogColor = hasUniform("Fog")
          ? "vec4<f32>(Fog.FogColorR, Fog.FogColorG, Fog.FogColorB, Fog.FogColorA)"
          : "vec4<f32>(1.0)";
        const lines = [];
        lines.push(atlas
          ? `  var color = sampleNearest(
    ${atlas}, ${atlas}_s, input.texCoord0,
    vec2<f32>(1.0) / max(vec2<f32>(ChunkSection.TextureSize), vec2<f32>(1.0))) * input.vertexColor;`
          : "  var color = input.vertexColor;");
        if (light) {
          lines.push(`  color = color * textureSample(${light}, ${light}_s, input.lightCoord);`);
        }
        lines.push(`  let fogColor = ${fogColor};`);
        lines.push(`  color = mix(fogColor * vec4<f32>(1.0, 1.0, 1.0, color.a), color, ${vis});`);
        if (cutout !== null) {
          lines.push(`  if (color.a < ${cutout.toFixed(2)}) {\n    discard;\n  }`);
        }
        if (hasUniform("Fog")) {
          lines.push(`  let fogFactor = clamp(
    (input.sphericalVertexDistance - Fog.FogRenderDistanceStart)
      / max(Fog.FogRenderDistanceEnd - Fog.FogRenderDistanceStart, 0.0001),
    0.0, 1.0);
  color = vec4<f32>(mix(color.rgb, fogColor.rgb, fogFactor * fogColor.a), color.a);`);
        }
        lines.push("  return color;");
        main = lines.join("\n");
        break;
      }
      case "block":
      case "particle":
      case "item":
      case "entity": {
        const atlas = hasSampler("Sampler0") ? sanitizeName("Sampler0") : null;
        const light = hasSampler("Sampler2") ? sanitizeName("Sampler2") : null;
        const overlay = hasSampler("Sampler1") && (family === "item" || family === "entity")
          ? sanitizeName("Sampler1") : null;
        const cutout = family === "particle"
          ? 0.1
          : numericDefine("ALPHA_CUTOUT", 0.1);
        const fogColor = hasUniform("Fog")
          ? "vec4<f32>(Fog.FogColorR, Fog.FogColorG, Fog.FogColorB, Fog.FogColorA)"
          : "vec4<f32>(1.0)";
        const lines = [];
        lines.push(atlas
          ? `  var color = textureSample(${atlas}, ${atlas}_s, input.texCoord0);`
          : "  var color = vec4<f32>(1.0);");
        if (cutout !== null) {
          lines.push(`  if (color.a < ${cutout.toFixed(4)}) {\n    discard;\n  }`);
        }
        lines.push(surfacePerFace
          ? `  let faceVertexColor = select(input.vertexColorBack, input.vertexColor, input.frontFacing);
  color = color * faceVertexColor * ${colorMod};`
          : `  color = color * input.vertexColor * ${colorMod};`);
        if (overlay) {
          lines.push(`  let overlayColor = textureLoad(
    ${overlay}, vec2<i32>(round(input.overlayCoord)), 0);
  color = vec4<f32>(mix(overlayColor.rgb, color.rgb, overlayColor.a), color.a);`);
        }
        if (light) {
          lines.push(`  color = color * textureSample(${light}, ${light}_s, input.lightCoord);`);
        }
        if (hasUniform("Fog")) {
          lines.push(`  let fogColor = ${fogColor};
  let fogFactor = clamp(
    (input.sphericalVertexDistance - Fog.FogRenderDistanceStart)
      / max(Fog.FogRenderDistanceEnd - Fog.FogRenderDistanceStart, 0.0001),
    0.0, 1.0);
  color = vec4<f32>(mix(color.rgb, fogColor.rgb, fogFactor * fogColor.a), color.a);`);
        }
        lines.push("  return color;");
        main = lines.join("\n");
        break;
      }
      case "text_background":
        main = `  var color = input.vertexColor * ${colorMod};
  if (color.a < 0.1) {
    discard;
  }
  return color;`;
        break;
      case "position_tex":
        main = `  var color = textureSample(Sampler0, Sampler0_s, input.texCoord0);
  if (color.a == 0.0) {
    discard;
  }
  return color * ${colorMod};`;
        break;
      case "position_tex_color":
        // GuiItemAtlas.SlotView deliberately publishes OpenGL-style V
        // coordinates (row zero is v=1). The atlas itself is rendered into a
        // WebGPU target where row zero is physically at the top, so only this
        // premultiplied-alpha atlas blit needs V inverted while sampling.
        // Flipping the item model's clip Y instead mirrors each model away
        // from its slot scissor and leaves every row except the middle blank.
        const sampleCoord = /gui_textured_premultiplied_alpha/.test(spec.label || "")
          ? "vec2<f32>(input.texCoord0.x, 1.0 - input.texCoord0.y)"
          : "input.texCoord0";
        main = `  var color = textureSample(Sampler0, Sampler0_s, ${sampleCoord}) * input.vertexColor;
  if (color.a == 0.0) {
    discard;
  }
  return color * ${colorMod};`;
        break;
      case "text": {
        const gui = hasDefine("IS_GUI") || hasDefine("IS_SEE_THROUGH");
        const grayscale = hasDefine("IS_GRAYSCALE");
        const forceSolid = typeof location !== "undefined"
          && new URLSearchParams(location.search).has("mcweb_textsolid");
        const sample = grayscale
          ? "textureSample(Sampler0, Sampler0_s, input.texCoord0).rrrr"
          : "textureSample(Sampler0, Sampler0_s, input.texCoord0)";
        if (forceSolid) {
          // DIAG: distinguish rejected text geometry/depth from glyph texture
          // sampling/alpha. Enabled only by ?mcweb_textsolid.
          main = `  return vec4<f32>(1.0, 0.0, 1.0, 1.0);`;
        } else if (gui) {
          main = `  let texColor = ${sample};
  var color = texColor * input.vertexColor;
  if (color.a < 0.1) {
    discard;
  }
  return color;`;
        } else {
          main = `  let texColor = ${sample};
  var color = texColor * input.vertexColor * ${colorMod};
  if (color.a < 0.1) {
    discard;
  }
  return color;`;
        }
        break;
      }
      case "panorama":
        main = `  return textureSample(Sampler0, Sampler0_s, input.texCoord0);`;
        break;
      case "position":
      case "sky":
      case "stars":
        main = `  return ${colorMod};`;
        break;
      case "screenquad":
        main = `  return textureSample(InSampler, InSampler_s, input.position.xy / 1.0);`;
        break;
      default: {
        // Generic fallback (see buildVs): sample the first bound texture with
        // UV0 when the format has it, modulate by vertex Color and
        // ColorModulator; flat color otherwise. Varyings mirror buildVs's
        // generic branch exactly.
        const names = new Set((spec.vertexFormats[0]?.elements || []).map((e) => e.name));
        const parts = [];
        if (names.has("UV0")) parts.push("  @location(0) texCoord0: vec2<f32>,\n");
        if (names.has("Color")) parts.push(`  @location(${parts.length}) vertexColor: vec4<f32>,\n`);
        genericVaryings = parts.join("");
        let firstSampler = null;
        for (const g of groups) {
          if (g.samplers.length) {
            firstSampler = sanitizeName(g.samplers[0]);
            break;
          }
        }
        if (names.has("UV0") && firstSampler) {
          main = `  var color = textureSample(${firstSampler}, ${firstSampler}_s, input.texCoord0);${
            names.has("Color") ? "\n  color *= input.vertexColor;" : ""
          }
  return color * ${colorMod};`;
        } else if (names.has("Color")) {
          main = `  return input.vertexColor * ${colorMod};`;
        } else {
          main = `  return ${colorMod};`;
        }
        break;
      }
    }

    const isSurface = family === "block" || family === "particle"
      || family === "item" || family === "entity";
    const surfaceHasOverlay = (family === "item" || family === "entity")
      && (spec.vertexFormats[0]?.elements || []).some((e) => e.name === "UV1");
    const varyingMembers = genericVaryings !== null
      ? genericVaryings
      : family === "terrain"
      // Must mirror buildVs's terrain varyings exactly, in order.
      ? "  @location(0) texCoord0: vec2<f32>,\n"
        + "  @location(1) vertexColor: vec4<f32>,\n"
        + "  @location(2) lightCoord: vec2<f32>,\n"
        + "  @location(3) sphericalVertexDistance: f32,\n"
        + "  @location(4) cylindricalVertexDistance: f32,\n"
      : isSurface
        ? "  @location(0) texCoord0: vec2<f32>,\n"
          + "  @location(1) vertexColor: vec4<f32>,\n"
          + "  @location(2) lightCoord: vec2<f32>,\n"
          + "  @location(3) sphericalVertexDistance: f32,\n"
          + "  @location(4) cylindricalVertexDistance: f32,\n"
          + (surfacePerFace ? "  @location(5) vertexColorBack: vec4<f32>,\n" : "")
          + (surfaceHasOverlay
            ? `  @location(${surfacePerFace ? 6 : 5}) overlayCoord: vec2<f32>,\n`
            : "")
      : family === "position_tex" || family === "position_tex_color"
        ? "  @location(0) texCoord0: vec2<f32>,\n" +
          (family === "position_tex_color" ? "  @location(1) vertexColor: vec4<f32>,\n" : "")
        : family === "panorama"
          ? "  @location(0) texCoord0: vec3<f32>,\n"
          : family === "position" || family === "sky" || family === "stars"
            ? "  @location(0) sphericalVertexDistance: f32,\n  @location(1) cylindricalVertexDistance: f32,\n"
            : family === "text"
              ? "  @location(0) vertexColor: vec4<f32>,\n  @location(1) texCoord0: vec2<f32>,\n"
              : "  @location(0) vertexColor: vec4<f32>,\n";

    const terrainHelpers = family === "terrain" ? `
fn sampleNearest(
  source: texture_2d<f32>,
  sourceSampler: sampler,
  uvIn: vec2<f32>,
  pixelSize: vec2<f32>
) -> vec4<f32> {
  let du = dpdx(uvIn);
  let dv = dpdy(uvIn);
  let texelScreenSize = max(sqrt(du * du + dv * dv), vec2<f32>(0.0000001));
  let uvTexelCoords = uvIn / pixelSize;
  let texelCenter = round(uvTexelCoords) - vec2<f32>(0.5);
  var texelOffset = uvTexelCoords - texelCenter;
  texelOffset = (texelOffset - vec2<f32>(0.5)) * pixelSize / texelScreenSize
    + vec2<f32>(0.5);
  texelOffset = clamp(texelOffset, vec2<f32>(0.0), vec2<f32>(1.0));
  let uv = (texelCenter + texelOffset) * pixelSize;
  return textureSampleGrad(source, sourceSampler, uv, du, dv);
}
` : "";

    return `struct FsInput {
  @builtin(position) position: vec4<f32>,
${surfacePerFace ? "  @builtin(front_facing) frontFacing: bool,\n" : ""}${varyingMembers}};

${samplers}
${terrainHelpers}
@fragment
fn fs_main(input: FsInput) -> @location(0) vec4<f32> {
${main}
}`;
  };

  const sanitizeName = (name) => name.replace(/[^a-zA-Z0-9_]/g, "_");
  // GLSL exposes color and depth attachments through sampler2D, but WebGPU
  // requires a depth32float view to use the distinct `depth` sample type.
  const isDepthSamplerName = (name) => /Depth(?:Sampler)?$/i.test(String(name));

  // WebGPU has NO 3-component vertex format (only x1/x2/x4). A 3-component
  // element (RGB*) whose buffer is tightly packed at a 3-wide stride overruns
  // when read with the 4-wide GPU format ("Attribute offset + format size >
  // stride") — exactly what kills sky/stars/panorama/end_gateway (and, via the
  // ShaderManager precompile gate, hangs the whole client). The data-preserving
  // fix: split the element into a 2-wide + 1-wide attribute pair (both within
  // the stride) and reconstruct the vec3 in the shader. Shared by buildVs
  // (WGSL decls + get()) and buildPipeline (GPU attributes) so the
  // shaderLocations stay identical on both sides.
  const SPLIT3_RGB = {
    RGB32_FLOAT: {hi: "float32x2", lo: "float32", hiBytes: 8, scalar: "f32", kind: "f"},
    RGB16_FLOAT: {hi: "float16x2", lo: "float16", hiBytes: 4, scalar: "f32", kind: "f"},
    RGB8_UNORM:  {hi: "unorm8x2",  lo: "unorm8",  hiBytes: 2, scalar: "f32", kind: "f"},
    RGB8_SNORM:  {hi: "snorm8x2",  lo: "snorm8",  hiBytes: 2, scalar: "f32", kind: "f"}
  };
  const compileVertexLayout = (vf) => {
    const members = [];     // WGSL `  @location(L) name: type,`
    const gpuAttrs = [];    // {shaderLocation, offset, format}
    const byName = new Map(); // rawName -> {split3, ...}
    let loc = 0;
    for (const element of vf.elements) {
      const sp = SPLIT3_RGB[element.format];
      if (sp) {
        const hiName = sanitizeName(element.name) + "_hi";
        const loName = sanitizeName(element.name) + "_lo";
        members.push(`  @location(${loc}) ${hiName}: vec2<${sp.scalar}>,`);
        members.push(`  @location(${loc + 1}) ${loName}: ${sp.scalar},`);
        gpuAttrs.push({shaderLocation: loc, offset: element.offset, format: sp.hi});
        gpuAttrs.push({shaderLocation: loc + 1, offset: element.offset + sp.hiBytes, format: sp.lo});
        byName.set(element.name, {split3: true, scalar: sp.scalar, hiName, loName, kind: sp.kind, dim: 3});
        loc += 2;
      } else {
        const info = VERTEX_GPU_FORMATS[element.format];
        if (!info) throw new Error(`vertex format ${element.format} is not ported`);
        const nm = sanitizeName(element.name);
        members.push(`  @location(${loc}) ${nm}: ${wgslVecType(element.format)},`);
        gpuAttrs.push({shaderLocation: loc, offset: element.offset, format: info.gpu});
        byName.set(element.name, {split3: false, name: nm, kind: info.kind, dim: info.dim});
        loc += 1;
      }
    }
    return {members, gpuAttrs, byName, inputMembers: members.join("\n")};
  };

  // Some WebGPU backends (this ANGLE/SwiftShader path) cap maxBindGroups at 4,
  // but Mojang's entity/item/terrain/text pipelines use 5-6 groups. Merge every
  // group >= K-1 into group K-1 (K = maxBindGroups) with collision-free binding
  // renumbering, and apply the SAME remap to the WGSL, the pipeline layout, and
  // the host bind calls so they stay consistent. Groups < K-1 are untouched.
  const GROUP_CAP = Math.max(1, Math.min(8, device?.limits?.maxBindGroups || 4));
  const computeBindGroupRemap = (groups) => {
    const K = Math.min(GROUP_CAP, groups.length || 1);
    const map = groups.map((g, gi) => {
      const ng = gi < K - 1 ? gi : K - 1;
      return { ng, baseU: 0, baseS: 0, nuniforms: g.uniforms.length, nsamplers: g.samplers.length };
    });
    if (K === groups.length) return { map, K, merged: false };
    // Pack each MERGED group globally: every original group that folds into the
    // same merged group shares one binding space, so we must lay out ALL of
    // their uniforms first (contiguous baseU), then ALL of their sampler pairs
    // (contiguous baseS AFTER the uniforms). The previous per-group running
    // count reset baseS relative to only the same original group's uniforms, so
    // when two original groups folded together (e.g. the blur post-chain's
    // SamplerInfo/BlurConfig group and its InSampler group) the sampler landed
    // on binding 0 — colliding with the other group's uniform at binding 0
    // ("binding index (0) was specified by a previous entry") and invalidating
    // the whole pipeline (and thus the title-screen render). Deriving every
    // binding from this single map keeps the WGSL uniform decls, the WGSL
    // sampler decls (via samplerMap), the bind-group layout, and ensureBindGroups
    // perfectly consistent and collision-free.
    const uCursor = new Array(K).fill(0);
    for (let gi = 0; gi < groups.length; gi++) {
      map[gi].baseU = uCursor[map[gi].ng];
      uCursor[map[gi].ng] += groups[gi].uniforms.length;
    }
    const sCursor = uCursor.slice(); // samplers start after all merged-group uniforms
    for (let gi = 0; gi < groups.length; gi++) {
      map[gi].baseS = sCursor[map[gi].ng];
      sCursor[map[gi].ng] += groups[gi].samplers.length * 2;
    }
    return { map, K, merged: true };
  };

  const buildPipeline = (spec, depthFormat) => {
    // depthFormat === undefined ⇒ caller (the precompile gate) didn't specify a
    // pass depth, so use the historical default. An EXPLICIT null means "the
    // pass has no depth attachment" and must NOT synthesize depth state.
    if (depthFormat === undefined) depthFormat = "depth32float";
    // Strictly "does the pass we are building for have a depth attachment".
    // This previously also OR'd in !!spec.depthStencil, which made it true for
    // the NO-depth variant (depthFormat === null) of any depth-declaring
    // family and synthesized `format: null` -- the exact failure the guard on
    // the spec branch below exists to prevent. A pipeline bound in a pass with
    // no depth attachment must carry no depth state, whatever its spec says.
    const depthRequested = depthFormat != null;
    // Open a validation scope for the whole build so the FIRST failing GPU
    // object (bind-group layout / pipeline layout / pipeline) is logged with
    // its real message, instead of only the downstream "invalid due to a
    // previous error" cascade.
    const _scopeOpen = typeof device.pushErrorScope === "function";
    if (_scopeOpen) device.pushErrorScope("validation");
    const _popScope = () => {
      if (_scopeOpen) device.popErrorScope().then((err) => {
        if (err && !(buildPipeline._loggedBuild || (buildPipeline._loggedBuild = new Set())).has(spec.label)) {
          buildPipeline._loggedBuild.add(spec.label);
          console.error(`[build validation error] ${spec.label}: ${err.message}`);
        }
      }).catch(() => {});
    };
    const family = shaderFamily(spec.vertexShader);
    const groups = spec.bindGroups || [];
    const { map: groupRemap, K: groupCount, merged: groupsMerged } = computeBindGroupRemap(groups);
    // Per-sampler remapped (group, binding) so the WGSL sampler/texture decls
    // match the merged layout.
    // Mojang numbers a group's UNIFORMS and SAMPLERS in *separate* binding
    // spaces (both from 0) — valid for GL/Vulkan descriptor sets but illegal in
    // WebGPU, where bindings must be unique within a bind group. The blur chain
    // exposes this directly (group 1 = SamplerInfo@0, BlurConfig@1, InSampler@0
    // → duplicate binding 0). So we lay each original group's samplers AFTER its
    // uniforms. When groups are merged, samplers must also clear every folded
    // group's uniforms in the same merged group, hence max(baseS, merged-group
    // uniform count). baseS already equals the merged-group uniform count when
    // groups fold (computeBindGroupRemap), and equals 0 otherwise, so this is a
    // safe no-op for non-overlapping / merged cases and fixes the overlap.
    const mergedUniformCount = new Array(groupCount).fill(0);
    groups.forEach((g, gi) => { mergedUniformCount[groupRemap[gi].ng] += g.uniforms.length; });
    const samplerBase = (gi) => Math.max(groupRemap[gi].baseS, mergedUniformCount[groupRemap[gi].ng]);
    const samplerMap = new Map();
    groups.forEach((g, gi) => g.samplers.forEach((s, si) =>
      samplerMap.set(s, { g: groupRemap[gi].ng, b: samplerBase(gi) + si * 2 })));
    if (/blur/.test(spec.label) && (typeof location !== "undefined") && new URLSearchParams(location.search).has("mcweb_debug") && !(buildPipeline._dbgBlur)) {
      buildPipeline._dbgBlur = true;
      console.log("[BLIT-REMAP-DBG]", spec.label,
        "groups=", JSON.stringify(groups.map((g, gi) => ({ gi, ng: groupRemap[gi].ng, baseU: groupRemap[gi].baseU, baseS: groupRemap[gi].baseS, nu: g.uniforms.length, ns: g.samplers.length, uniforms: g.uniforms.map((u) => u.name), samplers: g.samplers }))),
        "K=", groupCount, "merged=", groupsMerged,
        "samplerMap=", JSON.stringify([...samplerMap.entries()]));
    }

    // Uniform declarations shared by both stages. TEXEL_BUFFER entries get a
    // layout slot but no WGSL declaration: Mojang binds them as buffers via
    // setUniform (GL/Vulkan buffer textures have no WebGPU equivalent), and a
    // bind group layout may carry entries the shader never references.
    let uniformDecl = "";
    const declaredUniforms = new Set();
    groups.forEach((group, groupIndex) => {
      const r = groupRemap[groupIndex];
      group.uniforms.forEach((uniformDesc, uniformIndex) => {
        if (uniformDesc.type === "TEXEL_BUFFER") {
          // WebGPU has storage buffers but no formatted texel-buffer view. Keep
          // the underlying bytes packed in u32 storage words and let the one
          // shader that consumes R8_SINT (CloudFaces) perform the exact signed
          // byte extraction below. Mojang writes three BYTES per cloud quad;
          // treating those as three i32s skipped packed values and read past
          // the buffer for most vertices, producing valid draws with no pixels.
          const storageScalar = /R8_(?:SINT|UINT)/.test(uniformDesc.format || "")
            ? "u32"
            : "i32";
          uniformDecl += `@group(${r.ng}) @binding(${r.baseU + uniformIndex}) var<storage, read> ${sanitizeName(uniformDesc.name)}: array<${storageScalar}>;\n`;
          declaredUniforms.add(uniformDesc.name);
          return;
        }
        const struct = UNIFORM_STRUCTS[uniformDesc.name];
        if (!struct) {
          // Unknown uniform block: expose raw storage so unused uniforms still
          // bind; shaders in the boot family only read the known blocks.
          uniformDecl += `struct ${sanitizeName(uniformDesc.name)}_t { data: array<vec4<f32>, 16> };\n`;
        } else {
          uniformDecl += `struct ${sanitizeName(uniformDesc.name)}_t ${struct};\n`;
        }
        uniformDecl += `@group(${r.ng}) @binding(${r.baseU + uniformIndex}) var<uniform> ${sanitizeName(uniformDesc.name)}: ${sanitizeName(uniformDesc.name)}_t;\n`;
        declaredUniforms.add(uniformDesc.name);
      });
    });

    let vs = null;
    let fs = null;
    // Sprite-atlas assembly (animate_sprite family): TextureAtlas packs every
    // sprite's pixels into a big UBO, then blits each sprite into the atlas in
    // a render pass driven by the SpriteAnimationInfo uniform — SpriteMatrix
    // maps the unit quad onto the sprite's atlas region, the vertex shader
    // builds the quad from vertex_index (translated from core/animate_sprite.vsh
    // + animation_sprite.glsl). This is HOW the gui/widgets/blocks atlases get
    // populated; the generic fullscreen blit that used to catch this family
    // wrote garbage into the atlas and left every GUI texture black.
    const isAnimateSprite = family === "animate_sprite";
    if (isAnimateSprite) {
      vs = `const ANIMATE_POSITIONS = array<vec2<f32>, 6>(
  vec2<f32>(0.0, 0.0), vec2<f32>(1.0, 0.0), vec2<f32>(0.0, 1.0),
  vec2<f32>(0.0, 1.0), vec2<f32>(1.0, 0.0), vec2<f32>(1.0, 1.0));

struct VsOutput {
  @builtin(position) position: vec4<f32>,
  @location(0) texCoord0: vec2<f32>,
  @location(1) fAnimationProgress: f32,
};

@vertex
fn vs_main(@builtin(vertex_index) vertexIndex: u32) -> VsOutput {
  // The GLSL reads a 6-entry position table with (vertex_id & 7); indices 6-7
  // are undefined there (the driver clamps). Clamp explicitly in WGSL.
  let index = min(vertexIndex & 7u, 5u);
  let frameProgress = f32(vertexIndex >> 3u) / 1000.0;
  let padding = vec2<f32>(SpriteAnimationInfo.UPadding, SpriteAnimationInfo.VPadding);
  let uv = ANIMATE_POSITIONS[index];
  let direction = uv * vec2<f32>(2.0) - vec2<f32>(1.0, 1.0);
  var output: VsOutput;
  var atlasPosition = SpriteAnimationInfo.ProjectionMatrix * SpriteAnimationInfo.SpriteMatrix * vec4<f32>(uv, 0.0, 1.0);
  // Mojang's atlas projection is authored for the OpenGL framebuffer
  // convention, while WebGPU's render-target Y direction is inverted. Without
  // this correction, logical atlas row 0 lands at the bottom: GUI sprites
  // packed into rows 0..599 appear in physical rows 425..1023, so every widget
  // samples an unrelated sprite or transparency at its expected UV.
  atlasPosition.y = -atlasPosition.y;
  output.position = atlasPosition;
  output.texCoord0 = uv + padding * direction;
  output.fAnimationProgress = frameProgress;
  return output;
}`;
      // Two fragment variants share the vertex stage: animate_sprite_blit
      // (copy one sprite frame) and animate_sprite_interpolate (blend current
      // + next frame by the per-vertex progress).
      const interpolate = /interpolate/.test((spec.fragmentShader || "") + " " + spec.label);
      const orderedSamplerLocs = [];
      groups.forEach((g) => g.samplers.forEach((s) => {
        const loc = samplerMap.get(s);
        if (loc) orderedSamplerLocs.push({name: s, ...loc});
      }));
      const locOf = (name, order) => samplerMap.get(name) || orderedSamplerLocs[order] || {g: 0, b: 0};
      const fsInput = `struct FsInput {
  @builtin(position) position: vec4<f32>,
  @location(0) texCoord0: vec2<f32>,
  @location(1) fAnimationProgress: f32,
};`;
      if (interpolate) {
        const cur = locOf("CurrentSprite", 0);
        const nxt = locOf("NextSprite", 1);
        fs = `${fsInput}

@group(${cur.g}) @binding(${cur.b}) var CurrentSprite_s: sampler;
@group(${cur.g}) @binding(${cur.b + 1}) var CurrentSprite: texture_2d<f32>;
@group(${nxt.g}) @binding(${nxt.b}) var NextSprite_s: sampler;
@group(${nxt.g}) @binding(${nxt.b + 1}) var NextSprite: texture_2d<f32>;

@fragment
fn fs_main(input: FsInput) -> @location(0) vec4<f32> {
  let mip = f32(SpriteAnimationInfo.MipMapLevel);
  let currentColor = textureSampleLevel(CurrentSprite, CurrentSprite_s, input.texCoord0, mip);
  let nextColor = textureSampleLevel(NextSprite, NextSprite_s, input.texCoord0, mip);
  return mix(currentColor, nextColor, vec4<f32>(input.fAnimationProgress));
}`;
      } else {
        const spr = locOf("Sprite", 0);
        fs = `${fsInput}

@group(${spr.g}) @binding(${spr.b}) var Sprite_s: sampler;
@group(${spr.g}) @binding(${spr.b + 1}) var Sprite: texture_2d<f32>;

@fragment
fn fs_main(input: FsInput) -> @location(0) vec4<f32> {
  return textureSampleLevel(Sprite, Sprite_s, input.texCoord0, f32(SpriteAnimationInfo.MipMapLevel));
}`;
      }
    }
    // assets/minecraft/shaders/core/rendertype_end_portal.{vsh,fsh}.
    // The portal block entity and LevelLoadingScreen.Reason.END_PORTAL share
    // this exact vanilla pipeline. Its POSITION-only vertex format has neither
    // UV nor Color, so the generic shader fallback returned ColorModulator
    // (opaque white) even though both Mojang textures were bound and uploaded.
    // Keep Minecraft's projected, animated 15/16-layer shader semantics here;
    // only the source language changes from GLSL to WGSL.
    const isEndPortal = family === "rendertype_end_portal";
    if (isEndPortal) {
      const portalLayout = compileVertexLayout(spec.vertexFormats[0]);
      const positionAttribute = portalLayout.byName.get("Position");
      if (!positionAttribute) throw new Error("rendertype_end_portal needs Position");
      const portalPosition = positionAttribute.split3
        ? `vec3<f32>(input.${positionAttribute.hiName}, input.${positionAttribute.loName})`
        : `input.${positionAttribute.name}.xyz`;
      const sampler0 = samplerMap.get("Sampler0");
      const sampler1 = samplerMap.get("Sampler1");
      if (!sampler0 || !sampler1) {
        throw new Error("rendertype_end_portal needs Sampler0 and Sampler1");
      }
      const portalLayers = Math.max(1, Math.min(16,
        Number.parseInt(String((spec.defines || [])
          .find((value) => String(value).startsWith("PORTAL_LAYERS=")) || "=15")
          .split("=")[1], 10) || 15));
      vs = `struct VsInput {
${portalLayout.inputMembers}
};

struct VsOutput {
  @builtin(position) position: vec4<f32>,
  @location(0) texProj0: vec4<f32>,
  @location(1) sphericalVertexDistance: f32,
  @location(2) cylindricalVertexDistance: f32,
};

@vertex
fn vs_main(input: VsInput) -> VsOutput {
  let modelPosition = ${portalPosition};
  let clipPosition = Projection.ProjMat * DynamicTransforms.ModelViewMat
    * vec4<f32>(modelPosition, 1.0);
  var projected = clipPosition * 0.5;
  projected.x = projected.x + clipPosition.w;
  projected.y = projected.y + clipPosition.w;
  projected.z = clipPosition.z;
  projected.w = clipPosition.w;
  var output: VsOutput;
  output.position = clipPosition;
  output.texProj0 = projected;
  output.sphericalVertexDistance = length(modelPosition);
  output.cylindricalVertexDistance = max(length(modelPosition.xz), abs(modelPosition.y));
  return output;
}`;
      fs = `const PORTAL_COLORS = array<vec3<f32>, 16>(
  vec3<f32>(0.022087, 0.098399, 0.110818),
  vec3<f32>(0.011892, 0.095924, 0.089485),
  vec3<f32>(0.027636, 0.101689, 0.100326),
  vec3<f32>(0.046564, 0.109883, 0.114838),
  vec3<f32>(0.064901, 0.117696, 0.097189),
  vec3<f32>(0.063761, 0.086895, 0.123646),
  vec3<f32>(0.084817, 0.111994, 0.166380),
  vec3<f32>(0.097489, 0.154120, 0.091064),
  vec3<f32>(0.106152, 0.131144, 0.195191),
  vec3<f32>(0.097721, 0.110188, 0.187229),
  vec3<f32>(0.133516, 0.138278, 0.148582),
  vec3<f32>(0.070006, 0.243332, 0.235792),
  vec3<f32>(0.196766, 0.142899, 0.214696),
  vec3<f32>(0.047281, 0.315338, 0.321970),
  vec3<f32>(0.204675, 0.390010, 0.302066),
  vec3<f32>(0.080955, 0.314821, 0.661491));

const PORTAL_SCALE_TRANSLATE = mat4x4<f32>(
  0.5, 0.0, 0.0, 0.25,
  0.0, 0.5, 0.0, 0.25,
  0.0, 0.0, 1.0, 0.0,
  0.0, 0.0, 0.0, 1.0);

@group(${sampler0.g}) @binding(${sampler0.b}) var Sampler0_s: sampler;
@group(${sampler0.g}) @binding(${sampler0.b + 1}) var Sampler0: texture_2d<f32>;
@group(${sampler1.g}) @binding(${sampler1.b}) var Sampler1_s: sampler;
@group(${sampler1.g}) @binding(${sampler1.b + 1}) var Sampler1: texture_2d<f32>;

struct FsInput {
  @builtin(position) position: vec4<f32>,
  @location(0) texProj0: vec4<f32>,
  @location(1) sphericalVertexDistance: f32,
  @location(2) cylindricalVertexDistance: f32,
};

fn endPortalLayer(layer: f32) -> mat4x4<f32> {
  let translatedY = (2.0 + layer / 1.5) * (Globals.GameTime * 1.5);
  let translation = mat4x4<f32>(
    1.0, 0.0, 0.0, 17.0 / layer,
    0.0, 1.0, 0.0, translatedY,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0);
  let angle = radians((layer * layer * 4321.0 + layer * 9.0) * 2.0);
  let rotation = mat2x2<f32>(cos(angle), -sin(angle), sin(angle), cos(angle));
  let scaleValue = (4.5 - layer / 4.0) * 2.0;
  let scale = mat2x2<f32>(scaleValue, 0.0, 0.0, scaleValue);
  let scaledRotation = scale * rotation;
  let layerTransform = mat4x4<f32>(
    scaledRotation[0].x, scaledRotation[0].y, 0.0, 0.0,
    scaledRotation[1].x, scaledRotation[1].y, 0.0, 0.0,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0);
  return layerTransform * translation * PORTAL_SCALE_TRANSLATE;
}

fn projectedUv(position: vec4<f32>) -> vec2<f32> {
  return position.xy / max(abs(position.w), 0.000001) * sign(position.w);
}

fn linearFogValue(distance: f32, start: f32, end: f32) -> f32 {
  return clamp((distance - start) / max(end - start, 0.0001), 0.0, 1.0);
}

@fragment
fn fs_main(input: FsInput) -> @location(0) vec4<f32> {
  var color = textureSample(Sampler0, Sampler0_s, projectedUv(input.texProj0)).rgb
    * PORTAL_COLORS[0];
  for (var i = 0; i < ${portalLayers}; i = i + 1) {
    let projected = input.texProj0 * endPortalLayer(f32(i + 1));
    color = color + textureSample(Sampler1, Sampler1_s, projectedUv(projected)).rgb
      * PORTAL_COLORS[i];
  }
  let environmentalFog = linearFogValue(input.sphericalVertexDistance,
    Fog.FogEnvironmentalStart, Fog.FogEnvironmentalEnd);
  let renderFog = linearFogValue(input.cylindricalVertexDistance,
    Fog.FogRenderDistanceStart, Fog.FogRenderDistanceEnd);
  let fogValue = max(environmentalFog, renderFog);
  let fogColor = vec3<f32>(Fog.FogColorR, Fog.FogColorG, Fog.FogColorB);
  return vec4<f32>(mix(color, fogColor, fogValue * Fog.FogColorA), 1.0);
}`;
    }
    // core/rendertype_clouds.{vsh,fsh}. Clouds have no vertex buffer: Mojang
    // expands one quad per three CloudFaces integers using gl_VertexID. The old
    // no-input fallback mistook that for a fullscreen blit and painted the
    // entire world opaque white.
    const isClouds = family === "rendertype_clouds";
    if (isClouds) {
      vs = `const CLOUD_VERTICES = array<vec3<f32>, 24>(
  vec3<f32>(1.0,0.0,0.0), vec3<f32>(1.0,0.0,1.0), vec3<f32>(0.0,0.0,1.0), vec3<f32>(0.0,0.0,0.0),
  vec3<f32>(0.0,1.0,0.0), vec3<f32>(0.0,1.0,1.0), vec3<f32>(1.0,1.0,1.0), vec3<f32>(1.0,1.0,0.0),
  vec3<f32>(0.0,0.0,0.0), vec3<f32>(0.0,1.0,0.0), vec3<f32>(1.0,1.0,0.0), vec3<f32>(1.0,0.0,0.0),
  vec3<f32>(1.0,0.0,1.0), vec3<f32>(1.0,1.0,1.0), vec3<f32>(0.0,1.0,1.0), vec3<f32>(0.0,0.0,1.0),
  vec3<f32>(0.0,0.0,1.0), vec3<f32>(0.0,1.0,1.0), vec3<f32>(0.0,1.0,0.0), vec3<f32>(0.0,0.0,0.0),
  vec3<f32>(1.0,0.0,0.0), vec3<f32>(1.0,1.0,0.0), vec3<f32>(1.0,1.0,1.0), vec3<f32>(1.0,0.0,1.0));
const CLOUD_FACE_SHADE = array<f32, 6>(0.7, 1.0, 0.8, 0.8, 0.9, 0.9);

// CloudFaces is an R8_SINT texel buffer. WebGPU cannot bind a formatted buffer
// view, so its physical (four-byte padded) bytes arrive as packed u32 storage
// words. Select and sign-extend the same byte texelFetch would have returned.
fn cloudFaceAt(index: i32) -> i32 {
  let byteIndex = u32(index);
  let word = CloudFaces[byteIndex >> 2u];
  let raw = (word >> ((byteIndex & 3u) * 8u)) & 255u;
  return select(i32(raw), i32(raw) - 256, raw >= 128u);
}

struct VsOutput {
  @builtin(position) position: vec4<f32>,
  @location(0) vertexDistance: f32,
  @location(1) vertexColor: vec4<f32>,
};

@vertex
fn vs_main(@builtin(vertex_index) vertexIndex: u32) -> VsOutput {
  let quadVertex = i32(vertexIndex % 4u);
  let faceIndex = i32(vertexIndex / 4u) * 3;
  var cellX = cloudFaceAt(faceIndex);
  var cellZ = cloudFaceAt(faceIndex + 1);
  let flags = cloudFaceAt(faceIndex + 2);
  let direction = flags & 7;
  let inside = (flags & 16) == 16;
  let useTopColor = (flags & 32) == 32;
  cellX = (cellX << 1) | ((flags & 128) >> 7);
  cellZ = (cellZ << 1) | ((flags & 64) >> 6);
  let localVertex = select(quadVertex, 3 - quadVertex, inside);
  let faceVertex = CLOUD_VERTICES[direction * 4 + localVertex];
  let cellSize = vec3<f32>(CloudInfo.CellSizeX, CloudInfo.CellSizeY, CloudInfo.CellSizeZ);
  let cloudOffset = vec3<f32>(
    CloudInfo.CloudOffsetX, CloudInfo.CloudOffsetY, CloudInfo.CloudOffsetZ);
  let pos = faceVertex * cellSize + vec3<f32>(f32(cellX), 0.0, f32(cellZ)) * cellSize + cloudOffset;
  let shade = select(CLOUD_FACE_SHADE[direction], CLOUD_FACE_SHADE[1], useTopColor);
  let cloudColor = vec4<f32>(
    CloudInfo.CloudColorR, CloudInfo.CloudColorG, CloudInfo.CloudColorB, CloudInfo.CloudColorA);
  var output: VsOutput;
  output.position = Projection.ProjMat * DynamicTransforms.ModelViewMat * vec4<f32>(pos, 1.0);
  output.vertexDistance = length(pos);
  output.vertexColor = vec4<f32>(vec3<f32>(shade), 1.0) * cloudColor;
  return output;
}`;
      fs = `struct FsInput {
  @builtin(position) position: vec4<f32>,
  @location(0) vertexDistance: f32,
  @location(1) vertexColor: vec4<f32>,
};

@fragment
fn fs_main(input: FsInput) -> @location(0) vec4<f32> {
  let fogValue = clamp(input.vertexDistance / max(Fog.FogCloudsEnd, 0.0001), 0.0, 1.0);
  return vec4<f32>(input.vertexColor.rgb, input.vertexColor.a * (1.0 - fogValue));
}`;
    }
    // assets/minecraft/shaders/post/transparency.fsh. Improved transparency
    // renders the main scene plus five premultiplied translucent layers into
    // separate color/depth targets, sorts them by depth per pixel, and blends
    // them back-to-front. The old generic no-vertex fallback sampled only the
    // first color target and declared every depth view as an ordinary float
    // texture; WebGPU rejected that bind group and Main remained black.
    const isTransparencyPost = /(?:^|\/)post\/transparency$/i.test(
      String(spec.fragmentShader || "").replace(/^minecraft:/, "")
    );
    if (isTransparencyPost) {
      const samplerNames = [...samplerMap.keys()];
      const findSampler = (stem, depth) => samplerNames.find((name) => {
        const normalized = String(name).replace(/Sampler$/i, "");
        return normalized.toLowerCase()
          === `${stem}${depth ? "Depth" : ""}`.toLowerCase();
      });
      const layers = ["Main", "Translucent", "ItemEntity", "Particles", "Weather", "Clouds"]
        .map((stem) => ({
          stem,
          color: findSampler(stem, false),
          depth: findSampler(stem, true),
        }));
      if (layers.every((layer) => layer.color && layer.depth)) {
        const decls = layers.flatMap((layer) => [layer.color, layer.depth])
          .map((name) => {
            const loc = samplerMap.get(name);
            const textureType = isDepthSamplerName(name)
              ? "texture_depth_2d" : "texture_2d<f32>";
            return `@group(${loc.g}) @binding(${loc.b + 1}) var ${sanitizeName(name)}: ${textureType};`;
          }).join("\n");
        const load = (name) => {
          const n = sanitizeName(name);
          return `textureLoad(${n}, clamp(vec2<i32>(input.position.xy), vec2<i32>(0), vec2<i32>(textureDimensions(${n}, 0)) - vec2<i32>(1)), 0)`;
        };
        const colorInitializers = layers.map((layer, index) =>
          `  colors[${index}] = ${load(layer.color)};`).join("\n");
        const depthInitializers = layers.map((layer, index) =>
          `  depths[${index}] = ${load(layer.depth)};`).join("\n");
        vs = `struct VsOutput {
  @builtin(position) position: vec4<f32>,
};

@vertex
fn vs_main(@builtin(vertex_index) vertexIndex: u32) -> VsOutput {
  let uv = vec2<f32>(f32((vertexIndex << 1u) & 2u), f32(vertexIndex & 2u));
  var output: VsOutput;
  output.position = vec4<f32>(uv * vec2<f32>(2.0, 2.0) + vec2<f32>(-1.0, -1.0), 0.0, 1.0);
  return output;
}`;
        fs = `${decls}

struct FsInput {
  @builtin(position) position: vec4<f32>,
};

@fragment
fn fs_main(input: FsInput) -> @location(0) vec4<f32> {
  var colors: array<vec4<f32>, 6>;
  var depths: array<f32, 6>;
${colorInitializers}
${depthInitializers}
  // Transparent clears have alpha zero. Put those entries behind the active
  // [0,1] depth range so they cannot displace the opaque Main base.
  for (var i = 1; i < 6; i = i + 1) {
    if (colors[i].a == 0.0) {
      depths[i] = 2.0;
    }
  }
  for (var i = 1; i < 6; i = i + 1) {
    var j = i;
    loop {
      if (j <= 0 || depths[j] >= depths[j - 1]) { break; }
      let depthSwap = depths[j - 1];
      depths[j - 1] = depths[j];
      depths[j] = depthSwap;
      let colorSwap = colors[j - 1];
      colors[j - 1] = colors[j];
      colors[j] = colorSwap;
      j = j - 1;
    }
  }
  var accumulated = vec3<f32>(0.0);
  for (var i = 0; i < 6; i = i + 1) {
    accumulated = accumulated * (1.0 - colors[i].a) + colors[i].rgb;
  }
  return vec4<f32>(accumulated, 1.0);
}`;
      }
    }
    // Fullscreen-blit shape: explicit screenquad family, or any pipeline with
    // no vertex input (animate_sprite_blit/interpolate, lightmap): synthesize
    // a fullscreen triangle from vertex_index and sample the first sampler.
    const isBlit = !isAnimateSprite && !isEndPortal && !isClouds && !isTransparencyPost
      && (family === "screenquad" || !spec.vertexFormats || !spec.vertexFormats.length);
    if (isBlit) {
      vs = `@vertex
fn vs_main(@builtin(vertex_index) vertexIndex: u32) -> VsOutput {
  var uv = vec2<f32>(f32((vertexIndex << 1u) & 2u), f32(vertexIndex & 2u));
  var output: VsOutput;
  output.position = vec4<f32>(uv * vec2<f32>(2.0, 2.0) + vec2<f32>(-1.0, -1.0), 0.0, 1.0);
  return output;
}
struct VsOutput {
  @builtin(position) position: vec4<f32>,
};`;
      const samplerGroup = groups.findIndex((g) => g.samplers.length > 0);
      if (samplerGroup < 0) {
        // No texture input (clouds read a texel buffer; lightmap computes
        // procedurally): emit a valid constant-color fragment so the pipeline
        // compiles — none of these draw on the title screen.
        fs = `struct FsInput {
  @builtin(position) position: vec4<f32>,
};

@fragment
fn fs_main(input: FsInput) -> @location(0) vec4<f32> {
  return vec4<f32>(1.0, 1.0, 1.0, 1.0);
}`;
      } else {
      const g = groups[samplerGroup];
      const blitName = sanitizeName(g.samplers[0]);
      // Use the REMAPPED (group, binding) from samplerMap — NOT the raw group
      // index / g.uniforms.length. When groups are folded into the K-1 merged
      // group (this device caps maxBindGroups at 4, the blur post-chain has
      // more), the raw binding collides with a uniform's binding in the merged
      // layout ("binding index (0) was specified by a previous entry") and the
      // whole blur pipeline — and thus the title-screen render — fails.
      const sloc = samplerMap.get(g.samplers[0]) || { g: samplerGroup, b: g.uniforms.length };
      fs = `struct FsInput {
  @builtin(position) position: vec4<f32>,
};

@group(${sloc.g}) @binding(${sloc.b}) var ${blitName}_s: sampler;
@group(${sloc.g}) @binding(${sloc.b + 1}) var ${blitName}: texture_2d<f32>;

@fragment
fn fs_main(input: FsInput) -> @location(0) vec4<f32> {
  let size = textureDimensions(${blitName}, 0);
  let uv = (input.position.xy + vec2<f32>(0.5, 0.5)) / vec2<f32>(f32(size.x), f32(size.y));
  return textureSample(${blitName}, ${blitName}_s, uv);
}`;
      }
    } else if (!isAnimateSprite && !isEndPortal && !isClouds && !isTransparencyPost) {
      // (isAnimateSprite already assigned vs/fs above; falling into buildVs
      //  here would clobber them with null, since animate_sprite has no vertex
      //  format and buildVs returns null on an empty format -> pipeline-skipped.)
      vs = buildVs(family, spec, groups, groupRemap);
      fs = buildFs(family, spec, groups, groupRemap, samplerMap);
    }
    if (!vs || !fs) { _popScope(); return null; }

    const wgslCode = `${uniformDecl}\n${vs}\n${fs}\n`;
    if (/terrain/.test(spec.label || "") || /terrain/.test(family)
        || /^(block|particle|item|entity|rendertype_clouds|rendertype_end_portal)$/.test(family)) {
      (globalThis.mcWebGpu._wgsl ||= {})[spec.label || family] = wgslCode;
    }
    const module = device.createShaderModule({
      label: `minecraft:${family}`,
      code: wgslCode
    });
    // Surface shader compile errors (the real-GPU driver may reject WGSL that
    // headless SwiftShader accepts). Logged once per family to avoid spam.
    if (module.getCompilationInfo && !buildPipeline._loggedCompile) buildPipeline._loggedCompile = new Set();
    if (module.getCompilationInfo && !buildPipeline._loggedCompile.has(family)) {
      module.getCompilationInfo().then((info) => {
        const errs = (info?.messages || []).filter((m) => m.type === "error");
        if (errs.length) {
          buildPipeline._loggedCompile.add(family);
          console.error(`[WGSL compile error] ${spec.label} (family=${family}):`,
            errs.map((m) => `${m.message} @${m.lineNum}:${m.linePos}`).join(" | "),
            "\n---WGSL---\n" + wgslCode);
        }
      }).catch(() => {});
    }

    // One layout per *merged* group. When groups were folded, each merged
    // layout aggregates the entries of all original groups that map to it, at
    // their remapped bindings (matching the WGSL + the host bind calls).
    const bindGroupLayouts = [];
    for (let ng = 0; ng < groupCount; ng++) {
      const entries = [];
      groups.forEach((group, gi) => {
        const r = groupRemap[gi];
        if (r.ng !== ng) return;
        group.uniforms.forEach((uniformDesc, ui) => {
          entries.push({
            binding: r.baseU + ui,
            visibility: GPUShaderStage.VERTEX | GPUShaderStage.FRAGMENT,
            buffer: {type: uniformDesc.type === "TEXEL_BUFFER" ? "read-only-storage" : "uniform"}
          });
        });
        group.samplers.forEach((samplerName, si) => {
          const base = samplerBase(gi) + si * 2;
          entries.push({binding: base, visibility: GPUShaderStage.FRAGMENT, sampler: {type: "filtering"}});
          entries.push({binding: base + 1, visibility: GPUShaderStage.FRAGMENT,
            texture: {
              sampleType: isDepthSamplerName(samplerName) ? "depth" : "float",
              viewDimension: family === "panorama" ? "cube" : "2d"
            }});
        });
      });
      bindGroupLayouts.push(device.createBindGroupLayout({label: `${spec.label}:group${ng}`, entries}));
    }

    const format0 = spec.vertexFormats[0];
    // DIAG: terrain renders saturated white (255,255,255), not FogColor, so the
    // ChunkVisibility mix is fine and one multiplier in
    //   atlasSample * Color * sample_lightmap(Sampler2, UV2)
    // is ~255x too large. A unorm8 colour decoded as raw uint8 does exactly
    // that, so record the declared vs mapped attribute formats.
    if (/terrain|entity_solid/.test(spec.label || "")) {
      (globalThis.mcWebGpu._vertexLayouts ||= {})[spec.label] =
        spec.vertexFormats.map((vf) => ({
          stride: vf.stride,
          elements: (vf.elements || []).map((e) => ({
            name: e.name,
            declared: e.format,
            mapped: (VERTEX_GPU_FORMATS[e.format] || {}).gpu ?? "UNMAPPED",
            offset: e.offset
          }))
        }));
    }
    let vertex = undefined;
    if (family !== "screenquad" && family !== "animate_sprite" && format0) {
      vertex = {
        module,
        entryPoint: "vs_main",
        buffers: spec.vertexFormats.map((vf) => ({
          arrayStride: vf.stride,
          stepMode: vf.stepRate > 1 ? "instance" : "vertex",
          attributes: compileVertexLayout(vf).gpuAttrs
        }))
      };
    }

    const targets = [];
    const colorTargets = spec.colorTargets && spec.colorTargets.length
      ? spec.colorTargets
      : [null];
    for (const target of colorTargets) {
      if (!target) {
        targets.push(undefined);
        continue;
      }
      const entry = {format: minecraftFormat(target.format)};
      if (target.writeMask !== undefined && target.writeMask !== 15) {
        entry.writeMask = target.writeMask;
      }
      if (target.blend) {
        entry.blend = {
          color: {
            srcFactor: BLEND_FACTOR[target.blend.colorSrc],
            dstFactor: BLEND_FACTOR[target.blend.colorDst],
            operation: BLEND_OP[target.blend.colorOp]
          },
          alpha: {
            srcFactor: BLEND_FACTOR[target.blend.alphaSrc],
            dstFactor: BLEND_FACTOR[target.blend.alphaDst],
            operation: BLEND_OP[target.blend.alphaOp]
          }
        };
      }
      if (_drawCensus && !_blendLogged.has(spec.label)) {
        _blendLogged.add(spec.label);
        // A colour write mask of 0, or a blend that resolves to "keep the
        // destination", makes a draw invisible while every other state looks
        // correct - the same symptom as never drawing at all.
        console.log("[draw-census] target " + spec.label
          + " writeMask=" + (target.writeMask === undefined ? 15 : target.writeMask)
          + " blend=" + (entry.blend
            ? entry.blend.color.srcFactor + "/" + entry.blend.color.dstFactor
              + " a:" + entry.blend.alpha.srcFactor + "/" + entry.blend.alpha.dstFactor
            : "none")
          + " fmt=" + entry.format);
      }
      targets.push(entry);
    }

    const descriptor = {
      label: spec.label,
      layout: device.createPipelineLayout({
        label: `${spec.label}:layout`,
        bindGroupLayouts
      }),
      vertex: vertex || {module, entryPoint: "vs_main"},
      fragment: {
        module,
        entryPoint: "fs_main",
        targets: targets.map((t) => t || {format: "rgba8unorm"})
      },
      primitive: {
        topology: TOPOLOGY[spec.topology] || "triangle-list",
        // Sprite blits are two-dimensional copies, so culling has no useful
        // effect there.
        // ?mcweb_nocull=1 disables backface culling everywhere. Terrain is the
        // only family that culls and the only one that renders nothing, with
        // every input (vertices, indices, uniforms, transform, textures)
        // verified correct and no validation errors -- so reversed winding is
        // the remaining candidate, and this separates it in one run.
        cullMode: (_forceNoCull || family === "animate_sprite")
          ? "none"
          : (spec.cull ? "back" : "none"),
        frontFace: "ccw"
      }
    };
    if (spec.depthStencil && depthFormat != null) {
      descriptor.depthStencil = {
        // Derived from the render pass's depth attachment (see rpSetPipeline
        // variant handling); the precompile default assumes the main target.
        //
        // The depthFormat != null guard matters: createPipeline also builds a
        // NO-depth variant (depthFormat === null) of every family, including
        // those whose spec DOES declare depth state. Without the guard that
        // variant emitted `format: null` and WebGPU rejected the pipeline with
        // "Failed to read the 'format' property from 'GPUDepthStencilState'",
        // losing the no-depth variant for solid_terrain and cutout_terrain.
        // A spec-declared depth state simply cannot apply to a pass that has
        // no depth attachment, so the variant must be depth-less instead.
        format: depthFormat,
        depthWriteEnabled: spec.depthStencil.writeDepth,
        // ?mcweb_diag=depthalways -> terrain ignores the depth test. Splits
        // "the depth state rejects every terrain fragment" from "the geometry
        // or its uniforms are wrong": if blocks appear under this flag, depth
        // is the fault; if the frame is unchanged, depth is exonerated.
        depthCompare: (_forceDepthAlways && /terrain/.test(spec.label))
          ? "always"
          : (COMPARE_OP[spec.depthStencil.depthTest] || "less-equal"),
        depthBias: Math.round(spec.depthStencil.biasConstant || 0),
        depthBiasSlopeScale: spec.depthStencil.biasScale || 0
      };
      if (_drawCensus && !_depthStateLogged.has(spec.label)) {
        _depthStateLogged.add(spec.label);
        console.log("[draw-census] depthstate " + spec.label
          + " compare=" + descriptor.depthStencil.depthCompare
          + " write=" + descriptor.depthStencil.depthWriteEnabled
          + " declared=" + spec.depthStencil.depthTest);
      }
    } else if (depthRequested) {
      // The spec declared no depth state, but the pass we are being built for
      // HAS a depth attachment (panorama/sky AND the fullscreen blits are all
      // drawn into the depth-bearing main pass at some point). WebGPU requires
      // the pipeline's attachment state to match the pass regardless of family,
      // so synthesize a depth state that never disturbs the buffer. Guarded by
      // depthRequested so the NO-depth variant (depthFormat === null) stays
      // depth-less — otherwise a pipeline built for a no-depth pass would itself
      // fail validation. createPipeline pre-builds both variants into byDepth.
      if (_drawCensus && !_depthStateLogged.has(spec.label)) {
        _depthStateLogged.add(spec.label);
        console.log("[draw-census] depthstate " + spec.label
          + " compare=always write=false declared=SYNTHESIZED");
      }
      descriptor.depthStencil = {
        format: depthFormat,
        depthWriteEnabled: false,
        depthCompare: "always",
        // depthBias MUST be 0 for line-list topologies (rendertype_lines); since
        // this is a synthesized never-write/always-pass state, zero bias is also
        // the correct no-op for every other topology.
        depthBias: 0,
        depthBiasSlopeScale: 0
      };
    }
    // WebGPU forbids non-zero depth bias on line topologies ("depthBias must be
    // 0 when using PrimitiveTopology::LineList"). Mojang's lines render type
    // sets a bias, which would fail pipeline creation (and the precompile gate);
    // clamp it for any line topology regardless of whether the depth state came
    // from the spec or was synthesized above.
    if (descriptor.depthStencil && /^line/.test(TOPOLOGY[spec.topology] || "")) {
      descriptor.depthStencil.depthBias = 0;
      descriptor.depthStencil.depthBiasSlopeScale = 0;
    }
    if (/lines/.test(spec.label) && _DBG) {
      console.log("[lines-dbg] label=", spec.label, "spec.topology=", JSON.stringify(spec.topology),
        "mapped=", TOPOLOGY[spec.topology], "hadDepthStencil=", !!spec.depthStencil,
        "depthBias=", descriptor.depthStencil && descriptor.depthStencil.depthBias);
    }

    if (typeof device.pushErrorScope === "function") device.pushErrorScope("validation");
    const pipeline = device.createRenderPipeline(descriptor);
    if (typeof device.popErrorScope === "function") {
      device.popErrorScope().then((err) => {
        if (err && !(buildPipeline._loggedCreate || (buildPipeline._loggedCreate = new Set())).has(spec.label)) {
          buildPipeline._loggedCreate.add(spec.label);
          console.error(`[pipeline create error] ${spec.label} (family=${family}): ${err.message}`,
            "\nvertexFormats=" + JSON.stringify((spec.vertexFormats || []).map((vf) => vf.elements?.map((e) => e.name + ":" + e.format))),
            "\n---WGSL---\n" + wgslCode);
        }
      }).catch(() => {});
    }
    _popScope();
    return {pipeline, spec, family, depthFormat, groupRemap, groupCount,
      samplerBases: groups.map((g, gi) => samplerBase(gi))};
  };

  // ---------------------------------------------------------------------------
  // Non-indexed topology lowering (QUADS / TRIANGLE_FAN): synthesize index
  // buffers on demand, then drawIndexed.
  // ---------------------------------------------------------------------------

  const loweringIndexBuffers = new Map();
  const loweredIndices = (topology, vertexCount) => {
    const key = `${topology}:${vertexCount}`;
    let entry = loweringIndexBuffers.get(key);
    if (entry) return entry;
    let indices;
    if (topology === "QUADS") {
      const quads = Math.floor(vertexCount / 4);
      indices = new Uint32Array(quads * 6);
      for (let i = 0; i < quads; i++) {
        const base = i * 4;
        const out = i * 6;
        indices[out] = base;
        indices[out + 1] = base + 1;
        indices[out + 2] = base + 2;
        indices[out + 3] = base;
        indices[out + 4] = base + 2;
        indices[out + 5] = base + 3;
      }
    } else if (topology === "TRIANGLE_FAN") {
      const triangles = Math.max(0, vertexCount - 2);
      indices = new Uint32Array(triangles * 3);
      for (let i = 0; i < triangles; i++) {
        indices[i * 3] = 0;
        indices[i * 3 + 1] = i + 1;
        indices[i * 3 + 2] = i + 2;
      }
    } else {
      return null;
    }
    const buffer = device.createBuffer({
      label: `lowered ${topology} ${vertexCount}`,
      size: Math.max(4, indices.byteLength),
      usage: GPUBufferUsage.INDEX | GPUBufferUsage.COPY_DST
    });
    device.queue.writeBuffer(buffer, 0, indices);
    entry = {buffer, count: indices.length};
    loweringIndexBuffers.set(key, entry);
    return entry;
  };

  // ---------------------------------------------------------------------------
  // Host-side present composite: Mojang's 26.2 presenter renders the scene to
  // an offscreen main target but never blits it to the swap-chain canvas (its
  // blitFromTexture/copyTexture path is not exercised here), so the canvas
  // would stay empty. We track the last scene color target and, when a render
  // pass is opened against the canvas, draw that texture into it with a
  // dedicated fullscreen-triangle blit pipeline.
  let compositeSource = null;
  let _compositeLogAt = 0;
  let _lastPresentAt = 0;
  let _presentEnterAt = 0;
  let _presentExitAt = 0;
  let _presentCount = 0;
  let _rpDbgN = 0; // bounded rpSetPipeline debug trace counter (module scope)
  // DIAG: textures actually sampled (bound into a bind group) this frame, and
  // the last auto/forced texture-readback snapshot (see mcWebGpu._texDiag).
  let _sampledThisFrame = new Set();
  let _lastFrameSampled = new Set(); // DIAG: sampled set of the most recent frame
  const _sampledEntries = new Set(); // DIAG: every texture entry ever bound for sampling
  let _texDiagSnapshot = null;
  let _texDiagDone = false;
  let _texDiagLateDone = false; // DIAG: late lifetime-upload dump fired once
  let _animDbgDone = false; // DIAG: one-shot animate_sprite UBO dump
  let _texDrawDbgN = 0; // DIAG: count of textured GUI draw dumps emitted (cap 8)
  // DIAG: ring buffer of the ACTUAL decoded vertex/index bytes for the first few
  // textured GUI draws. The older _maybeTexDrawDiag reads VBO offset 0 (not the
  // batched draw's baseVertex) and never stored the index-buffer handle, so it
  // could not show why atlas-sampled GUI quads scatter while the standalone logo
  // (same family) renders correctly. This captures, per draw, the decoded
  // Position/UV0/Color at baseVertex+index, the index values, the draw params,
  // and the DynamicTransforms block (incl. TextureMat), then dumps once.
  const _guiDrawRing = [];
  // Item models are rendered into a dedicated offscreen atlas with the
  // block/item/entity pipelines, so the early GUI-only ring above never sees
  // the draws that populate missing inventory icons. Keep a separate bounded
  // trace with the exact slot transform and scissor used for each atlas draw.
  const _itemAtlasDrawRing = [];
  // DIAG: title-frame text draws are normally reached after the early GUI ring
  // has filled with the Mojang splash/logo. Keep them in a separate bounded
  // ring so font debugging can prove whether glyph quads and a glyph texture
  // actually reach the host.
  const _fontDrawRing = [];
  const _fontPassStats = new Map();
  let _guiRingDumped = false;
  // DIAG: panorama cube-fill + draw probe (consolidated single-line dump at frame 2).
  const _panoProbe = { created: [], uploads: [], pipeBegin: [], pipeOk: [], pipeSkip: [], pipeFail: [], set: 0 };
  let _panoDumped = false;
  // DIAG: global upload history by label [count, bytes], and a creation registry
  // (every texture instance), so we can spot a label that WAS uploaded but whose
  // sampled instance wasn't (stale/duplicate handle).
  const _uploadByLabel = new Map();
  const _recentCreated = [];
  // Verbose per-frame/pipeline diagnostics; off by default (set ?mcweb_debug).
  const _DBG = (typeof location !== "undefined") && new URLSearchParams(location.search).has("mcweb_debug");
  let compositePipeline = null;
  let compositePipelineH = 0;
  let compositeSampler = null;
  // Region clears cannot use attachment loadOp=clear: WebGPU applies that to
  // the entire subresource, regardless of render area or scissor. Minecraft's
  // GUI item cache clears one atlas tile before drawing each item, so turning
  // those into full clears erased every previously rendered inventory icon.
  // Cache tiny draw-based clear pipelines by attachment formats + values.
  const regionClearPipelines = new Map();
  // Dummy resources used to fill bind-group slots whose real resource isn't
  // bound yet, so a group is ALWAYS complete and setBindGroup never gets
  // skipped (skipping it leaves the group unbound → "No bind group at group
  // index N" validation → Mojang's Java throws → the frame pump dies).
  let dummyUniform = null, dummyStorage = null, dummySampler = null;
  let dummyTexView = null, dummyDepthView = null;
  // Fence-based deferred GPU-resource destruction. Mojang deletes a uniform/
  // vertex buffer (e.g. SpriteAnimationInfo) while a command buffer that
  // references it is still in flight; native GL retains the buffer until the GPU
  // is done, but WebGPU rejects the submit ("used in submit while destroyed") and
  // the frame aborts, killing the pump. So on destroy we only drop the live
  // registry entry and park the GPU object in a pending list; after each submit
  // we snapshot that list and hold it until queue.onSubmittedWorkDone() resolves
  // (= every submit issued up to that point has completed on the GPU), which is
  // the exact moment no in-flight command buffer can reference it. A frame-count
  // grace is too fragile because a buffer destroyed AFTER a submit is still in
  // flight for that submit.
  const _deferredDestroy = globalThis.mcWebTextureLifetime.createDeferredDestroyQueue({
    getQueue: () => device?.queue ?? null,
    fallbackBatches: 4,
  });
  const deferDestroyBuffer = _deferredDestroy.deferDestroyBuffer;
  const deferDestroyTexture = _deferredDestroy.deferDestroyTexture;
  const flushGraveyard = _deferredDestroy.flush;
  const _textureLifetime = globalThis.mcWebTextureLifetime.createTextureLifetime({
    deferDestroyTexture,
    recentCalls: () => _recentCalls,
    rpcBatch: () => globalThis.mcWebGpu?._rpcLastBatch ?? null,
  });
  let diagChecker = null;
  // Per-target draw accounting to identify the real scene target by data.
  let texSeq = 0;
  const texInventory = [];
  let frameDraws = new Map();   // tid -> draw calls this frame
  let lastFrameDraws = new Map();
  let drawSeq = 0;              // monotonic per-pass draw-order stamp
  let frameDrawSeq = new Map(); // tid -> last drawSeq that drew into it this frame
  let lastFrameDrawSeq = new Map();
  // Absolute last-drawn target this frame (incl. the canvas, _tid -1) so we can
  // tell "Mojang drew the final image to the canvas" (splash ⇒ don't composite)
  // from "Mojang's final image is offscreen" (title ⇒ composite it).
  let frameLastTid = 0;
  let frameLastIsCanvas = false;
  let lastFrameIsCanvas = false;
  const ensureDiagChecker = () => {
    if (diagChecker) return diagChecker;
    const S = 16;
    const data = new Uint8Array(S * S * 4);
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y * S + x) * 4, on = (x + y) & 1;
      data[i] = on ? 255 : 40; data[i+1] = on ? 150 : 40; data[i+2] = on ? 0 : 200; data[i+3] = 255;
    }
    const t = device.createTexture({size: [S, S], format: "rgba8unorm",
      usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_DST});
    device.queue.writeTexture({texture: t}, data, {bytesPerRow: S * 4}, {width: S, height: S});
    diagChecker = t;
    return t;
  };
  const ensureComposite = (h) => {
    if (!compositeSampler) {
      compositeSampler = device.createSampler({magFilter: "linear", minFilter: "linear"});
    }
    if (compositePipeline && compositePipelineH === h) return compositePipeline;
    const mod = device.createShaderModule({label: "mcweb-composite", code: `
struct VsOut { @builtin(position) position: vec4<f32>, @location(0) uv: vec2<f32> };
@vertex fn vs_main(@builtin(vertex_index) i: u32) -> VsOut {
  var p = array<vec2<f32>,3>(vec2<f32>(-1.0,-1.0), vec2<f32>(3.0,-1.0), vec2<f32>(-1.0,3.0));
  var u = array<vec2<f32>,3>(vec2<f32>(0.0,1.0), vec2<f32>(2.0,1.0), vec2<f32>(0.0,-1.0));
  var o: VsOut; o.position = vec4<f32>(p[i], 0.0, 1.0); o.uv = u[i]; return o;
}
@group(0) @binding(0) var S_s: sampler;
@group(0) @binding(1) var S: texture_2d<f32>;
@fragment fn fs_main(in: VsOut) -> @location(0) vec4<f32> { return textureSample(S, S_s, in.uv); }
`});
    const layout = device.createPipelineLayout({bindGroupLayouts: [device.createBindGroupLayout({
      entries: [
        {binding: 0, visibility: GPUShaderStage.FRAGMENT, sampler: {type: "filtering"}},
        {binding: 1, visibility: GPUShaderStage.FRAGMENT, texture: {sampleType: "float", viewDimension: "2d"}}
      ]
    })]});
    compositePipeline = device.createRenderPipeline({
      label: "mcweb-composite", layout, vertex: {module: mod, entryPoint: "vs_main"},
      fragment: {module: mod, entryPoint: "fs_main", targets: [{format: "rgba8unorm"}]},
      primitive: {topology: "triangle-list"}
    });
    compositePipelineH = h;
    return compositePipeline;
  };

  // Render pass state
  // ---------------------------------------------------------------------------

  const passState = (passHandle) => get(passHandle, "render pass");
  // DIAG: one-shot dump of the first textured GUI draw (vertex bytes + matrices + target).
  // Called from both rpDraw and rpDrawIndexed because Mojang's BufferBuilder emits
  // indexed geometry for quads (logo, buttons, widgets all go through rpDrawIndexed).
  const _maybeTexDrawDiag = (state, drawKind) => {
    if (_texDrawDbgN >= 8 || !state.pipeline || !state.pipeline.spec) return;
    const vsId = state.pipeline.spec.vertexShader || "";
    const fam = shaderFamily(vsId);
    if (!/^(position_tex|gui_textured|text|position_tex_color|gui_text)$/.test(fam)) return;
    _texDrawDbgN++;
    const vf = state.pipeline.spec.vertexFormats && state.pipeline.spec.vertexFormats[0];
    const vb = state.vertexBuffers[0];
    let vbytes = "no vb0";
    if (vb && vb._handle) {
      const be = objects.get(vb._handle);
      if (be && be._shadow) {
        const base = vb.offset;
        const n = Math.min(64, be._shadow.length - base);
        const f32 = new Float32Array(be._shadow.buffer, base, Math.floor(n / 4));
        vbytes = JSON.stringify({off: vb.offset, sz: vb.size, f: Array.from(f32)});
      } else { vbytes = "vb0#" + vb._handle + " noshadow"; }
    }
    let ibytes = "no ib";
    if (state.indexBuffer && state.indexBuffer._handle) {
      const be = objects.get(state.indexBuffer._handle);
      if (be && be._shadow) {
        const u = state.indexBuffer.format === "uint16" ? new Uint16Array(be._shadow.buffer, 0, Math.min(12, be._shadow.length >> 1)) : new Uint32Array(be._shadow.buffer, 0, Math.min(12, be._shadow.length >> 2));
        ibytes = JSON.stringify({fmt: state.indexBuffer.format, idx: Array.from(u)});
      }
    }
    const grabMat = (uname) => {
      const u = state.resources.get(uname);
      if (!(u && u.kind === "uniform")) return "not-bound";
      const be = objects.get(u.bufferHandle);
      if (!(be && be._shadow)) return "noshadow";
      return Array.from(new Float32Array(be._shadow.buffer, u.offset, 16));
    };
    const sampled = [];
    for (const [nm, r] of state.resources) if (r && r.kind === "texture" && r._texEntry) sampled.push(nm + "=" + (r._texEntry._label || "?"));
    const layout = vf ? vf.elements.map((e) => e.name + "@" + e.offset + ":" + e.format) : "no-vf";
    console.log("[TEX-DRAW-DIAG #" + _texDrawDbgN + "] " + drawKind + " fam=" + fam + " tgt=" + state._targetLabel + " " + state._targetWH + " h=" + state.height
      + " | sampled=" + JSON.stringify(sampled)
      + " | Proj=" + JSON.stringify(grabMat("Projection"))
      + " | MV=" + JSON.stringify(grabMat("DynamicTransforms"))
      + " | layout=" + JSON.stringify(layout)
      + " | vb0=" + vbytes + " | ib=" + ibytes);
  };
  const _decodeGuiDraw = (state, kind, p) => {
  try {
    if (!state.pipeline || !state.pipeline.spec) return;
    const fam = shaderFamily(state.pipeline.spec.vertexShader || "");
    const isFontDraw = fam === "text" || /(?:^|[/_])text(?:$|[/_])/.test(state.pipeline.spec.label || "");
    const isItemAtlasDraw = /^UI items atlas(?:\s|$)/.test(state._targetLabel || "");
    const drawRing = isItemAtlasDraw ? _itemAtlasDrawRing : (isFontDraw ? _fontDrawRing : _guiDrawRing);
    const ringLimit = isItemAtlasDraw ? 160 : (isFontDraw ? 40 : 12);
    if (drawRing.length >= ringLimit) return;
    if (!isItemAtlasDraw && !/position_tex_color|position_tex|^text$|^gui$|gui_text/.test(fam)) return;
    const vf = state.pipeline.spec.vertexFormats && state.pipeline.spec.vertexFormats[0];
    const vb = state.vertexBuffers[0];
    if (!vb || !vb._handle) return;
    const be = objects.get(vb._handle);
    if (!be || !be._shadow) return;
    const stride = vf ? vf.stride : 32;
    const elOff = (n, d) => vf ? ((vf.elements.find((e) => e.name === n) || {}).offset ?? d) : d;
    const posOff = elOff("Position", 0), uvOff = elOff("UV0", 12), colOff = elOff("Color", 20);
    const dv = new DataView(be._shadow.buffer, be._shadow.byteOffset || 0);
    const readVert = (v) => {
      const base = vb.offset + v * stride;
      if (base < 0 || base + colOff + 4 > be._shadow.length) return null;
      return {
        pos: [dv.getFloat32(base + posOff, true), dv.getFloat32(base + posOff + 4, true), dv.getFloat32(base + posOff + 8, true)],
        uv: [dv.getFloat32(base + uvOff, true), dv.getFloat32(base + uvOff + 4, true)],
        col: [dv.getUint8(base + colOff), dv.getUint8(base + colOff + 1), dv.getUint8(base + colOff + 2), dv.getUint8(base + colOff + 3)]
      };
    };
    let idxs = null;
    const ib = state.indexBuffer;
    if (ib && ib._handle) {
      const ibe = objects.get(ib._handle);
      if (ibe && ibe._shadow) {
        const idv = new DataView(ibe._shadow.buffer, ibe._shadow.byteOffset || 0);
        const w = ib.format === "uint16" ? 2 : 4;
        idxs = [];
        for (let k = 0; k < Math.min(p.indexCount || 0, 6); k++) {
          const bo = (p.firstIndex + k) * w;
          idxs.push(w === 2 ? idv.getUint16(bo, true) : idv.getUint32(bo, true));
        }
      }
    }
    const verts = [];
    const n = idxs ? idxs.length : Math.min(p.vertexCount || 0, 6);
    for (let k = 0; k < n; k++) {
      const v = (p.baseVertex || 0) + (idxs ? idxs[k] : (p.firstVertex || 0) + k);
      verts.push(readVert(v));
    }
    const grab = (u) => {
      const r = state.resources.get(u);
      if (!(r && r.kind === "uniform")) return null;
      const b = objects.get(r.bufferHandle);
      if (!(b && b._shadow)) return null;
      const byteOffset = (b._shadow.byteOffset || 0) + r.offset;
      const available = Math.max(0, b._shadow.byteLength - r.offset);
      return Array.from(new Float32Array(b._shadow.buffer, byteOffset, Math.min(32, available >> 2)));
    };
    const sampled = []; for (const [nm, r] of state.resources) if (r && r.kind === "texture" && r._texEntry) sampled.push((r._texEntry._label || "?").split("/").pop());
    drawRing.push({ kind, pipe: state.pipeline.spec.label, fam, tgt: state._targetLabel, topo: state.pipeline.spec.topology, sampled, scissor: state._scissor || null, vbOff: vb.offset, vbSize: vb.size, stride, baseVertex: p.baseVertex || 0, firstIndex: p.firstIndex || 0, indexCount: p.indexCount || 0, firstVertex: p.firstVertex, idxs, verts, DT: grab("DynamicTransforms"), P: grab("Projection") });
  } catch (_e) { /* never let a decode error skip a real draw */ }
  };
  const _noteFontDraw = (state, kind, params = null) => {
    if (!state.pipeline || !state.pipeline.spec) return;
    const label = state.pipeline.spec.label || "";
    const fam = shaderFamily(state.pipeline.spec.vertexShader || "");
    if (fam !== "text" && !/(?:^|[/_])text(?:$|[/_])/.test(label)) return;
    const sampled = [];
    for (const [name, resource] of state.resources) {
      if (resource && resource.kind === "texture" && resource._texEntry) {
        sampled.push(name + "=" + (resource._texEntry._label || "?"));
      }
    }
    const key = kind + "|" + label + "|" + sampled.join(",");
    const old = _fontPassStats.get(key);
    _fontPassStats.set(key, {
      kind,
      pipe: label,
      fam,
      tgt: state._targetLabel,
      sampled,
      params,
      vertexSlots: state.vertexBuffers.map((slot, index) => slot ? {
        index,
        handle: slot._handle,
        offset: slot.offset,
        size: slot.size,
        shadow: Boolean(objects.get(slot._handle)?._shadow)
      } : null).filter(Boolean),
      index: state.indexBuffer ? {
        handle: state.indexBuffer._handle,
        format: state.indexBuffer.format,
        shadow: Boolean(objects.get(state.indexBuffer._handle)?._shadow)
      } : null,
      count: (old?.count || 0) + 1
    });
  };

  const ensureDummies = () => {
    if (dummyUniform) return;
    // Must be >= every uniform block the layout may reference (Mojang's Globals
    // is large); a too-small dummy makes createBindGroup throw, which aborts
    // ensureBindGroups before setBindGroup and leaves the group unbound.
    const ubSize = Math.min(device.limits?.maxUniformBufferBindingSize || 65536, 65536);
    dummyUniform = device.createBuffer({label: "mcweb-dummy-uniform", size: ubSize,
      usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST});
    dummyStorage = device.createBuffer({label: "mcweb-dummy-storage", size: 16,
      usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST});
    dummySampler = device.createSampler({magFilter: "linear", minFilter: "linear"});
    const t = device.createTexture({label: "mcweb-dummy-tex", size: [1, 1, 1],
      format: "rgba8unorm", usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_DST});
    dummyTexView = t.createView();
    const depth = device.createTexture({label: "mcweb-dummy-depth", size: [1, 1, 1],
      format: "depth32float",
      usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.RENDER_ATTACHMENT});
    dummyDepthView = depth.createView();
  };
  const dummyFor = (entry) => {
    if (entry.buffer) return {resource: {buffer: dummyUniform}};
    if (entry.sampler) return {resource: dummySampler};
    // sampled texture OR storage texture (storageTexture) → a texture view
    return {resource: dummyTexView};
  };

  // Bind groups are cached for the lifetime of the device, not the render pass.
  // The signature below names every resource a group binds by *handle*, and
  // handles come from a monotonic counter that never reuses a number, so an
  // entry can only ever describe the resources it was built from. A per-pass
  // Map looked equivalent but was not: this port issues ~84 render passes per
  // frame on a real server, so every pass started with an empty cache and
  // re-created every group it bound — measured at 9.0% of frame time on
  // hoplite.gg, 3.4% of it inside createBindGroup itself.
  //
  // Keyed by pipeline as well as by signature: two pipelines can want the same
  // resources under incompatible layouts, and getBindGroupLayout(ng) is a
  // property of the pipeline. (The per-pass `boundGroups` early-out stays as
  // it is — setPipeline already clears it.)
  const _bindGroupCache = new Map();
  const _bindGroupStats = {hit: 0, miss: 0, clears: 0};
  const _pipelineIds = new WeakMap();
  let _pipelineIdSeq = 0;
  const pipelineId = (pipeline) => {
    let id = _pipelineIds.get(pipeline);
    if (id === undefined) {
      id = ++_pipelineIdSeq;
      _pipelineIds.set(pipeline, id);
    }
    return id;
  };
  // Destroyed resources keep their (never-reissued) handles, so a stale entry
  // is unreachable rather than wrong; the only cost is retention. Cap it —
  // but well above the live working set, which is larger than it looks: a UBO
  // ring cycles 256 slot offsets and each offset is its own signature, so a
  // handful of pipelines reach several thousand entries. At 8192 the cache
  // cleared 8 times in a 24 s window and re-created what it had just evicted.
  const BIND_GROUP_CACHE_LIMIT = 32768;
  // Bisect control, matching mcweb_mesh_pace / mcweb_uncapped: `pass` restores
  // the old per-render-pass cache lifetime for an A/B against this one.
  const _bindGroupCacheScope =
    new URLSearchParams(location.search).get("mcweb_bindgroup_cache") === "pass"
      ? "pass" : "device";

  // What remains of this function's cost is building the per-draw signature
  // string, ~5% of frame time at ~525 draws/frame. An early-out keyed on "did
  // any binding actually change since the last draw" was tried and measured no
  // difference (4.67% -> 4.99%): Minecraft sets a uniform offset from its UBO
  // ring on essentially every draw, so the early-out never fires. The cost is
  // Minecraft's rebinding rate, not bookkeeping here.
  const ensureBindGroups = (state) => {
    ensureDummies();
    const spec = state.pipeline.spec;
    const groups = spec.bindGroups || [];
    const groupRemap = state.pipeline.groupRemap || groups.map((g, i) => ({ng: i, baseU: 0, baseS: 0}));
    const groupCount = state.pipeline.groupCount || groups.length;
    // Per-original-group sampler binding base (samplers laid after the group's
    // uniforms; see buildPipeline). Fallback recomputes the same offset if the
    // pipeline predates the field.
    const samplerBases = state.pipeline.samplerBases || groups.map((g, gi) => {
      let nu = 0; groups.forEach((gg, ggi) => { if (groupRemap[ggi].ng === groupRemap[gi].ng) nu += gg.uniforms.length; });
      return Math.max(groupRemap[gi].baseS, nu);
    });
    const layout = state.pipeline.pipeline;
    // Drive binding from the SPEC + remap (the authoritative set of bindings —
    // the same data that built the pipeline layout and the WGSL). We do NOT rely
    // on getBindGroupLayout(n).entries, which is empty/unavailable on some
    // Chromium builds and previously caused every group to be skipped → "No bind
    // group at group index 0" validation → Mojang's Java throws → pump dies.
    // For each merged group we emit EVERY binding the layout expects; a slot
    // whose real resource isn't bound yet gets a type-matched dummy so the group
    // is always complete and ALWAYS bound.
    // Texture binds Mojang issued this draw that did NOT match any spec sampler
    // name — used as a positional fallback (bind order → sampler-slot order) so
    // textured draws sample the real atlas even when the bind name differs from
    // the spec's sampler name (which would otherwise yield the black dummy).
    const matchedTex = new Set();
    for (const g of groups) for (const s of g.samplers) {
      const b = state.resources.get(s);
      if (b && b.kind === "texture") matchedTex.add(b);
    }
    const unmatchedTex = state.drawTexBinds.filter((t) => !matchedTex.has(t));
    let unmatchedCursor = 0;
    for (let ng = 0; ng < groupCount; ng++) {
      const entries = [];
      const signatureParts = [];
      let hasAny = false;
      groups.forEach((group, gi) => {
        const r = groupRemap[gi];
        if (r.ng !== ng) return;
        group.uniforms.forEach((uniformDesc, ui) => {
          hasAny = true;
          const binding = r.baseU + ui;
          const bound = state.resources.get(uniformDesc.name);
          if (bound && bound.kind === "uniform") {
            // WebGPU enforces the std140 rule that a uniform struct's bound
            // range be >= its shader-declared size (padded to a multiple of
            // the struct's base alignment: 16 with a mat4). Mojang's
            // Std140SizeCalculator returns AND allocates at the UN-rounded
            // size (SpriteAnimationInfo slice = 140 vs the 144 the shader
            // needs; Globals buffer = 56 vs 64). So:
            //   * a slice too small for the shader  -> "bound with size 140
            //     ... too small ... requires 144" (atlas blits dropped);
            //   * rounding the range to 16 overruns the un-rounded BUFFER
            //     (Globals 56->64 > 56) -> createBindGroup fails -> the whole
            //     submit cascades as "Invalid BindGroup ... invalid due to a
            //     previous error" and the screen goes black.
            // The one boundary that is ALWAYS safe is the per-slot packing
            // stride: Mojang lays UBO slots out at
            // roundToward(size, minUniformOffsetAlignment=256), so every slot
            // (and, for the last, the run to the buffer end) has >=256 B of
            // room, which always covers the WGSL-aligned struct (<=144) and
            // never reads past real data (std140 member offsets are < the
            // un-rounded size). Round the bound range up to 256, capped at the
            // buffer so we can never exceed it.
            const ubound = Math.min((bound.size + 255) & ~255, bound.bufferSize - bound.offset);
            entries.push({binding, resource: {buffer: bound.buffer, offset: bound.offset, size: ubound}});
            signatureParts.push(`${binding}:u:${bound.bufferHandle}:${bound.offset}:${ubound}`);
          } else {
            const dummy = uniformDesc.type === "TEXEL_BUFFER" ? dummyStorage : dummyUniform;
            entries.push({binding, resource: {buffer: dummy}});
            signatureParts.push(`${binding}:${uniformDesc.type === "TEXEL_BUFFER" ? "sD" : "uD"}`);
          }
        });
        group.samplers.forEach((samplerName, si) => {
          hasAny = true;
          const base = samplerBases[gi] + si * 2;
          let bound = state.resources.get(samplerName);
          if (!(bound && bound.kind === "texture") && unmatchedCursor < unmatchedTex.length) {
            // Name miss → fall back to the next bind-order texture that no spec
            // sampler claimed by name.
            bound = unmatchedTex[unmatchedCursor++];
            if (!buildPipeline._loggedTexFallback) {
              buildPipeline._loggedTexFallback = new Set();
            }
            const fk = `${spec.label}|${samplerName}`;
            if (!buildPipeline._loggedTexFallback.has(fk)) {
              buildPipeline._loggedTexFallback.add(fk);
              console.warn(`[tex-fallback] ${spec.label}: sampler "${samplerName}" not in resources by name; used positional bind (drawTexBinds). Bind names this draw may differ from spec sampler names.`);
            }
          }
          if (bound && bound.kind === "texture") {
            entries.push({binding: base, resource: bound.sampler});
            entries.push({binding: base + 1, resource: bound.view});
            signatureParts.push(`${base}:s:${bound.samplerHandle}`, `${base + 1}:t:${bound.viewHandle}`);
            if (bound._texEntry) { _sampledThisFrame.add(bound._texEntry); _sampledEntries.add(bound._texEntry); } // DIAG
          } else {
            entries.push({binding: base, resource: dummySampler});
            entries.push({binding: base + 1,
              resource: isDepthSamplerName(samplerName) ? dummyDepthView : dummyTexView});
            signatureParts.push(`${base}:sD`, `${base + 1}:tD`);
          }
        });
      });
      if (!hasAny) continue; // genuinely empty group (e.g. a globals-only remap target with nothing) → no layout entry → nothing to bind
      const signature = `${pipelineId(layout)}|${ng}|${signatureParts.join(",")}`;
      if (state.boundGroups[ng] === signature) continue;
      const cache = _bindGroupCacheScope === "pass"
        ? (state.bindGroupCache ||= new Map()) : _bindGroupCache;
      let bindGroup = cache.get(signature);
      if (!bindGroup) {
        _bindGroupStats.miss++;
        bindGroup = device.createBindGroup({
          label: `${spec.label}:bind${ng}`,
          layout: layout.getBindGroupLayout(ng),
          entries
        });
        if (cache.size >= BIND_GROUP_CACHE_LIMIT) {
          cache.clear();
          _bindGroupStats.clears++;
        }
        cache.set(signature, bindGroup);
      } else {
        _bindGroupStats.hit++;
      }
      state.pass.setBindGroup(ng, bindGroup);
      state.boundGroups[ng] = signature;
    }
  };

  // Largest buffer that gets a diagnostic CPU mirror; see createBuffer.
  const SHADOW_LIMIT = 8 * 1024 * 1024;

  // DIAG: terrain draws. Sections are drawn out of the shared 128 MiB
  // "UberBuffer" allocations, so a draw whose slot-0 vertex buffer is that
  // large is chunk geometry. Terrain currently compiles renderable meshes
  // (visRenderable > 0) and issues indexed draws with no WebGPU validation
  // error, yet nothing appears — so the question is whether the draw
  // parameters and buffer bindings addressing that shared buffer are sane.
  // Queryable via globalThis.mcWebGpu._terrainDraws.
  // A census keyed by pipeline label: how many draws each pipeline issued, how
  // many were dropped because no pipeline was bound, and the largest slot-0
  // vertex buffer it drew from. Chunk geometry lives in the 128 MiB
  // "UberBuffer" allocations, so a terrain pipeline should show a maxVb in that
  // range; a terrain label that never appears at all means its draws are being
  // dropped upstream, which is silent in both the Java and JS layers.
  // Bytes reaching each buffer, by label. Terrain geometry lands in the
  // "UberBuffer *" allocations; if those show zero bytes in, the sections are
  // meshed and drawn but the GPU buffer is still empty, which is silent (no
  // validation error, correct-looking draw parameters, nothing on screen).
  // Uniform-buffer content probes (?mcweb_gpu_probe=1).
  //
  // Small uniform buffers are created UNIFORM|COPY_DST with no COPY_SRC, so a
  // copyBufferToBuffer readback of them is invalid and silently yields zeros —
  // which reads exactly like "the uniform is zero". Recording what is written
  // is the only way to see the value the shader sees, and the atlas and
  // AutoStorageIndexBuffer investigations both needed it.
  //
  // It must not be on by default. Every UBO slot is <= 256 B, so the "watched"
  // branch fired on essentially every uniform upload and cost a slice plus two
  // typed-array materializations plus a rounding map — on the hot path of a
  // frame that already uploads ~500 KB.
  const _gpuProbe = new URLSearchParams(location.search).has("mcweb_gpu_probe");
  const noteUniformProbe = (entry, handle, destinationOffset, bytes, byteLength) => {
    if (!entry) return;
    if (/Auto Storage/i.test(entry._label || "")) {
      const record = (globalThis.mcWebGpu._autoIndexWrites ||= {
        writeBuffer: 0, bytes: 0, copies: 0, handles: {}
      });
      record.writeBuffer++;
      record.bytes += byteLength;
      // Keyed by handle, not label: AutoStorageIndexBuffer grows by creating a
      // NEW buffer, so "the label was written" can be true while the instance
      // the draw binds was never touched.
      const key = String(handle);
      record.handles[key] = (record.handles[key] || 0) + 1;
    }
    if (entry.size <= 256) {
      const copy = bytes.slice(0, Math.min(64, bytes.length));
      const record = {
        offset: destinationOffset,
        floats: Array.from(new Float32Array(copy.buffer, 0, copy.byteLength >> 2))
          .map((x) => Math.round(x * 10000) / 10000),
        ints: Array.from(new Int32Array(copy.buffer, 0, copy.byteLength >> 2))
      };
      const key = (entry._label || "?") + "#" + handle;
      (globalThis.mcWebGpu._smallBufferWrites ||= {})[key] = record;
      // Keep the first few writes as well as the last. "Never written
      // correctly" and "written correctly then clobbered" need opposite
      // fixes, and only the last write was being retained.
      const history = (globalThis.mcWebGpu._smallBufferWriteHistory ||= {});
      const list = (history[key] ||= []);
      if (list.length < 6) list.push(record);
      record.seq = (history[key + "#count"] = (history[key + "#count"] || 0) + 1);
    }
  };

  const _noteBytes = (entry, kind, bytes) => {
    if (!entry || !(bytes > 0)) return;
    const io = (globalThis.mcWebGpu._bufferIo ||= {});
    const row = (io[entry._label ?? "<unlabelled>"] ||= {write: 0, copy: 0, size: entry.size});
    row[kind] += bytes;
  };

  // Bounded evidence for the real vanilla cloud path. The cloud vertex shader
  // has no vertex buffer: each indexed draw expands three CloudFaces bytes
  // per quad and combines CloudInfo with the regular Projection / transform
  // UBOs. A draw count alone therefore cannot distinguish real cloud geometry
  // from an empty or mis-bound storage buffer. Capture one small, serialisable
  // sample while continuing to count every attempted/executed draw.
  const _uniformFloatSample = (state, name, count) => {
    const bound = state.resources.get(name);
    if (bound?.kind !== "uniform") return null;
    const entry = objects.get(bound.bufferHandle);
    const shadow = entry?._shadow;
    if (!shadow) return null;
    const byteLength = Math.min(bound.size, count * 4,
      Math.max(0, shadow.byteLength - bound.offset));
    if (byteLength < 4) return null;
    const view = new DataView(shadow.buffer,
      (shadow.byteOffset || 0) + bound.offset, byteLength);
    return Array.from({length: Math.floor(byteLength / 4)},
      (_, index) => view.getFloat32(index * 4, true));
  };

  const _cloudFacesSample = (state, indexCount) => {
    const bound = state.resources.get("CloudFaces");
    if (bound?.kind !== "uniform") return null;
    const entry = objects.get(bound.bufferHandle);
    const shadow = entry?._shadow;
    if (!shadow) {
      return {
        label: entry?._label ?? null,
        bufferSize: entry?.size ?? null,
        boundOffset: bound.offset,
        boundSize: bound.size,
        shadowed: false
      };
    }
    const boundBytes = Math.min(bound.size,
      Math.max(0, shadow.byteLength - bound.offset));
    // Six indices address four vertices for each quad; R8_SINT consumes exactly
    // three signed bytes. Report those logical texels, not the host's padded
    // u32 storage words, so the sample proves the shader-visible data.
    const requiredValues = Math.ceil(Math.max(0, indexCount) / 6) * 3;
    const scannedValues = Math.min(requiredValues, boundBytes);
    const view = new Uint8Array(shadow.buffer,
      (shadow.byteOffset || 0) + bound.offset, scannedValues);
    const firstValues = [];
    let nonzeroValues = 0;
    for (let index = 0; index < scannedValues; index++) {
      const byte = view[index];
      const value = byte < 128 ? byte : byte - 256;
      if (value !== 0) nonzeroValues++;
      if (index < 24) firstValues.push(value);
    }
    return {
      label: entry?._label ?? null,
      bufferSize: entry?.size ?? null,
      boundOffset: bound.offset,
      boundSize: bound.size,
      shadowed: true,
      format: "R8_SINT",
      requiredValues,
      scannedValues,
      nonzeroValues,
      firstValues
    };
  };

  const _isCloudPipelineLabel = (label) =>
    /(?:pipeline\/(?:flat_)?clouds|rendertype_clouds)/i.test(label);

  const _noteCloudDraw = (state, params, executed) => {
    const label = state.pipeline?.spec?.label ?? "";
    if (!_isCloudPipelineLabel(label)) return;
    const report = (globalThis.mcWebGpu._cloudDraws ||= {
      attemptedDraws: 0,
      executedDraws: 0,
      suppressedDraws: 0,
      totalIndices: 0,
      maxIndexCount: 0,
      firstTick: null,
      lastTick: null,
      sample: null
    });
    report.attemptedDraws++;
    if (executed) {
      report.executedDraws++;
      report.totalIndices += params.indexCount;
      report.maxIndexCount = Math.max(report.maxIndexCount, params.indexCount);
    } else {
      report.suppressedDraws++;
    }
    const tick = globalThis.mcWebPump?.ticks ?? 0;
    if (report.firstTick === null) report.firstTick = tick;
    report.lastTick = tick;
    if (executed && !report.sample) {
      report.sample = {
        pipeline: label,
        target: state._targetLabel,
        targetWH: state._targetWH,
        depthFormat: state.depthFormat,
        topology: state.pipeline?.spec?.topology ?? null,
        cull: state.pipeline?.spec?.cull ?? null,
        indexCount: params.indexCount,
        instanceCount: params.instanceCount,
        cloudFaces: _cloudFacesSample(state, params.indexCount),
        cloudInfo: _uniformFloatSample(state, "CloudInfo", 11),
        dynamicTransforms: _uniformFloatSample(state, "DynamicTransforms", 16),
        projection: _uniformFloatSample(state, "Projection", 16),
        fog: _uniformFloatSample(state, "Fog", 10)
      };
    }
  };

  const _noteDraw = (state, kind, params) => {
    const census = (globalThis.mcWebGpu._drawCensus ||= {});
    const label = state.pipeline?.spec?.label ?? "<no-pipeline>";
    const row = (census[label] ||= {draws: 0, maxVb: 0, kinds: {}, sample: null,
                                    firstTick: null, lastTick: null});
    row.draws++;
    // Frame numbers bracket the activity. A pipeline whose draws stop early
    // while the Java side still reports visible, renderable sections means the
    // geometry is being dropped between LevelRenderer and the render pass.
    const tick = globalThis.mcWebPump?.ticks ?? 0;
    if (row.firstTick === null) row.firstTick = tick;
    row.lastTick = tick;
    row.kinds[kind] = (row.kinds[kind] || 0) + 1;
    const slot0 = state.vertexBuffers[0];
    const vb = slot0 ? objects.get(slot0._handle) : null;
    if (vb && vb.size > row.maxVb) {
      row.maxVb = vb.size;
      row.vbLabel = vb._label;
    }
    // Which textures this pipeline samples, and whether they ever received
    // pixel data. Terrain that draws thousands of valid triangles but shows
    // nothing is equally consistent with an atlas that uploaded blank.
    if (!row.textures && state.resources && state.resources.size) {
      row.textures = [];
      for (const [name, rec] of state.resources) {
        if (rec?.kind !== "texture") continue;
        const te = rec._texEntry;
        row.textures.push({
          bound: name,
          handle: rec.viewHandle,
          texHandle: te?._hid ?? null,
          label: te?._label ?? null,
          wh: te ? `${te.width}x${te.height}` : null,
          uploads: te?._uploads ?? 0
        });
      }
    }
    if (!row.sample && vb && vb.size > SHADOW_LIMIT) {
      const ib = state.indexBuffer ? objects.get(state.indexBuffer._handle) : null;
      row.sample = {
        ...params,
        vbOffset: slot0.offset, vbSize: slot0.size, vbTotal: vb.size,
        ibLabel: ib?._label ?? null, ibTotal: ib?.size ?? null,
        ibFormat: state.indexBuffer?.format ?? null
      };
    }
  };

  const applyVertexAndIndex = (state) => {
    const spec = state.pipeline.spec;
    if (spec.vertexFormats) {
      spec.vertexFormats.forEach((vf, slot) => {
        const bound = state.vertexBuffers[slot];
        if (bound) {
          state.pass.setVertexBuffer(slot, bound.buffer, bound.offset, bound.size);
        }
      });
    }
    if (state.indexBuffer) {
      state.pass.setIndexBuffer(state.indexBuffer.buffer, state.indexBuffer.format);
    }
  };

  const needsLowering = (state) => {
    const topology = state.pipeline.spec.topology;
    return topology === "QUADS" || topology === "TRIANGLE_FAN";
  };

  // ---------------------------------------------------------------------------
  // On-screen frame graph (?mcweb_framegraph=1)
  // ---------------------------------------------------------------------------
  // A visual frame-time strip for live playtesting: one column per client
  // frame (height = frame ms, 16.7 ms line drawn), one row of squares for
  // server pump advances (green = tick landed, dark = pump ran without a
  // tick), and a numeric readout. Stalls read as spikes; a 2 Hz server reads
  // as a sparse green row. Opt-in so normal runs carry zero cost.
  const _frameGraph = (() => {
    const enabled = new URLSearchParams(location.search).has("mcweb_framegraph");
    if (!enabled) return {
      push() {}, pushServer() {}, reset() {},
      report() { return { error: "frame graph off; load with ?mcweb_framegraph=1" }; },
      enabled: false
    };
    const N = 240;
    // Main bars use frame-start cadence: that is what the player experiences,
    // and it exposes pauses which occur between Java callbacks (GC, browser
    // scheduling, GPU back-pressure). With VSync this is rAF-to-rAF; uncapped
    // mode uses yielding task starts. Cyan ticks retain callback execution
    // duration so a tall bar can still be attributed at a glance.
    const frames = new Float32Array(N); // rendered-frame start cadence ms
    const callbackFrames = new Float32Array(N); // Java callback duration ms
    const serverDeltas = new Uint8Array(N); // ticks advanced in this frame window
    // Server-side pump timing from the Worker (pump-stats messages): per
    // pump-call duration and the gap between pump starts. Big durations =
    // slow server ticks; big gaps with small durations = a starved Worker
    // event loop. Both read as the user-visible gameplay delay.
    const SN = 240;
    const serverDur = new Float32Array(SN);
    const serverGap = new Float32Array(SN);
    let serverHead = 0;
    let lastServerDur = 0;
    let worstServerGap = 0;
    let head = 0;
    let pendingTicks = 0;
    let lastTickCount = -1;
    let canvasEl = null;
    let labelEl = null;
    let lastDraw = 0;
    let lastFrameMs = 0;
    let lastRafGapMs = 0;
    let visibleOver33 = 0;
    let visibleOver100 = 0;
    const MAX_MS = 250; // vertical scale cap
    // The visible strip is deliberately short, but QA needs a long enough raw
    // history to catch intermittent stutters without enabling ?mcweb_perf=1.
    // That profiler wraps every host method and materially lowers FPS, whereas
    // these two opt-in typed-array writes do not change the Minecraft/host seam.
    // Retain a complete 30-second acceptance window even at Minecraft's
    // 260/unlimited setting (7,800 frames), with room for scheduling jitter.
    const HN = 12000;
    const frameHistory = new Float32Array(HN);
    const rafGapHistory = new Float32Array(HN);
    let historyHead = 0;
    let historyCount = 0;
    // When each hitch happened, not just how many there were. A stutter with a
    // regular period is a periodic *task*, and its period names the culprit —
    // a 2.0 s spacing led straight to the storage sync. Counts alone cannot
    // distinguish that from scattered jitter.
    const HITCH_MS = 33;
    const hitchTimes = [];

    /**
     * Spacing between consecutive hitches. A tight median with a small spread
     * means something periodic is running on the frame thread; `periodicity` is
     * the fraction of gaps within 25% of the median, so a caller can tell "one
     * task every 2 s" from "jitter that averages 2 s".
     */
    const hitchSpacing = () => {
      const gaps = [];
      for (let i = 1; i < hitchTimes.length; i++) gaps.push(hitchTimes[i].t - hitchTimes[i - 1].t);
      if (!gaps.length) {
        return {count: hitchTimes.length, gaps: [], medianGapMs: 0, periodicity: 0,
          events: hitchTimes.slice(0, 40)};
      }
      const sorted = [...gaps].sort((a, b) => a - b);
      const median = sorted[sorted.length >> 1];
      const near = gaps.filter((g) => Math.abs(g - median) <= median * 0.25).length;
      return {
        count: hitchTimes.length,
        medianGapMs: median,
        periodicity: Math.round(near / gaps.length * 100) / 100,
        gaps: gaps.slice(0, 40),
        events: hitchTimes.slice(0, 40)
      };
    };

    const historyValues = (source, positiveOnly = false) => {
      const out = [];
      const start = (historyHead - historyCount + HN) % HN;
      for (let i = 0; i < historyCount; i++) {
        const value = source[(start + i) % HN];
        if (!positiveOnly || value > 0) out.push(value);
      }
      return out;
    };

    const summary = (values) => {
      if (!values.length) return { mean: 0, p50: 0, p95: 0, p99: 0, max: 0 };
      const sorted = [...values].sort((a, b) => a - b);
      const at = (p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
      const round = (value) => Math.round(value * 100) / 100;
      return {
        mean: round(values.reduce((a, b) => a + b, 0) / values.length),
        p50: round(at(0.50)),
        p95: round(at(0.95)),
        p99: round(at(0.99)),
        max: round(sorted[sorted.length - 1])
      };
    };

    const ensureDom = () => {
      if (canvasEl) return;
      canvasEl = document.createElement("canvas");
      canvasEl.width = N * 2;
      canvasEl.height = 132;
      canvasEl.style.cssText = "position:fixed;left:8px;top:8px;z-index:9999;"
        + "background:rgba(0,0,0,0.55);border:1px solid #555;pointer-events:none;";
      document.body.appendChild(canvasEl);
      labelEl = document.createElement("div");
      labelEl.style.cssText = "position:fixed;left:8px;top:144px;z-index:9999;"
        + "color:#9f9;font:11px monospace;pointer-events:none;white-space:pre;";
      document.body.appendChild(labelEl);
    };

    const draw = (nowMs) => {
      if (nowMs - lastDraw < 100) return; // 10 Hz redraw keeps cost negligible
      lastDraw = nowMs;
      ensureDom();
      const ctx = canvasEl.getContext("2d");
      const W = canvasEl.width, H = canvasEl.height;
      const graphH = 88, serverY = 94, durY = 106, gapY = 120;
      ctx.clearRect(0, 0, W, H);
      // 16.7 ms reference line (60 FPS).
      const yOf = (ms) => graphH - Math.min(ms, MAX_MS) / MAX_MS * graphH;
      ctx.strokeStyle = "#3a6";
      ctx.beginPath();
      const ref = yOf(16.7);
      ctx.moveTo(0, ref); ctx.lineTo(W, ref); ctx.stroke();
      // Frame bars.
      for (let i = 0; i < N; i++) {
        const idx = (head + i) % N;
        const ms = frames[idx];
        if (ms <= 0) continue;
        const x = i * 2;
        ctx.fillStyle = ms > 100 ? "#e44" : ms > 33 ? "#ea3" : "#5c8";
        ctx.fillRect(x, yOf(ms), 2, graphH - yOf(ms));
        const callbackMs = callbackFrames[idx];
        if (callbackMs > 0) {
          ctx.fillStyle = "#5cf";
          ctx.fillRect(x, yOf(callbackMs), 2, 1);
        }
        // Server tick advance in this frame window: green square below.
        ctx.fillStyle = serverDeltas[idx] > 0 ? "#3f6" : "#222";
        ctx.fillRect(x, serverY, 2, 8);
      }
      // Server pump durations (bars rising from durY) and gaps between pump
      // starts (bars rising from gapY): the attribution for delayed gameplay.
      for (let i = 0; i < SN; i++) {
        const idx = (serverHead + i) % SN;
        const x = i * 2;
        const d = serverDur[idx];
        if (d > 0) {
          ctx.fillStyle = d > 100 ? "#e44" : d > 33 ? "#ea3" : "#69d";
          const h = Math.max(1, 10 * Math.min(d, MAX_MS) / MAX_MS);
          ctx.fillRect(x, durY + 10 - h, 2, h);
        }
        const g = serverGap[idx];
        if (g > 0) {
          ctx.fillStyle = g > 100 ? "#e44" : g > 60 ? "#ea3" : "#244";
          const h = Math.max(1, 8 * Math.min(g, MAX_MS) / MAX_MS);
          ctx.fillRect(x, gapY + 8 - h, 2, h);
        }
      }
    };

    const refreshLabel = () => {
      const server = globalThis.mcWebServer;
      const info = server?.info?.() ?? {};
      const fpsNow = lastRafGapMs > 0
        ? Math.min(999, Math.round(1000 / lastRafGapMs)) : 0;
      labelEl.textContent =
        `frame ${globalThis.mcWebPump?.pacing?.().scheduler ?? "?"}`
        + ` ${Math.round(lastRafGapMs * 10) / 10}ms (~${fpsNow}fps)`
        + ` callback ${Math.round(lastFrameMs * 10) / 10}ms`
        + `  recent >33=${visibleOver33} >100=${visibleOver100}`
        + `  serverTick=${info.tickCount ?? "?"} inQ=${info.inboundQueued ?? "?"}`
        + `  serverPump ${lastServerDur}ms gap ${worstServerGap}ms`
        + `  state=${info.state ?? "?"}`;
    };

    return {
      enabled: true,
      push(frameMs, rafGapMs = 0) {
        // Captured before lastFrameMs is reassigned below: the gap being
        // recorded ended with THIS callback, so the callback that ran before it
        // is the one that could have overrun into it.
        const previousCallbackMs = lastFrameMs;
        const cadenceMs = rafGapMs > 0 ? rafGapMs : frameMs;
        if (frames[head] > 33) visibleOver33--;
        if (frames[head] > 100) visibleOver100--;
        frames[head] = cadenceMs;
        callbackFrames[head] = frameMs;
        if (cadenceMs > 33) visibleOver33++;
        if (cadenceMs > 100) visibleOver100++;
        lastFrameMs = frameMs;
        if (rafGapMs > 0) lastRafGapMs = rafGapMs;
        frameHistory[historyHead] = frameMs;
        rafGapHistory[historyHead] = rafGapMs;
        if (rafGapMs > HITCH_MS && hitchTimes.length < 4096) {
          // Record the callback cost alongside the gap. The two together say
          // where the hitch lives without any profiling: a gap that matches its
          // callback is Java work inside the frame, while a gap much larger
          // than its callback is time spent *between* callbacks — GC, browser
          // scheduling, or GPU back-pressure — and those have different fixes.
          // `frameMs` is the callback that ran at the END of this gap; the one
          // that preceded it is the more likely culprit, so keep both.
          hitchTimes.push({
            t: Math.round(performance.now()),
            gapMs: Math.round(rafGapMs * 10) / 10,
            callbackMs: Math.round(frameMs * 10) / 10,
            prevCallbackMs: Math.round(previousCallbackMs * 10) / 10
          });
        }
        historyHead = (historyHead + 1) % HN;
        historyCount = Math.min(HN, historyCount + 1);
        // Sample the server pump counter once per client frame: the delta is
        // how many server ticks advanced inside this frame window.
        const server = globalThis.mcWebServer;
        const count = server?.info?.()?.tickCount;
        if (typeof count === "number") {
          if (lastTickCount >= 0) pendingTicks += Math.max(0, count - lastTickCount);
          lastTickCount = count;
        }
        serverDeltas[head] = pendingTicks;
        pendingTicks = 0;
        head = (head + 1) % N;
        const now = performance.now();
        draw(now);
        refreshLabel();
      },
      pushServer(samples) {
        // Each sample is [durationMs, gapMs] for one pump call; the gap is
        // the time since the previous pump start on the Worker event loop.
        for (const pair of samples) {
          serverDur[serverHead] = Number(pair[0]) || 0;
          serverGap[serverHead] = Number(pair[1]) || 0;
          if (serverDur[serverHead] > 0) lastServerDur = serverDur[serverHead];
          if (serverGap[serverHead] > worstServerGap) worstServerGap = serverGap[serverHead];
          serverHead = (serverHead + 1) % SN;
        }
        worstServerGap *= 0.9; // decay so stale spikes fade from the label
      },
      reset() {
        historyHead = 0;
        historyCount = 0;
        frames.fill(0);
        callbackFrames.fill(0);
        serverDeltas.fill(0);
        head = 0;
        pendingTicks = 0;
        visibleOver33 = 0;
        visibleOver100 = 0;
        serverDur.fill(0);
        serverGap.fill(0);
        serverHead = 0;
        lastServerDur = 0;
        worstServerGap = 0;
        hitchTimes.length = 0;
      },
      report() {
        const callbackMs = historyValues(frameHistory);
        const rafGapMs = historyValues(rafGapHistory, true);
        const gaps = summary(rafGapMs);
        const nonzeroServerDur = [...serverDur].filter((x) => x > 0);
        const nonzeroServerGap = [...serverGap].filter((x) => x > 0);
        return {
          enabled: true,
          pacing: globalThis.mcWebPump?.pacing?.() ?? null,
          samples: callbackMs.length,
          fps: gaps.mean > 0 ? Math.round(100000 / gaps.mean) / 100 : 0,
          callbackMs: summary(callbackMs),
          rafGapMs: {
            ...gaps,
            over33: rafGapMs.filter((x) => x > 33).length,
            over50: rafGapMs.filter((x) => x > 50).length,
            over100: rafGapMs.filter((x) => x > 100).length,
            over250: rafGapMs.filter((x) => x > 250).length
          },
          serverPumpDurationMs: summary(nonzeroServerDur),
          serverPumpGapMs: summary(nonzeroServerGap),
          hitches: hitchSpacing()
        };
      },
    };
  })();

  // ---------------------------------------------------------------------------
  // Frame pump and host API
  // ---------------------------------------------------------------------------

  globalThis.mcWebPump = {
    callback: null,
    running: false,
    ticks: 0,        // JS-side: how many times the frame callback was entered
    lastMs: 0,       // duration (ms) of the most recent callback
    slow: 0,         // count of callbacks that took > 1500ms (a hang signature)
    vsync: !new URLSearchParams(location.search).has("mcweb_uncapped"),
    targetFps: Math.max(1, Math.min(260,
      Number(new URLSearchParams(location.search).get("mcweb_max_fps")) || 260)),
    _nextDueMs: 0,
    _postTaskUnavailable: false,
    _skippedPeriods: 0,
    _firstFrameFallbacks: 0,
    _wakeFirst: null,
    configure(vsync, frameLimit) {
      const params = new URLSearchParams(location.search);
      const forcedUncapped = params.has("mcweb_uncapped");
      const forcedLimit = Number(params.get("mcweb_max_fps"));
      const nextVsync = forcedUncapped ? false : Boolean(vsync);
      const nextLimit = Math.max(1, Math.min(260,
        Number.isFinite(forcedLimit) && forcedLimit > 0 ? forcedLimit : Number(frameLimit) || 260));
      if (this.vsync === nextVsync && this.targetFps === nextLimit) return;
      this.vsync = nextVsync;
      this.targetFps = nextLimit;
      this._nextDueMs = 0;
      this._skippedPeriods = 0;
    },
    _schedulerName() {
      if (this.vsync) return "requestAnimationFrame";
      return !this._postTaskUnavailable
          && typeof globalThis.scheduler?.postTask === "function"
        ? "scheduler.postTask"
        : "timer";
    },
    pacing() {
      return {
        scheduler: this._schedulerName(),
        vsync: this.vsync,
        targetFps: this.targetFps,
        skippedPeriods: this._skippedPeriods,
        firstFrameFallbacks: this._firstFrameFallbacks
      };
    },
    _schedule(loop) {
      if (this.vsync) {
        requestAnimationFrame(loop);
        return;
      }

      // Minecraft's desktop limiter treats 260 as "unlimited". Carrying that
      // literally into a MessageChannel pump let a cheap sky frame repost as
      // fast as the browser could drain tasks (999+ FPS), starving input and
      // doing render work the display could never present. In the browser 260
      // is a real safety ceiling. scheduler.postTask retains the sub-4 ms
      // capacity that nested setTimeout cannot provide, while its delayed,
      // user-visible task yields to input and schedules exactly one frame.
      const now = performance.now();
      const interval = 1000 / this.targetFps;
      if (!(this._nextDueMs > 0)) {
        this._nextDueMs = now;
      }
      const due = this._nextDueMs;
      const delay = Math.max(0, due - now);
      const fire = () => {
        const fired = performance.now();
        let nextDue = due + interval;
        // A hidden tab, GC pause, or long Java frame must not leave hundreds
        // of expired callbacks to catch up. Advance to the first future slot
        // and run Minecraft once; later frames resume from that deadline.
        if (fired >= nextDue) {
          const skipped = Math.floor((fired - nextDue) / interval) + 1;
          this._skippedPeriods += skipped;
          nextDue += skipped * interval;
        }
        this._nextDueMs = nextDue;
        loop(fired);
      };

      if (!this._postTaskUnavailable
          && typeof globalThis.scheduler?.postTask === "function") {
        try {
          globalThis.scheduler.postTask(fire, {priority: "user-visible", delay});
          return;
        } catch (error) {
          this._postTaskUnavailable = true;
          console.warn("scheduler.postTask unavailable; using timer pacing:", error);
        }
      }
      setTimeout(fire, delay);
    },
    register(callback) {
      this.callback = callback;
      this._lastRafTimestamp = null;
      if (this.running) return;
      this.running = true;
      const loop = (frameTimestamp) => {
        const cb = this.callback;
        if (cb) {
          if (!firstRafRecorded) {
            firstRafRecorded = true;
            markPhase("first-raf-entered");
          }
          // Apply a pending window resize here rather than in the DOM event, so
          // Minecraft rebuilds its render targets between frames instead of
          // underneath one that is already recording commands.
          globalThis.mcWebCanvas.applyPendingResize();
          this.ticks++;
          if (_DBG && (this.ticks <= 3 || this.ticks % 60 === 0)) console.log("[pump] enter #" + this.ticks);
          const t0 = (typeof performance !== "undefined" ? performance.now() : Date.now());
          const previousRaf = this._lastRafTimestamp;
          this._lastRafTimestamp = typeof frameTimestamp === "number" ? frameTimestamp : t0;
          if (_perf.on) {
            _perf.rafGaps.push(previousRaf == null ? 0 : this._lastRafTimestamp - previousRaf);
          }
          try {
            if (typeof cb.run === "function") cb.run();
            else cb();
          } catch (error) {
            // Same reasoning as the input bridge: "Exception" on its own names
            // nothing, and this catch is the last place the object exists.
            (globalThis.mcWebPumpErrors ||= []).push({
              tick: this.ticks,
              text: String(error),
              ctor: error?.constructor?.name ?? null,
              message: error?.message ?? null,
              props: (() => { try { return Object.getOwnPropertyNames(error).slice(0, 20); } catch { return null; } })(),
              stack: String(error?.stack || "").split("\n").slice(0, 14)
            });
            console.error("frame pump error:", error);
          }
          const ms = (typeof performance !== "undefined" ? performance.now() : Date.now()) - t0;
          this.lastMs = Math.round(ms);
          _frameGraph.push(ms,
            previousRaf == null ? 0 : this._lastRafTimestamp - previousRaf);
          if (_perf.on) {
            _perf.frames.push(ms);
            _perf.hostFrames.push(_perf.hostMsThisFrame);
            _perf.callFrames.push(_perf.callsThisFrame);
            _perf.uploadBytesFrames.push(_perf.bytesThisFrame);
            if (ms >= 17 && _uploadAttribution) {
              // Attribution for stutter frames: which buffers ate the frame,
              // with call counts so many-small vs few-big bursts can be told
              // apart (over-drain vs inherent upload cost).
              _perf.slowFrameUploads.push({
                frame: _perf.frames.length - 1,
                ms,
                bytes: _perf.bytesThisFrame,
                byLabel: Object.fromEntries(_perf.bytesByLabelThisFrame),
                callsByLabel: Object.fromEntries(_perf.callsByLabelThisFrame),
              });
            }
            _perf.hostMsThisFrame = 0;
            _perf.callsThisFrame = 0;
            _perf.bytesThisFrame = 0;
            _perf.bytesByLabelThisFrame.clear();
            _perf.callsByLabelThisFrame.clear();
          }
          if (ms > 1500) { this.slow++; console.warn("[pump] slow tick #" + this.ticks + " = " + this.lastMs + "ms (runTick hang?)"); }
        }
        this._schedule(loop);
      };
      // Chromium can lose the very first rAF request when Web Image finishes a
      // long synchronous boot inside the launch-button task. A visibility
      // transition makes rAF wake again, which is why changing tabs appeared to
      // "start" Minecraft. Race that first scheduled callback with one bounded
      // visible-tab timer. The once gate guarantees exactly one pump chain; all
      // later frames continue through the selected Minecraft pacing mode.
      let firstPending = true;
      const first = (timestamp) => {
        if (!firstPending) return;
        firstPending = false;
        this._wakeFirst = null;
        loop(timestamp);
      };
      // A launch button can finish a long synchronous Web Image constructor
      // while Safari/Chromium is transitioning the page between task queues.
      // pageshow, visibilitychange, and focus are explicit wake signals; use
      // the same once-gated first callback so none of them can create a second
      // render loop.
      this._wakeFirst = () => {
        if (!firstPending || document.visibilityState === "hidden") return false;
        this._firstFrameFallbacks++;
        first(performance.now());
        return true;
      };
      const rescueFirst = () => {
        if (!firstPending) return;
        if (document.visibilityState === "hidden") {
          const onVisible = () => {
            if (document.visibilityState !== "visible") return;
            removeEventListener("visibilitychange", onVisible);
            rescueFirst();
          };
          addEventListener("visibilitychange", onVisible);
          return;
        }
        this._wakeFirst?.();
      };
      this._schedule(first);
      setTimeout(rescueFirst, 100);
    },
    frameReported(count) {
      document.body.dataset.frames = String(count);
      // Reveal the canvas once frames start flowing so screenshots show the
      // actual game render, not the diagnostic overlay. Any positive count
      // qualifies (the first report may be 0 or 1 depending on ordering).
      if (count >= 1) {
        if (!firstFrameReported) {
          firstFrameReported = true;
          markPhase("first-frame-reported");
          markPhase("boot-healthy", "first-frame-reported");
        }
        framesFlowing = true;
        const main = document.querySelector("main");
        if (main) main.style.display = "none";
        failure.hidden = true;
        // Game is rendering: drop the boot overlay (a couple of frames in, so
        // the title screen's first paint isn't raced by the hide).
        if (count >= 2 && globalThis.mcWebBootOverlay && !globalThis.mcWebBootOverlay.hidden) {
          globalThis.mcWebBootOverlay.hide();
          // Self-report the texture-identity diag a moment after the title
          // screen starts sampling, so the console carries the evidence
          // without a manual _texDiagLate() call.
          setTimeout(() => { try { globalThis.mcWebGpu._texDiagLate && globalThis.mcWebGpu._texDiagLate(); } catch {} }, 2500);
        }
      }
    }
  };

  const wakeInitialFrame = () => {
    if (!globalThis.mcWebPump?.running) return;
    globalThis.mcWebPump._wakeFirst?.();
  };
  addEventListener("pageshow", wakeInitialFrame, { passive: true });
  addEventListener("focus", wakeInitialFrame, { passive: true });
  document.addEventListener("visibilitychange", wakeInitialFrame, { passive: true });

  // ---------------------------------------------------------------------------
  // DOM input -> GLFW callback bridge. The Java side registers a
  // BrowserInputBridge once the frame pump starts; DOM listeners below map
  // browser events onto GLFW keycodes/buttons/mods and forward them.
  // ---------------------------------------------------------------------------

  const GLFW = {
    RELEASE: 0, PRESS: 1, REPEAT: 2,
    MOUSE_LEFT: 0, MOUSE_RIGHT: 1, MOUSE_MIDDLE: 2,
    MOD_SHIFT: 1, MOD_CONTROL: 2, MOD_ALT: 4, MOD_SUPER: 8
  };

  const KEY_MAP = {
    Space: 32, Apostrophe: 39, Comma: 44, Minus: 45, Period: 46, Slash: 47,
    Digit0: 48, Digit1: 49, Digit2: 50, Digit3: 51, Digit4: 52,
    Digit5: 53, Digit6: 54, Digit7: 55, Digit8: 56, Digit9: 57,
    Semicolon: 59, Equal: 61,
    KeyA: 65, KeyB: 66, KeyC: 67, KeyD: 68, KeyE: 69, KeyF: 70, KeyG: 71,
    KeyH: 72, KeyI: 73, KeyJ: 74, KeyK: 75, KeyL: 76, KeyM: 77, KeyN: 78,
    KeyO: 79, KeyP: 80, KeyQ: 81, KeyR: 82, KeyS: 83, KeyT: 84, KeyU: 85,
    KeyV: 86, KeyW: 87, KeyX: 88, KeyY: 89, KeyZ: 90,
    BracketLeft: 91, Backslash: 92, BracketRight: 93, GraveAccent: 96,
    Escape: 256, Enter: 257, Tab: 258, Backspace: 259, Insert: 260,
    Delete: 261, ArrowRight: 262, ArrowLeft: 263, ArrowDown: 264,
    ArrowUp: 265, PageUp: 266, PageDown: 267, Home: 268, End: 269,
    CapsLock: 280, ScrollLock: 281, NumLock: 282, PrintScreen: 283, Pause: 284,
    F1: 290, F2: 291, F3: 292, F4: 293, F5: 294, F6: 295, F7: 296, F8: 297,
    F9: 298, F10: 299, F11: 300, F12: 301,
    Numpad0: 320, Numpad1: 321, Numpad2: 322, Numpad3: 323, Numpad4: 324,
    Numpad5: 325, Numpad6: 326, Numpad7: 327, Numpad8: 328, Numpad9: 329,
    NumpadDecimal: 330, NumpadDivide: 331, NumpadMultiply: 332,
    NumpadSubtract: 333, NumpadAdd: 334, NumpadEnter: 335, NumpadEqual: 336,
    ShiftLeft: 340, ControlLeft: 341, AltLeft: 342, MetaLeft: 343,
    ShiftRight: 344, ControlRight: 345, AltRight: 346, MetaRight: 347,
    ContextMenu: 348
  };

  const glfwMods = (event) => {
    let mods = 0;
    if (event.shiftKey) mods |= GLFW.MOD_SHIFT;
    if (event.ctrlKey) mods |= GLFW.MOD_CONTROL;
    if (event.altKey) mods |= GLFW.MOD_ALT;
    if (event.metaKey) mods |= GLFW.MOD_SUPER;
    return mods;
  };

  const MOUSE_BUTTON_MAP = {0: GLFW.MOUSE_LEFT, 1: GLFW.MOUSE_MIDDLE, 2: GLFW.MOUSE_RIGHT};

  // Keys that would scroll/interact with the page while the game has focus.
  const PREVENT_DEFAULT_CODES = new Set([
    "Space", "Tab", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight",
    "Backspace", "Enter", "Slash", "F3", "F5"
  ]);

  globalThis.mcWebInput = {
    bridge: null,
    lockPointerOnNextClick: false,
    syntheticX: 0,
    syntheticY: 0,
    // Chromium was already correct with the original canvas listener. Keep
    // WebKit's document-targeted Pointer Lock workaround scoped to Safari so
    // it cannot change Chromium's input routing or feel.
    documentPointerMoves: navigator.vendor === "Apple Computer, Inc.",
    diagnostics: {
      calls: Object.create(null),
      lastCall: null
    },
    register(bridge) {
      this.bridge = bridge;
      recordStage("input-bridge-registered");
    },
    call(name, ...args) {
      const bridge = this.bridge;
      if (!bridge) return;
      try {
        this.diagnostics.calls[name] = (this.diagnostics.calls[name] || 0) + 1;
        this.diagnostics.lastCall = {
          name,
          args: args.slice(),
          frame: Number(document.body?.dataset?.frames || 0)
        };
        if (typeof bridge === "function") {
          bridge(name, args[0] ?? 0, args[1] ?? 0, args[2] ?? 0, args[3] ?? 0);
        } else if (typeof bridge[name] === "function") {
          // Compatibility with any host-object interop that does expose Java
          // instance methods directly.
          bridge[name](...args);
        }
      } catch (error) {
        // A Web Image exception stringifies to a bare "Exception", which names
        // nothing. Keep the readable parts so a lost click can be attributed.
        (this.diagnostics.errors ||= []).push({
          name,
          args: args.slice(),
          text: String(error),
          ctor: error?.constructor?.name ?? null,
          message: error?.message ?? null,
          props: (() => { try { return Object.getOwnPropertyNames(error).slice(0, 20); } catch { return null; } })(),
          stack: String(error?.stack || "").split("\n").slice(0, 12)
        });
        console.error("input bridge error:", name, error);
      }
    }
  };

  const inputMapping = globalThis.mcWebInputMapping;
  if (!inputMapping) throw new Error("MC-Web input mapping module is missing");
  const canvasToPixel = (clientX, clientY) => inputMapping.point(
    clientX,
    clientY,
    canvas.getBoundingClientRect(),
    canvas.width,
    canvas.height,
  );
  const pointerLockToPixelDelta = (movementX, movementY) => inputMapping.lockedDelta(
    movementX,
    movementY,
    canvas.getBoundingClientRect(),
    canvas.width,
    canvas.height,
  );

  // Escape while the pointer is locked belongs to the browser: it exits the lock
  // and does not dispatch the keydown to the page. Minecraft therefore never saw
  // the press -- the cursor came free but no menu opened, and only a *second*
  // Escape (delivered normally now that the page has keys again) reached the
  // game. Desktop Minecraft does both on one press, so the lost key is
  // synthesized on pointerlockchange below. This timestamp covers the browsers
  // that do deliver it, so the key is never counted twice. It starts at
  // -Infinity rather than 0 because performance.now() itself starts near zero,
  // and a 0 sentinel would read as "just delivered" for the first half-second.
  let escapeSeenWhileLocked = -Infinity;

  addEventListener("keydown", (event) => {
    if (!globalThis.mcWebInput.bridge) return;
    if (PREVENT_DEFAULT_CODES.has(event.code)) event.preventDefault();
    const key = KEY_MAP[event.code] ?? -1;
    if (key < 0) return;
    if (event.code === "Escape" && document.pointerLockElement === canvas) {
      escapeSeenWhileLocked = performance.now();
    }
    const action = event.repeat ? GLFW.REPEAT : GLFW.PRESS;
    globalThis.mcWebInput.call("key", key, 0, action, glfwMods(event));
    // Character input for text fields (printable single-codepoint keys).
    if (action !== GLFW.RELEASE && event.key && event.key.length === 1 && !event.ctrlKey && !event.metaKey) {
      globalThis.mcWebInput.call("charInput", event.key.codePointAt(0));
    }
  });

  addEventListener("keyup", (event) => {
    if (!globalThis.mcWebInput.bridge) return;
    const key = KEY_MAP[event.code] ?? -1;
    if (key < 0) return;
    globalThis.mcWebInput.call("key", key, 0, GLFW.RELEASE, glfwMods(event));
  });

  const handleMouseMove = (event) => {
    if (!globalThis.mcWebInput.bridge) return;
    if (document.pointerLockElement === canvas) {
      // Pointer is locked: synthesize absolute coordinates from movement so
      // Minecraft's delta-based camera handling still works.
      //
      // These coordinates must stay UNCLAMPED. Under GLFW_CURSOR_DISABLED the
      // cursor position GLFW reports is virtual and unbounded, and
      // MouseHandler.onMove only ever consumes it as a delta
      // (accumulatedDX += xpos - lastXpos). Clamping to the canvas therefore
      // capped the total turn at one canvas width: once the virtual cursor
      // pinned to an edge every further mousemove produced a zero delta and the
      // camera stopped, which is the "can only turn the width of the window"
      // symptom. Doubles hold integer pixel sums exactly to 2^53, so unbounded
      // accumulation cannot lose precision within a session.
      const input = globalThis.mcWebInput;
      // WebKit may retarget locked movement to Document when no mouse button
      // is held, while dragging retains the canvas as its capture target. A
      // listener attached only to the canvas therefore made free-look appear
      // much slower than button-held movement in Safari. Chromium stays on
      // the original canvas listener below. Older WebKit builds exposed only
      // the prefixed relative deltas.
      const movementX = Number(event.movementX || event.webkitMovementX || 0);
      const movementY = Number(event.movementY || event.webkitMovementY || 0);
      const [deltaX, deltaY] = pointerLockToPixelDelta(movementX, movementY);
      input.syntheticX += deltaX;
      input.syntheticY += deltaY;
      globalThis.mcWebInput.call("cursorPos", input.syntheticX, input.syntheticY);
      return;
    }
    // Safari's document listener also observes every ordinary page mousemove.
    // Only canvas-targeted movement belongs to Minecraft while the cursor is
    // free.
    if (event.target !== canvas) return;
    const [x, y] = canvasToPixel(event.clientX, event.clientY);
    if (globalThis.mcWebInput.lockPointerOnNextClick) {
      // Embedded browsers may deny pointer lock. Keep the captured-coordinate
      // fallback aligned with the last real mouse position so a click does not
      // inject a camera delta before Minecraft receives the button event.
      globalThis.mcWebInput.syntheticX = x;
      globalThis.mcWebInput.syntheticY = y;
    }
    globalThis.mcWebInput.call("cursorPos", x, y);
  };
  const pointerMoveTarget = globalThis.mcWebInput.documentPointerMoves
    ? document : canvas;
  pointerMoveTarget.addEventListener("mousemove", handleMouseMove,
    globalThis.mcWebInput.documentPointerMoves ? {capture: true} : undefined);

  canvas.addEventListener("mousedown", (event) => {
    if (!globalThis.mcWebInput.bridge) return;
    event.preventDefault();
    canvas.focus?.();
    // GLFW reports the current cursor position before a mouse-button callback.
    // A synthetic/automated click is not required to emit mousemove first, so
    // refresh it from this event as well or Minecraft can hit-test using the
    // stale startup position (0, 0).
    const input = globalThis.mcWebInput;
    const cursorIsCaptured =
      document.pointerLockElement === canvas || input.lockPointerOnNextClick;
    const [x, y] = cursorIsCaptured
      ? [input.syntheticX, input.syntheticY]
      : canvasToPixel(event.clientX, event.clientY);
    globalThis.mcWebInput.call("cursorPos", x, y);
    if (document.pointerLockElement !== canvas && input.lockPointerOnNextClick
        && canvas.requestPointerLock) {
      input.syntheticX = x;
      input.syntheticY = y;
      const request = canvas.requestPointerLock();
      if (request && typeof request.catch === "function") {
        // A denied lock is not cosmetic: the fallback path feeds Minecraft the
        // real canvas-bounded cursor, so the camera stops turning at the window
        // edge. Record the reason instead of swallowing it -- that failure and a
        // clamped virtual cursor produce the same "can only turn so far" report,
        // and only this tells them apart.
        request.catch((error) => {
          input.diagnostics.pointerLockError = {
            text: String(error),
            name: error?.name ?? null,
            at: Date.now()
          };
          console.warn("pointer lock denied:", error);
        });
      }
    }
    const button = MOUSE_BUTTON_MAP[event.button] ?? event.button;
    globalThis.mcWebInput.call("mouseButton", button, GLFW.PRESS, glfwMods(event));
  });

  // Safari does not provide a desktop mouse stream for a sustained finger
  // drag. Keep the game surface usable while the document stays portrait:
  // touch points map to the same landscape canvas/backing-pixel contract as
  // mouse points. Preventing the default touch action also avoids a synthetic
  // mouse click being delivered a second time after pointerup.
  const touchPointers = new Set();
  const handleTouchPointer = (event) => {
    if (event.pointerType !== "touch" || !globalThis.mcWebInput.bridge) return;
    event.preventDefault();
    const input = globalThis.mcWebInput;
    const [x, y] = canvasToPixel(event.clientX, event.clientY);
    if (event.type === "pointerdown") {
      touchPointers.add(event.pointerId);
      input.call("cursorPos", x, y);
      input.call("mouseButton", GLFW.MOUSE_LEFT, GLFW.PRESS, glfwMods(event));
      canvas.setPointerCapture?.(event.pointerId);
    } else if (event.type === "pointermove" && touchPointers.has(event.pointerId)) {
      input.call("cursorPos", x, y);
    } else if ((event.type === "pointerup" || event.type === "pointercancel")
        && touchPointers.delete(event.pointerId)) {
      input.call("cursorPos", x, y);
      input.call("mouseButton", GLFW.MOUSE_LEFT, GLFW.RELEASE, glfwMods(event));
      canvas.releasePointerCapture?.(event.pointerId);
    }
  };
  canvas.addEventListener("pointerdown", handleTouchPointer, { passive: false });
  canvas.addEventListener("pointermove", handleTouchPointer, { passive: false });
  canvas.addEventListener("pointerup", handleTouchPointer, { passive: false });
  canvas.addEventListener("pointercancel", handleTouchPointer, { passive: false });

  addEventListener("mouseup", (event) => {
    if (!globalThis.mcWebInput.bridge) return;
    const button = MOUSE_BUTTON_MAP[event.button] ?? event.button;
    globalThis.mcWebInput.call("mouseButton", button, GLFW.RELEASE, glfwMods(event));
  });

  canvas.addEventListener("wheel", (event) => {
    if (!globalThis.mcWebInput.bridge) return;
    event.preventDefault();
    // GLFW scroll is reported in line units; pixel-mode wheels typically
    // produce multiples of ~100 per line.
    const scale = event.deltaMode === 1 ? 1 : 1 / 100;
    globalThis.mcWebInput.call("scroll", -event.deltaX * scale, -event.deltaY * scale);
  }, {passive: false});

  canvas.addEventListener("contextmenu", (event) => event.preventDefault());

  // The other half of the single-Escape fix: deliver the key the browser ate.
  //
  // Minecraft releases the cursor itself whenever it opens a screen, and that
  // path (BrowserInputCompat.setCursorDisabled(0)) clears lockPointerOnNextClick
  // *before* calling exitPointerLock. So a lock that drops while the flag is
  // still set was dropped by the browser -- Escape, or focus loss -- while the
  // game still wanted the cursor grabbed. That is exactly when Minecraft should
  // pause, and pausing is what it does with an Escape press.
  document.addEventListener("pointerlockchange", () => {
    const input = globalThis.mcWebInput;
    if (!input.bridge) return;
    if (document.pointerLockElement === canvas) return;
    if (!input.lockPointerOnNextClick) return;
    if (performance.now() - escapeSeenWhileLocked < 500) return;
    const escape = KEY_MAP.Escape;
    input.call("key", escape, 0, GLFW.PRESS, 0);
    input.call("key", escape, 0, GLFW.RELEASE, 0);
  });

  // Safari versions where requestPointerLock() returns void report denial only
  // through this event rather than a rejected Promise. Keep the failure visible
  // in the same diagnostics used by the Promise path above.
  document.addEventListener("pointerlockerror", (event) => {
    globalThis.mcWebInput.diagnostics.pointerLockError = {
      text: "pointerlockerror",
      name: event?.type ?? "pointerlockerror",
      at: Date.now(),
    };
    console.warn("pointer lock denied: pointerlockerror");
  });

  // ---------------------------------------------------------------------
  // Opt-in profiler (?mcweb_perf=1). Answers the first question any perf work
  // has to answer here: of a frame's milliseconds, how many are spent inside
  // the host (WebGPU calls) versus inside Java, and which bridge calls carry
  // the cost -- by total time, not by the call count that intuition reaches
  // for first.
  const _perf = {
    on: false,
    frames: [],          // per-frame total ms, from the pump loop
    rafGaps: [],         // time between rAF callbacks, including time outside Java
    hostMsThisFrame: 0,  // ms inside bridge calls, reset each frame
    hostFrames: [],      // per-frame host ms, index-aligned with frames
    calls: new Map(),    // name -> {n, ms} accumulated over the whole run
    callsThisFrame: 0,
    callFrames: [],      // per-frame bridge-call count
    bytesThisFrame: 0,   // bytes handed to queue.writeBuffer this frame
    bytesByLabelThisFrame: new Map(), // buffer label -> bytes this frame
    callsByLabelThisFrame: new Map(), // buffer label -> upload calls this frame
    uploadBytesFrames: [], // index-aligned with frames: upload bytes/frame
    slowFrameUploads: [],  // per-stutter-frame upload bytes by buffer label
    gpuQueueMs: [],      // asynchronous submit -> onSubmittedWorkDone timings
    gpuSubmits: 0
  };

  // Per-frame upload accounting: total bytes always on; per-buffer-label
  // attribution is opt-in (?mcweb_upattr) because it allocates per upload and
  // was isolated while chasing a perf-flag boot stall. Slow-frame attribution
  // reads the label map.
  const _uploadAttribution = new URLSearchParams(location.search).has("mcweb_upattr");
  const _noteUploadBytes = (label, bytes) => {
    _perf.bytesThisFrame += bytes;
    if (!_uploadAttribution) return;
    const map = _perf.bytesByLabelThisFrame;
    map.set(label, (map.get(label) || 0) + bytes);
    const calls = _perf.callsByLabelThisFrame;
    calls.set(label, (calls.get(label) || 0) + 1);
  };
  const _forceCopySrc = new URLSearchParams(location.search).has("mcweb_copysrc");
  const _forceNoCull = new URLSearchParams(location.search).has("mcweb_nocull");
  let _depthSnapshot = null;
  let _uniformSnapshot = null;
  let _visibleUniformSnapshot = null;

  const _suppressPipelines = (() => {
    const raw = new URLSearchParams(location.search).get("mcweb_suppress");
    return raw ? new RegExp(raw, "i") : null;
  })();
  let _diagnosticSuppression = null;
  const _isPipelineSuppressed = (label) =>
    Boolean((_suppressPipelines && _suppressPipelines.test(label))
      || (_diagnosticSuppression && _diagnosticSuppression.test(label)));

  const _percentile = (sorted, p) =>
    sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))] : 0;

  function installPerfProfiler() {
    const target = globalThis.mcWebGpu;
    for (const name of Object.keys(target)) {
      const original = target[name];
      if (typeof original !== "function") continue;
      target[name] = function (...args) {
        const start = performance.now();
        try {
          return original.apply(this, args);
        } finally {
          const ms = performance.now() - start;
          let entry = _perf.calls.get(name);
          if (!entry) _perf.calls.set(name, entry = {n: 0, ms: 0});
          entry.n++;
          entry.ms += ms;
          _perf.hostMsThisFrame += ms;
          _perf.callsThisFrame++;
        }
      };
    }
    _perf.on = true;
  }

  // WebGPU reports a destroyed-parent/view mismatch asynchronously, usually at
  // submit time, after the Java call that created the bad binding is gone. Keep
  // one bounded, queryable record with the call tail and (on the RPC lane) the
  // exact batch that carried it. The helper deduplicates the visible report.
  const snapshotTextureValidation = () => {
    const viewHandle = _textureLifetime.lastViewHandle();
    const viewEntry = Number.isInteger(viewHandle)
      ? (objects.get(viewHandle) ?? _textureLifetime.lastViewEntry()) : null;
    return _textureLifetime.snapshotValidation(
      viewHandle,
      viewEntry,
      globalThis.mcWebGpu?._rpcLastBatch ?? null,
    );
  };
  let _lastSubmitTextureCandidate = null;
  const noteTextureValidation = (message, candidate = _lastSubmitTextureCandidate) => {
    const snapshot = _textureLifetime.captureValidation(message, candidate);
    if (!snapshot) return null;
    const gpu = globalThis.mcWebGpu;
    if (gpu && !gpu._textureValidation) gpu._textureValidation = snapshot;
    if (gpu && !gpu._textureValidationLogged) {
      gpu._textureValidationLogged = true;
      console.error("[GPU-TEXTURE-VALIDATION]", JSON.stringify(snapshot));
    }
    return snapshot;
  };

  globalThis.mcWebGpu = {
    diagnostics,
    _itemAtlasDraws: () => _itemAtlasDrawRing.slice(),
    _compositeDbg: () => ({
      srcTid: compositeSource?._tid, srcLabel: compositeSource?._label ?? null,
      srcWH: compositeSource ? (compositeSource.width + "x" + compositeSource.height) : null,
      srcSeq: compositeSource ? (lastFrameDrawSeq.get(compositeSource._tid) || 0) : 0,
      lastTid: lastFrameIsCanvas ? -1 : lastFrameDrawSeq.size ? [...lastFrameDrawSeq.entries()].sort((a, b) => b[1] - a[1])[0][0] : 0,
      lastIsCanvas: lastFrameIsCanvas,
      draws: Object.fromEntries(lastFrameDraws),
      seq: Object.fromEntries(lastFrameDrawSeq),
      inv: texInventory.slice(-40).map((t) => [t._tid, t.width + "x" + t.height, t.format, t.isCanvas ? "C" : ""])
    }),
    // DIAG: read back the top-left 32x32 of a texture entry and count non-black /
    // non-transparent pixels. Returns a promise. Proves whether an uploaded atlas
    // actually holds pixel data (vs. an empty/black texture = upload gap).
    _readbackTex: (entry, rw, rh) => new Promise((resolve) => {
      try {
        // Read the WHOLE texture (capped) — a corner-only read gave false
        // negatives for atlases whose art isn't in the top-left 32x32.
        const w = Math.min(rw || 128, entry.width || 1), h = Math.min(rh || 128, entry.height || 1);
        const bpp = 4;
        const bpr = Math.ceil(w * bpp / 256) * 256; // WebGPU: bytesPerRow % 256 == 0
        const buf = device.createBuffer({size: bpr * h, usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ});
        const enc = device.createCommandEncoder();
        enc.copyTextureToBuffer({texture: entry.texture}, {buffer: buf, bytesPerRow: bpr, rowsPerImage: h}, {width: w, height: h, depthOrArrayLayers: 1});
        device.queue.submit([enc.finish()]);
        buf.mapAsync(GPUMapMode.READ).then(() => {
          const u8 = new Uint8Array(buf.getMappedRange().slice(0));
          buf.unmap(); buf.destroy();
          let nonBlack = 0, nonTransp = 0;
          for (let i = 0; i < w * h * bpp; i += bpp) {
            if (u8[i + 3] > 0) nonTransp++;
            if (u8[i] > 4 || u8[i + 1] > 4 || u8[i + 2] > 4) nonBlack++;
          }
          resolve({nonBlack, nonTransp, sampled: w * h});
        }).catch((e) => resolve({error: "map:" + (e && e.message)}));
      } catch (e) { resolve({error: String(e && e.message || e)}); }
    }),
    // DIAG: snapshot every texture sampled on the last frame: label, size, format,
    // per-instance upload count, the TOTAL uploads across ALL instances sharing
    // that label (labelUploads — catches a stale-handle: sampled instance=0 but
    // another instance of the same atlas was uploaded), and a whole-texture
    // readback. Also runs a magenta self-check (readbackSelf) to prove the
    // readback path itself returns non-zero pixels.
    _texDiag: () => {
      const entries = [...(_lastFrameSampled.size ? _lastFrameSampled : _sampledThisFrame)].slice(0, 16);
      const rows = entries.map((e) => ({
        label: (e._label || "?").slice(0, 30),
        wh: `${e.width}x${e.height}x${e.depth || 1}`,
        fmt: e.format,
        uploads: e._uploads || 0,
        labelUploads: (_uploadByLabel.get(e._label) || [0, 0])[0],
        upKB: Math.round((e._uploadBytes || 0) / 1024 * 0.75) // base64→bytes approx
      }));
      // Self-check: a texture we fill with opaque magenta MUST read back non-
      // transparent. If readbackSelf.nonTransp==0 the readback path is broken and
      // every nonTransp value above is untrustworthy.
      const selfCheck = (() => {
        try {
          const t = device.createTexture({size: [8, 8, 1], format: "rgba8unorm", usage: GPUTextureUsage.COPY_DST | GPUTextureUsage.COPY_SRC});
          const d = new Uint8Array(8 * 8 * 4); for (let i = 0; i < d.length; i += 4) { d[i] = 255; d[i + 2] = 255; d[i + 3] = 255; }
          device.queue.writeTexture({texture: t}, d, {bytesPerRow: 8 * 4}, {width: 8, height: 8});
          return globalThis.mcWebGpu._readbackTex({texture: t, width: 8, height: 8});
        } catch (e) { return Promise.resolve({error: "self:" + (e && e.message)}); }
      })();
      const p = Promise.all([selfCheck, ...entries.map((e) => globalThis.mcWebGpu._readbackTex(e))]).then(([self, ...rbs]) => {
        const out = rows.map((r, i) => Object.assign({}, r, rbs[i]));
        _texDiagSnapshot = {readbackSelf: self, rows: out, uploadByLabel: Object.fromEntries(_uploadByLabel), recentCreated: _recentCreated.slice(-24)};
        console.log("[TEX-DIAG] readbackSelf (MUST be nonTransp>0 else readback is broken):", JSON.stringify(self));
        console.log("[TEX-DIAG] uploadByLabel [uploads,bytes] (atlas gui.png uploads=0 here ⇒ atlas truly never uploaded):", JSON.stringify(Object.fromEntries(_uploadByLabel)));
        console.log("[TEX-DIAG] recentCreated (label|wh|uploads) — look for gui.png with uploads>0 that is NOT the sampled instance ⇒ stale handle):", JSON.stringify(_recentCreated.slice(-24).map((r) => ({label: (r.label || "?").slice(0, 26), wh: r.wh, uploads: r._e ? (r._e._uploads || 0) : 0}))));
        try { console.table(out); } catch {}
        console.log("[TEX-DIAG] sampled rows (uploads=inst uploads; labelUploads=total for that label; nonBlack/nonTransp from whole-tex readback):", JSON.stringify(out));
        return out;
      });
      return p;
    },
    _texDiagSnapshot: () => _texDiagSnapshot,
    _fontProbe: () => ({draws: _fontDrawRing, stats: [..._fontPassStats.values()]}),
    _atlasProbe: () => ({
      targets: Object.fromEntries(_animTargetCounts),
      gui: _guiAtlasRing,
      assemblyOffTarget: _asmBad,
      screenSpriteDraws: _scatterDraws
    }),
    _textureProbe: (needle) => {
      const query = String(needle || "").toLowerCase();
      const entries = _recentCreated
        .filter((record) => String(record.label || "").toLowerCase().includes(query))
        .map((record) => record._e)
        .filter(Boolean);
      return Promise.all(entries.map(async (entry) => ({
        label: entry._label,
        handle: entry._hid,
        size: `${entry.width}x${entry.height}x${entry.depth || 1}`,
        format: entry.format,
        uploads: entry._uploads || 0,
        uploadBytes: entry._uploadBytes || 0,
        uploadRegions: entry._uploadRegions || [],
        readback: await globalThis.mcWebGpu._readbackTex(entry)
      })));
    },
    _textureSamples: (needle, coords) => new Promise((resolve) => {
      try {
        const query = String(needle || "").toLowerCase();
        const record = [..._recentCreated].reverse()
          .find((item) => String(item.label || "").toLowerCase().includes(query));
        const entry = record?._e;
        if (!entry) {
          resolve({error: "texture not found"});
          return;
        }
        const w = entry.width, h = entry.height, bpr = Math.ceil(w * 4 / 256) * 256;
        const buffer = device.createBuffer({
          size: bpr * h,
          usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ
        });
        const encoder = device.createCommandEncoder();
        encoder.copyTextureToBuffer(
          {texture: entry.texture},
          {buffer, bytesPerRow: bpr, rowsPerImage: h},
          {width: w, height: h, depthOrArrayLayers: 1}
        );
        device.queue.submit([encoder.finish()]);
        buffer.mapAsync(GPUMapMode.READ).then(() => {
          const bytes = new Uint8Array(buffer.getMappedRange().slice(0));
          buffer.unmap();
          buffer.destroy();
          const result = (Array.isArray(coords) ? coords : []).map(([u, v]) => {
            const cx = Math.max(0, Math.min(w - 1, Math.floor(u * w)));
            const cy = Math.max(0, Math.min(h - 1, Math.floor(v * h)));
            const neighborhood = [];
            for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
              const x = Math.max(0, Math.min(w - 1, cx + dx));
              const y = Math.max(0, Math.min(h - 1, cy + dy));
              const offset = y * bpr + x * 4;
              neighborhood.push([x, y, ...bytes.slice(offset, offset + 4)]);
            }
            return {uv: [u, v], pixel: [cx, cy], neighborhood};
          });
          let minX = w, minY = h, maxX = -1, maxY = -1, nonzero = 0;
          const first = [];
          for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
            const offset = y * bpr + x * 4;
            if (bytes[offset] || bytes[offset + 1] || bytes[offset + 2] || bytes[offset + 3]) {
              nonzero++;
              minX = Math.min(minX, x); minY = Math.min(minY, y);
              maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
              if (first.length < 40) first.push([x, y, ...bytes.slice(offset, offset + 4)]);
            }
          }
          resolve({
            label: entry._label,
            size: [w, h],
            uploads: entry._uploadRegions || [],
            nonzero,
            bounds: nonzero ? [minX, minY, maxX, maxY] : null,
            first,
            samples: result
          });
        }).catch((error) => resolve({error: "map:" + error.message}));
      } catch (error) {
        resolve({error: String(error?.message || error)});
      }
    }),
    // DIAG: late lifetime dump. instancesPerLabel lists EVERY created texture
    // instance for labels that contain atlas/gui/font/menu/title (with each
    // instance's upload count), so we can see a sampled-but-empty instance next
    // to an uploaded one (stale handle) OR confirm the atlas label never got any
    // upload at all (upload-path gap). uploadByLabel is the global total.
    _texDiagLate: () => {
      const byLabel = {};
      for (const r of _recentCreated) {
        const k = r.label || "?";
        (byLabel[k] = byLabel[k] || []).push(r._e ? (r._e._uploads || 0) : -1);
      }
      const interesting = {};
      for (const k of Object.keys(byLabel)) {
        if (/atlas|gui|font|menu|title|widget|missing/i.test(k)) interesting[k] = {instances: byLabel[k], totalUploads: (_uploadByLabel.get(k) || [0])[0]};
      }
      const out = {tick: globalThis.mcWebPump?.ticks || 0, interestingInstances: interesting, uploadByLabelTotals: Object.fromEntries([..._uploadByLabel.entries()].filter(([k]) => /atlas|gui|font|menu|title|widget/i.test(k)))};
      _texDiagSnapshot = out;
      // DIAG: per-handle identity check. sampled[] = textures actually bound for
      // sampling (with their upload count); uploadedNotSampled[] = textures that
      // got a writeTexture64 but were NEVER sampled. If a sampled atlas/widget
      // shows uploads=0 while an uploadedNotSampled entry shares its label => the
      // bind group samples a DIFFERENT instance than the upload (stale handle).
      const sampled = [..._sampledEntries].filter((e) => /atlas|gui|widget|title|font/.test(e._label || "")).slice(0, 40)
        .map((e) => ({label: (e._label || "?").slice(0, 24), hid: e._hid, uploads: e._uploads || 0, wh: `${e.width}x${e.height}`}));
      const sampledSet = new Set([..._sampledEntries]);
      const uploadedNotSampled = _recentCreated.filter((r) => r._e && (r._e._uploads || 0) > 0 && !sampledSet.has(r._e) && /atlas|gui|widget|title|font/.test(r.label || ""))
        .slice(0, 40).map((r) => ({label: (r.label || "?").slice(0, 24), hid: r._e._hid, uploads: r._e._uploads || 0}));
      console.log("[TEX-IDENTITY] sampled (hid:uploads) — uploads=0 on a sampled atlas/widget means the sampled instance never got data:", JSON.stringify(sampled));
      console.log("[TEX-IDENTITY] uploaded-but-NEVER-sampled (hid:uploads) — if a label here matches a sampled uploads=0 label => stale handle:", JSON.stringify(uploadedNotSampled));
      console.log("[TEX-DIAG-LATE] (frame>200) instancesPerLabel{[uploads per instance]} + totals. If a label shows instances like [0,>0] the sampled [0] one is a STALE handle; if a label is absent from totals it NEVER uploaded:", JSON.stringify(out));
      return out;
    },
    isReady: () => Boolean(device),
    textureValidationReport: () =>
      globalThis.mcWebGpu?._textureValidation ?? _textureLifetime.validationReport(),
    // Progress history, independent of console.log: a chatty run can overflow
    // the CDP console pipe and drop exactly the failure line you need.
    stages: (count = 400) => diagnostics.stages.slice(-count),
    /** The same ring with page-relative milliseconds, newest last. */
    stageTimeline: (count = 400) => diagnostics.stages.slice(-count)
      .map((stage, index) => [diagnostics.stageMs.slice(-count)[index], stage]),
    reloadProbe: (count = 8000) => ({
      events: diagnostics.reloadProbe.slice(-count),
      threads: globalThis.mcWebThreadRuntime?.info?.() ?? null,
    }),
    canvasWidth: () => canvas.width,
    canvasHeight: () => canvas.height,
    adapterName: () =>
      adapter?.info?.description || adapter?.info?.device || "Browser WebGPU adapter",

    createTexture(label, usage, format, width, height, depth, mips) {
      markCall("createTexture", {label, usage, format, width, height, depth, mips});
      const gpuFormat = minecraftFormat(format);
      const _handle = put({
        kind: "texture",
        texture: device.createTexture({
          label,
          size: {width, height, depthOrArrayLayers: depth},
          mipLevelCount: mips,
          sampleCount: 1,
          dimension: "2d",
          format: gpuFormat,
          // Force the copy bits: Mojang's presenter blits the main target to
          // the canvas with copyTextureToTexture, but the main target is
          // created without COPY_SRC. Extra usage bits are harmless.
          usage: minecraftTextureUsage(usage) | GPUTextureUsage.COPY_SRC | GPUTextureUsage.COPY_DST
        }),
        format: gpuFormat,
        width,
        height,
        depth,
        _label: label,
        _hid: nextHandle - 1, // DIAG: stable handle id of this texture instance
        _uploads: 0,   // DIAG: how many writeTexture64 uploads landed on this texture
        _uploadBytes: 0,
        _uploadRegions: []
      });
      const _entry = objects.get(_handle);
      _textureLifetime.initializeTexture(_entry);
      _recentCreated.push({label, wh: `${width}x${height}x${depth}`, fmt: gpuFormat, _e: _entry}); // DIAG registry
      if (/panorama/i.test(label)) _panoProbe.created.push({ label, w: width, h: height, depth, fmt: gpuFormat, usage, hid: _entry._hid });
      return _handle;
    },

    createTextureView(textureHandle, baseMip, mipLevels, dimension) {
      markCall("createTextureView", {textureHandle, baseMip, mipLevels, dimension});
      const entry = get(textureHandle, "texture");
      const viewEntry = {
        kind: "textureView",
        view: entry.texture.createView({
          dimension: dimension === "cube" ? "cube" : "2d",
          baseMipLevel: baseMip,
          mipLevelCount: mipLevels
        }),
        textureEntry: entry,
        // Needed to tell "the atlas was stitched" from "only mip 0 was
        // stitched": TextureAtlas.upload makes one view per mip and blits into
        // each, and a distant block face samples a high mip. An unfilled mip
        // reads as uniform garbage while mip 0 still looks perfect up close.
        baseMip
      };
      _textureLifetime.retainView(entry);
      return put(viewEntry);
    },

    createSamplerJson(specJson) {
      const spec = JSON.parse(specJson);
      markCall("createSampler", spec);
      const address = (mode) => (mode === "REPEAT" ? "repeat" : "clamp-to-edge");
      const linear = (filter) => filter === "LINEAR";
      const handle = put({
        kind: "sampler",
        sampler: device.createSampler({
          addressModeU: address(spec.addressU),
          addressModeV: address(spec.addressV),
          minFilter: linear(spec.min) ? "linear" : "nearest",
          magFilter: linear(spec.mag) ? "linear" : "nearest",
          mipmapFilter: linear(spec.min) ? "linear" : "nearest",
          maxLod: spec.maxLod == null ? 32 : spec.maxLod
        })
      });
      (globalThis.mcWebGpu._samplerSpecs ||= []).push({handle, ...spec});
      return handle;
    },

    destroyTexture(handle) {
      const entry = get(handle, "texture");
      _textureLifetime.requestDestroy(entry);
      objects.delete(handle);
    },

    destroyObject(handle) {
      const object = objects.get(handle);
      if (object?.kind === "texture") _textureLifetime.requestDestroy(object);
      if (object?.kind === "textureView") _textureLifetime.releaseView(object);
      if (object?.kind === "buffer" && object.buffer) deferDestroyBuffer(object.buffer);
      objects.delete(handle);
    },

    createCommandEncoder() {
      markCall("createCommandEncoder");
      return put({kind: "encoder", encoder: device.createCommandEncoder({label: "Minecraft CommandEncoder"})});
    },

    createBuffer(label, usage, size) {
      markCall("createBuffer", {label, usage, size});
      if (usage & 256) {
        (globalThis.mcWebGpu._texelBuffers ||= []).push({label, usage, size});
      }
      return put({
        kind: "buffer",
        size, // retained so uniform binds can cap their inflated range to the buffer
        _label: label, // DIAG: for UBO inspection
        // DIAG: CPU mirror of buffer contents, for the UBO/vertex/index
        // decoders below. Skipped above SHADOW_LIMIT: terrain's three
        // "UberBuffer solid/cutout/translucent" are 128 MiB each, so mirroring
        // them costs ~384 MiB of JS heap for diagnostics that only ever inspect
        // small uniform and GUI buffers. Every reader is null-guarded, and the
        // two writers (writeBuffer, copyBuffer) test for it before mirroring.
        _shadow: size <= SHADOW_LIMIT ? new Uint8Array(size) : null,
        buffer: device.createBuffer({
          label,
          size,
          // ?mcweb_copysrc=1 makes every buffer readable. Uniform buffers are
          // normally UNIFORM|COPY_DST, so copyBufferToBuffer on them is a
          // validation error that leaves the destination zero-filled -- which
          // is indistinguishable from the uniform genuinely being zero, and
          // already produced one false "ProjMat is all zeros" reading.
          usage: minecraftBufferUsage(usage) | (_forceCopySrc ? GPUBufferUsage.COPY_SRC : 0)
        })
      });
    },

    destroyBuffer(handle) {
      const entry = get(handle, "buffer");
      if (entry?.buffer) deferDestroyBuffer(entry.buffer);
      objects.delete(handle);
    },

    writeBuffer64(handle, destinationOffset, base64) {
      const _bytes0 = base64ToBytes(base64);
      if (_gpuProbe) {
        noteUniformProbe(get(handle, "buffer"), handle, destinationOffset,
          _bytes0, base64 ? base64.length : 0);
      }
      markCall("writeBuffer", {handle, destinationOffset, base64Length: base64?.length});
      const _be = get(handle, "buffer");
      const _bytes = _bytes0;
      // DIAG: keep CPU shadow for UBO inspection
      if (_be._shadow && destinationOffset + _bytes.length <= _be._shadow.length) {
        _be._shadow.set(_bytes, destinationOffset);
      }
      _noteBytes(_be, "write", _bytes.length);
      _noteUploadBytes(_be._label || "?", _bytes.length);
      device.queue.writeBuffer(_be.buffer, destinationOffset, _bytes);
    },

    // Bytes straight from the caller, no encoding at all. The OpenJDK lane
    // hands its uploads over as a typed array, which the RPC copies once into
    // its SharedArrayBuffer; base64 cost an encode, three string copies and a
    // decode for data that was always bytes. Same body as the linear-memory
    // path, which already takes bytes.
    writeBufferBytes(handle, destinationOffset, bytes) {
      return this.writeBufferRaw(handle, destinationOffset, bytes);
    },

    // Texture counterpart of writeBufferBytes: no base64, no strings.
    writeTextureBytes(handle, bytes, mipLevel, depthOrLayer, x, y, width, height, bytesPerRow, rowsPerImage) {
      return this.writeTextureRaw(handle, bytes, mipLevel, depthOrLayer, x, y, width, height, bytesPerRow, rowsPerImage);
    },

    writeBufferText(handle, destinationOffset, text, byteLength) {
      const bytes = packedTextToBytes(text, byteLength);
      if (_gpuProbe) {
        noteUniformProbe(get(handle, "buffer"), handle, destinationOffset, bytes, byteLength);
      }
      markCall("writeBuffer", {
        handle, destinationOffset, byteLength, packedChars: text.length
      });
      const buffer = get(handle, "buffer");
      if (buffer._shadow && destinationOffset + byteLength <= buffer._shadow.length) {
        buffer._shadow.set(bytes, destinationOffset);
      }
      _noteBytes(buffer, "write", byteLength);
      _noteUploadBytes(buffer._label || "?", byteLength);
      device.queue.writeBuffer(buffer.buffer, destinationOffset, bytes);
    },

    /**
     * Typed-array upload path. WasmLM passes a view into its shared Java heap;
     * WasmGC's raw reader bridge passes the exact decoded scratch view without
     * materializing a Java String.
     */
    writeBufferRaw(handle, destinationOffset, bytes) {
      const byteLength = bytes?.byteLength ?? 0;
      const entry = get(handle, "buffer");
      // Two uniform-buffer probes used to run on every upload: a regex over
      // the label, and — for any buffer <= 256 B, which is every UBO slot —
      // a slice plus two typed-array materializations with a rounding map.
      // They answer questions from the atlas/uniform investigations, so they
      // are kept, behind the flag that asks for them.
      if (_gpuProbe) noteUniformProbe(entry, handle, destinationOffset, bytes, byteLength);
      markCall("writeBufferRaw", {handle, destinationOffset, byteLength});
      if (entry._shadow && destinationOffset + byteLength <= entry._shadow.length) {
        entry._shadow.set(bytes, destinationOffset);
      }
      _noteBytes(entry, "write", byteLength);
      _noteUploadBytes(entry._label || "?", byteLength);
      device.queue.writeBuffer(entry.buffer, destinationOffset, bytes);
    },

    writeTexture64(handle, base64, mipLevel, depthOrLayer, x, y, width, height, bytesPerRow, rowsPerImage) {
      markCall("writeTexture", {handle, mipLevel, depthOrLayer, x, y, width, height, bytesPerRow, rowsPerImage, base64Length: base64?.length});
      const _te = get(handle, "texture");
      _te._uploads = (_te._uploads || 0) + 1;            // DIAG
      // Survives destroyTexture: records that a label was written at all, and
      // on which handle. If an atlas shows writes here but the handle terrain
      // samples has none, the upload landed on a since-replaced instance --
      // the "stale handle" case the probe below was written to catch.
      {
        const wl = (globalThis.mcWebGpu._texWriteLog ||= {});
        const key = (_te._label || "?");
        const rec = (wl[key] ||= {writes: 0, handles: []});
        rec.writes++;
        if (!rec.handles.includes(handle)) rec.handles.push(handle);
      }
      _te._uploadBytes = (_te._uploadBytes || 0) + (base64 ? base64.length : 0); // DIAG (base64 len ∝ bytes)
      if (_te._uploadRegions.length < 100) {
        _te._uploadRegions.push({mipLevel, depthOrLayer, x, y, width, height, bytesPerRow, rowsPerImage});
      }
      { const _k = _te._label || "?"; const _u = _uploadByLabel.get(_k) || [0, 0]; _u[0]++; _u[1] += base64 ? base64.length : 0; _uploadByLabel.set(_k, _u); } // DIAG
      if (/panorama/i.test(_te._label)) _panoProbe.uploads.push({ hid: _te._hid, label: _te._label, layer: depthOrLayer, w: width, h: height, mip: mipLevel, b64len: base64 ? base64.length : 0 });
      device.queue.writeTexture(
        {texture: _te.texture, mipLevel, origin: {x, y, z: depthOrLayer}},
        base64ToBytes(base64),
        {bytesPerRow, rowsPerImage},
        {width, height, depthOrArrayLayers: 1}
      );
    },

    /** WasmLM texture upload: consume the shared linear-memory view directly. */
    writeTextureRaw(handle, bytes, mipLevel, depthOrLayer, x, y, width, height, bytesPerRow, rowsPerImage) {
      const byteLength = bytes?.byteLength ?? 0;
      markCall("writeTextureRaw", {handle, mipLevel, depthOrLayer, x, y, width, height, bytesPerRow, rowsPerImage, byteLength});
      const _te = get(handle, "texture");
      _te._uploads = (_te._uploads || 0) + 1;
      {
        const wl = (globalThis.mcWebGpu._texWriteLog ||= {});
        const key = (_te._label || "?");
        const rec = (wl[key] ||= {writes: 0, handles: []});
        rec.writes++;
        if (!rec.handles.includes(handle)) rec.handles.push(handle);
      }
      _te._uploadBytes = (_te._uploadBytes || 0) + byteLength;
      if (_te._uploadRegions.length < 100) {
        _te._uploadRegions.push({mipLevel, depthOrLayer, x, y, width, height, bytesPerRow, rowsPerImage});
      }
      { const _k = _te._label || "?"; const _u = _uploadByLabel.get(_k) || [0, 0]; _u[0]++; _u[1] += byteLength; _uploadByLabel.set(_k, _u); }
      if (/panorama/i.test(_te._label)) _panoProbe.uploads.push({hid: _te._hid, label: _te._label, layer: depthOrLayer, w: width, h: height, mip: mipLevel, byteLength});
      device.queue.writeTexture(
        {texture: _te.texture, mipLevel, origin: {x, y, z: depthOrLayer}},
        bytes,
        {bytesPerRow, rowsPerImage},
        {width, height, depthOrArrayLayers: 1}
      );
    },

    clearColorTexture(encoderHandle, textureHandle, r, g, b, a) {
      const encoder = get(encoderHandle, "command encoder").encoder;
      const texture = get(textureHandle, "texture").texture;
      const pass = encoder.beginRenderPass({
        label: "Minecraft clearColorTexture",
        colorAttachments: [{
          view: texture.createView(),
          clearValue: {r, g, b, a},
          loadOp: "clear",
          storeOp: "store"
        }]
      });
      pass.end();
    },

    clearColorAndDepth(encoderHandle, colorHandle, r, g, b, a, depthHandle, depth) {
      const encoder = get(encoderHandle, "command encoder").encoder;
      const pass = encoder.beginRenderPass({
        label: "Minecraft clearColorAndDepth",
        colorAttachments: [{
          view: get(colorHandle, "texture").texture.createView(),
          clearValue: {r, g, b, a},
          loadOp: "clear",
          storeOp: "store"
        }],
        depthStencilAttachment: {
          view: get(depthHandle, "texture").texture.createView(),
          depthClearValue: depth,
          depthLoadOp: "clear",
          depthStoreOp: "store"
        }
      });
      pass.end();
    },

    clearColorAndDepthRegion(
      encoderHandle,
      colorHandle,
      r,
      g,
      b,
      a,
      depthHandle,
      depth,
      x,
      y,
      width,
      height
    ) {
      const encoder = get(encoderHandle, "command encoder").encoder;
      const colorEntry = get(colorHandle, "texture");
      const depthEntry = get(depthHandle, "texture");
      const colorFormat = colorEntry.format || "rgba8unorm";
      const depthFormat = depthEntry.format || "depth32float";
      const values = [r, g, b, a, depth].map((value) => Number(value).toFixed(8));
      const key = `${colorFormat}|${depthFormat}|${values.join("|")}`;
      let pipeline = regionClearPipelines.get(key);
      if (!pipeline) {
        const module = device.createShaderModule({
          label: "Minecraft regional color/depth clear",
          code: `
struct ClearOutput {
  @location(0) color: vec4<f32>,
  @builtin(frag_depth) depth: f32,
};

@vertex
fn vs_main(@builtin(vertex_index) vertexIndex: u32) -> @builtin(position) vec4<f32> {
  let positions = array<vec2<f32>, 3>(
    vec2<f32>(-1.0, -1.0),
    vec2<f32>(3.0, -1.0),
    vec2<f32>(-1.0, 3.0)
  );
  return vec4<f32>(positions[vertexIndex], 0.0, 1.0);
}

@fragment
fn fs_main() -> ClearOutput {
  var output: ClearOutput;
  output.color = vec4<f32>(${values.slice(0, 4).join(", ")});
  output.depth = ${values[4]};
  return output;
}`
        });
        pipeline = device.createRenderPipeline({
          label: "Minecraft regional color/depth clear",
          layout: "auto",
          vertex: {module, entryPoint: "vs_main"},
          fragment: {
            module,
            entryPoint: "fs_main",
            targets: [{format: colorFormat}]
          },
          primitive: {topology: "triangle-list"},
          depthStencil: {
            format: depthFormat,
            depthWriteEnabled: true,
            depthCompare: "always"
          }
        });
        regionClearPipelines.set(key, pipeline);
      }

      const pass = encoder.beginRenderPass({
        label: "Minecraft clearColorAndDepthRegion",
        colorAttachments: [{
          view: colorEntry.texture.createView(),
          loadOp: "load",
          storeOp: "store"
        }],
        depthStencilAttachment: {
          view: depthEntry.texture.createView(),
          depthLoadOp: "load",
          depthStoreOp: "store"
        }
      });
      const clearWidth = Math.max(1, Math.min(width, colorEntry.width - x));
      const clearHeight = Math.max(1, Math.min(height, colorEntry.height - y));
      // Minecraft exposes OpenGL's bottom-left texture coordinates; WebGPU
      // scissor rectangles use a top-left origin.
      const webGpuY = Math.max(0, colorEntry.height - y - clearHeight);
      pass.setScissorRect(
        Math.max(0, x),
        webGpuY,
        clearWidth,
        clearHeight
      );
      pass.setPipeline(pipeline);
      pass.draw(3);
      pass.end();
    },

    clearDepth(encoderHandle, textureHandle, depth) {
      (globalThis.mcWebGpu._depthClears ||= {})["clearDepth:" + depth] =
        ((globalThis.mcWebGpu._depthClears || {})["clearDepth:" + depth] || 0) + 1;
      const encoder = get(encoderHandle, "command encoder").encoder;
      const pass = encoder.beginRenderPass({
        label: "Minecraft clearDepth",
        // colorAttachments is a required WebIDL member even for depth-only
        // passes — an empty sequence is the valid depth-only form.
        colorAttachments: [],
        depthStencilAttachment: {
          view: get(textureHandle, "texture").texture.createView(),
          depthClearValue: depth,
          depthLoadOp: "clear",
          depthStoreOp: "store"
        }
      });
      pass.end();
    },

    copyTexture(encoderHandle, sourceHandle, destinationHandle,
                sourceX, sourceY, destinationX, destinationY,
                width, height, mipLevel) {
      const encoder = get(encoderHandle, "command encoder").encoder;
      const source = get(sourceHandle, "source texture");
      const destination = get(destinationHandle, "destination texture");
      // DIAG (one-shot per dest label): is the atlas/gui assembled by GPU blit,
      // and from a source that actually has uploads? Remove with the fix.
      {
        const dl = destination._label || "?";
        if (/atlas|gui|widget|title|font/.test(dl) && !(copyTexture._seen || (copyTexture._seen = new Set())).has(dl)) {
          copyTexture._seen.add(dl);
          console.log("[COPY-TEX] dest=" + dl + " (hid=" + destination._hid + ",uploads=" + (destination._uploads || 0) + ") <- src=" + (source._label || "?") + " (hid=" + source._hid + ",uploads=" + (source._uploads || 0) + ") " + width + "x" + height);
        }
      }
      destination._copiesIn = (destination._copiesIn || 0) + 1;
      source._copiesOut = (source._copiesOut || 0) + 1;
      // Clamp to what actually remains in each texture from its own origin. The
      // offsets used to be dropped entirely, which silently turned every
      // sub-rectangle blit into a copy of the top-left corner; the mip level was
      // dropped with them, so mip blits overwrote mip 0.
      const mip = Math.max(0, mipLevel | 0);
      const sx = Math.max(0, sourceX | 0);
      const sy = Math.max(0, sourceY | 0);
      const dx = Math.max(0, destinationX | 0);
      const dy = Math.max(0, destinationY | 0);
      const mipExtent = (size) => Math.max(1, (size ?? 0) >> mip);
      const w = Math.max(0, Math.min(
        width,
        mipExtent(source.width) - sx,
        mipExtent(destination.width) - dx
      ));
      const h = Math.max(0, Math.min(
        height,
        mipExtent(source.height) - sy,
        mipExtent(destination.height) - dy
      ));
      if (!w || !h) return;
      encoder.copyTextureToTexture(
        {texture: source.texture, mipLevel: mip, origin: {x: sx, y: sy, z: 0}},
        {texture: destination.texture, mipLevel: mip, origin: {x: dx, y: dy, z: 0}},
        {width: w, height: h, depthOrArrayLayers: 1}
      );
    },

    copyBuffer(encoderHandle, sourceHandle, sourceOffset, destinationHandle, destinationOffset, size) {
      const encoder = get(encoderHandle, "command encoder").encoder;
      const source = get(sourceHandle, "source buffer");
      const destination = get(destinationHandle, "destination buffer");
      if (/Auto Storage/i.test(destination?._label || "")) {
        const r = (globalThis.mcWebGpu._autoIndexWrites ||= {writeBuffer: 0, bytes: 0, copies: 0});
        r.copies++;
      }
      // Keep the diagnostic CPU mirror coherent with the GPU copy. Persistent
      // GUI/text vertex buffers are filled from transient staging buffers, so
      // inspecting only queue.writeBuffer calls otherwise reports misleading
      // all-zero destination vertices.
      if (source._shadow && destination._shadow
          && sourceOffset >= 0 && destinationOffset >= 0
          && sourceOffset + size <= source._shadow.length
          && destinationOffset + size <= destination._shadow.length) {
        destination._shadow.set(
          source._shadow.subarray(sourceOffset, sourceOffset + size),
          destinationOffset
        );
      }
      _noteBytes(destination, "copy", size);
      encoder.copyBufferToBuffer(
        source.buffer,
        sourceOffset,
        destination.buffer,
        destinationOffset,
        size
      );
    },

    submit(encoderHandle) {
      markCall("submit", {encoderHandle});
      const entry = get(encoderHandle, "command encoder");
      // Error scopes resolve asynchronously. Freeze the candidate view, its
      // parent state, the call tail, and the RPC batch at this submit boundary
      // so a later frame cannot overwrite the evidence before the scope pops.
      const validationCandidate = snapshotTextureValidation();
      _lastSubmitTextureCandidate = validationCandidate;
      const gpuSubmitStarted = _perf.on ? performance.now() : 0;
      // Validation errors poison the device; capture them per submit so the
      // first offending command is reported before the device is lost.
      if (typeof device.pushErrorScope === "function") {
        device.pushErrorScope("validation");
        device.pushErrorScope("out-of-memory");
      }
      device.queue.submit([entry.encoder.finish()]);
      if (_perf.on && typeof device.queue.onSubmittedWorkDone === "function") {
        _perf.gpuSubmits++;
        device.queue.onSubmittedWorkDone().then(() => {
          _perf.gpuQueueMs.push(performance.now() - gpuSubmitStarted);
        }).catch(() => {});
      }
      // Attach everything queued since the prior submit to this submission's
      // completion fence before releasing the underlying GPU objects.
      flushGraveyard();
      if (typeof device.popErrorScope === "function") {
        const onScopeError = (label) => (error) => {
          if (error) {
            const msg = `[WebGPU ${label}] ${error.message}`;
            noteTextureValidation(msg, validationCandidate);
            console.error(msg);
            if (!globalThis.mcWebGpu._firstGpuError) {
              globalThis.mcWebGpu._firstGpuError = msg;
            }
            // DIAG: keep the first N validation messages UN-deduplicated so the
            // ROOT error (e.g. a createBindGroup "too small"/overrun) is visible
            // and not hidden behind later "invalid due to a previous error"
            // cascades. Queryable via globalThis.mcWebGpu._valErrors.
            const ve = (globalThis.mcWebGpu._valErrors ||= []);
            if (ve.length < 16) ve.push(msg.slice(0, 400));
          }
        };
        // "Instance dropped" rejections are benign (headless SwiftShader
        // quirk, or device torn down at process exit); swallow them quietly.
        const swallowDropped = () => {};
        device.popErrorScope().then(onScopeError("oom"), swallowDropped);
        device.popErrorScope().then(onScopeError("validation"), swallowDropped);
      }
      objects.delete(encoderHandle);
    },

    createPipeline(specJson) {
      const spec = JSON.parse(specJson);
      (globalThis.mcWebGpu._pipelineSpecs ||= {})[spec.label] = {
        vertexShader: spec.vertexShader,
        fragmentShader: spec.fragmentShader,
        defines: spec.defines,
        uniforms: (spec.bindGroups || []).flatMap((g) => g.uniforms || []),
        samplers: (spec.bindGroups || []).flatMap((g) => g.samplers || []),
        vertexElements: (spec.vertexFormats?.[0]?.elements || []).map((e) => e.name),
      };
      if (/entity|item_|transparency/.test(spec.label || "")) {
        (globalThis.mcWebGpu._rawPipelineSpecs ||= {})[spec.label] = spec;
      }
      // DIAG: what depth state each pipeline declares, and what it resolves to.
      // COMPARE_OP silently falls back to "less-equal" for any name it does not
      // know, which under Mojang's reverse-Z projection (near maps to 1, far to
      // 0) rejects every fragment against a 0.0 depth clear -- invisible
      // geometry with correct draw calls and no validation error.
      (globalThis.mcWebGpu._pipelineDepth ||= {})[spec.label] = spec.depthStencil
        ? {
            declared: spec.depthStencil.depthTest,
            resolved: COMPARE_OP[spec.depthStencil.depthTest] || "less-equal(FALLBACK)",
            write: spec.depthStencil.writeDepth
          }
        : null;
      if (/panorama/i.test(spec.label)) _panoProbe.pipeBegin.push({ label: spec.label, vs: spec.vertexShader, topo: spec.topology });
      markCall("createPipeline", {label: spec.label});
      try {
        // For families whose spec declares no depth state, the natural base is
        // the NO-depth pipeline; the depth variant is the alt below. (Passing
        // the default would synthesize depth into the base and leave the
        // no-depth pass unmatched.) Families with depthStencil keep the default.
        const baseDepthArg = spec.depthStencil ? undefined : null;
        const built = buildPipeline(spec, baseDepthArg);
        if (!built) {
          recordStage(`pipeline-skipped:${spec.label}`);
          if (/panorama/i.test(spec.label)) _panoProbe.pipeSkip.push(spec.label);
          return 0;
        }
        // Pre-build the *other* depth attachment state up front so rpSetPipeline
        // can pick an exact match by direct lookup (the lazy variant path raced
        // the cached base handle and left the no-depth pipeline bound in a
        // depth pass). Map key null = no depth attachment.
        const byDepth = new Map();
        byDepth.set(built.depthFormat, built.pipeline);
        const baseDepth = built.depthFormat;
        const altDepth = spec.depthStencil ? null : "depth32float";
        if (altDepth !== baseDepth) {
          try {
            const alt = buildPipeline(spec, altDepth);
            if (alt) byDepth.set(alt.depthFormat, alt.pipeline);
          } catch (altError) {
            recordStage(`pipeline-alt-failed:${spec.label}:${altDepth}:${altError.message}`);
          }
        }
        recordStage(`pipeline-ok:${spec.label}`);
        if (/panorama/i.test(spec.label)) _panoProbe.pipeOk.push(spec.label);
        return put({kind: "pipeline", ...built, byDepth});
      } catch (error) {
        recordStage(`pipeline-failed:${spec.label}:${error.message}`);
        if (/panorama/i.test(spec.label)) _panoProbe.pipeFail.push(spec.label + ":" + error.message);
        console.warn("pipeline failed:", spec.label, error);
        return 0;
      }
    },

    beginRenderPass(encoderHandle, descriptorJson) {
      const descriptor = JSON.parse(descriptorJson);
      markCall("beginRenderPass", descriptor);
      const encoder = get(encoderHandle, "command encoder").encoder;
      const colorAttachments = descriptor.color.map((attachment) => {
        if (!attachment) return null;
        const viewEntry = get(attachment.view, "texture view");
        _textureLifetime.noteViewUse(attachment.view, viewEntry);
        const gpuAttachment = {
          view: viewEntry.view,
          storeOp: "store"
        };
        if (attachment.clear) {
          gpuAttachment.loadOp = "clear";
          gpuAttachment.clearValue = {
            r: attachment.clear[0],
            g: attachment.clear[1],
            b: attachment.clear[2],
            a: attachment.clear[3]
          };
        } else {
          gpuAttachment.loadOp = "load";
        }
        return gpuAttachment;
      });
      // Render-target census. 26.2 fills an atlas by *drawing* sprites into it,
      // so neither _uploads (writeTexture) nor _copiesIn (copyTexture) counts
      // that path -- an atlas can read 0/0 and still be fully populated, or
      // 0/0 and never touched, and those look identical without this. Keyed by
      // the colour attachment's texture label.
      {
        const census = (globalThis.mcWebGpu._renderTargets ||= {});
        for (const attachment of descriptor.color) {
          if (!attachment) continue;
          const viewEntry = get(attachment.view, "texture view");
          const label = String(viewEntry.textureEntry?._label ?? "?")
            + " mip" + (viewEntry.baseMip ?? 0);
          const record = (census[label] ||= {passes: 0, draws: 0, cleared: 0});
          record.passes++;
          if (attachment.clear) record.cleared++;
        }
      }
      const passDescriptor = {label: "Minecraft render pass", colorAttachments};
      let depthFormat = null;
      if (descriptor.depth) {
        const viewEntry = get(descriptor.depth.view, "texture view");
        _textureLifetime.noteViewUse(descriptor.depth.view, viewEntry);
        depthFormat = viewEntry.textureEntry.format || "depth32float";
        const hasStencil = depthFormat.includes("stencil");
        passDescriptor.depthStencilAttachment = {
          view: viewEntry.view,
          depthStoreOp: "store",
          // Stencil ops only for stencil-bearing formats.
          ...(hasStencil ? {stencilStoreOp: "store"} : {})
        };
        if (descriptor.depth.clear != null) {
          passDescriptor.depthStencilAttachment.depthLoadOp = "clear";
          passDescriptor.depthStencilAttachment.depthClearValue = descriptor.depth.clear;
          (globalThis.mcWebGpu._depthClears ||= {})[String(descriptor.depth.clear)] =
            ((globalThis.mcWebGpu._depthClears || {})[String(descriptor.depth.clear)] || 0) + 1;
          if (hasStencil) {
            passDescriptor.depthStencilAttachment.stencilLoadOp = "clear";
            passDescriptor.depthStencilAttachment.stencilClearValue = 0;
          }
        } else {
          passDescriptor.depthStencilAttachment.depthLoadOp = "load";
          if (hasStencil) passDescriptor.depthStencilAttachment.stencilLoadOp = "load";
        }
      }
      const pass = encoder.beginRenderPass(passDescriptor);
      const firstColor = descriptor.color.find((a) => a) || null;
      const firstColorEntry = firstColor ? get(firstColor.view, "texture view").textureEntry : null;
      const height = descriptor.height || (firstColorEntry ? firstColorEntry.height : canvas.height);
      // The scene main target is whichever non-canvas target the frame draws to
      // most (the GUI/title render), NOT the largest by area. Count draws per
      // target this frame and choose at present() time.
      let passState_tid = 0;
      if (firstColorEntry && !firstColorEntry.isCanvas) {
        if (firstColorEntry._tid == null) { firstColorEntry._tid = ++texSeq; texInventory.push(firstColorEntry); }
        passState_tid = firstColorEntry._tid;
      } else {
        passState_tid = firstColorEntry && firstColorEntry.isCanvas ? -1 : 0;
      }
      return put({
        kind: "renderPass",
        pass,
        // Retained so the depth attachment can be snapshotted the instant the
        // world pass ends. Reading it at end of frame is useless: GUI passes
        // clear depth (clearDepth:0 fires 6495 times a run), so a late read
        // always shows the cleared value and hides whether terrain ever wrote.
        _encoder: encoder,
        _depthEntry: descriptor.depth ? get(descriptor.depth.view, "texture view").textureEntry : null,
        _sawTerrain: false,
        height,
        depthFormat,
        area: descriptor.area || null,
        _tid: passState_tid,
        _drawn: 0,
        pipeline: null,
        resources: new Map(),
        drawTexBinds: [], // positional texture binds this draw (name-independent fallback)
        vertexBuffers: [],
        indexBuffer: null,
        boundGroups: [],
        // DIAG: render target identity for atlas-assembly debugging
        _targetLabel: firstColorEntry
          ? firstColorEntry._label + " mip" + (get(firstColor.view, "texture view").baseMip ?? 0)
          : null,
        _targetWH: firstColorEntry ? (firstColorEntry.width + "x" + firstColorEntry.height) : null,
        _targetTid: firstColorEntry ? firstColorEntry._tid : null,
        // Ordered pass trace for ?mcweb_drawcensus. Terrain drawing into the
        // right target and still not appearing points at frame order: a later
        // pass that clears the same attachment would erase it.
        _traceEntry: _drawCensus
          ? (() => {
              const entry = {
                target: firstColorEntry ? String(firstColorEntry._label) : "?",
                clear: !!(firstColor && firstColor.clear), draws: 0,
                // Depth state matters as much as colour here: sky draws with
                // no depth test, terrain is depth-tested, so a depth buffer
                // that is never cleared rejects every terrain fragment while
                // the sky still shows.
                depth: descriptor.depth
                  ? (descriptor.depth.clear != null ? "clear" + descriptor.depth.clear : "load")
                  : "none",
              };
              _framePassTrace.push(entry);
              return entry;
            })()
          : null,
        _scissor: null
      });
    },

    rpEnd(passHandle) {
      if (_renderCommandReplayDepth === 0) markCall("rpEnd", {passHandle});
      const state = passState(passHandle);
      state.pass.end();
      if (state._drawn) {
        const record = (globalThis.mcWebGpu._renderTargets ||= {})[state._targetLabel ?? "?"];
        if (record) record.draws += state._drawn;
      }
      // Capture a pass that actually drew a lot of terrain, not merely the
      // first pass that bound a terrain pipeline: terrain does not start
      // drawing until ~tick 2425, so an early pass yields an all-zero depth
      // buffer that proves nothing. Record the draw count so an all-zero
      // result can be told apart from an empty pass.
      if (state._sawTerrain) {
        // Census first: how many draws a terrain pass really carries, whether
        // it has a depth attachment at all, and at which ticks. The previous
        // threshold of 50 draws never triggered -- terrain draws ~36 times per
        // frame split across passes -- so the "no depth captured" result said
        // nothing about the renderer.
        const c = (globalThis.mcWebGpu._terrainPassCensus ||= {
          passes: 0, totalDraws: 0, maxDraws: 0, withDepth: 0, firstTick: null, lastTick: null
        });
        c.passes++;
        c.totalDraws += state._drawn;
        c.maxDraws = Math.max(c.maxDraws, state._drawn);
        if (state._depthEntry) c.withDepth++;
        const tick = globalThis.mcWebPump?.ticks || 0;
        if (c.firstTick == null) c.firstTick = tick;
        c.lastTick = tick;
      }
      if (state._sawTerrain && state._depthEntry && !_depthSnapshot
          && state._drawn >= 5
          && (_drawCensus || (globalThis.mcWebPump?.ticks || 0) > 2600)) {
        const tex = state._depthEntry;
        const bytesPerRow = Math.ceil(tex.width * 4 / 256) * 256;
        try {
          _depthSnapshot = {
            buffer: device.createBuffer({
              size: bytesPerRow * tex.height,
              usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ
            }),
            bytesPerRow, width: tex.width, height: tex.height, label: tex._label,
            drawn: state._drawn, tick: globalThis.mcWebPump?.ticks || 0
          };
          state._encoder.copyTextureToBuffer(
            {texture: tex.texture, aspect: "depth-only"},
            {buffer: _depthSnapshot.buffer, bytesPerRow, rowsPerImage: tex.height},
            {width: tex.width, height: tex.height, depthOrArrayLayers: 1}
          );
        } catch (error) {
          _depthSnapshot = {error: String(error).slice(0, 200)};
        }
      }
      // Uniform snapshot, taken the same way the depth snapshot is: with the
      // pass's own encoder, immediately after pass.end(), so the bytes are the
      // ones this submission used. Reading a uniform buffer at present() time
      // measures a recycled ring buffer and reports zeros for every pipeline,
      // including ones that visibly render.
      if (_drawCensus && state._sawTerrain && !_uniformSnapshot
          && globalThis.mcWebGpu._terrainDrawSample?.projection) {
        const range = globalThis.mcWebGpu._terrainDrawSample.projection;
        const entry = objects.get(range.bufferHandle);
        if (entry && entry.buffer) {
          try {
            _uniformSnapshot = {
              buffer: device.createBuffer({
                size: 64,
                usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ
              }),
              drawn: state._drawn
            };
            state._encoder.copyBufferToBuffer(entry.buffer, range.offset,
              _uniformSnapshot.buffer, 0, 64);
          } catch (error) {
            _uniformSnapshot = {error: String(error).slice(0, 160)};
          }
        }
      }
      if (_drawCensus && state._sawVisible && !_visibleUniformSnapshot
          && globalThis.mcWebGpu._visibleProjRange) {
        const range = globalThis.mcWebGpu._visibleProjRange;
        const entry = objects.get(range.bufferHandle);
        if (entry && entry.buffer) {
          try {
            _visibleUniformSnapshot = {
              buffer: device.createBuffer({
                size: 64,
                usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ
              }),
              drawn: state._drawn
            };
            state._encoder.copyBufferToBuffer(entry.buffer, range.offset,
              _visibleUniformSnapshot.buffer, 0, 64);
          } catch (error) {
            _visibleUniformSnapshot = {error: String(error).slice(0, 160)};
          }
        }
      }
      // Track canvas passes (_tid === -1) separately: Mojang draws the splash
      // straight to the canvas, so when the canvas is the last-drawn target we
      // must NOT composite (it would overwrite Mojang's correct output).
      if (state._tid === -1 && state._drawn) {
        frameLastTid = -1;
        frameLastIsCanvas = true;
      }
      if (state._tid > 0 && state._drawn) {
        frameDraws.set(state._tid, (frameDraws.get(state._tid) || 0) + state._drawn);
        // Stamp draw order for non-canvas targets.
        state._seq = ++drawSeq;
        frameDrawSeq.set(state._tid, state._seq);
        frameLastTid = state._tid;
        // A non-canvas target drawn after any canvas pass means Mojang is NOT
        // presenting to the canvas directly (title screen renders offscreen).
        frameLastIsCanvas = false;
      }
      objects.delete(passHandle);
    },

    rpSetPipeline(passHandle, pipelineHandle) {
      if (_renderCommandReplayDepth === 0) {
        markCall("rpSetPipeline", {passHandle, pipelineHandle});
      }
      const state = passState(passHandle);
      if (!pipelineHandle) {
        state.pipeline = null;
        return;
      }
      const entry = get(pipelineHandle, "pipeline");
      const passDepth = state.depthFormat || null;
      // Pick the pre-built pipeline whose depth attachment matches this pass
      // (createPipeline built both states into entry.byDepth). Direct lookup,
      // no lazy caching race. Fall back to a just-in-time build for any depth
      // format not pre-built (e.g. depth24plus-stencil8), then to the base.
      let chosen = entry.byDepth?.get(passDepth);
      let chosenDepth = passDepth;
      if (!chosen && passDepth) {
        let lazy = entry.variants?.get(passDepth);
        if (!lazy) {
          try {
            const built = buildPipeline(entry.spec, passDepth);
            if (built) {
              lazy = {pipeline: built.pipeline, depthFormat: passDepth};
              (entry.variants ??= new Map()).set(passDepth, lazy);
              recordStage(`depth-variant-ok:${entry.spec.label}:${passDepth}`);
            }
          } catch (error) {
            recordStage(`depth-variant-failed:${entry.spec.label}:${error.message}`);
          }
        }
        if (lazy) { chosen = lazy.pipeline; chosenDepth = lazy.depthFormat; }
      }
      if (!chosen) { chosen = entry.pipeline; chosenDepth = entry.depthFormat; }
      if (entry.spec && /panorama/i.test(entry.spec.label)) _panoProbe.set++;
      if (_DBG && entry.spec && /blit|interpolate|panorama|sky|stars/.test(entry.spec.label) && (_rpDbgN = (_rpDbgN || 0) + 1) <= 6) {
        console.log("[rpSetPipeline-dbg]", entry.spec.label, "passDepth=", String(passDepth),
          "byDepthKeys=", JSON.stringify([...(entry.byDepth?.keys() || [])].map((k) => String(k))),
          "variantsKeys=", JSON.stringify([...(entry.variants?.keys() || [])].map((k) => String(k))),
          "chosenDepth=", String(chosenDepth),
          "entryDepth=", String(entry.depthFormat));
      }
      if (/terrain/.test(entry.spec?.label || "")) state._sawTerrain = true;
      // Control: a pipeline whose output is visible on screen, sampled the
      // same way, so "terrain's projection is zero" can be told apart from
      // "every projection reads zero".
      if (_drawCensus && /\/(sky|celestial)$/.test(entry.spec?.label || "")) {
        state._sawVisible = true;
        const r = state.resources.get("Projection");
        if (r && !globalThis.mcWebGpu._visibleProjRange) {
          globalThis.mcWebGpu._visibleProjRange =
            {bufferHandle: r.bufferHandle, offset: r.offset, size: r.size};
        }
      }
      state.pipeline = {...entry, pipeline: chosen, depthFormat: chosenDepth};
      // ?mcweb_suppress=<regex> drops draws from matching pipelines. This is an
      // isolation tool only; the normal path renders CloudFaces through the
      // formatted-texel compatibility adapter above.
      state._suppressed = _isPipelineSuppressed(entry.spec?.label || "");
      state.pass.setPipeline(chosen);
      state.boundGroups = [];
    },

    // Renames a freshly created object's handle.
    //
    // For the OpenJDK lane, whose RPC has to answer Java before the host has
    // run the call: the client hands Java a placeholder, the host creates the
    // object under its own handle, and this moves it onto the placeholder so
    // the two are the same number from then on. Every lookup goes through the
    // one `objects` map, so nothing else needs to know. Returns false if the
    // handle names no object, which keeps a non-handle return value (a width, a
    // boolean) from renaming something at random.
    rekeyObject(from, to) {
      if (from === to) return true;
      const object = objects.get(from);
      if (object === undefined) return false;
      objects.delete(from);
      if (object && typeof object === "object") object._handle = to;
      objects.set(to, object);
      return true;
    },

    // Publishes one interned binding name. Images used to write _bindingNames
    // directly from their @JS body, which only works when the host object is
    // the host: over the OpenJDK lane's RPC proxy the write lands on a local
    // stand-in and every later rpSetUniform fails to resolve its name. Going
    // through a method means the call is forwarded like any other.
    registerBindingName(id, name) {
      (this._bindingNames ||= [])[id] = name;
    },

    // New images pass an interned integer id here.  Keep accepting a string as
    // well: staged WasmLM images are expensive to rebuild and older artifacts
    // call this same host entry point with the pre-interning String ABI.  Do not
    // index _bindingNames with that value -- a WasmLM Java String is a Proxy,
    // and coercing it to a property key re-enters the wasm string bridge until
    // the Java stack overflows.
    rpBindTexture(passHandle, nameId, viewHandle, samplerHandle) {
      const name = resolveBindingName(nameId, "texture");
      if (_renderCommandReplayDepth === 0) markCall("rpBindTexture", {passHandle, name});
      const state = passState(passHandle);
      const _viewEntry = get(viewHandle, "texture view");
      _textureLifetime.noteViewUse(viewHandle, _viewEntry);
      const texRec = {
        kind: "texture",
        view: _viewEntry.view,
        viewHandle,
        sampler: get(samplerHandle, "sampler").sampler,
        samplerHandle,
        _name: name,                  // DIAG: name Mojang bound under
        _texEntry: _viewEntry.textureEntry // DIAG: underlying texture entry (uploads/size)
      };
      state.resources.set(name, texRec);
      // Positional record too: Mojang's bindTexture name need not equal the
      // pipeline spec's sampler name (the lookup key in ensureBindGroups). When
      // the name lookup misses we fall back to bind order (Nth bind → Nth
      // sampler slot), which is name-independent and correct for the
      // single-sampler textured families (text/position_tex*/panorama).
      state.drawTexBinds.push(texRec);
    },

    rpSetUniform(passHandle, nameId, bufferHandle, offset, size) {
      const name = resolveBindingName(nameId, "uniform");
      if (_renderCommandReplayDepth === 0) markCall("rpSetUniform", {passHandle, name});
      const state = passState(passHandle);
      const _bentry = get(bufferHandle, "buffer");
      // DIAG: terrain.fsh ends with
      //   color = mix(FogColor * vec4(1,1,1,color.a), color, ChunkVisibility)
      // so ChunkVisibility == 0 paints the whole chunk in FogColor no matter
      // what the atlas sample returned -- a uniform pale-blue wash over all
      // terrain, while entity and GUI shaders (no ChunkVisibility) stay
      // correct. Capture the bytes actually bound, std140:
      //   mat4 ModelViewMat @0, float ChunkVisibility @64,
      //   ivec2 TextureSize @72, ivec3 ChunkPosition @80.
      if (name === "ChunkSection" && _bentry._shadow) {
        const ring = (globalThis.mcWebGpu._chunkSectionRing ||= []);
        if (ring.length < 40) {
          const base = (_bentry._shadow.byteOffset || 0) + offset;
          if (base + 92 <= _bentry._shadow.buffer.byteLength) {
            const f = new Float32Array(_bentry._shadow.buffer, base, 23);
            const i = new Int32Array(_bentry._shadow.buffer, base, 23);
            ring.push({
              chunkVisibility: f[16],
              textureSize: [i[18], i[19]],
              chunkPosition: [i[20], i[21], i[22]],
              // Full matrix, not just the diagonal: terrain provably does not
              // rasterise (a pass with 18 draws writes zero depth), so
              // gl_Position must be recomputed by hand from the measured
              // inputs to find which factor collapses or clips it.
              modelView: Array.from({length: 16}, (_, k) => f[k])
            });
          }
        }
      }
      state.resources.set(name, {
        kind: "uniform",
        buffer: _bentry.buffer,
        bufferHandle,
        bufferSize: _bentry.size, // full buffer size, for safe uniform-range inflate
        offset,
        size
      });
    },

    rpSetVertexBuffer(passHandle, slot, bufferHandle, offset, size) {
      const state = passState(passHandle);
      state.vertexBuffers[slot] = {buffer: get(bufferHandle, "buffer").buffer, offset, size, _handle: bufferHandle};
    },

    rpSetIndexBuffer(passHandle, bufferHandle, format) {
      const state = passState(passHandle);
      state.indexBuffer = {buffer: get(bufferHandle, "buffer").buffer, format, _handle: bufferHandle};
    },

    rpScissor(passHandle, x, y, width, height) {
      const state = passState(passHandle);
      // Bottom-left origin (Minecraft) -> top-left origin (WebGPU).
      const yy = Math.max(0, state.height - y - height);
      const ww = Math.max(1, width);
      const hh = Math.max(1, height);
      state._scissor = [x, yy, ww, hh];
      state.pass.setScissorRect(x, yy, ww, hh);
    },

    rpDisableScissor(passHandle) {
      const state = passState(passHandle);
      if (state.area) {
        state._scissor = state.area.slice();
        state.pass.setScissorRect(state.area[0], state.area[1], state.area[2], state.area[3]);
      } else {
        state._scissor = [0, 0, 32767, 32767];
        state.pass.setScissorRect(0, 0, 32767, 32767);
      }
    },

    rpCommandStreamRaw(passHandle, bytes, byteLength, end) {
      return replayRenderPassCommands(this, passHandle, bytes, byteLength, end, "raw");
    },

    rpCommandStream64(passHandle, base64, byteLength, end) {
      return replayRenderPassCommands(this, passHandle, base64, byteLength, end, "base64");
    },

    rpCommandStreamText(passHandle, text, wordCount, end) {
      return replayRenderPassCommands(
        this, passHandle, text, wordCount * 4, end, "packed-text"
      );
    },

    rpCommandStreamWasmGc(passHandle, words, wordCount, end, readWord) {
      return replayRenderPassCommands(
        this, passHandle, words, wordCount * 4, end, "wasmgc-reader", readWord
      );
    },

    drawBatchEnabled() {
      return new URLSearchParams(globalThis.location?.search || "").has("mcweb_gpu_immediate") ? 0 : 1;
    },

    rpDrawIndexedBatch(passHandle, base64, drawCount) {
      const bytes = base64ToBytes(base64);
      if (bytes.length < drawCount * 20) throw new Error("indexed draw batch is truncated");
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      for (let i = 0; i < drawCount; i++) {
        const base = i * 20;
        this.rpDrawIndexed(passHandle, view.getInt32(base, true), view.getInt32(base + 4, true),
          view.getInt32(base + 8, true), view.getInt32(base + 12, true), view.getInt32(base + 16, true));
      }
    },

    rpDrawBatch(passHandle, base64, drawCount) {
      const bytes = base64ToBytes(base64);
      if (bytes.length < drawCount * 16) throw new Error("draw batch is truncated");
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      for (let i = 0; i < drawCount; i++) {
        const base = i * 16;
        this.rpDraw(passHandle, view.getInt32(base, true), view.getInt32(base + 4, true),
          view.getInt32(base + 8, true), view.getInt32(base + 12, true));
      }
    },

    rpDraw(passHandle, firstVertex, vertexCount, instanceCount, baseInstance) {
      if (_renderCommandReplayDepth === 0) markCall("rpDraw", {passHandle, vertexCount});
      const state = passState(passHandle);
      state._drawn++;
      // ?mcweb_drawcensus=1: which pipelines actually issue draws, and into
      // which target. Chunk counters say terrain is renderable; this says
      // whether anything is drawn for it. Inert unless the flag is present.
      if (_drawCensus) _noteDrawCensus(state, vertexCount, instanceCount);
      if (!state.pipeline || state._suppressed) return;
      _noteFontDraw(state, "draw", {firstVertex, vertexCount, instanceCount});
      ensureBindGroups(state);
      state.drawTexBinds.length = 0; // binds consumed AFTER ensureBindGroups reads them
      // DIAG: one-shot animate_sprite UBO + target dump
      if (!_animDbgDone && state.pipeline.spec && /animate_sprite/.test(state.pipeline.spec.label)) {
        _animDbgDone = true;
        const uni = state.resources.get("SpriteAnimationInfo");
        let uboDump = "no SpriteAnimationInfo bound";
        if (uni && uni.kind === "uniform") {
          const be = objects.get(uni.bufferHandle);
          if (be && be._shadow) {
            const f = new Float32Array(be._shadow.buffer, uni.offset, 32);
            const i32 = new Int32Array(be._shadow.buffer, uni.offset, 35);
            uboDump = JSON.stringify({
              bufLabel: be._label, bufSize: be.size, uniOffset: uni.offset, uniSize: uni.size,
              ProjMat: Array.from(f.slice(0, 16)),
              SpriteMat: Array.from(f.slice(16, 32)),
              UPadding: f[32], VPadding: f[33], MipMapLevel: i32[34]
            });
          } else { uboDump = "buffer handle " + uni.bufferHandle + " has no shadow"; }
        }
        const spr = state.resources.get("Sprite");
        const sprInfo = spr && spr.kind === "texture" && spr._texEntry
          ? {label: spr._texEntry._label, uploads: spr._texEntry._uploads, wh: spr._texEntry.width + "x" + spr._texEntry.height}
          : "not bound or no entry";
        console.log("[ANIM-SPRITE-DIAG] target=" + state._targetLabel + " " + state._targetWH
          + " | UBO: " + uboDump
          + " | Sprite tex: " + JSON.stringify(sprInfo)
          + " | vertexCount=" + vertexCount + " firstVertex=" + firstVertex);
      }
      if (state.pipeline && state.pipeline.spec && /animate_sprite/.test(state.pipeline.spec.label) && _animRing.length < 40) {
        const _au = state.resources.get("SpriteAnimationInfo");
        let _asm = null, _apm = null;
        if (_au && _au.kind === "uniform") { const _abe = objects.get(_au.bufferHandle); if (_abe && _abe._shadow) { const _af = new Float32Array(_abe._shadow.buffer, (_abe._shadow.byteOffset || 0) + _au.offset, 32); _apm = [_af[0], _af[5]]; _asm = [_af[16], _af[21], _af[28], _af[29]]; } }
        const _asp = state.resources.get("Sprite"); const _ase = _asp && _asp.kind === "texture" && _asp._texEntry;
        _animRing.push({ tgt: state._targetLabel, tgtWH: state._targetWH, pm: _apm, sm: _asm, spr: _ase ? (_ase._label || "?").split("/").pop() : null, swh: _ase ? (_ase.width + "x" + _ase.height) : null, sup: _ase ? _ase._uploads : null });
        if (_asmBad.length < 12 && state._targetLabel && !/\/atlas\//.test(state._targetLabel)) _asmBad.push({ tgt: state._targetLabel, tgtWH: state._targetWH, pm: _apm, spr: _ase ? (_ase._label || "?").split("/").pop() : null });
      }
      if (state.pipeline && state.pipeline.spec && /animate_sprite/.test(state.pipeline.spec.label)) {
        const target = state._targetLabel || "?";
        _animTargetCounts.set(target, (_animTargetCounts.get(target) || 0) + 1);
        if (/atlas\/gui\.png/.test(target) && _guiAtlasRing.length < 40) {
          const uniform = state.resources.get("SpriteAnimationInfo");
          let projection = null;
          let spriteMatrix = null;
          if (uniform && uniform.kind === "uniform") {
            const buffer = objects.get(uniform.bufferHandle);
            if (buffer && buffer._shadow) {
              const values = new Float32Array(
                buffer._shadow.buffer,
                (buffer._shadow.byteOffset || 0) + uniform.offset,
                32
              );
              projection = [values[0], values[5]];
              spriteMatrix = [values[16], values[21], values[28], values[29]];
            }
          }
          const sprite = state.resources.get("Sprite");
          const texture = sprite && sprite.kind === "texture" && sprite._texEntry;
          _guiAtlasRing.push({
            target,
            projection,
            spriteMatrix,
            sprite: texture ? texture._label : null,
            size: texture ? `${texture.width}x${texture.height}` : null,
            uploads: texture ? texture._uploads || 0 : 0
          });
        }
      }
      _maybeTexDrawDiag(state, "rpDraw");
      _decodeGuiDraw(state, "draw", {vertexCount, firstVertex, baseVertex: 0, indexCount: 0});
      _noteScatter(state);
      applyVertexAndIndex(state);
      if (needsLowering(state)) {
        const lowered = loweredIndices(state.pipeline.spec.topology, firstVertex + vertexCount);
        if (lowered) {
          state.pass.setIndexBuffer(lowered.buffer, "uint32");
          state.pass.drawIndexed(lowered.count, Math.max(1, instanceCount), 0, 0, 0);
          return;
        }
      }
      state.pass.draw(vertexCount, Math.max(1, instanceCount), firstVertex, 0);
    },

    rpDrawIndexed(passHandle, indexCount, instanceCount, firstIndex, baseVertex, firstInstance) {
      if (_renderCommandReplayDepth === 0) {
        markCall("rpDrawIndexed", {passHandle, indexCount});
      }
      const state = passState(passHandle);
      state._drawn++;
      // Indexed draws are how world geometry actually reaches the GPU; the
      // non-indexed path above is mostly atlas blits. Census both or the
      // in-world picture looks empty for the wrong reason.
      if (_drawCensus) _noteDrawCensus(state, indexCount, instanceCount);
      // Control sample: the same uniform ranges for a pipeline that is visibly
      // rendering. "Terrain's Projection reads as zero" only means something
      // if a working draw's Projection does not.
      if (_drawCensus && !globalThis.mcWebGpu._visibleDrawSample
          && /celestial|clouds|entity_/.test(state.pipeline?.spec?.label || "")
          && state.resources.get("Projection")) {
        const r = state.resources.get("Projection");
        globalThis.mcWebGpu._visibleDrawSample = {
          label: state.pipeline.spec.label,
          projection: {bufferHandle: r.bufferHandle, offset: r.offset, size: r.size},
        };
      }
      // One-shot: remember which vertex buffer a terrain draw read, so its
      // actual bytes can be decoded later (see readTerrainVertices).
      if (!globalThis.mcWebGpu._terrainDrawSample
          && /solid_terrain/.test(state.pipeline?.spec?.label || "")
          && state.vertexBuffers[0]) {
        const vb = state.vertexBuffers[0];
        const uniformRange = (name) => {
          const r = state.resources.get(name);
          return r ? {bufferHandle: r.bufferHandle, offset: r.offset, size: r.size} : null;
        };
        globalThis.mcWebGpu._terrainDrawSample = {
          bufferHandle: vb._handle,
          offset: vb.offset,
          stride: state.pipeline.spec.vertexFormats?.[0]?.stride ?? 28,
          indexCount, baseVertex, firstIndex,
          // The transform inputs, captured at the draw that used them:
          //   pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset
          //   gl_Position = ProjMat * ModelViewMat * vec4(pos, 1)
          // Vertex data and every texture measured correct, so if terrain is
          // absent rather than black, one of these is placing it off-screen.
          vbOffset: vb.offset,
          vbSize: vb.size,
          vbBufferSize: objects.get(vb._handle)?.size ?? null,
          // The comparison that matters: a bound range of vbSize bytes at
          // stride 28 holds vbSize/28 vertices, and the draw indexes up to
          // baseVertex + (max index in the run). If that exceeds the range the
          // browser rejects the draw outright.
          vertexCapacity: Math.floor((vb.size ?? 0) / 28),
          indexBufferHandle: state.indexBuffer?._handle ?? null,
          indexFormat: state.indexBuffer?.format ?? null,
          globals: uniformRange("Globals"),
          projection: uniformRange("Projection"),
          chunkSection: uniformRange("ChunkSection")
        };
      }
      if (state._suppressed) {
        _noteCloudDraw(state,
          {indexCount, instanceCount, firstIndex, baseVertex, firstInstance}, false);
        return;
      }
      if (!state.pipeline) {
        // Silently dropped: no pipeline bound. Counted so a whole family of
        // missing geometry cannot hide behind a clean validation log.
        const census = (globalThis.mcWebGpu._drawCensus ||= {});
        const row = (census["<dropped-no-pipeline>"] ||= {draws: 0, maxVb: 0, kinds: {}, sample: null});
        row.draws++;
        return;
      }
      _noteFontDraw(state, "indexed", {indexCount, instanceCount, firstIndex, baseVertex, firstInstance});
      ensureBindGroups(state);
      state.drawTexBinds.length = 0;
      _maybeTexDrawDiag(state, "rpDrawIndexed");
      _decodeGuiDraw(state, "idx", {indexCount, firstIndex, baseVertex, firstVertex: 0});
      _noteScatter(state);
      applyVertexAndIndex(state);
      _noteDraw(state, "indexed", {indexCount, firstIndex, baseVertex});
      _noteCloudDraw(state,
        {indexCount, instanceCount, firstIndex, baseVertex, firstInstance}, true);
      state.pass.drawIndexed(indexCount, Math.max(1, instanceCount), firstIndex, baseVertex, firstInstance);
    },

    rpDrawIndirect(passHandle, bufferHandle, offset, drawCount) {
      const state = passState(passHandle);
      state._drawn++;
      if (!state.pipeline) return;
      _noteFontDraw(state, "indirect", {bufferHandle, offset, drawCount});
      ensureBindGroups(state);
      state.drawTexBinds.length = 0;
      applyVertexAndIndex(state);
      const buffer = get(bufferHandle, "buffer").buffer;
      for (let i = 0; i < drawCount; i++) {
        state.pass.drawIndirect(buffer, offset + i * 16);
      }
    },

    rpDrawIndexedIndirect(passHandle, bufferHandle, offset, drawCount) {
      const state = passState(passHandle);
      state._drawn++;
      if (!state.pipeline) return;
      _noteFontDraw(state, "indexedIndirect", {bufferHandle, offset, drawCount});
      ensureBindGroups(state);
      state.drawTexBinds.length = 0;
      applyVertexAndIndex(state);
      const buffer = get(bufferHandle, "buffer").buffer;
      for (let i = 0; i < drawCount; i++) {
        state.pass.drawIndexedIndirect(buffer, offset + i * 20);
      }
    },

    rpPushDebugGroup(passHandle, label) {
      const state = passState(passHandle);
      if (typeof state.pass.pushDebugGroup === "function") state.pass.pushDebugGroup(label);
    },

    rpPushDebugGroupId(passHandle, labelId) {
      const label = resolveBindingName(labelId, "debug group");
      const state = passState(passHandle);
      if (typeof state.pass.pushDebugGroup === "function") state.pass.pushDebugGroup(label);
    },

    rpPopDebugGroup(passHandle) {
      const state = passState(passHandle);
      if (typeof state.pass.popDebugGroup === "function") state.pass.popDebugGroup();
    },

    configureCanvas(width, height) {
      canvas.width = width;
      canvas.height = height;
      context.configure({
        device,
        format: "rgba8unorm",
        usage: GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.COPY_DST,
        alphaMode: "opaque"
      });
    },

    acquireCanvasTexture() {
      return put({kind: "texture", isCanvas: true, texture: context.getCurrentTexture(), format: "rgba8unorm", width: canvas.width, height: canvas.height, depth: 1});
    },

    present(handle) {
      markCall("present", {handle});
      _lastPresentAt = Date.now();
      _presentCount++;
      if (_drawCensus && _lastPresentAt - _presentEnterAt > 10000) {
        _presentEnterAt = _lastPresentAt;
        console.log("[draw-census] present enter #" + _presentCount);
      }
      // End of frame: the per-target draw maps are complete. Snapshot + reset.
      lastFrameDraws = frameDraws;
      frameDraws = new Map();
      lastFrameDrawSeq = frameDrawSeq;
      frameDrawSeq = new Map();
      lastFrameIsCanvas = frameLastIsCanvas;
      frameLastTid = 0; frameLastIsCanvas = false;
      // DIAG: preserve this frame's sampled set for _texDiag, then clear for next.
      _lastFrameSampled = _sampledThisFrame;
      _sampledThisFrame = new Set();
      const _qs = new URLSearchParams(location.search);
      // DIAG: with ?mcweb_texdiag, snapshot+readback the sampled textures ONCE the
      // title screen is up (frame>40). Console.table the result; no blind testing.
      if (_qs.has("mcweb_texdiag") && !_texDiagDone && (globalThis.mcWebPump?.ticks || 0) > 40 && _lastFrameSampled.size) {
        _texDiagDone = true;
        globalThis.mcWebGpu._texDiag();
      }
      // DIAG: LATE lifetime dump (frame>200): per-label total uploads + how many
      // texture INSTANCES exist per label and each one's upload count. Answers
      // "did the atlas EVER upload (just late)?" and "is the sampled atlas a
      // different instance than the uploaded one (stale handle)?".
      // Gate on a few ticks, not 200: with the per-frame blur validation spam
      // each tick takes ~6s, so 200 ticks never arrives in a probe window.
      // _sampledEntries is cumulative, so a few frames already hold the title
      // screen's textures.
      if (_qs.has("mcweb_texdiag") && !_texDiagLateDone && (globalThis.mcWebPump?.ticks || 0) >= 3) {
        _texDiagLateDone = true;
        globalThis.mcWebGpu._texDiagLate();
      }
      if (!_guiRingDumped && _guiDrawRing.length && (globalThis.mcWebPump?.ticks || 0) >= 2) {
        _guiRingDumped = true;
        console.log("[GUI-DRAW-RING] " + JSON.stringify(_guiDrawRing));
      }
      if (!_panoDumped && (globalThis.mcWebPump?.ticks || 0) >= 2) {
        _panoDumped = true;
        console.log("[PANO-PROBE] " + JSON.stringify(_panoProbe));
      }
      if (!_probe2Dumped && (globalThis.mcWebPump?.ticks || 0) >= 2) {
        _probe2Dumped = true;
        console.log("[PROBE2] animRing=" + JSON.stringify(_animRing) + " | javaErr=" + JSON.stringify(_javaErrBuf));
      }
      if (!_probe3Dumped && (globalThis.mcWebPump?.ticks || 0) >= 2) {
        _probe3Dumped = true;
        console.log("[PROBE3] asmBad=" + JSON.stringify(_asmBad) + " | scatter=" + JSON.stringify(_scatterDraws) + " | comp=" + JSON.stringify(compositeSource ? {label: compositeSource._label, wh: compositeSource.width + "x" + compositeSource.height, tid: compositeSource._tid} : null));
        // Write a human-readable verdict to the on-page div so a screenshot suffices.
        const _vLines = [];
        const _compLbl = compositeSource ? (compositeSource._label || "?") + " " + compositeSource.width + "x" + compositeSource.height : "null";
        _vLines.push("comp=" + _compLbl);
        if (_asmBad.length) {
          _vLines.push("VERDICT: atlas-assembly MIS-TARGETED (" + _asmBad.length + " draws onto non-atlas)");
          _vLines.push("  first tgt=" + (_asmBad[0].tgt || "?") + " src=" + (_asmBad[0].src || "?"));
        } else if (_scatterDraws.length) {
          _vLines.push("VERDICT: screen draws sample raw sprites (" + _scatterDraws.length + " draws)");
          _vLines.push("  first src=" + (_scatterDraws[0].src || "?") + " tgt=" + (_scatterDraws[0].tgt || "?"));
        } else {
          _vLines.push("VERDICT: asmBad=0 scatter=0 → scatter NOT from assembly or screen-draw path");
          _vLines.push("  (check composite source above; if comp=atlas → composite picks wrong texture)");
        }
        const _vd = document.getElementById("mcweb-probe-verdict");
        if (_vd) _vd.textContent = _vLines.join("\n");
      }
      const _diag = _qs.get("mcweb_diag");
      // Composite-source policy. Mojang draws the loading SPLASH straight to the
      // canvas, but renders the TITLE screen (panorama + GUI) into an offscreen
      // main target that its presenter does NOT copy to the canvas in our
      // backend — so with no composite the title goes black (the user's
      // "splash → black" report). The correct source is the target drawn LAST
      // in the frame (the GUI/title composition; the panorama atlas is drawn
      // early and samples as flat black, which is what the old max-draws pick
      // grabbed). The composite is a no-op for the splash (there the last draw
      // is the canvas itself, which we never composite), so it is safe to
      // enable by default. Overrides: ?mcweb_nocomposite (off), or
      // ?mcweb_composite=lastdraw|maxdraws|<tid>.
      const _cval = _qs.get("mcweb_composite");
      const _noComposite = _qs.has("mcweb_nocomposite");
      const _mode = !_cval ? "lastdraw" : (_cval === "maxdraws" ? "maxdraws" : (_cval === "lastdraw" ? "lastdraw" : "tid"));
      const _forceTid = _mode === "tid" ? Number(_cval) : 0;
      if (!_diag && !_noComposite && !lastFrameIsCanvas) {
        if (_forceTid) {
          const e = texInventory.find((t) => t._tid === _forceTid);
          if (e) compositeSource = e;
        } else if (_mode === "maxdraws") {
          let bestTid = 0, bestDraws = 0;
          for (const [tid, n] of lastFrameDraws) { if (n > bestDraws) { bestDraws = n; bestTid = tid; } }
          if (bestTid) {
            const e = texInventory.find((t) => t._tid === bestTid);
            if (e) compositeSource = e;
          }
        } else {
          // Composite-source selection. Mojang's frame draws the panorama + GUI
          // composition into the main target (created at exactly canvas pixel
          // size), then TextureAtlas.tick() → uploadAnimationFrames() opens a
          // render pass on the atlas (both dims power-of-two) which gets the
          // highest drawSeq.  The old naive max-seq pick grabbed the atlas and
          // stretched its packed sprites across the canvas (the scattered icon
          // grid in the broken screenshot).  Fix: prefer the target whose
          // dimensions EXACTLY match the canvas (the main target is created at
          // canvas.width × canvas.height by Mojang's presenter), then fall back
          // to aspect-ratio match excluding atlases, then plain lastdraw.
          const isPOT = (n) => n > 0 && (n & (n - 1)) === 0;
          const isAtlas = (e) => {
            if (!e || !e.width || !e.height) return true;
            // Both dims power-of-two = texture atlas (gui/widgets/blocks).
            if (isPOT(e.width) && isPOT(e.height)) return true;
            // Very wide or very tall non-screen-aspect textures (sprite sheets).
            const a = e.width / e.height;
            if (a > 3 || a < 0.33) return true;
            return false;
          };
          const canvasAspect = canvas.width / canvas.height;
          const aspectMatch = (e) => {
            if (!e || !e.width || !e.height) return false;
            const a = e.width / e.height;
            return Math.abs(a - canvasAspect) / canvasAspect < 0.15;
          };
          // Pass 1: Minecraft names the scene presenter target `Main / Color`.
          // Prefer that semantic identity before considering dimensions or draw
          // order. Portal/transparency/post processing creates FBO 0 and FBO 1
          // at the exact same canvas size; choosing the last of those is browser
          // scheduling dependent and can present a cleared black auxiliary FBO.
          let bestTid = 0, bestSeq = 0;
          for (const [tid, sq] of lastFrameDrawSeq) {
            const e = texInventory.find((t) => t._tid === tid);
            if (e && String(e._label || "").toLowerCase() === "main / color"
                && sq > bestSeq) {
              bestSeq = sq; bestTid = tid;
            }
          }
          // Pass 2: exact canvas-dimension match. This remains the generic
          // fallback for early/diagnostic images that do not name a Main target.
          if (!bestTid) {
            for (const [tid, sq] of lastFrameDrawSeq) {
              const e = texInventory.find((t) => t._tid === tid);
              if (e && e.width === canvas.width && e.height === canvas.height && sq > bestSeq) {
                bestSeq = sq; bestTid = tid;
              }
            }
          }
          // Pass 3: aspect match + not atlas.
          if (!bestTid) {
            for (const [tid, sq] of lastFrameDrawSeq) {
              const e = texInventory.find((t) => t._tid === tid);
              if (e && !isAtlas(e) && aspectMatch(e) && sq > bestSeq) { bestSeq = sq; bestTid = tid; }
            }
          }
          // Pass 4: last-drawn non-atlas (any aspect).
          if (!bestTid) {
            for (const [tid, sq] of lastFrameDrawSeq) {
              const e = texInventory.find((t) => t._tid === tid);
              if (e && !isAtlas(e) && sq > bestSeq) { bestSeq = sq; bestTid = tid; }
            }
          }
          // Pass 5: plain lastdraw (fallback).
          if (!bestTid) {
            for (const [tid, sq] of lastFrameDrawSeq) { if (sq > bestSeq) { bestSeq = sq; bestTid = tid; } }
          }
          if (bestTid) {
            const e = texInventory.find((t) => t._tid === bestTid);
            if (e) compositeSource = e;
          }
        }
      }
      // Composite the scene main target to the swap-chain canvas: Mojang's 26.2
      // presenter acquires + presents the canvas texture but never draws or
      // copies into it, so without this the canvas stays empty. The canvas
      // texture is still the current one here (valid until context.present()).
      // Under ?mcweb_drawcensus the census already says which pipelines drew
      // into which target; pair it with what the composite actually put on the
      // canvas, or "terrain drew but nothing appeared" stays ambiguous.
      if (_drawCensus) {
        const now = Date.now();
        if (now - _compositeLogAt > 10000) {
          _compositeLogAt = now;
          console.log("[draw-census] composite <- "
            + (compositeSource ? (compositeSource._label || "?") + " "
                + compositeSource.width + "x" + compositeSource.height
                + " tid=" + compositeSource._tid : "null")
            + " noComposite=" + _noComposite + " lastFrameIsCanvas=" + lastFrameIsCanvas);
        }
      }
      if (!_noComposite && compositeSource && compositeSource.texture) {
        try {
          const canvasTexture = context.getCurrentTexture();
          const enc = device.createCommandEncoder({label: "mcweb-composite-encoder"});
          // ?mcweb_diag=magenta → clear the canvas to magenta and draw nothing.
          // If magenta appears on screen, this pass reaches the presented
          // drawable (so a black screen means the SOURCE is empty/wrong); if
          // the screen stays black, this pass isn't being presented at all.
          const diag = _diag;
          const clear = diag === "magenta" ? {r: 1, g: 0, b: 1, a: 1} : {r: 0, g: 0, b: 0, a: 1};
          const pass = enc.beginRenderPass({colorAttachments: [{
            view: canvasTexture.createView(),
            loadOp: "clear", storeOp: "store", clearValue: clear
          }]});
          if (diag !== "magenta") {
            const pipeline = ensureComposite(canvas.height);
            // ?mcweb_diag=checker → sample a known-good in-page texture instead
            // of the tracked scene target. Checker on screen ⇒ sampling works
            // and the tracked source is the problem; black ⇒ sampling is broken.
            const srcTex = diag === "checker" ? ensureDiagChecker() : compositeSource.texture;
            const bg = device.createBindGroup({layout: pipeline.getBindGroupLayout(0), entries: [
              {binding: 0, resource: compositeSampler},
              {binding: 1, resource: srcTex.createView()}
            ]});
            pass.setPipeline(pipeline);
            pass.setBindGroup(0, bg);
            pass.draw(3);
          }
          pass.end();
          device.queue.submit([enc.finish()]);
          flushGraveyard();
          markCall("composite", {source: compositeSource.width + "x" + compositeSource.height, area: compositeSource._area, diag: diag || "blit"});
        } catch (error) {
          markCall("composite-failed", {error: String(error).slice(0, 120)});
          if (_drawCensus) console.log("[draw-census] composite FAILED " + String(error).slice(0, 160));
        }
      }
      if (_drawCensus && Date.now() - _presentExitAt > 10000) {
        _presentExitAt = Date.now();
        console.log("[draw-census] present exit #" + _presentCount);
        // Only the passes that touched the composited target matter here.
        const main = _framePassTrace
          .filter((entry) => /main \/ color/i.test(entry.target))
          .map((entry) => (entry.clear ? "CLEAR" : "load") + "/d=" + entry.depth
            + "/sc=" + (entry.scissor ?? "-") + "/ar=" + (entry.area ?? "-")
            + ":" + entry.draws + "[" + [...(entry.pipes || [])].join(",") + "]");
        console.log("[draw-census] frame passes on Main/Color: " + main.slice(0, 8).join(" -> "));
        // The question is what runs *after* terrain in the same target.
        const terrainAt = main.findLastIndex((entry) => /terrain/.test(entry));
        // Which depth clear values the frame used at all. Vanilla clears the
        // world depth before terrain; if only GUI's clear-to-0 ever appears,
        // terrain is depth-testing against a buffer that says "nearest".
        // One-shot: read back what a terrain draw actually fed the GPU. If the
        // positions are degenerate or absurd, the geometry never had a chance
        // of landing on screen and nothing downstream matters.
        // Did terrain rasterise at all? The pass clears depth to 0 and terrain
        // writes depth, so a non-zero texel is geometry that passed the depth
        // test and wrote. All-zero means nothing rasterised, whatever the
        // colour buffer shows - which separates "geometry never landed" from
        // "geometry landed and was not shaded/blended into view".
        if (!_terrainDepthDumped && _depthSnapshot) {
          _terrainDepthDumped = true;
          Promise.resolve(globalThis.mcWebGpu.readTerrainPassDepth()).then((result) => {
            console.log("[draw-census] terrain pass depth " + JSON.stringify(result));
          }).catch((error) => {
            console.log("[draw-census] terrain depth failed " + String(error).slice(0, 120));
          });
        }
        if (!_uniformSnapshotDumped && _uniformSnapshot && !_uniformSnapshot.error) {
          _uniformSnapshotDumped = true;
          const snapshot = _uniformSnapshot;
          snapshot.buffer.mapAsync(GPUMapMode.READ).then(() => {
            const floats = new Float32Array(snapshot.buffer.getMappedRange().slice(0));
            snapshot.buffer.unmap();
            console.log("[draw-census] PROJECTION at terrain pass (drawn="
              + snapshot.drawn + ") "
              + JSON.stringify(Array.from(floats).map((v) => Number(v.toFixed(4)))));
          }).catch((error) => {
            console.log("[draw-census] projection snapshot failed "
              + String(error).slice(0, 120));
          });
        }
        if (!_visibleSnapshotDumped && _visibleUniformSnapshot
            && !_visibleUniformSnapshot.error) {
          _visibleSnapshotDumped = true;
          const snapshot = _visibleUniformSnapshot;
          snapshot.buffer.mapAsync(GPUMapMode.READ).then(() => {
            const floats = new Float32Array(snapshot.buffer.getMappedRange().slice(0));
            snapshot.buffer.unmap();
            console.log("[draw-census] PROJECTION at sky pass (drawn="
              + snapshot.drawn + ") "
              + JSON.stringify(Array.from(floats).map((v) => Number(v.toFixed(4)))));
          }).catch(() => {});
        }
        // With ?mcweb_gpu_probe the host records the floats of every UBO-sized
        // write keyed by label#handle. Pair that with the handle the terrain
        // draw actually bound: if a good matrix is written to one instance and
        // the draw binds another, the draw sees a freshly-zeroed buffer.
        if (!_writeProbeDumped && globalThis.mcWebGpu._smallBufferWrites
            && globalThis.mcWebGpu._terrainDrawSample?.projection) {
          _writeProbeDumped = true;
          const bound = globalThis.mcWebGpu._terrainDrawSample.projection;
          console.log("[draw-census] terrain binds Projection handle="
            + bound.bufferHandle + " offset=" + bound.offset + " size=" + bound.size);
          const history = globalThis.mcWebGpu._smallBufferWriteHistory || {};
          for (const [key, list] of Object.entries(history)) {
            if (!Array.isArray(list) || !/level/i.test(key)) continue;
            list.forEach((r, i) => {
              console.log("[draw-census] history " + key + " #" + i
                + " seq=" + r.seq + " f=" + JSON.stringify(r.floats.slice(0, 6)));
            });
          }
          for (const [key, value] of Object.entries(globalThis.mcWebGpu._smallBufferWrites)) {
            if (!/Proj|Dynamic|Transform|Globals/i.test(key)) continue;
            console.log("[draw-census] write " + key + " @" + value.offset
              + " f=" + JSON.stringify(value.floats.slice(0, 16)));
            // Ints too: garbage floats that are a byte-shifted or int-encoded
            // view of a sane matrix show up clearly in the raw words.
            console.log("[draw-census] write " + key + " i="
              + JSON.stringify(value.ints.slice(0, 16)));
          }
        }
        if (!_terrainUniformDumped && globalThis.mcWebGpu._terrainDrawSample) {
          _terrainUniformDumped = true;
          for (const which of ["projection", "globals", "chunkSection"]) {
            Promise.resolve(globalThis.mcWebGpu.readTerrainUniform(which)).then((r) => {
              console.log("[draw-census] uniform " + which + " "
                + (r.error ? r.error : JSON.stringify(r.floats)));
            }).catch(() => {});
          }
          Promise.resolve(globalThis.mcWebGpu.readTerrainUniform("projection", "visible"))
            .then((r) => {
              console.log("[draw-census] uniform CONTROL projection ("
                + (globalThis.mcWebGpu._visibleDrawSample?.label || "?") + ") "
                + (r.error ? r.error : JSON.stringify(r.floats)));
            }).catch(() => {});
        }
        if (!_terrainVertsDumped && globalThis.mcWebGpu._terrainDrawSample) {
          _terrainVertsDumped = true;
          Promise.resolve(globalThis.mcWebGpu.readTerrainVertices()).then((result) => {
            if (!result || result.error) {
              console.log("[draw-census] terrain verts: " + (result && result.error));
              return;
            }
            console.log("[draw-census] terrain rec "
              + JSON.stringify(result.rec).slice(0, 900));
            for (const vertex of result.vertices.slice(0, 4)) {
              console.log("[draw-census] vert pos=" + JSON.stringify(vertex.position)
                + " col=" + JSON.stringify(vertex.color)
                + " uv0=" + JSON.stringify(vertex.uv0));
            }
          }).catch((error) => {
            console.log("[draw-census] terrain verts failed " + String(error).slice(0, 120));
          });
        }
        // Copies are invisible to a draw census: copyTextureToTexture into the
        // composited target after the terrain pass would erase terrain without
        // appearing as a pass or a draw anywhere above.
        if (compositeSource) {
          console.log("[draw-census] target tid=" + compositeSource._tid
            + " copiesIn=" + (compositeSource._copiesIn || 0)
            + " copiesOut=" + (compositeSource._copiesOut || 0)
            + " uploads=" + (compositeSource._uploads || 0));
        }
        console.log("[draw-census] depthClears="
          + JSON.stringify(globalThis.mcWebGpu._depthClears || {}));
        if (terrainAt >= 0) {
          console.log("[draw-census] terrainPass=" + main[terrainAt]);
        }
        console.log("[draw-census] passes=" + main.length + " lastTerrainPass=" + terrainAt
          + " after=" + (terrainAt < 0 ? "n/a" : main.slice(terrainAt + 1).join(" -> ").slice(0, 400)));
        // Per-target draws for the frame being presented right now. The
        // aggregate census covers ten seconds; if terrain only draws in frames
        // that are never presented, these two disagree and that is the answer.
        const perTarget = [];
        for (const [tid, n] of lastFrameDraws) {
          const e = texInventory.find((t) => t._tid === tid);
          perTarget.push((e ? (e._label || "?") + " " + e.width + "x" + e.height : "tid" + tid)
            + "=" + n);
        }
        console.log("[draw-census] presented frame draws: " + (perTarget.join(", ") || "none"));
      }
      if (_drawCensus) _framePassTrace.length = 0;
      if (_presentCount === 1) markPhase("first-frame-presented");
      objects.delete(handle);
    },

    reportSuccess(argb, backend, path) {
      const hex = (argb >>> 0).toString(16).padStart(8, "0").toUpperCase();
      setText("jar-status", "executed from minecraft-26.2-client.jar");
      setText("jar-method", path);
      setText("jar-result", `0x${hex}`);
      setText("gpu-status", `${backend} canvas presented`);
      document.documentElement.style.setProperty(
        "--jar-color",
        `rgb(${(argb >>> 16) & 255} ${(argb >>> 8) & 255} ${argb & 255})`
      );
      document.body.dataset.ready = "true";
    },

    reportReloadProbe(event, source = "main") {
      const text = String(event);
      const entries = diagnostics.reloadProbe;
      entries.push({
        at: Math.round(performance.now()),
        source: String(source),
        event: text,
      });
      if (entries.length > 8000) entries.splice(0, entries.length - 8000);
      // The reload probe is most useful precisely when the main thread is stuck
      // inside the synchronous Create World barrier. Keep the last few events in
      // the out-of-thread watchdog ring as well; the page-side array above cannot
      // be read while that renderer is frozen. Do not route through reportProgress:
      // this path can be hot during the reload and must remain shared-memory-only.
      globalThis.mcWebThreadRuntime?.diag?.("reload " + text);
      // Keep a compact copy in CDP for the healthy portion of a run. The full
      // reload stream is intentionally not logged here (it is thousands of task
      // events and would overflow the console pipe); these transition records are
      // the ones that identify a barrier that never completes.
      if (/event=(begin|snapshot|all-done|listener-(complete|create-failed)|barrier-(wait|submit-main|main-ran|main-failed|ready-for-apply)|task-failed)/.test(text)) {
        console.log("[MC-RELOAD]", text);
      }
    },

    reportProgress(stage) {
      /*
       * Telemetry markers take the cheap path: the ring only.
       *
       * This method used to do the same seven things for every marker — a
       * console line, a beacon write, an object allocation, a markCall, and TWO
       * DOM writes — and gameplay emits ~230 markers a second, dominated by
       * per-packet `evt:` ones. Measured at 35.76 us each, that is ~500 ms of
       * every minute spent describing the frame rather than drawing it, and it
       * gets far worse with DevTools open, which is exactly when someone is
       * looking. The <dd> elements those DOM writes feed sit behind the canvas
       * and nobody reads them mid-game.
       *
       * The ring is what the tests and probes actually read, so it is kept
       * exact. `?mcweb_log_all=1` restores the full treatment for debugging.
       */
      if (!_logAllStages && typeof stage === "string" && _CHATTY_STAGE.test(stage)) {
        globalThis.__mcWebLastStage = stage;
        recordStage(stage);
        return;
      }
      console.log("[MC-INIT]", stage);
      /*
       * Mirror into shared linear memory, where a Worker can still read it after this
       * thread stops returning. Everything else in this method — the console line, the
       * stage ring, `__mcWebLastStage` — lives on the main thread and disappears with
       * it, which is why a world-load hang has never had a last known position.
       */
      globalThis.mcWebThreadRuntime?.beacon?.(0, stage);
      // Web Image strips *Java* stack traces (getStackTrace() is always empty),
      // but every @JS bridge call crosses into JavaScript, so the JS/wasm stack
      // at the boundary is still available -- and under WasmGC it carries wasm
      // frames. BrowserNativeMemory already reports each large allocation
      // through here, so capturing a stack at that moment attributes the block
      // to whatever asked for it, with no image rebuild.
      if (typeof stage === "string" && stage.startsWith("native:large-alloc")) {
        const sink = (globalThis.mcWebGpu._allocStacks ||= []);
        if (sink.length < 12) {
          sink.push({stage, stack: String(new Error("alloc").stack || "").split("\n").slice(1, 40)});
        }
      }
      // Read by tests/world-create.spec.ts, tools/menu-clickthrough.mjs,
      // tools/t0-probe.mjs and tools/browser-check.mjs. It was never assigned,
      // so every screen wait in those tools silently saw "" and fell through to
      // its timeout.
      globalThis.__mcWebLastStage = stage;
      recordStage(stage);
      markCall("reportProgress", {stage});
      setText("jar-status", "running minecraft-26.2-client.jar");
      setText("jar-method", stage);
    },

    /**
     * High-frequency probe channel: shared memory only, no console and no stage ring.
     *
     * Callers are spin loops that run while this thread is *not* returning to the
     * browser, so everything `reportProgress` also does — the console line, the stage
     * ring, the DOM text — is both unreachable and, at this rate, ruinous. The beacon
     * write is two atomics and a bounded byte copy, which is affordable inside a wait.
     */
    reportDiag(text) {
      globalThis.mcWebThreadRuntime?.diag?.(text);
      if (String(text ?? "").startsWith("worldgen:step-failure n=")) {
        globalThis.mcWebThreadRuntime?.stickyDiag?.(text);
      }
    },

    // DIAG: float contents of the small uniform buffers that drive fog and the
    // global render settings. A frame that is uniformly fog-coloured -- no
    // sky/ground split -- while terrain issues thousands of valid draws points
    // at the fog term saturating, not at missing geometry.
    dumpUniforms() {
      const out = {};
      for (const [handle, entry] of objects) {
        if (entry?.kind !== "buffer" || !entry._shadow) continue;
        const label = entry._label || String(handle);
        // All small buffers, not just fog: the terrain transform (projection,
        // model-view, per-section offset) lives in one of these, and a section
        // offset that never reaches the shader would draw every chunk at the
        // world origin -- hundreds of blocks from the player, so off-screen,
        // with entirely valid draw calls.
        if (entry.size > 4096) continue;
        const n = Math.min(entry._shadow.byteLength >> 2, 24);
        out[label] = {
          size: entry.size,
          floats: Array.from(new Float32Array(entry._shadow.buffer, entry._shadow.byteOffset || 0, n))
        };
      }
      return out;
    },

    // DIAG: every texture with its upload count. An atlas at 0 uploads never
    // received pixels; a non-zero count means the bytes arrived and any
    // blankness is downstream (blit, sampler, or the sprite UV lookup).
    /**
     * Every texture object matching `pattern`, with its handle and no top-N
     * slicing. dumpTextures() dedups and truncates, which hides the case where
     * several objects share a label (four are labelled blocks.png) and the one
     * being uploaded into is not the one a draw binds.
     */
    /**
     * Async readback of a small texture, for the two multipliers that can
     * saturate terrain to white. Returns a promise of RGBA rows. Only sane for
     * tiny textures -- the lightmap is 16x16.
     */
    async readSmallTexture(pattern) {
      const re = new RegExp(pattern, "i");
      let found = null;
      for (const [, entry] of objects) {
        if (entry?.kind === "texture" && re.test(String(entry._label ?? ""))) { found = entry; break; }
      }
      if (!found) return {error: "no texture matches " + pattern};
      const w = found.width, h = found.height;
      const bytesPerRow = Math.ceil(w * 4 / 256) * 256;
      const readback = device.createBuffer({
        size: bytesPerRow * h,
        usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ
      });
      const encoder = device.createCommandEncoder();
      encoder.copyTextureToBuffer(
        {texture: found.texture},
        {buffer: readback, bytesPerRow, rowsPerImage: h},
        {width: w, height: h, depthOrArrayLayers: 1}
      );
      device.queue.submit([encoder.finish()]);
      await readback.mapAsync(GPUMapMode.READ);
      const bytes = new Uint8Array(readback.getMappedRange().slice(0));
      readback.unmap();
      readback.destroy();
      // Sample a coarse grid across the whole texture, so this works for a
      // 2048x2048 atlas as well as a 16x16 lightmap.
      const stepX = Math.max(1, Math.floor(w / 16));
      const stepY = Math.max(1, Math.floor(h / 16));
      const rows = [];
      const histogram = new Map();
      for (let y = 0; y < h; y += stepY) {
        const row = [];
        for (let x = 0; x < w; x += stepX) {
          const o = y * bytesPerRow + x * 4;
          row.push([bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3]]);
        }
        rows.push(row);
      }
      for (let y = 0; y < h; y += 4) {
        for (let x = 0; x < w; x += 4) {
          const o = y * bytesPerRow + x * 4;
          const key = `${bytes[o]},${bytes[o + 1]},${bytes[o + 2]},${bytes[o + 3]}`;
          histogram.set(key, (histogram.get(key) ?? 0) + 1);
        }
      }
      return {
        label: found._label,
        wh: `${w}x${h}`,
        rows,
        top: [...histogram.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6)
      };
    },

    /**
     * Decode the vertices a terrain draw actually consumed. The section uber
     * buffers are far above the CPU-shadow limit, so this reads the real GPU
     * buffer back rather than a mirror -- the mirror does not exist for them,
     * and the port's own readback path returns zeros for GPU-written buffers.
     */
    /** Read back any recorded uniform range as floats and ints. */
    async readUniformRange(which) {
      const rec = globalThis.mcWebGpu._terrainDrawSample?.[which];
      if (!rec) return {error: "no range for " + which};
      const entry = objects.get(rec.bufferHandle);
      if (!entry) return {error: "buffer gone"};
      const size = Math.ceil(Math.min(rec.size || 256, entry.size - rec.offset) / 4) * 4;
      if (size <= 0) return {error: "empty range", rec};
      const readback = device.createBuffer({
        size, usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ
      });
      const encoder = device.createCommandEncoder();
      encoder.copyBufferToBuffer(entry.buffer, rec.offset, readback, 0, size);
      device.queue.submit([encoder.finish()]);
      await readback.mapAsync(GPUMapMode.READ);
      const raw = readback.getMappedRange().slice(0);
      readback.unmap();
      readback.destroy();
      return {
        rec,
        floats: Array.from(new Float32Array(raw)).map((x) => Math.round(x * 10000) / 10000),
        ints: Array.from(new Int32Array(raw))
      };
    },

    /**
     * The shared sequential quad-index buffer a terrain draw used. All-zero
     * indices make every triangle degenerate: no fragments, from draw calls
     * that validate cleanly and carry correct vertices and uniforms.
     */
    /**
     * Depth histogram. Colour-independent answer to "did terrain rasterise?":
     * the pass clears depth to 0 and terrain writes depth, so any texel that is
     * not 0 is geometry that passed the depth test and wrote. All-zero means
     * nothing ever rasterised, whatever the colour buffer shows.
     */
    /** The depth snapshot taken the instant a terrain pass ended. */
    async readTerrainPassDepth() {
      if (!_depthSnapshot) return {error: "no terrain pass depth captured"};
      if (_depthSnapshot.error) return _depthSnapshot;
      await _depthSnapshot.buffer.mapAsync(GPUMapMode.READ);
      const raw = _depthSnapshot.buffer.getMappedRange().slice(0);
      _depthSnapshot.buffer.unmap();
      const floats = new Float32Array(raw);
      const perRow = _depthSnapshot.bytesPerRow / 4;
      let zero = 0, nonZero = 0, min = Infinity, max = -Infinity;
      for (let y = 0; y < _depthSnapshot.height; y += 2) {
        for (let x = 0; x < _depthSnapshot.width; x += 2) {
          const d = floats[y * perRow + x];
          if (d === 0) zero++; else { nonZero++; if (d < min) min = d; if (d > max) max = d; }
        }
      }
      return {label: _depthSnapshot.label, drawn: _depthSnapshot.drawn,
              tick: _depthSnapshot.tick, zero, nonZero,
              min: nonZero ? min : null, max: nonZero ? max : null};
    },

    async readDepthHistogram() {
      let found = null;
      for (const [, entry] of objects) {
        if (entry?.kind === "texture" && /depth/i.test(String(entry._label ?? ""))) {
          if (!found || entry.width > found.width) found = entry;
        }
      }
      if (!found) return {error: "no depth texture"};
      const w = found.width, h = found.height;
      const bytesPerRow = Math.ceil(w * 4 / 256) * 256;
      const readback = device.createBuffer({
        size: bytesPerRow * h,
        usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ
      });
      const encoder = device.createCommandEncoder();
      encoder.copyTextureToBuffer(
        {texture: found.texture, aspect: "depth-only"},
        {buffer: readback, bytesPerRow, rowsPerImage: h},
        {width: w, height: h, depthOrArrayLayers: 1}
      );
      device.queue.submit([encoder.finish()]);
      await readback.mapAsync(GPUMapMode.READ);
      const raw = readback.getMappedRange().slice(0);
      readback.unmap();
      readback.destroy();
      const floats = new Float32Array(raw);
      let zero = 0, nonZero = 0, min = Infinity, max = -Infinity;
      const perRow = bytesPerRow / 4;
      for (let y = 0; y < h; y += 2) {
        for (let x = 0; x < w; x += 2) {
          const d = floats[y * perRow + x];
          if (d === 0) zero++; else { nonZero++; if (d < min) min = d; if (d > max) max = d; }
        }
      }
      return {
        label: found._label, wh: `${w}x${h}`, format: found.format,
        zero, nonZero,
        min: nonZero ? min : null, max: nonZero ? max : null
      };
    },

    async readTerrainIndices() {
      const rec = globalThis.mcWebGpu._terrainDrawSample;
      if (!rec?.indexBufferHandle) return {error: "no index buffer captured", rec};
      const entry = objects.get(rec.indexBufferHandle);
      if (!entry) return {error: "index buffer gone"};
      const wide = rec.indexFormat !== "uint16";
      const stride = wide ? 4 : 2;
      const first = rec.firstIndex * stride;
      const size = Math.min(48 * stride, entry.size - first);
      if (size <= 0) return {error: "range past end", rec};
      const readback = device.createBuffer({
        size: Math.ceil(size / 4) * 4,
        usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ
      });
      const encoder = device.createCommandEncoder();
      encoder.copyBufferToBuffer(entry.buffer, first, readback, 0, Math.ceil(size / 4) * 4);
      device.queue.submit([encoder.finish()]);
      await readback.mapAsync(GPUMapMode.READ);
      const raw = readback.getMappedRange().slice(0);
      readback.unmap();
      readback.destroy();
      const values = Array.from(wide ? new Uint32Array(raw) : new Uint16Array(raw));
      return {
        label: entry._label,
        bufferSize: entry.size,
        format: rec.indexFormat,
        indexCount: rec.indexCount,
        firstIndex: rec.firstIndex,
        baseVertex: rec.baseVertex,
        first48: values.slice(0, 48)
      };
    },

    /**
     * The transform the GPU actually saw, read back from the uniform buffer
     * rather than from a host-side shadow copy. A shadow can be stale or
     * mis-offset; this cannot.
     */
    async readTerrainUniform(name, from) {
      const rec = from === "visible"
        ? globalThis.mcWebGpu._visibleDrawSample
        : globalThis.mcWebGpu._terrainDrawSample;
      const range = rec && rec[name];
      if (!range) return {error: "no " + name + " range captured"};
      const entry = objects.get(range.bufferHandle);
      if (!entry) return {error: name + " buffer gone"};
      const size = Math.min(64, range.size || 64);
      const readback = device.createBuffer({
        size: Math.ceil(size / 4) * 4,
        usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ
      });
      try {
        const encoder = device.createCommandEncoder();
        encoder.copyBufferToBuffer(entry.buffer, range.offset, readback, 0,
          Math.ceil(size / 4) * 4);
        device.queue.submit([encoder.finish()]);
        await readback.mapAsync(GPUMapMode.READ);
        const floats = new Float32Array(readback.getMappedRange().slice(0));
        readback.unmap();
        readback.destroy();
        return {name, floats: Array.from(floats).map((v) => Number(v.toFixed(4)))};
      } catch (error) {
        try { readback.destroy(); } catch (ignored) { /* best effort */ }
        return {error: String(error).slice(0, 160)};
      }
    },

    async readTerrainVertices() {
      const rec = globalThis.mcWebGpu._terrainDrawSample;
      if (!rec) return {error: "no terrain draw captured"};
      const entry = objects.get(rec.bufferHandle);
      if (!entry) return {error: "vertex buffer gone"};
      const count = 8;
      // Decode the vertices the draw actually used. An indexed draw starts at
      // baseVertex, so reading from the binding offset alone decodes whatever
      // happens to sit at the start of a 16 MB shared buffer - which looks
      // like garbage and says nothing about the geometry that was drawn.
      const start = (rec.offset || 0) + (rec.baseVertex || 0) * rec.stride;
      const size = Math.min(rec.stride * count, entry.size - start);
      if (size <= 0) return {error: "offset past end", rec, start};
      const readback = device.createBuffer({
        size: Math.ceil(size / 4) * 4,
        usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ
      });
      const encoder = device.createCommandEncoder();
      encoder.copyBufferToBuffer(entry.buffer, start, readback, 0, Math.ceil(size / 4) * 4);
      device.queue.submit([encoder.finish()]);
      await readback.mapAsync(GPUMapMode.READ);
      const raw = readback.getMappedRange().slice(0);
      readback.unmap();
      readback.destroy();
      const view = new DataView(raw);
      const bytes = new Uint8Array(raw);
      const out = [];
      for (let v = 0; v * rec.stride + 28 <= raw.byteLength; v++) {
        const base = v * rec.stride;
        out.push({
          position: [view.getFloat32(base, true), view.getFloat32(base + 4, true), view.getFloat32(base + 8, true)],
          color: [bytes[base + 12], bytes[base + 13], bytes[base + 14], bytes[base + 15]],
          uv0: [view.getFloat32(base + 16, true), view.getFloat32(base + 20, true)],
          uv2: [view.getInt16(base + 24, true), view.getInt16(base + 26, true)]
        });
      }
      return {rec, start, vertices: out};
    },

    dumpTexturesMatching(pattern) {
      const re = new RegExp(pattern, "i");
      const out = [];
      for (const [handle, entry] of objects) {
        if (entry?.kind !== "texture") continue;
        const label = String(entry._label ?? "?");
        if (!re.test(label)) continue;
        out.push({
          handle,
          label: label.slice(0, 46),
          wh: `${entry.width}x${entry.height}`,
          mipLevels: entry.texture?.mipLevelCount ?? null,
          uploads: entry._uploads || 0,
          copiesIn: entry._copiesIn || 0,
          copiesOut: entry._copiesOut || 0
        });
      }
      return out;
    },

    dumpTextures() {
      const out = [];
      for (const [, entry] of objects) {
        if (entry?.kind !== "texture") continue;
        out.push({
          label: (entry._label || "?").slice(0, 46),
          wh: `${entry.width}x${entry.height}`,
          uploads: entry._uploads || 0,
          // An atlas is stitched by blitting each sprite in, so writeTexture64
          // uploads can legitimately be 0 while the texture is still fully
          // populated. copiesIn is what distinguishes "assembled by blit" from
          // "never filled at all".
          copiesIn: entry._copiesIn || 0
        });
      }
      return out.filter((t) => /atlas|lightmap|font|gui/i.test(t.label))
                .sort((a, b) => (b.copiesIn + b.uploads) - (a.copiesIn + a.uploads))
                .slice(0, 25);
    },

    /** Raw frame/stutter census used by the on-screen graph, without the
     *  method-wrapping overhead of the full performance profiler. */
    /** Bind-group cache effectiveness: hit/miss since boot, and live size. */
    bindGroupStats() {
      return {..._bindGroupStats, size: _bindGroupCache.size};
    },

    frameGraphReport() {
      return _frameGraph.report();
    },

    /** Start a clean raw measurement window after boot/join has settled. */
    frameGraphReset() {
      _frameGraph.reset();
      return true;
    },

    /**
     * Real cloud-path evidence used by the localhost visual gate. Suppression
     * is opt-in and diagnostic-only: normal Minecraft rendering never calls
     * this method. Toggling the existing pipeline filter in the same live world
     * gives the pixel test a causal clouds-on / clouds-off comparison without
     * changing the camera, weather, world, or Minecraft's cloud settings.
     */
    setDiagnosticPipelineSuppression(pattern) {
      const raw = String(pattern ?? "").trim();
      if (raw.length > 128) throw new Error("diagnostic pipeline pattern is too long");
      _diagnosticSuppression = raw ? new RegExp(raw, "i") : null;
      return _diagnosticSuppression ? String(_diagnosticSuppression) : null;
    },

    cloudDrawReset() {
      delete globalThis.mcWebGpu._cloudDraws;
      return true;
    },

    cloudDrawReport() {
      return {
        draws: globalThis.mcWebGpu._cloudDraws ?? null,
        validationErrors: (globalThis.mcWebGpu._valErrors ?? []).slice(-10),
        firstGpuError: globalThis.mcWebGpu._firstGpuError ?? null
      };
    },

    /**
     * Frame-time and bridge-cost census. `sinceFrame` drops the boot and
     * world-load frames, which are enormous and would otherwise dominate every
     * average; pass the tick at which the world became steady.
     */
    perfReport(sinceFrame = 0) {
      if (!_perf.on) return {error: "profiler off; load with ?mcweb_perf=1"};
      const frames = _perf.frames.slice(sinceFrame);
      const hostFrames = _perf.hostFrames.slice(sinceFrame);
      const callFrames = _perf.callFrames.slice(sinceFrame);
      const rafGaps = _perf.rafGaps.slice(sinceFrame);
      if (!frames.length) return {error: "no frames recorded"};
      const sorted = [...frames].sort((a, b) => a - b);
      const sortedGaps = [...rafGaps].sort((a, b) => a - b);
      const gpuQueueMs = _perf.gpuQueueMs.slice();
      const sortedGpu = [...gpuQueueMs].sort((a, b) => a - b);
      const sum = (xs) => xs.reduce((a, b) => a + b, 0);
      const totalMs = sum(frames);
      const hostMs = sum(hostFrames);
      const round = (x) => Math.round(x * 100) / 100;
      return {
        frames: frames.length,
        fps: round(1000 / (totalMs / frames.length)),
        frameMs: {
          mean: round(totalMs / frames.length),
          p50: round(_percentile(sorted, 0.5)),
          p95: round(_percentile(sorted, 0.95)),
          p99: round(_percentile(sorted, 0.99)),
          max: round(sorted[sorted.length - 1])
        },
        rafGapMs: {
          mean: round(sum(rafGaps) / Math.max(1, rafGaps.length)),
          p50: round(_percentile(sortedGaps, 0.5)),
          p95: round(_percentile(sortedGaps, 0.95)),
          p99: round(_percentile(sortedGaps, 0.99)),
          max: round(sortedGaps.length ? sortedGaps[sortedGaps.length - 1] : 0),
          over50: rafGaps.filter((x) => x > 50).length,
          over100: rafGaps.filter((x) => x > 100).length,
          over250: rafGaps.filter((x) => x > 250).length
        },
        gpuQueue: {
          timing: "coarse queue.submit -> onSubmittedWorkDone (asynchronous)",
          submits: _perf.gpuSubmits,
          samples: gpuQueueMs.length,
          mean: round(sum(gpuQueueMs) / Math.max(1, gpuQueueMs.length)),
          p50: round(_percentile(sortedGpu, 0.5)),
          p95: round(_percentile(sortedGpu, 0.95)),
          max: round(sortedGpu.length ? sortedGpu[sortedGpu.length - 1] : 0),
          timestampQuerySupported: Boolean(globalThis.mcWebGpu?._gpuTiming?.timestampQuery)
        },
        // The split that decides where optimisation effort belongs.
        hostMsPerFrame: round(hostMs / frames.length),
        javaMsPerFrame: round((totalMs - hostMs) / frames.length),
        hostSharePct: round((hostMs / totalMs) * 100),
        bridgeCallsPerFrame: Math.round(sum(callFrames) / frames.length),
        // Whole-run totals, ranked by cost rather than by count -- the two
        // orders disagree, and the cheap-but-numerous calls are the trap.
        topCallsByMs: [..._perf.calls.entries()]
          .sort((a, b) => b[1].ms - a[1].ms)
          .slice(0, 18)
          .map(([name, e]) => ({
            name,
            ms: Math.round(e.ms),
            n: e.n,
            usPerCall: round((e.ms / e.n) * 1000)
          }))
      };
    },

    /**
     * Raw per-frame total/host millisecond arrays for the probe harnesses that
     * need the distribution rather than perfReport's summary (long-frame
     * host-vs-Java split). Read-only; returns copies.
     */
    perfFrames(sinceFrame = 0) {
      if (!_perf.on) return {error: "profiler off; load with ?mcweb_perf=1"};
      return {
        frames: _perf.frames.slice(sinceFrame),
        hostFrames: _perf.hostFrames.slice(sinceFrame),
        uploadBytes: _perf.uploadBytesFrames.slice(sinceFrame),
        slowFrames: _perf.slowFrameUploads,
      };
    },

    reportJavaFailure(stage, type, message) {
      const activeStage = globalThis.__mcWebLastStage || stage;
      const failureNumber = (globalThis.mcWebGpu._javaFailureCount || 0) + 1;
      globalThis.mcWebGpu._javaFailureCount = failureNumber;
      // Same trick at the failure boundary. This is the catch site rather than
      // the throw site, but the wasm frames below it still name the call path
      // the frame pump was in when the heap gave out.
      globalThis.mcWebGpu._failStack =
        String(new Error("fail").stack || "").split("\n").slice(1, 40);
      // The bridge calls immediately preceding the failure. Web Image gives no
      // Java stack, but every GPU operation crosses this boundary, so the tail
      // of this list brackets whatever the frame pump was doing when it threw.
      // First failure only: the frame pump now survives a bad frame, so later
      // failures would otherwise overwrite the one that started the cascade.
      globalThis.mcWebGpu._recentAtFail ||= _recentCalls.slice(-60);
      globalThis.mcWebGpu._recentAtLastFail = _recentCalls.slice(-60);
      // `stage` is the caller's own label ("integrated-server", "frame-pump",
      // …) and names the subsystem that caught the throw; `activeStage` is only
      // the last progress marker anyone recorded. Keep both: with no Java stack
      // traces under Web Image, the caller label is the single most direct clue
      // about where a failure came from, and it used to be dropped here.
      recordStage(`FAIL source=${stage} ${activeStage} ${type}`);
      console.error("[MC-FAIL]", `source=${stage}`, activeStage, type, message);
      if (failureNumber === 1) {
        console.error("[MC-FAIL-RECENT]", globalThis.mcWebGpu._recentAtFail.join(" | "));
      }
      markCall("reportJavaFailure", {source: stage, stage: activeStage, type, message});
      const detail = `${type}${message ? `: ${message}` : ""}`;
      setText("jar-status", "runtime error");
      setText("jar-method", activeStage);
      failure.hidden = false;
      failure.textContent = `${activeStage}\n${detail}`;
      globalThis.mcWebGpu.lastJavaFailure = {source: stage, stage: activeStage, type, message};
    }
  };

  // Capture the unwrapped render operations before ?mcweb_perf replaces every
  // public bridge function with a timing wrapper. Stream replay is one bridge
  // call; counting its internal JS dispatches as additional crossings would
  // both perturb the benchmark and lie about the boundary reduction.
  const _renderPassReplay = Object.freeze({
    end: globalThis.mcWebGpu.rpEnd,
    setPipeline: globalThis.mcWebGpu.rpSetPipeline,
    bindTexture: globalThis.mcWebGpu.rpBindTexture,
    setUniform: globalThis.mcWebGpu.rpSetUniform,
    setVertexBuffer: globalThis.mcWebGpu.rpSetVertexBuffer,
    setIndexBuffer: globalThis.mcWebGpu.rpSetIndexBuffer,
    scissor: globalThis.mcWebGpu.rpScissor,
    disableScissor: globalThis.mcWebGpu.rpDisableScissor,
    draw: globalThis.mcWebGpu.rpDraw,
    drawIndexed: globalThis.mcWebGpu.rpDrawIndexed,
    drawIndirect: globalThis.mcWebGpu.rpDrawIndirect,
    drawIndexedIndirect: globalThis.mcWebGpu.rpDrawIndexedIndirect,
    pushDebugGroup: globalThis.mcWebGpu.rpPushDebugGroup,
    popDebugGroup: globalThis.mcWebGpu.rpPopDebugGroup
  });

  const _renderCommandHandlers = Object.freeze({
    setPipeline(host, pass, pipeline) {
      _renderPassReplay.setPipeline.call(host, pass, pipeline);
    },
    bindTexture(host, pass, nameId, view, sampler) {
      const name = resolveBindingName(nameId, "render texture");
      _renderPassReplay.bindTexture.call(host, pass, name, view, sampler);
    },
    setUniform(host, pass, nameId, buffer, offset, size) {
      const name = resolveBindingName(nameId, "render uniform");
      _renderPassReplay.setUniform.call(host, pass, name, buffer, offset, size);
    },
    setVertexBuffer(host, pass, slot, buffer, offset, size) {
      _renderPassReplay.setVertexBuffer.call(host, pass, slot, buffer, offset, size);
    },
    setIndexBuffer(host, pass, buffer, formatCode) {
      const format = formatCode === 0 ? "uint16" : formatCode === 1 ? "uint32" : null;
      if (!format) throw new Error(`unknown render index format ${formatCode}`);
      _renderPassReplay.setIndexBuffer.call(host, pass, buffer, format);
    },
    scissor(host, pass, x, y, width, height) {
      _renderPassReplay.scissor.call(host, pass, x, y, width, height);
    },
    disableScissor(host, pass) {
      _renderPassReplay.disableScissor.call(host, pass);
    },
    draw(host, pass, firstVertex, vertexCount, instanceCount, firstInstance) {
      _renderPassReplay.draw.call(
        host, pass, firstVertex, vertexCount, instanceCount, firstInstance
      );
    },
    drawIndexed(host, pass, indexCount, instanceCount, firstIndex, baseVertex, firstInstance) {
      _renderPassReplay.drawIndexed.call(
        host, pass, indexCount, instanceCount, firstIndex, baseVertex, firstInstance
      );
    },
    drawIndirect(host, pass, buffer, offset, drawCount) {
      _renderPassReplay.drawIndirect.call(host, pass, buffer, offset, drawCount);
    },
    drawIndexedIndirect(host, pass, buffer, offset, drawCount) {
      _renderPassReplay.drawIndexedIndirect.call(host, pass, buffer, offset, drawCount);
    },
    pushDebugGroup(host, pass, labelId) {
      const label = host._bindingNames?.[labelId];
      if (typeof label !== "string") throw new Error(`unknown render debug label ${labelId}`);
      _renderPassReplay.pushDebugGroup.call(host, pass, label);
    },
    popDebugGroup(host, pass) {
      _renderPassReplay.popDebugGroup.call(host, pass);
    }
  });

  function replayRenderPassCommands(
    host, passHandle, payload, byteLength, end, transport, rawWordReader
  ) {
    let replaying = false;
    try {
      if (!Number.isInteger(byteLength) || byteLength < 0 || (byteLength & 3) !== 0) {
        throw new RangeError(`invalid render command byte length ${byteLength}`);
      }
      const protocol = globalThis.mcWebRenderCommands;
      if (!protocol) throw new Error("render command stream decoder is unavailable");
      const stats = (diagnostics.renderCommands ||= {
        streams: 0, bytes: 0, maxBytes: 0, empty: 0,
        le64: 0, le256: 0, le1024: 0, over1024: 0
      });
      stats.streams++;
      stats.bytes += byteLength;
      stats.maxBytes = Math.max(stats.maxBytes, byteLength);
      if (byteLength === 0) stats.empty++;
      else if (byteLength <= 64) stats.le64++;
      else if (byteLength <= 256) stats.le256++;
      else if (byteLength <= 1024) stats.le1024++;
      else stats.over1024++;
      markCall("rpCommandStream", {passHandle, byteLength, end: Boolean(end)});
      _renderCommandReplayDepth++;
      replaying = true;
      let count;
      if (transport === "wasmgc-reader") {
        if (typeof protocol.replayReader !== "function") {
          throw new Error("raw WasmGC render command decoder is unavailable");
        }
        count = protocol.replayReader(
          payload, byteLength >>> 2, rawWordReader,
          _renderCommandHandlers, host, passHandle
        );
      } else if (transport === "packed-text") {
        if (typeof protocol.replayText !== "function") {
          throw new Error("packed render command decoder is unavailable");
        }
        count = protocol.replayText(
          payload, byteLength >>> 2, _renderCommandHandlers, host, passHandle
        );
      } else {
        const bytes = transport === "base64" ? base64ToBytes(payload) : payload;
        if (transport === "base64" && bytes.byteLength !== byteLength) {
          throw new Error(
            `render command base64 length mismatch: ${bytes.byteLength} != ${byteLength}`
          );
        }
        count = protocol.replay(
          bytes, byteLength, _renderCommandHandlers, host, passHandle
        );
      }
      if (end) _renderPassReplay.end.call(host, passHandle);
      return count;
    } catch (error) {
      // A failed segment is not recoverable, even when it was flushed for a
      // timestamp rather than pass end. Never strand an open pass encoder.
      if (objects.has(passHandle)) {
        try {
          _renderPassReplay.end.call(host, passHandle);
        } catch (_cleanupError) {
          objects.delete(passHandle);
        }
      }
      throw error;
    } finally {
      if (replaying) _renderCommandReplayDepth--;
    }
  }

  addEventListener("error", (event) => fail(event.error || event.message));
  addEventListener("unhandledrejection", (event) => fail(event.reason));

  globalThis.mcWebServer = globalThis.mcWebServer || (() => {
    let worker = null;
    let transport = null;
    let state = "idle";
    let statusLog = [];
    /** Inbound frames awaiting drainPackets64(); see the comment in onPacket. */
    const inboundQueue = [];
    let inboundQueueBytes = 0;
    /** Peak queue depth since the last info() read — the burst evidence a
     *  point sample misses: S->C packets arriving faster than the client
     *  drains them (or in bursts after a server stall). */
    let inboundQueuePeak = 0;
    let packetHandler = null;
    let packetHandler64 = null;
    let tickCount = 0;
    let lastError = null;
    let serverStages = [];
    /**
     * Saved worlds shipped back by the Worker, oldest first. A queue rather than
     * a slot because two arrive per world — the small openable set as soon as the
     * world is ready, then the full directory on exit — and dropping the first
     * would put back exactly the failure the early one exists to prevent.
     */
    const worldSnapshots = [];
    let serverLoadProgress = [];
    let serverLoadProgressCount = 0;
    /** Grid frames stream while the loading screen is up; the client drains the
     *  queue every frame, so this counter is the durable evidence of streaming. */
    let serverGridFrames = 0;
    /** Server pump timing from the Worker (pump-stats), always recorded:
     *  per-pump-call [durationMs, gapSincePreviousStartMs]. The delay probe
     *  reads this through info(); the frame graph renders it when enabled. */
    const pumpStats = [];
    /** Slow-tick attribution markers, never evicted (the serverStages ring
     *  is 200 entries and floods during worldgen). */
    /** Durable server-realm evt: log. The client realm's mcWebEvtCounts
     *  cannot see what the server received or sent; combat attribution
     *  needs both sides of the wire. Never evicted. */
    const serverEvtLog = [];
    const slowTickLog = [];

    const status = (s) => {
      state = s;
      statusLog.push({ t: performance.now(), s });
      markPhase(`server-${String(s).toLowerCase().replace(/[^a-z0-9._:-]/g, "-").slice(0, 64)}`);
      console.log("[mcweb-server]", s);
    };

    async function launch(imageName) {
      // Replace any previous Worker here rather than making the caller stop()
      // first. A separate stop() leaves the state at "stopped" until this
      // function's first await resolves, and the client reads that as a
      // terminal server failure and abandons the world it is starting.
      if (worker) {
        try { worker.terminate(); } catch { /* already gone */ }
        worker = null;
      }
      if (transport) {
        try { transport.close(); } catch { /* already closed */ }
        transport = null;
      }
      inboundQueue.length = 0;
      inboundQueueBytes = 0;
      worldSnapshots.length = 0;
      status("launching");

      // The public launcher has one generated runtime pair. Keep the server
      // Worker pinned to that same canonical image; experimental image query
      // routes are intentionally not part of this distribution.
      const serverImage = "minecraft-client";
      console.log(`[mcweb-server] worker image ${serverImage}`);

      const runtimePrefix = globalThis.mcWebRuntimePrefix || "/";
      const { PacketTransport } = await import(`${runtimePrefix}packet-transport.js?v=20260807-batch1`);
      const channel = new MessageChannel();
      const port = channel.port1;
      const workerPort = channel.port2;
      serverLoadProgress = [];
      serverLoadProgressCount = 0;

      transport = new PacketTransport(port, (msg) => {
        if (msg.type === "status") {
          status(msg.status);
        } else if (msg.type === "ready") {
          status("ready");
        } else if (msg.type === "error") {
          lastError = msg.message;
          status("error");
          console.error("[mcweb-server] worker error:", msg.message);
        } else if (msg.type === "tick") {
          tickCount = msg.count;
        } else if (msg.type === "server-stage") {
          serverStages.push(msg.stage);
          if (typeof msg.stage === "string" && msg.stage.startsWith("pumpslow:")) {
            slowTickLog.push(msg.stage);
            if (slowTickLog.length > 400) slowTickLog.shift();
          }
          if (typeof msg.stage === "string" && msg.stage.startsWith("evt:")
              && msg.stage.includes("C->S")) {
            // Only client-to-server packets: these are the rare player-input
            // packets (attacks, actions, commands). Server-to-client markers
            // flood at entity-motion rates and would evict the input events
            // that combat attribution needs.
            serverEvtLog.push(msg.stage + " @" + Math.round(performance.now()));
            if (serverEvtLog.length > 500) serverEvtLog.shift();
          }
          if (serverStages.length > 200) serverStages.shift();
        } else if (msg.type === "server-load-progress") {
          const message = String(msg.message || "");
          serverLoadProgress.push(message);
          serverLoadProgressCount++;
          if (message.startsWith("grid ")) serverGridFrames++;
          if (serverLoadProgressCount <= 4 || (serverLoadProgressCount & 0x3F) === 0) {
            console.log("[mcweb-server] load-progress", `n=${serverLoadProgressCount}`, message);
          }
          if (serverLoadProgress.length > 256) serverLoadProgress.shift();
        } else if (msg.type === "pump-stats") {
          for (const pair of (msg.samples || [])) pumpStats.push(pair);
          if (pumpStats.length > 1200) pumpStats.splice(0, pumpStats.length - 1200);
          _frameGraph.pushServer(msg.samples || []);
        } else if (msg.type === "world-snapshot") {
          // The saved world coming home. Queued until the client's Java side
          // drains it in consumeWorldSnapshot(); "" means the Worker had
          // nothing to send.
          const snapshot = String(msg.json ?? "");
          worldSnapshots.push(snapshot);
          // The Java client still consumes and applies this exact snapshot to
          // its in-memory saves directory.  Persist the already-serialised copy
          // at the page boundary as well so a reload can rebuild that directory.
          if (snapshot) globalThis.mcWebStorage?.storeWorldSnapshot?.(snapshot);
          console.log(`[mcweb-server] world snapshot ${snapshot.length} chars`);
        }
      });
      transport.onPacket((bytes) => {
        if (packetHandler) packetHandler(bytes);
        if (packetHandler64) { packetHandler64(bytesToBase64(bytes)); return; }
        inboundQueue.push(bytes);
        inboundQueueBytes += bytes.length + 4;
        if (inboundQueue.length > inboundQueuePeak) inboundQueuePeak = inboundQueue.length;
      });

      worker = new Worker(`${runtimePrefix}server-worker.js?v=20260810-actualticks1`);
      worker.onerror = (e) => {
        lastError = e.message;
        status("worker-error");
        console.error("[mcweb-server] worker onerror:", e.message);
      };

      const wasmPath = `${runtimePrefix}graal/${serverImage || "minecraft-client"}.js.wasm`;
      const runtimeManifest = globalThis.mcWebDevRuntimeManifest;
      const loaderEntry = runtimeManifest?.files?.find((entry) => entry?.name === "minecraft-client.js");
      worker.postMessage({
        type: "init",
        port: workerPort,
        wasmPath,
        loaderSha256: loaderEntry?.sha256,
        loaderBytes: loaderEntry?.bytes,
      }, [workerPort]);

      return { ok: true };
    }

    function stop() {
      if (worker) {
        worker.terminate();
        worker = null;
      }
      if (transport) {
        transport.close();
        transport = null;
      }
      serverLoadProgress = [];
      serverLoadProgressCount = 0;
      serverGridFrames = 0;
      status("stopped");
    }

    function consumeLoadProgress() {
      const batch = serverLoadProgress;
      serverLoadProgress = [];
      return batch.join("\n");
    }

    /**
     * Hands the pending saved world to the client's Java side exactly once.
     * Returns null while nothing has arrived, so the caller can distinguish
     * "still waiting" from the Worker's empty "nothing to send" answer.
     */
    function consumeWorldSnapshot() {
      return worldSnapshots.length ? worldSnapshots.shift() : null;
    }

    function startWorld(commandJson) {
      if (!transport || state !== "ready") {
        return { error: `server worker is not ready (${state})` };
      }
      // A snapshot from the previous world must never be applied over the one
      // starting now — the client has already shipped its files.
      worldSnapshots.length = 0;
      status("world-starting");
      transport.sendControl({ type: "command", json: commandJson });
      return { ok: true };
    }

    /**
     * Client -> server control-plane push. "world-entered" flips the Worker
     * server from accelerated world-load pacing into the normal 20 TPS loop.
     */
    function sendState(state) {
      if (transport && worker) transport.sendControl({ type: "state", state: String(state) });
    }

    function sendPacket(bytes) {
      if (transport && worker && state !== "error" && state !== "worker-error") {
        transport.send(bytes);
      }
    }

    function sendPacket64(base64) {
      if (transport && worker && state !== "error" && state !== "worker-error") {
        transport.send(base64ToBytes(base64));
      }
    }

    function onPacket(handler) {
      packetHandler = handler;
    }

    function onPacket64(handler) {
      packetHandler64 = handler;
    }

    function bytesToBase64(bytes) {
      if (typeof bytes.toBase64 === "function") return bytes.toBase64();
      let binary = "";
      for (let offset = 0; offset < bytes.length; offset += 0x8000) {
        binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
      }
      return btoa(binary);
    }

    /**
     * Hands the client every packet queued since the last call, as one base64
     * blob of 4-byte-big-endian-length-prefixed frames.
     *
     * One crossing and one Base64 decode per frame instead of per packet. The
     * framing is explicit because concatenating base64 strings would force the
     * client to decode each one separately, which is the cost being removed.
     */
    function drainPackets64() {
      if (inboundQueue.length === 0) return "";
      const blob = new Uint8Array(inboundQueueBytes);
      let offset = 0;
      for (const bytes of inboundQueue) {
        const length = bytes.length;
        blob[offset++] = (length >>> 24) & 0xff;
        blob[offset++] = (length >>> 16) & 0xff;
        blob[offset++] = (length >>> 8) & 0xff;
        blob[offset++] = length & 0xff;
        blob.set(bytes, offset);
        offset += length;
      }
      inboundQueue.length = 0;
      inboundQueueBytes = 0;
      return bytesToBase64(blob);
    }

    function info() {
      const peak = inboundQueuePeak;
      inboundQueuePeak = 0;
      return {
        state,
        tickCount,
        inboundQueued: inboundQueue.length,
        inboundQueuePeak: peak,
        lastError,
        statusLog: statusLog.slice(-20),
        serverStages: serverStages.slice(-80),
        serverLoadProgress: serverLoadProgress.slice(-80),
        serverLoadProgressCount,
        serverGridFrames,
        pumpStats: pumpStats.slice(-600),
        serverEvts: serverEvtLog.slice(-200),
        slowTicks: slowTickLog.slice(-100),
      };
    }

    return {
      launch, startWorld, stop, sendPacket, sendPacket64, sendState,
      onPacket, onPacket64, drainPackets64, consumeLoadProgress,
      consumeWorldSnapshot, info
    };
  })();

  /**
   * Waits for one painted frame, or for `budgetMs`, whichever comes first.
   *
   * Nothing on the boot path may block on an animation frame. Animation frames
   * are a rendering courtesy the browser can withhold — a hidden tab, an
   * occluded window, a backgrounded process — and every second of that wait is
   * a second the game is not starting for a player who is sitting there
   * watching it.
   */
  function paintOrGiveUp(budgetMs) {
    return new Promise((resolve) => {
      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        resolve();
      };
      requestAnimationFrame(() => requestAnimationFrame(finish));
      setTimeout(finish, budgetMs);
    });
  }

  // Some Chromium/Android contexts expose navigator.gpu while their
  // high-performance adapter request is filtered out by the browser's power
  // policy. A null result for that preference does not prove that WebGPU is
  // unavailable: retry the neutral request, then low-power, and use the first
  // adapter the browser actually offers. The selected adapter is requested only
  // once, so the fallback cannot create two runtimes or devices.
  async function requestCompatibleAdapter(gpu) {
    const attempts = [
      { label: "high-performance", options: {powerPreference: "high-performance"} },
      { label: "default", options: {} },
      { label: "low-power", options: {powerPreference: "low-power"} },
    ];
    const probe = {
      secureContext: Boolean(globalThis.isSecureContext),
      userAgent: String(globalThis.navigator?.userAgent || "unknown").slice(0, 240),
      attempts: [],
    };
    for (const attempt of attempts) {
      const started = typeof performance?.now === "function" ? performance.now() : 0;
      try {
        // Pass an options dictionary even for the neutral request. This is
        // accepted by current Safari/Chromium and avoids strict WebIDL
        // implementations treating an omitted argument differently.
        const candidate = await gpu.requestAdapter(attempt.options);
        const result = candidate ? "available" : "null";
        probe.attempts.push({
          label: attempt.label,
          options: {...attempt.options},
          result,
          ms: Math.max(0, Math.round((typeof performance?.now === "function" ? performance.now() : started) - started)),
        });
        if (!candidate) continue;
        const info = candidate.info || {};
        probe.selected = attempt.label;
        probe.adapter = {
          vendor: String(info.vendor || ""),
          architecture: String(info.architecture || ""),
          device: String(info.device || ""),
          description: String(info.description || ""),
          features: candidate.features ? Array.from(candidate.features, String).sort() : [],
          limits: {
            maxBindGroups: candidate.limits?.maxBindGroups,
            maxBindingsPerBindGroup: candidate.limits?.maxBindingsPerBindGroup,
          },
        };
        globalThis.mcWebGpu._adapterProbe = probe;
        return candidate;
      } catch (error) {
        // A browser may reject one preference rather than returning null. Keep
        // its bounded diagnostic, then continue with the next preference.
        probe.attempts.push({
          label: attempt.label,
          options: {...attempt.options},
          result: "error",
          error: String(error?.name || "Error"),
          message: String(error?.message || error).slice(0, 240),
          ms: Math.max(0, Math.round((typeof performance?.now === "function" ? performance.now() : started) - started)),
        });
      }
    }
    // There is no standard WebGPU blocklist-reason API. If a browser exposes
    // one in an adapter-request error, it is retained above; otherwise the
    // attempt table makes the null/error distinction explicit for support.
    globalThis.mcWebGpu._adapterProbe = probe;
    const error = new Error(
      "WebGPU is present, but this browser context has no compatible adapter."
    );
    error.code = "CAPABILITY_UNAVAILABLE";
    error.capability = "webgpu-adapter";
    error.adapterProbe = probe;
    throw error;
  }

  (async () => {
    if (!navigator.gpu) throw new Error("WebGPU is unavailable in this browser");
    // Everything from here to the first [MC-INIT] line used to report nothing,
    // so a slow adapter or a cold Wasm compile was indistinguishable from a
    // hang. Each step names itself before it can block.
    markPhase("webgpu-adapter-requested");
    globalThis.mcWebBootOverlay.stage("asking the browser for a WebGPU adapter…");
    adapter = await requestCompatibleAdapter(navigator.gpu);
    markPhase("webgpu-adapter-ready");
    globalThis.mcWebBootOverlay.stage("opening the GPU device…");
    device = await adapter.requestDevice();
    globalThis.mcWebGpu._gpuTiming = {
      timestampQuery: Boolean(adapter.features?.has?.("timestamp-query")),
      method: adapter.features?.has?.("timestamp-query")
        ? "timestamp-query capability detected; per-pass query wiring remains opt-in"
        : "coarse queue completion"
    };
    device.lost.then((info) => fail(new Error(`WebGPU device lost: ${info.message}`)));
    // Draw-time validation errors were never captured: the existing error
    // scopes only wrap pipeline and buffer creation. A draw whose bound vertex
    // range does not cover baseVertex+indexCount is rejected by the browser and
    // produces no fragments -- silently, with a clean log and a correct-looking
    // draw call. Terrain issues valid draws and renders nothing, so this is the
    // gap that has to be closed before blaming anything else.
    device.addEventListener?.("uncapturederror", (event) => {
      const sink = (globalThis.mcWebGpu._gpuErrors ||= {count: 0, byMessage: {}});
      sink.count++;
      const message = String(event.error?.message ?? event.error).slice(0, 220);
      noteTextureValidation(message, _lastSubmitTextureCandidate);
      sink.byMessage[message] = (sink.byMessage[message] || 0) + 1;
      if (sink.count <= 5) console.error("[GPU-ERROR]", message);
    });
    globalThis.mcWebGpu = globalThis.mcWebGpu || {};
    globalThis.mcWebGpu._limits = {
      maxBindGroups: device.limits?.maxBindGroups,
      maxBindingsPerBindGroup: device.limits?.maxBindingsPerBindGroup
    };
    context = canvas.getContext("webgpu");

    globalThis.mcWebCanvas.resizeBackingStore();
    setText("gpu-status", `adapter ready: ${globalThis.mcWebGpu.adapterName()}`);

    // Main-thread heartbeat, independent of requestAnimationFrame. setInterval
    // only fires when the main thread is idle between tasks; if a synchronous
    // Java call (the constructor's trailing boot, or runTick) blocks the thread,
    // the beats stop. So "beats keep climbing but no [pump] enter / no further
    // [MC-INIT]" ⇒ boot LOGIC is stalled (thread free); "beats stop" ⇒ the
    // thread is BLOCKED in a synchronous call. globalThis.mcWebGpu._beats is
    // readable from the probe at any time.
    globalThis.mcWebGpu._beats = 0;
    setInterval(() => { globalThis.mcWebGpu._beats = (globalThis.mcWebGpu._beats || 0) + 1; }, 2000);

    // ?mcweb_perf=1 -- frame-time distribution plus a per-bridge-call cost and
    // count census. Two performance.now() calls per bridge crossing is far too
    // expensive to leave on (a busy frame makes tens of thousands of them), so
    // this stays opt-in and the unflagged path keeps the original functions.
    if (new URLSearchParams(location.search).has("mcweb_perf")) {
      installPerfProfiler();
    }

    const launchParams = new URLSearchParams(location.search);
    if (launchParams.has("mcweb_debug")) {
      // Keep the allocator counters next to the frame/GC markers while diagnosing
      // WasmLM pressure. The call is opt-in: it re-enters the image and must not be
      // part of the normal render loop.
      setInterval(() => {
        try {
          const counters = globalThis.mcWebThreadRuntime?.imageCounters?.();
          if (counters) console.log("[THREAD-COUNTERS] " + JSON.stringify(counters));
        } catch (error) {
          console.warn("[THREAD-COUNTERS] failed", error);
        }
      }, 5000);
    }
    // The pre-runtime auth boundary exposes only the profile name/UUID. This
    // bounded promise normally finished while WebGPU initialised; awaiting it
    // here guarantees Minecraft's GameConfig sees the authenticated identity,
    // with a bounded local-identity fallback only for diagnostics.
    await globalThis.mcWebNet?.identityReady?.();

    const defaultImage = globalThis.mcWebConfig?.runtime?.image || "minecraft-client";
    const imageName = defaultImage;
    // mcWebServer.launch() derives the server-Worker image from this name so
    // the Worker always runs the image that was just built from this tree.
    globalThis.mcWebImageName = imageName;
    globalThis.mcWebRuntimeMode = "WASMGC_COOPERATIVE";
    console.log(`[mcweb-host] runtime mode ${globalThis.mcWebRuntimeMode}`);
    const script = document.createElement("script");
    if (imageName === defaultImage) {
      // graalWebImage patches Config.wasm_path to consume this explicit URL.
      // Version both requests with one tag: the page must never instantiate a
      // new Wasm binary against an older cached generated loader (or vice versa).
      const runtimePrefix = globalThis.mcWebRuntimePrefix || "/";
      globalThis.mcWebGraalWasmPath = new URL(
        `${runtimePrefix}graal/${imageName}.js.wasm?v=${MCWEB_CACHE_TAG}`,
        location.origin,
      ).href;
      script.src = `${runtimePrefix}graal/${imageName}.js?v=${MCWEB_CACHE_TAG}`;
    } else {
      // Historical diagnostic images were built before the explicit-path
      // loader patch and retain Web Image's original sibling derivation.
      delete globalThis.mcWebGraalWasmPath;
      script.src = `${globalThis.mcWebRuntimePrefix || "/"}graal/${imageName}.js`;
    }
    script.onload = () => {
      markPhase("generated-loader-loaded");
      setText("jar-status", "WebAssembly runtime loaded; starting JAR…");
    };
    script.onerror = () => fail(new Error("Could not load the GraalVM Web Image runtime"));
    globalThis.mcWebBootOverlay.status("Starting Minecraft…");
    globalThis.mcWebBootOverlay.stage(
      "loading and compiling the Minecraft WebAssembly image…");
    // Give the page a chance to paint that message before the loader is
    // attached, because compiling and running the image occupies this thread in
    // long stretches. Never *wait* on it: Chrome stops delivering animation
    // frames to an occluded window while still reporting visibilityState
    // "visible", so a bare rAF await here parked the entire boot until the
    // window was raised again. That is what "waited 75s, switched tabs, it
    // loaded" looks like from the outside.
    await paintOrGiveUp(250);
    markPhase("generated-loader-requested");
    document.body.append(script);
  })().catch(fail);
})();
