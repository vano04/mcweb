# Local tools

This copy contains only the source needed to build and run the local MC-Web
development lane.

| Tool | Purpose |
| --- | --- |
| `dev-server.mjs` | Serves `build/web-graal` (or the source `web/` fallback), `/mcweb/*`, and the integrated gateway. |
| `mc-relay.mjs` | Target-policy WebSocket-to-TCP gateway with official Launcher online auth. |
| `profile-property-verifier.mjs` | Verifies signed Minecraft profile properties locally. |
| `build.mjs` | Downloads or validates 26.2 inputs from official Mojang CDNs (or accepts `--mc-dir`), stages title assets, then builds and packages the image. |
| `native-image-preflight.mjs` | Starts the selected GraalVM `native-image --version` launcher and turns Windows loader failures into actionable MSVC/SDK diagnostics before Gradle. |
| `oss-toolchain.mjs` | On Windows, prepares the pinned llvm-mingw compiler behind the Visual Studio-shaped `cl.exe`/`vswhere.exe` facade GraalVM expects. No Visual Studio install or Windows SDK is required. |
| `windows-toolchain.mjs` | Validates and reapplies the local Windows adapter metadata for installer-driven and direct builds. |
| `windows-pointsto-patch/` | Windows-only, exact-counted GraalVM builder workaround for the `@Delete` multi-callee analysis failure reproduced by this image. |
| `stage-mojang-assets.mjs` | Validates/reconstructs the same local title assets used automatically by `build.mjs`. |
| `install.sh` / `install.ps1` | Clean-machine bootstrap wrappers for macOS/Linux and Windows. |
| `mcweb-install.mjs` | Downloads checksum-verified developer tools, validates/downloads the 26.2 input cache, and optionally builds/runs. |

The repository root exposes the standard three-step interface:
`install`/`install.ps1` provision and verify dependencies and inputs,
`build.sh`/`build.ps1` build the image, and `run.sh`/`run.ps1` serve the built
image with the integrated localhost relay. The run step never rebuilds.

The public launcher stages only the canonical `minecraft-client` WasmGC pair;
the Gradle build uses the standard GraalVM Web Image lane and has no alternate
builder or threading lane.

Run the server from this directory with:

```sh
MC_WEB_PORT=4199 node tools/dev-server.mjs
```

The Node process owns the page, local auth-aware routes, and the configured
Minecraft TCP destination. By default `MC_RELAY_ALLOW=*` permits public
Minecraft targets; set an exact comma-separated `host:port` list to restrict
it. The process remains loopback-bound and same-origin by default, and wildcard
mode rejects local, link-local, metadata, and other unsafe destinations unless
that exact private target is explicitly allowlisted.

For a clean machine, run `./install` followed by `./build.sh` (or
`.\install.ps1` followed by `.\build.ps1` on Windows). The lower-level
installer's `--dry-run` mode only inspects the platform matrix and
official archive/checksum URLs. The default `--build` path downloads the 26.2
client JAR, matching libraries, asset index, title objects, and sounds from the
official Mojang HTTPS CDNs into `~/.mcweb/minecraft`, with bounded retries,
strict SHA-1/size checks, and atomic cache commits. Add `--mc-dir
/path/to/.minecraft` to use an existing official Launcher or PrismLauncher
layout instead, or `--offline` to require the verified cache. `--verify` runs
the real input-only CDN gate (`--download-only`) without compiling. For
`build.mjs`, `--dry-run` is strictly no-network/no-write, while
`--download-only` downloads and verifies game inputs without compiling. No
account data is fetched.

On Windows, the same bootstrap also downloads the pinned llvm-mingw 20260616
archive for the machine's execution architecture, verifies its SHA-256, and
creates a user-local Visual Studio-shaped adapter under `%USERPROFILE%\.mcweb`.
GraalVM still compiles and runs its normal C layout probes; `cl.exe` translates
those MSVC-shaped probe arguments to the x86-64 MinGW driver. The adapter does
not modify the registry or require administrator access.
