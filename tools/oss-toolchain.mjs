#!/usr/bin/env node
// Build the Visual-Studio-shaped llvm-mingw adapter used by GraalVM on Windows.
// The verified llvm-mingw archive is installed by mcweb-install.mjs; this file
// creates only local shims and metadata and never modifies the registry.
import { spawn } from "node:child_process";
import { mkdir, stat, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const argv = process.argv.slice(2);
const flag = (name, fallback = null) => {
  const index = argv.indexOf(`--${name}`);
  return index >= 0 && argv[index + 1] ? argv[index + 1] : fallback;
};
const home = resolve(flag("home", "C:\\mcweb"));
const llvmDir = resolve(flag("llvm-dir", join(home, "llvm-mingw")));
const nodeDir = resolve(flag("node-dir", dirname(process.execPath)));
const project = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const root = join(home, "ossvc");
const toolset = "14.44.35207";
const binDir = join(root, "VC", "Tools", "MSVC", toolset, "bin", "Hostx64", "x64");
const programFiles = join(home, "oss-program-files-x86");
const vswhere = join(programFiles, "Microsoft Visual Studio", "Installer", "vswhere.exe");
const targetClang = join(llvmDir, "bin", "x86_64-w64-mingw32-clang.exe");
const cl = join(binDir, "cl.exe");
const exists = async (path) => !!(await stat(path).catch(() => null));
const die = (message) => { console.error(`oss: ${message}`); process.exit(1); };

function run(command, args, options = {}) {
  return new Promise((resolveRun, rejectRun) => {
    const child = spawn(command, args, { stdio: "inherit", ...options });
    child.on("exit", (code) => code === 0 ? resolveRun() : rejectRun(new Error(`${command} exited ${code}`)));
    child.on("error", rejectRun);
  });
}

if (process.platform !== "win32") die("the llvm-mingw adapter is only built on Windows");
if (!(await exists(targetClang))) die(`x86_64 target compiler is missing: ${targetClang}`);

await mkdir(binDir, { recursive: true });
await mkdir(join(root, "VC", "Tools", "MSVC", toolset, "include"), { recursive: true });
await mkdir(join(root, "VC", "Tools", "MSVC", toolset, "lib", "x64"), { recursive: true });
const buildDir = join(root, "VC", "Auxiliary", "Build");
await mkdir(buildDir, { recursive: true });
await writeFile(join(buildDir, "vcvarsall.bat"), [
  "@echo off",
  `set "VSINSTALLDIR=${root}\\"`,
  `set "VCINSTALLDIR=${join(root, "VC")}\\"`,
  `set "VCToolsInstallDir=${join(root, "VC", "Tools", "MSVC", toolset)}\\"`,
  `set "MCWEB_CC=${targetClang}"`,
  `set "MCWEB_CL_LOG=${join(home, "cl-calls.log")}"`,
  `set "PATH=${binDir};${join(llvmDir, "bin")};%PATH%"`,
  "exit /b 0",
  "",
].join("\r\n"), "ascii");

const sea = join(project, "tools", "build-sea-exe.mjs");
await run(process.execPath, [sea, "--node-dir", nodeDir,
  "--shim", join(project, "tools", "msvc-cl-shim.js"), "--out", cl]);
await run(process.execPath, [sea, "--node-dir", nodeDir,
  "--shim", join(project, "tools", "msvc-vswhere-shim.js"), "--out", vswhere]);

const metadata = {
  version: 1,
  kind: "llvm-mingw-msvc-shim",
  llvmDir,
  compiler: targetClang,
  msvcRoot: root,
  programFilesX86: programFiles,
  cl,
  vswhere,
  graalExtraArgs: "-H:-CheckToolchain",
};
await writeFile(join(home, "windows-toolchain.json"), JSON.stringify(metadata, null, 2) + "\n");
console.log(`oss: llvm-mingw adapter ready at ${root}`);
