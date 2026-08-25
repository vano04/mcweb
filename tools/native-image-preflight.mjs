import { spawn } from "node:child_process";
import { stat } from "node:fs/promises";
import { join } from "node:path";

export const WINDOWS_STATUS_DLL_NOT_FOUND = -1073741515;
export const WINDOWS_STATUS_DLL_NOT_FOUND_UNSIGNED = 0xC0000135;

const exists = async (path) => !!(await stat(path).catch(() => null));

export async function findNativeImage(home, platformName = process.platform) {
  const candidates = platformName === "win32"
    ? [
        join(home, "lib", "svm", "bin", "native-image.exe"),
        join(home, "bin", "native-image.exe"),
        join(home, "bin", "native-image.cmd"),
        join(home, "bin", "native-image.bat"),
      ]
    : [join(home, "bin", "native-image")];
  for (const candidate of candidates) {
    if (await exists(candidate)) return candidate;
  }
  return null;
}

export function spawnCommand(command, args, {
  platformName = process.platform,
  comSpec = process.env.ComSpec || "cmd.exe",
} = {}) {
  if (platformName === "win32" && /\.(cmd|bat)$/i.test(command)) {
    return { command: comSpec, args: ["/c", command, ...args] };
  }
  return { command, args };
}

export function classifyNativeImageExit({ platformName = process.platform, code, signal } = {}) {
  const dllNotFound = platformName === "win32"
    && (code === WINDOWS_STATUS_DLL_NOT_FOUND
      || code === WINDOWS_STATUS_DLL_NOT_FOUND_UNSIGNED);
  if (code === 0) return { kind: "ok", code, signal };
  if (dllNotFound) return { kind: "windows-dll-not-found", code, signal };
  return { kind: "failed", code, signal };
}

function formatExit(code, signal) {
  if (code === WINDOWS_STATUS_DLL_NOT_FOUND || code === WINDOWS_STATUS_DLL_NOT_FOUND_UNSIGNED) {
    return `${code} (0xC0000135)`;
  }
  return signal ? `signal ${signal}` : `exit ${code}`;
}

export function nativeImageFailureMessage({ command, code, signal, stderr = "", platformName = process.platform } = {}) {
  const result = classifyNativeImageExit({ platformName, code, signal });
  const detail = stderr.trim() ? `\n  native-image output:\n${stderr.trim()}` : "";
  if (result.kind === "windows-dll-not-found") {
    return `native-image preflight failed before Gradle could start: ${command} --version exited ${formatExit(code, signal)}.\n`
      + "  Windows reported STATUS_DLL_NOT_FOUND: a DLL needed by native-image.exe could not be loaded.\n"
      + "  This is a loader/dependency failure, not a native-image Java heap or active-RAM failure.\n"
      + "  The likely dependency class is the MSVC/UCRT runtime; this status alone does not reveal the exact DLL.\n"
      + "  Re-run .\\tools\\install.ps1 --build so the verified JDK bin directory and llvm-mingw adapter are restored.\n"
      + "  If the loader still fails, install Microsoft's official current x64 Visual C++ Redistributable;\n"
      + "  llvm-mingw replaces the compiler and SDK, not DLLs imported by Oracle's native-image executable.\n"
      + "  Do not download or copy an unofficial DLL into the JDK. To reproduce the loader failure, run:\n"
      + `    & '${command}' --version\n`
      + `    where.exe vcruntime140.dll${detail}`;
  }
  return `native-image preflight failed: ${command} --version ${formatExit(code, signal)}.${detail}`;
}

export async function preflightNativeImage(home, {
  platformName = process.platform,
  env = process.env,
  cwd = process.cwd(),
} = {}) {
  const command = await findNativeImage(home, platformName);
  if (!command) throw new Error(`native-image preflight could not find a launcher under ${home}`);
  const launched = spawnCommand(command, ["--version"], {
    platformName,
    comSpec: env.ComSpec || "cmd.exe",
  });
  return new Promise((resolve, reject) => {
    const child = spawn(launched.command, launched.args, {
      cwd,
      env,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    child.stdout.on("data", (chunk) => { output += chunk; });
    child.stderr.on("data", (chunk) => { output += chunk; });
    child.on("error", (error) => reject(error));
    child.on("exit", (code, signal) => {
      const result = classifyNativeImageExit({ platformName, code, signal });
      if (result.kind === "ok") {
        resolve({ command, output: output.trim() });
        return;
      }
      reject(Object.assign(new Error(nativeImageFailureMessage({
        command,
        code,
        signal,
        stderr: output,
        platformName,
      })), { code, signal, kind: result.kind, command }));
    });
  });
}
