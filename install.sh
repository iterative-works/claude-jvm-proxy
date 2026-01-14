#!/bin/bash
# PURPOSE: Set up JVM build tools to work in Claude Code sandbox environment
# PURPOSE: Handles proxy authentication and native image limitations

set -e

REPO="iterative-works/claude-jvm-proxy"
INSTALL_DIR="${HOME}/.local/bin"
LIB_DIR="${HOME}/.local/lib"
ENV_FILE="${HOME}/.jvm-proxy-env"
SCALA_CLI_VERSION="1.6.1"

# Get upstream proxy URL - try environment first, then GLOBAL_AGENT_HTTP_PROXY
get_upstream_proxy() {
    if [ -n "$HTTPS_PROXY" ]; then
        echo "$HTTPS_PROXY"
    elif [ -n "$HTTP_PROXY" ]; then
        echo "$HTTP_PROXY"
    elif [ -n "$GLOBAL_AGENT_HTTP_PROXY" ]; then
        echo "$GLOBAL_AGENT_HTTP_PROXY"
    else
        echo ""
    fi
}

UPSTREAM_PROXY=$(get_upstream_proxy)

if [ -z "$UPSTREAM_PROXY" ]; then
    echo "Error: No proxy URL found in HTTPS_PROXY, HTTP_PROXY, or GLOBAL_AGENT_HTTP_PROXY" >&2
    exit 1
fi

echo "Using upstream proxy: ${UPSTREAM_PROXY%%@*}@..." >&2

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

# Create directories
mkdir -p "$INSTALL_DIR" "$LIB_DIR"

# Download jvm-proxy binary (using upstream proxy directly)
echo "Downloading jvm-proxy..." >&2
JVM_PROXY_URL="https://github.com/${REPO}/releases/latest/download/jvm-proxy-${PLATFORM}"
curl -x "$UPSTREAM_PROXY" -fsSL "$JVM_PROXY_URL" -o "${INSTALL_DIR}/jvm-proxy"
chmod +x "${INSTALL_DIR}/jvm-proxy"

# Start jvm-proxy with explicit upstream (since native images can't read env vars in sandbox)
if "${INSTALL_DIR}/jvm-proxy" status 2>/dev/null | grep -q "running"; then
    echo "Proxy already running" >&2
else
    "${INSTALL_DIR}/jvm-proxy" start --upstream "$UPSTREAM_PROXY" &>/dev/null &
    disown 2>/dev/null || true
    sleep 0.5
    echo "Proxy started on localhost:13130" >&2
fi

# Download scala-cli JAR version (native image has SSL/proxy issues in sandbox)
echo "Downloading scala-cli JAR..." >&2
SCALA_CLI_JAR="${LIB_DIR}/scala-cli.jar"
SCALA_CLI_URL="https://github.com/VirtusLab/scala-cli/releases/download/v${SCALA_CLI_VERSION}/scala-cli.jar"

# Use curl with local proxy for download
curl -x http://127.0.0.1:13130 -fsSL "$SCALA_CLI_URL" -o "$SCALA_CLI_JAR"

# Create scala-cli wrapper script
cat > "${INSTALL_DIR}/scala-cli" << 'WRAPPER'
#!/bin/bash
# Wrapper for scala-cli that uses JVM version with proxy settings
exec java \
    -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=13130 \
    -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=13130 \
    -jar ~/.local/lib/scala-cli.jar \
    --server=false --jvm system "$@"
WRAPPER
chmod +x "${INSTALL_DIR}/scala-cli"

# Create cs (coursier) wrapper - uses scala-cli's coursier internally
cat > "${INSTALL_DIR}/cs" << 'WRAPPER'
#!/bin/bash
# Wrapper for coursier that uses JVM version with proxy settings
exec java \
    -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=13130 \
    -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=13130 \
    -jar ~/.local/lib/scala-cli.jar \
    --power --server=false --jvm system \
    shim coursier "$@"
WRAPPER
chmod +x "${INSTALL_DIR}/cs"

# Write environment file
cat > "$ENV_FILE" << EOF
export PATH="${INSTALL_DIR}:\$PATH"
export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=13130 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=13130"
export SBT_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=13130 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=13130"
EOF

echo "Environment saved to ${ENV_FILE}" >&2

# If CLAUDE_ENV_FILE is set, append to it for Claude Code persistence
if [ -n "$CLAUDE_ENV_FILE" ]; then
    touch "$CLAUDE_ENV_FILE"
    if ! grep -q "jvm-proxy" "$CLAUDE_ENV_FILE" 2>/dev/null; then
        echo "" >> "$CLAUDE_ENV_FILE"
        echo "# jvm-proxy environment" >> "$CLAUDE_ENV_FILE"
        cat "$ENV_FILE" >> "$CLAUDE_ENV_FILE"
        echo "Configured CLAUDE_ENV_FILE for persistence" >&2
    fi
fi

# Output for eval
cat "$ENV_FILE"

echo "" >&2
echo "Setup complete. Tools available:" >&2
echo "  - scala-cli (JVM wrapper with proxy)" >&2
echo "  - cs (coursier via scala-cli)" >&2
echo "  - sbt (uses JAVA_TOOL_OPTIONS)" >&2
echo "  - mill (uses JAVA_TOOL_OPTIONS)" >&2
echo "" >&2
echo "Usage: source ~/.jvm-proxy-env && sbt compile" >&2
