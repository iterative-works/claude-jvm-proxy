# jvm-proxy

A local HTTP proxy that enables JVM build tools (sbt, scala-cli, coursier, mill) to work in environments with authenticated HTTP proxies, such as Claude Code sandbox.

## Problem

In Claude Code sandbox:
1. Traffic must go through an authenticated HTTP proxy
2. Native image tools (scala-cli, coursier) can't read environment variables due to sandbox restrictions
3. Native images have embedded SSL certificates that don't include the sandbox's TLS inspection CA

## Solution

`jvm-proxy` runs locally, accepts unauthenticated connections from JVM tools, and forwards them to the upstream proxy with authentication. The install script also provides JVM-based wrappers for scala-cli that bypass the native image limitations.

```
JVM Tool -> jvm-proxy (localhost:13130) -> Upstream Proxy (with auth) -> Internet
```

## Quick Start (Claude Code Sandbox)

```bash
# One-time setup (run this first in your session)
curl -fsSL https://raw.githubusercontent.com/iterative-works/claude-jvm-proxy/main/install.sh | bash

# Then for each command, source the environment:
source ~/.jvm-proxy-env && sbt compile
source ~/.jvm-proxy-env && scala-cli run .
source ~/.jvm-proxy-env && mill compile
```

The install script will:
1. Download and start `jvm-proxy` with the upstream proxy URL
2. Download scala-cli JAR version (bypasses native image issues)
3. Create wrapper scripts for `scala-cli` and `cs`
4. Set up `JAVA_TOOL_OPTIONS` and `SBT_OPTS` for sbt/mill

### Environment Persistence

If you set `CLAUDE_ENV_FILE` in your Claude Code settings, the environment will persist across bash commands:

```
CLAUDE_ENV_FILE=/tmp/claude-env.sh
```

Then after running install once, subsequent commands work without `source`:
```bash
sbt compile  # just works
```

## Tool Support

| Tool | Method | Notes |
|------|--------|-------|
| sbt | `JAVA_TOOL_OPTIONS` | Works directly after sourcing env |
| mill | `JAVA_TOOL_OPTIONS` | Works directly after sourcing env |
| scala-cli | JVM wrapper | Uses JAR version, not native image |
| coursier (cs) | JVM wrapper | Via scala-cli shim |

## Local Usage (outside sandbox)

```bash
# If you have proxy env vars set
eval "$(curl -fsSL https://raw.githubusercontent.com/iterative-works/claude-jvm-proxy/main/install.sh)"
sbt compile
```

## Manual Usage

```bash
# Start with explicit upstream proxy
./jvm-proxy start --upstream http://user:pass@proxy:8080 &

# Or using environment variables (if they work in your environment)
export HTTPS_PROXY=http://user:pass@proxy:8080
./jvm-proxy start &

# Configure JVM tools
eval $(./jvm-proxy env)
sbt compile
```

### Commands

| Command | Description |
|---------|-------------|
| `start` | Start the proxy server |
| `stop` | Stop the running proxy |
| `status` | Check if proxy is running |
| `env` | Print environment variables for JVM tools |

### Options

| Option | Description |
|--------|-------------|
| `-p, --port PORT` | Listen port (default: 13130) |
| `-u, --upstream URL` | Upstream proxy: `http://user:pass@host:port` |

## How It Works

1. Install script captures proxy URL from environment (bash can read env vars)
2. `jvm-proxy` starts with explicit `--upstream` flag (bypasses native image env var issue)
3. JVM tools connect to `localhost:13130` (no auth needed)
4. `jvm-proxy` forwards to upstream with `Proxy-Authorization` header
5. JVM reads system truststore which has the TLS inspection CA

## Building from Source

Requires [scala-cli](https://scala-cli.virtuslab.org/):

```bash
# Debug build
scala-cli --power package --native . -o jvm-proxy

# Release build
scala-cli --power package --native . -o jvm-proxy --native-mode release-fast
strip jvm-proxy
```

## Technical Details

- Built with Scala Native 0.5.9 for fast startup and small binary
- Uses POSIX APIs for process management
- Bidirectional streaming with thread-per-direction (no buffering)
- Only handles HTTPS CONNECT tunneling (what JVM tools need for Maven Central)

## License

MIT
