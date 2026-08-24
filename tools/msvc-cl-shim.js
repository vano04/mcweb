// A `cl.exe` facade over llvm-mingw's x86_64-targeting clang driver.
//
// GraalVM's Windows native-image launcher discovers only a Visual Studio-shaped
// toolchain and invokes its compiler with MSVC flags.  This small executable
// translates the probe invocations to llvm-mingw, whose MinGW-w64 headers and
// import libraries make the build independent of Visual Studio and the SDK.
// tools/oss-toolchain.mjs packages this file as cl.exe with Node SEA.
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");

const cc = process.env.MCWEB_CC;
const log = process.env.MCWEB_CL_LOG;
const argv = process.argv.slice(2);

function note(text) {
  if (!log) return;
  try { fs.appendFileSync(log, text + "\n"); } catch { /* diagnostics cannot fail a build */ }
}

note(`\n=== cl ${argv.join(" ")}`);
if (!cc) { console.error("cl-shim: MCWEB_CC is not set"); process.exit(1); }

const args = [];
const sources = [];
let preprocess = false;
let output = null;

for (const arg of argv) {
  const flag = arg.startsWith("/") ? `-${arg.slice(1)}` : arg;
  const lower = flag.toLowerCase();

  if (lower === "-ep" || lower === "-e") { preprocess = true; continue; }
  if (lower.startsWith("-fe")) { output = arg.slice(3); continue; }
  if (lower.startsWith("-fo")) { output = arg.slice(3); continue; }
  if (/^-(wd\d+|w[0-4]|wx|nologo|zi|md|mt|mdd|mtd|o[12x]?|ehsc|tc|tp|utf-8)$/.test(lower)) continue;
  if (arg.startsWith("-I") || arg.startsWith("/I")) { args.push(`-I${arg.slice(2)}`); continue; }
  if (arg.startsWith("-D") || arg.startsWith("/D")) { args.push(`-D${arg.slice(2)}`); continue; }
  if (/\.(c|cc|cpp)$/i.test(arg)) { sources.push(arg); continue; }
  if (arg.startsWith("/")) { note(`  (dropped MSVC flag ${arg})`); continue; }
  args.push(arg);
}

const defines = [
  "-D_MSC_VER=1944",
  "-D_MSC_FULL_VER=194435211",
  "-D_WIN32=1",
  "-D_WIN64=1",
  "-D_M_X64=100",
  "-D_M_AMD64=100",
];
const ccArgs = [...defines, ...args, ...sources];
if (preprocess) ccArgs.push("-E", "-P");
else if (output) ccArgs.push("-o", output);

note(`  -> cc ${ccArgs.join(" ")}`);
const versionLine = "LLVM/clang MSVC-compatible shim Version 19.44.35228 for x64";
if (preprocess) {
  const result = spawnSync(cc, ccArgs, { encoding: "utf8" });
  if (result.stderr) process.stderr.write(result.stderr);
  const name = sources.length ? sources[0].replace(/^.*[\\/]/, "") : "";
  process.stdout.write(`${versionLine}\r\n\r\n${name}\r\n\r\n`
    + String(result.stdout || "").replace(/\r?\n/g, "\r\n"));
  note(`  exit ${result.status} (banner prepended)`);
  process.exit(result.status === null ? 1 : result.status);
}

const result = spawnSync(cc, ccArgs, { stdio: "inherit" });
note(`  exit ${result.status}`);
process.exit(result.status === null ? 1 : result.status);
