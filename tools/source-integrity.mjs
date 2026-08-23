import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { join } from "node:path";

// build.gradle is part of the executable source distribution. Keep its digest
// pinned so a truncated or binary GitHub upload fails before Gradle parses it.
// CRLF checkouts are normalized to LF before hashing for Windows Git clients.
export const EXPECTED_BUILD_GRADLE_SHA256 = "9f0d5304f1e71210d4587dc255c3c1087f3a6b1c8ff4818d8e85f8bee4f3fe50";

const SOURCE_URL = "https://github.com/vano04/mcweb";

function normalizeSourceText(text) {
  return text.replace(/\r\n?/g, "\n");
}

export function inspectBuildGradle(bytes) {
  const buffer = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
  let text;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(buffer);
  } catch {
    return {
      ok: false,
      reason: "contains invalid UTF-8/binary bytes",
      actualSha256: null,
    };
  }

  const normalized = normalizeSourceText(text);
  const actualSha256 = createHash("sha256").update(normalized, "utf8").digest("hex");
  if (actualSha256 !== EXPECTED_BUILD_GRADLE_SHA256) {
    return {
      ok: false,
      reason: `has an unexpected or truncated source digest (got ${actualSha256})`,
      actualSha256,
    };
  }
  if (!normalized.startsWith("buildscript {\n") || !normalized.endsWith("tasks.named(\"buildWasmGC\") {\n    dependsOn(\"stageWeb\")\n}\n")) {
    return {
      ok: false,
      reason: "does not have the expected Gradle source boundaries",
      actualSha256,
    };
  }
  return { ok: true, actualSha256 };
}

export async function validateBuildGradle(projectRoot) {
  const path = join(projectRoot, "build.gradle");
  let bytes;
  try {
    bytes = await readFile(path);
  } catch (error) {
    return {
      ok: false,
      reason: `could not be read (${error.message})`,
      path,
      actualSha256: null,
      message: sourceFailureMessage(path, `could not be read (${error.message})`),
    };
  }
  const result = inspectBuildGradle(bytes);
  return {
    ...result,
    path,
    message: result.ok ? null : sourceFailureMessage(path, result.reason, result.actualSha256),
  };
}

function sourceFailureMessage(path, reason, actualSha256 = null) {
  const digest = actualSha256 ? `\n  Observed normalized SHA-256: ${actualSha256}` : "";
  return `source checkout is invalid: ${path} ${reason}.${digest}\n`
    + `  Expected SHA-256: ${EXPECTED_BUILD_GRADLE_SHA256}\n`
    + `  Re-download a fresh Git clone or ZIP from ${SOURCE_URL}, then run the local entrypoint again.`;
}
