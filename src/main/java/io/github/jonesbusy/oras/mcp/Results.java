package io.github.jonesbusy.oras.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import java.util.Map;

/** Tool results, serialized as MCP structured content. Null fields are omitted to save tokens. */
final class Results {

    private Results() {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Descriptor(
            String mediaType,
            String digest,
            Long size,
            String artifactType,
            @JsonPropertyDescription("os/architecture[/variant], for index entries") String platform,
            Map<String, String> annotations) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Inspection(
            @JsonPropertyDescription("The reference as given") String reference,
            @JsonPropertyDescription("Registry host the reference resolved to") String registry,
            @JsonPropertyDescription("Repository path, without registry") String repository,
            String tag,
            @JsonPropertyDescription("Digest of the inspected manifest or index") String digest,
            String mediaType,
            Long size,
            @JsonPropertyDescription("index or manifest") String kind,
            String artifactType,
            Map<String, String> annotations,
            @JsonPropertyDescription("Descriptor this artifact refers to (OCI subject), if any") Descriptor subject,
            @JsonPropertyDescription("When a platform was selected from an index, the digest of that index")
                    String indexDigest,
            @JsonPropertyDescription("Index only: the manifests it lists") List<Descriptor> manifests,
            @JsonPropertyDescription("Manifest only: the config descriptor") Descriptor config,
            @JsonPropertyDescription("Manifest only: the layers") List<Descriptor> layers,
            @JsonPropertyDescription("Manifest only: sum of config and layer sizes, in bytes") Long totalSize,
            @JsonPropertyDescription("Image config content, when includeConfig is set and the config is JSON")
                    Map<String, Object> configContent) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Resolution(
            @JsonPropertyDescription("The reference as given") String reference,
            @JsonPropertyDescription("Fully qualified reference after unqualified search and registries.conf rewrites")
                    String resolved,
            @JsonPropertyDescription("Fully qualified reference pinned to the current digest") String pinned,
            String registry,
            String repository,
            String tag,
            String digest,
            String mediaType,
            @JsonPropertyDescription("Mirror that actually answered, when different from the registry")
                    String servedBy) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record ManifestDocument(
            String reference,
            String digest,
            String mediaType,
            Long size,
            @JsonPropertyDescription("The manifest or index JSON as served by the registry")
                    Map<String, Object> content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Blob(
            String reference,
            String digest,
            Long size,
            @JsonPropertyDescription("True when only the first maxBytes bytes were returned") boolean truncated,
            @JsonPropertyDescription("Blob content decoded as UTF-8") String content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TagPage(
            String repository,
            List<String> tags,
            @JsonPropertyDescription("Pass as 'last' to fetch the next page; absent on the last page") String next) {}

    record RepositoryList(String registry, List<String> repositories) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReferrerList(
            String reference,
            @JsonPropertyDescription("Digest of the subject the referrers point to") String subject,
            String artifactType,
            List<Descriptor> referrers) {}
}
