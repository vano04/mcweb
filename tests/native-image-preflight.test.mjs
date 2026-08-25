import assert from "node:assert/strict";
import test from "node:test";
import {
  WINDOWS_STATUS_DLL_NOT_FOUND,
  classifyNativeImageExit,
  nativeImageFailureMessage,
  spawnCommand,
} from "../tools/native-image-preflight.mjs";

test("Windows loader failure is classified separately from a Java OOM", () => {
  const result = classifyNativeImageExit({
    platformName: "win32",
    code: WINDOWS_STATUS_DLL_NOT_FOUND,
  });
  assert.equal(result.kind, "windows-dll-not-found");
  const message = nativeImageFailureMessage({
    platformName: "win32",
    command: "C:\\Users\\vano\\.mcweb\\toolchain\\lib\\svm\\bin\\native-image.exe",
    code: WINDOWS_STATUS_DLL_NOT_FOUND,
  });
  assert.match(message, /STATUS_DLL_NOT_FOUND/);
  assert.match(message, /MSVC\/UCRT runtime/);
  assert.match(message, /official current x64 Visual C\+\+ Redistributable/);
  assert.match(message, /llvm-mingw replaces the compiler and SDK/);
  assert.match(message, /install\.ps1 --build/);
  assert.match(message, /where\.exe vcruntime140\.dll/);
  assert.doesNotMatch(message, /OutOfMemoryError/);
});

test("batch native-image launchers are routed through the Windows command processor", () => {
  assert.deepEqual(
    spawnCommand("C:\\graal\\bin\\native-image.cmd", ["--version"], {
      platformName: "win32",
      comSpec: "C:\\Windows\\System32\\cmd.exe",
    }),
    {
      command: "C:\\Windows\\System32\\cmd.exe",
      args: ["/c", "C:\\graal\\bin\\native-image.cmd", "--version"],
    },
  );
});
