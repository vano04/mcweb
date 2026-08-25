"use strict";

// Device-local persistence for the owner-only development lane. This module
// never performs network I/O and never writes credentials to the DOM, a URL,
// cookie, log, or hosted service. IndexedDB keeps only a bounded,
// credential-free boot-phase ring. A versioned migration removes the old auth
// object store so a browser that used an earlier build cannot retain a token.
(() => {
  const DB_NAME = "mcweb-dev-local-v1";
  const DB_VERSION = 3;
  const AUTH_STORE = "auth";
  const PHASE_STORE = "diagnostics";
  const PHASE_KEY = "boot";
  const MAX_PHASES = 48;
  let database = null;
  let available = typeof globalThis.indexedDB !== "undefined";
  let readyPromise = null;
  let phaseEntries = [];
  let phasesLoaded = false;
  let phasesReadyPromise = null;

  function requestValue(request) {
    return new Promise((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error("local storage request failed"));
    });
  }

  function transactionDone(transaction) {
    return new Promise((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onabort = () => reject(transaction.error || new Error("local storage transaction aborted"));
      transaction.onerror = () => reject(transaction.error || new Error("local storage transaction failed"));
    });
  }

  function open() {
    if (readyPromise) return readyPromise;
    readyPromise = (async () => {
      if (!available) return false;
      try {
        const request = globalThis.indexedDB.open(DB_NAME, DB_VERSION);
        request.onupgradeneeded = () => {
          const db = request.result;
          if (db.objectStoreNames.contains(AUTH_STORE)) db.deleteObjectStore(AUTH_STORE);
          if (!db.objectStoreNames.contains(PHASE_STORE)) {
            db.createObjectStore(PHASE_STORE, { keyPath: "key" });
          }
        };
        database = await requestValue(request);
        return true;
      } catch {
        available = false;
        database = null;
        return false;
      }
    })();
    return readyPromise;
  }

  function validPhaseEntry(value) {
    return value && typeof value === "object"
      && typeof value.phase === "string" && /^[a-z0-9._:-]{1,80}$/.test(value.phase)
      && typeof value.detail === "string" && value.detail.length <= 220
      && Number.isFinite(Number(value.at));
  }

  function renderPhases() {
    if (typeof document === "undefined") return;
    const summary = document.getElementById("runtime-diagnostics-summary");
    const list = document.getElementById("runtime-diagnostics-log");
    if (!summary || !list) return;
    summary.textContent = phaseEntries.length
      ? `${phaseEntries.length} persisted boot phase${phaseEntries.length === 1 ? "" : "s"}; newest first.`
      : "No persisted boot phases yet.";
    list.replaceChildren();
    for (const entry of [...phaseEntries].reverse()) {
      const item = document.createElement("li");
      const time = new Date(Number(entry.at));
      item.textContent = `${Number.isNaN(time.getTime()) ? "" : time.toLocaleTimeString()} ${entry.phase}${entry.detail ? ` — ${entry.detail}` : ""}`;
      list.append(item);
    }
  }

  async function readPhases() {
    if (!phasesReadyPromise) {
      phasesReadyPromise = (async () => {
        if (phasesLoaded) return;
        phasesLoaded = true;
        const pending = phaseEntries.slice();
        if (!(await open()) || !database) return;
        try {
          const transaction = database.transaction(PHASE_STORE, "readonly");
          const done = transactionDone(transaction);
          const value = await requestValue(transaction.objectStore(PHASE_STORE).get(PHASE_KEY));
          await done;
          const persisted = Array.isArray(value?.entries)
            ? value.entries.filter(validPhaseEntry) : [];
          phaseEntries = [...persisted, ...pending].slice(-MAX_PHASES);
        } catch {
          phaseEntries = pending;
        }
        renderPhases();
      })();
    }
    await phasesReadyPromise;
    return phaseEntries.slice();
  }

  async function persistPhases() {
    if (!(await open()) || !database) return false;
    try {
      const transaction = database.transaction(PHASE_STORE, "readwrite");
      const done = transactionDone(transaction);
      transaction.objectStore(PHASE_STORE).put({
        key: PHASE_KEY,
        version: 1,
        entries: phaseEntries.slice(-MAX_PHASES),
      });
      await done;
      return true;
    } catch {
      return false;
    }
  }

  function markPhase(phase, detail = "") {
    if (!/^[a-z0-9._:-]{1,80}$/.test(String(phase))) return null;
    const entry = {
      phase: String(phase),
      detail: String(detail).slice(0, 220),
      at: Date.now(),
    };
    phaseEntries.push(entry);
    if (phaseEntries.length > MAX_PHASES) phaseEntries.splice(0, phaseEntries.length - MAX_PHASES);
    renderPhases();
    void persistPhases();
    return entry;
  }

  async function clearPhases() {
    phaseEntries = [];
    phasesLoaded = true;
    renderPhases();
    if (!(await open()) || !database) return false;
    try {
      const transaction = database.transaction(PHASE_STORE, "readwrite");
      const done = transactionDone(transaction);
      transaction.objectStore(PHASE_STORE).delete(PHASE_KEY);
      await done;
      return true;
    } catch {
      return false;
    }
  }

  globalThis.mcWebDevLocalStore = Object.freeze({
    available: () => available,
    ready: open,
  });
  globalThis.mcWebDevDiagnostics = Object.freeze({
    ready: readPhases,
    read: () => phaseEntries.slice(),
    mark: markPhase,
    clear: clearPhases,
  });
  void readPhases();
})();
