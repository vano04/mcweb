#!/usr/bin/env node
// build - turn a player's own Minecraft installation into an MC-Web image.
//
// Compiling the Minecraft closure inside a browser tab is out of reach (it needs a
// ~50 MB stack, >4 GB of heap and over three hours - see docs/STATUS.md), so the
// compile runs here instead. The licensing property is unchanged: the player's game
// files never leave their machine, and the image this produces is theirs.
//
//   node tools/build.mjs                 # download/verify official 26.2 inputs
//   node tools/build.mjs --mc-dir ~/...  # point at one explicitly
//   node tools/build.mjs --graalvm-home ~/graalvm/.../Contents/Home
//   node tools/build.mjs --download      # fetch game files from Mojang, then build
//   node tools/build.mjs --dry-run       # resolve inputs, do not build
//
// Output: dist/build/ containing the image, its loader and the staged audio, ready
// to hand to the web app.
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { createReadStream, createWriteStream } from "node:fs";
import { cp, mkdir, readFile, readdir, rename, rm, stat, symlink, writeFile } from "node:fs/promises";
import { homedir, platform } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import { PANORAMA_KEYS, stageMojangAssets } from "./stage-mojang-assets.mjs";
import {
  isNativeCoordinate as isNativeCoord,
  launcherRuleAllows as ruleAllows,
  safeJoin,
  validateCdnUrl,
  validateRedirect,
  validateRelativePath as safeRelativePath,
} from "./minecraft-input-policy.mjs";

const VERSION = "26.2";
const EXPECTED_CLIENT_SHA256 = "40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290";
// fileURLToPath, not URL.pathname: a checkout under a path with spaces percent-encodes.
const PROJECT = resolve(fileURLToPath(new URL("..", import.meta.url)));
// Windows has no ./gradlew; the wrapper ships a .bat alongside it.
const GRADLEW = process.platform === "win32"
  ? join(PROJECT, "gradlew.bat")
  : join(PROJECT, "gradlew");

// Windows JDK distributions expose java as `java.exe`, while GraalVM's
// native-image entrypoint may be either the direct executable or its batch
// launcher depending on the distribution.  Checking the extensionless POSIX
// names made a valid Windows Web Image JDK look empty during preflight.
const executableNames = (name) => process.platform === "win32"
  ? [`${name}.exe`, `${name}.cmd`, `${name}.bat`]
  : [name];
const hasExecutable = async (directory, name) => {
  for (const candidate of executableNames(name)) {
    if (await exists(join(directory, candidate))) return true;
  }
  return false;
};

const argv = process.argv.slice(2);
const flag = (name, fallback = null) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith("--") ? argv[i + 1] : fallback;
};
const has = (name) => argv.includes(`--${name}`);

const version = flag("version", VERSION);
const dryRun = has("dry-run");
const offline = has("offline");
const downloadOnly = has("download-only");
// Sounds are 367 MiB across ~4,900 objects and are staged beside the image, not
// compiled into it, so a build that only needs a runnable image can skip them.
const noAudio = has("no-audio");
// Host-specific native-image flags. Windows with the open-source C toolchain
// needs "-H:-CheckToolchain"; see tools/oss-toolchain.mjs.
const graalExtraArgs = flag("graal-extra-args", "");
const graalVmHomeArg = flag("graalvm-home") ?? flag("graalVmHome");
const outDir = resolve(flag("out", join(PROJECT, "dist", "build")));
const MCWEB_HOME = process.env.MCWEB_HOME || join(homedir(), ".mcweb");
const DEFAULT_MINECRAFT_CACHE = join(MCWEB_HOME, "minecraft");

const exists = async (p) => !!(await stat(p).catch(() => null));
const say = (...a) => console.log(...a);
const die = (msg) => { console.error(`\nbuild: ${msg}`); process.exit(1); };
if (version !== VERSION) die(`only Minecraft ${VERSION} is supported by this build`);

function validateBuildArgs() {
  const explicit = flag("mc-dir");
  const downloading = has("download") || downloadOnly || (!explicit && !has("local-only"));
  if (has("download") && explicit) die("--download cannot be combined with --mc-dir");
  if (has("download") && has("local-only")) die("--download cannot be combined with --local-only");
  if (downloadOnly && explicit) die("--download-only cannot be combined with --mc-dir");
  if (downloadOnly && has("local-only")) die("--download-only cannot be combined with --local-only");
  if (downloadOnly && (dryRun || has("build") || has("run"))) {
    die("--download-only cannot be combined with --dry-run, --build, or --run");
  }
  if (dryRun && (has("build") || has("run"))) die("--dry-run cannot be combined with --build or --run");
  if (offline && !downloading) die("--offline requires the CDN download mode; remove --mc-dir/--local-only");
}
validateBuildArgs();

// The checked-in image recipes use Oracle GraalVM's Web Image distribution. The
// ordinary macOS `java_home` registry often only knows about a system JDK, so
// discover the user's downloaded GraalVM as well. Web Image is supported by
// Oracle GraalVM 25.1+. The public bootstrap pins 25.2.4 (25i2). An explicitly
// selected, structurally complete 25.0.4 toolchain is retained as a legacy
// reproduction path because this project was originally developed with it;
// auto-discovery never treats that old toolchain as the public baseline.
const MIN_GRAAL_VERSION = [25, 1, 0];
const LEGACY_GRAAL_VERSION = [25, 0, 4];

function expandHome(path) {
  return path?.replace(/^~(?=$|[\\/])/, homedir());
}

function versionTuple(version) {
  const match = String(version ?? "").match(/^(\d+)\.(\d+)(?:\.(\d+))?/);
  return match ? [Number(match[1]), Number(match[2]), Number(match[3] ?? 0)] : null;
}

function compareVersions(a, b) {
  for (let i = 0; i < 3; i++) {
    if (a[i] !== b[i]) return a[i] - b[i];
  }
  return 0;
}

function releaseValue(release, key) {
  const match = release.match(new RegExp(`^${key}="([^"]+)"$`, "m"));
  return match?.[1] ?? null;
}

function graalHomeCandidates(raw) {
  if (!raw) return [];
  const path = resolve(expandHome(raw));
  // Accept both a JDK home and the macOS .jdk bundle containing Contents/Home.
  return [
    path,
    join(path, "Contents", "Home"),
    join(path, "Home"),
  ];
}

async function discoverGraalHomes() {
  const homes = [];
  const add = (path) => {
    if (path && !homes.includes(path)) homes.push(path);
  };
  const roots = platform() === "darwin"
    ? [
        join(homedir(), "graalvm"),
        join(homedir(), ".mcweb", "toolchain"),
        join(homedir(), "Library", "Java", "JavaVirtualMachines"),
        "/Library/Java/JavaVirtualMachines",
        "/opt/homebrew/opt",
        "/opt/homebrew/Cellar",
        "/usr/local/opt",
        "/usr/local/Cellar",
      ]
    : [
        join(homedir(), ".mcweb", "toolchain"),
        join(homedir(), ".local", "share", "graalvm"),
        join(homedir(), ".sdkman", "candidates", "java"),
        "/usr/lib/jvm",
      ];

  for (const root of roots) {
    if (!(await exists(root))) continue;
    // Some install locations are the JDK home itself (not a versioned parent),
    // notably ~/.mcweb/toolchain created by the clean-machine bootstrap.
    add(root);
    const entries = await readdir(root, { withFileTypes: true }).catch(() => []);
    for (const entry of entries) {
      if (!entry.name.toLowerCase().includes("graal")
          && !entry.name.toLowerCase().includes("jdk-25")) continue;
      add(join(root, entry.name));
    }
  }
  return homes;
}

async function findBinaryenBin() {
  const configured = process.env.MCWEB_BINARYEN_HOME || process.env.BINARYEN_HOME;
  const candidates = [
    configured,
    join(homedir(), ".mcweb", "binaryen", "bin"),
    join(homedir(), "tools", "binaryen-version_131", "bin"),
    join(homedir(), "tools", "binaryen", "bin"),
  ].filter(Boolean).map((path) => resolve(expandHome(path)));
  for (const bin of candidates) {
    if (await exists(join(bin, process.platform === "win32" ? "wasm-as.exe" : "wasm-as"))) return bin;
  }
  return null;
}

async function inspectGraalHome(raw, { allowLegacy = false } = {}) {
  const candidates = graalHomeCandidates(raw);
  for (const home of candidates) {
    const releasePath = join(home, "release");
    const svmJar = join(home, "lib", "svm", "builder", "svm.jar");
    const svmWasmJar = join(home, "lib", "svm", "tools", "svm-wasm", "builder", "svm-wasm.jar");
    const webImageModule = join(home, "jmods", "org.graalvm.webimage.api.jmod");
    if (!(await exists(releasePath))) continue;
    const release = await readFile(releasePath, "utf8").catch(() => "");
    const javaVersion = versionTuple(releaseValue(release, "JAVA_VERSION"));
    const implementor = releaseValue(release, "IMPLEMENTOR") ?? "";
    const graalVersionText = releaseValue(release, "GRAALVM_VERSION") ?? "";
    const graalVersion = versionTuple(graalVersionText);
    if (!javaVersion) return { error: `${home}: release file has no JAVA_VERSION` };
    if (!/Oracle/i.test(implementor)) {
      return { error: `${home}: implementor is ${implementor || "unknown"}, not Oracle GraalVM` };
    }
    if (!graalVersion) return { error: `${home}: release file has no GRAALVM_VERSION` };
    // Oracle does not publish a native ARM64 Windows Web Image builder.  On
    // Windows, accept only an explicitly identifiable x64 JDK so an ARM64
    // (or otherwise unknown) toolchain cannot be mistaken for the emulated
    // x64 builder used by the supported ARM64 path.
    if (process.platform === "win32") {
      const osArch = releaseValue(release, "OS_ARCH") ?? releaseValue(release, "OS_ARCHITECTURE");
      if (!osArch) return { error: `${home}: release file has no OS_ARCH; cannot verify Windows x64 Web Image builder` };
      if (!/^(?:amd64|x86_64|x64)$/i.test(osArch)) {
        return { error: `${home}: OS_ARCH=${osArch} is not the required Windows x64 Web Image builder` };
      }
    }
    const isLegacy = compareVersions(graalVersion, MIN_GRAAL_VERSION) < 0;
    if (isLegacy && (!allowLegacy || compareVersions(graalVersion, LEGACY_GRAAL_VERSION) < 0)) {
      return { error: `${home}: GraalVM ${graalVersionText} is older than Web Image 25.1 (public baseline)` };
    }
    const missing = [];
    if (!(await hasExecutable(join(home, "bin"), "java"))) missing.push("bin/java");
    if (!(await exists(svmJar))) missing.push("lib/svm/builder/svm.jar");
    if (!(await exists(svmWasmJar))) missing.push("lib/svm/tools/svm-wasm/builder/svm-wasm.jar");
    if (!(await exists(webImageModule))) missing.push("jmods/org.graalvm.webimage.api.jmod");
    // The direct native-image executable is preferred by the Gradle task on
    // Windows, but the public distribution can expose only the batch
    // launcher. Either location is valid; require one, not both.
    const directNativeImage = await hasExecutable(join(home, "bin"), "native-image");
    const svmNativeImage = await hasExecutable(join(home, "lib", "svm", "bin"), "native-image");
    if (!directNativeImage && !svmNativeImage) missing.push("native-image");
    if (missing.length) return { error: `${home}: missing ${missing.join(", ")}` };
    return {
      home,
      javaVersion: javaVersion.join("."),
      graalVersion: graalVersionText,
      legacy: isLegacy,
    };
  }
  return { error: `${raw}: not a GraalVM JDK home` };
}

async function resolveGraalVm() {
  const explicit = graalVmHomeArg || process.env.GRAALVM_HOME;
  const candidates = explicit
    ? [explicit]
    : [process.env.JAVA_HOME, ...(await discoverGraalHomes())];
  const attempted = [];
  for (const raw of candidates) {
    if (!raw) continue;
    const result = await inspectGraalHome(raw, { allowLegacy: Boolean(explicit) });
    if (result.home) return result;
    if (result.error) attempted.push(result.error);
  }
  const detail = attempted.length ? `\n  Checked:\n${attempted.map((line) => `    ${line}`).join("\n")}` : "";
  die("no supported Oracle GraalVM Web Image JDK found.\n"
    + "  Install Oracle GraalVM Web Image 25.1+ (the clean bootstrap pins 25.2.4/25i2), then set:\n"
    + "    export GRAALVM_HOME=/path/to/graalvm-jdk-25i2-25.0.4/Contents/Home\n"
    + "    export JAVA_HOME=\"$GRAALVM_HOME\"\n"
    + "  Or pass --graalvm-home /path/to/Contents/Home. The build does not download a JDK.\n"
    + "  An old 25.0.4 toolchain is accepted only when explicitly selected via GRAALVM_HOME or --graalvm-home."
    + detail);
}

// ---------------------------------------------------------------- installation

// The build consumes the official Minecraft Launcher's vanilla data directory.
// Keep discovery limited to that layout so a different launcher cannot silently
// supply a mismatched manifest, classpath, or asset store.
function candidateRoots() {
  const home = homedir();
  if (platform() === "darwin") return [join(home, "Library/Application Support/minecraft")];
  if (platform() === "win32") {
    const appData = process.env.APPDATA || join(process.env.USERPROFILE || home, "AppData/Roaming");
    return [join(appData, ".minecraft")];
  }
  return [join(home, ".minecraft")];
}

// A usable root is one that actually has this version's manifest somewhere under it.
async function findVersionManifest(root) {
  const direct = safeJoin(root, `versions/${version}/${version}.json`, "version manifest path");
  if (await exists(direct)) {
    return {
      manifest: direct,
      librariesRoot: safeJoin(root, "libraries", "libraries root"),
      assetsRoot: safeJoin(root, "assets", "assets root"),
    };
  }
  return null;
}

// ------------------------------------------------------------------ download
//
// The official launcher's authenticated session is used by the local gateway,
// not by this input fetcher. Minecraft's version manifest, client JAR, libraries,
// asset index and asset objects are public CDN downloads, just as PrismLauncher
// obtains them. The downloader is deliberately restricted to those official
// HTTPS hosts and verifies every published SHA-1/size (plus the pinned client
// SHA-256) before atomically committing a file to the local cache.

const VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const RESOURCES = "https://resources.download.minecraft.net";
const DOWNLOAD_ATTEMPTS = 4;
const DOWNLOAD_TIMEOUT_MS = 30_000;
const MAX_REDIRECTS = 3;
const MAX_OBJECT_BYTES = 512 * 1024 * 1024;
const MAX_JSON_BYTES = 64 * 1024 * 1024;
const DOWNLOAD_RECORD = ".mcweb-download.json";
const CACHE_LOCK_DIR = ".mcweb-download.lock";
const CACHE_LOCK_TIMEOUT_MS = 30_000;
const CACHE_LOCK_STALE_MS = 10 * 60_000;

function officialUrl(raw, label, options = {}) {
  try {
    return validateCdnUrl(raw, { ...options, label });
  } catch (error) {
    error.policy = true;
    throw error;
  }
}

function expectedSize(value, label) {
  if (!Number.isSafeInteger(Number(value)) || Number(value) < 0) {
    throw Object.assign(new Error(`${label} is missing a valid byte size`), { fatal: true });
  }
  return Number(value);
}

function expectedSha1(value, label) {
  const digest = String(value ?? "").toLowerCase();
  if (!/^[0-9a-f]{40}$/.test(digest)) {
    throw Object.assign(new Error(`${label} is missing a valid SHA-1`), { fatal: true });
  }
  return digest;
}

function expectedSha256(value, label) {
  const digest = String(value ?? "").toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(digest)) {
    throw Object.assign(new Error(`${label} is missing a valid SHA-256`), { fatal: true });
  }
  return digest;
}

function transientStatus(status) {
  return status === 408 || status === 429 || status >= 500;
}

function declaredLength(response, label) {
  const raw = response.headers.get("content-length");
  if (raw === null) return null;
  const length = Number(raw);
  if (!Number.isSafeInteger(length) || length < 0) {
    throw Object.assign(new Error(`${label} declared an invalid Content-Length`), { fatal: true });
  }
  return length;
}

async function fetchRedirected(url, { kind, expectedPath = null, label = "CDN URL", signal } = {}) {
  let current = officialUrl(url, label, { kind, expectedPath });
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    const response = await fetch(current, { signal, redirect: "manual" });
    if (response.status >= 300 && response.status < 400) {
      if (hop === MAX_REDIRECTS) throw new Error(`${label} exceeded the ${MAX_REDIRECTS}-hop redirect limit`);
      try {
        current = validateRedirect(current.href, response.headers.get("location"), {
          kind,
          expectedPath,
          label: `${label} redirect`,
        });
      } catch (error) {
        error.policy = true;
        throw error;
      }
      continue;
    }
    return { response, url: current };
  }
  throw new Error(`${label} exceeded the ${MAX_REDIRECTS}-hop redirect limit`);
}

async function readCappedBody(response, controller, maxBytes, label) {
  if (!response.body) throw new Error(`${label} returned no body`);
  const chunks = [];
  let size = 0;
  for await (const chunk of Readable.fromWeb(response.body)) {
    size += chunk.length;
    if (size > maxBytes) {
      controller.abort();
      throw Object.assign(new Error(`${label} exceeded the ${maxBytes}-byte response cap`), { fatal: true });
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, size);
}

async function fetchJson(url, { kind = "global", expectedPath = null, offlineMode = false } = {}) {
  const requested = officialUrl(url, "JSON CDN URL", { kind, expectedPath });
  if (offlineMode) throw new Error(`offline mode prevents fetching ${requested}`);
  let last = "";
  for (let attempt = 0; attempt < DOWNLOAD_ATTEMPTS; attempt++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), DOWNLOAD_TIMEOUT_MS);
    try {
      const { response, url: finalUrl } = await fetchRedirected(requested, {
        kind, expectedPath, label: "JSON CDN URL", signal: controller.signal,
      });
      if (!response.ok) {
        last = `HTTP ${response.status}`;
        if (!transientStatus(response.status)) {
          throw Object.assign(new Error(`GET ${finalUrl} -> HTTP ${response.status}`), { fatal: true });
        }
      } else {
        const contentLength = declaredLength(response, `${finalUrl} JSON`);
        if (contentLength !== null && contentLength > MAX_JSON_BYTES) {
          throw Object.assign(new Error(`${finalUrl} JSON is too large`), { fatal: true });
        }
        const text = (await readCappedBody(response, controller, MAX_JSON_BYTES, `${finalUrl} JSON`)).toString("utf8");
        try {
          return JSON.parse(text);
        } catch (error) {
          throw new Error(`${finalUrl} returned invalid JSON: ${error.message}`);
        }
      }
    } catch (error) {
      last = error?.cause?.code || error?.cause?.errors?.[0]?.code || error?.message || String(error);
      if (error?.policy || error?.fatal || error?.message?.startsWith("GET ")
          || error?.message?.includes("invalid JSON") || error?.message?.includes("outside the official")) throw error;
    } finally {
      clearTimeout(timer);
    }
    if (attempt < DOWNLOAD_ATTEMPTS - 1) await new Promise((resolve) => setTimeout(resolve, 500 * 2 ** attempt));
  }
  throw Object.assign(new Error(`GET ${requested} failed after ${DOWNLOAD_ATTEMPTS} attempts (${last})`), {
    retryable: true,
  });
}

async function fileDigests(path, algorithms, maxBytes = MAX_OBJECT_BYTES) {
  const info = await stat(path);
  if (!Number.isSafeInteger(info.size) || info.size > maxBytes) {
    throw Object.assign(new Error(`${path} exceeds its ${maxBytes}-byte cache-file cap`), { fatal: true });
  }
  const wanted = new Map(algorithms.map((algorithm) => [algorithm, createHash(algorithm)]));
  let size = 0;
  for await (const chunk of createReadStream(path)) {
    size += chunk.length;
    if (size > maxBytes) {
      throw Object.assign(new Error(`${path} exceeds its ${maxBytes}-byte cache-file cap`), { fatal: true });
    }
    for (const hash of wanted.values()) hash.update(chunk);
  }
  return { size, digests: new Map([...wanted].map(([algorithm, hash]) => [algorithm, hash.digest("hex")])) };
}

async function readCappedFile(path, maxBytes, label = path) {
  const info = await stat(path);
  if (!Number.isSafeInteger(info.size) || info.size > maxBytes) {
    throw Object.assign(new Error(`${label} exceeds its ${maxBytes}-byte cache-file cap`), { fatal: true });
  }
  const chunks = [];
  let size = 0;
  for await (const chunk of createReadStream(path)) {
    size += chunk.length;
    if (size > maxBytes) {
      throw Object.assign(new Error(`${label} exceeds its ${maxBytes}-byte cache-file cap`), { fatal: true });
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, size);
}

async function fileHash(algorithm, path, maxBytes = MAX_OBJECT_BYTES) {
  return (await fileDigests(path, [algorithm], maxBytes)).digests.get(algorithm);
}

async function atomicDownload(url, dest, {
  sha1 = null,
  sha256 = null,
  size = null,
  maxBytes = MAX_OBJECT_BYTES,
  readOnly = false,
  kind,
  expectedPath = null,
} = {}) {
  const requested = officialUrl(url, "artifact URL", { kind, expectedPath });
  if (size !== null && size > maxBytes) {
    throw Object.assign(new Error(`artifact ${requested} exceeds its ${maxBytes}-byte cap`), { fatal: true });
  }
  const parent = dirname(dest);
  if (!readOnly) await mkdir(parent, { recursive: true });
  if (await exists(dest)) {
    const actual = await fileDigests(dest, ["sha1", ...(sha256 ? ["sha256"] : [])], maxBytes);
    if ((size === null || actual.size === size)
        && (!sha1 || actual.digests.get("sha1") === sha1)
        && (!sha256 || actual.digests.get("sha256") === sha256)) return false;
  }
  if (offline) throw new Error(`offline cache artifact missing or corrupt: ${dest}`);
  if (readOnly) throw new Error(`read-only cache verification found a missing or corrupt artifact: ${dest}`);

  let last = "";
  for (let attempt = 0; attempt < DOWNLOAD_ATTEMPTS; attempt++) {
    const temp = `${dest}.part-${process.pid}-${Math.random().toString(16).slice(2)}`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), DOWNLOAD_TIMEOUT_MS);
    try {
      const { response, url: finalUrl } = await fetchRedirected(requested, {
        kind, expectedPath, label: "artifact URL", signal: controller.signal,
      });
      if (!response.ok || !response.body) {
        last = `HTTP ${response.status}`;
        if (!transientStatus(response.status)) {
          throw Object.assign(new Error(`GET ${finalUrl} -> HTTP ${response.status}`), { fatal: true });
        }
      } else {
        const contentLength = declaredLength(response, String(finalUrl));
        if (contentLength !== null
            && (contentLength > maxBytes || (size !== null && contentLength !== size))) {
          throw Object.assign(new Error(`${finalUrl} declared an invalid response size ${contentLength}`), { fatal: true });
        }
        const hashes = new Map([["sha1", createHash("sha1")]]);
        if (sha256) hashes.set("sha256", createHash("sha256"));
        let received = 0;
        const meter = new Transform({
          transform(chunk, encoding, callback) {
            if (received + chunk.length > maxBytes || (size !== null && received + chunk.length > size)) {
              controller.abort();
              callback(Object.assign(new Error(`${finalUrl} exceeded its expected byte limit`), { fatal: true }));
              return;
            }
            received += chunk.length;
            for (const hash of hashes.values()) hash.update(chunk);
            callback(null, chunk);
          },
        });
        await pipeline(Readable.fromWeb(response.body), meter, createWriteStream(temp, { flags: "wx" }));
        const actualSha1 = hashes.get("sha1").digest("hex");
        const actualSha256 = hashes.get("sha256")?.digest("hex") ?? null;
        if ((size !== null && received !== size)
            || (sha1 && actualSha1 !== sha1)
            || (sha256 && actualSha256 !== sha256)) {
          throw new Error(`checksum/size mismatch for ${finalUrl}\n`
            + `  expected size ${size ?? "(not published)"}, SHA-1 ${sha1 ?? "(not published)"}, SHA-256 ${sha256 ?? "(not published)"}\n`
            + `  got      size ${received}, SHA-1 ${actualSha1}, SHA-256 ${actualSha256 ?? "(not checked)"}`);
        }
        await rename(temp, dest);
        return true;
      }
    } catch (error) {
      last = error?.cause?.code || error?.cause?.errors?.[0]?.code || error?.message || String(error);
      if (error?.policy || error?.fatal || error?.message?.startsWith("GET ")
          || error?.message?.includes("outside the official")) throw error;
    } finally {
      clearTimeout(timer);
      await rm(temp, { force: true }).catch(() => {});
    }
    if (attempt < DOWNLOAD_ATTEMPTS - 1) await new Promise((resolve) => setTimeout(resolve, 500 * 2 ** attempt));
  }
  throw new Error(`GET ${requested} failed after ${DOWNLOAD_ATTEMPTS} attempts (${last})`);
}

async function atomicJson(path, value) {
  const temp = `${path}.part-${process.pid}-${Math.random().toString(16).slice(2)}`;
  await mkdir(dirname(path), { recursive: true });
  try {
    await writeFile(temp, `${JSON.stringify(value, null, 2)}\n`, { flag: "wx" });
    await rename(temp, path);
  } finally {
    await rm(temp, { force: true }).catch(() => {});
  }
}

function processIsAlive(pid) {
  if (!Number.isSafeInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error?.code === "EPERM";
  }
}

async function acquireCacheLock(cacheDir) {
  const lockPath = safeJoin(cacheDir, CACHE_LOCK_DIR, "download lock path");
  await mkdir(cacheDir, { recursive: true });
  const startedWaiting = Date.now();
  const token = `${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  while (true) {
    try {
      await mkdir(lockPath);
      try {
        await atomicJson(safeJoin(lockPath, "owner.json", "download lock owner"), {
          pid: process.pid,
          startedAt: Date.now(),
          token,
        });
      } catch (error) {
        await rm(lockPath, { recursive: true, force: true }).catch(() => {});
        throw error;
      }
      return async () => {
        const ownerPath = safeJoin(lockPath, "owner.json", "download lock owner");
        const owner = JSON.parse((await readCappedFile(ownerPath, MAX_JSON_BYTES, "download lock owner")
          .catch((error) => {
            if (error?.code === "ENOENT") return Buffer.from("null");
            throw error;
          })).toString("utf8"));
        if (owner?.token === token) await rm(lockPath, { recursive: true, force: true });
      };
    } catch (error) {
      if (error?.code !== "EEXIST") throw error;
      const lockStat = await stat(lockPath).catch(() => null);
      if (!lockStat) continue;
      const owner = JSON.parse((await readCappedFile(
        safeJoin(lockPath, "owner.json", "download lock owner"), MAX_JSON_BYTES, "download lock owner",
      ).catch((error) => {
        if (error?.code === "ENOENT") return Buffer.from("null");
        throw error;
      })).toString("utf8"));
      const ownerStart = Number(owner?.startedAt);
      const ageStart = Number.isFinite(ownerStart) ? ownerStart : Number(lockStat?.mtimeMs || 0);
      const stale = ageStart > 0 && Date.now() - ageStart > CACHE_LOCK_STALE_MS
        && (!owner || !processIsAlive(Number(owner.pid)));
      if (stale) {
        await rm(lockPath, { recursive: true, force: true }).catch(() => {});
        continue;
      }
      if (Date.now() - startedWaiting >= CACHE_LOCK_TIMEOUT_MS) {
        throw new Error(`Minecraft CDN cache lock is busy: ${lockPath}`);
      }
      await new Promise((resolveWait) => setTimeout(resolveWait, 250));
    }
  }
}

async function withCacheLock(cacheDir, work) {
  const release = await acquireCacheLock(cacheDir);
  try {
    return await work();
  } finally {
    await release().catch(() => {});
  }
}

async function pool(items, limit, worker) {
  let index = 0;
  let done = 0;
  const runners = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (index < items.length) {
      const item = items[index++];
      await worker(item);
      done++;
      if (done % 250 === 0) process.stdout.write(`\r  ${done}/${items.length}`);
    }
  });
  await Promise.all(runners);
  if (items.length >= 250) process.stdout.write(`\r  ${items.length}/${items.length}\n`);
}

function parseDownloadRecord(value, path) {
  if (!value || value.schema !== 1 || value.version !== version || !value.entry) {
    throw new Error(`cached download record is invalid: ${path}`);
  }
  const entry = value.entry;
  if (entry.id !== version || value.assetIndexId !== "32" || value.clientJarSha256 !== EXPECTED_CLIENT_SHA256) {
    throw new Error(`cached download record is for an unsupported Minecraft input set: ${path}`);
  }
  officialUrl(entry.url, "cached version manifest URL", { kind: "version" });
  expectedSha1(entry.sha1, "cached version manifest SHA-1");
  return entry;
}

// Lays the download out exactly like a vanilla .minecraft directory so the ordinary
// resolver above can consume it with no special cases.
async function downloadInstallationUnlocked(cacheDir, { readOnly = false } = {}) {
  if (!readOnly) await mkdir(cacheDir, { recursive: true });
  say(`downloading ${version} into ${cacheDir}`);
  const versionJson = safeJoin(cacheDir, `versions/${version}/${version}.json`, "version cache manifest");
  const recordPath = safeJoin(cacheDir, DOWNLOAD_RECORD, "download record");
  let entry = null;
  if (!offline) {
    try {
      const global = await fetchJson(VERSION_MANIFEST, {
        kind: "global", expectedPath: "/mc/game/version_manifest_v2.json",
      });
      entry = (global.versions ?? []).find((candidate) => candidate.id === version);
      if (!entry) {
        throw Object.assign(new Error(`version ${version} is not in the Mojang manifest`), { fatal: true });
      }
      officialUrl(entry.url, "version manifest URL", { kind: "version" });
      expectedSha1(entry.sha1, "version manifest SHA-1");
    } catch (error) {
      if (!error?.retryable) throw error;
      say(`  Mojang manifest unavailable; trying verified cache (${error.message})`);
    }
  }
  if (!entry) {
    let cached = null;
    try {
      cached = JSON.parse((await readCappedFile(recordPath, MAX_JSON_BYTES, "cached download record")).toString("utf8"));
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
    entry = parseDownloadRecord(cached, recordPath);
    say("  using verified offline Minecraft cache");
  }

  await atomicDownload(entry.url, versionJson, {
    sha1: expectedSha1(entry.sha1, "version manifest SHA-1"),
    size: entry.size === undefined ? null : expectedSize(entry.size, "version manifest size"),
    maxBytes: MAX_JSON_BYTES,
    readOnly,
    kind: "version",
  });
  let client;
  try {
    client = JSON.parse((await readCappedFile(versionJson, MAX_JSON_BYTES, "Minecraft version manifest")).toString("utf8"));
  } catch (error) {
    throw new Error(`downloaded Minecraft ${version} manifest is invalid JSON: ${error.message}`);
  }
  if (client.id !== version || !client.downloads?.client) {
    throw new Error(`Minecraft ${version} manifest is missing the exact client download`);
  }
  const clientDownload = client.downloads.client;
  const clientSha1 = expectedSha1(clientDownload.sha1, "client JAR SHA-1");
  const clientSize = expectedSize(clientDownload.size, "client JAR size");
  officialUrl(clientDownload.url, "client JAR URL", { kind: "client" });
  say("  client jar");
  await atomicDownload(clientDownload.url, safeJoin(cacheDir, `versions/${version}/${version}.jar`, "client cache path"), {
    sha1: clientSha1,
    sha256: EXPECTED_CLIENT_SHA256,
    size: clientSize,
    readOnly,
    kind: "client",
  });

  const libs = (client.libraries ?? []).filter((l) => ruleAllows(l.rules)
    && !(l.name && isNativeCoord(l.name)) && l.downloads?.artifact?.path);
  say(`  ${libs.length} libraries`);
  await pool(libs, 8, async (lib) => {
    const artifact = lib.downloads.artifact;
    const relative = safeRelativePath(artifact.path, `library ${lib.name || "artifact"} path`);
    const sha1 = expectedSha1(artifact.sha1, `library ${lib.name || relative} SHA-1`);
    const size = expectedSize(artifact.size, `library ${lib.name || relative} size`);
    const libraryUrl = officialUrl(artifact.url, `library ${lib.name || relative} URL`, {
      kind: "library", expectedPath: relative,
    });
    await atomicDownload(libraryUrl, safeJoin(cacheDir, `libraries/${relative}`, "library cache path"), {
      sha1, size, readOnly, kind: "library", expectedPath: relative,
    });
  });

  // The title panorama is needed even for a silent build. Always fetch and verify
  // the asset index and its seven title objects first; --no-audio only skips the
  // sound objects below.
  const assetIndex = client.assetIndex;
  if (!assetIndex || typeof assetIndex.id !== "string" || !/^[0-9a-f]{40}$/i.test(assetIndex.sha1 || "")) {
    throw new Error(`Minecraft ${version} manifest is missing a valid asset-index id/SHA-1`);
  }
  const assetIndexId = assetIndex.id;
  if (version === VERSION && assetIndexId !== "32") {
    throw new Error(`Minecraft ${version} must use asset index 32, found ${assetIndexId}`);
  }
  const indexPath = safeJoin(cacheDir, `assets/indexes/${assetIndexId}.json`, "asset-index cache path");
  await atomicDownload(assetIndex.url, indexPath, {
    sha1: expectedSha1(assetIndex.sha1, "asset index SHA-1"),
    size: expectedSize(assetIndex.size, "asset index size"),
    maxBytes: MAX_JSON_BYTES,
    readOnly,
    kind: "assetIndex",
    expectedPath: `/v1/packages/${assetIndex.sha1}/${assetIndex.id}.json`,
  });
  let index;
  try {
    index = JSON.parse((await readCappedFile(indexPath, MAX_JSON_BYTES, "asset index")).toString("utf8"));
  } catch (error) {
    throw new Error(`asset index ${assetIndexId} is invalid JSON: ${error.message}`);
  }
  if (!index.objects || typeof index.objects !== "object" || Array.isArray(index.objects)) {
    throw new Error(`asset index ${assetIndexId} has no objects map`);
  }
  const titleObjects = PANORAMA_KEYS.map((name) => {
    const meta = index.objects[name];
    const hash = expectedSha1(meta?.hash, `asset ${name} SHA-1`);
    const size = expectedSize(meta?.size, `asset ${name} size`);
    return [name, hash, size];
  });
  say(`  ${titleObjects.length} title panorama objects`);
  await pool(titleObjects, 16, ([, hash, size]) => {
    const rel = `${hash.slice(0, 2)}/${hash}`;
    return atomicDownload(`${RESOURCES}/${rel}`, safeJoin(cacheDir, `assets/objects/${rel}`, "asset cache path"), {
      sha1: hash, size, readOnly, kind: "assetObject", expectedPath: rel,
    });
  });
  if (noAudio) {
    say("  skipping sound objects (--no-audio)");
  } else {
    // Only sounds are needed beyond the title assets: every other texture and
    // data file lives inside the client jar, and stageMinecraftAudio wants
    // sounds.json plus the .ogg objects.
    const sounds = Object.entries(index.objects)
      .filter(([name]) => name.endsWith("/sounds.json") || (name.includes("/sounds/") && name.endsWith(".ogg")))
      .map(([name, meta]) => [name, expectedSha1(meta?.hash, `asset ${name} SHA-1`), expectedSize(meta?.size, `asset ${name} size`)]);
    say(`  ${sounds.length} sound objects (of ${Object.keys(index.objects).length} total)`);
    await pool(sounds, 16, ([, hash, size]) => {
      const rel = `${hash.slice(0, 2)}/${hash}`;
      return atomicDownload(`${RESOURCES}/${rel}`, safeJoin(cacheDir, `assets/objects/${rel}`, "sound cache path"), {
        sha1: hash, size, readOnly, kind: "assetObject", expectedPath: rel,
      });
    });
  }
  if (!readOnly) {
    await atomicJson(recordPath, {
      schema: 1,
      version,
      entry: { id: entry.id, url: entry.url, sha1: entry.sha1, ...(entry.size === undefined ? {} : { size: entry.size }) },
      assetIndexId,
      clientJarSha256: EXPECTED_CLIENT_SHA256,
      audio: !noAudio,
    });
    say("download complete (verified cache committed atomically)");
  } else {
    say("offline dry-run: verified cache contents without writes");
  }
}

async function downloadInstallation(cacheDir) {
  return withCacheLock(cacheDir, () => downloadInstallationUnlocked(cacheDir));
}

async function locateInstallation() {
  const explicit = flag("mc-dir");
  const wantsDownload = has("download") || downloadOnly || (!explicit && !has("local-only"));
  if (wantsDownload) {
    const cacheDir = resolve(flag("cache-dir", DEFAULT_MINECRAFT_CACHE));
    if (dryRun) {
      if (offline) {
        if (await exists(safeJoin(cacheDir, CACHE_LOCK_DIR, "download lock path"))) {
          throw new Error(`offline dry-run cannot verify a cache while its download lock is present: ${cacheDir}`);
        }
        await downloadInstallationUnlocked(cacheDir, { readOnly: true });
        const found = await findVersionManifest(cacheDir);
        if (!found) throw new Error(`offline dry-run verified no usable Minecraft ${version} cache at ${cacheDir}`);
        say(`dry-run: offline cache verified without network or writes at ${cacheDir}`);
        return { root: cacheDir, ...found };
      }
      const found = await findVersionManifest(cacheDir);
      const verifiedRecord = await exists(safeJoin(cacheDir, DOWNLOAD_RECORD, "download record"));
      if (!found || !verifiedRecord) {
        say(`dry-run: would download verified Minecraft ${version} inputs into ${cacheDir}`);
        return { planOnly: true, root: cacheDir };
      }
      say(`dry-run: using existing verified-cache layout at ${cacheDir}`);
      return { root: cacheDir, ...found };
    }
    await downloadInstallation(cacheDir);
    const found = await findVersionManifest(cacheDir);
    if (!found) throw new Error(`download completed but ${cacheDir} is not usable`);
    return { root: cacheDir, ...found };
  }
  const roots = explicit ? [resolve(explicit.replace(/^~/, homedir()))] : candidateRoots();
  for (const root of roots) {
    if (!(await exists(root))) continue;
    const found = await findVersionManifest(root);
    if (found) return { root, ...found };
  }
  throw new Error(`no Minecraft ${version} installation found.\n`
    + `  Looked in:\n${roots.map((r) => `    ${r}`).join("\n")}\n`
    + `  Pass --mc-dir /path/to/.minecraft or omit it to download from official Mojang CDNs.`);
}

// ------------------------------------------------------------------ classpath

// Launcher rules and exact native classifiers are shared with the CDN input
// policy module. Unsupported feature requirements fail closed.

async function collectLibraries(components, librariesRoot) {
  const jars = [];
  const missing = [];
  const seen = new Set();
  for (const manifest of components) {
    for (const lib of manifest.libraries ?? []) {
      if (!ruleAllows(lib.rules)) continue;
      if (lib.name && isNativeCoord(lib.name)) continue;
      const relative = lib.downloads?.artifact?.path;
      if (!relative || seen.has(relative)) continue;
      seen.add(relative);
      const safeRelative = safeRelativePath(relative, `library ${lib.name || "artifact"} path`);
      const file = safeJoin(librariesRoot, safeRelative, "local library path");
      if (await exists(file)) jars.push(file); else missing.push(relative);
    }
  }
  return { jars, missing };
}

async function resolveLibraries(install) {
  const manifest = JSON.parse((await readCappedFile(install.manifest, MAX_JSON_BYTES, "Minecraft version manifest")).toString("utf8"));
  if (manifest.inheritsFrom) {
    die(`${basename(install.manifest)} inherits from ${manifest.inheritsFrom} (a modded profile).\n`
      + `  Build from a vanilla ${version} installation.`);
  }
  const components = [manifest];
  const { jars, missing } = await collectLibraries(components, install.librariesRoot);
  return { manifest, jars, missing };
}

function run(cmd, args, opts = {}) {
  return new Promise((ok, no) => {
    // Node cannot exec a .bat/.cmd directly (EINVAL on Windows); batch launchers
    // such as gradlew.bat have to go through the command processor.
    let command = cmd;
    let argList = args;
    if (process.platform === "win32" && /\.(bat|cmd)$/i.test(cmd)) {
      command = process.env.ComSpec || "cmd.exe";
      argList = ["/c", cmd, ...args];
    }
    const child = spawn(command, argList, { stdio: "inherit", cwd: PROJECT, ...opts });
    child.on("exit", (code) => (code === 0 ? ok() : no(new Error(`${cmd} exited ${code}`))));
    child.on("error", no);
  });
}

// ----------------------------------------------------------------------- main

const install = await locateInstallation();
if (install.planOnly) {
  say("--dry-run: no network downloads or writes.");
  process.exit(0);
}
say(`installation:   ${install.root}`);
say(`manifest:       ${install.manifest}`);

const { manifest, jars, missing } = await resolveLibraries(install);
if (missing.length) {
  die(`${missing.length} library jar(s) referenced by the manifest are not downloaded, e.g.\n`
    + `    ${missing.slice(0, 3).join("\n    ")}\n`
    + `  Launch ${version} once in your launcher so it fetches them, then re-run.`);
}
say(`libraries:      ${jars.length}`);

const clientJar = safeJoin(install.root, `versions/${version}/${version}.jar`, "client jar path");
if (!(await exists(clientJar))) die(`client jar not found: ${clientJar}`);
const publishedClientSize = Number(manifest.downloads?.client?.size);
if (!Number.isSafeInteger(publishedClientSize) || publishedClientSize < 0) {
  die(`Minecraft ${version} manifest is missing a valid client-JAR size`);
}
const localClientStat = await stat(clientJar);
if (localClientStat.size !== publishedClientSize) {
  die(`client jar size mismatch: expected ${publishedClientSize}, got ${localClientStat.size}`);
}
const clientSha = await fileHash("sha256", clientJar);
say(`client jar:     ${clientJar}`);
say(`client sha256:  ${clientSha}${clientSha === EXPECTED_CLIENT_SHA256 ? "  (matches known 26.2)" : "  (UNRECOGNISED)"}`);
if (clientSha !== EXPECTED_CLIENT_SHA256 && !has("allow-unknown-jar")) {
  die(`this jar is not the 26.2 client this port was built against.\n`
    + `  The transforms are version-specific and will almost certainly fail.\n`
    + `  Re-run with --allow-unknown-jar to try anyway.`);
}

const assetIndex = manifest.assetIndex;
if (!assetIndex || typeof assetIndex.id !== "string" || !/^[0-9a-f]{40}$/i.test(assetIndex.sha1 || "")) {
  die(`Minecraft ${version} manifest is missing its asset-index id/SHA-1; refusing to stage unverified assets`);
}
const assetIndexId = assetIndex.id;
if (version === VERSION && assetIndexId !== "32") {
  die(`Minecraft ${version} must use asset index 32, found ${assetIndexId}`);
}
const assetIndexFile = safeJoin(install.assetsRoot, `indexes/${assetIndexId}.json`, "asset-index path");
const haveAssets = await exists(assetIndexFile);
if (!haveAssets) die(`asset index ${assetIndexId} not found: ${assetIndexFile}`);
say(`asset index:    ${assetIndexId}`);

// Validate and reconstruct the local title assets before any GraalVM/Gradle
// preflight. This is deliberately shared with the standalone staging command so
// a clean build cannot produce a black title screen merely because that command
// was omitted. Dry-run validates the complete plan but writes no derived bytes.
const stagedMojangAssets = stageMojangAssets({
  jarPath: clientJar,
  assetsRoot: install.assetsRoot,
  projectRoot: PROJECT,
  assetIndexId,
  assetIndexSha1: assetIndex.sha1,
  dryRun,
});
say(`title assets:   ${stagedMojangAssets.files.length} validated${dryRun ? " (planned; no writes)" : " staged"}`);

if (downloadOnly) {
  say("\n--download-only: inputs downloaded and verified; stopping before GraalVM/image build.");
  process.exit(0);
}

// Resolve the compiler before touching the ignored staging directory. This turns
// the late Gradle "Set GRAALVM_HOME" failure into a short, actionable message
// and makes every Gradle subprocess use one identical toolchain home.
const graalVm = dryRun ? null : await resolveGraalVm();
if (graalVm) {
  say(`graalvm:        ${graalVm.home} (Oracle ${graalVm.graalVersion}${graalVm.legacy ? "; legacy project-compatible toolchain explicitly selected" : ""})`);
}

// Stage the classpath in one directory so the build gets an explicit, ordered set
// rather than re-deriving it from launcher logs.
const stagedLibs = join(PROJECT, "build", "minecraft-libraries");
say(`\nstaging ${jars.length} libraries -> ${stagedLibs}`);
if (!dryRun) {
  await rm(stagedLibs, { recursive: true, force: true });
  await mkdir(stagedLibs, { recursive: true });
  for (const jar of jars) {
    const dest = join(stagedLibs, basename(jar));
    await symlink(jar, dest).catch(async () => { await cp(jar, dest); });
  }
  // The build reads the client jar from a fixed project-root path.
  const projectJar = join(PROJECT, `minecraft-${version}-client.jar`);
  if (!(await exists(projectJar))) {
    await symlink(clientJar, projectJar).catch(async () => { await cp(clientJar, projectJar); });
    say(`linked client jar -> ${projectJar}`);
  }
}

if (dryRun) {
  say("\n--dry-run: inputs resolved, stopping before the build.");
  process.exit(0);
}

const gradleArgs = [
  "buildGraalWeb",
  "-PgraalMainClass=dev.mcweb.graal.BrowserMinecraftMain",
  "-PgraalOutputName=minecraft-client",
  `-PgraalVmHome=${graalVm.home}`,
  `-PmcLibrariesDir=${stagedLibs}`,
];
if (!noAudio && haveAssets) gradleArgs.push(`-PmcAssetsDir=${install.assetsRoot}`);
if (graalExtraArgs) gradleArgs.push(`-PgraalExtraArgs=${graalExtraArgs}`);

const gradleEnv = {
  ...process.env,
  GRAALVM_HOME: graalVm.home,
  JAVA_HOME: graalVm.home,
};
const binaryenBin = await findBinaryenBin();
if (binaryenBin) {
  const separator = process.platform === "win32" ? ";" : ":";
  gradleEnv.PATH = `${binaryenBin}${separator}${gradleEnv.PATH || ""}`;
  say(`binaryen:       ${binaryenBin}`);
}

say(`\nbuilding image (this takes ~9 minutes)\n  ${GRADLEW} ${gradleArgs.join(" ")}\n`);
await run(GRADLEW,gradleArgs,{ env: gradleEnv });
if (!noAudio && haveAssets) {
  say("\nstaging audio");
  await run(GRADLEW,["stageMinecraftAudio", `-PgraalVmHome=${graalVm.home}`, `-PmcAssetsDir=${install.assetsRoot}`], { env: gradleEnv });
}

// ------------------------------------------------------------------- packaging

const staged = join(PROJECT, "build", "web-graal");
const wanted = ["graal/minecraft-client.js", "graal/minecraft-client.js.wasm"];
say(`\npackaging -> ${outDir}`);
await rm(outDir, { recursive: true, force: true });
await mkdir(join(outDir, "graal"), { recursive: true });
for (const rel of wanted) {
  const src = join(staged, rel);
  if (!(await exists(src))) die(`expected build output missing: ${src}`);
  await cp(src, join(outDir, rel));
}
const audioSrc = join(staged, "mcweb-audio");
if (await exists(audioSrc)) await cp(audioSrc, join(outDir, "mcweb-audio"), { recursive: true });

const imageSha = await fileHash("sha256", join(outDir, "graal/minecraft-client.js.wasm"));
await writeFile(join(outDir, "build-manifest.json"), JSON.stringify({
  version,
  builtAt: new Date().toISOString(),
  clientJarSha256: clientSha,
  assetIndexId,
  libraryCount: jars.length,
  imageSha256: imageSha,
}, null, 2) + "\n");

say(`\ndone.`);
say(`  image sha256: ${imageSha}`);
say(`  bundle:       ${outDir}`);
say(`\nThese files are derived from your own game installation. Load them into the`);
say(`web app; do not redistribute them.`);
