import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  JAR_TEXTURES,
  PANORAMA_KEYS,
  stageMojangAssets,
} from "../tools/stage-mojang-assets.mjs";

const png = (tag) => Buffer.concat([
  Buffer.from("89504e470d0a1a0a", "hex"),
  Buffer.from(String(tag)),
]);

// The staging reader only needs ordinary stored ZIP entries. Keeping this tiny
// fixture writer here avoids a dependency and never touches a real game file.
function storedZip(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, data] of entries) {
    const nameBytes = Buffer.from(name);
    const bytes = Buffer.from(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt32LE(bytes.length, 18);
    local.writeUInt32LE(bytes.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    locals.push(Buffer.concat([local, nameBytes, bytes]));

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt32LE(bytes.length, 20);
    central.writeUInt32LE(bytes.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(Buffer.concat([central, nameBytes]));
    offset += locals.at(-1).length;
  }
  const centralOffset = offset;
  const centralBytes = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBytes.length, 12);
  end.writeUInt32LE(centralOffset, 16);
  return Buffer.concat([...locals, centralBytes, end]);
}

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), "mcweb-stage-assets-"));
  const checkout = join(root, "checkout");
  const assetsRoot = join(root, "assets");
  await mkdir(join(assetsRoot, "indexes"), { recursive: true });
  const objects = {};
  const objectFiles = [];
  for (const [index, key] of PANORAMA_KEYS.entries()) {
    const bytes = png(`panorama-${index}`);
    const hash = createHash("sha1").update(bytes).digest("hex");
    objects[key] = { hash, size: bytes.length };
    const destination = join(assetsRoot, "objects", hash.slice(0, 2), hash);
    await mkdir(join(destination, ".."), { recursive: true });
    await writeFile(destination, bytes);
    objectFiles.push(destination);
  }
  const indexBytes = Buffer.from(JSON.stringify({ objects }));
  await writeFile(join(assetsRoot, "indexes", "32.json"), indexBytes);
  const jarPath = join(root, "client.jar");
  await writeFile(jarPath, storedZip(JAR_TEXTURES.map(([entry], index) => [entry, png(`jar-${index}`)])));
  return {
    root,
    checkout,
    assetsRoot,
    jarPath,
    indexSha1: createHash("sha1").update(indexBytes).digest("hex"),
    objectFiles,
  };
}

test("stages the complete title set after validating the synthetic index and hashes", async () => {
  const value = await fixture();
  try {
    const plan = stageMojangAssets({
      jarPath: value.jarPath,
      assetsRoot: value.assetsRoot,
      projectRoot: value.checkout,
      assetIndexSha1: value.indexSha1,
    });
    assert.equal(plan.files.length, 9);
    assert.equal(plan.dryRun, false);
    for (const file of plan.files) {
      const bytes = await readFile(join(value.checkout, file.relative));
      assert.equal(bytes.length, file.bytes, file.relative);
    }
    assert.equal(await readFile(join(value.checkout, "web/assets/minecraft.png")).then((bytes) => bytes.at(-1)), "0".charCodeAt(0));
  } finally {
    await rm(value.root, { recursive: true, force: true });
  }
});

test("dry-run validates but does not create a staged payload", async () => {
  const value = await fixture();
  try {
    const plan = stageMojangAssets({
      jarPath: value.jarPath,
      assetsRoot: value.assetsRoot,
      projectRoot: value.checkout,
      assetIndexSha1: value.indexSha1,
      dryRun: true,
    });
    assert.equal(plan.dryRun, true);
    assert.equal(plan.files.length, 9);
    await assert.rejects(readFile(join(value.checkout, "web/assets/minecraft.png")), { code: "ENOENT" });
  } finally {
    await rm(value.root, { recursive: true, force: true });
  }
});

test("rejects a content-addressed object whose bytes do not match the index", async () => {
  const value = await fixture();
  try {
    await writeFile(value.objectFiles[0], png("tampered"));
    assert.throws(() => stageMojangAssets({
      jarPath: value.jarPath,
      assetsRoot: value.assetsRoot,
      projectRoot: value.checkout,
      assetIndexSha1: value.indexSha1,
    }), /asset (?:size|hash) mismatch/);
  } finally {
    await rm(value.root, { recursive: true, force: true });
  }
});

test("rejects an asset index whose bytes do not match the manifest", async () => {
  const value = await fixture();
  try {
    assert.throws(() => stageMojangAssets({
      jarPath: value.jarPath,
      assetsRoot: value.assetsRoot,
      projectRoot: value.checkout,
      assetIndexSha1: "0".repeat(40),
    }), /asset index hash mismatch/);
  } finally {
    await rm(value.root, { recursive: true, force: true });
  }
});
