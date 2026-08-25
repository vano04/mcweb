// Shared texture/view ownership for the WebGPU host and the OpenJDK GPU
// Worker.  GPUTextureView has no destroy() method, but it keeps its parent
// GPUTexture alive from WebGPU's point of view.  Keep the parent parked until
// the last logical view is closed.
"use strict";

(() => {
  if (globalThis.mcWebTextureLifetime) return;

  const MAX_DIAGNOSTIC_ITEMS = 80;
  const MAX_DIAGNOSTIC_ARGS = 8;
  const MAX_TEXT = 400;

  const shortText = (value, limit = MAX_TEXT) => String(value ?? "")
    .slice(0, limit);

  const describeArg = (value) => {
    if (value && typeof value === "object") {
      if (typeof value.__bin === "number") return `bin[${value.len ?? 0}]`;
      if (typeof value.__str === "number") return `str[${value.len ?? 0}]`;
      try { return shortText(JSON.stringify(value), 120); } catch { return "[object]"; }
    }
    if (typeof value === "string") return JSON.stringify(value.length > 64 ? `${value.slice(0, 64)}…` : value);
    return String(value);
  };

  const copyCalls = (calls) => (Array.isArray(calls) ? calls : [])
    .slice(-MAX_DIAGNOSTIC_ITEMS)
    .map((call) => shortText(call, 180));

  const copyBatch = (batch) => (Array.isArray(batch) ? batch : [])
    .slice(-MAX_DIAGNOSTIC_ITEMS)
    .map((call) => ({
      o: shortText(call?.o, 32),
      m: shortText(call?.m, 64),
      ...(typeof call?.r === "number" ? {r: call.r} : {}),
      a: (Array.isArray(call?.a) ? call.a : [])
        .slice(0, MAX_DIAGNOSTIC_ARGS)
        .map(describeArg)
    }));

  const createDeferredDestroyQueue = ({
    getQueue = () => null,
    fallbackBatches = 4,
  } = {}) => {
    let pending = [];
    const graveyard = [];

    const release = (batch) => {
      for (const item of batch) {
        try { item.object.destroy(); } catch {}
        if (item.textureEntry) item.textureEntry._gpuDestroyed = true;
      }
    };

    const deferDestroyBuffer = (object) => {
      pending.push({object, textureEntry: null});
    };

    const deferDestroyTexture = (object, textureEntry = null) => {
      pending.push({object, textureEntry});
    };

    const flush = () => {
      if (!pending.length) return false;
      const batch = pending;
      pending = [];
      const queue = getQueue();
      if (queue && typeof queue.onSubmittedWorkDone === "function") {
        try {
          queue.onSubmittedWorkDone().then(() => release(batch), () => release(batch));
          return true;
        } catch {}
      }
      graveyard.push(batch);
      if (graveyard.length > fallbackBatches) release(graveyard.shift());
      return true;
    };

    return {
      deferDestroyBuffer,
      deferDestroyTexture,
      flush,
      pendingCount: () => pending.length,
      graveyardCount: () => graveyard.length,
    };
  };

  const createTextureLifetime = ({
    deferDestroyTexture,
    recentCalls = () => [],
    rpcBatch = () => null,
  } = {}) => {
    if (typeof deferDestroyTexture !== "function") {
      throw new TypeError("texture lifetime requires deferDestroyTexture");
    }

    let firstValidation = null;
    let lastViewHandle = null;
    let lastViewEntry = null;

    const initializeTexture = (entry) => {
      entry._viewRefs = 0;
      entry._destroyRequested = false;
      entry._gpuDestroyQueued = false;
      entry._gpuDestroyed = false;
      return entry;
    };

    const parentState = (entry) => entry ? {
      handle: Number.isInteger(entry._handle)
        ? entry._handle : (Number.isInteger(entry._hid) ? entry._hid : null),
      label: shortText(entry._label, 96),
      viewRefs: entry._viewRefs || 0,
      destroyRequested: Boolean(entry._destroyRequested),
      gpuDestroyQueued: Boolean(entry._gpuDestroyQueued),
      pending: Boolean(entry._destroyRequested && !entry._gpuDestroyed),
      destroyed: Boolean(entry._gpuDestroyed),
    } : null;

    const queueDestroy = (entry) => {
      if (!entry || entry._gpuDestroyQueued || entry._gpuDestroyed) return false;
      entry._gpuDestroyQueued = true;
      // The host marks _gpuDestroyed after the fence releases the object.
      deferDestroyTexture(entry.texture, entry);
      return true;
    };

    const requestDestroy = (entry) => {
      if (!entry || entry._destroyRequested) return false;
      entry._destroyRequested = true;
      if (entry._viewRefs === 0) queueDestroy(entry);
      return true;
    };

    const retainView = (entry) => {
      if (!entry || entry._destroyRequested || entry._gpuDestroyed) {
        throw new Error(`Cannot create texture view for destroyed texture handle ${entry?._hid ?? "?"}`);
      }
      entry._viewRefs = (entry._viewRefs || 0) + 1;
      return entry._viewRefs;
    };

    const releaseView = (viewEntry) => {
      if (!viewEntry || viewEntry._lifetimeReleased) return false;
      viewEntry._lifetimeReleased = true;
      const entry = viewEntry.textureEntry;
      if (!entry) return true;
      // A view can be closed exactly once by Java, but keep this defensive
      // clamp so a malformed RPC cannot underflow the parent's reference count.
      entry._viewRefs = Math.max(0, (entry._viewRefs || 0) - 1);
      if (entry._destroyRequested && entry._viewRefs === 0) queueDestroy(entry);
      return true;
    };

    const noteViewUse = (handle, viewEntry = null) => {
      lastViewHandle = Number.isInteger(handle) ? handle : null;
      lastViewEntry = viewEntry;
    };

    const snapshotValidation = (
      viewHandle = lastViewHandle,
      viewEntry = null,
      batch = rpcBatch(),
    ) => ({
      viewHandle: Number.isInteger(viewHandle) ? viewHandle : null,
      parent: parentState(viewEntry?.textureEntry),
      recentCalls: copyCalls(recentCalls()),
      batch: copyBatch(batch),
    });

    const captureValidation = (message, candidate = null) => {
      const text = shortText(message);
      if (!/(?:texture\s*view|textureview)/i.test(text)) return null;
      if (firstValidation) return firstValidation;
      firstValidation = {
        message: text,
        ...(candidate || snapshotValidation()),
      };
      return firstValidation;
    };

    return {
      initializeTexture,
      parentState,
      requestDestroy,
      retainView,
      releaseView,
      noteViewUse,
      snapshotValidation,
      captureValidation,
      validationReport: () => firstValidation,
      lastViewHandle: () => lastViewHandle,
      lastViewEntry: () => lastViewEntry,
    };
  };

  globalThis.mcWebTextureLifetime = {
    createDeferredDestroyQueue,
    createTextureLifetime,
  };
})();
