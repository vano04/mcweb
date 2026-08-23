/**
 * Server-side adapters for local Minecraft launcher account stores.
 *
 * The file is deliberately read only by the local Node process.  The parser
 * returns a token to its caller so the gateway can use it for the short-lived
 * Minecraft Services checks and session join, but no browser-facing route ever
 * serialises that field.  Keep this module free of logging: account files also
 * contain Microsoft account identifiers that must not reach a terminal log.
 */
import { existsSync, readFileSync } from "node:fs";
import { homedir, platform } from "node:os";
import { join, resolve } from "node:path";

export const LAUNCHER_AUTH_SCHEMA = "minecraft-launcher-accounts-v1";
export const PRISM_AUTH_SCHEMA = "prismlauncher-accounts-v1";
export const LAUNCHER_ACCOUNTS_ENV = "MCWEB_LAUNCHER_ACCOUNTS";
export const MINECRAFT_ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore";
export const MINECRAFT_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

const MAX_ACCOUNTS = 32;
const MAX_TOKEN_BYTES = 64 * 1024;
const PROFILE_NAME = /^[A-Za-z0-9_]{1,16}$/;
const PROFILE_ID = /^(?:[0-9a-f]{32}|[0-9a-f-]{36})$/i;
const OFFICIAL_ACCOUNT_TYPES = new Set(["XBOX", "MSA", "MICROSOFT"]);
const PRISM_ACCOUNT_TYPES = new Set(["MSA"]);

function failure(code, message) {
  return { ok: false, code, error: message };
}

function objectRecord(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function normalizedProfileId(value) {
  const id = String(value ?? "").trim();
  return PROFILE_ID.test(id) ? id.replaceAll("-", "").toLowerCase() : null;
}

function validProfile(profile) {
  return objectRecord(profile)
    && typeof profile.name === "string"
    && PROFILE_NAME.test(profile.name)
    && normalizedProfileId(profile.id) !== null;
}

function parseExpiry(value, now) {
  if (typeof value !== "string" || !value.trim()) return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) && parsed > now ? parsed : null;
}

function parseUnixExpiry(value, now) {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) return null;
  const parsed = value * 1000;
  return Number.isFinite(parsed) && parsed > now ? parsed : null;
}

function usableAccount(entry, now) {
  const account = entry?.[1];
  if (!objectRecord(account)) return failure("invalid-account", "the selected launcher account is not an object");
  const accountType = typeof account.type === "string" ? account.type.trim().toUpperCase() : "";
  if (!OFFICIAL_ACCOUNT_TYPES.has(accountType)) {
    return failure("unsupported-account", "the selected launcher account is not a Microsoft account");
  }
  const token = typeof account.accessToken === "string" ? account.accessToken.trim() : "";
  if (!token || Buffer.byteLength(token, "utf8") > MAX_TOKEN_BYTES) {
    return failure("missing-token", "the selected launcher account has no usable access token");
  }
  const expiresAt = parseExpiry(account.accessTokenExpiresAt, now);
  if (expiresAt === null) {
    return failure("expired-token", "the selected launcher account has no unexpired access token");
  }
  if (!validProfile(account.minecraftProfile)) {
    return failure("missing-profile", "the selected launcher account has no valid Minecraft profile");
  }
  return {
    ok: true,
    token,
    id: normalizedProfileId(account.minecraftProfile.id),
    name: account.minecraftProfile.name,
    expiresAt,
  };
}

/**
 * Parse the official launcher schema without exposing account identifiers or
 * token values in the result's diagnostics.
 */
export function parseLauncherAccounts(value, { now = Date.now() } = {}) {
  if (!objectRecord(value)) return failure("invalid-shape", "launcher_accounts.json must contain one object");
  if (!objectRecord(value.accounts)) return failure("invalid-accounts", "launcher_accounts.json has no account map");

  const entries = Object.entries(value.accounts);
  if (entries.length === 0) return failure("missing-account", "launcher_accounts.json contains no accounts");
  if (entries.length > MAX_ACCOUNTS) return failure("too-many-accounts", "launcher_accounts.json contains too many accounts");

  const activeId = value.activeAccountLocalId;
  if (typeof activeId !== "string" || activeId.length === 0) {
    return failure("missing-active-account", "launcher_accounts.json has no activeAccountLocalId");
  }
  const selected = entries.find(([key]) => key === activeId);
  if (!selected) return failure("missing-active-account", "the active launcher account is not present in the account map");

  const credentials = usableAccount(selected, now);
  if (!credentials.ok) return credentials;
  return {
    ok: true,
    code: "official-launcher-accounts-v1",
    schema: LAUNCHER_AUTH_SCHEMA,
    provider: "official-launcher",
    token: credentials.token,
    id: credentials.id,
    name: credentials.name,
    expiresAt: credentials.expiresAt,
  };
}

function usablePrismAccount(account, now) {
  if (!objectRecord(account)) return failure("invalid-account", "the selected PrismLauncher account is not an object");
  const accountType = typeof account.type === "string" ? account.type.trim().toUpperCase() : "";
  if (!PRISM_ACCOUNT_TYPES.has(accountType)) {
    return failure("unsupported-account", "the selected PrismLauncher account is not an MSA account");
  }
  const token = typeof account.ygg?.token === "string" ? account.ygg.token.trim() : "";
  if (!token || Buffer.byteLength(token, "utf8") > MAX_TOKEN_BYTES) {
    return failure("missing-token", "the selected PrismLauncher account has no usable access token");
  }
  const expiresAt = parseUnixExpiry(account.ygg?.exp, now);
  if (expiresAt === null) {
    return failure("expired-token", "the selected PrismLauncher account has no unexpired access token");
  }
  if (!validProfile(account.profile)) {
    return failure("missing-profile", "the selected PrismLauncher account has no valid Minecraft profile");
  }
  return {
    ok: true,
    token,
    id: normalizedProfileId(account.profile.id),
    name: account.profile.name,
    expiresAt,
  };
}

/**
 * Parse PrismLauncher's local `accounts.json` without accepting its refresh
 * token or any unchecked profile metadata. The active account must be
 * unambiguous; only the short-lived ygg token is used for live validation.
 */
export function parsePrismAccounts(value, { now = Date.now() } = {}) {
  if (!objectRecord(value)) return failure("invalid-shape", "PrismLauncher accounts.json must contain one object");
  if (!Number.isSafeInteger(value.formatVersion) || value.formatVersion < 1) {
    return failure("invalid-format", "PrismLauncher accounts.json has no supported format version");
  }
  if (!Array.isArray(value.accounts)) return failure("invalid-accounts", "PrismLauncher accounts.json has no account list");
  if (value.accounts.length === 0) return failure("missing-account", "PrismLauncher accounts.json contains no accounts");
  if (value.accounts.length > MAX_ACCOUNTS) return failure("too-many-accounts", "PrismLauncher accounts.json contains too many accounts");

  const active = value.accounts.filter((account) => account?.active === true);
  if (active.length === 0) return failure("missing-active-account", "no active PrismLauncher account is selected");
  if (active.length !== 1) return failure("ambiguous-active-account", "multiple PrismLauncher accounts are active");

  const credentials = usablePrismAccount(active[0], now);
  if (!credentials.ok) return credentials;
  return {
    ok: true,
    code: "prismlauncher-accounts-v1",
    schema: PRISM_AUTH_SCHEMA,
    provider: "prismlauncher",
    token: credentials.token,
    id: credentials.id,
    name: credentials.name,
    expiresAt: credentials.expiresAt,
  };
}

/** Accept exactly one supported local launcher schema; never guess between shapes. */
export function parseLauncherDocument(value, options = {}) {
  if (objectRecord(value) && Array.isArray(value.accounts)) return parsePrismAccounts(value, options);
  return parseLauncherAccounts(value, options);
}

/**
 * Resolve the file with an explicit override first, then the platform's
 * ordinary official-launcher location.  `exists` is injectable for tests.
 */
export function launcherAccountPaths({
  platformName = platform(),
  env = process.env,
  home = homedir(),
} = {}) {
  const homeDir = home || env.HOME || env.USERPROFILE || ".";
  if (platformName === "darwin") {
    return [join(homeDir, "Library/Application Support/minecraft/launcher_accounts.json")];
  }
  if (platformName === "win32") {
    const appData = env.APPDATA || join(env.USERPROFILE || homeDir, "AppData/Roaming");
    return [join(appData, ".minecraft/launcher_accounts.json")];
  }
  return [join(homeDir, ".minecraft/launcher_accounts.json")];
}

export function prismLauncherAccountPaths({
  platformName = platform(),
  env = process.env,
  home = homedir(),
} = {}) {
  const homeDir = home || env.HOME || env.USERPROFILE || ".";
  if (platformName === "darwin") {
    return [join(homeDir, "Library/Application Support/PrismLauncher/accounts.json")];
  }
  return [];
}

/**
 * Return autodiscovery candidates in deterministic priority order. An explicit
 * override is intentionally the only candidate so a malformed or unusable
 * configured file cannot silently fall back to another account store.
 */
export function launcherAccountCandidates({
  override = "",
  platformName = platform(),
  env = process.env,
  home = homedir(),
} = {}) {
  const explicit = String(override || env[LAUNCHER_ACCOUNTS_ENV] || "").trim();
  if (explicit) return [resolve(explicit.replace(/^~/, home || env.HOME || env.USERPROFILE || ""))];
  return [
    ...launcherAccountPaths({ platformName, env, home }),
    ...prismLauncherAccountPaths({ platformName, env, home }),
  ];
}

export function resolveLauncherAccountsPath({
  override = "",
  platformName = platform(),
  env = process.env,
  home = homedir(),
  exists = (path) => {
    return existsSync(path);
  },
} = {}) {
  return launcherAccountCandidates({ override, platformName, env, home }).find(exists) || null;
}

export function readLauncherCredentials(path, { now = Date.now(), readFile = readFileSync } = {}) {
  if (!path) return failure("missing-file", "no supported Minecraft launcher account file was found");
  let value;
  try {
    value = JSON.parse(readFile(path, "utf8"));
  } catch {
    return failure("invalid-file", "the Minecraft launcher account file could not be read as valid JSON");
  }
  return parseLauncherDocument(value, { now });
}

/**
 * Read candidates in priority order and return the first usable document.
 * Explicit overrides pass a one-element list, so they remain fail-closed.
 */
export function readLauncherCredentialsCandidates(paths, options = {}) {
  let result = failure("missing-file", "no supported Minecraft launcher account file was found");
  for (const path of paths || []) {
    result = readLauncherCredentials(path, options);
    if (result.ok) return result;
  }
  return result;
}

async function readJsonResponse(fetchImpl, url, token, label, timeoutMs) {
  const controller = typeof AbortController === "function" ? new AbortController() : null;
  const timer = controller && timeoutMs > 0 ? setTimeout(() => controller.abort(), timeoutMs) : null;
  try {
    const response = await fetchImpl(url, {
      headers: { authorization: `Bearer ${token}`, accept: "application/json" },
      cache: "no-store",
      ...(controller ? { signal: controller.signal } : {}),
    });
    if (!response.ok) return failure(`service-${label}`, `Minecraft Services rejected the ${label} check`);
    let body;
    try {
      body = await response.json();
    } catch {
      return failure(`service-${label}`, `Minecraft Services returned invalid ${label} data`);
    }
    return { ok: true, body };
  } catch {
    return failure(`service-${label}`, `Minecraft Services could not complete the ${label} check`);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/**
 * Re-check the launcher token against Mojang/Minecraft Services immediately
 * before it is used online.  The endpoint responses are never returned to the
 * browser; only the public profile metadata survives in the caller's session.
 */
export async function validateLauncherCredentials(credentials, {
  fetchImpl = fetch,
  now = Date.now(),
  timeoutMs = 10_000,
} = {}) {
  if (!credentials?.ok || typeof credentials.token !== "string") {
    return failure("missing-credentials", "no usable Minecraft launcher credentials are available");
  }
  if (!Number.isFinite(credentials.expiresAt) || credentials.expiresAt <= now) {
    return failure("expired-token", "the Minecraft launcher access token has expired");
  }

  const entitlements = await readJsonResponse(
    fetchImpl, MINECRAFT_ENTITLEMENTS_URL, credentials.token, "entitlement", timeoutMs,
  );
  if (!entitlements.ok) return entitlements;
  if (!Array.isArray(entitlements.body?.items) || entitlements.body.items.length === 0) {
    return failure("missing-entitlement", "Minecraft Services did not confirm Minecraft ownership");
  }

  const profile = await readJsonResponse(
    fetchImpl, MINECRAFT_PROFILE_URL, credentials.token, "profile", timeoutMs,
  );
  if (!profile.ok) return profile;
  if (!validProfile(profile.body)) {
    return failure("missing-profile", "Minecraft Services did not return a usable Minecraft profile");
  }
  const serviceId = normalizedProfileId(profile.body.id);
  if (serviceId !== credentials.id || profile.body.name !== credentials.name) {
    return failure("profile-mismatch", "the launcher profile does not match the live Minecraft profile");
  }

  const activeSkin = Array.isArray(profile.body.skins)
    ? profile.body.skins.find((skin) => skin?.state === "ACTIVE") || profile.body.skins[0]
    : null;
  const provider = credentials.provider === "prismlauncher" ? "prismlauncher" : "official-launcher";
  return {
    ok: true,
    code: `${provider}-validated`,
    provider,
    token: credentials.token,
    id: serviceId,
    name: profile.body.name,
    expiresAt: credentials.expiresAt,
    skinUrl: typeof activeSkin?.url === "string" ? activeSkin.url : null,
  };
}
