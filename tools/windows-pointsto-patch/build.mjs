#!/usr/bin/env node
import { spawn } from "node:child_process";
import { mkdir, readdir, rm, stat } from "node:fs/promises";
import { homedir } from "node:os";
import { delimiter, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const REPO = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const outDir = resolve(process.argv[2] || join(REPO, "build", "windows-pointsto-patch", "classes"));
const workDir = join(REPO, "build", "windows-pointsto-patch", "tool");
const graalHome = process.env.GRAALVM_HOME;
if (!graalHome) throw new Error("GRAALVM_HOME is required");

const exists = async (path) => Boolean(await stat(path).catch(() => null));
const run = (command, args) => new Promise((resolveRun, rejectRun) => {
  const child = spawn(command, args, { cwd: REPO, stdio: "inherit" });
  child.on("error", rejectRun);
  child.on("exit", (code) => code === 0
    ? resolveRun()
    : rejectRun(new Error(`${command} exited ${code}`)));
});

async function walk(dir, test, found = []) {
  for (const entry of await readdir(dir, { withFileTypes: true }).catch(() => [])) {
    const path = join(dir, entry.name);
    if (entry.name.startsWith("._")) continue;
    if (entry.isDirectory()) await walk(path, test, found);
    else if (test(entry.name)) found.push(path);
  }
  return found;
}

const javac = join(graalHome, "bin", "javac.exe");
const java = join(graalHome, "bin", "java.exe");
const pointstoJar = join(graalHome, "lib", "svm", "builder", "pointsto.jar");
for (const path of [javac, java, pointstoJar]) {
  if (!(await exists(path))) throw new Error(`required GraalVM file not found: ${path}`);
}

const asmRoot = join(homedir(), ".gradle", "caches", "modules-2", "files-2.1", "org.ow2.asm");
const asmJars = await walk(asmRoot, (name) => /^asm(-tree)?-9.*\.jar$/.test(name));
if (asmJars.length === 0) throw new Error("ASM is not present in the Gradle cache");
const asmClasspath = asmJars.join(delimiter);

for (const dir of [outDir, workDir]) {
  await rm(dir, { recursive: true, force: true });
  await mkdir(dir, { recursive: true });
}
await run(javac, ["-nowarn", "-cp", asmClasspath, "-d", workDir,
  join(REPO, "tools", "windows-pointsto-patch", "PointstoPatcher.java")]);
await run(java, ["-cp", [workDir, asmClasspath].join(delimiter), "PointstoPatcher",
  pointstoJar, outDir]);
console.log(`Windows points-to patch staged in ${outDir}`);
