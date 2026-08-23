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
  assert.match(index, /Launcher session/);
  assert.match(index, /Win<\/kbd>\+<kbd>R/);
  assert.ok(index.includes("%APPDATA%\\.minecraft\\launcher_accounts.json"));
  assert.match(index, /Go → Go to Folder/);
  assert.ok(index.includes("~/Library/Application Support/minecraft/launcher_accounts.json"));
  assert.ok(index.includes("~/Library/Application Support/PrismLauncher/accounts.json"));
  assert.ok(index.includes("~/.minecraft/launcher_accounts.json"));
  assert.match(index, /selected file is sent only to this loopback Node process/i);
  assert.match(index, /PrismLauncher/);
  assert.match(index, /<input[^>]+type=["']file["']/i);
  assert.match(index, /BUILD WASM IMAGE/);
  assert.match(index, /BUILD FROM TERMINAL/);
  assert.doesNotMatch(index, /view-(?:login|install|play)/);
  assert.doesNotMatch(index, new RegExp("href=[\\\"']" + "/pr" + "ivacy"));
});

test("public source has one official pointer and no hosted relay/front-door residue", async () => {
  const paths = await filesUnder(ROOT);
  const text = (await Promise.all(paths.map(async (path) => `${relative(ROOT, path)}\n${await readFile(path, "utf8")}`))).join("\n");
  assert.equal((text.match(/mc\.belenko\.dev/gi) || []).length, 0);
  const officialDomain = ["minecraft", "wasm", "click"].join(".");
  assert.equal((text.match(new RegExp(officialDomain, "g")) || []).length, 1);
  assert.match(text, /Official deployment:/);
  assert.match(text, /GitHub:\s*<a href="https:\/\/github\.com\/vano04\/mcweb">https:\/\/github\.com\/vano04\/mcweb<\/a>/);
  const oldBuildLabels = new RegExp("cb" + "10|cb" + "11", "i");
  const hostedRelay = new RegExp(["tcp", "wasm", "click"].join("\\."), "i");
  const oldPolicy = new RegExp("\\/pr" + "ivacy|Pri" + "vacy", "i");
  const publicAuth = new RegExp(["MCWEB", "MS", "CLIENT", "ID"].join("_") + "|microsoft-" + "auth", "i");
  const hostedPlatform = new RegExp("cloud" + "flare", "i");
  const relayOverride = new RegExp("mcweb_" + "relay", "i");
  for (const forbidden of [hostedRelay, oldBuildLabels, oldPolicy,
    publicAuth, hostedPlatform, relayOverride]) {
    assert.doesNotMatch(text, forbidden, forbidden.toString());
  }
  assert.match(text, /MC_RELAY_ALLOW=\*/);
  assert.match(text, /MC_RELAY_ALLOW=.*play\.example\.net:25565/);
});

test("the shared launcher documents the original local Node command and wildcard boundary", async () => {
  const readme = await readFile(join(ROOT, "README.md"), "utf8");
  assert.match(readme, /node tools\/build\.mjs/);
  assert.match(readme, /build\/web-graal\/graal\/minecraft-client\.js\.wasm/);
  assert.match(readme, /10 GB of RAM available/);
  assert.match(readme, /MC_RELAY_ALLOW=\*/);
  assert.match(readme, /comma-separated/i);
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

test("root install and run entrypoints remain source-only and repository-pinned", async () => {
  assert.equal(await exists(join(ROOT, "install.sh")), false, "the Unix root bootstrap is named install");
  const paths = ["install", "install.ps1", "run.sh", "run.ps1"].map((name) => join(ROOT, name));
  const text = (await Promise.all(paths.map(async (path) => readFile(path, "utf8")))).join("\n");
  assert.match(text, /vano04\/mcweb/);
  assert.match(text, /refs\/heads/);
  assert.match(text, /codeload\.github\.com/);
  assert.match(text, /mcweb-install\.json/);
  assert.doesNotMatch(text, /minecraft-26\.2-client\.jar|\.wasm|\.ogg/);
  assert.doesNotMatch(text, /minecraft\.wasm\.click|tcp\.wasm\.click|cloudflare/i);
});
