# shellcheck shell=bash
#
# Shared bootstrap for bin/ scripts. Source it as the FIRST line of each script:
#
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
#
# Goal: make every script safe to run from any working directory and from a
# non-interactive, non-login shell (CI runners and AI-agent sandboxes), which
# do NOT source a developer's ~/.bashrc / ~/.zshrc and therefore lack the PATH
# entries a normal terminal would have.
#
# Not meant to be executed directly.

# Fail loudly and early. Deliberately NOT using `-u` (nounset): several scripts
# (e.g. forAll) intentionally test unset positional args like `[ "x$1" = "x" ]`.
set -eo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export REPO_ROOT

# --- PATH: add standard, broadly-portable locations -------------------------
# Only prepend dirs that exist and aren't already present. This does NOT assume
# any particular toolchain manager — it just surfaces the usual install spots so
# an existing flutter/dart/node is found in a bare shell.
_prepend_path() {
  [ -d "$1" ] || return 0
  case ":$PATH:" in
    *":$1:"*) ;;            # already present
    *) PATH="$1:$PATH" ;;
  esac
}
_prepend_path "/opt/homebrew/bin"        # Homebrew (Apple Silicon)
_prepend_path "/usr/local/bin"           # Homebrew (Intel) / common installs
_prepend_path "$HOME/.pub-cache/bin"     # globally-activated Dart tools
# node/npm via nvm, newest installed version, if nvm is used — but only when npm
# isn't already resolvable. This avoids overriding a newer system/Homebrew npm
# with an older nvm-bundled one (npm <11.15.0 rejects min-release-age + --before
# together; fixed in npm 11.15.0 via https://github.com/npm/cli/pull/9339).
if ! command -v npm >/dev/null 2>&1 && [ -d "$HOME/.nvm/versions/node" ]; then
  _nvm_bin="$(ls -d "$HOME"/.nvm/versions/node/*/bin 2>/dev/null | sort -V | tail -1)"
  [ -n "${_nvm_bin:-}" ] && _prepend_path "$_nvm_bin"
fi
export PATH

# --- Toolchain: prefer the Flutter pinned in .fvmrc --------------------------
# The project pin wins over a global Flutter already on PATH. Real SDK
# binaries, not the `fvm` shim: they inherit through PATH and cannot recurse
# when fvm itself shells out to dart/flutter.
_fvmrc_flutter_version() {
  [ -f "$REPO_ROOT/.fvmrc" ] || return 1
  sed -nE 's/.*"flutter"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' "$REPO_ROOT/.fvmrc"
}

_prefer_project_flutter() {
  local _sdk_bin _ver
  for _sdk_bin in \
    "$REPO_ROOT/.fvm/flutter_sdk/bin" \
    "$REPO_ROOT/flutter_readium/.fvm/flutter_sdk/bin"; do
    if [ -x "$_sdk_bin/dart" ] && [ -x "$_sdk_bin/flutter" ]; then
      _prepend_path "$_sdk_bin"
      return 0
    fi
  done
  _ver="$(_fvmrc_flutter_version)" || return 1
  [ -n "$_ver" ] || return 1
  _sdk_bin="$HOME/fvm/versions/${_ver}/bin"
  if [ -x "$_sdk_bin/dart" ] && [ -x "$_sdk_bin/flutter" ]; then
    _prepend_path "$_sdk_bin"
    return 0
  fi
  return 1
}

_prefer_project_flutter || true
