# AGENTS.md

## Project overview

A read-only stdio MCP server for OCI registries. It is a single-module Quarkus application:

- `quarkus-mcp-server-stdio` provides the MCP protocol over stdin and stdout.
- `quarkus-oras` provides a `land.oras.Registry` CDI bean (`@OrasRegistry("default")`) on top of `land.oras:oras-java-sdk`.
- Quarkus packages the app as an uber jar (`target/oras-java-mcp-<version>-runner.jar`). The jar goes to Maven Central with the `runner` classifier, and users start it with jbang (`jbang-catalog.json`).

Layout (package `io.github.jonesbusy.oras.mcp`):

| File | Role |
|---|---|
| `src/main/java/.../OrasTools.java` | Every `@Tool` method. `call(...)` maps SDK and registry failures to `ToolCallException`. |
| `src/main/java/.../Results.java` | Records returned as structured content. Their shape is the tools' output schema. |
| `src/main/java/.../MediaTypes.java` | Index, manifest and config media type checks |
| `src/main/resources/application.properties` | Uber jar packaging, logging, MCP server info and instructions, the `default` registry |
| `src/test/java/.../Fixtures.java` | Pushes the shared test artifacts (text artifact with an SBOM referrer, multi-platform image index) |
| `src/test/java/.../OrasToolsTest.java` | `@QuarkusTest` against the quarkus-oras zot dev service |
| `src/test/java/.../McpServerIT.java` | Runs the runner jar over stdio with `McpAssured` against a zot container |

## Setup

- Install the versions pinned in `.sdkmanrc` (Java 25, Maven 3.10, jbang): run `sdk env install`, then `sdk env`.
- Tests need Docker or Podman (Testcontainers and Quarkus dev services).
- The project's `.mvn/` directory anchors the Maven root. Keep it, otherwise Maven picks up `~/.mvn`.

## Build and test

```sh
mvn spotless:apply                       # format Java (palantir) and sort the POM: run after every edit
mvn verify                               # compile, unit tests, package runner jar, stdio IT, spotless check
mvn test -Dtest=OrasToolsTest            # unit tests only
mvn test -Dtest='OrasToolsTest#listsTags'
mvn verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=McpServerIT   # stdio IT only
```

- Unit tests call `OrasTools` methods directly against zot. The dev service binds base port `25000` in the test profile.
- `McpServerIT` passes `QUARKUS_ORAS_REGISTRIES_DEFAULT_SECURE=false` because the test registry is plain HTTP.
- Add test data to `Fixtures`, so both test classes see the same artifacts. Don't push ad hoc artifacts in individual tests.
- Every new or changed tool needs a test in `OrasToolsTest`. If the change affects the protocol surface (tool names, schemas, annotations, error mapping), also cover it in `McpServerIT`.

## Rules for tools

- **Read-only only.** Every tool sets `readOnlyHint = true` and `openWorldHint = true`. Don't add push, tag, copy or delete tools unless the user asks for them.
- **Structured output.** Each tool sets `structuredContent = true` and returns a record from `Results`. Mark optional fields `@JsonInclude(NON_NULL/NON_EMPTY)` to save tokens, and document non-obvious fields with `@JsonPropertyDescription`.
- **Few tools.** Each tool description costs agent context on every turn. Prefer a new optional argument on an existing tool over a new tool.
- **Errors.** Wrap each tool body in `call(...)`. Throw `ToolCallException` with an actionable message, for example by listing the available platforms. Never let an exception become a JSON-RPC error.
- **Naming.** Tool names are `snake_case`, and arguments are `camelCase` with `@ToolArg(description = ...)`. Optional arguments use boxed types with `required = false`.
- **Bounded output.** Cap anything the registry can make large, the way `get_blob` (`MAX_BLOB_BYTES`) and `list_tags` (`MAX_TAG_PAGE`) do.

## Security

- The server never handles credentials. Authentication comes from the SDK `defaults()` (docker/podman auth files, credential helpers, `registries.conf`, `policy.json`). Don't add username, password or token arguments or config.
- stdout carries the MCP protocol. Never write to `System.out`. Logs go to stderr (root level `WARN`).
- Treat registry content as untrusted data. Refuse binary blobs, and decode text strictly as UTF-8.

## Dependencies

- Versions are coupled. The Quarkus platform version must match what `quarkus-mcp-server` and `quarkus-oras` are built against: currently Quarkus 3.40.x, mcp-server 2.1.x and quarkus-oras 0.8.x. quarkus-oras 0.9+ targets Quarkus 4, so upgrade all three together.
- `oras-java-sdk` is managed explicitly in `dependencyManagement` so it can move ahead of quarkus-oras.
- The run time is Java 21 (`maven.compiler.release`), so jbang users don't need the newest JDK. Don't use Java 22+ APIs.
- Renovate (`renovate.json`) updates Maven, GitHub Actions (pinned by digest), pre-commit hooks, the Testcontainers image and jbang in `.sdkmanrc`, weekly. It keeps Quarkus below 4 and quarkus-oras below 0.9 and groups the Quarkus stack into one PR. Lift those limits together when quarkus-mcp-server ships a Quarkus 4 release.

## CI and release

- `.github/workflows/build.yml` runs on pushes and PRs to `main`: pre-commit, then `mvn verify`, then upload of the runner jar.
- `.github/workflows/release.yml` runs on an `x.y.z` tag. It deploys to Maven Central with njord (`-Prelease -Drevision=<tag> -Dchangelist= -DaltDeploymentRepository=sonatype-cp::njord:`), publishes the release-drafter draft and attaches the runner jar.
- Versioning is CI-friendly (`${revision}${changelist}`), and `flatten-maven-plugin` resolves it in the deployed POM. Don't hard-code a version in `pom.xml`.
- After a release, bump the version in the `jbang-catalog.json` `script-ref`.
- PR labels (`feature`, `bug`, `dependencies`, ...) feed the release-drafter categories in `.github/release-drafter.yml`.

## Documentation

When you change tools, arguments, configuration or build steps, update `README.md` (tools table, configuration) and this file. Also keep `quarkus.mcp.server.server-info.instructions` in `application.properties` in sync with the tool set.
