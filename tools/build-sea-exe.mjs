#!/usr/bin/env node
// Package a JavaScript shim as a Windows executable using Node SEA.
import { spawn } from "node:child_process";
import { copyFile, mkdir, mkdtemp, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";

const argv = process.argv.slice(2);
const flag = (name) => { const index = argv.indexOf(`--${name}`); return index >= 0 ? argv[index + 1] : null; };
const die = (message) => { console.error(`sea: ${message}`); process.exit(1); };
const nodeDir = resolve(flag("node-dir") || die("--node-dir is required"));
const shim = resolve(flag("shim") || die("--shim is required"));
const out = resolve(flag("out") || die("--out is required"));
const node = join(nodeDir, process.platform === "win32" ? "node.exe" : "node");
const exists = async (path) => !!(await stat(path).catch(() => null));

function run(command, args, options = {}) {
  return new Promise((resolveRun, rejectRun) => {
    const { quiet = false, ...spawnOptions } = options;
    const child = spawn(command, args, {
      stdio: quiet ? ["ignore", "pipe", "pipe"] : "inherit",
      ...spawnOptions,
    });
    let diagnostics = "";
    const capture = (chunk) => { diagnostics = `${diagnostics}${chunk}`.slice(-64 * 1024); };
    if (quiet) {
      child.stdout.on("data", capture);
      child.stderr.on("data", capture);
    }
    child.on("exit", (code) => {
      if (code === 0) resolveRun();
      else rejectRun(new Error(`${basename(command)} exited ${code}${diagnostics.trim() ? `\n${diagnostics.trim()}` : ""}`));
    });
    child.on("error", rejectRun);
  });
}

if (!(await exists(node))) die(`no node executable at ${node}`);
if (!(await exists(shim))) die(`shim source is missing: ${shim}`);
const work = await mkdtemp(join(tmpdir(), "mcweb-sea-"));
try {
  console.log(`sea: creating ${basename(out)} as a local compatibility adapter`);
  console.log(`sea: source: verified Node runtime copy plus ${basename(shim)}`);
  await copyFile(shim, join(work, "shim.js"));
  await writeFile(join(work, "sea-config.json"), JSON.stringify({
    main: "shim.js",
    output: "sea.blob",
    disableExperimentalSEAWarning: true,
  }));
  await run(node, ["--experimental-sea-config", "sea-config.json"], { cwd: work, quiet: true });
  await mkdir(dirname(out), { recursive: true });
  await copyFile(node, out);
  const separator = process.platform === "win32" ? ";" : ":";
  // Keep the injector version exact. It is needed only to add the SEA resource
  // to a copy of the already verified Node executable.
  await run(node, [
    join(nodeDir, "node_modules", "npm", "bin", "npx-cli.js"),
    "--yes", "--package=postject@1.0.0-alpha.6", "postject",
    out, "NODE_SEA_BLOB", join(work, "sea.blob"),
    "--sentinel-fuse", "NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2",
  ], {
    cwd: work,
    env: { ...process.env, PATH: `${nodeDir}${separator}${process.env.PATH || ""}` },
    quiet: true,
  });
  console.log(`sea: embedded the local adapter payload in the Node copy`);
  console.log(`sea: wrote ${out}`);
} finally {
  await rm(work, { recursive: true, force: true }).catch(() => {});
}
