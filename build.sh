#!/bin/sh
# Build and package the local browser image, installing missing dependencies.
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec sh "$SCRIPT_DIR/tools/install.sh" --build "$@"
