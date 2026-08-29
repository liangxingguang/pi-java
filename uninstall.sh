#!/usr/bin/env bash
# Uninstall pi-java from the install dir (default ~/.local/pi-java).
# Usage:
#   ./uninstall.sh           -> default install dir
#   ./uninstall.sh /opt/pi   -> custom install dir
set -euo pipefail

INSTALL_DIR="${1:-$HOME/.local/pi-java}"

rm -rf "$INSTALL_DIR"
echo "[uninstall] removed $INSTALL_DIR."
echo "[uninstall] note: remove the bin dir from your shell PATH manually if added."
