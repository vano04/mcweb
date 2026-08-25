# Notices

MC-Web is licensed under the GNU General Public License v3.0 ([LICENSE](LICENSE)).
That grant applies to the browser platform layer in this repository. It does not
extend to Minecraft, which is not distributed here, nor to the GraalVM-derived
files noted under "Third-party components" below.

## This repository contains no Minecraft code or assets

MC-Web is a browser **platform layer**. It supplies what desktop LWJGL normally
supplies — WebGPU, input, audio, filesystem, a cooperative scheduler — so that
Mojang's own client can run unmodified in a browser. Minecraft itself is not
here, in any form:

- No Minecraft classes, decompiled or otherwise.
- No Minecraft resources, textures, sounds, or data files.
- No redistributable copy of the client JAR or of any library it depends on.

Everything Mojang-owned is an **input supplied by the person running the build**,
read from their own licensed installation at build time. The build discovers it;
it never vendors it.

## What you must supply yourself

| Input | Where the build reads it from |
| --- | --- |
| `minecraft-26.2-client.jar` | Project root. SHA-256 `40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290` |
| 26.2 library JARs | Your local launcher install, hash-verified (`./gradlew printMinecraftClasspath`) |
| Audio (`sounds.json`, `.ogg`) | Your launcher's asset store, index 32 (`stageMinecraftAudio`) |
| Title/font textures, panorama faces | Your jar and asset store (`node tools/stage-mojang-assets.mjs`) |

Run this once after cloning, before the first build:

```sh
node tools/stage-mojang-assets.mjs
```

It writes nine files that are deliberately absent from version control and
listed in `.gitignore`:

- `web/assets/minecraft.png`, `web/assets/ascii_sga.png` — extracted from the jar.
- `src/graal/resources/assets/.../panorama_{0..5,overlay}.png` — copied from the
  asset store. These are **not** usable from the jar: 26.2 ships 1×1 placeholders
  there and keeps the real 1024×1024 bytes in the content-addressed asset store.
  Building without this step gives a black title screen.

Minecraft is a trademark of Mojang Synergies AB. This project is not affiliated
with, endorsed by, or associated with Mojang or Microsoft. Using it requires
your own valid Minecraft licence.

## How Minecraft's bytecode is treated

The supplied JAR is never modified. `browserMinecraftJar` writes a separate
`build/transformed/minecraft-26.2-browser-input.jar`, strips signature metadata,
and applies named browser-compatibility transforms. Every transform matches by
owner, descriptor and surrounding bytecode and asserts an **exact count**, so a
JAR change cannot silently land a desktop call site on a browser path.

Most browser behaviour is not a transform at all: classes under `src/graal/java`
shadow their JAR counterparts by coming first on the image classpath. The
source tree and `build.gradle` are the authoritative local build description.

## Third-party components

- **GraalVM Web Image** (Oracle, GPLv2 + Classpath Exception). The local build
  invokes your own GraalVM installation. On Windows only,
  `tools/windows-pointsto-patch/PointstoPatcher.java` rewrites one
  exact-counted builder check that otherwise aborts this image during analysis;
  the resulting class is staged locally and is not distributed as a binary.
- **Binaryen** (Apache-2.0) — `wasm-as` is required by every WasmGC build and
  must be available in the developer's local toolchain.
- **llvm-mingw** (LLVM under Apache-2.0 with LLVM exceptions; MinGW-w64 under
  its upstream permissive licenses) — the checksum-pinned Windows bootstrap
  downloads it locally as the C compiler used for GraalVM's layout probes.
- **Node.js** (MIT) — used by the local staging, server, and test scripts.
- **postject** (MIT) — the Windows bootstrap invokes one exact npm version to
  embed the local `cl.exe` and `vswhere.exe` JavaScript facades into copies of
  the verified Node executable.
