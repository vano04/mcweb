#!/usr/bin/env node
// Builds the Web Image builder patch: compiles the MC-Web helper classes into the
// builder module and rewrites the upstream methods that block `@JS` (and, later,
// threads) on the WasmLM backend.
//
// Output: build/webimage-patch/classes, passed to native-image as
//   -J--patch-module=org.graalvm.extraimage.builder=<that dir>
//
// A Node port of the original build.sh. The shell version is the one step of the
// whole pipeline that needed bash, which made the image unbuildable on Windows --
// everything else after the Node bootstrap is already Node. Behaviour is
// unchanged on macOS and Linux; the differences are all path separators.
//
//   node tools/webimage-patch/build.mjs [outputDir]
import { spawn } from "node:child_process";
import { mkdir, readdir, rm, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { delimiter, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const WIN = process.platform === "win32";
const exe = (name) => (WIN ? `${name}.exe` : name);

const REPO = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const outDir = resolve(process.argv[2] || join(REPO, "build", "webimage-patch", "classes"));
const svmPatchDir = join(REPO, "build", "webimage-patch", "svm-classes");
const workDir = join(REPO, "build", "webimage-patch", "tool");

const die = (m) => { console.error(m); process.exit(1); };
const exists = async (p) => !!(await stat(p).catch(() => null));

const graalHome = process.env.GRAALVM_HOME
  || die("set GRAALVM_HOME to the Oracle GraalVM used for the image build");

function run(cmd, args) {
  return new Promise((ok, no) => {
    const child = spawn(cmd, args, { stdio: "inherit", cwd: REPO });
    child.on("exit", (code) => (code === 0 ? ok() : no(new Error(`${cmd} exited ${code}`))));
    child.on("error", no);
  });
}

/** Every file under `dir` matching `test`, recursively. */
async function walk(dir, test, found = []) {
  for (const entry of await readdir(dir, { withFileTypes: true }).catch(() => [])) {
    const path = join(dir, entry.name);
    // Skip macOS AppleDouble sidecars: they are named like the file they shadow,
    // so "._Foo.java" passes a .java test and javac chokes on the binary content.
    if (entry.name.startsWith("._")) continue;
    if (entry.isDirectory()) await walk(path, test, found);
    else if (test(entry.name)) found.push(path);
  }
  return found;
}

const builderDir = join(graalHome, "lib", "svm", "tools", "svm-wasm", "builder");
const svmWasmJar = join(builderDir, "svm-wasm.jar");
const javac = join(graalHome, "bin", exe("javac"));
const java = join(graalHome, "bin", exe("java"));
if (!(await exists(svmWasmJar)) || !(await exists(javac))) {
  die(`svm-wasm builder not found under ${graalHome}`);
}

// ASM comes from the Gradle cache the root build already populates.
const asmRoot = join(homedir(), ".gradle", "caches", "modules-2", "files-2.1", "org.ow2.asm");
const asmJars = await walk(asmRoot, (n) => /^asm(-tree)?-9.*\.jar$/.test(n));
if (asmJars.length === 0) {
  die("ASM not in the Gradle cache; run ./gradlew help once to populate it");
}
const asmClasspath = asmJars.join(delimiter);

for (const dir of [outDir, svmPatchDir, workDir]) {
  await rm(dir, { recursive: true, force: true });
  await mkdir(dir, { recursive: true });
}

// 1. The patcher itself.
await run(javac, ["-nowarn", "-cp", asmClasspath, "-d", workDir,
  join(REPO, "tools", "webimage-patch", "McWebImagePatcher.java")]);

// 2. Rewrite the upstream builder classes (exact-count asserted inside). The extra
//    two arguments rewrite the runtime class-initialization publication in svm.jar
//    (module org.graalvm.nativeimage.builder) into a second patch-module dir.
await run(java, ["-cp", [workDir, asmClasspath].join(delimiter), "McWebImagePatcher",
  svmWasmJar, outDir, join(graalHome, "lib", "svm", "builder", "svm.jar"), svmPatchDir]);

// 2b. Windows only: rewrite the points-to analysis so a @Delete method with more
//     than one callee reports and skips instead of aborting the build. See
//     PointstoPatcher for why. Other platforms never hit the check, so leave
//     their builder untouched.
const pointstoPatchDir = join(REPO, "build", "webimage-patch", "pointsto-classes");
if (WIN) {
  await rm(pointstoPatchDir, { recursive: true, force: true });
  await mkdir(pointstoPatchDir, { recursive: true });
  await run(javac, ["-nowarn", "-cp", asmClasspath, "-d", workDir,
    join(REPO, "tools", "webimage-patch", "PointstoPatcher.java")]);
  await run(java, ["-cp", [workDir, asmClasspath].join(delimiter), "PointstoPatcher",
    join(graalHome, "lib", "svm", "builder", "pointsto.jar"), pointstoPatchDir]);
}

// 3. Compile the helper classes *into* the builder module, so they can use its
//    package-private API and read the same modules it reads.
const sourceRoot = join(REPO, "src", "webimage-patch", "java");
const sources = await walk(sourceRoot, (n) => n.endsWith(".java"));
if (sources.length === 0) die(`no sources under ${sourceRoot}`);
const sourceList = join(workDir, "sources.txt");
await writeFile(sourceList, sources.join("\n") + "\n");

const exports = [
  "jdk.vm.ci.meta", "jdk.vm.ci.code", "jdk.vm.ci.common",
  "jdk.vm.ci.code.site", "jdk.vm.ci.runtime",
].flatMap((pkg) => ["--add-exports", `jdk.internal.vm.ci/${pkg}=org.graalvm.extraimage.builder`]);

await run(javac, [
  "-nowarn",
  "--module-path", [builderDir, join(graalHome, "lib", "svm", "builder")].join(delimiter),
  "--add-modules", "org.graalvm.extraimage.builder",
  ...exports,
  "--patch-module", `org.graalvm.extraimage.builder=${[sourceRoot, outDir].join(delimiter)}`,
  "-d", outDir,
  `@${sourceList}`,
]);

console.log(`webimage patch staged in ${outDir}`);
