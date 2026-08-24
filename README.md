# How to run MC-Web locally

MC-Web builds Minecraft 26.2 as a browser WebAssembly image. The build uses
your licensed Minecraft files and keeps them on your computer. This repository
contains the browser platform code, not Minecraft code or assets.

The local Node process serves the browser page and the Minecraft TCP relay. It
binds to `127.0.0.1` by default.

## Before you start

Use one of these build hosts:

| Host | Support |
| --- | --- |
| macOS on Apple silicon | Native build |
| macOS on Intel | Receive-only. Build on another supported computer. |
| Linux on x64 or arm64 | Native build |
| Windows on x64 | Native build |
| Windows on arm64 | Supported through Windows x64 emulation |

You also need a WebGPU browser and at least 10 GB of RAM available to the
build. A computer with 16 GB of RAM leaves more room for the operating system.
Close memory-heavy applications before you build.

The image build usually takes 9 to 15 minutes after the installer downloads
the inputs.

## Get the source

Clone the repository:

```sh
git clone https://github.com/vano04/mcweb.git
cd mcweb
```

If you do not use Git, download the
[main branch ZIP](https://github.com/vano04/mcweb/archive/refs/heads/main.zip).
Extract it, then open a terminal in `mcweb-main`.

## Build and run on macOS or Linux

Run each command from the repository root:

```sh
./build.sh
sh ./run.sh
```

The commands do separate jobs:

1. `./build.sh` installs and verifies Node.js, Oracle GraalVM, Binaryen, and
   the Minecraft 26.2 inputs under `~/.mcweb`, then builds the browser image.
   A successful build prints
   `BUILD SUCCESSFUL` and writes the output under `build/web-graal` and
   `dist/build`.
2. `./run.sh` starts the page and the integrated Minecraft relay. It does not
   rebuild the image.

Open <http://127.0.0.1:4199/> after `run.sh` prints the local URL.

## Build and run on Windows

Run these commands in Windows PowerShell 5.1 or newer:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run.ps1
```

The policy bypass applies only to each PowerShell process. It does not change
the execution policy for your user or computer.

On Windows, `build.ps1` also installs llvm-mingw under
`%USERPROFILE%\.mcweb`. MC-Web presents llvm-mingw to GraalVM through local
`cl.exe` and `vswhere.exe` adapters. You do not need Visual Studio or the
Windows SDK.

Windows arm64 uses native arm64 Node.js. Oracle does not publish an arm64 Web
Image builder for Windows, so MC-Web runs the x64 GraalVM builder through
Windows emulation.

Open <http://127.0.0.1:4199/> after `run.ps1` prints the local URL.

## Use an existing Launcher installation

By default, `build` downloads the public Minecraft 26.2 files from Mojang's
CDNs. It verifies every published hash and size. The installer never reads an
account token during this step.

To use files from the official Launcher or PrismLauncher, pass `--mc-dir` to
the build command.

On macOS:

```sh
./build.sh --mc-dir "$HOME/Library/Application Support/minecraft"
```

On Windows:

```powershell
.\build.ps1 --mc-dir "$env:APPDATA\.minecraft"
```

`--download` and `--download-only` use the CDN cache. They cannot be combined
with `--mc-dir`. When no `--mc-dir` or `--local-only` is supplied, CDN download
is the default.

Use these options when needed:

- `--no-audio` omits the sound files and builds a silent image.
- `--cache-dir PATH` changes the CDN cache directory.
- `--offline` allows only an existing verified cache.
- `--local-only` requires a detected local Launcher installation.

For direct control, run `node tools/build.mjs`. For example:

```sh
node tools/build.mjs --download --cache-dir "$HOME/.mcweb/minecraft" --offline --out dist/build
```

The build writes these files:

```text
build/web-graal/graal/minecraft-client.js
build/web-graal/graal/minecraft-client.js.wasm
build/web-graal/mcweb-audio/
dist/build/graal/minecraft-client.js
dist/build/graal/minecraft-client.js.wasm
dist/build/build-manifest.json
```

`run.sh` and `run.ps1` serve
`build/web-graal/graal/minecraft-client.js.wasm`. The build manifest records
the input JAR hash and the output Wasm hash.

## Sign in for online play

The local Node process looks for an active Microsoft account in the official
Launcher file. On macOS, it tries PrismLauncher if the official file is absent
or unusable.

MC-Web accepts these files:

- The official Launcher `launcher_accounts.json` file must name one active
  Microsoft account. That account needs an unexpired access token and a
  Minecraft profile.
- The PrismLauncher `accounts.json` file must contain exactly one active MSA
  account. That account needs an unexpired token and a Minecraft profile.

The page can send a file that you select to the loopback Node process. MC-Web
validates the file in memory. It does not write the file, return the token to
the browser, or send the token to a public host.

Find the account file for your operating system:

- On Windows, press **Win+R** and enter `%APPDATA%\.minecraft`. Select
  `%APPDATA%\.minecraft\launcher_accounts.json`.
- On macOS, open
  `~/Library/Application Support/minecraft/launcher_accounts.json`. If that
  file has no reusable token, use
  `~/Library/Application Support/PrismLauncher/accounts.json`.
- On Linux, use `~/.minecraft/launcher_accounts.json`.

For a different location, set `MCWEB_LAUNCHER_ACCOUNTS` before you run MC-Web:

```sh
MCWEB_LAUNCHER_ACCOUNTS=/absolute/path/to/launcher_accounts.json ./run.sh
```

Do not paste an account file or token into a public page or command.

## Choose which Minecraft servers the relay can reach

The default `MC_RELAY_ALLOW=*` setting permits syntactically valid public
Minecraft `host:port` targets. The relay rejects loopback, private,
link-local, multicast, carrier-grade NAT, documentation, and cloud metadata
addresses under this setting. It also rejects DNS names that resolve to those
addresses.

To allow only selected servers on macOS or Linux, set a comma-separated list:

```sh
MC_RELAY_ALLOW=play.example.net:25565,backup.example.net:25565 ./run.sh
```

On Windows PowerShell, set the same variable before `run.ps1`:

```powershell
$env:MC_RELAY_ALLOW = 'play.example.net:25565,backup.example.net:25565'
.\run.ps1
```

Use brackets around an IPv6 address:

```sh
MC_RELAY_ALLOW='[2001:db8::20]:25565' ./run.sh
```

To reach a private server, list its exact host and port. An exact entry permits
that destination, but the Node process still binds to loopback and requires a
same-origin browser connection.

The browser cannot open raw TCP. All Minecraft traffic passes through the
local Node relay. MC-Web has no hosted relay or unauthenticated fallback.

## Toolchain versions

The installer pins these tools:

- Node.js `v24.19.0`
- Oracle GraalVM `25.2.4`, release `25i2`, based on JDK `25.0.4`
- Binaryen `131`
- llvm-mingw `20260616` on Windows

The build requires Oracle GraalVM Web Image 25.1 or newer. A stock OpenJDK does
not include Web Image. `tools/mcweb-install.mjs` contains the archive names and
SHA-256 pins. Binaryen uses the checksum files from its release.

The installer verifies the Minecraft 26.2 client with this SHA-256:

```text
40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290
```

## Troubleshoot a failed build

- If the installer reports `this jar is not the 26.2 client`, use the vanilla
  26.2 Launcher files. The bytecode transforms do not support another version.
- If the installer cannot find Minecraft 26.2, check access to Mojang's CDNs.
  You can also pass `--mc-dir` to a vanilla Launcher directory.
- If `--offline` fails, run `build` without `--offline` to create a verified
  cache first.
- If the installer cannot find Oracle GraalVM Web Image, run `build` again.
  For a manual JDK, set both `GRAALVM_HOME` and `JAVA_HOME` to the Oracle JDK.
- If `wasm-as` is missing, run `build` again or put Binaryen 131 on `PATH`.
- On Windows, if native-image exits with `0xC0000135`, run `build.ps1` again.
  If it still fails, install Microsoft's current x64 Visual C++
  Redistributable. Do not copy an unofficial DLL into the JDK.
- If native-image runs out of memory, make at least 10 GB available. Close
  other applications, then retry the build.
- If `run` reports a missing image, run `build.sh` or `build.ps1` first. Confirm
  that `build/web-graal/graal/minecraft-client.js.wasm` exists.
- If the relay refuses a server, check `MC_RELAY_ALLOW`. Private servers need
  an exact `host:port` entry.

## Keep generated files private

You can share this source code and your own changes. Do not commit or
redistribute these files:

- `minecraft-26.2-client.jar`
- Launcher libraries, account files, or tokens
- Staged Minecraft textures or sounds
- Generated JavaScript or Wasm files
- The packaged `dist/build` directory

`.gitignore` excludes these local files. See [NOTICE.md](NOTICE.md) and
[LICENSE](LICENSE) for the license terms. MC-Web's browser platform code is
GPLv3. Minecraft and GraalVM keep their own terms.

## Repository layout

| Path | Contents |
| --- | --- |
| `src/graal/java` | Browser implementations for WebGPU, input, audio, storage, and networking |
| `src/feature` | GraalVM substitutions for JDK code that cannot run in the browser |
| `web/` | The page, workers, and browser host code |
| `tools/build.mjs` | The image builder and packager |
| `tools/dev-server.mjs` | The local HTTP server and authentication routes |
| `tools/mc-relay.mjs` | The WebSocket-to-TCP Minecraft relay |
| `build.gradle` | The image build and exact-count bytecode transforms |
