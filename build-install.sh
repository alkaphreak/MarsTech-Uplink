#!/usr/bin/env zsh
# build-install.sh
# Builds the marstech-uplink GraalVM native binary and installs it to ~/.local/bin.

set -eo pipefail  # Note: -u is intentionally omitted here; sdkman-init.sh uses unset variables

# ── sdkman bootstrap ──────────────────────────────────────────────────────────
export SDKMAN_DIR="$HOME/.sdkman"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Source sdkman from $HOME (no .sdkmanrc there) to avoid the auto_env cd-hook
# firing before the required Java candidate is installed.
set +u
cd "$HOME"
source "$SDKMAN_DIR/bin/sdkman-init.sh"

# Read the required Java version from the project's .sdkmanrc and install it now,
# while still in $HOME, so the version exists on disk before we cd back.
# `sdk install` is a no-op when the version is already present.
JAVA_VERSION=$(grep '^java=' "$PROJECT_DIR/.sdkmanrc" | cut -d= -f2)
echo "==> Installing java ${JAVA_VERSION} (no-op if already present)..."
echo "n" | sdk install java "$JAVA_VERSION" 2>&1 || true

# Now cd to the project dir. The auto_env cd-hook will run `sdk env`, but the
# candidate is already installed so it succeeds without "Stop!".
cd "$PROJECT_DIR"

# Activate the exact versions declared in .sdkmanrc for this shell session.
sdk env
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

BINARY_NAME="marstech-uplink"
TARGET_BIN="${PROJECT_DIR}/target/${BINARY_NAME}"
INSTALL_DIR="${HOME}/.local/bin"
INSTALL_PATH="${INSTALL_DIR}/${BINARY_NAME}"

echo "==> Building native binary (mvn -Pnative package)..."
mvn clean install
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
