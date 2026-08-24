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
  assert.match(message, /MSVC 14\.x/);
  assert.match(message, /MSVC\/UCRT runtime/);
  assert.match(message, /Windows 11 SDK/);
  assert.match(message, /dumpbin\.exe \/DEPENDENTS/);
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
