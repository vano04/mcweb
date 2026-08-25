import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { runInNewContext } from "node:vm";

const ROOT = new URL("..", import.meta.url).pathname;
const BOOTSTRAP = await readFile(`${ROOT}/web/dev/runtime-bootstrap.js`, "utf8");

function runtimeContext(pageOrigin, socketUrl) {
  const page = new URL(`${pageOrigin}/`);
  const phases = [];
  const elements = new Map();
  const element = (id) => elements.get(id) || elements.set(id, {
    hidden: false,
    textContent: "",
    dataset: {},
    focus: () => {},
    removeAttribute: () => {},
  }).get(id);
  const pointer = {
    version: `v-${"a".repeat(20)}`,
    manifest: {
      version: 1,
      loader: "instantiate-streaming-v1",
      files: [
        { name: "minecraft-client.js", bytes: 1, sha256: "a".repeat(64) },
        { name: "minecraft-client.js.wasm", bytes: 2, sha256: "b".repeat(64) },
      ],
    },
  };
  const cache = {
    match: async (url) => String(url).endsWith("current.json")
      ? { json: async () => pointer }
      : { headers: { get: (name) => name.toLowerCase() === "content-length"
        ? String(String(url).endsWith(".wasm") ? 2 : 1) : null },
        blob: async () => new Blob([String(url).endsWith(".wasm") ? "ww" : "l"]) },
  };
  const context = {
    URL,
    URLSearchParams,
    queueMicrotask,
    setTimeout,
    clearTimeout,
    location: {
      href: page.href,
      origin: page.origin,
      protocol: page.protocol,
      hostname: page.hostname,
      port: page.port,
    },
    navigator: {},
    caches: { open: async () => cache },
    document: {
      body: {
        append: (script) => queueMicrotask(() => script.onload?.()),
        dataset: {},
        style: {},
      },
      createElement: () => ({}),
      getElementById: element,
    },
    mcWebDevAuthAccepted: true,
    mcWebDevProfile: { id: "player", name: "Player" },
    mcWebDevGateway: {
      ready: true,
      provider: "node",
      localNode: true,
      allowAnyTarget: true,
      allowedTargets: ["*"],
      profile: { id: "player", name: "Player" },
      socketUrl,
    },
    mcWebDevRuntimeReady: true,
    mcWebDevDiagnostics: { mark: (phase) => phases.push(phase) },
  };
  runInNewContext(BOOTSTRAP, context, { filename: "runtime-bootstrap.js" });
  return { context, phases };
}

async function start(pageOrigin, socketUrl) {
  const value = runtimeContext(pageOrigin, socketUrl);
  await value.context.mcWebRuntime.start();
  return value;
}

test("normalizes an HTTP page and same-host WS gateway to the local path", async () => {
  const { context } = await start(
    "http://127.0.0.1:4199",
    "ws://127.0.0.1:4199/mcweb/socket",
  );
  assert.equal(context.mcWebConfig.auth.online, true);
  assert.equal(context.mcWebConfig.gateway.socketPath, "/mcweb/socket");
});

test("normalizes an HTTPS page and same-host WSS gateway to the local path", async () => {
  const { context } = await start(
    "https://127.0.0.1",
    "wss://127.0.0.1/mcweb/socket",
  );
  assert.equal(context.mcWebConfig.auth.online, true);
  assert.equal(context.mcWebConfig.gateway.socketPath, "/mcweb/socket");
});

for (const [label, pageOrigin, socketUrl] of [
  ["cross-host", "http://127.0.0.1:4199", "ws://127.0.0.2:4199/mcweb/socket"],
  ["wrong-port", "http://127.0.0.1:4199", "ws://127.0.0.1:4200/mcweb/socket"],
  ["wrong-protocol", "http://127.0.0.1:4199", "wss://127.0.0.1:4199/mcweb/socket"],
  ["private-path", "http://127.0.0.1:4199", "ws://127.0.0.1:4199/private/socket"],
]) {
  test(`rejects ${label} gateway metadata before enabling online play`, async () => {
    const { context } = await start(pageOrigin, socketUrl);
    assert.equal(context.mcWebConfig.auth.online, false);
    assert.equal(context.mcWebConfig.gateway.socketPath, "/mcweb/socket");
    assert.equal(context.mcWebConfig.gateway.allowAnyTarget, false);
  });
}
