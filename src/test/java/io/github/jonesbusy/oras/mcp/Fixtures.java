package io.github.jonesbusy.oras.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import land.oras.Annotations;
import land.oras.ArtifactType;
import land.oras.Config;
import land.oras.ContainerRef;
import land.oras.Index;
import land.oras.LocalPath;
import land.oras.Manifest;
import land.oras.ManifestDescriptor;
import land.oras.Platform;
import land.oras.Registry;

final class Fixtures {

    static final String TEXT_ARTIFACT_TYPE = "application/vnd.example.text.v1";
    static final String SBOM_ARTIFACT_TYPE = "application/vnd.example.sbom.v1+json";
    static final String TEXT = "hello from oras-java-mcp\n";

    final String host;

    final String textArtifact;

    String textArtifactDigest;

    String textLayerDigest;

    final String multiPlatformImage;

    String amd64ConfigDigest;

    Fixtures(String host) {
        this.host = host;
        this.textArtifact = host + "/artifacts/text:1.0";
        this.multiPlatformImage = host + "/images/multi:1.0";
    }

    Fixtures push(Registry registry, Path workDir) {
        try {
            pushTextArtifact(registry, workDir);
            pushMultiPlatformImage(registry);
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void pushTextArtifact(Registry registry, Path workDir) throws IOException {
        Path text = Files.writeString(workDir.resolve("hello.txt"), TEXT);
        ContainerRef ref = ContainerRef.parse(textArtifact);
        Manifest manifest = registry.pushArtifact(
                ref,
                ArtifactType.from(TEXT_ARTIFACT_TYPE),
                Annotations.ofManifest(Map.of("org.opencontainers.image.description", "A text artifact")),
                LocalPath.of(text, "text/plain"));
        textArtifactDigest = manifest.getDescriptor().getDigest();
        textLayerDigest = manifest.getLayers().get(0).getDigest();

        Path sbom = Files.writeString(workDir.resolve("sbom.json"), "{\"bomFormat\":\"CycloneDX\"}");
        registry.attachArtifact(
                ref.withDigest(textArtifactDigest),
                ArtifactType.from(SBOM_ARTIFACT_TYPE),
                LocalPath.of(sbom, "application/vnd.cyclonedx+json"));
    }

    private void pushMultiPlatformImage(Registry registry) {
        ContainerRef ref = ContainerRef.parse(multiPlatformImage);
        Manifest amd64 = pushImage(registry, ref, "amd64", null);
        Manifest arm64 = pushImage(registry, ref, "arm64", "v8");
        amd64ConfigDigest = amd64.getConfig().getDigest();
        Index index = Index.fromManifests(List.of(
                ManifestDescriptor.of(amd64).withPlatform(Platform.linuxAmd64()),
                ManifestDescriptor.of(arm64).withPlatform(Platform.linuxArm64V8())));
        registry.pushIndex(ref, index);
    }

    private static Manifest pushImage(Registry registry, ContainerRef ref, String arch, String variant) {
        String configJson =
                """
                {"architecture":"%s",%s"os":"linux","config":{"Env":["PATH=/usr/bin"],"Labels":{"arch":"%s"}},\
                "rootfs":{"type":"layers","diff_ids":[]}}"""
                        .formatted(arch, variant == null ? "" : "\"variant\":\"%s\",".formatted(variant), arch);
        var configBlob = registry.pushBlob(ref, configJson.getBytes(StandardCharsets.UTF_8));
        Config config = Config.fromBlob(MediaTypes.OCI_IMAGE_CONFIG, configBlob);
        var layer = registry.pushBlob(ref, ("layer for " + arch).getBytes(StandardCharsets.UTF_8))
                .withMediaType("application/vnd.oci.image.layer.v1.tar");
        Manifest manifest = Manifest.empty().withConfig(config).withLayers(List.of(layer));
        return registry.pushManifest(ref.withTag(arch), manifest);
    }
}
