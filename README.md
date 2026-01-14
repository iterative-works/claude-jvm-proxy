# jvm-proxy

A local HTTP proxy that enables JVM tools (sbt, scala-cli, coursier) to work with authenticated HTTP proxies.

## Problem

JVM tools often fail to authenticate with HTTP proxies that require Basic auth for HTTPS tunneling. This is particularly problematic in environments like Claude Code on web, where traffic goes through an authenticated proxy.

## Solution

`jvm-proxy` runs locally, accepts unauthenticated connections from JVM tools, and forwards them to the upstream proxy with proper authentication headers.

```
JVM Tool -> jvm-proxy (localhost:13130) -> Upstream Proxy (with auth) -> Internet
```

## Quick Start (Claude Code)

```bash
# Install, start proxy, and set up environment (one command)
eval "$(curl -fsSL https://raw.githubusercontent.com/iterative-works/claude-jvm-proxy/main/install.sh)"

# JVM tools now work normally
sbt compile
scala-cli run .
cs fetch org.typelevel::cats-core:2.10.0
```

## Manual Installation

Download the binary for your platform from [releases](https://github.com/iterative-works/claude-jvm-proxy/releases):

```bash
# Linux x86_64
curl -fsSL https://github.com/iterative-works/claude-jvm-proxy/releases/latest/download/jvm-proxy-linux-x86_64 -o jvm-proxy
chmod +x jvm-proxy
```

## Usage

```bash
# Start proxy (reads HTTPS_PROXY/HTTP_PROXY from environment)
./jvm-proxy start &

# Configure JVM tools to use local proxy
eval "$(./jvm-proxy env)"

# Check status
./jvm-proxy status

# Stop proxy
./jvm-proxy stop
```

### Commands

| Command | Description |
|---------|-------------|
| `start` | Start the proxy server (default port: 13130) |
| `stop` | Stop the running proxy |
| `status` | Check if proxy is running |
| `env` | Print environment variables for JVM tools |

### Options

| Option | Description |
|--------|-------------|
| `-p, --port PORT` | Use custom port (default: 13130) |

## Requirements

- `HTTPS_PROXY` or `HTTP_PROXY` environment variable set with credentials:
  ```
  export HTTPS_PROXY=http://user:password@proxy.example.com:8080
  ```

## How It Works

1. JVM tools connect to `localhost:13130`
2. `jvm-proxy` receives the CONNECT request
3. It connects to the upstream proxy with Basic auth header
4. Once tunnel is established, data flows bidirectionally

The proxy only handles HTTPS CONNECT tunneling (which is what JVM tools need for Maven Central, etc.).

## Building from Source

Requires [scala-cli](https://scala-cli.virtuslab.org/):

```bash
scala-cli --power package --native . -o jvm-proxy
```

## License

MIT
