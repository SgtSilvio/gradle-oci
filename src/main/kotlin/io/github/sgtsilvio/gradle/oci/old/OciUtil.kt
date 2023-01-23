package io.github.sgtsilvio.gradle.oci.old

import io.github.sgtsilvio.gradle.oci.internal.*

fun JsonValueStringBuilder.addOciDescriptor(mediaType: String, ociDescriptor: OciDescriptor) = addObject {
    // sorted for canonical json: annotations, data, digest, mediaType, size, urls
    addKeyAndObjectIfNotEmpty("annotations", ociDescriptor.annotations.orNull)
    addKeyAndStringIfNotNull("data", ociDescriptor.data.orNull)
    addKey("digest").addString(ociDescriptor.digest.get())
    addKey("mediaType").addString(mediaType)
    addKey("size").addNumber(ociDescriptor.size.get())
    addKeyAndArrayIfNotEmpty("urls", ociDescriptor.urls.orNull)
}

fun JsonValueStringBuilder.addOciManifestDescriptor(ociManifestDescriptor: OciManifestDescriptor) = addObject {
    // sorted for canonical json: annotations, data, digest, mediaType, size, urls
    addKeyAndObjectIfNotEmpty("annotations", ociManifestDescriptor.annotations.orNull)
    addKeyAndStringIfNotNull("data", ociManifestDescriptor.data.orNull)
    addKey("digest").addString(ociManifestDescriptor.digest.get())
    addKey("mediaType").addString(MANIFEST_MEDIA_TYPE)
    addKey("platform").addObject {
        // sorted for canonical json: architecture, os, osFeatures, osVersion, variant
        addKey("architecture").addString(ociManifestDescriptor.platform.architecture.get())
        addKey("os").addString(ociManifestDescriptor.platform.os.get())
        addKeyAndArrayIfNotEmpty("os.features", ociManifestDescriptor.platform.osFeatures.orNull)
        addKeyAndStringIfNotNull("os.version", ociManifestDescriptor.platform.osVersion.orNull)
        addKeyAndStringIfNotNull("variant", ociManifestDescriptor.platform.variant.orNull)
    }
    addKey("size").addNumber(ociManifestDescriptor.size.get())
    addKeyAndArrayIfNotEmpty("urls", ociManifestDescriptor.urls.orNull)
}