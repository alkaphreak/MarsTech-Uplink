#!/usr/bin/env zsh
# build-install.sh
# Builds the mac-update GraalVM native binary and installs it to ~/.local/bin.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BINARY_NAME="mac-update"
TARGET_BIN="${PROJECT_DIR}/target/${BINARY_NAME}"
INSTALL_DIR="${HOME}/.local/bin"
INSTALL_PATH="${INSTALL_DIR}/${BINARY_NAME}"

echo "==> Building native binary (mvn -Pnative package)..."
cd "${PROJECT_DIR}"
mvn -Pnative package -q

if [[ ! -f "${TARGET_BIN}" ]]; then
    echo "ERROR: Expected binary not found at ${TARGET_BIN}" >&2
    exit 1
fi

echo "==> Installing ${BINARY_NAME} -> ${INSTALL_PATH}"
mkdir -p "${INSTALL_DIR}"
cp "${TARGET_BIN}" "${INSTALL_PATH}"
chmod +x "${INSTALL_PATH}"

echo "==> Smoke-testing installation..."
"${INSTALL_PATH}" --dry-run --only brew

echo ""
echo "  Installed : ${INSTALL_PATH}"
echo "  Version   : $("${INSTALL_PATH}" --version 2>&1 | head -1)"
echo ""
echo "  Make sure ${INSTALL_DIR} is in your PATH:"
echo "    export PATH=\"\${HOME}/.local/bin:\${PATH}\""
