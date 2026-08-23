import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { chmod, mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import test from "node:test";
import { download, platformConfigFor, sidecarChecksum, toolUrl } from "../tools/mcweb-install.mjs";

const execFileAsync = promisify(execFile);
const ROOT = new URL("..", import.meta.url).pathname;
const realFetch = globalThis.fetch;
const NODE_ARCHIVE_URL = "https://nodejs.org/dist/v24.19.0/node-v24.19.0-darwin-arm64.tar.gz";
const GRAAL_ARCHIVE_URL = "https://gds.oracle.com/download/graal/25i2/archive/graalvm-jdk-25i2-25.0.4_macos-aarch64_bin.tar.gz";
const GRAAL_OBJECT_HOST = "https://objectstorage.uk-london-1.oraclecloud.com";
const GRAAL_OBJECT_PATH = (redirectId, file) => `${GRAAL_OBJECT_HOST}/p/${redirectId}/n/lr0crfzcb4ml/b/gds-artifacts/o/object-id/${file}`;
const GRAAL_ARM64_FILE = "graalvm-jdk-25i2-25.0.4_linux-aarch64_bin.tar.gz";
const GRAAL_AMD64_FILE = "graalvm-jdk-25i2-25.0.4_linux-x64_bin.tar.gz";
const BINARYEN_ARCHIVE_URL = "https://github.com/WebAssembly/binaryen/releases/download/version_131/binaryen-version_131-arm64-macos.tar.gz";
const BINARYEN_SIDECAR_URL = `${BINARYEN_ARCHIVE_URL}.sha256`;

async function tempDir(t) {
  const directory = await mkdtemp(join(tmpdir(), "mcweb-installer-test-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}

async function exists(path) {
  return !!(await stat(path).catch(() => null));
}

function digest(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

test("clean-machine installer publishes an explicit native/emulated matrix", async () => {
  const installer = `${ROOT}/tools/mcweb-install.mjs`;
  const { stdout } = await execFileAsync(process.execPath, [installer, "--platform-matrix", "--dry-run"], {
    cwd: ROOT,
    maxBuffer: 1024 * 1024,
  });
  assert.match(stdout, /darwin-arm64/);
  assert.match(stdout, /darwin-x64: unsupported/);
  assert.match(stdout, /linux-x64/);
  assert.match(stdout, /linux-arm64/);
  assert.match(stdout, /win32-x64/);
  assert.match(stdout, /win32-arm64/);
  assert.match(stdout, /Windows ARM64 uses the pinned Windows x64 GraalVM builder/);
  assert.match(stdout, /25\.2\.4 \(25i2\)/);
  assert.match(stdout, /SHA-256/);
  assert.match(stdout, /dry-run: no downloads or writes/);
});

test("Windows ARM64 selects x64 GraalVM under emulation without inventing an ARM archive", async () => {
  const native = platformConfigFor("win32", "arm64", { PROCESSOR_ARCHITECTURE: "ARM64" });
  assert.equal(native.key, "win32-arm64");
  assert.equal(native.node, "win-arm64");
  assert.equal(native.graal, "windows-x64");
  assert.match(native.emulated, /Windows x64 GraalVM builder/);

  const emulated = platformConfigFor("win32", "x64", {
    PROCESSOR_ARCHITECTURE: "AMD64",
    PROCESSOR_ARCHITEW6432: "ARM64",
  });
  assert.equal(emulated.key, "win32-arm64");
  assert.equal(emulated.node, "win-x64");
  assert.equal(emulated.graal, "windows-x64");

  const source = await readFile(`${ROOT}/tools/mcweb-install.mjs`, "utf8");
  assert.match(source, /windows-x64/);
  assert.doesNotMatch(source, /windows-aarch64/);
});

test("installer pins official archives and verifies vendor checksums", async () => {
  const source = await readFile(`${ROOT}/tools/mcweb-install.mjs`, "utf8");
  assert.match(source, /NODE_VERSION = "24\.19\.0"/);
  assert.match(source, /GRAALVM_VERSION = "25\.2\.4"/);
  assert.match(source, /GRAALVM_ARCHIVE = "25i2-25\.0\.4"/);
  assert.match(source, /MIN_GRAALVM_VERSION = \[25, 1, 0\]/);
  assert.match(source, /BINARYEN_VERSION = "131"/);
  assert.match(source, /nodejs\.org\/dist/);
  assert.match(source, /gds\.oracle\.com\/download\/graal\/25i2\/archive/);
  assert.match(source, /github\.com\/WebAssembly\/binaryen\/releases/);
  assert.match(source, /1b5937aa3076707459cfc815a1699761f943d2d1c9cbe03388e36d5e47eb27c3/);
  assert.match(source, /7100d99cbfec68b03b669cc60c7e8592bbcda1732e8eaebc460fe0b75849a894/);
  assert.match(source, /0bc65f9c36ae77bd83aad46a2b4de4b0ec97da1b4ac83fedb59e19f868873dee/);
  assert.match(source, /2b41fffc94c4c7795bce0fdde8847ab1c894903cb20779aedb6ca8628aa9983a/);
  assert.match(source, /d209fadd8a894bdaf3bd3612a23c32a0af184d2f4a979b8c789e6e4f6a4de883/);
  assert.match(source, /checksumUrl/);
  assert.match(source, /SHA-256/);
  assert.doesNotMatch(source, /minecraft\.wasm\.click|tcp\.wasm\.click|cloudflare/i);
});

test("README states the public GraalVM baseline and unsupported hosts", async () => {
  const readme = await readFile(`${ROOT}/README.md`, "utf8");
  assert.match(readme, /^# How to run/);
  assert.match(readme, /git clone https:\/\/github\.com\/vano04\/mcweb\.git/);
  assert.match(readme, /https:\/\/github\.com\/vano04\/mcweb\/archive\/refs\/heads\/main\.zip/);
  assert.match(readme, /sh \.\/run\.sh/);
  assert.match(readme, /powershell\.exe -NoProfile -ExecutionPolicy Bypass -File \.\\run\.ps1/);
  assert.doesNotMatch(readme, /raw\.githubusercontent\.com/i);
  assert.doesNotMatch(readme, /curl[^\n]*(?:\|\s*(?:sh|bash)|install\.ps1)/i);
  assert.doesNotMatch(readme, /New-TemporaryFile/);
  assert.doesNotMatch(readme, /irm\s*\|\s*iex/i);
  const windowsCodeBlock = readme.match(/```powershell\r?\n([\s\S]*?)\r?\n```/);
  assert.ok(windowsCodeBlock, "README must contain the Windows bootstrap code block");
  assert.equal(windowsCodeBlock[1].split(/\r?\n/).length, 1, "Windows bootstrap must be one line");
  assert.match(windowsCodeBlock[1], /run\.ps1/);
  assert.doesNotMatch(windowsCodeBlock[1], /curl/i);
  assert.doesNotMatch(readme, /Invoke-WebRequest/);
  assert.match(readme, /ExecutionPolicy Bypass/);
  assert.match(readme, /GraalVM Web Image 25\.1 or newer/);
  assert.match(readme, /25\.2\.4 \(25i2/);
  assert.match(readme, /receive-only/);
  assert.match(readme, /supported through Windows x64 emulation/);
  assert.match(readme, /10 GB of RAM/);
  assert.match(readme, /d209fadd8a894bdaf3bd3612a23c32a0af184d2f4a979b8c789e6e4f6a4de883/);
});

test("README separates CDN download mode from the optional local launcher mode", async () => {
  const readme = await readFile(`${ROOT}/README.md`, "utf8");
  assert.match(readme, /`--download`[\s\S]{0,180}cannot be combined with `--mc-dir`/);
  assert.match(readme, /When no `--mc-dir` or\s+`--local-only` is supplied, CDN download is the default/);
});

test("build preflight requires public Web Image but labels the old toolchain legacy", async () => {
  const [build, gradle] = await Promise.all([
    readFile(`${ROOT}/tools/build.mjs`, "utf8"),
    readFile(`${ROOT}/build.gradle`, "utf8"),
  ]);
  assert.match(build, /MIN_GRAAL_VERSION = \[25, 1, 0\]/);
  assert.match(build, /LEGACY_GRAAL_VERSION = \[25, 0, 4\]/);
  assert.match(build, /explicitly selected via GRAALVM_HOME or --graalvm-home/);
  assert.match(build, /lib.*svm.*builder.*svm\.jar/);
  assert.match(build, /svm-wasm.*builder.*svm-wasm\.jar/);
  assert.match(build, /org\.graalvm\.webimage\.api\.jmod/);
  assert.match(build, /native-image/);
  assert.match(build, /executableNames/);
  assert.match(build, /OS_ARCH/);
  assert.match(gradle, /native-image\.exe/);
  assert.match(gradle, /native-image\.cmd/);
});

test("root entrypoints route through one standard build-and-run flow", async () => {
  const [unixRun, windowsRun, unixInstall, windowsInstall] = await Promise.all([
    readFile(`${ROOT}/run.sh`, "utf8"),
    readFile(`${ROOT}/run.ps1`, "utf8"),
    readFile(`${ROOT}/install`, "utf8"),
    readFile(`${ROOT}/install.ps1`, "utf8"),
  ]);
  assert.match(unixRun, /tools\/install\.sh.*--run/);
  assert.match(windowsRun, /tools\\install\.ps1/);
  assert.match(windowsRun, /--run/);
  assert.match(windowsInstall, /\[switch\]\$Run/);
  assert.match(windowsInstall, /Usage: \.\\install\.ps1 \[-DryRun\] \[-Run\]/);
  assert.match(windowsInstall, /ExecutionPolicy Bypass/);
  assert.match(windowsInstall, /RunScript/);
  assert.match(windowsInstall, /\$RunExitCode = \$LASTEXITCODE/);
  for (const source of [unixInstall, windowsInstall]) {
    assert.match(source, /vano04\/mcweb/);
    assert.match(source, /refs\/heads/);
    assert.match(source, /(?:REF|Ref)\s*=?.*main/);
    assert.match(source, /codeload\.github\.com/);
    assert.match(source, /mcweb-install\.json/);
    assert.match(source, /refus(?:e|ing).*overwrite/i);
    assert.doesNotMatch(source, /minecraft-26\.2-client\.jar|\.wasm|\.ogg/);
  }
});

test("Unix root bootstrap dry-run is write-free and pinned", async (t) => {
  const install = `${ROOT}/install`;
  const destination = await tempDir(t);
  const result = await execFileAsync("sh", [install, "--dry-run"], {
    cwd: ROOT,
    env: { ...process.env, MCWEB_INSTALL_DIR: join(destination, "project") },
    timeout: 5000,
    maxBuffer: 1024 * 1024,
  });
  assert.match(result.stdout, /https:\/\/github\.com\/vano04\/mcweb\/archive\/refs\/heads\/main\.tar\.gz/);
  assert.match(result.stdout, /no downloads or writes/);
  assert.equal(await exists(join(destination, "project")), false);
});

test("bootstrap wrappers stay local and do not fetch a hosted installer", async () => {
  const [sh, ps1] = await Promise.all([
    readFile(`${ROOT}/tools/install.sh`, "utf8"),
    readFile(`${ROOT}/tools/install.ps1`, "utf8"),
  ]);
  for (const source of [sh, ps1]) {
    assert.match(source, /mcweb-install\.mjs/);
    assert.match(source, /SHASUMS256|Get-FileHash/);
    assert.doesNotMatch(source, /minecraft\.wasm\.click|tcp\.wasm\.click|cloudflare/i);
  }
});

test("clean-machine Node bootstrap is bounded, pinned, atomic, and dry-run safe", async () => {
  const [sh, ps1] = await Promise.all([
    readFile(`${ROOT}/tools/install.sh`, "utf8"),
    readFile(`${ROOT}/tools/install.ps1`, "utf8"),
  ]);
  assert.match(sh, /NODE_CHECKSUM_URL/);
  assert.match(sh, /validate_node_url/);
  assert.match(sh, /--max-redirs 0/);
  assert.match(sh, /--connect-timeout/);
  assert.match(sh, /--max-time/);
  assert.match(sh, /--max-filesize/);
  assert.match(sh, /mktemp/);
  assert.match(sh, /trap cleanup/);
  assert.match(sh, /has_arg --dry-run/);
  assert.match(sh, /mv -f/);
  assert.doesNotMatch(sh, /curl -fsSL\s+-o\s+"\$ARCHIVE"/);

  assert.match(ps1, /ChecksumUrl/);
  assert.match(ps1, /Assert-NodeUrl/);
  assert.match(ps1, /--max-redirs 0/);
  assert.match(ps1, /--connect-timeout/);
  assert.match(ps1, /--max-time/);
  assert.match(ps1, /--max-filesize/);
  assert.match(ps1, /New-NodeTemp/);
  assert.match(ps1, /Commit-Atomic/);
  assert.match(ps1, /Has-McDir/);
  assert.match(ps1, /Has-Arg '--dry-run'/);
  assert.match(ps1, /PROCESSOR_ARCHITEW6432/);
  assert.match(ps1, /trap\s*\{/);
  assert.match(ps1, /Cleanup-Temps\s*\n& \$Node/);
  assert.match(ps1, /File\]::Replace/);
  assert.doesNotMatch(ps1, /curl\.exe\s+-fsSL\s+-o/);
});

test("wrapper rejects CDN/local conflicts before curl, bootstrap, or writes", async (t) => {
  const shell = `${ROOT}/tools/install.sh`;
  const sandbox = await tempDir(t);
  const fakeBin = join(sandbox, "bin");
  const curlLog = join(sandbox, "curl.log");
  await mkdir(fakeBin);
  await writeFile(join(fakeBin, "curl"), "#!/bin/sh\nprintf '%s\\n' called >> \"$MCWEB_CURL_LOG\"\nexit 99\n");
  await chmod(join(fakeBin, "curl"), 0o755);

  for (const args of [
    ["--download", "--mc-dir", join(sandbox, "launcher")],
    ["--download-only", "--mc-dir", join(sandbox, "launcher-download-only")],
    ["--download", "--local-only"],
    ["--download-only", "--local-only"],
    ["--download", `--mc-dir=${join(sandbox, "launcher-equals")}`],
  ]) {
    const home = join(sandbox, `home-${args.join("-").replace(/[^A-Za-z0-9-]/g, "_")}`);
    await assert.rejects(execFileAsync("sh", [shell, ...args], {
      cwd: ROOT,
      env: {
        ...process.env,
        PATH: `${fakeBin}:/usr/bin:/bin`,
        MCWEB_HOME: home,
        MCWEB_FORCE_DOWNLOAD: "1",
        MCWEB_CURL_LOG: curlLog,
      },
      timeout: 3000,
      maxBuffer: 1024 * 1024,
    }), /cannot be combined with --mc-dir or --local-only/);
    assert.equal(await exists(home), false, `conflict must not create ${home}`);
  }
  assert.equal(await exists(curlLog), false, "conflict must not invoke curl");
});

test("POSIX wrapper removes bootstrap temps before a successful Node handoff", async (t) => {
  const shell = `${ROOT}/tools/install.sh`;
  const sandbox = await tempDir(t);
  const fakeBin = join(sandbox, "bin");
  const fixture = join(sandbox, "fixture");
  const archive = join(sandbox, "fixture.tar.gz");
  const home = join(sandbox, "home");
  await mkdir(join(fakeBin), { recursive: true });
  await mkdir(join(fixture, "bin"), { recursive: true });
  const fakeNode = join(fixture, "bin", "node");
  await writeFile(fakeNode, "#!/bin/sh\n[ \"$1\" = \"-v\" ] && { echo v24.19.0; exit 0; }\nexit 0\n");
  await chmod(fakeNode, 0o755);
  await execFileAsync("tar", ["-czf", archive, "-C", fixture, "."]);
  const sums = digest(await readFile(archive));
  const fakeCurl = join(fakeBin, "curl");
  await writeFile(fakeCurl, `#!/bin/sh
header=""
output=""
url=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -D) header="$2"; shift 2 ;;
    -o) output="$2"; shift 2 ;;
    *) url="$1"; shift ;;
  esac
done
printf 'HTTP/1.1 200 OK\\r\\n\\r\\n' > "$header"
if [ "$output" != "/dev/null" ]; then
  case "$url" in
    *SHASUMS256.txt) printf '%s  node-v24.19.0-darwin-arm64.tar.gz\\n' "$MCWEB_FAKE_SUM" > "$output" ;;
    *) cp "$MCWEB_FAKE_ARCHIVE" "$output" ;;
  esac
fi
`);
  await chmod(fakeCurl, 0o755);

  await execFileAsync("sh", [shell, "--verify"], {
    cwd: ROOT,
    env: {
      ...process.env,
      PATH: `${fakeBin}:/usr/bin:/bin`,
      MCWEB_HOME: home,
      MCWEB_FORCE_DOWNLOAD: "1",
      MCWEB_FAKE_SUM: sums,
      MCWEB_FAKE_ARCHIVE: archive,
    },
    timeout: 5000,
    maxBuffer: 1024 * 1024,
  });
  const entries = await readdir(home);
  assert.deepEqual(entries, ["node"]);
  assert.equal(await exists(join(home, "node", "bin", "node")), true);
});

test("clean build and installer paths automatically validate and stage title assets", async () => {
  const [build, installer] = await Promise.all([
    readFile(`${ROOT}/tools/build.mjs`, "utf8"),
    readFile(`${ROOT}/tools/mcweb-install.mjs`, "utf8"),
  ]);
  assert.match(build, /stage-mojang-assets\.mjs/);
  assert.match(build, /stageMojangAssets\(/);
  assert.match(build, /assetIndexSha1/);
  assert.match(build, /dryRun/);
  assert.match(build, /title assets:/);
  assert.match(installer, /tools\/build\.mjs/);
  assert.match(installer, /\.\.\.buildArgs, useDownload \? "--download-only" : "--dry-run"/);
  assert.match(installer, /has\("build"\) \|\| has\("run"\)/);
});

test("clean build defaults to a verified official Mojang CDN cache", async () => {
  const [build, installer, policy] = await Promise.all([
    readFile(`${ROOT}/tools/build.mjs`, "utf8"),
    readFile(`${ROOT}/tools/mcweb-install.mjs`, "utf8"),
    readFile(`${ROOT}/tools/minecraft-input-policy.mjs`, "utf8"),
  ]);
  for (const host of [
    "piston-meta\\.mojang\\.com",
    "piston-data\\.mojang\\.com",
    "libraries\\.minecraft\\.net",
    "resources\\.download\\.minecraft\\.net",
  ]) assert.match(policy, new RegExp(host));
  assert.match(policy, /CDN_HOSTS/);
  assert.match(build, /DOWNLOAD_TIMEOUT_MS/);
  assert.match(build, /MAX_OBJECT_BYTES/);
  assert.match(build, /MAX_JSON_BYTES/);
  assert.match(build, /maxBytes/);
  assert.match(build, /AbortController/);
  assert.match(build, /atomicDownload/);
  assert.match(build, /\.part-/);
  assert.match(build, /expectedSize\(/);
  assert.match(build, /EXPECTED_CLIENT_SHA256/);
  assert.match(build, /!explicit && !has\("local-only"\)/);
  assert.match(build, /DEFAULT_MINECRAFT_CACHE/);
  assert.match(build, /const offline = has\("offline"\)/);
  assert.match(build, /const downloadOnly = has\("download-only"\)/);
  assert.match(build, /--download-only: inputs downloaded and verified/);
  assert.match(installer, /useDownload = has\("download"\) \|\| downloadOnly \|\| \(!mcDir && !has\("local-only"\)\)/);
  assert.match(installer, /join\(HOME, "minecraft"\)/);
  assert.match(installer, /buildArgs\.push\("--download", "--cache-dir", cacheDir\)/);
});

test("direct and wrapper input modes reject conflicting flags before toolchain/network work", async () => {
  const build = `${ROOT}/tools/build.mjs`;
  const installer = `${ROOT}/tools/mcweb-install.mjs`;
  const directCases = [
    ["--download", "--mc-dir", "/private/tmp/not-used"],
    ["--download", "--local-only"],
    ["--download-only", "--build"],
    ["--dry-run", "--build"],
    ["--offline", "--local-only"],
  ];
  for (const args of directCases) {
    await assert.rejects(execFileAsync(process.execPath, [build, ...args], {
      cwd: ROOT, timeout: 3000, maxBuffer: 1024 * 1024,
    }), /--download|--download-only|--dry-run|--offline/);
  }
  const wrapperCases = [
    ["--download", "--mc-dir", "/private/tmp/not-used"],
    ["--download-only", "--build"],
    ["--dry-run", "--download"],
    ["--verify", "--build"],
    ["--offline", "--local-only"],
  ];
  for (const args of wrapperCases) {
    await assert.rejects(execFileAsync(process.execPath, [installer, ...args], {
      cwd: ROOT, timeout: 3000, maxBuffer: 1024 * 1024,
    }), /--download|--download-only|--dry-run|--verify|--offline/);
  }
});

test("empty CDN dry-run is write-free and offline mode fails closed without fetching", async (t) => {
  const build = `${ROOT}/tools/build.mjs`;
  const dryCache = await tempDir(t);
  const dry = await execFileAsync(process.execPath, [build, "--download", "--no-audio", "--dry-run",
    "--cache-dir", dryCache], { cwd: ROOT, timeout: 5000, maxBuffer: 1024 * 1024 });
  assert.match(dry.stdout, /would download verified Minecraft 26\.2/);
  assert.deepEqual(await readdir(dryCache), []);

  const offlineCache = await tempDir(t);
  await assert.rejects(execFileAsync(process.execPath, [build, "--download", "--download-only", "--offline",
    "--no-audio", "--cache-dir", offlineCache], {
    cwd: ROOT, timeout: 5000, maxBuffer: 1024 * 1024,
  }), /cached download record is invalid|offline cache artifact/);
  assert.deepEqual(await readdir(offlineCache), [], "offline failure must not leave cache or partial files");

  const offlineDryRunCache = await tempDir(t);
  await assert.rejects(execFileAsync(process.execPath, [build, "--download", "--offline", "--dry-run",
    "--no-audio", "--cache-dir", offlineDryRunCache], {
    cwd: ROOT, timeout: 5000, maxBuffer: 1024 * 1024,
  }), /cached download record is invalid|offline cache artifact/);
  assert.deepEqual(await readdir(offlineDryRunCache), [],
    "offline dry-run must fail closed without network, locks, or partial files");
});

test("CDN cache lock recovers only a stale lock owned by a dead process", async (t) => {
  const build = `${ROOT}/tools/build.mjs`;
  const cache = await tempDir(t);
  const lock = join(cache, ".mcweb-download.lock");
  await mkdir(lock);
  await writeFile(join(lock, "owner.json"), JSON.stringify({
    pid: 99999999, startedAt: Date.now() - 11 * 60 * 1000, token: "stale-test",
  }));
  await assert.rejects(execFileAsync(process.execPath, [build, "--download", "--download-only", "--offline",
    "--no-audio", "--cache-dir", cache], {
    cwd: ROOT, timeout: 5000, maxBuffer: 1024 * 1024,
  }), /cached download record is invalid|offline cache artifact/);
  assert.deepEqual(await readdir(cache), [], "stale lock must be removed after the failed offline gate");
});

test("developer-tool URLs are exact HTTPS vendor paths and reject unsafe variants", () => {
  assert.equal(toolUrl(NODE_ARCHIVE_URL, "node"), NODE_ARCHIVE_URL);
  assert.equal(toolUrl(GRAAL_ARCHIVE_URL, "graal"), GRAAL_ARCHIVE_URL);
  assert.throws(() => toolUrl(GRAAL_ARCHIVE_URL.replace("25i2/archive", "25i1/archive"), "graal"), /pinned vendor path/);
  assert.throws(() => toolUrl(NODE_ARCHIVE_URL.replace("https://", "http://"), "node"), /HTTPS/);
  assert.throws(() => toolUrl(NODE_ARCHIVE_URL.replace("nodejs.org/", "nodejs.org:8443/"), "node"), /HTTPS/);
  assert.throws(() => toolUrl(NODE_ARCHIVE_URL.replace("https://", "https://user:pass@"), "node"), /userinfo/);
  assert.throws(() => toolUrl(`${NODE_ARCHIVE_URL}#fragment`, "node"), /userinfo\/fragment/);
  assert.throws(() => toolUrl("https://nodejs.org/dist/v24.19.0/other.tar.gz", "node"), /pinned vendor path/);
});

test("Oracle Graal regional redirects are exact object paths for the selected archive", () => {
  const arm64 = GRAAL_OBJECT_PATH("redirect-id", GRAAL_ARM64_FILE);
  const amd64 = GRAAL_OBJECT_PATH("redirect-id", GRAAL_AMD64_FILE);
  assert.equal(toolUrl(arm64, "graal", { redirect: true, expectedFile: GRAAL_ARM64_FILE }), arm64);
  assert.equal(toolUrl(amd64, "graal", { redirect: true, expectedFile: GRAAL_AMD64_FILE }), amd64);
  assert.throws(() => toolUrl(arm64, "graal", { redirect: true }), /object-storage path/);
  for (const bad of [
    arm64.replace("objectstorage.uk-london-1.oraclecloud.com", "objectstorage.us-phoenix-1.oraclecloud.com"),
    arm64.replace("/n/lr0crfzcb4ml/", "/n/othernamespace/"),
    arm64.replace("/b/gds-artifacts/", "/b/otherbucket/"),
    arm64.replace("/o/object-id/", "/o/object-id/extra/"),
    arm64.replace(GRAAL_ARM64_FILE, GRAAL_AMD64_FILE),
    `${arm64}?download=1`,
    arm64.replace("/p/redirect-id/", "/p/redirect-id/extra/"),
    arm64.replace("/p/redirect-id/", "/p/id%2Fwith-slash/"),
    arm64.replace("objectstorage.uk-london-1.oraclecloud.com", "user:pass@objectstorage.uk-london-1.oraclecloud.com"),
  ]) assert.throws(() => toolUrl(bad, "graal", { redirect: true, expectedFile: GRAAL_ARM64_FILE }));
});

test("developer-tool download retries transient responses, commits atomically, and reuses a valid cache", async (t) => {
  const directory = await tempDir(t);
  const destination = join(directory, "node.tar.gz");
  const body = Buffer.from("synthetic developer archive");
  const expectedSha256 = digest(body);
  let calls = 0;
  globalThis.fetch = async (url, init) => {
    calls++;
    assert.equal(url, NODE_ARCHIVE_URL);
    assert.equal(init.redirect, "manual");
    if (calls < 3) return new Response("retry", { status: 503 });
    return new Response(body, { status: 200 });
  };
  try {
    assert.equal(await download(NODE_ARCHIVE_URL, destination, {
      kind: "node", expectedSha256, expectedBytes: body.length, retryDelayMs: 0,
    }), true);
    assert.deepEqual(await readFile(destination), body);
    assert.deepEqual(await readdir(directory), ["node.tar.gz"]);
    assert.equal(await download(NODE_ARCHIVE_URL, destination, {
      kind: "node", expectedSha256, expectedBytes: body.length, retryDelayMs: 0,
    }), false);
    assert.equal(calls, 3, "a valid cache must not be fetched again");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("developer-tool redirects are manual, bounded, and host-allowlisted per hop", async (t) => {
  const directory = await tempDir(t);
  const body = Buffer.from("redirected archive");
  const expectedSha256 = digest(body);
  const releaseUrl = "https://release-assets.githubusercontent.com/github-production-release-asset/org/repo/asset?sig=test";
  const objectUrl = "https://objects.githubusercontent.com/github-production-release-asset/org/repo/asset?sig=test";
  let calls = 0;
  globalThis.fetch = async (url, init) => {
    calls++;
    assert.equal(init.redirect, "manual");
    if (calls === 1) return new Response(null, { status: 302, headers: { location: releaseUrl } });
    if (calls === 2) return new Response(null, { status: 307, headers: { location: objectUrl } });
    return new Response(body, { status: 200 });
  };
  try {
    await download(BINARYEN_ARCHIVE_URL, join(directory, "binaryen.tar.gz"), {
      kind: "binaryen", expectedSha256, expectedBytes: body.length, retryDelayMs: 0,
    });
    assert.equal(calls, 3);
  } finally {
    globalThis.fetch = realFetch;
  }

  calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return new Response(null, { status: 302, headers: { location: "https://evil.example.invalid/archive" } });
  };
  try {
    await assert.rejects(download(BINARYEN_ARCHIVE_URL, join(directory, "wrong-host.tar.gz"), {
      kind: "binaryen", expectedSha256, retryDelayMs: 0,
    }), /approved vendor hosts/);
    assert.equal(calls, 1, "an unapproved redirect must not be retried or contacted");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("developer-tool timeout, checksum, oversize, and partial-stream failures clean temporary files", async (t) => {
  const directory = await tempDir(t);
  const body = Buffer.from("correct bytes");
  const expectedSha256 = digest(body);
  const destination = join(directory, "failure.tar.gz");
  let timeoutCalls = 0;
  globalThis.fetch = async (url, { signal }) => new Promise((resolveResponse, rejectResponse) => {
    timeoutCalls++;
    signal.addEventListener("abort", () => rejectResponse(Object.assign(new Error("aborted"), { name: "AbortError" })), { once: true });
  });
  try {
    await assert.rejects(download(NODE_ARCHIVE_URL, destination, {
      kind: "node", expectedSha256, attempts: 2, timeoutMs: 5, retryDelayMs: 0,
    }), /2 attempts/);
    assert.equal(timeoutCalls, 2);
    assert.deepEqual(await readdir(directory), []);
  } finally {
    globalThis.fetch = realFetch;
  }

  globalThis.fetch = async () => new Response(Buffer.from("wrong bytes"), { status: 200 });
  try {
    await assert.rejects(download(NODE_ARCHIVE_URL, destination, {
      kind: "node", expectedSha256, attempts: 1, retryDelayMs: 0,
    }), /checksum mismatch/);
    assert.deepEqual(await readdir(directory), []);
  } finally {
    globalThis.fetch = realFetch;
  }

  globalThis.fetch = async () => new Response(Buffer.from("12345"), { status: 200 });
  try {
    await assert.rejects(download(NODE_ARCHIVE_URL, destination, {
      kind: "node", expectedSha256, expectedBytes: 4, attempts: 1, retryDelayMs: 0,
    }), /response exceeds its allowed size/);
    assert.deepEqual(await readdir(directory), []);
  } finally {
    globalThis.fetch = realFetch;
  }

  const partialStream = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode("partial"));
      controller.error(new Error("synthetic connection reset"));
    },
  });
  globalThis.fetch = async () => new Response(partialStream, { status: 200 });
  try {
    await assert.rejects(download(NODE_ARCHIVE_URL, destination, {
      kind: "node", expectedSha256, attempts: 1, retryDelayMs: 0,
    }), /failed after 1 attempts/);
    assert.deepEqual(await readdir(directory), []);
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("developer-tool checksum sidecars are bounded and retried", async () => {
  const expected = "a".repeat(64);
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    if (calls === 1) return new Response("busy", { status: 429 });
    return new Response(`${expected}  binaryen.tar.gz\n`, { status: 200 });
  };
  try {
    assert.equal(await sidecarChecksum(BINARYEN_SIDECAR_URL, { attempts: 2, retryDelayMs: 0 }), expected);
    assert.equal(calls, 2);
  } finally {
    globalThis.fetch = realFetch;
  }
});
