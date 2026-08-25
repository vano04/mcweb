#!/usr/bin/env node
// Validate and stage the Mojang-owned title assets from the developer's own
// Minecraft installation. The build helper imports this module so a clean
// `tools/build.mjs` or installer `--build` cannot silently skip this step.
//
// These bytes are Mojang's and are never redistributed by this repository. They
// are read from the player's own installation and written only below the local
// checkout's ignored `web/assets` and `src/graal/resources/assets` paths.

import { createHash } from "node:crypto";
import { inflateRawSync } from "node:zlib";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";

export const ASSET_INDEX = "32";
export const JAR_TEXTURES = Object.freeze([
  ["assets/minecraft/textures/gui/title/minecraft.png", "web/assets/minecraft.png"],
  ["assets/minecraft/textures/font/ascii_sga.png", "web/assets/ascii_sga.png"],
]);
export const PANORAMA_KEYS = Object.freeze([
  ...Array.from({ length: 6 }, (_, index) => `minecraft/textures/gui/title/background/panorama_${index}.png`),
  "minecraft/textures/gui/title/background/panorama_overlay.png",
]);
export const PANORAMA_DIR = "src/graal/resources/assets/minecraft/textures/gui/title/background";
const MAX_CLIENT_JAR_BYTES = 512 * 1024 * 1024;
const MAX_ASSET_INDEX_BYTES = 64 * 1024 * 1024;
const MAX_ASSET_OBJECT_BYTES = 512 * 1024 * 1024;
const ROOT = path.resolve(fileURLToPath(new URL("..", import.meta.url)));

function readBoundedFile(file, maxBytes, label = file) {
  const info = fs.statSync(file);
  if (!Number.isSafeInteger(info.size) || info.size > maxBytes) {
    throw new Error(`${label} exceeds its ${maxBytes}-byte cache-file cap`);
  }
  const fd = fs.openSync(file, "r");
  const chunks = [];
  let remaining = info.size;
  try {
    while (remaining > 0) {
      const chunk = Buffer.allocUnsafe(Math.min(1024 * 1024, remaining));
      const read = fs.readSync(fd, chunk, 0, chunk.length, info.size - remaining);
      if (read <= 0) break;
      chunks.push(read === chunk.length ? chunk : chunk.subarray(0, read));
      remaining -= read;
    }
  } finally {
    fs.closeSync(fd);
  }
  const finalInfo = fs.statSync(file);
  if (finalInfo.size !== info.size) {
    throw new Error(`${label} changed while it was being read`);
  }
  return Buffer.concat(chunks, info.size - remaining);
}

function flag(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] && !process.argv[i + 1].startsWith("--")
    ? process.argv[i + 1] : fallback;
}

function findAssetsRoot() {
  const explicit = flag("assets", process.env.MC_ASSETS_DIR);
  if (explicit) return explicit;
  const home = os.homedir();
  const candidates = process.platform === "darwin"
    ? [path.join(home, "Library/Application Support/minecraft/assets")]
    : process.platform === "win32"
      ? [path.join(process.env.APPDATA || path.join(home, "AppData/Roaming"), ".minecraft/assets")]
      : [path.join(home, ".minecraft/assets"),
        path.join(process.env.XDG_CONFIG_HOME || path.join(home, ".config"), "minecraft/assets")];
  return candidates.find((dir) => fs.existsSync(dir));
}

// Minimal central-directory reader. Node has no jar API, and `unzip` is not a
// default tool on Windows, so this stays dependency-free and is shared by the
// CLI and the build helper.
export function readZipEntries(file) {
  const buf = readBoundedFile(file, MAX_CLIENT_JAR_BYTES, "client JAR");
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 22 - 0xffff; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error(`not a zip archive: ${file}`);

  let count = buf.readUInt16LE(eocd + 10);
  let offset = buf.readUInt32LE(eocd + 16);
  if (count === 0xffff || offset === 0xffffffff) {
    const loc = eocd - 20;
    if (loc >= 0 && buf.readUInt32LE(loc) === 0x07064b50) {
      const z64 = Number(buf.readBigUInt64LE(loc + 8));
      count = Number(buf.readBigUInt64LE(z64 + 32));
      offset = Number(buf.readBigUInt64LE(z64 + 48));
    }
  }

  const entries = new Map();
  let p = offset;
  for (let i = 0; i < count; i++) {
    if (p + 46 > buf.length || buf.readUInt32LE(p) !== 0x02014b50) break;
    const method = buf.readUInt16LE(p + 10);
    const compressedSize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localOffset = buf.readUInt32LE(p + 42);
    const name = buf.toString("utf8", p + 46, p + 46 + nameLen);
    entries.set(name, { method, compressedSize, localOffset });
    p += 46 + nameLen + extraLen + commentLen;
  }

  return {
    read(name) {
      const entry = entries.get(name);
      if (!entry) return null;
      const local = entry.localOffset;
      if (local + 30 > buf.length || buf.readUInt32LE(local) !== 0x04034b50) {
        throw new Error(`bad local header for ${name}`);
      }
      const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
      const end = start + entry.compressedSize;
      if (start < 0 || end > buf.length) throw new Error(`truncated zip entry ${name}`);
      const raw = buf.subarray(start, end);
      if (entry.method === 0) return Buffer.from(raw);
      if (entry.method === 8) return inflateRawSync(raw);
      throw new Error(`unsupported compression method ${entry.method} for ${name}`);
    },
  };
}

function sha1(bytes) {
  return createHash("sha1").update(bytes).digest("hex");
}

function assertPng(bytes, label) {
  if (!bytes || bytes.length < 8 || !bytes.subarray(0, 8).equals(Buffer.from("89504e470d0a1a0a", "hex"))) {
    throw new Error(`staged local asset is not a PNG: ${label}`);
  }
}

function safeDestination(root, relative) {
  const resolvedRoot = path.resolve(root);
  const destination = path.resolve(resolvedRoot, relative);
  if (destination !== resolvedRoot && !destination.startsWith(`${resolvedRoot}${path.sep}`)) {
    throw new Error(`refusing staged asset outside project root: ${relative}`);
  }
  return destination;
}

function requiredAssetObjects(index, assetsRoot, assetIndexId) {
  if (!index || typeof index.objects !== "object" || index.objects === null || Array.isArray(index.objects)) {
    throw new Error(`asset index ${assetIndexId} has no objects map`);
  }
  const objectsRoot = path.join(assetsRoot, "objects");
  return PANORAMA_KEYS.map((key) => {
    const meta = index.objects[key];
    const hash = typeof meta?.hash === "string" ? meta.hash.toLowerCase() : "";
    if (!/^[0-9a-f]{40}$/.test(hash)) throw new Error(`asset index ${assetIndexId} has no valid hash for ${key}`);
    const source = path.join(objectsRoot, hash.slice(0, 2), hash);
    if (!fs.existsSync(source)) throw new Error(`asset object is missing for ${key}: ${source}`);
    const bytes = readBoundedFile(source, MAX_ASSET_OBJECT_BYTES, `asset object ${key}`);
    if (meta.size !== undefined && Number(meta.size) !== bytes.length) {
      throw new Error(`asset size mismatch for ${key}: index ${meta.size}, file ${bytes.length}`);
    }
    const actual = sha1(bytes);
    if (actual !== hash) throw new Error(`asset hash mismatch for ${key}: expected ${hash}, got ${actual}`);
    assertPng(bytes, key);
    return { key, hash, source, bytes };
  });
}

/**
 * Validate all title assets first, then optionally write the complete ignored
 * staging set. No destination is touched when dryRun is true.
 */
export function stageMojangAssets({
  jarPath,
  assetsRoot,
  projectRoot = ROOT,
  assetIndexId = ASSET_INDEX,
  assetIndexSha1 = null,
  dryRun = false,
} = {}) {
  const root = path.resolve(projectRoot);
  const jar = path.resolve(jarPath || path.join(root, "minecraft-26.2-client.jar"));
  const selectedAssetsRoot = assetsRoot || findAssetsRoot();
  if (!fs.existsSync(jar)) throw new Error(`client jar not found: ${jar}`);
  if (!selectedAssetsRoot) throw new Error("no launcher asset store found; pass --assets <dir> or set MC_ASSETS_DIR");
  const assets = path.resolve(selectedAssetsRoot);
  if (!fs.existsSync(assets)) throw new Error(`launcher asset store not found: ${assets}`);

  const zip = readZipEntries(jar);
  const staged = [];
  for (const [entry, relative] of JAR_TEXTURES) {
    const bytes = zip.read(entry);
    if (!bytes) throw new Error(`client jar is missing required title asset: ${entry}`);
    assertPng(bytes, entry);
    staged.push({ source: jar, entry, relative, bytes });
  }

  const indexPath = path.join(assets, "indexes", `${assetIndexId}.json`);
  if (!fs.existsSync(indexPath)) throw new Error(`asset index ${assetIndexId} not found: ${indexPath}`);
  const indexBytes = readBoundedFile(indexPath, MAX_ASSET_INDEX_BYTES, `asset index ${assetIndexId}`);
  if (assetIndexSha1) {
    const actual = sha1(indexBytes);
    if (actual !== String(assetIndexSha1).toLowerCase()) {
      throw new Error(`asset index hash mismatch: expected ${assetIndexSha1}, got ${actual}`);
    }
  }
  let index;
  try {
    index = JSON.parse(indexBytes.toString("utf8"));
  } catch (error) {
    throw new Error(`asset index ${assetIndexId} is not valid JSON: ${error.message}`);
  }
  for (const object of requiredAssetObjects(index, assets, assetIndexId)) {
    staged.push({ source: object.source, entry: object.key, relative: path.join(PANORAMA_DIR, path.basename(object.key)), bytes: object.bytes });
  }

  const plan = {
    projectRoot: root,
    jarPath: jar,
    assetsRoot: assets,
    assetIndexId: String(assetIndexId),
    assetIndexPath: indexPath,
    files: staged.map(({ entry, relative, bytes }) => ({ entry, relative, bytes: bytes.length })),
    dryRun: Boolean(dryRun),
  };
  if (dryRun) {
    console.log(`dry-run: validated ${staged.length} local Mojang PNG files; would stage them under ignored web/assets and src/graal/resources`);
    return plan;
  }

  for (const file of staged) {
    const destination = safeDestination(root, file.relative);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.writeFileSync(destination, file.bytes);
    console.log(`  ${path.relative(root, destination)}  (${file.bytes.length} bytes)`);
  }
  console.log(`staged ${staged.length} local Mojang PNG files (asset index ${assetIndexId})`);
  return plan;
}

async function main() {
  const plan = stageMojangAssets({
    jarPath: flag("jar", path.join(ROOT, "minecraft-26.2-client.jar")),
    assetsRoot: findAssetsRoot(),
    projectRoot: ROOT,
    assetIndexId: flag("asset-index", ASSET_INDEX),
    dryRun: process.argv.includes("--dry-run"),
  });
  if (plan.dryRun) console.log("--dry-run: no staged bytes were written.");
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));
if (invokedDirectly) {
  try {
    await main();
  } catch (error) {
    console.error(`stage-mojang-assets: ${error.message}`);
    process.exitCode = 1;
  }
}
