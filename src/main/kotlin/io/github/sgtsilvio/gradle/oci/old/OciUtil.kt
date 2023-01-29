package io.github.sgtsilvio.gradle.oci.old

import io.github.sgtsilvio.gradle.oci.internal.*

fun JsonObjectStringBuilder.encodeOciDescriptor(mediaType: String, ociDescriptor: OciDescriptor) {
    // sorted for canonical json: annotations, data, digest, mediaType, size, urls
    addObjectIfNotEmpty("annotations", ociDescriptor.annotations.orNull)
    addStringIfNotNull("data", ociDescriptor.data.orNull)
    addString("digest", ociDescriptor.digest.get())
    addString("mediaType", mediaType)
    addNumber("size", ociDescriptor.size.get())
    addArrayIfNotEmpty("urls", ociDescriptor.urls.orNull)
}

fun JsonObjectStringBuilder.encodeOciManifestDescriptor(ociManifestDescriptor: OciManifestDescriptor) {
    // sorted for canonical json: annotations, data, digest, mediaType, size, urls
    addObjectIfNotEmpty("annotations", ociManifestDescriptor.annotations.orNull)
    addStringIfNotNull("data", ociManifestDescriptor.data.orNull)
    addString("digest", ociManifestDescriptor.digest.get())
    addString("mediaType", MANIFEST_MEDIA_TYPE)
    addObject("platform") {
        // sorted for canonical json: architecture, os, osFeatures, osVersion, variant
        addString("architecture", ociManifestDescriptor.platform.architecture.get())
        addString("os", ociManifestDescriptor.platform.os.get())
        addArrayIfNotEmpty("os.features", ociManifestDescriptor.platform.osFeatures.orNull)
        addStringIfNotNull("os.version", ociManifestDescriptor.platform.osVersion.orNull)
        addStringIfNotNull("variant", ociManifestDescriptor.platform.variant.orNull)
    }
    addNumber("size", ociManifestDescriptor.size.get())
    addArrayIfNotEmpty("urls", ociManifestDescriptor.urls.orNull)
}