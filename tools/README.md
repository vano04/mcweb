# Local tools

This copy contains only the source needed to build and run the local MC-Web
development lane.

| Tool | Purpose |
| --- | --- |
| `dev-server.mjs` | Serves `build/web-graal` (or the source `web/` fallback), `/mcweb/*`, and the integrated gateway. |
| `mc-relay.mjs` | Target-policy WebSocket-to-TCP gateway with official Launcher online auth. |
| `profile-property-verifier.mjs` | Verifies signed Minecraft profile properties locally. |
| `build.mjs` | Downloads or validates 26.2 inputs from official Mojang CDNs (or accepts `--mc-dir`), stages title assets, then builds and packages the image. |
| `stage-mojang-assets.mjs` | Validates/reconstructs the same local title assets used automatically by `build.mjs`. |
| `install.sh` / `install.ps1` | Clean-machine bootstrap wrappers for macOS/Linux and Windows. |
| `mcweb-install.mjs` | Downloads checksum-verified developer tools, validates/downloads the 26.2 input cache, and optionally builds/runs. |
| `webimage-patch/` | Source patches used by the Gradle Web Image build. |

The repository-root `run.sh` and `run.ps1` are the only standard local
build-and-run entrypoints. The repository-root `install` and `install.ps1`
are source-only GitHub bootstraps for machines that do not already have this
directory; they install into `~/.mcweb/project` (or `MCWEB_INSTALL_DIR`) and
refuse to overwrite an unmarked user directory.

The remaining `src/webimage-patch` and `tools/webimage-patch` files are build
inputs referenced by `build.gradle`; they do not fetch or contain generated
runtime bytes. The public launcher stages only the canonical `minecraft-client`
WasmGC pair; experimental shared-memory staging helpers are not included.

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

For a clean machine, run `sh tools/install.sh --build` (or
`.\tools\install.ps1 --build` on Windows) to download and build. The wrapper's
`--dry-run` mode only inspects the platform matrix and
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
