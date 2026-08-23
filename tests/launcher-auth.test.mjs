import assert from "node:assert/strict";
import test from "node:test";
import {
  launcherAccountPaths,
  parseLauncherDocument,
  parseLauncherAccounts,
  parsePrismAccounts,
  readLauncherCredentialsCandidates,
  launcherAccountCandidates,
  prismLauncherAccountPaths,
  resolveLauncherAccountsPath,
  validateLauncherCredentials,
} from "../tools/launcher-auth.mjs";

const NOW = Date.parse("2026-08-23T00:00:00.000Z");

function account(overrides = {}) {
  return {
    type: "msa",
    accessToken: "synthetic-token-for-tests",
    accessTokenExpiresAt: "2026-08-23T01:00:00.000Z",
    minecraftProfile: {
      id: "0123456789abcdef0123456789abcdef",
      name: "TestPlayer",
    },
    ...overrides,
  };
}

function document(accounts, activeAccountLocalId = "one") {
  return { activeAccountLocalId, accounts };
}

function prismAccount(overrides = {}) {
  return {
    active: true,
    type: "MSA",
    ygg: {
      token: "synthetic-prism-token-for-tests",
      exp: Math.floor(Date.parse("2026-08-23T01:00:00.000Z") / 1000),
    },
    profile: {
      id: "abcdefabcdefabcdefabcdefabcdefab",
      name: "PrismPlayer",
    },
    ...overrides,
  };
}

function prismDocument(accounts = [prismAccount()]) {
  return { formatVersion: 3, accounts };
}

test("parses the official launcher schema and selects its active account", () => {
  const parsed = parseLauncherAccounts(document({ one: account() }), { now: NOW });
  assert.equal(parsed.ok, true);
  assert.equal(parsed.provider, "official-launcher");
  assert.equal(parsed.id, "0123456789abcdef0123456789abcdef");
  assert.equal(parsed.name, "TestPlayer");
  assert.equal(parsed.token, "synthetic-token-for-tests");
});

test("parses the PrismLauncher schema and selects its single active account", () => {
  const parsed = parsePrismAccounts(prismDocument(), { now: NOW });
  assert.equal(parsed.ok, true);
  assert.equal(parsed.provider, "prismlauncher");
  assert.equal(parsed.schema, "prismlauncher-accounts-v1");
  assert.equal(parsed.id, "abcdefabcdefabcdefabcdefabcdefab");
  assert.equal(parsed.name, "PrismPlayer");
  assert.equal(parsed.token, "synthetic-prism-token-for-tests");
  assert.deepEqual(parseLauncherDocument(prismDocument(), { now: NOW }), parsed);
});

test("PrismLauncher selection, token, expiry, and profile validation fail closed", () => {
  assert.equal(parsePrismAccounts({ formatVersion: 3, accounts: [] }, { now: NOW }).code, "missing-account");
  assert.equal(parsePrismAccounts(prismDocument([prismAccount(), prismAccount({ profile: { id: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", name: "Second" } })]), { now: NOW }).code,
    "ambiguous-active-account");
  assert.equal(parsePrismAccounts(prismDocument([prismAccount({ active: false })]), { now: NOW }).code,
    "missing-active-account");
  assert.equal(parsePrismAccounts(prismDocument([prismAccount({ ygg: { token: "", exp: 1 } })]), { now: NOW }).code,
    "missing-token");
  assert.equal(parsePrismAccounts(prismDocument([prismAccount({ ygg: { token: "usable", exp: 1 } })]), { now: NOW }).code,
    "expired-token");
  assert.equal(parsePrismAccounts(prismDocument([prismAccount({ profile: null })]), { now: NOW }).code,
    "missing-profile");
  assert.equal(parsePrismAccounts(prismDocument([prismAccount({ type: "offline" })]), { now: NOW }).code,
    "unsupported-account");
  assert.equal(parsePrismAccounts({ formatVersion: "3", accounts: [prismAccount()] }, { now: NOW }).code,
    "invalid-format");
});

test("malformed, ambiguous, expired, and missing-profile files fail closed", () => {
  assert.equal(parseLauncherAccounts(null, { now: NOW }).code, "invalid-shape");
  assert.equal(parseLauncherAccounts({ accounts: [] }, { now: NOW }).code, "invalid-accounts");
  assert.equal(parseLauncherAccounts(document({ one: account(), two: account() }, ""), { now: NOW }).code,
    "missing-active-account");
  assert.equal(parseLauncherAccounts(document({ one: account({ accessTokenExpiresAt: "2026-08-22T23:59:59Z" }) }), { now: NOW }).code,
    "expired-token");
  assert.equal(parseLauncherAccounts(document({ one: account({ minecraftProfile: null }) }), { now: NOW }).code,
    "missing-profile");
  assert.equal(parseLauncherAccounts({ accounts: { one: account() } }, { now: NOW }).code,
    "missing-active-account");
});

test("active account selection is deterministic and never falls back to another account", () => {
  const parsed = parseLauncherAccounts(document({
    first: account({ minecraftProfile: { id: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", name: "First" } }),
    second: account({ minecraftProfile: { id: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", name: "Second" } }),
  }, "second"), { now: NOW });
  assert.equal(parsed.ok, true);
  assert.equal(parsed.name, "Second");
  assert.equal(parsed.id, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
  assert.equal(parseLauncherAccounts(document({
    first: account({ accessTokenExpiresAt: "2026-08-22T23:59:59Z" }),
    second: account(),
  }, "first"), { now: NOW }).code, "expired-token");
});

test("official launcher locations are platform-specific", () => {
  assert.deepEqual(launcherAccountPaths({
    platformName: "darwin", home: "/Users/test", env: {},
  }), ["/Users/test/Library/Application Support/minecraft/launcher_accounts.json"]);
  assert.deepEqual(launcherAccountPaths({
    platformName: "win32", home: "C:\\Users\\test", env: { APPDATA: "C:\\Users\\test\\AppData\\Roaming" },
  }), ["C:\\Users\\test\\AppData\\Roaming/.minecraft/launcher_accounts.json"]);
  assert.deepEqual(launcherAccountPaths({
    platformName: "linux", home: "/home/test", env: {},
  }), ["/home/test/.minecraft/launcher_accounts.json"]);
  assert.equal(resolveLauncherAccountsPath({
    override: "~/custom/launcher_accounts.json", home: "/home/test", exists: () => true,
  }), "/home/test/custom/launcher_accounts.json");
  assert.deepEqual(prismLauncherAccountPaths({
    platformName: "darwin", home: "/Users/test", env: {},
  }), ["/Users/test/Library/Application Support/PrismLauncher/accounts.json"]);
  assert.deepEqual(launcherAccountCandidates({
    platformName: "darwin", home: "/Users/test", env: {},
  }), [
    "/Users/test/Library/Application Support/minecraft/launcher_accounts.json",
    "/Users/test/Library/Application Support/PrismLauncher/accounts.json",
  ]);
});

test("autodiscovery falls through an unusable official file to PrismLauncher", () => {
  const official = JSON.stringify({ accounts: { one: account({ accessToken: "" }) }, activeAccountLocalId: "one" });
  const prism = JSON.stringify(prismDocument());
  const paths = launcherAccountCandidates({ platformName: "darwin", home: "/Users/test", env: {} });
  const files = new Map([
    [paths[0], official],
    [paths[1], prism],
  ]);
  const parsed = readLauncherCredentialsCandidates(paths, {
    now: NOW,
    readFile: (path) => {
      if (!files.has(path)) throw new Error("missing fixture");
      return files.get(path);
    },
  });
  assert.equal(parsed.ok, true);
  assert.equal(parsed.provider, "prismlauncher");
});

test("an explicit unusable override does not fall through to PrismLauncher", () => {
  const candidates = launcherAccountCandidates({
    override: "/Users/test/explicit.json",
    platformName: "darwin", home: "/Users/test", env: {},
  });
  assert.deepEqual(candidates, ["/Users/test/explicit.json"]);
  const parsed = readLauncherCredentialsCandidates(candidates, {
    now: NOW,
    readFile: () => JSON.stringify({ accounts: { one: account({ accessToken: "" }) }, activeAccountLocalId: "one" }),
  });
  assert.equal(parsed.ok, false);
  assert.equal(parsed.code, "missing-token");
});

test("live validation checks entitlement and profile without returning a browser credential", async () => {
  const calls = [];
  const credentials = parseLauncherAccounts(document({ one: account() }), { now: NOW });
  const validated = await validateLauncherCredentials(credentials, {
    now: NOW,
    fetchImpl: async (url, init) => {
      calls.push({ url, authorization: init.headers.authorization });
      if (url.endsWith("/entitlements/mcstore")) {
        return new Response(JSON.stringify({ items: [{ name: "product_minecraft" }] }), { status: 200 });
      }
      return new Response(JSON.stringify({
        id: "0123456789abcdef0123456789abcdef",
        name: "TestPlayer",
        skins: [],
      }), { status: 200 });
    },
  });
  assert.equal(validated.ok, true);
  assert.equal(validated.provider, "official-launcher");
  assert.equal(validated.name, "TestPlayer");
  assert.equal(calls.length, 2);
  assert.ok(calls.every((call) => call.authorization === "Bearer synthetic-token-for-tests"));
});

test("live validation rejects an empty entitlement response", async () => {
  const credentials = parseLauncherAccounts(document({ one: account() }), { now: NOW });
  const result = await validateLauncherCredentials(credentials, {
    now: NOW,
    fetchImpl: async () => new Response(JSON.stringify({ items: [] }), { status: 200 }),
  });
  assert.equal(result.ok, false);
  assert.equal(result.code, "missing-entitlement");
});

test("live validation preserves the PrismLauncher provider without exposing refresh metadata", async () => {
  const credentials = parsePrismAccounts(prismDocument(), { now: NOW });
  const validated = await validateLauncherCredentials(credentials, {
    now: NOW,
    fetchImpl: async (url) => new Response(JSON.stringify(url.endsWith("entitlements/mcstore")
      ? { items: [{ name: "product_minecraft" }] }
      : { id: credentials.id, name: credentials.name, skins: [] }), { status: 200 }),
  });
  assert.equal(validated.ok, true);
  assert.equal(validated.provider, "prismlauncher");
  assert.equal(validated.code, "prismlauncher-validated");
  assert.equal("refreshToken" in validated, false);
});
