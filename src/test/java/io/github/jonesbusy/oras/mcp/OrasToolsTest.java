package io.github.jonesbusy.oras.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.oras.runtime.OrasRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import land.oras.Referrers;
import land.oras.Registry;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OrasToolsTest {

    @Inject
    OrasTools tools;

    @Inject
    @OrasRegistry("default")
    Registry registry;

    @ConfigProperty(name = "quarkus.oras.registries.default.host")
    String host;

    private static Fixtures fixtures;

    @BeforeEach
    void pushFixtures() throws Exception {
        if (fixtures == null) {
            Path workDir = Files.createTempDirectory("oras-mcp-test");
            fixtures = new Fixtures(host).push(registry, workDir);
        }
    }

    @Test
    void inspectsArtifactManifest() {
        var result = tools.inspect(fixtures.textArtifact, null, null);

        assertThat(result.kind()).isEqualTo("manifest");
        assertThat(result.registry()).isEqualTo(host);
        assertThat(result.repository()).isEqualTo("artifacts/text");
        assertThat(result.tag()).isEqualTo("1.0");
        assertThat(result.digest()).isEqualTo(fixtures.textArtifactDigest);
        assertThat(result.artifactType()).isEqualTo(Fixtures.TEXT_ARTIFACT_TYPE);
        assertThat(result.annotations()).containsEntry("org.opencontainers.image.description", "A text artifact");
        assertThat(result.layers()).singleElement().satisfies(layer -> {
            assertThat(layer.mediaType()).isEqualTo("text/plain");
            assertThat(layer.annotations()).containsEntry("org.opencontainers.image.title", "hello.txt");
        });
        assertThat(result.totalSize()).isPositive();
        assertThat(result.manifests()).isNull();
    }

    @Test
    void resolvesAndPinsReference() {
        var result = tools.resolve(fixtures.textArtifact);

        assertThat(result.resolved()).isEqualTo(fixtures.textArtifact);
        assertThat(result.pinned()).isEqualTo(host + "/artifacts/text@" + fixtures.textArtifactDigest);
        assertThat(result.tag()).isEqualTo("1.0");
        assertThat(result.digest()).isEqualTo(fixtures.textArtifactDigest);
        assertThat(result.mediaType()).isEqualTo(MediaTypes.OCI_MANIFEST);
        assertThat(result.servedBy()).isNull();
    }

    @Test
    void resolvesDigestReference() {
        var result = tools.resolve(host + "/artifacts/text@" + fixtures.textArtifactDigest);

        assertThat(result.tag()).isNull();
        assertThat(result.resolved()).isEqualTo(result.pinned());
    }

    @Test
    void inspectsIndex() {
        var result = tools.inspect(fixtures.multiPlatformImage, null, null);

        assertThat(result.kind()).isEqualTo("index");
        assertThat(result.manifests())
                .extracting(Results.Descriptor::platform)
                .containsExactly("linux/amd64", "linux/arm64/v8");
        assertThat(result.layers()).isNull();
    }

    @Test
    void inspectsPlatformManifestWithConfig() {
        var index = tools.inspect(fixtures.multiPlatformImage, null, null);
        var result = tools.inspect(fixtures.multiPlatformImage, "linux/amd64", true);

        assertThat(result.kind()).isEqualTo("manifest");
        assertThat(result.indexDigest()).isEqualTo(index.digest());
        assertThat(result.digest()).isEqualTo(index.manifests().get(0).digest());
        assertThat(result.config().digest()).isEqualTo(fixtures.amd64ConfigDigest);
        assertThat(result.configContent()).containsEntry("architecture", "amd64");
    }

    @Test
    void reportsAvailablePlatformsWhenNoneMatch() {
        assertThatThrownBy(() -> tools.inspect(fixtures.multiPlatformImage, "windows/amd64", null))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("linux/amd64")
                .hasMessageContaining("linux/arm64/v8");
    }

    @Test
    void getsRawManifest() {
        var result = tools.getManifest(fixtures.textArtifact);

        assertThat(result.digest()).isEqualTo(fixtures.textArtifactDigest);
        assertThat(result.content()).containsEntry("artifactType", Fixtures.TEXT_ARTIFACT_TYPE);
    }

    @Test
    void readsTextBlob() {
        var ref = fixtures.host + "/artifacts/text@" + fixtures.textLayerDigest;

        var full = tools.getBlob(ref, null);
        assertThat(full.content()).isEqualTo(Fixtures.TEXT);
        assertThat(full.truncated()).isFalse();

        var truncated = tools.getBlob(ref, 5);
        assertThat(truncated.content()).isEqualTo("hello");
        assertThat(truncated.truncated()).isTrue();
    }

    @Test
    void blobNeedsDigest() {
        assertThatThrownBy(() -> tools.getBlob(fixtures.textArtifact, null))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void listsTags() {
        var result = tools.listTags(fixtures.host + "/images/multi", null, null);
        assertThat(result.tags()).contains("1.0", "amd64", "arm64");
        assertThat(result.repository()).isEqualTo("images/multi");

        var firstPage = tools.listTags(fixtures.host + "/images/multi", 1, null);
        assertThat(firstPage.tags()).hasSize(1);
        assertThat(firstPage.next()).isNotNull();
    }

    @Test
    void listsRepositories() {
        var result = tools.listRepositories(fixtures.host);
        assertThat(result.repositories()).contains("artifacts/text", "images/multi");
    }

    @Test
    void listsReferrersResolvingTag() {
        var result = tools.listReferrers(fixtures.textArtifact, null);

        assertThat(result.subject()).isEqualTo(fixtures.textArtifactDigest);
        assertThat(result.referrers()).singleElement().satisfies(referrer -> assertThat(referrer.artifactType())
                .isEqualTo(Fixtures.SBOM_ARTIFACT_TYPE));

        assertThat(tools.listReferrers(fixtures.textArtifact, "application/vnd.other")
                        .referrers())
                .isEmpty();
    }

    @Test
    void treatsMissingReferrerManifestsAsEmpty() {
        assertThat(OrasTools.referrers(Referrers.fromJson(
                        "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.index.v1+json\"}")))
                .isEmpty();
        assertThat(OrasTools.referrers(null)).isEmpty();
    }

    @Test
    void listsNoReferrersForArtifactWithoutAny() {
        var result = tools.listReferrers(fixtures.multiPlatformImage, null);
        assertThat(result.referrers()).isEmpty();
    }

    @Test
    void mapsRegistryErrorsToToolErrors() {
        assertThatThrownBy(() -> tools.inspect(fixtures.host + "/does/not-exist:1", null, null))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("404");
    }
}
