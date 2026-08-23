#!/bin/sh
# Bootstrap the local MC-Web toolchain on macOS or Linux.
#
# This script is intended to run from a cloned checkout:
#   sh tools/install.sh --build
#   sh tools/install.sh --mc-dir "$HOME/Library/Application Support/minecraft" --build
# It uses only POSIX sh, curl, tar, and a checksum utility that ships on the
# host. Developer tools are installed under ~/.mcweb (or MCWEB_HOME); no admin
# access is needed. Without --mc-dir, Minecraft inputs are downloaded by the
# Node installer from the official Mojang CDNs into ~/.mcweb/minecraft.
set -eu

NODE_VERSION="v24.19.0"
MCWEB_HOME="${MCWEB_HOME:-$HOME/.mcweb}"

say() { echo "mcweb: $*"; }
die() { echo "mcweb: $*" >&2; exit 1; }

OS=$(uname -s)
ARCH=$(uname -m)
case "$OS-$ARCH" in
  Darwin-arm64|Darwin-aarch64) NODE_PLAT=darwin-arm64 ;;
  Darwin-x86_64) die "Intel macOS is receive-only: Oracle GraalVM 25i2 has no macOS x64 Web Image archive. Build on Apple Silicon, Linux, or Windows." ;;
  Linux-x86_64|Linux-amd64) NODE_PLAT=linux-x64 ;;
  Linux-aarch64|Linux-arm64) NODE_PLAT=linux-arm64 ;;
  *) die "unsupported host: $OS-$ARCH" ;;
esac

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v tar >/dev/null 2>&1 || die "tar is required"
if command -v sha256sum >/dev/null 2>&1; then
  sha256() { sha256sum "$1" | { read -r sum _; echo "$sum"; }; }
elif command -v shasum >/dev/null 2>&1; then
  sha256() { shasum -a 256 "$1" | { read -r sum _; echo "$sum"; }; }
else
  die "no SHA-256 tool found (looked for sha256sum and shasum)"
fi

DOWNLOAD_ATTEMPTS=4
RETRY_DELAY_SECONDS=1
CONNECT_TIMEOUT_SECONDS=15
REQUEST_TIMEOUT_SECONDS=120
MAX_NODE_BYTES=536870912
MAX_TEXT_BYTES=1048576
MAX_REDIRECTS=3

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
INSTALLER="$SCRIPT_DIR/mcweb-install.mjs"
[ -f "$INSTALLER" ] || die "run this script from the cloned public/mcweb checkout; tools/mcweb-install.mjs was not found"

NODE_ARCHIVE_URL="https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-PLACEHOLDER.tar.gz"
NODE_CHECKSUM_URL="https://nodejs.org/dist/$NODE_VERSION/SHASUMS256.txt"
NODE_TMP=""
SUMS_TMP=""
HEADER_TMP=""
ERROR_TMP=""
EXTRACT_DIR=""
cleanup() {
  [ -z "$NODE_TMP" ] || rm -f "$NODE_TMP"
  [ -z "$SUMS_TMP" ] || rm -f "$SUMS_TMP"
  [ -z "$HEADER_TMP" ] || rm -f "$HEADER_TMP"
  [ -z "$ERROR_TMP" ] || rm -f "$ERROR_TMP"
  [ -z "$EXTRACT_DIR" ] || rm -rf "$EXTRACT_DIR"
}
trap cleanup EXIT HUP INT TERM

has_arg() {
  wanted=$1
  shift
  for arg in "$@"; do
    [ "$arg" = "$wanted" ] && return 0
  done
  return 1
}

if has_arg --dry-run "$@"; then
  if has_arg --build "$@" || has_arg --run "$@" || has_arg --verify "$@" \
      || has_arg --download "$@" || has_arg --download-only "$@"; then
    die "--dry-run cannot be combined with --build, --run, --verify, --download, or --download-only"
  fi
fi

has_mc_dir() {
  for arg in "$@"; do
    case "$arg" in
      --mc-dir|--mc-dir=*) return 0 ;;
    esac
  done
  return 1
}
if has_arg --download "$@" || has_arg --download-only "$@"; then
  if has_mc_dir "$@" || has_arg --local-only "$@"; then
    die "--download and --download-only cannot be combined with --mc-dir or --local-only"
  fi
fi

NODE=""
if [ -z "${MCWEB_FORCE_DOWNLOAD:-}" ] && command -v node >/dev/null 2>&1; then
  case "$(node -v 2>/dev/null || true)" in
    v2[0-9].*|v[3-9][0-9].*) NODE=$(command -v node); say "using system node $(node -v)" ;;
  esac
fi

NODE_DIR="$MCWEB_HOME/node"
if [ -z "$NODE" ] && [ ! -x "$NODE_DIR/bin/node" ] && has_arg --dry-run "$@"; then
  TARBALL="node-$NODE_VERSION-$NODE_PLAT.tar.gz"
  say "dry-run: no downloads or writes"
  say "would download Node $NODE_VERSION from https://nodejs.org/dist/$NODE_VERSION/$TARBALL"
  say "would verify https://nodejs.org/dist/$NODE_VERSION/SHASUMS256.txt"
  exit 0
fi

node_url() {
  case "$1" in
    archive) printf '%s\n' "https://nodejs.org/dist/$NODE_VERSION/$TARBALL" ;;
    checksum) printf '%s\n' "$NODE_CHECKSUM_URL" ;;
    *) die "unknown Node download class: $1" ;;
  esac
}

validate_node_url() {
  raw=$1
  kind=$2
  expected=$(node_url "$kind")
  [ "$raw" = "$expected" ] || die "Node $kind URL is outside the pinned vendor path: $raw"
  printf '%s\n' "$raw"
}

resolve_node_location() {
  current=$1
  location=$2
  case "$location" in
    https://*) candidate=$location ;;
    //*) candidate="https:$location" ;;
    /*) candidate="https://nodejs.org$location" ;;
    *) candidate="${current%/*}/$location" ;;
  esac
  printf '%s\n' "$candidate"
}

transient_status() {
  case "$1" in
    408|429|5??) return 0 ;;
    *) return 1 ;;
  esac
}

header_status() {
  awk '$1 ~ /^HTTP\// && $2 ~ /^[0-9][0-9][0-9]$/ { status=$2 } END { if (status) print status }' "$1"
}

header_location() {
  awk 'tolower($1) == "location:" { $1=""; sub(/^[ \t]+/, ""); sub(/\r$/, ""); location=$0 } END { if (location) print location }' "$1"
}

new_temp() {
  mktemp "$MCWEB_HOME/.mcweb-node.XXXXXX" || die "could not create a temporary Node download file"
}

request_headers() {
  url=$1
  header=$2
  error_file=$3
  attempt=1
  while [ "$attempt" -le "$DOWNLOAD_ATTEMPTS" ]; do
    : > "$header"
    : > "$error_file"
    set +e
    curl -fsS --max-redirs 0 --connect-timeout "$CONNECT_TIMEOUT_SECONDS" \
      --max-time "$REQUEST_TIMEOUT_SECONDS" -D "$header" -o /dev/null "$url" 2>"$error_file"
    curl_status=$?
    set -e
    status=$(header_status "$header")
    if [ "$curl_status" -eq 0 ] && [ -n "$status" ]; then
      case "$status" in
        2??|3??) printf '%s\n' "$status"; return 0 ;;
        *) transient_status "$status" || return 1 ;;
      esac
    elif [ -n "$status" ] && ! transient_status "$status"; then
      return 1
    fi
    if [ "$attempt" -lt "$DOWNLOAD_ATTEMPTS" ]; then sleep "$RETRY_DELAY_SECONDS"; fi
    attempt=$((attempt + 1))
  done
  return 1
}

resolve_node_url() {
  current=$(validate_node_url "$1" "$2")
  hop=0
  while [ "$hop" -le "$MAX_REDIRECTS" ]; do
    status=$(request_headers "$current" "$HEADER_TMP" "$ERROR_TMP") || return 1
    case "$status" in
      2??) printf '%s\n' "$current"; return 0 ;;
      3??)
        [ "$hop" -lt "$MAX_REDIRECTS" ] || return 1
        location=$(header_location "$HEADER_TMP")
        [ -n "$location" ] || return 1
        current=$(validate_node_url "$(resolve_node_location "$current" "$location")" "$2")
        hop=$((hop + 1))
        ;;
      *) return 1 ;;
    esac
  done
  return 1
}

download_to_file() {
  initial_url=$1
  kind=$2
  target=$3
  max_bytes=$4
  wanted=${5:-}
  current=$(resolve_node_url "$initial_url" "$kind") || return 1
  attempt=1
  redirects=0
  while [ "$attempt" -le "$DOWNLOAD_ATTEMPTS" ]; do
    : > "$target"
    : > "$HEADER_TMP"
    : > "$ERROR_TMP"
    set +e
    curl -fsS --max-redirs 0 --connect-timeout "$CONNECT_TIMEOUT_SECONDS" \
      --max-time "$REQUEST_TIMEOUT_SECONDS" --max-filesize "$max_bytes" \
      -D "$HEADER_TMP" -o "$target" "$current" 2>"$ERROR_TMP"
    curl_status=$?
    set -e
    status=$(header_status "$HEADER_TMP")
    if [ "$curl_status" -eq 0 ] && [ -n "$status" ]; then
      case "$status" in
        3??)
          [ "$redirects" -lt "$MAX_REDIRECTS" ] || return 1
          location=$(header_location "$HEADER_TMP")
          [ -n "$location" ] || return 1
          current=$(validate_node_url "$(resolve_node_location "$current" "$location")" "$kind")
          redirects=$((redirects + 1))
          continue
          ;;
        2??)
          bytes=$(wc -c < "$target" | tr -d '[:space:]')
          [ "$bytes" -le "$max_bytes" ] || return 2
          if [ -n "$wanted" ]; then
            got=$(sha256 "$target")
            if [ "$got" != "$wanted" ]; then
              if [ "$attempt" -lt "$DOWNLOAD_ATTEMPTS" ]; then
                sleep "$RETRY_DELAY_SECONDS"
                attempt=$((attempt + 1))
                continue
              fi
              return 1
            fi
          fi
          return 0
          ;;
        *) transient_status "$status" || return 1 ;;
      esac
    elif [ "$curl_status" -eq 63 ]; then
      return 2
    elif [ -n "$status" ] && ! transient_status "$status"; then
      return 1
    fi
    if [ "$attempt" -lt "$DOWNLOAD_ATTEMPTS" ]; then sleep "$RETRY_DELAY_SECONDS"; fi
    attempt=$((attempt + 1))
  done
  return 1
}

if [ -z "$NODE" ] && [ ! -x "$NODE_DIR/bin/node" ]; then
  mkdir -p "$MCWEB_HOME"
  TARBALL="node-$NODE_VERSION-$NODE_PLAT.tar.gz"
  ARCHIVE="$MCWEB_HOME/$TARBALL"
  NODE_ARCHIVE_URL=$(node_url archive)
  say "verifying Node $NODE_VERSION ($NODE_PLAT)"
  HEADER_TMP=$(new_temp)
  ERROR_TMP=$(new_temp)
  SUMS_TMP=$(new_temp)
  download_to_file "$NODE_CHECKSUM_URL" checksum "$SUMS_TMP" "$MAX_TEXT_BYTES" \
    || die "could not download the pinned Node checksum list"
  WANT=""
  while read -r sum name; do
    [ "$name" = "$TARBALL" ] && WANT="$sum"
  done < "$SUMS_TMP"
  if ! printf '%s\n' "$WANT" | awk 'length($0) == 64 && $0 ~ /^[0-9A-Fa-f]+$/ { ok=1 } END { exit !ok }'; then
    die "Node did not publish a valid checksum for $TARBALL"
  fi
  if [ -f "$ARCHIVE" ]; then
    archive_bytes=$(wc -c < "$ARCHIVE" | tr -d '[:space:]')
    if [ "$archive_bytes" -le "$MAX_NODE_BYTES" ] && [ "$(sha256 "$ARCHIVE")" = "$(printf '%s' "$WANT" | tr '[:upper:]' '[:lower:]')" ]; then
      say "reusing verified Node archive $ARCHIVE"
    else
      rm -f "$ARCHIVE"
    fi
  fi
  if [ ! -f "$ARCHIVE" ]; then
    say "downloading Node $NODE_VERSION ($NODE_PLAT)"
    NODE_TMP=$(new_temp)
    if download_to_file "$NODE_ARCHIVE_URL" archive "$NODE_TMP" "$MAX_NODE_BYTES" \
      "$(printf '%s' "$WANT" | tr '[:upper:]' '[:lower:]')"; then
      :
    else
      code=$?
      [ "$code" -eq 2 ] && die "Node archive exceeds the $MAX_NODE_BYTES-byte cap"
      die "could not download and verify $TARBALL"
    fi
    mv -f "$NODE_TMP" "$ARCHIVE"
    NODE_TMP=""
  fi
  mkdir -p "$MCWEB_HOME"
  EXTRACT_DIR=$(mktemp -d "$MCWEB_HOME/.mcweb-node-extract.XXXXXX") \
    || die "could not create a temporary Node extraction directory"
  if ! tar -xzf "$ARCHIVE" -C "$EXTRACT_DIR" --strip-components=1; then
    die "could not extract $TARBALL"
  fi
  [ -x "$EXTRACT_DIR/bin/node" ] || die "Node archive did not contain bin/node"
  [ -e "$NODE_DIR" ] && rm -rf "$NODE_DIR"
  mv "$EXTRACT_DIR" "$NODE_DIR"
  EXTRACT_DIR=""
  rm -f "$ARCHIVE" "$SUMS_TMP"
  SUMS_TMP=""
fi
[ -n "$NODE" ] || NODE="$NODE_DIR/bin/node"
[ -x "$NODE" ] || die "Node is not executable at $NODE"
say "node $($NODE -v)"

cleanup
exec "$NODE" "$INSTALLER" "$@"
