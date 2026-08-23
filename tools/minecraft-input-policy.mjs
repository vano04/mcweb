import { resolve, sep } from "node:path";

export const MINECRAFT_VERSION = "26.2";

const CDN_HOSTS = Object.freeze({
  global: "piston-meta.mojang.com",
  version: "piston-meta.mojang.com",
  client: "piston-data.mojang.com",
  library: "libraries.minecraft.net",
  assetIndex: "piston-meta.mojang.com",
  assetObject: "resources.download.minecraft.net",
});

const SHA1 = "[0-9a-f]{40}";

function fail(label, detail) {
  throw new Error(`${label} is invalid${detail ? `: ${detail}` : ""}`);
}

function assertNoEncodedSeparators(pathname, label) {
  if (/[\\\0]/.test(pathname) || /%(?:00|2f|5c|2e)/i.test(pathname)) {
    fail(label, "encoded or control path separator");
  }
}

export function validateCdnUrl(raw, {
  kind,
  expectedPath = null,
  label = "CDN URL",
} = {}) {
  let url;
  try {
    url = new URL(raw);
  } catch {
    fail(label, "not a URL");
  }
  const host = CDN_HOSTS[kind];
  if (!host) fail(label, `unsupported artifact class ${kind || "(missing)"}`);
  if (url.protocol !== "https:" || url.port !== "" || url.hostname.toLowerCase() !== host
      || url.username || url.password || url.search || url.hash) {
    fail(label, "not an official HTTPS URL with the default port and no credentials/query/fragment");
  }
  assertNoEncodedSeparators(url.pathname, label);
  if (expectedPath !== null && url.pathname !== `/${String(expectedPath).replace(/^\/+/, "")}`) {
    fail(label, `path does not match the manifest path ${expectedPath}`);
  }
  if (kind === "global" && url.pathname !== "/mc/game/version_manifest_v2.json") {
    fail(label, "global manifest path is not version_manifest_v2.json");
  }
  if (kind === "version" && !new RegExp(`^/v1/packages/${SHA1}/${MINECRAFT_VERSION}\\.json$`).test(url.pathname)) {
    fail(label, "version manifest path is not the pinned 26.2 package");
  }
  if (kind === "client" && !new RegExp(`^/v1/objects/${SHA1}/client\\.jar$`).test(url.pathname)) {
    fail(label, "client path is not a content-addressed client.jar object");
  }
  if (kind === "assetIndex" && !new RegExp(`^/v1/packages/${SHA1}/[A-Za-z0-9._-]+\\.json$`).test(url.pathname)) {
    fail(label, "asset-index path is not a content-addressed package JSON");
  }
  if (kind === "assetObject" && !new RegExp(`^/[0-9a-f]{2}/${SHA1}$`).test(url.pathname)) {
    fail(label, "asset-object path is not a content-addressed resource");
  }
  return url;
}

export function validateRedirect(currentUrl, location, options = {}) {
  if (!location) fail(options.label || "redirect", "missing Location header");
  let next;
  try {
    next = new URL(location, currentUrl).href;
  } catch {
    fail(options.label || "redirect", "Location is not a URL");
  }
  return validateCdnUrl(next, options);
}

export function validateRelativePath(raw, label = "manifest path") {
  if (typeof raw !== "string") fail(label, "path must be a string");
  const value = raw;
  if (!value || value.startsWith("/") || value.startsWith("\\")
      || /^[A-Za-z]:/.test(value) || value.includes("\\") || value.includes("%")
      || value.split("/").some((part) => !part || part === "." || part === ".." || part.includes(":"))) {
    fail(label, `unsafe relative path ${value}`);
  }
  return value;
}

export function resolveContained(root, relative, label = "path") {
  const base = resolve(root);
  const candidate = resolve(base, validateRelativePath(relative, label));
  if (candidate !== base && !candidate.startsWith(`${base}${sep}`)) {
    fail(label, `resolves outside ${base}`);
  }
  return candidate;
}

export const SUPPORTED_LAUNCHER_FEATURES = Object.freeze(new Set([
  "is_demo_user",
  "has_custom_resolution",
  "has_quick_plays_support",
  "is_quick_play_singleplayer",
  "is_quick_play_multiplayer",
  "is_quick_play_realms",
]));

export function launcherRuleAllows(rules, {
  os = process.platform === "darwin" ? "osx" : process.platform === "win32" ? "windows" : "linux",
  arch = process.arch === "x64" ? "x86_64" : process.arch,
  features = {},
} = {}) {
  if (rules === undefined || rules === null || rules.length === 0) return true;
  if (!Array.isArray(rules)) return false;
  let allowed = false;
  for (const rule of rules) {
    if (!rule || (rule.action !== "allow" && rule.action !== "disallow")) return false;
    if (rule.os) {
      if (typeof rule.os !== "object") return false;
      if (rule.os.name && rule.os.name !== os) continue;
      if (rule.os.arch && rule.os.arch !== arch) continue;
    }
    if (rule.features) {
      if (typeof rule.features !== "object" || Array.isArray(rule.features)) return false;
      let matches = true;
      for (const [feature, wanted] of Object.entries(rule.features)) {
        // Unsupported features are never assumed true. A rule requiring one
        // therefore does not silently enable a library on this build host.
        if (!SUPPORTED_LAUNCHER_FEATURES.has(feature) || typeof wanted !== "boolean") {
          matches = false;
          break;
        }
        if (Boolean(features[feature] ?? false) !== wanted) {
          matches = false;
          break;
        }
      }
      if (!matches) continue;
    }
    allowed = rule.action === "allow";
  }
  return allowed;
}

const NATIVE_CLASSIFIER = /^(?:natives-(?:linux|macos(?:-arm64)?|windows(?:-(?:arm64|x86))?)|(?:linux|osx)-(?:aarch_64|x86_64))$/;

export function isNativeCoordinate(name) {
  const parts = String(name ?? "").split(":");
  if (parts.length < 3 || parts.length > 4 || parts.some((part) => !part || part.includes("/"))) return true;
  return parts.length === 4 && NATIVE_CLASSIFIER.test(parts[3]);
}

export function safeJoin(root, relative, label = "path") {
  return resolveContained(root, validateRelativePath(relative, label), label);
}
