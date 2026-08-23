import { createHash } from "node:crypto";
import { existsSync, readFileSync, statSync } from "node:fs";
import { join, normalize } from "node:path";

export const RUNTIME_FILES = Object.freeze([
  "minecraft-client.js",
  "minecraft-client.js.wasm",
]);

const manifestCache = new Map();

function fileStats(root) {
  const normalizedRoot = normalize(root);
  const entries = RUNTIME_FILES.map((name) => {
    const file = join(normalizedRoot, "graal", name);
    try {
      const stat = statSync(file);
      if (!stat.isFile()) return null;
      return {
        name,
        file,
        bytes: stat.size,
        // mtimeNs/ino catch same-size rebuilds on filesystems that expose
        // nanosecond timestamps. The fallback keeps this portable on older
        // Node/filesystem combinations.
        mtime: stat.mtimeNs?.toString() || String(stat.mtimeMs),
        ctime: stat.ctimeNs?.toString() || String(stat.ctimeMs),
        inode: String(stat.ino ?? ""),
      };
    } catch {
      return null;
    }
  });
  return entries.every(Boolean) ? { root: normalizedRoot, entries } : null;
}

function statsSignature(value) {
  return value.entries.map((entry) => `${entry.name}:${entry.bytes}:${entry.mtime}:${entry.ctime}:${entry.inode}`).join("|");
}

/** A cheap existence/stat check suitable for a frequent container healthcheck. */
export function runtimeAvailable(root) {
  return fileStats(root) !== null;
}

/**
 * Hash the runtime pair only when its stat signature changes. The browser
 * install endpoint needs the digest, but /healthz should never re-read a
 * 140MiB Wasm file on every probe.
 */
export function runtimeManifest(root) {
  const snapshot = fileStats(root);
  if (!snapshot) {
    manifestCache.delete(normalize(root));
    return null;
  }
  const signature = statsSignature(snapshot);
  const cached = manifestCache.get(snapshot.root);
  if (cached?.signature === signature) return cached.manifest;

  const files = snapshot.entries.map((entry) => ({
    name: entry.name,
    bytes: entry.bytes,
    sha256: createHash("sha256").update(readFileSync(entry.file)).digest("hex"),
  }));
  const manifest = { version: 1, loader: "instantiate-streaming-v1", files };
  manifestCache.set(snapshot.root, { signature, manifest });
  return manifest;
}
