import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { runtimeAvailable, runtimeManifest } from "../tools/runtime-manifest.mjs";

test("runtime health uses stat availability and the manifest hash is cached by file signature", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "mcweb-runtime-manifest-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(join(root, "graal"));
  await writeFile(join(root, "graal", "minecraft-client.js"), "loader");
  await writeFile(join(root, "graal", "minecraft-client.js.wasm"), "wasm-v1");

  assert.equal(runtimeAvailable(root), true);
  const first = runtimeManifest(root);
  assert.equal(first.files[0].bytes, 6);
  assert.equal(first.files[1].bytes, 7);
  assert.strictEqual(runtimeManifest(root), first, "unchanged runtime should reuse the cached manifest object");

  await writeFile(join(root, "graal", "minecraft-client.js.wasm"), "wasm-v2-longer");
  const changed = runtimeManifest(root);
  assert.notStrictEqual(changed, first);
  assert.equal(changed.files[1].bytes, 14);
  assert.notEqual(changed.files[1].sha256, first.files[1].sha256);

  await rm(join(root, "graal", "minecraft-client.js.wasm"));
  assert.equal(runtimeAvailable(root), false);
  assert.equal(runtimeManifest(root), null);
});

