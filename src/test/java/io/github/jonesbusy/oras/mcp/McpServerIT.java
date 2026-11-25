package io.github.jonesbusy.oras.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStdioTestClient;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import land.oras.Registry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class McpServerIT {

    private static final List<String> TOOLS = List.of(
            "resolve", "inspect", "get_manifest", "get_blob", "list_tags", "list_repositories", "list_referrers");

    private static GenericContainer<?> zot;
    private static Fixtures fixtures;

    @BeforeAll
    static void startRegistry() throws Exception {
        zot = new GenericContainer<>("ghcr.io/project-zot/zot-linux-amd64:v2.1.15")
                .withExposedPorts(5000)
                .waitingFor(Wait.forHttp("/v2/").forPort(5000));
        zot.start();
        String host = "localhost:" + zot.getMappedPort(5000);
        Registry client = Registry.builder().insecure().build();
        fixtures = new Fixtures(host).push(client, Files.createTempDirectory("oras-mcp-it"));
    }

    @AfterAll
    static void stopRegistry() {
        if (zot != null) {
            zot.stop();
        }
    }

    private static McpStdioTestClient connect() {
        String runner = System.getProperty("runner.jar", "target/oras-java-mcp-runner.jar");
        assertThat(Path.of(runner)).exists();
        return McpAssured.newStdioClient()
                .setCommand("java", "-jar", runner)
                .setEnvironment(Map.of("QUARKUS_ORAS_REGISTRIES_DEFAULT_SECURE", "false"))
                .build()
                .connect();
    }

    @Test
    void listsReadOnlyToolsWithOutputSchemas() {
        try (McpStdioTestClient client = connect()) {
            client.when()
                    .toolsList(page -> {
                        assertThat(page.tools())
                                .extracting(McpAssured.ToolInfo::name)
                                .containsExactlyInAnyOrderElementsOf(TOOLS);
                        page.tools().forEach(tool -> {
                            assertThat(tool.outputSchema())
                                    .as("outputSchema of %s", tool.name())
                                    .isNotNull();
                            assertThat(tool.annotations()).hasValueSatisfying(a -> assertThat(a.readOnlyHint())
                                    .isTrue());
                        });
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void returnsStructuredContentOverStdio() {
        try (McpStdioTestClient client = connect()) {
            client.when()
                    .toolsCall("inspect", Map.of("reference", fixtures.multiPlatformImage), response -> {
                        assertThat(response.isError()).isFalse();
                        JsonObject structured = JsonObject.mapFrom(response.structuredContent());
                        assertThat(structured.getString("kind")).isEqualTo("index");
                        assertThat(structured.getJsonArray("manifests")).hasSize(2);
                    })
                    .toolsCall("list_referrers", Map.of("reference", fixtures.textArtifact), response -> {
                        assertThat(response.isError()).isFalse();
                        JsonObject structured = JsonObject.mapFrom(response.structuredContent());
                        assertThat(structured.getString("subject")).isEqualTo(fixtures.textArtifactDigest);
                    })
                    .toolsCall("inspect", Map.of("reference", fixtures.host + "/missing/repo:1"), response -> {
                        assertThat(response.isError()).isTrue();
                        assertThat(response.firstContent().asText().text()).contains("404");
                    })
                    .thenAssertResults();
        }
    }
}
