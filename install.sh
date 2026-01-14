#!/bin/bash
# PURPOSE: Download and set up jvm-proxy for Claude Code environments
# PURPOSE: Curl-pipeable: curl -fsSL https://raw.githubusercontent.com/iterative-works/claude-jvm-proxy/main/install.sh | bash

set -e

REPO="iterative-works/claude-jvm-proxy"
BINARY_NAME="jvm-proxy"
INSTALL_DIR="${HOME}/.local/bin"
VERSION="${JVM_PROXY_VERSION:-latest}"

# Detect platform
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

case "$ARCH" in
    x86_64|amd64) ARCH="x86_64" ;;
    aarch64|arm64) ARCH="aarch64" ;;
    *) echo "Unsupported architecture: $ARCH" >&2; exit 1 ;;
esac

case "$OS" in
    linux) PLATFORM="linux-${ARCH}" ;;
    darwin) PLATFORM="macos-${ARCH}" ;;
    *) echo "Unsupported OS: $OS" >&2; exit 1 ;;
esac

# Get download URL
if [ "$VERSION" = "latest" ]; then
    DOWNLOAD_URL="https://github.com/${REPO}/releases/latest/download/${BINARY_NAME}-${PLATFORM}"
else
    DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${VERSION}/${BINARY_NAME}-${PLATFORM}"
fi

# Create install directory
mkdir -p "$INSTALL_DIR"

echo "Downloading jvm-proxy for ${PLATFORM}..." >&2
curl -fsSL "$DOWNLOAD_URL" -o "${INSTALL_DIR}/${BINARY_NAME}"
chmod +x "${INSTALL_DIR}/${BINARY_NAME}"

echo "Installed to ${INSTALL_DIR}/${BINARY_NAME}" >&2

# Start proxy in background if not already running
if "${INSTALL_DIR}/${BINARY_NAME}" status 2>/dev/null | grep -q "running"; then
    echo "Proxy already running" >&2
else
    "${INSTALL_DIR}/${BINARY_NAME}" start &>/dev/null &
    disown 2>/dev/null || true
    sleep 0.5
    echo "Proxy started in background" >&2
fi

# Output setup commands for eval
cat <<EOF
export PATH="${INSTALL_DIR}:\$PATH"
export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=13130 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=13130"
EOF

echo "" >&2
echo "Ready. Use sbt, scala-cli, or coursier normally." >&2
