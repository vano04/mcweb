"use strict";

/**
 * Resource packs, unpacked in the browser.
 *
 * Minecraft reads a pack either as a zip (`FilePackResources`) or as a plain
 * directory (`PathPackResources`). Only the second one works here: Web Image
 * has no native zlib, so `java.util.zip.Inflater` cannot run inside the image
 * and a deflated archive is unreadable to the game. That is also why the
 * transformed game jar is repacked with STORED entries.
 *
 * The browser, however, has a native inflater — `DecompressionStream` — so the
 * zip is expanded *here* and written out as an ordinary directory pack under
 * `resourcepacks/<name>/`. Those files go into the same IndexedDB store the
 * rest of the writable game directory uses, so `BrowserPersistentStorage`
 * restores them into the image's filesystem before Minecraft boots and
 * vanilla's own `FolderRepositorySource` discovers them with no game-side
 * change at all.
 */
globalThis.mcWebResourcePacks = globalThis.mcWebResourcePacks || (() => {
  const ROOT = "resourcepacks";
  /** Guards IndexedDB (and the tab) against a pathological archive. */
  const MAX_PACK_BYTES = 512 * 1024 * 1024;
  // Caps parsing and storage work independently of archive metadata.
  const MAX_ENTRIES = 65535;

  function loadScript(src) {
    return new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = src.startsWith("/") ? src : `/dev/${src}`;
      script.async = false;
      script.onload = resolve;
      script.onerror = () => reject(new Error(`Could not load ${src}`));
      document.body.append(script);
    });
  }

  /**
   * The storage layer is a runtime script, loaded by the local launcher before
   * Play or by the runtime bootstrap. Loading it lazily keeps this seam usable
   * from the pre-Play picker without duplicating storage state.
   */
  async function storage() {
    if (!globalThis.mcWebStorage) {
      await loadScript("persistent-storage.js?v=20260814-packs4");
    }
    await globalThis.mcWebStorage.ready();
    return globalThis.mcWebStorage;
  }

  function bytesToBase64(bytes) {
    let binary = "";
    const chunk = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunk) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + chunk));
    }
    return btoa(binary);
  }

  /** A pack name that survives both the IndexedDB key rules and Java's Path. */
  function safeName(raw) {
    const name = String(raw ?? "")
      .replace(/\.zip$/i, "")
      .replace(/[^A-Za-z0-9 ._-]/g, "_")
      .replace(/^[.\s]+|[.\s]+$/g, "")
      .slice(0, 80);
    if (!name) throw new Error("That pack needs a name with usable characters");
    return name;
  }

  // ---------------------------------------------------------------------------
  // Zip reading
  //
  // Only what a resource pack actually contains: STORED and DEFLATE entries in
  // a single-disk archive. Sizes come from the central directory, so entries
  // written with a trailing data descriptor need no special case.
  // ---------------------------------------------------------------------------

  const EOCD_SIGNATURE = 0x06054b50;
  const ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
  const ZIP64_EOCD_SIGNATURE = 0x06064b50;
  const CENTRAL_SIGNATURE = 0x02014b50;
  const LOCAL_SIGNATURE = 0x04034b50;

  function findEndOfCentralDirectory(view, length) {
    const earliest = Math.max(0, length - 0xffff - 22);
    for (let offset = length - 22; offset >= earliest; offset--) {
      if (view.getUint32(offset, true) === EOCD_SIGNATURE) return offset;
    }
    throw new Error("That file is not a zip archive");
  }

  /**
   * A pack with more than 65535 files, or one written by a tool that always
   * emits Zip64, stores the real counts in a second record while the classic
   * record holds 0xffff markers.
   */
  function readZip64(view, eocd) {
    const locator = eocd - 20;
    if (locator < 0 || view.getUint32(locator, true) !== ZIP64_LOCATOR_SIGNATURE) {
      return null;
    }
    const record = Number(view.getBigUint64(locator + 8, true));
    if (record < 0 || record + 56 > view.byteLength
        || view.getUint32(record, true) !== ZIP64_EOCD_SIGNATURE) {
      return null;
    }
    return {
      count: Number(view.getBigUint64(record + 32, true)),
      offset: Number(view.getBigUint64(record + 48, true)),
    };
  }

  /** Values in the reserved sentinel range do not provide a verifiable size. */
  const UNKNOWN_SIZE = 0xffffff00;

  function readCentralDirectory(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const eocd = findEndOfCentralDirectory(view, bytes.byteLength);
    let declared = view.getUint16(eocd + 10, true);
    let offset = view.getUint32(eocd + 16, true);
    if (declared === 0xffff || offset === 0xffffffff) {
      const zip64 = readZip64(view, eocd);
      if (!zip64) {
        throw new Error("That archive declares Zip64 but has no Zip64 record");
      }
      declared = zip64.count;
      offset = zip64.offset;
    }
    if (offset + 4 > bytes.byteLength) {
      throw new Error("The zip central directory is outside the file");
    }
    // Scan central-directory signatures instead of trusting the declared count.
    const entries = [];
    while (offset + 46 <= bytes.byteLength
        && view.getUint32(offset, true) === CENTRAL_SIGNATURE) {
      if (entries.length >= MAX_ENTRIES) {
        throw new Error(`That pack has over ${MAX_ENTRIES} files`);
      }
      const nameLength = view.getUint16(offset + 28, true);
      const name = new TextDecoder().decode(
        bytes.subarray(offset + 46, offset + 46 + nameLength));
      // Deflate streams are self-terminating, so an unknown size skips the
      // post-inflate size check.
      const size = view.getUint32(offset + 24, true);
      entries.push({
        name,
        method: view.getUint16(offset + 10, true),
        compressedSize: view.getUint32(offset + 20, true),
        size: size >= UNKNOWN_SIZE ? -1 : size,
        localOffset: view.getUint32(offset + 42, true),
      });
      offset += 46 + nameLength
        + view.getUint16(offset + 30, true)
        + view.getUint16(offset + 32, true);
    }
    if (entries.length === 0) {
      throw new Error("The zip central directory is empty or damaged");
    }
    if (declared > 0 && entries.length !== declared) {
      console.warn(`[mcweb-packs] zip declares ${declared} entries, found ${entries.length}`);
    }
    return entries;
  }

  /** `expected` may be -1 when the archive does not record a real size. */
  async function inflate(bytes, expected) {
    const stream = new Blob([bytes]).stream()
      .pipeThrough(new DecompressionStream("deflate-raw"));
    const out = new Uint8Array(await new Response(stream).arrayBuffer());
    if (expected >= 0 && out.byteLength !== expected) {
      throw new Error(`a pack entry inflated to ${out.byteLength} bytes, expected ${expected}`);
    }
    return out;
  }

  async function readEntry(bytes, entry) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (view.getUint32(entry.localOffset, true) !== LOCAL_SIGNATURE) {
      throw new Error(`the zip entry ${entry.name} is damaged`);
    }
    const start = entry.localOffset + 30
      + view.getUint16(entry.localOffset + 26, true)
      + view.getUint16(entry.localOffset + 28, true);
    const raw = bytes.subarray(start, start + entry.compressedSize);
    if (entry.method === 0) return raw;
    if (entry.method === 8) return inflate(raw, entry.size);
    throw new Error(`the zip entry ${entry.name} uses compression method ${entry.method}`);
  }

  /**
   * Packs are zipped both ways: `pack.mcmeta` at the archive root, or one
   * wrapper folder containing it. Minecraft only accepts the first, so the
   * shallowest `pack.mcmeta` decides the prefix to strip.
   */
  function packPrefix(names) {
    const candidates = names
      .filter((name) => name === "pack.mcmeta" || name.endsWith("/pack.mcmeta"))
      .sort((a, b) => a.split("/").length - b.split("/").length);
    if (candidates.length === 0) {
      throw new Error("No pack.mcmeta in that archive, so it is not a resource pack");
    }
    return candidates[0].slice(0, candidates[0].length - "pack.mcmeta".length);
  }

  function relativePath(name, prefix) {
    if (!name.startsWith(prefix)) return null;
    const relative = name.slice(prefix.length);
    if (!relative || relative.endsWith("/")) return null;
    if (relative.includes("\\") || relative.split("/").some(
      (part) => !part || part === "." || part === "..")) {
      return null;
    }
    return relative;
  }

  async function writePack(name, files, onProgress) {
    const store = await storage();
    const rows = [];
    let total = 0;
    let index = 0;
    for (const [relative, bytes] of files) {
      total += bytes.byteLength;
      if (total > MAX_PACK_BYTES) {
        throw new Error(`That pack is over the ${MAX_PACK_BYTES / 1024 / 1024} MB limit`);
      }
      rows.push({ path: `${ROOT}/${name}/${relative}`, data: bytesToBase64(bytes) });
      onProgress?.({ done: ++index, total: files.length, bytes: total });
    }
    // One batch: storeFiles serialises its own IndexedDB write, and a per-file
    // call would queue one transaction per file.
    if (!store.storeFiles(JSON.stringify(rows))) {
      throw new Error("The browser refused to store that pack");
    }
    await store.flush();
    return { name, files: rows.length, bytes: total };
  }

  async function installZip(file, onProgress) {
    const name = safeName(file.name);
    onProgress?.({ stage: "reading" });
    const bytes = new Uint8Array(await file.arrayBuffer());
    const entries = readCentralDirectory(bytes);
    const prefix = packPrefix(entries.map((entry) => entry.name));
    onProgress?.({ stage: "unpacking", total: entries.length });
    const files = [];
    for (const entry of entries) {
      const relative = relativePath(entry.name, prefix);
      if (relative === null) continue;
      files.push([relative, await readEntry(bytes, entry)]);
      onProgress?.({ stage: "unpacking", done: files.length, total: entries.length });
    }
    onProgress?.({ stage: "storing" });
    return writePack(name, files, onProgress);
  }

  /** `input[webkitdirectory]` hands over every file with its relative path. */
  async function installDirectory(fileList) {
    const picked = [...fileList];
    if (picked.length === 0) throw new Error("That folder is empty");
    const paths = picked.map((file) => file.webkitRelativePath || file.name);
    const prefix = packPrefix(paths);
    const root = (picked[0].webkitRelativePath || "").split("/")[0];
    const name = safeName(prefix ? prefix.replace(/\/$/, "").split("/").pop() : root);
    const files = [];
    for (const [index, file] of picked.entries()) {
      const relative = relativePath(paths[index], prefix);
      if (relative === null) continue;
      files.push([relative, new Uint8Array(await file.arrayBuffer())]);
    }
    return writePack(name, files);
  }

  async function install(input, onProgress) {
    if (input instanceof File) return installZip(input, onProgress);
    return installDirectory(input);
  }

  /**
   * Fetches a pack the client was pointed at, preferring a direct read.
   *
   * The direct attempt is the point: those bytes go browser-to-pack-host and
   * never touch the process serving MC-Web. It only works when the pack host
   * sends `Access-Control-Allow-Origin`, which many Minecraft servers' pack
   * hosts do not, and a browser gives the page no way to read an opaque
   * response. The same-origin relay is the fallback for exactly that case.
   */
  async function fetchPack(url) {
    try {
      const direct = await fetch(String(url), { mode: "cors", cache: "no-store" });
      if (direct.ok) return { blob: await direct.blob(), via: "direct" };
      throw new Error(`HTTP ${direct.status}`);
    } catch (error) {
      const proxyPath = globalThis.mcWebConfig?.gateway?.packProxyPath || "/mcweb/pack";
      const proxy = new URL(proxyPath, globalThis.location?.href);
      proxy.searchParams.set("url", String(url));
      const relayed = await fetch(proxy, { cache: "no-store", credentials: "same-origin" });
      if (!relayed.ok) {
        throw new Error(`pack download failed (direct: ${error?.message || error};`
          + ` relayed: HTTP ${relayed.status})`);
      }
      return { blob: await relayed.blob(), via: "relay" };
    }
  }

  async function installFromUrl(url, name, onProgress) {
    const { blob, via } = await fetchPack(url);
    const result = await installZip(
      new File([blob], `${safeName(name || "server-pack")}.zip`), onProgress);
    return { ...result, via };
  }

  // ---------------------------------------------------------------------------
  // Server-pushed packs
  //
  // The image cannot do this itself: the download is an HTTP request Web Image
  // has no client for, and the payload is a deflated zip it has no inflater
  // for. Java starts a request here and polls; the unpacked pack lands in the
  // same persistent store the manual installs use, under its own prefix, and
  // BrowserServerPacks materialises it into the game filesystem as a directory
  // pack.
  // ---------------------------------------------------------------------------

  globalThis.mcWebServerPacks = globalThis.mcWebServerPacks || (() => {
    const SERVER_ROOT = "server-resource-packs";
    const requests = new Map();

    function start(id, url) {
      const key = String(id);
      if (requests.has(key)) return true;
      const request = { state: "downloading", prefix: `${SERVER_ROOT}/${key}/`, error: "" };
      requests.set(key, request);
      (async () => {
        const { blob, via } = await fetchPack(url);
        const bytes = new Uint8Array(await blob.arrayBuffer());
        const entries = readCentralDirectory(bytes);
        const prefix = packPrefix(entries.map((entry) => entry.name));
        const store = await storage();
        const rows = [];
        let total = 0;
        for (const entry of entries) {
          const relative = relativePath(entry.name, prefix);
          if (relative === null) continue;
          const content = await readEntry(bytes, entry);
          total += content.byteLength;
          if (total > MAX_PACK_BYTES) throw new Error("the server pack is too large");
          rows.push({ path: `${request.prefix}${relative}`, data: bytesToBase64(content) });
        }
        store.storeFiles(JSON.stringify(rows));
        await store.flush();
        request.files = rows.map((row) => row.path);
        request.bytes = total;
        request.via = via;
        request.state = "ready";
      })().catch((error) => {
        request.state = "failed";
        request.error = String(error?.message || error);
        // Minecraft's disconnect screen only ever says "Resource pack failed to
        // download", so the reason has to be findable somewhere.
        console.error(`[mcweb-packs] server pack ${key} failed: ${request.error}`,
          { url: String(url) });
        globalThis.mcWebGpu?.reportProgress?.(
          `serverpack:page-failed ${request.error}`.slice(0, 180));
      });
      return true;
    }

    /** Java polls this once per frame; JSON keeps it to one boundary crossing. */
    function poll(id) {
      const request = requests.get(String(id));
      if (!request) return JSON.stringify({ state: "unknown" });
      return JSON.stringify({
        state: request.state,
        error: request.error,
        via: request.via || "",
        bytes: request.bytes || 0,
        files: request.state === "ready" ? request.files : [],
      });
    }

    function forget(id) {
      requests.delete(String(id));
      return true;
    }

    /** Drops the stored copy of a pack the game is finished with. */
    async function discard(id) {
      const store = await storage();
      const paths = store.fileNames(`${SERVER_ROOT}/${String(id)}/`).map((file) => file.path);
      if (paths.length > 0) store.deleteFiles(JSON.stringify(paths));
      forget(id);
      return true;
    }

    return { start, poll, forget, discard, root: SERVER_ROOT };
  })();

  async function list() {
    const store = await storage();
    const packs = new Map();
    for (const { path, bytes } of store.fileNames(`${ROOT}/`)) {
      const name = path.slice(ROOT.length + 1).split("/")[0];
      if (!name) continue;
      const pack = packs.get(name) || { name, files: 0, bytes: 0 };
      pack.files++;
      pack.bytes += bytes;
      packs.set(name, pack);
    }
    return [...packs.values()].sort((a, b) => a.name.localeCompare(b.name));
  }

  async function remove(name) {
    const store = await storage();
    const prefix = `${ROOT}/${safeName(name)}/`;
    const paths = store.fileNames(prefix).map((file) => file.path);
    if (paths.length === 0) return false;
    store.deleteFiles(JSON.stringify(paths));
    await store.flush();
    return true;
  }

  return { install, installZip, installDirectory, installFromUrl, list, remove, safeName };
})();
