import { readFile, stat } from "node:fs/promises";
import { isAbsolute, join } from "node:path";

const exists = async (path) => !!(await stat(path).catch(() => null));

export async function loadWindowsToolchain(home, { requireFiles = true } = {}) {
  const metadataPath = join(home, "windows-toolchain.json");
  let value;
  try {
    value = JSON.parse(await readFile(metadataPath, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    throw new Error(`invalid Windows toolchain metadata at ${metadataPath}: ${error?.message || error}`);
  }
  if (value?.version !== 2 || value?.kind !== "llvm-mingw-msvc-shim") {
    throw new Error(`unsupported Windows toolchain metadata at ${metadataPath}`);
  }
  for (const key of ["llvmDir", "compiler", "msvcRoot", "programFilesX86", "cl", "vswhere"]) {
    if (typeof value[key] !== "string" || !isAbsolute(value[key])) {
      throw new Error(`Windows toolchain metadata has an invalid ${key}`);
    }
  }
  if (value.graalExtraArgs !== "-H:-CheckToolchain") {
    throw new Error("Windows toolchain metadata has unexpected native-image arguments");
  }
  if (requireFiles) {
    for (const key of ["compiler", "cl", "vswhere"]) {
      if (!(await exists(value[key]))) throw new Error(`Windows toolchain file is missing: ${value[key]}`);
    }
  }
  return value;
}

export function applyWindowsToolchain(environment, toolchain) {
  if (!toolchain) return { env: { ...environment }, graalExtraArgs: "" };
  return {
    env: {
      ...environment,
      "ProgramFiles(x86)": toolchain.programFilesX86,
      MCWEB_MSVC_ROOT: toolchain.msvcRoot,
    },
    graalExtraArgs: toolchain.graalExtraArgs,
  };
}
