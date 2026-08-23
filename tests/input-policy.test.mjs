import assert from "node:assert/strict";
import test from "node:test";
import {
  isNativeCoordinate,
  launcherRuleAllows,
  resolveContained,
  safeJoin,
  validateCdnUrl,
  validateRedirect,
  validateRelativePath,
} from "../tools/minecraft-input-policy.mjs";

const HASH = "a".repeat(40);

test("CDN policy accepts only the exact official artifact class and path", () => {
  assert.equal(
    validateCdnUrl("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", {
      kind: "global", expectedPath: "/mc/game/version_manifest_v2.json",
    }).hostname,
    "piston-meta.mojang.com",
  );
  assert.doesNotThrow(() => validateCdnUrl(
    `https://piston-meta.mojang.com/v1/packages/${HASH}/26.2.json`, { kind: "version" },
  ));
  assert.doesNotThrow(() => validateCdnUrl(
    `https://piston-data.mojang.com/v1/objects/${HASH}/client.jar`, { kind: "client" },
  ));
  assert.doesNotThrow(() => validateCdnUrl(
    "https://libraries.minecraft.net/com/example/game/1.0/game-1.0.jar",
    { kind: "library", expectedPath: "com/example/game/1.0/game-1.0.jar" },
  ));
  assert.doesNotThrow(() => validateCdnUrl(
    `https://piston-meta.mojang.com/v1/packages/${HASH}/32.json`, { kind: "assetIndex" },
  ));
  assert.doesNotThrow(() => validateCdnUrl(
    `https://resources.download.minecraft.net/aa/${HASH}`, { kind: "assetObject" },
  ));
});

test("CDN policy rejects ports, credentials, query/fragment, host confusion, and path escapes", () => {
  const allowed = "https://resources.download.minecraft.net/aa/" + HASH;
  const reject = (url, options = { kind: "assetObject" }) => assert.throws(
    () => validateCdnUrl(url, options), /invalid|official|path|HTTPS|default port/i,
  );
  reject(allowed.replace("https://", "http://"));
  reject(allowed.replace("resources.download.minecraft.net", "resources.download.minecraft.net:8443"));
  reject(allowed.replace("https://", "https://user:pass@"));
  reject(`${allowed}?sig=untrusted`);
  reject(`${allowed}#fragment`);
  reject(allowed.replace("resources.download.minecraft.net", "resources.download.minecraft.net.evil.example"));
  reject("https://piston-data.mojang.com/v1/objects/" + HASH + "/client.jar", { kind: "assetObject" });
  reject(`https://resources.download.minecraft.net/aa/%2e%2e/${HASH}`);
  reject(`https://resources.download.minecraft.net/aa%2f${HASH}`);
  assert.throws(() => validateRedirect(allowed, "https://evil.example.invalid/aa/" + HASH, {
    kind: "assetObject",
  }), /official|HTTPS|host/i);
});

test("manifest paths use resolve-based containment and reject traversal/absolute forms", () => {
  assert.equal(validateRelativePath("com/example/lib.jar"), "com/example/lib.jar");
  for (const value of ["../escape.jar", "a/../../escape.jar", "/absolute.jar", "\\absolute.jar",
    "C:\\absolute.jar", "a\\b.jar", "a//b.jar", "a/./b.jar", "a%2fb.jar", "a:b.jar"]) {
    assert.throws(() => validateRelativePath(value), /invalid|unsafe relative path/);
    assert.throws(() => safeJoin("/tmp/mcweb-cache", value), /invalid|unsafe|outside/);
  }
  assert.throws(() => validateRelativePath({ path: "not-a-string" }), /path must be a string/);
  assert.equal(resolveContained("/tmp/mcweb-cache", "libraries/a.jar"), "/tmp/mcweb-cache/libraries/a.jar");
  assert.throws(() => resolveContained("/tmp/mcweb-cache", "../../outside.jar"), /outside|unsafe/);
});

test("launcher rules default to deny and evaluate supported features explicitly", () => {
  assert.equal(launcherRuleAllows(undefined, { os: "osx", arch: "arm64" }), true);
  assert.equal(launcherRuleAllows([], { os: "osx", arch: "arm64" }), true);
  const featureRule = [{ action: "allow", os: { name: "osx", arch: "arm64" }, features: { has_custom_resolution: true } }];
  assert.equal(launcherRuleAllows(featureRule, { os: "osx", arch: "arm64" }), false);
  assert.equal(launcherRuleAllows(featureRule, {
    os: "osx", arch: "arm64", features: { has_custom_resolution: true },
  }), true);
  assert.equal(launcherRuleAllows([{ action: "allow", features: { unknown_feature: true } }], {
    os: "osx", arch: "arm64", features: { unknown_feature: true },
  }), false);
  assert.equal(launcherRuleAllows([{ action: "allow", os: { name: "windows" } }], { os: "osx" }), false);
  assert.equal(launcherRuleAllows([{ action: "disallow", os: { name: "osx" } }], { os: "osx" }), false);
  assert.equal(launcherRuleAllows([{ action: "allow" }, { action: "disallow", os: { name: "windows" } }], {
    os: "osx",
  }), true);
  assert.equal(launcherRuleAllows([{ action: "maybe" }], { os: "osx" }), false);
});

test("native exclusion is exact and does not substring-match ordinary artifacts", () => {
  assert.equal(isNativeCoordinate("org.example:lib:1.0"), false);
  assert.equal(isNativeCoordinate("io.netty:netty-transport-native-unix-common:1.0"), false);
  assert.equal(isNativeCoordinate("org.example:lib:1.0:natives-linux"), true);
  assert.equal(isNativeCoordinate("org.example:lib:1.0:natives-macos-arm64"), true);
  assert.equal(isNativeCoordinate("org.example:lib:1.0:linux-x86_64"), true);
  assert.equal(isNativeCoordinate("org.example:lib:1.0:natives-linux-extra"), false);
  assert.equal(isNativeCoordinate("org.example:lib:1.0:classifier-with-natives-linux"), false);
  assert.equal(isNativeCoordinate("malformed-coordinate"), true);
});
