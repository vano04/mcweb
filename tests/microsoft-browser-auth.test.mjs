import test from "node:test";
import assert from "node:assert/strict";
import {
  MINECRAFT_ENTITLEMENTS_URL,
  MINECRAFT_LAUNCHER_LOGIN_URL,
  MINECRAFT_PROFILE_URL,
  exchangeXstsForMinecraft,
} from "../tools/microsoft-browser-auth.mjs";

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

test("XSTS proof becomes a short-lived in-memory Minecraft credential", async () => {
  const calls = [];
  const fetchImpl = async (url, options = {}) => {
    calls.push({ url, options });
    if (url === MINECRAFT_LAUNCHER_LOGIN_URL) {
      return json({ access_token: "minecraft-access", expires_in: 3600 });
    }
    if (url === MINECRAFT_ENTITLEMENTS_URL) return json({ items: [{ name: "game_minecraft" }] });
    if (url === MINECRAFT_PROFILE_URL) {
      return json({
        id: "0123456789abcdef0123456789abcdef",
        name: "BlockPilot",
        skins: [{ state: "ACTIVE", url: "https://textures.minecraft.net/texture/test" }],
      });
    }
    throw new Error(`unexpected URL ${url}`);
  };

  const credential = await exchangeXstsForMinecraft({
    userHash: "1234567890",
    xstsToken: "xsts-proof",
  }, { fetchImpl, now: () => 1_000 });

  assert.deepEqual(calls.map((call) => call.url), [
    MINECRAFT_LAUNCHER_LOGIN_URL,
    MINECRAFT_ENTITLEMENTS_URL,
    MINECRAFT_PROFILE_URL,
  ]);
  assert.deepEqual(JSON.parse(calls[0].options.body), {
    xtoken: "XBL3.0 x=1234567890;xsts-proof",
    platform: "PC_LAUNCHER",
  });
  assert.equal(calls[1].options.headers.authorization, "Bearer minecraft-access");
  assert.deepEqual(credential, {
    ok: true,
    provider: "microsoft-browser",
    token: "minecraft-access",
    id: "0123456789abcdef0123456789abcdef",
    name: "BlockPilot",
    skinUrl: "https://textures.minecraft.net/texture/test",
    expiresAt: 3_601_000,
  });
});

test("Minecraft ownership is required before credentials enter the gateway", async () => {
  const fetchImpl = async (url) => {
    if (url === MINECRAFT_LAUNCHER_LOGIN_URL) return json({ access_token: "minecraft-access" });
    if (url === MINECRAFT_ENTITLEMENTS_URL) return json({ items: [] });
    throw new Error("profile must not be requested without ownership");
  };
  await assert.rejects(
    exchangeXstsForMinecraft({ userHash: "123", xstsToken: "proof" }, { fetchImpl }),
    /does not own Minecraft: Java Edition/,
  );
});

test("malformed Xbox proofs fail before any network request", async () => {
  let called = false;
  await assert.rejects(
    exchangeXstsForMinecraft({ userHash: "bad hash", xstsToken: "proof" }, {
      fetchImpl: async () => { called = true; return json({}); },
    }),
    /invalid Minecraft security proof/,
  );
  assert.equal(called, false);
});
