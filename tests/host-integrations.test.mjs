import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { runInNewContext } from "node:vm";

const ROOT = new URL("..", import.meta.url).pathname;
const read = (name) => readFile(`${ROOT}/${name}`, "utf8");

test("self-hosted runtime loads the skin and resource-pack seams before WebGPU", async () => {
  const bootstrap = await read("web/dev/runtime-bootstrap.js");
  const hostList = bootstrap.slice(bootstrap.indexOf("const HOST_FILES"), bootstrap.indexOf("function profile"));
  assert.match(hostList, /"persistent-storage\.js",\s*"resource-packs\.js",\s*"mc-net\.js",\s*"skin-fetch\.js",/s);
  assert.match(hostList, /"webgpu-texture-lifetime\.js",\s*"webgpu-frame-pacing\.js",\s*"input-mapping\.js",\s*"webgpu-host\.js",/s);
  assert.match(bootstrap, /for \(const name of HOST_FILES\) await loadScript\(name\)/);
  assert.match(await read("web/dev/resource-packs.js"), /globalThis\.mcWebServerPacks/);
  assert.match(await read("web/dev/skin-fetch.js"), /globalThis\.mcWebSkinFetch/);
});

test("self-hosted seams contain no hosted profile-verifier override", async () => {
  const [skin, resource] = await Promise.all([
    read("web/dev/skin-fetch.js"),
    read("web/dev/resource-packs.js"),
  ]);
  assert.doesNotMatch(skin, /mcweb_relay/i);
  assert.match(skin, /\/mcweb\/verify-profile-property/);
  assert.match(resource, /\/mcweb\/pack/);
  assert.ok(resource.includes('script.src = src.startsWith("/") ? src : `/dev/${src}`;'));
});

test("skin signature verification stays same-origin and returns only explicit validity", async () => {
  const source = await read("web/dev/skin-fetch.js");
  const requests = [];
  const context = {
    URL,
    btoa: (value) => Buffer.from(value, "binary").toString("base64"),
    location: { href: "http://127.0.0.1:4199/" },
    fetch: async (url, options) => {
      requests.push({ url: String(url), options });
      return { ok: true, json: async () => ({ valid: true }) };
    },
  };
  runInNewContext(source, context, { filename: "skin-fetch.js" });
  let result = null;
  context.mcWebSkinFetch.onSignatureResult((id, valid) => { result = { id, valid }; });
  await context.mcWebSkinFetch.verifyProfileProperty(7, "value", "signature");
  assert.equal(result?.id, 7);
  assert.equal(result?.valid, true);
  assert.equal(new URL(requests[0].url).pathname, "/mcweb/verify-profile-property");
  assert.equal(requests[0].options.credentials, undefined);
});

test("launcher captures the Play button before awaiting diagnostics", async () => {
  const source = await read("web/dev/dev.js");
  const play = source.slice(source.indexOf('id="dev-play-button"') >= 0
    ? source.indexOf('id="dev-play-button"') : source.indexOf('getElementById("dev-play-button")'));
  assert.match(play, /const button = event\.currentTarget;\s*await globalThis\.mcWebDevDiagnostics\?\.ready\?\.\(\);/s);
  assert.doesNotMatch(play, /await globalThis\.mcWebDevDiagnostics\?\.ready\?\.\(\);\s*event\.currentTarget/);
});

test("unlocked points retain backing-store scaling while locked deltas stay raw", async () => {
  const source = await read("web/dev/input-mapping.js");
  const context = {};
  runInNewContext(source, context, { filename: "input-mapping.js" });
  const mapping = context.mcWebInputMapping;
  assert.deepEqual(Array.from(mapping.point(10, 20, { left: 0, top: 0, width: 100, height: 100 }, 200, 300)), [20, 60]);
  assert.deepEqual(Array.from(mapping.lockedDelta(10, -20, { width: 100, height: 100 }, 200, 300)), [10, -20]);
});

test("local launcher exposes the pre-Play resource-pack controls and lifecycle handoff", async () => {
  const [html, bootstrap] = await Promise.all([
    read("web/index.html"),
    read("web/dev/runtime-bootstrap.js"),
  ]);
  for (const id of ["pack-toggle", "pack-drop", "pack-pick-file", "pack-pick-dir",
    "pack-file-input", "pack-dir-input", "pack-status", "pack-list"]) {
    assert.match(html, new RegExp(`id=["']${id}["']`));
  }
  assert.match(bootstrap, /globalThis\.mcWebLifecycle =/);
  assert.match(bootstrap, /await globalThis\.mcWebStorage\?\.flush\?\.\(\)/);
  assert.match(bootstrap, /location\.replace\(url\.href\)/);
});
