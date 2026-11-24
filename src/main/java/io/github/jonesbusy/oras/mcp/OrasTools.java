package io.github.jonesbusy.oras.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.Tool.Annotations;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.oras.runtime.OrasRegistry;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import land.oras.ArtifactType;
import land.oras.Config;
import land.oras.ContainerRef;
import land.oras.Descriptor;
import land.oras.Index;
import land.oras.Layer;
import land.oras.Manifest;
import land.oras.ManifestDescriptor;
import land.oras.Platform;
import land.oras.Referrers;
import land.oras.Registry;
import land.oras.Subject;
import land.oras.exception.OrasException;

/**
 * Read-only MCP tools over OCI registries. Credentials come from the registry {@code defaults()}
 * (docker/podman auth files and credential helpers); this server never handles secrets itself.
 */
public class OrasTools {

    static final int DEFAULT_BLOB_BYTES = 64 * 1024;
    static final int MAX_BLOB_BYTES = 1024 * 1024;
    static final int DEFAULT_TAG_PAGE = 100;
    static final int MAX_TAG_PAGE = 1000;

    private static final String REFERENCE_HELP =
            "OCI reference: [registry/]repository[:tag|@digest], e.g. ghcr.io/oras-project/oras:v1.2.0."
                    + " Unqualified names resolve like docker/podman (docker.io/library/...).";

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    @Inject
    @OrasRegistry("default")
    Registry registry;

    @Inject
    ObjectMapper mapper;

    @Tool(
            name = "inspect",
            description = "Inspect an OCI artifact or image: resolves the reference and summarizes the manifest"
                    + " (config, layers, artifactType, annotations, subject) or index (one entry per platform"
                    + " or artifact). Start here for any question about what a reference points to.",
            annotations = @Annotations(title = "Inspect OCI artifact", readOnlyHint = true, openWorldHint = true),
            structuredContent = true)
    Results.Inspection inspect(
            @ToolArg(description = REFERENCE_HELP) String reference,
            @ToolArg(
                            description = "When the reference is an index, inspect the manifest for this platform"
                                    + " instead, as os/architecture[/variant] (e.g. linux/arm64)",
                            required = false)
                    String platform,
            @ToolArg(
                            description = "Also fetch and return the image config JSON (entrypoint, env, labels,"
                                    + " history...) for a manifest whose config is JSON",
                            required = false)
                    Boolean includeConfig) {
        return call(() -> {
            ContainerRef ref = parse(reference);
            Descriptor descriptor = registry.getDescriptor(ref);
            String mediaType = mediaTypeOf(descriptor);
            if (MediaTypes.isIndex(mediaType)) {
                Index index = Index.fromJson(descriptor.getJson());
                if (blank(platform)) {
                    return inspection(reference, ref, descriptor, mediaType, index);
                }
                ManifestDescriptor selected = select(index, platform);
                ContainerRef child = ref.withDigest(selected.getDigest());
                Descriptor childDescriptor = registry.getDescriptor(child);
                String childMediaType = mediaTypeOf(childDescriptor);
                if (!MediaTypes.isManifest(childMediaType)) {
                    throw new ToolCallException("Platform entry %s is a %s, not an image manifest; inspect it by digest"
                            .formatted(selected.getDigest(), childMediaType));
                }
                return inspection(
                        reference,
                        child,
                        childDescriptor,
                        childMediaType,
                        Manifest.fromJson(childDescriptor.getJson()),
                        digestOf(ref, descriptor),
                        Boolean.TRUE.equals(includeConfig));
            }
            if (MediaTypes.isManifest(mediaType)) {
                if (!blank(platform)) {
                    throw new ToolCallException(
                            "'%s' is a single manifest, not an index; omit 'platform'".formatted(reference));
                }
                return inspection(
                        reference,
                        ref,
                        descriptor,
                        mediaType,
                        Manifest.fromJson(descriptor.getJson()),
                        null,
                        Boolean.TRUE.equals(includeConfig));
            }
            throw new ToolCallException(
                    "Unsupported manifest media type '%s' for '%s'".formatted(mediaType, reference));
        });
    }

    @Tool(
            name = "resolve",
            description = "Resolve a reference the way docker/podman would (unqualified search, registries.conf"
                    + " aliases and rewrites) and pin it to its current digest. Cheap: a single HEAD request.",
            annotations = @Annotations(title = "Resolve reference", readOnlyHint = true, openWorldHint = true),
            structuredContent = true)
    Results.Resolution resolve(@ToolArg(description = REFERENCE_HELP) String reference) {
        return call(() -> {
            ContainerRef ref = registry.getRegistriesConf().rewrite(parse(reference));
            String host = registryOf(ref);
            String repository = repositoryOf(ref);
            String tag = ref.getDigest() == null ? ref.getTag() : null;
            Descriptor descriptor = registry.probeDescriptor(ref);
            String digest = descriptor.getDigest();
            String mediaType = MediaTypes.normalize(descriptor.getMediaType());
            if (digest == null) {
                // Some registries omit Docker-Content-Digest on HEAD: fall back to a GET.
                Descriptor full = registry.getDescriptor(ref);
                digest = digestOf(ref, full);
                mediaType = mediaTypeOf(full);
            }
            String name = host + "/" + repository;
            String servedBy = descriptor.getRegistry();
            return new Results.Resolution(
                    reference,
                    tag == null ? name + "@" + digest : name + ":" + tag,
                    name + "@" + digest,
                    host,
                    repository,
                    tag,
                    digest,
                    mediaType,
                    servedBy == null || servedBy.equals(host) ? null : servedBy);
        });
    }

    @Tool(
            name = "get_manifest",
            description = "Fetch the raw manifest or index JSON of a reference, exactly as the registry serves it."
                    + " Prefer 'inspect' unless every field is needed.",
            annotations = @Annotations(title = "Get manifest", readOnlyHint = true, openWorldHint = true),
            structuredContent = true)
    Results.ManifestDocument getManifest(@ToolArg(description = REFERENCE_HELP) String reference) {
        return call(() -> {
            ContainerRef ref = parse(reference);
            Descriptor descriptor = registry.getDescriptor(ref);
            return new Results.ManifestDocument(
                    reference,
                    digestOf(ref, descriptor),
                    mediaTypeOf(descriptor),
                    descriptor.getSize(),
                    readJson(descriptor.getJson()));
        });
    }

    @Tool(
            name = "get_blob",
            description = "Read a text blob (config JSON, SBOM, signature payload, small text layer) by digest."
                    + " Binary content such as compressed layers is refused.",
            annotations = @Annotations(title = "Get blob", readOnlyHint = true, openWorldHint = true),
            structuredContent = true)
    Results.Blob getBlob(
            @ToolArg(description = "Blob reference with a digest: [registry/]repository@sha256:...") String reference,
            @ToolArg(
                            description = "Maximum bytes to return (default " + DEFAULT_BLOB_BYTES + ", max "
                                    + MAX_BLOB_BYTES + "); longer content is truncated",
                            required = false)
                    Integer maxBytes) {
        return call(() -> {
            ContainerRef ref = parse(reference);
            if (ref.getDigest() == null) {
                throw new ToolCallException("A blob reference needs a digest: repository@sha256:...");
            }
            int limit = clamp(maxBytes, DEFAULT_BLOB_BYTES, MAX_BLOB_BYTES);
            Descriptor descriptor = registry.fetchBlobDescriptor(ref);
            byte[] bytes;
            try (InputStream in = registry.fetchBlob(ref)) {
                bytes = in.readNBytes(limit);
            } catch (IOException e) {
                throw new OrasException("Failed to read blob " + ref.getDigest(), e);
            }
            Long size = descriptor.getSize();
            boolean truncated = size != null ? size > bytes.length : bytes.length == limit;
            return new Results.Blob(reference, ref.getDigest(), size, truncated, decodeText(bytes, truncated));
        });
    }

    @Tool(
            name = "list_tags",
            description = "List tags of a repository, one page at a time, in the order the registry returns them.",
            annotations = @Annotations(title = "List tags", readOnlyHint = true, openWorldHint = true),
            structuredContent = true)
    Results.TagPage listTags(
            @ToolArg(description = "Repository: [registry/]repository, e.g. ghcr.io/oras-project/oras")
                    String repository,
            @ToolArg(
                            description = "Page size (default " + DEFAULT_TAG_PAGE + ", max " + MAX_TAG_PAGE + ")",
                            required = false)
                    Integer pageSize,
            @ToolArg(
                            description = "Return tags after this one: the 'next' value of the previous page",
                            required = false)
                    String last) {
        return call(() -> {
            ContainerRef ref = parse(repository);
            int n = clamp(pageSize, DEFAULT_TAG_PAGE, MAX_TAG_PAGE);
            var tags = registry.getTags(ref, n, blank(last) ? null : last);
            List<String> names = tags.tags() == null ? List.of() : tags.tags();
            String next = tags.last();
            if (next == null && names.size() >= n) {
                next = names.get(names.size() - 1);
            }
            return new Results.TagPage(repositoryOf(ref), names, next);
        });
    }

    @Tool(
            name = "list_repositories",
            description = "List repositories of a registry via the catalog API. Many public registries"
                    + " (Docker Hub, GHCR) do not allow it.",
            annotations = @Annotations(title = "List repositories", readOnlyHint = true, openWorldHint = true),
            structuredContent = true)
    Results.RepositoryList listRepositories(
            @ToolArg(description = "Registry host, with port if any, e.g. localhost:5000") String registry) {
        return call(() -> {
            if (blank(registry) || registry.contains("/")) {
                throw new ToolCallException(
                        "Expected a registry host such as 'localhost:5000', got '%s'".formatted(registry));
            }
            var repositories = this.registry.copy(registry).getRepositories().repositories();
            return new Results.RepositoryList(registry, repositories == null ? List.of() : repositories);
        });
    }

    @Tool(
            name = "list_referrers",
            description = "List artifacts attached to a subject through the OCI referrers API: signatures,"
                    + " SBOMs, attestations, scan reports. Tags are resolved to their digest first.",
            annotations = @Annotations(title = "List referrers", readOnlyHint = true, openWorldHint = true),
            structuredContent = true)
    Results.ReferrerList listReferrers(
            @ToolArg(description = REFERENCE_HELP) String reference,
            @ToolArg(
                            description = "Only return referrers of this artifactType,"
                                    + " e.g. application/vnd.dev.cosign.artifact.sig.v1+json",
                            required = false)
                    String artifactType) {
        return call(() -> {
            ContainerRef ref = parse(reference);
            if (ref.getDigest() == null) {
                ref = ref.withDigest(registry.probeDescriptor(ref).getDigest());
            }
            ArtifactType type = blank(artifactType) ? null : ArtifactType.from(artifactType);
            List<Results.Descriptor> referrers = referrers(registry.getReferrers(ref, type));
            return new Results.ReferrerList(
                    reference, ref.getDigest(), blank(artifactType) ? null : artifactType, referrers);
        });
    }

    private Results.Inspection inspection(
            String reference, ContainerRef ref, Descriptor descriptor, String mediaType, Index index) {
        List<Results.Descriptor> manifests = orEmpty(index.getManifests()).stream()
                .map(OrasTools::descriptor)
                .toList();
        return new Results.Inspection(
                reference,
                registryOf(ref),
                repositoryOf(ref),
                ref.getDigest() == null ? ref.getTag() : null,
                digestOf(ref, descriptor),
                mediaType,
                descriptor.getSize(),
                "index",
                index.getArtifactTypeAsString(),
                index.getAnnotations(),
                subject(index.getSubject()),
                null,
                manifests,
                null,
                null,
                null,
                null);
    }

    private Results.Inspection inspection(
            String reference,
            ContainerRef ref,
            Descriptor descriptor,
            String mediaType,
            Manifest manifest,
            String indexDigest,
            boolean includeConfig) {
        var config = manifest.getConfig() == null ? Config.empty() : manifest.getConfig();
        List<Layer> manifestLayers = orEmpty(manifest.getLayers());
        List<Results.Descriptor> layers =
                manifestLayers.stream().map(OrasTools::descriptor).toList();
        long totalSize = (config.getSize() == null ? 0 : config.getSize())
                + manifestLayers.stream()
                        .map(Layer::getSize)
                        .mapToLong(s -> s == null ? 0 : s)
                        .sum();
        Map<String, Object> configContent = null;
        if (includeConfig && MediaTypes.isJson(config.getMediaType())) {
            long configSize = config.getSize() == null ? 0 : config.getSize();
            if (configSize > MAX_BLOB_BYTES) {
                throw new ToolCallException("Config blob is %d bytes, over the %d byte limit; use get_blob"
                        .formatted(configSize, MAX_BLOB_BYTES));
            }
            byte[] bytes = registry.getBlob(ref.withDigest(config.getDigest()));
            configContent = readJson(new String(bytes, StandardCharsets.UTF_8));
        }
        String digest = ref.getDigest() != null ? ref.getDigest() : digestOf(ref, descriptor);
        return new Results.Inspection(
                reference,
                registryOf(ref),
                repositoryOf(ref),
                indexDigest == null && ref.getDigest() == null ? ref.getTag() : null,
                digest,
                mediaType,
                descriptor.getSize(),
                "manifest",
                manifest.getArtifactTypeAsString(),
                manifest.getAnnotations(),
                subject(manifest.getSubject()),
                indexDigest,
                null,
                new Results.Descriptor(
                        config.getMediaType(), config.getDigest(), config.getSize(), null, null, Map.of()),
                layers,
                totalSize,
                configContent);
    }

    /** Pick the single index entry for an os/architecture[/variant] platform string. */
    static ManifestDescriptor select(Index index, String platform) {
        String[] parts = platform.trim().toLowerCase(Locale.ROOT).split("/");
        if (parts.length < 2 || parts.length > 3) {
            throw new ToolCallException("Platform must be os/architecture[/variant], got '%s'".formatted(platform));
        }
        String variant = parts.length == 3 ? parts[2] : null;
        List<ManifestDescriptor> entries = orEmpty(index.getManifests());
        List<ManifestDescriptor> matches = entries.stream()
                .filter(m -> {
                    Platform p = m.getPlatformOrNull();
                    return p != null
                            && parts[0].equalsIgnoreCase(p.os())
                            && parts[1].equalsIgnoreCase(p.architecture())
                            && (variant == null || variant.equalsIgnoreCase(p.variant()));
                })
                .toList();
        if (matches.isEmpty()) {
            List<String> available = entries.stream()
                    .map(m -> platformOf(m.getPlatformOrNull()))
                    .filter(p -> p != null)
                    .distinct()
                    .toList();
            throw new ToolCallException("No manifest for platform '%s'; available: %s".formatted(platform, available));
        }
        if (matches.size() > 1) {
            throw new ToolCallException("Platform '%s' matches %d manifests (%s); add the variant"
                    .formatted(
                            platform,
                            matches.size(),
                            matches.stream()
                                    .map(m -> platformOf(m.getPlatformOrNull()))
                                    .toList()));
        }
        return matches.get(0);
    }

    /** Registries without referrers answer with an index that may have no 'manifests' field. */
    static List<Results.Descriptor> referrers(Referrers referrers) {
        if (referrers == null) {
            return List.of();
        }
        return orEmpty(referrers.getManifests()).stream()
                .map(OrasTools::descriptor)
                .toList();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    static Results.Descriptor descriptor(ManifestDescriptor m) {
        return new Results.Descriptor(
                m.getMediaType(),
                m.getDigest(),
                m.getSize(),
                m.getArtifactType(),
                platformOf(m.getPlatformOrNull()),
                m.getAnnotations());
    }

    static Results.Descriptor descriptor(Layer layer) {
        return new Results.Descriptor(
                layer.getMediaType(), layer.getDigest(), layer.getSize(), null, null, layer.getAnnotations());
    }

    static Results.Descriptor subject(Subject subject) {
        if (subject == null) {
            return null;
        }
        return new Results.Descriptor(
                subject.getMediaType(), subject.getDigest(), subject.getSize(), null, null, Map.of());
    }

    static String platformOf(Platform platform) {
        if (platform == null || Platform.unspecified(platform)) {
            return null;
        }
        String value = platform.os() + "/" + platform.architecture();
        return blank(platform.variant()) ? value : value + "/" + platform.variant();
    }

    /** Decode as UTF-8 text, refusing binary content. */
    static String decodeText(byte[] bytes, boolean truncated) {
        for (byte b : bytes) {
            if (b == 0) {
                throw new ToolCallException("Blob is binary (contains NUL bytes); only text blobs can be read");
            }
        }
        // A truncated read may end in the middle of a multi-byte character: tolerate it at the very end only.
        int length = bytes.length;
        for (int trim = 0; trim <= (truncated ? 3 : 0) && trim <= bytes.length; trim++) {
            try {
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, length - trim))
                        .toString();
            } catch (CharacterCodingException e) {
                // try with fewer trailing bytes
            }
        }
        throw new ToolCallException(
                "Blob is not UTF-8 text (probably a compressed layer); only text blobs can be read");
    }

    private Map<String, Object> readJson(String json) {
        try {
            return mapper.readValue(json, JSON_OBJECT);
        } catch (IOException e) {
            throw new ToolCallException("Content is not a JSON object: " + e.getMessage());
        }
    }

    private String registryOf(ContainerRef ref) {
        return ref.getEffectiveRegistry(registry);
    }

    private String repositoryOf(ContainerRef ref) {
        return ref.getFullRepository(registry);
    }

    private static String digestOf(ContainerRef ref, Descriptor descriptor) {
        if (descriptor.getDigest() != null) {
            return descriptor.getDigest();
        }
        if (ref.getDigest() != null) {
            return ref.getDigest();
        }
        return ref.getAlgorithm().digest(descriptor.getJson().getBytes(StandardCharsets.UTF_8));
    }

    private String mediaTypeOf(Descriptor descriptor) {
        String mediaType = MediaTypes.normalize(descriptor.getMediaType());
        if (mediaType == null || !(MediaTypes.isIndex(mediaType) || MediaTypes.isManifest(mediaType))) {
            // Some registries answer with a generic content type: fall back to the document's own mediaType.
            Object fromJson = descriptor.getJson() == null ? null : sniffMediaType(descriptor.getJson());
            if (fromJson instanceof String s) {
                return MediaTypes.normalize(s);
            }
        }
        return mediaType;
    }

    private Object sniffMediaType(String json) {
        try {
            return mapper.readTree(json).path("mediaType").textValue();
        } catch (IOException e) {
            return null;
        }
    }

    private static ContainerRef parse(String reference) {
        if (blank(reference)) {
            throw new ToolCallException("A reference is required");
        }
        try {
            return ContainerRef.parse(reference.trim());
        } catch (RuntimeException e) {
            throw new ToolCallException("Invalid reference '%s': %s".formatted(reference, e.getMessage()));
        }
    }

    private static int clamp(Integer value, int defaultValue, int max) {
        if (value == null) {
            return defaultValue;
        }
        if (value < 1) {
            throw new ToolCallException("Expected a positive number, got " + value);
        }
        return Math.min(value, max);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Turn registry and SDK failures into tool errors the agent can read, instead of protocol errors. */
    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (ToolCallException e) {
            throw e;
        } catch (OrasException e) {
            Integer status = e.getStatusCode();
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new ToolCallException(status == null ? message : "HTTP %d: %s".formatted(status, message), e);
        } catch (RuntimeException e) {
            throw new ToolCallException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }
}
