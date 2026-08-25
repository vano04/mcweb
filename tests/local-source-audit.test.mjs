import assert from "node:assert/strict";
import { readFile, readdir, stat } from "node:fs/promises";
import { extname, join, relative } from "node:path";
import test from "node:test";

const ROOT = new URL("..", import.meta.url).pathname;
const TEXT_EXTENSIONS = new Set([".html", ".js", ".mjs", ".java", ".gradle", ".md", ".json", ".css", ".svg", ".properties"]);

async function exists(path) {
  return !!(await stat(path).catch(() => null));
}

async function filesUnder(directory) {
  const output = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    // Generated local bundles may exist after a developer runs the build. They
    // are ignored and are not part of the source-only audit; otherwise the one
    // official pointer in web/index.html is counted again from build/web-graal.
    if (["tests", "build", "dist", ".gradle"].includes(entry.name)) continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) output.push(...await filesUnder(path));
    else if (TEXT_EXTENSIONS.has(extname(entry.name)) || entry.name === "wasm-as-debugnames") output.push(path);
  }
  return output;
}

test("the local distribution is the dev launcher, not the old dashboard", async () => {
  const index = await readFile(join(ROOT, "web/index.html"), "utf8");
  assert.match(index, /MC-WEB \/ SELF-HOSTED/);
  assert.match(index, /Verify your Minecraft account/);
  assert.match(index, /Sign in with Microsoft here, or open your launcher and sign in/);
  assert.match(index, /For launcher sign-in, return here/);
  assert.match(index, /Win<\/kbd>\+<kbd>R/);
  assert.ok(index.includes("%APPDATA%\\.minecraft\\launcher_accounts.json"));
  assert.match(index, /Go → Go to Folder/);
  assert.ok(index.includes("~/Library/Application Support/minecraft/launcher_accounts.json"));
  assert.ok(index.includes("~/Library/Application Support/PrismLauncher/accounts.json"));
  assert.ok(index.includes("~/.minecraft/launcher_accounts.json"));
  assert.match(index, /sends the selected file only to this loopback Node process/i);
  assert.match(index, /PrismLauncher/);
  assert.match(index, /<input[^>]+type=["']file["']/i);
  assert.match(index, /BUILD GAME FOR BROWSER/);
  assert.match(index, /INSTALL IN THIS BROWSER/);
  assert.match(index, /PLAY MINECRAFT/);
  assert.match(index, /BUILD FROM THE TERMINAL/);
  assert.doesNotMatch(index, /view-(?:login|install|play)/);
  assert.doesNotMatch(index, new RegExp("href=[\\\"']" + "/pr" + "ivacy"));
});

test("the hybrid dev source wires Microsoft auth through the Cloudflare relay", async () => {
  const paths = await filesUnder(ROOT);
  const text = (await Promise.all(paths.map(async (path) => `${relative(ROOT, path)}\n${await readFile(path, "utf8")}`))).join("\n");
  assert.match(text, /wss:\/\/tcp\.wasm\.click\/mcweb\/socket/);
  assert.match(text, /MCWEB_MS_CLIENT_ID/);
  assert.match(text, /microsoft-browser/);
  const officialDomain = ["minecraft", "wasm", "click"].join(".");
  assert.equal((text.match(new RegExp(officialDomain, "g")) || []).length, 2,
    "the production hostname appears only in the official link and the validated relay Origin allowlist");
  assert.match(text, /Official deployment:/);
  assert.match(text, /GitHub:\s*<a href="https:\/\/github\.com\/vano04\/mcweb">https:\/\/github\.com\/vano04\/mcweb<\/a>/);
  const oldBuildLabels = new RegExp("cb" + "10|cb" + "11", "i");
  const oldPolicy = new RegExp("\\/pr" + "ivacy|Pri" + "vacy", "i");
  const relayOverride = new RegExp("mcweb_" + "relay", "i");
  for (const forbidden of [oldBuildLabels, oldPolicy, relayOverride]) {
    assert.doesNotMatch(text, forbidden, forbidden.toString());
  }
  assert.doesNotMatch(text, /MC_RELAY_WORKER_SECRET\s*=\s*[A-Za-z0-9_-]{32,}/);
});

test("the shared launcher documents the local Node command and unpinned relay boundary", async () => {
  const readme = await readFile(join(ROOT, "README.md"), "utf8");
  assert.match(readme, /node tools\/build\.mjs/);
  assert.match(readme, /build\/web-graal\/graal\/minecraft-client\.js\.wasm/);
  assert.match(readme, /10 GB of RAM available/);
  assert.match(readme, /tcp\.wasm\.click/);
  assert.doesNotMatch(readme, /mc\.belenko\.dev:25565/);
  assert.match(readme, /default is `\*`/);
  assert.match(readme, /host:port/i);
  assert.match(readme, /same-origin/);
  assert.match(readme, /Win\+R/);
  assert.ok(readme.includes("%APPDATA%\\.minecraft\\launcher_accounts.json"));
  assert.ok(readme.includes("~/Library/Application Support/minecraft/launcher_accounts.json"));
  assert.ok(readme.includes("~/Library/Application Support/PrismLauncher/accounts.json"));
  assert.ok(readme.includes("~/.minecraft/launcher_accounts.json"));
  assert.match(readme, /PrismLauncher/);
});

test("How to run starts from inspectable source and invokes local wrappers", async () => {
  const readme = await readFile(join(ROOT, "README.md"), "utf8");
  assert.match(readme, /git clone https:\/\/github\.com\/vano04\/mcweb\.git/);
  assert.match(readme, /https:\/\/github\.com\/vano04\/mcweb\/archive\/refs\/heads\/main\.zip/);
  assert.match(readme, /sh \.\/run\.sh/);
  assert.match(readme, /powershell\.exe -NoProfile -ExecutionPolicy Bypass -File \.\\run\.ps1/);
  assert.doesNotMatch(readme, /raw\.githubusercontent\.com/i);
  assert.doesNotMatch(readme, /curl[^\n]*(?:\|\s*(?:sh|bash)|install\.ps1)/i);
  assert.doesNotMatch(readme, /(?:Invoke-WebRequest|irm\s*\|\s*iex)/i);
});

test("generated or proprietary payloads are absent from the copy", async () => {
  const paths = await filesUnder(ROOT);
  for (const path of paths) {
    const name = relative(ROOT, path);
    if (/gradle\/wrapper\/gradle-wrapper\.jar$/.test(name)) continue;
    assert.doesNotMatch(name, /\.wasm$|\.ogg$|\.jar$|minecraft-.*\.jar$/i, name);
  }
});

test("root build and run entrypoints remain source-only", async () => {
  const paths = ["build.sh", "build.ps1", "run.sh", "run.ps1"]
    .map((name) => join(ROOT, name));
  const text = (await Promise.all(paths.map(async (path) => readFile(path, "utf8")))).join("\n");
  assert.match(text, /--build/);
  assert.match(text, /dev-server\.mjs/);
  assert.doesNotMatch(text, /https?:\/\//);
  assert.doesNotMatch(text, /\.ogg/);
  assert.doesNotMatch(text, /minecraft\.wasm\.click|tcp\.wasm\.click|cloudflare/i);
});

test("the public launcher exposes only the canonical WasmGC runtime lane", async () => {
  const [host, gradle, toolsReadme] = await Promise.all([
    readFile(join(ROOT, "web/dev/webgpu-host.js"), "utf8"),
    readFile(join(ROOT, "build.gradle"), "utf8"),
    readFile(join(ROOT, "tools/README.md"), "utf8"),
  ]);
  for (const path of [
    "tools/wasm-share-memory.mjs",
    "tools/stage-wasmlm-browser.mjs",
    "tools/webimage-patch",
    "src/webimage-patch",
    "src/graal/java/dev/mcweb/graal/WasmLMBootstrapProbeMain.java",
    "src/graal/java/dev/mcweb/graal/WasmLMUtilProbeMain.java",
    "src/graal/java/dev/mcweb/graal/ThreadConformanceMain.java",
    "src/graal/java/dev/mcweb/graal/WorkerPoolProbeMain.java",
  ]) assert.equal(await exists(join(ROOT, path)), false, `${path} must not be shipped`);
  assert.doesNotMatch(host, /mcweb_server_image|mcweb_threads|mcWebWasmLMThreads/);
  assert.match(host, /const serverImage = "minecraft-client"/);
  assert.doesNotMatch(host, /mcWebRuntimeMode|mcWebThreadRuntime|rpCommandStreamRaw|writeTextureRaw/);
  assert.doesNotMatch(gradle, /graalBackend|wasmLMThreadArtifacts|stage-wasmlm-browser|wasm-share-memory/);
  assert.doesNotMatch(gradle, /webImagePatch|webimage-patch|MCWEB_PATCH_CLASS_INIT/);
  assert.match(gradle, /windowsPointstoPatch/);
  assert.match(gradle, /org\.graalvm\.nativeimage\.pointsto/);
  assert.match(gradle, /--features=dev\.mcweb\.feature\.BrowserParkerFeature/);
  assert.match(toolsReadme, /canonical `minecraft-client`\s+WasmGC pair/);
  const paths = await filesUnder(join(ROOT, "src"));
  const source = (await Promise.all(paths.map(async (path) => readFile(path, "utf8")))).join("\n");
  assert.doesNotMatch(source, /WasmLM|WASMLM|wasmlm|mcWebRuntimeMode|mcWebThreadRuntime/);
});

test("render command replay preserves interned binding name ids", async () => {
  const host = await readFile(join(ROOT, "web/dev/webgpu-host.js"), "utf8");
  const handlers = host.slice(
    host.indexOf("const _renderCommandHandlers"),
    host.indexOf("function replayRenderPassCommands")
  );

  assert.match(
    handlers,
    /bindTexture\(host, pass, nameId, view, sampler\)\s*\{\s*_renderPassReplay\.bindTexture\.call\(host, pass, nameId, view, sampler\);/
  );
  assert.match(
    handlers,
    /setUniform\(host, pass, nameId, buffer, offset, size\)\s*\{\s*_renderPassReplay\.setUniform\.call\(host, pass, nameId, buffer, offset, size\);/
  );
  assert.doesNotMatch(handlers, /resolveBindingName/);
});

test("the host build tag comes from the verified runtime hash", async () => {
  const host = await readFile(join(ROOT, "web/dev/webgpu-host.js"), "utf8");
  assert.match(host, /const MCWEB_BUILD = `build-\$\{runtimeWasm\.sha256\.slice\(0, 12\)\.toLowerCase\(\)\}`;/);
  assert.doesNotMatch(host, /ios-streaming|iphone/i);
});
