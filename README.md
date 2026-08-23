# How to run

Unix/macOS/Linux, from any directory with `curl` and `tar`:

```sh
curl -fsSL https://raw.githubusercontent.com/vano04/mcweb/main/install | sh
cd "$HOME/.mcweb/project"
./run.sh
```

Windows PowerShell, as one copy-paste command. It downloads the installer to a
fresh temporary file, uses a process-only policy bypass, installs to
`%USERPROFILE%\.mcweb\project`, and starts the standard local flow. It does not
change the machine or user execution policy:

```powershell
& { $ErrorActionPreference = 'Stop'; $p = (New-TemporaryFile).FullName; try { curl.exe -fsSL --proto '=https' --tlsv1.2 --max-redirs 3 --connect-timeout 15 --max-time 300 'https://raw.githubusercontent.com/vano04/mcweb/main/install.ps1' -o $p; if ($LASTEXITCODE -ne 0) { throw 'mcweb: installer download failed' }; powershell.exe -NoProfile -ExecutionPolicy Bypass -File $p -Run; if ($LASTEXITCODE -ne 0) { throw "mcweb: install/run failed with exit code $LASTEXITCODE" } } finally { Remove-Item -LiteralPath $p -Force -ErrorAction SilentlyContinue } }
```

The two `run` scripts are the standard local flow: they validate or install
the pinned developer tools, download or validate the official 26.2 inputs,
build the local Web Image, and start the loopback server. Use `--mc-dir` to
reuse an existing official Launcher/PrismLauncher layout. On Windows ARM64,
the installer uses the native ARM64 Node release when available and
intentionally selects the pinned Windows x64 Oracle GraalVM Web Image builder,
which runs through Windows x64 emulation. Oracle does not publish a native
ARM64 Web Image archive; the installer never invents or labels one as native.

# MC-Web local development distribution

This directory is the source-only local launcher for MC-Web. It runs the
developer's own licensed Minecraft Java client in a browser; it is not a
reimplementation or a Minecraft-like engine. By default the build obtains the
published 26.2 client JAR, libraries, asset index, title assets, and sounds from
Mojang's official CDN endpoints, verifies every published digest/size, and keeps
the resulting cache under `~/.mcweb/minecraft`. A local Launcher/Prism install
can still be supplied with `--mc-dir`; generated loader/Wasm/image outputs stay
local and are never uploaded.

The launcher footer contains the one pointer to the official deployment. This
copy is for building and running the local development lane.

## Clean-machine bootstrap

Clone this directory first, then run the platform wrapper. It installs only
developer tools into `~/.mcweb` (or `MCWEB_HOME`): Node.js, Oracle GraalVM Web
Image, and Binaryen. Each archive is checked against the SHA-256 checksum
published by its official vendor. No administrator access is required.

```sh
# macOS/Linux; --dry-run is write-free even when Node is absent
sh tools/install.sh --dry-run
sh tools/install.sh --build
```

```powershell
# Windows PowerShell; --dry-run is write-free even when Node is absent
.\tools\install.ps1 --dry-run
.\tools\install.ps1 --build
```

If Node 20+ is already installed, the wrappers reuse it. Otherwise they place
the pinned portable Node release under `~/.mcweb/node`. The shared installer
then places GraalVM under `~/.mcweb/toolchain` and Binaryen under
`~/.mcweb/binaryen`, validates the tools, downloads or validates the verified
26.2 CDN cache, and builds only when `--build` is present. Use `--run` instead
of `--build` to build and start the local server, or `--verify` to validate
without compiling. `--platform-matrix` prints the supported native/emulated
host mapping.

The installer never signs in, copies account files, or handles tokens. It pulls
only public game inputs from Mojang's official HTTPS CDNs, like PrismLauncher.
Use `--mc-dir` when you prefer an already-downloaded official Launcher or
PrismLauncher layout; use `--cache-dir` to choose the local CDN cache location.

The current toolchain matrix is:

| Host | Builder | Note |
| --- | --- | --- |
| macOS arm64 | native Oracle GraalVM macOS aarch64 | supported |
| macOS x64 | — | receive-only: Oracle 25i2 has no x64 Web Image archive; build elsewhere |
| Linux x64 / arm64 | native Oracle GraalVM | supported |
| Windows x64 | native Oracle GraalVM | supported |
| Windows arm64 | Windows x64 Oracle GraalVM | supported through Windows x64 emulation; not native ARM64 |

The archive URLs and locked checksum pins are kept in
`tools/mcweb-install.mjs`: Node.js `v24.19.0`, Oracle GraalVM `25.2.4`
(`25i2`, the public Web Image baseline is 25.1+), and Binaryen `131`. Oracle's
25i2 archive is based on JDK `25.0.4`; that number in the archive filename is
not the GraalVM release. The installer fails closed when a platform has no
supported Oracle builder; it does not pretend a plain OpenJDK is equivalent.

The pinned Oracle archive filenames and SHA-256 values are:

| Platform | Archive | SHA-256 |
| --- | --- | --- |
| macOS arm64 | `graalvm-jdk-25i2-25.0.4_macos-aarch64_bin.tar.gz` | `1b5937aa3076707459cfc815a1699761f943d2d1c9cbe03388e36d5e47eb27c3` |
| Linux x64 | `graalvm-jdk-25i2-25.0.4_linux-x64_bin.tar.gz` | `7100d99cbfec68b03b669cc60c7e8592bbcda1732e8eaebc460fe0b75849a894` |
| Linux arm64 | `graalvm-jdk-25i2-25.0.4_linux-aarch64_bin.tar.gz` | `0bc65f9c36ae77bd83aad46a2b4de4b0ec97da1b4ac83fedb59e19f868873dee` |
| Windows x64 | `graalvm-jdk-25i2-25.0.4_windows-x64_bin.zip` | `2b41fffc94c4c7795bce0fdde8847ab1c894903cb20779aedb6ca8628aa9983a` |

Binaryen 131 uses its official GitHub `.sha256` sidecars. The macOS x64
archive (receive-only on this project) is pinned to
`d209fadd8a894bdaf3bd3612a23c32a0af184d2f4a979b8c789e6e4f6a4de883`.

## Build requirements

You need:

- Network access to Mojang's official HTTPS CDNs for the 26.2 client JAR,
  libraries, asset index, title objects, and (unless `--no-audio`) sound
  objects. The build checks each published SHA-1/size and the pinned client
  SHA-256 `40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290`.
- Oracle GraalVM Web Image 25.1 or newer. The clean-machine bootstrap pins
  Oracle GraalVM 25.2.4 (25i2); set `GRAALVM_HOME` explicitly when a manually
  installed JDK is elsewhere. It must be Oracle GraalVM, not a stock OpenJDK.
- Java 25 available through `JAVA_HOME` (normally the same GraalVM directory).
- Node.js 20 or newer and a working Gradle wrapper (`./gradlew`).
- Binaryen 131's `wasm-as` executable on `PATH`, or under
  `~/.mcweb/binaryen/bin` / `~/tools/binaryen/bin`.
- An optional local official Launcher/PrismLauncher installation with the
  matching 26.2 libraries and asset store when using `--mc-dir` instead of CDN
  download.
- A WebGPU-capable browser for running the result.

Plan for at least **10 GB of RAM available to the build**. A machine with 16 GB
or more physical RAM is more comfortable; close memory-heavy applications so
the native-image builder does not fall into swap. A full image build takes about
9 minutes after inputs are present, and the first asset/library staging pass can
take longer.

## Build the Wasm binary

Run these commands from this directory. The default command downloads into the
ignored user cache `~/.mcweb/minecraft`; no Minecraft-owned file is written into
the source checkout.

```sh
# Optional when the helper cannot find your Oracle GraalVM automatically.
# The bootstrap writes this path to ~/.mcweb/toolchain.env. For a manual
# install, use the JDK's Contents/Home on macOS or the JDK directory on Linux.
export GRAALVM_HOME="$HOME/.mcweb/toolchain/Contents/Home"
export JAVA_HOME="$GRAALVM_HOME"
"$GRAALVM_HOME/bin/java" -version
wasm-as --version

# Download and hash-check official 26.2 inputs, stage title/panorama files into
# ignored local paths, then run the GraalVM Web Image build. --out is local.
node tools/build.mjs --out dist/build

# Optional local-launcher fallback or offline reuse of a verified CDN cache.
node tools/build.mjs --mc-dir "$HOME/Library/Application Support/minecraft" --out dist/build
node tools/build.mjs --download --cache-dir "$HOME/.mcweb/minecraft" --offline --out dist/build
```

`tools/build.mjs` checks the Oracle Web Image API, `svm.jar`, `svm-wasm.jar`,
and `native-image` before it stages libraries or starts Gradle. It passes the
same selected home as `GRAALVM_HOME`, `JAVA_HOME`, and `-PgraalVmHome`. If
auto-detection does not find it, pass `--graalvm-home /path/to/Contents/Home`;
the helper never downloads a JDK. A manually selected, project-compatible
Oracle 25.0.4 development install may be accepted for legacy reproduction,
but the shareable/bootstrap requirement remains Web Image 25.1+.

`tools/build.mjs` performs the complete build. It validates the asset index and
all seven panorama objects, reconstructs the two JAR-backed title textures and
panorama files under the ignored staging paths, stages the local classpath,
links the verified 26.2 client JAR at the build's ignored project-root path, and
runs `./gradlew buildGraalWeb` with
`dev.mcweb.graal.BrowserMinecraftMain`, then packages the browser pair. The
important outputs are:

```text
build/web-graal/graal/minecraft-client.js
build/web-graal/graal/minecraft-client.js.wasm
build/web-graal/mcweb-audio/                 # present when audio was staged
dist/build/graal/minecraft-client.js
dist/build/graal/minecraft-client.js.wasm
dist/build/build-manifest.json
```

The `.wasm` file in `build/web-graal/graal/` is the file served by the local
launcher. `dist/build/build-manifest.json` records the input JAR hash and the
output Wasm hash for your local build. Use `node tools/build.mjs --download
--no-audio --dry-run` first to print the CDN plan without network downloads or
writes. Use `node tools/build.mjs --download --no-audio --download-only` for a
real input-only gate; it populates the verified cache and stops before Gradle.
With `--offline`, only a previously committed verified cache is accepted. A
local `--mc-dir ... --dry-run` remains a no-download validation path.
The standalone `node tools/stage-mojang-assets.mjs` command remains available
for explicitly reconstructing the same ignored staging set.

The resolver accepts only the official launcher's vanilla
`versions/26.2/26.2.json` layout. If your launcher data directory is elsewhere,
pass that vanilla root explicitly with `--mc-dir`; the JAR must still be the
exact 26.2 client listed above.

The build script supports `--no-audio` when you only need a silent runtime,
`--cache-dir` to select the local cache, `--offline` to prohibit network use,
and `--local-only` to require a detected launcher installation. `--download`
and `--download-only` are CDN modes and cannot be combined with `--mc-dir`;
the local-launcher path is a separate mode. When no `--mc-dir` or
`--local-only` is supplied, CDN download is the default. The downloader never
sends account credentials and never uploads the resulting cache or generated
image.

## Run locally

After the build finishes, start the original one-process Node command from this
directory:

```sh
MC_WEB_PORT=4199 node tools/dev-server.mjs
```

Open <http://127.0.0.1:4199/>. The same process serves `build/web-graal`, the
launcher shell, the authenticated `/mcweb/*` routes, and the WebSocket-to-TCP
Minecraft gateway. It binds to loopback (`127.0.0.1`) by default; do not expose
this process to a network you do not control.

The launcher reads a supported local account store, verifies its active
Microsoft profile, unexpired session, ownership, and live profile with
Minecraft Services, then keeps the access token in the Node process. When the
file is not at the normal location, the page can send one player-selected copy
to the same loopback Node process at `/mcweb/auth/launcher-accounts`; the raw
document is validated in memory and never written, returned to the page, or
sent to a public host. It never sends that token to a public page. Online mode
remains authenticated: the gateway performs Mojang session join, RSA/AES login
encryption, compression framing, and signed profile-property verification. The
file is autodetected at the platform's normal locations. The official Launcher
path is tried first; if it is absent or unusable, macOS also tries the
PrismLauncher path. Use
`MCWEB_LAUNCHER_ACCOUNTS=/absolute/path/to/account-file.json` for a configured
official Launcher or PrismLauncher path; an explicit override is fail-closed
and never falls back to another file.

### Find a local Launcher account file

This self-hosted build accepts exactly these two formats:

- **Official Minecraft Launcher:** the top-level object has an `accounts`
  object map and a nonempty `activeAccountLocalId` that names an account in that
  map. The selected account must be a
  Microsoft/Xbox account with an unexpired string `accessToken` and a valid
  `minecraftProfile` containing the UUID and name.
- **PrismLauncher:** the top-level object has a numeric `formatVersion` and an
  `accounts` array. Exactly one account must have `active: true`, `type: "MSA"`,
  an unexpired numeric Unix-seconds `ygg.exp`, a nonempty `ygg.token`, and a
  valid `profile` containing the UUID and name. Prism's refresh-token fields
  are not used.

The browser file picker accepts either document. Choose the file under **Use a
local Launcher JSON file**, then wait for the local Node process to report live
entitlement/profile validation. The selected file is sent only to the loopback
Node process. It is never uploaded to a hosted service, written to disk by
MC-Web, returned to the browser, or placed in browser storage. Only the
validated profile name, UUID, and provider label are safe to display. Do not
paste account-file contents into a public page or copy a token into a command.

- **Windows:** press **Win+R**, enter `%APPDATA%\.minecraft`, and press Enter.
  The file is `%APPDATA%\.minecraft\launcher_accounts.json`.
- **macOS:** in Finder choose **Go → Go to Folder…**, enter
  `~/Library/Application Support/minecraft`, and open
  `~/Library/Application Support/minecraft/launcher_accounts.json`.
  If the official file has no reusable `accessToken`, open the PrismLauncher
  alternative at `~/Library/Application Support/PrismLauncher/accounts.json`.
- **Linux:** open `~/.minecraft` in your file manager (or use `Ctrl+L`), then
  use `~/.minecraft/launcher_accounts.json`.

Some current official Launcher files omit a reusable `accessToken` even after
the game has been launched. In that case, autodiscovery falls through to the
PrismLauncher file on macOS; otherwise use that file explicitly or sign in
again with the official Launcher. For a nonstandard location, set
`MCWEB_LAUNCHER_ACCOUNTS=/absolute/path/to/account-file.json` before starting
Node. No other account format is accepted. If a replacement upload is rejected,
the previous in-memory session is cleared and must be validated again.

## Minecraft server target policy

The default `MC_RELAY_ALLOW=*` policy allows all syntactically valid public
Minecraft `host:port` targets. This is still a local capability: the gateway
requires a same-origin browser WebSocket, the Node process is loopback-bound by
default, and the official Microsoft/Minecraft entitlement and online-session
checks remain in force.

To restrict the gateway to an exact comma-separated list, set the variable
before starting Node:

```sh
MC_RELAY_ALLOW=play.example.net:25565,lan.example.net:25565 \
  MC_WEB_PORT=4199 node tools/dev-server.mjs
```

Each entry must be one exact `host:port` target. IPv6 targets use brackets, for
example `MC_RELAY_ALLOW='[2001:db8::20]:25565'`. A malformed list, a mixed `*`
plus exact list, an invalid host, or an invalid port fails closed and allows no
targets. The page and gateway both enforce the same policy.

Under `*`, literal loopback, private, link-local, carrier-grade NAT,
documentation, multicast, and cloud metadata destinations are rejected. DNS
names resolving to any unsafe address are rejected too. If you intentionally
need a private server, explicitly list its exact host and port in
`MC_RELAY_ALLOW`; this is the documented opt-in boundary. Exact allowlisting
does not remove the loopback bind or same-origin browser requirement.

The browser cannot open raw TCP itself, so all server traffic goes through this
same local gateway. It does not use a hosted relay, SRV redirect, arbitrary URL
override, or unauthenticated fallback.

## Troubleshooting

- `this jar is not the 26.2 client`: check the SHA-256 output and pass the
  matching official Launcher installation. `--allow-unknown-jar` is an unsupported
  experiment because the transforms are version-specific.
- `no Minecraft 26.2 installation found`: check network access to the official
  Mojang CDNs, or pass `--mc-dir` for a local Launcher/Prism cache. `--offline`
  requires a previously committed `~/.mcweb/minecraft/.mcweb-download.json`.
- `library ... is missing a valid SHA-1/size`: the published manifest is
  incomplete or changed; the downloader fails closed instead of accepting an
  unverified classpath.
- `no supported Oracle GraalVM Web Image JDK found`: install Oracle GraalVM
  25.1+ (the bootstrap pins 25.2.4/25i2), then set `GRAALVM_HOME` and
  `JAVA_HOME` to its JDK home, or pass `--graalvm-home`. The build helper does
  not download a JDK. For a separate `wasm-as not found` error, install/put
  Binaryen's `wasm-as` on `PATH`.
- Native-image runs out of memory: make at least 10 GB available, close other
  applications, or lower the builder settings with Gradle properties such as
  `-PgraalBuilderMemoryGb=8 -PgraalParallelism=4` before retrying.
- The launcher reports missing artifacts: the build must finish before Node is
  started; confirm `build/web-graal/graal/minecraft-client.js.wasm` exists.
- A server is refused: inspect `MC_RELAY_ALLOW`, use an exact `host:port` entry
  for a private destination, and remember that Minecraft server addresses are
  not arbitrary URLs or SRV records.

## Sharing and licensing boundary

Share the source files in this directory, build instructions, and your own
changes. Do **not** commit or redistribute `minecraft-26.2-client.jar`, launcher
libraries, account files/tokens, staged textures, sounds, generated JavaScript,
the `.wasm` binary, or the packaged `dist/build` output. These are generated or
Mojang-owned inputs derived from the recipient's own installation. `.gitignore`
excludes the local JAR, staged assets, build output, and packaged runtime. See
[NOTICE.md](NOTICE.md) and [LICENSE](LICENSE) for the applicable terms.

## Layout

| Path | Purpose |
| --- | --- |
| `src/graal/java` | Browser platform seams: WebGPU, input, audio, storage, networking, and JAR shadows |
| `src/feature` | GraalVM substitutions for browser-incompatible JDK internals |
| `src/webimage-patch` | Web Image code-generation patches |
| `src/main`, `src/drain` | Build-time probe and helper sources |
| `web/` | Local launcher shell, workers, host scripts, and Service Worker |
| `tools/build.mjs` | Local-install Wasm build and package helper |
| `tools/dev-server.mjs` | Static server plus the local auth-aware gateway |
| `tools/mc-relay.mjs` | Target-policy WebSocket-to-TCP implementation used by the server |
| `build.gradle` | Image build and exact-count browser transforms |

MC-Web's browser platform layer is GPLv3; Minecraft and the GraalVM-derived
patch sources retain their own terms. See [LICENSE](LICENSE) and
[NOTICE.md](NOTICE.md).
