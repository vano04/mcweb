"use strict";

/**
 * Durable backing store for Minecraft's otherwise in-memory Web Image files.
 *
 * IndexedDB is deliberately owned by the page, not by Minecraft.  The Java
 * image restores a synchronous snapshot before constructing Minecraft and
 * publishes later file changes back here as base64.  World snapshots already
 * cross the server-Worker boundary as one JSON value, so they are stored at
 * that existing seam instead of recapturing region files on the render thread.
 */
globalThis.mcWebStorage = globalThis.mcWebStorage || (() => {
  const DB_NAME = "mcweb-player-data";
  const DB_VERSION = 1;
  const FILE_STORE = "files";
  const WORLD_STORE = "worlds";
  const files = new Map();
  const worlds = new Map();
  let database = null;
  let available = typeof indexedDB !== "undefined";
  let lastError = null;
  let writes = Promise.resolve();

  const requestValue = (request) => new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("IndexedDB request failed"));
  });

  const transactionDone = (transaction) => new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onabort = () => reject(transaction.error || new Error("IndexedDB transaction aborted"));
    transaction.onerror = () => reject(transaction.error || new Error("IndexedDB transaction failed"));
  });

  const readyPromise = (async () => {
    if (!available) return false;
    try {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(FILE_STORE)) {
          db.createObjectStore(FILE_STORE, { keyPath: "path" });
        }
        if (!db.objectStoreNames.contains(WORLD_STORE)) {
          db.createObjectStore(WORLD_STORE, { keyPath: "levelId" });
        }
      };
      database = await requestValue(request);
      const transaction = database.transaction([FILE_STORE, WORLD_STORE], "readonly");
      // Install completion handlers before awaiting either request. IndexedDB
      // may complete a read-only transaction in the same task as the final
      // request; attaching oncomplete afterwards can leave boot waiting forever.
      const done = transactionDone(transaction);
      const fileRows = await requestValue(transaction.objectStore(FILE_STORE).getAll());
      const worldRows = await requestValue(transaction.objectStore(WORLD_STORE).getAll());
      await done;
      for (const row of fileRows || []) {
        if (validPath(row?.path) && typeof row?.data === "string") {
          files.set(row.path, row.data);
        }
      }
      for (const row of worldRows || []) {
        if (validLevelId(row?.levelId) && typeof row?.json === "string") {
          worlds.set(row.levelId, row.json);
        }
      }
      console.log(`[mcweb-storage] restored ${files.size} files and ${worlds.size} worlds`);
      return true;
    } catch (error) {
      available = false;
      lastError = String(error?.message || error);
      console.warn("[mcweb-storage] IndexedDB unavailable; using tab-local files", error);
      return false;
    }
  })();

  function validPath(value) {
    const path = String(value ?? "");
    return path.length > 0 && path.length <= 1024
      && !path.startsWith("/") && !path.includes("\\")
      && !path.includes("\0")
      && path.split("/").every((part) => part && part !== "." && part !== "..");
  }

  function validLevelId(value) {
    const id = String(value ?? "");
    return id.length > 0 && id.length <= 255
      && !id.includes("/") && !id.includes("\\")
      && id !== "." && id !== ".." && !id.includes("\0");
  }

  function enqueue(write) {
    writes = writes.then(async () => {
      if (!(await readyPromise) || !database) return;
      await write(database);
    }).catch((error) => {
      lastError = String(error?.message || error);
      console.warn("[mcweb-storage] write failed", error);
    });
    return writes;
  }

  function startupFiles() {
    return JSON.stringify([...files].map(([path, data]) => ({ path, data })));
  }

  /**
   * Paths only. The pack manager needs to know what is installed and how big
   * it is; materialising every base64 payload to answer that would copy the
   * whole resource-pack store on every launcher render.
   */
  function fileNames(prefix = "") {
    const wanted = String(prefix);
    return [...files]
      .filter(([path]) => path.startsWith(wanted))
      // base64 carries 3 bytes per 4 characters, minus the padding.
      .map(([path, data]) => ({
        path,
        bytes: Math.floor((data.length * 3) / 4) - (data.endsWith("==") ? 2 : data.endsWith("=") ? 1 : 0),
      }));
  }

  function isBulkWorldPath(path) {
    return String(path).split("/").some((part) =>
      part === "region" || part === "entities" || part === "poi");
  }

  /** Metadata needed by Minecraft's world list; bulk chunks restore on open. */
  function startupWorldMetadata() {
    const restored = [];
    for (const [levelId, json] of worlds) {
      try {
        const snapshot = JSON.parse(json);
        const metadata = (Array.isArray(snapshot?.files) ? snapshot.files : [])
          .filter((file) => validPath(file?.path)
            && file.path !== "session.lock"
            && !isBulkWorldPath(file.path)
            && typeof file?.data === "string");
        restored.push({ levelId, files: metadata });
      } catch (error) {
        lastError = `invalid saved world ${levelId}: ${error?.message || error}`;
      }
    }
    return JSON.stringify(restored);
  }

  /**
   * One stored file, base64. Pulling a restored tree one entry at a time keeps
   * a 100 MB resource pack from having to exist as a single JSON string on
   * both sides of the Wasm boundary at once.
   */
  function fileData(path) {
    return files.get(String(path ?? "")) || "";
  }

  function worldSnapshot(levelId) {
    return worlds.get(String(levelId ?? "")) || "";
  }

  function worldIds() {
    return JSON.stringify([...worlds.keys()]);
  }

  function deleteWorld(levelIdIn) {
    const levelId = String(levelIdIn ?? "");
    if (!validLevelId(levelId)) return false;
    worlds.delete(levelId);
    enqueue(async (db) => {
      const transaction = db.transaction(WORLD_STORE, "readwrite");
      const done = transactionDone(transaction);
      transaction.objectStore(WORLD_STORE).delete(levelId);
      await done;
    });
    return true;
  }

  function storeFiles(json) {
    let rows;
    try {
      rows = JSON.parse(String(json || "[]"));
    } catch (error) {
      lastError = `invalid file batch: ${error?.message || error}`;
      return false;
    }
    if (!Array.isArray(rows)) return false;
    const accepted = [];
    for (const row of rows) {
      if (!validPath(row?.path) || typeof row?.data !== "string") continue;
      files.set(row.path, row.data);
      accepted.push({ path: row.path, data: row.data });
    }
    if (accepted.length > 0) {
      enqueue(async (db) => {
        const transaction = db.transaction(FILE_STORE, "readwrite");
        const done = transactionDone(transaction);
        const store = transaction.objectStore(FILE_STORE);
        for (const row of accepted) store.put(row);
        await done;
      });
    }
    return true;
  }

  function deleteFiles(json) {
    let paths;
    try {
      paths = JSON.parse(String(json || "[]"));
    } catch (error) {
      lastError = `invalid delete batch: ${error?.message || error}`;
      return false;
    }
    if (!Array.isArray(paths)) return false;
    const accepted = paths.map(String).filter(validPath);
    for (const path of accepted) files.delete(path);
    if (accepted.length > 0) {
      enqueue(async (db) => {
        const transaction = db.transaction(FILE_STORE, "readwrite");
        const done = transactionDone(transaction);
        const store = transaction.objectStore(FILE_STORE);
        for (const path of accepted) store.delete(path);
        await done;
      });
    }
    return true;
  }

  /**
   * Merge rather than replace: an early metadata-only snapshot must not erase
   * region files from the previous complete save before the new exit snapshot.
   */
  function storeWorldSnapshot(json) {
    let incoming;
    try {
      incoming = JSON.parse(String(json || ""));
    } catch (error) {
      lastError = `invalid world snapshot: ${error?.message || error}`;
      return false;
    }
    const levelId = String(incoming?.levelId ?? "");
    if (!validLevelId(levelId) || !Array.isArray(incoming?.files)) return false;

    const merged = new Map();
    const previous = worlds.get(levelId);
    if (previous) {
      try {
        const old = JSON.parse(previous);
        for (const file of Array.isArray(old?.files) ? old.files : []) {
          if (validPath(file?.path) && file.path !== "session.lock"
              && typeof file?.data === "string") {
            merged.set(file.path, { path: file.path, data: file.data });
          }
        }
      } catch { /* a valid incoming snapshot repairs a corrupt old record */ }
    }
    for (const file of incoming.files) {
      if (validPath(file?.path) && file.path !== "session.lock"
          && typeof file?.data === "string") {
        merged.set(file.path, { path: file.path, data: file.data });
      }
    }
    const stored = JSON.stringify({ levelId, files: [...merged.values()] });
    worlds.set(levelId, stored);
    enqueue(async (db) => {
      const transaction = db.transaction(WORLD_STORE, "readwrite");
      const done = transactionDone(transaction);
      transaction.objectStore(WORLD_STORE).put({ levelId, json: stored });
      await done;
    });
    return true;
  }

  return {
    ready: () => readyPromise,
    flush: async () => { await readyPromise; await writes; },
    startupFiles,
    fileNames,
    fileData,
    startupWorldMetadata,
    worldSnapshot,
    worldIds,
    deleteWorld,
    storeFiles,
    deleteFiles,
    storeWorldSnapshot,
    info: () => ({
      available,
      ready: database !== null,
      files: files.size,
      worlds: worlds.size,
      lastError,
    }),
  };
})();
