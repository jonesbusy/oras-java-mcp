package io.github.jonesbusy.oras.mcp;

import java.util.Locale;
import java.util.Set;

/** Media types the server needs to tell apart. */
final class MediaTypes {

    static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
    static final String DOCKER_MANIFEST_LIST = "application/vnd.docker.distribution.manifest.list.v2+json";
    static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    static final String DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json";
    static final String OCI_IMAGE_CONFIG = "application/vnd.oci.image.config.v1+json";
    static final String DOCKER_IMAGE_CONFIG = "application/vnd.docker.container.image.v1+json";

    private static final Set<String> INDEXES = Set.of(OCI_INDEX, DOCKER_MANIFEST_LIST);
    private static final Set<String> MANIFESTS = Set.of(OCI_MANIFEST, DOCKER_MANIFEST);
    private static final Set<String> IMAGE_CONFIGS = Set.of(OCI_IMAGE_CONFIG, DOCKER_IMAGE_CONFIG);

    private MediaTypes() {}

    /** Strip parameters (such as {@code charset}) and normalize case. */
    static String normalize(String mediaType) {
        if (mediaType == null) {
            return null;
        }
        int semicolon = mediaType.indexOf(';');
        String bare = semicolon >= 0 ? mediaType.substring(0, semicolon) : mediaType;
        return bare.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isIndex(String mediaType) {
        return INDEXES.contains(normalize(mediaType));
    }

    static boolean isManifest(String mediaType) {
        return MANIFESTS.contains(normalize(mediaType));
    }

    static boolean isImageConfig(String mediaType) {
        return IMAGE_CONFIGS.contains(normalize(mediaType));
    }

    static boolean isJson(String mediaType) {
        String normalized = normalize(mediaType);
        return normalized != null && (normalized.endsWith("+json") || normalized.endsWith("/json"));
    }
}
