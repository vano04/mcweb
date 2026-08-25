#!/bin/sh
# Serve the built image and integrated auth-aware Minecraft relay on loopback.
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MCWEB_HOME=${MCWEB_HOME:-${HOME:-}/.mcweb}

NODE=""
if command -v node >/dev/null 2>&1; then
  case "$(node -v 2>/dev/null || true)" in
    v2[0-9].*|v[3-9][0-9].*) NODE=$(command -v node) ;;
  esac
fi
[ -n "$NODE" ] || NODE="$MCWEB_HOME/node/bin/node"
[ -x "$NODE" ] || {
  echo "mcweb: Node 20+ is not installed; run ./build.sh first" >&2
  exit 1
}
[ -f "$SCRIPT_DIR/build/web-graal/graal/minecraft-client.js" ] \
  && [ -f "$SCRIPT_DIR/build/web-graal/graal/minecraft-client.js.wasm" ] || {
  echo "mcweb: no built image found; run ./build.sh first" >&2
  exit 1
}

export MC_WEB_PORT=${MC_WEB_PORT:-4199}
export MCWEB_DISABLE_LOCAL_BUILD=1
cd "$SCRIPT_DIR"
exec "$NODE" tools/dev-server.mjs "$@"
