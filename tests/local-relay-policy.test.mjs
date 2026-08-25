import assert from "node:assert/strict";
import { Readable } from "node:stream";
import test from "node:test";
import {
  handleAuthSession,
  handleLauncherAccountsUpload,
  handleMinecraftGatewayUpgrade,
  isUnsafeMinecraftHost,
  minecraftTargetPolicy,
  resolveMinecraftTarget,
} from "../tools/mc-relay.mjs";

test("the default policy accepts valid public Minecraft targets", () => {
  const policy = minecraftTargetPolicy();
  assert.equal(policy.valid, true);
  assert.equal(policy.wildcard, true);
  assert.deepEqual(policy.allowedTargets, ["*"]);
  assert.equal(policy.allows("play.example.net", 25565), true);
  assert.equal(policy.allows("MC.BELENKO.DEV", "25565"), true);
  assert.deepEqual(resolveMinecraftTarget("play.example.net", 25565, policy), {
    host: "play.example.net", port: 25565, target: "play.example.net:25565", redirected: false,
  });
});

test("the default Cloudflare policy refuses unsafe private and malformed targets", () => {
  const policy = minecraftTargetPolicy();
  for (const [host, port] of [
    ["localhost", 25565],
    ["127.0.0.1", 25565],
    ["bad host", 25565],
    ["play.example.net", 65536],
  ]) {
    assert.equal(policy.allows(host, port), false, `${host}:${port}`);
    assert.throws(() => resolveMinecraftTarget(host, port, policy), /not allowed/);
  }
});

test("an explicit comma-separated allowlist accepts only exact host:port entries", () => {
  const policy = minecraftTargetPolicy("play.example.net:25565,another.example:25566");
  assert.equal(policy.valid, true);
  assert.equal(policy.wildcard, false);
  assert.deepEqual(policy.allowedTargets, ["play.example.net:25565", "another.example:25566"]);
  assert.equal(policy.allows("play.example.net", 25565), true);
  assert.equal(policy.allows("play.example.net", 25566), false);
  assert.equal(policy.allows("unlisted.example", 25565), false);
});

test("wildcard rejects unsafe local destinations but exact private allowlisting opts in", () => {
  const wildcard = minecraftTargetPolicy("*");
  for (const [host, port] of [
    ["localhost", 25565],
    ["127.0.0.1", 25565],
    ["10.0.0.1", 25565],
    ["172.16.0.1", 25565],
    ["192.168.1.1", 25565],
    ["169.254.169.254", 80],
    ["100.64.0.1", 25565],
    ["192.0.2.1", 25565],
    ["198.18.0.1", 25565],
    ["203.0.113.1", 25565],
    ["224.0.0.1", 25565],
    ["::", 25565],
    ["::1", 25565],
    ["0:0:0:0:0:0:0:0", 25565],
    ["0:0:0:0:0:0:0:1", 25565],
    ["::ffff:c0a8:0101", 25565],
    ["0:0:0:0:0:ffff:c0a8:0101", 25565],
    ["fc00::1", 25565],
    ["fe80::1", 25565],
    ["fec0::1", 25565],
    ["ff02::1", 25565],
    ["2001:0db8:0:0:0:0:0:1", 25565],
    ["2001:2::1", 25565],
    ["2001:10::1", 25565],
    ["2001:1f::1", 25565],
    ["2001:20::1", 25565],
    ["2001:2f::1", 25565],
    ["100::1", 25565],
    ["3ffe::1", 25565],
    ["metadata.google.internal", 25565],
    ["server.local", 25565],
  ]) {
    assert.equal(isUnsafeMinecraftHost(host), true, host);
    assert.equal(wildcard.allows(host, port), false, host);
  }
  const explicit = minecraftTargetPolicy("127.0.0.1:25565");
  assert.equal(explicit.allows("127.0.0.1", 25565), true);
  assert.deepEqual(explicit.explicitlyAllowedPrivateTargets, ["127.0.0.1:25565"]);
  const explicitIpv6 = minecraftTargetPolicy("[::1]:25565");
  assert.equal(explicitIpv6.allows("0:0:0:0:0:0:0:1", 25565), true);
  assert.deepEqual(explicitIpv6.explicitlyAllowedPrivateTargets, ["[::1]:25565"]);
});

test("wildcard, malformed, SRV-style, and invalid entries fail closed", () => {
  for (const value of [
    "*,play.example.net:25565",
    "",
    "play.example.net",
    "_minecraft._tcp.example:25565",
    "play.example.net:0",
    "play.example.net:65536",
    "play.example.net:25565.0",
    "play.example.net:0x63dd",
    "play.example.net: 25565",
    "play.example.net:25565 ",
    " play.example.net:25565",
    "https://play.example.net:25565",
    "play.example.net:25565,",
    ",play.example.net:25565",
    "play.example.net:25565,,another.example:25566",
    "*,,",
  ]) {
    const policy = minecraftTargetPolicy(value);
    assert.equal(policy.valid, false, value);
    assert.deepEqual(policy.allowedTargets, [], value);
    assert.equal(policy.allows("play.example.net", 25565), false, value);
  }
});

test("request ports use the same canonical decimal grammar as the allowlist", () => {
  const policy = minecraftTargetPolicy("play.example.net:25565");
  for (const port of ["25565.0", "0x63dd", " 25565", "025565", "+25565"]) {
    assert.equal(policy.allows("play.example.net", port), false, port);
    assert.throws(() => resolveMinecraftTarget("play.example.net", port, policy), /not allowed/);
  }
  assert.equal(policy.allows("play.example.net", "25565"), true);
  assert.equal(policy.allows("play.example.net", 25565), true);
});

test("wildcard accepts canonical public IPv6 while rejecting alternate spellings of unsafe ranges", () => {
  const wildcard = minecraftTargetPolicy("*");
  for (const host of [
    "2001:4860:4860::8888",
    "2606:4700:4700::1111",
    "2001:db9::1",
  ]) {
    assert.equal(wildcard.allows(host, 25565), true, host);
  }
  for (const host of [
    "::ffff:7f00:1",
    "0:0:0:0:0:ffff:7f00:1",
    "2001:0db8:0:0:0:0:0:1",
    "0:0:0:0:0:0:0:1",
  ]) {
    assert.equal(wildcard.allows(host, 25565), false, host);
  }
});

test("IPv6 exact targets use bracketed host:port syntax", () => {
  const policy = minecraftTargetPolicy("[2001:db8::20]:25565");
  assert.equal(policy.valid, true);
  assert.equal(policy.allows("2001:DB8::20", 25565), true);
  assert.equal(policy.allows("[2001:db8::20]", 25566), false);
});

test("the WebSocket boundary rejects an unsafe local target under wildcard", () => {
  const writes = [];
  const socket = {
    destroyed: false,
    end(value) { writes.push(String(value)); },
    destroy() { this.destroyed = true; },
  };
  handleMinecraftGatewayUpgrade({
    url: "/mcweb/socket?host=127.0.0.1&port=25565",
    headers: { "sec-websocket-key": "test-key" },
  }, socket);
  assert.equal(writes.length, 1);
  assert.match(writes[0], /^HTTP\/1\.1 403 Forbidden/);
});

function syntheticOfficialDocument() {
  return {
    activeAccountLocalId: "one",
    accounts: {
      one: {
        type: "MSA",
        accessToken: "synthetic-upload-token",
        accessTokenExpiresAt: "2099-01-01T00:00:00.000Z",
        minecraftProfile: {
          id: "0123456789abcdef0123456789abcdef",
          name: "UploadPlayer",
        },
      },
    },
  };
}

function responseCapture() {
  return {
    status: null,
    headers: null,
    body: "",
    writeHead(status, headers) { this.status = status; this.headers = headers; },
    end(value = "") { this.body += String(value); },
  };
}

test("a failed replacement upload clears the old server session", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url) => new Response(JSON.stringify(
    String(url).endsWith("entitlements/mcstore")
      ? { items: [{ name: "product_minecraft" }] }
      : { id: "0123456789abcdef0123456789abcdef", name: "UploadPlayer", skins: [] },
  ), { status: 200 });
  try {
    const accepted = responseCapture();
    await handleLauncherAccountsUpload(
      Readable.from([Buffer.from(JSON.stringify(syntheticOfficialDocument()))]), accepted,
    );
    assert.equal(accepted.status, 200);
    assert.doesNotMatch(accepted.body, /synthetic-upload-token|accessToken/);

    const replaced = responseCapture();
    await handleLauncherAccountsUpload(Readable.from([Buffer.from("{not-json")]), replaced);
    assert.equal(replaced.status, 400);

    const session = responseCapture();
    await handleAuthSession({}, session);
    assert.equal(session.status, 401);
    assert.doesNotMatch(session.body, /UploadPlayer|synthetic-upload-token|accessToken/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
