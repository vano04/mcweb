#!/bin/sh
# The single Unix entrypoint for the standard local build-and-run flow.
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec sh "$SCRIPT_DIR/tools/install.sh" --run "$@"
