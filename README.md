<div align="center">

<img src="https://oras.land/img/oras.svg" alt="ORAS logo" width="96">

# ORAS Java MCP

[![Build](https://github.com/jonesbusy/oras-java-mcp/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/jonesbusy/oras-java-mcp/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.jonesbusy/oras-java-mcp?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.jonesbusy/oras-java-mcp)
[![Java](https://img.shields.io/badge/Java-21%2B-blue?logo=openjdk)](https://adoptium.net)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Let your coding agent inspect container images, Helm charts and OCI artifacts in any registry.

[Overview](#overview) • [Tools](#tools) • [Getting started](#getting-started) • [Configuration](#configuration) • [Development](#development)

</div>

## Overview

ORAS Java MCP is a read-only [Model Context Protocol](https://modelcontextprotocol.io) server for OCI registries. It runs over stdio and answers questions such as:

- Which platforms does this image support, and what does its `linux/arm64` variant contain?
- Is this image signed? Does it have an SBOM attached?
- What is the entrypoint, environment or label set of this image?
- Which tags exist for this Helm chart?
- Which digest does `alpine` resolve to right now, and through which mirror?

It is built on the [ORAS Java SDK](https://github.com/oras-project/oras-java), [Quarkus ORAS](https://github.com/quarkiverse/quarkus-oras) and the [Quarkus MCP server](https://github.com/quarkiverse/quarkus-mcp-server), and it runs with [jbang](https://www.jbang.dev).

Key features:

- **Read-only.** Every tool is annotated `readOnlyHint`. The server never pushes, tags or deletes.
- **Structured output.** Every tool publishes an output schema and returns `structuredContent`, so agents get typed JSON.
- **Bring your own login.** The server handles no credentials. It reuses `docker login` and `podman login`, and the host's `registries.conf` and `policy.json`.
- **Few tools.** There are seven, because every tool description costs the agent context on every turn.

## Tools

A reference is `[registry/]repository[:tag|@digest]`. Unqualified names resolve the same way docker and podman resolve them (`alpine` becomes `docker.io/library/alpine:latest`).

| Tool | What it answers |
|---|---|
| `resolve` | The fully qualified reference after unqualified search and `registries.conf` aliases and rewrites, pinned to its current digest (`pinned`). It costs a single HEAD request. |
| `inspect` | What a reference points to. For a manifest it returns the artifactType, annotations, subject, config and layers (with titles) and the total size. For an index it returns one entry per platform or artifact. `platform` (for example `linux/arm64`) goes down into an index entry, and `includeConfig` adds the image config (entrypoint, env, labels, history). |
| `get_manifest` | The manifest or index JSON, exactly as the registry serves it. |
| `get_blob` | A text blob by digest: a config, SBOM, signature payload or small text layer. Output is capped by `maxBytes` (64 KiB by default, 1 MiB at most). Binary blobs are refused. |
| `list_tags` | Tags of a repository, a page at a time (`pageSize`, `last`). |
| `list_repositories` | The registry catalog, where the registry allows it. |
| `list_referrers` | Artifacts attached through the OCI referrers API (signatures, SBOMs, attestations), with an optional `artifactType` filter. A tag is resolved to its digest first. |

Registry failures come back as tool errors (`isError: true`) that include the HTTP status, so the agent can act on them.

## Getting started

### Prerequisites

- [jbang](https://www.jbang.dev/download/). It downloads a JDK if none is found. Java 21 or later is required.
- Optionally, a `docker login` or `podman login` for private registries.

### Trust the download origin

On first run, jbang asks whether to trust the download origin, and an MCP client cannot answer that prompt. Trust the origin once from a terminal:

```sh
jbang trust add https://repo.maven.apache.org/maven2/io/github/jonesbusy/
```

### Add the server to your MCP client

**Claude Code**

```sh
claude mcp add oras -- jbang --quiet oras-java-mcp@jonesbusy/oras-java-mcp
```

**VS Code, Claude Desktop and other clients**

```json
{
  "mcpServers": {
    "oras": {
      "command": "jbang",
      "args": ["--quiet", "oras-java-mcp@jonesbusy/oras-java-mcp"]
    }
  }
}
```

> [!TIP]
> To run without jbang, download `oras-java-mcp-<version>-runner.jar` from [Maven Central](https://central.sonatype.com/artifact/io.github.jonesbusy/oras-java-mcp) or the [GitHub releases](https://github.com/jonesbusy/oras-java-mcp/releases), and use `java -jar oras-java-mcp-<version>-runner.jar` as the command.

### Try it

Ask your agent something like:

> Inspect `ghcr.io/oras-project/oras:v1.2.0` for `linux/arm64` and show me its entrypoint.

## Configuration

Authentication and registry behaviour come from the ORAS Java SDK defaults, the same files the docker and podman CLIs use:

| Source | Used for |
|---|---|
| `~/.docker/config.json`, `$REGISTRY_AUTH_FILE`, `$XDG_RUNTIME_DIR/containers/auth.json` | Credentials, including credential helpers |
| `registries.conf` | Unqualified-search registries, aliases, mirrors, `insecure` registries |
| `policy.json` | Trust policy |

[Quarkus ORAS settings](https://docs.quarkiverse.io/quarkus-oras/dev/) can be overridden through environment variables in the MCP client config:

| Variable | Effect |
|---|---|
| `QUARKUS_ORAS_REGISTRIES_DEFAULT_SECURE=false` | Plain HTTP for every registry |
| `QUARKUS_ORAS_REGISTRIES_DEFAULT_CA_FILE=/path/ca.pem` | Trust an extra CA |
| `QUARKUS_ORAS_REGISTRIES_DEFAULT_MAX_RETRIES=5` | Retry transient failures more often |

> [!NOTE]
> `QUARKUS_ORAS_REGISTRIES_DEFAULT_SECURE=false` applies to every registry. To use plain HTTP for one local registry only, mark it `insecure = true` in `registries.conf`.

## Development

You need Java 25, Maven 3.10, and Docker or Podman for the tests. Versions are pinned in [`.sdkmanrc`](.sdkmanrc), so `sdk env` sets them up.

```sh
mvn spotless:apply   # format Java and POM
mvn verify           # build, unit tests, stdio integration test
```

- `OrasToolsTest` runs every tool against the zot registry that Quarkus ORAS starts as a dev service.
- `McpServerIT` starts the packaged runner jar and talks to it over stdio, as an MCP client would, against a zot container.

To try a local build in the MCP Inspector:

```sh
npx @modelcontextprotocol/inspector java -jar target/oras-java-mcp-0.1.0-SNAPSHOT-runner.jar
```

### Release

Push an `x.y.z` tag on a green `main` commit. The [release workflow](.github/workflows/release.yml) then:

1. deploys the signed artifacts, including the `runner` uber jar, to Maven Central with [njord](https://github.com/maveniverse/njord)
2. publishes the release-drafter draft and attaches the runner jar

After the release, bump the version in [`jbang-catalog.json`](jbang-catalog.json) so the jbang alias serves it.

> [!IMPORTANT]
> The release workflow needs the `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_KEY` and `GPG_PASSPHRASE` repository secrets.
