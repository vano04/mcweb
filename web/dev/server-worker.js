/**
 * Server Worker: loads the same GraalVM Wasm image in a dedicated Worker
 * and runs it in server mode. Communicates with the main thread via a
 * MessagePort passed in the first message.
 *
 * Protocol:
 *   main -> worker: { type: "init", port: MessagePort, wasmPath: string }
 *   worker -> main: { type: "status", status: string }
 *   worker -> main: { type: "ready" }
 *   worker -> main: { type: "error", message: string }
 */

let vm = null;

self.onmessage = async (event) => {
  const msg = event.data;
  if (msg.type !== "init") return;

  const { port, wasmPath, loaderSha256, loaderBytes } = msg;
  port.start();

  const status = (s) => port.postMessage({ type: "status", status: s });
  const fail = (m) => port.postMessage({ type: "error", message: m });

  try {
    status("fetching-loader");
    const loaderUrl = wasmPath.replace(/\.wasm$/, "");
    const resp = await fetch(loaderUrl);
    if (!resp.ok) throw new Error(`loader fetch failed: ${resp.status}`);
    const loaderBytesValue = new Uint8Array(await resp.arrayBuffer());
    if (Number.isInteger(loaderBytes) && loaderBytesValue.byteLength !== loaderBytes) {
      throw new Error(`loader byte length mismatch: expected ${loaderBytes}, got ${loaderBytesValue.byteLength}`);
    }
    if (loaderSha256 !== undefined) {
      if (typeof loaderSha256 !== "string" || !/^[a-f0-9]{64}$/i.test(loaderSha256)
          || !globalThis.crypto?.subtle) {
        throw new Error("loader hash verification is unavailable");
      }
      const digest = await crypto.subtle.digest("SHA-256", loaderBytesValue);
      const actualHash = Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, "0")).join("");
      if (actualHash !== loaderSha256.toLowerCase()) {
        throw new Error(`loader hash mismatch: expected ${loaderSha256}, got ${actualHash}`);
      }
    }
    let loaderSource;
    try {
      loaderSource = new TextDecoder("utf-8", { fatal: true }).decode(loaderBytesValue);
    } catch {
      throw new Error("loader is not valid UTF-8 JavaScript");
    }

    const autoRunMarker = "GraalVM.run(load_cmd_args(),config)";
    const markerIdx = loaderSource.lastIndexOf(autoRunMarker);
    if (markerIdx !== -1) {
      const iifeStart = loaderSource.lastIndexOf("(function()", markerIdx);
      if (iifeStart !== -1) {
        loaderSource = loaderSource.slice(0, iifeStart) + "/* auto-run stripped */";
      }
    }

    status("loading-loader-module");
    // The generated loader is verified above, but it is not safe to execute it
    // through Function/eval: the production /dev CSP deliberately excludes
    // unsafe-eval. A module Blob gives the loader its own scope, so append a
    // single explicit export bridge instead of relying on a global declaration.
    const loaderBlob = new Blob([
      `${loaderSource}\n;globalThis.__mcWebVerifiedGraalVM = GraalVM;\n`,
    ], { type: "text/javascript" });
    const loaderBlobUrl = URL.createObjectURL(loaderBlob);
    let GraalVM;
    try {
      await import(loaderBlobUrl);
      GraalVM = globalThis.__mcWebVerifiedGraalVM;
    } finally {
      URL.revokeObjectURL(loaderBlobUrl);
      delete globalThis.__mcWebVerifiedGraalVM;
    }

    if (!GraalVM || typeof GraalVM.run !== "function") {
      throw new Error("GraalVM.run not found after loader evaluation");
    }

    status("setting-up-transport");
    const { PacketTransport } = await import("./packet-transport.js?v=20260807-batch1");
    let commandHandler = null;
    const queuedCommands = [];
    let stateHandler = null;
    const dispatchCommand = (json) => {
      if (commandHandler) commandHandler(json);
      else queuedCommands.push(json);
    };
    const dispatchState = (state) => {
      if (stateHandler) stateHandler(String(state));
      // States sent before Java registers its handler are dropped on purpose:
      // they describe a client that is further along than the server lane's
      // own bookkeeping, never the other way around.
    };
    const transport = new PacketTransport(port, (msg) => {
      if (msg?.type === "command") dispatchCommand(msg.json);
      else if (msg?.type === "state") dispatchState(msg.state);
    });

    globalThis.mcWebServerTransport = {
      sendBase64(base64) {
        transport.send(base64ToBytes(base64));
      },
      flush() {
        transport.flush();
      },
      onPacket(handler) {
        transport.onPacket((bytes) => handler(bytesToBase64(bytes)));
      },
    };

    globalThis.mcWebServerControl = {
      onCommand(handler) {
        commandHandler = handler;
        while (queuedCommands.length > 0) dispatchCommand(queuedCommands.shift());
      },
      onState(handler) {
        stateHandler = handler;
      },
      /**
       * Ships the saved world directory back to the page. This is the only
       * path by which anything the server writes reaches the client realm's
       * filesystem, so a world that is not snapshotted cannot be reopened.
       */
      sendSnapshot(json) {
        port.postMessage({ type: "world-snapshot", json: String(json ?? "") });
      },
    };

    globalThis.mcWebServerPump = (() => {
      let callback = null;
      let timer = null;
      let count = 0;
      let lastReportedTick = -1;
      // Per-pump-call durations and the gaps between pump starts. A slow
      // server tick shows as a big duration; a starving event loop shows as
      // a big gap with small durations. Both read as the user-visible
      // "~500 ms delay". Ring of the last 512 samples, drained by the host.
      const durations = new Float64Array(512);
      const gaps = new Float64Array(512);
      let head = 0;
      let lastStart = 0;
      return {
        register(cb) {
          callback = cb;
          if (!timer) {
            /*
             * Schedule the next pump only after the current Java call returns.
             * A repeating timer queues its next task while Java is still in a
             * long (>50 ms) chunk/save slice. That overdue timer can run before
             * MessagePort tasks which arrived during the slice, repeatedly
             * putting player-action packets behind server work. A recursive
             * timeout keeps the same 50 ms start-to-start cadence when Java is
             * under budget, but lets already-queued packet tasks run before the
             * next timer is even enqueued after an overrun.
             */
            // Anchor to an absolute cadence so timer overshoot does not add a
            // millisecond or two on every otherwise-cheap pump. After a long
            // callback, skip whole missed periods but leave at most one
            // immediate catch-up; that timer is created only after Java returns,
            // so MessagePort work which queued during Java gets the intervening
            // event-loop turn.
            let nextDeadline = performance.now() + 50;
            const run = () => {
              timer = null;
              const start = performance.now();
              try {
                if (callback) {
                  if (lastStart > 0) {
                    gaps[head] = start - lastStart;
                  }
                  lastStart = start;
                  callback.run ? callback.run() : callback();
                  durations[head] = performance.now() - start;
                  head = (head + 1) & 511;
                  count++;
                }
              } finally {
                const end = performance.now();
                nextDeadline += 50;
                const lateness = end - nextDeadline;
                if (lateness >= 50) {
                  nextDeadline += Math.floor(lateness / 50) * 50;
                }
                timer = setTimeout(run, Math.max(0, nextDeadline - end));
              }
            };
            timer = setTimeout(run, Math.max(0, nextDeadline - performance.now()));
            // Ship the timing rings to the page for the frame graph
            // (?mcweb_framegraph=1). The host ignores these unless the
            // graph is on; 4 messages/s of small arrays is negligible.
            setInterval(() => {
              const samples = this.drainStats();
              if (samples.length > 0) {
                port.postMessage({ type: "pump-stats", samples });
              }
            }, 250);
          }
        },
        reportTick(n) {
          count = Number(n);
          // `count` is the number of completed Minecraft ticks, not pump
          // callbacks. A bounded catch-up pump may advance it by two or three,
          // so sampling only exact multiples (`count % 20 === 0`) can skip
          // every future update and leave the page's TPS counter frozen. Keep
          // the bridge low-rate, but sample by distance from the last value we
          // actually published. Five ticks bounds a 10 s TPS observation to
          // less than half a tick/second of endpoint quantisation.
          if (Number.isFinite(count)
              && (lastReportedTick < 0
                || count < lastReportedTick
                || count - lastReportedTick >= 5)) {
            lastReportedTick = count;
            port.postMessage({ type: "tick", count });
          }
        },
        drainStats() {
          // Snapshot and reset the rings; called on a 250 ms timer. Cheap:
          // at most 512 iterations. Pairs keep each gap aligned with the
          // duration that follows it, so the host graph lines them up.
          const samples = [];
          for (let i = 0; i < 512; i++) {
            if (durations[i] > 0 || gaps[i] > 0) {
              samples.push([
                durations[i] > 0 ? Math.round(durations[i] * 10) / 10 : 0,
                gaps[i] > 0 ? Math.round(gaps[i] * 10) / 10 : 0,
              ]);
            }
            durations[i] = 0;
            gaps[i] = 0;
          }
          return samples;
        },
      };
    })();

    globalThis.mcWebServerStatus = {
      lastFailure: null,
      reportProgress(stage) {
        port.postMessage({ type: "status", status: stage });
      },
      reportFailure(stage, type, message) {
        port.postMessage({ type: "error", message: `${stage}: ${type}: ${message}` });
      },
    };

    globalThis.mcWebGpu = {
      reportProgress(stage) {
        const value = String(stage);
        port.postMessage({ type: "server-stage", stage: value });
        if (value.startsWith("levelload:")) {
          port.postMessage({
            type: "server-load-progress",
            message: value.slice("levelload:".length),
          });
        }
      },
      reportJavaFailure(stage, type, message) {
        port.postMessage({ type: "error", message: `${stage}: ${type}: ${message}` });
      },
    };

    status("instantiating-wasm");
    const config = new GraalVM.Config();
    config.wasm_path = wasmPath;

    vm = await GraalVM.run(["--server"], config);

    status("server-vm-running");
    port.postMessage({ type: "ready" });
  } catch (err) {
    fail(String(err?.stack || err));
  }
};

function bytesToBase64(bytes) {
  if (typeof bytes.toBase64 === "function") return bytes.toBase64();
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

function base64ToBytes(base64) {
  if (typeof Uint8Array.fromBase64 === "function") {
    return Uint8Array.fromBase64(base64);
  }
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}
