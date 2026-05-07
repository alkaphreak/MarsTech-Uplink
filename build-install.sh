#!/usr/bin/env zsh
# build-install.sh
# Builds the marstech-uplink GraalVM native binary and installs it to ~/.local/bin.

set -eo pipefail  # Note: -u is intentionally omitted here; sdkman-init.sh uses unset variables

# ── sdkman bootstrap ──────────────────────────────────────────────────────────
export SDKMAN_DIR="$HOME/.sdkman"
# Temporarily allow unset variables while sourcing sdkman (it is not -u safe)
set +u
source "$SDKMAN_DIR/bin/sdkman-init.sh"
set -u

echo "==> Configuring SDK environment from .sdkmanrc..."
set +u
sdk env install   # install any missing candidates declared in .sdkmanrc
sdk env           # activate the versions declared in .sdkmanrc
set -u

# Fallback: resolve JAVA_HOME directly from sdkman candidates if sdk env did not export it
if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_VERSION=$(grep '^java=' .sdkmanrc | cut -d= -f2)
  export JAVA_HOME="$SDKMAN_DIR/candidates/java/$JAVA_VERSION"
  export PATH="$JAVA_HOME/bin:$PATH"
  echo "    (JAVA_HOME resolved manually from .sdkmanrc)"
fi

echo "    Using Java : $(java -version 2>&1 | head -1)"
echo "    JAVA_HOME  : ${JAVA_HOME}"

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BINARY_NAME="marstech-uplink"
TARGET_BIN="${PROJECT_DIR}/target/${BINARY_NAME}"
INSTALL_DIR="${HOME}/.local/bin"
INSTALL_PATH="${INSTALL_DIR}/${BINARY_NAME}"

echo "==> Building native binary (mvn -Pnative package)..."
cd "${PROJECT_DIR}"
mvn -Pnative package -q -DskipTests

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
