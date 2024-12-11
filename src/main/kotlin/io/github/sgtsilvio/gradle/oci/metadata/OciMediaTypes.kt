package io.github.sgtsilvio.gradle.oci.metadata

internal const val INDEX_MEDIA_TYPE = "application/vnd.oci.image.index.v1+json"
internal const val MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json"
internal const val CONFIG_MEDIA_TYPE = "application/vnd.oci.image.config.v1+json"
internal const val UNCOMPRESSED_LAYER_MEDIA_TYPE = "application/vnd.oci.image.layer.v1.tar"
internal const val GZIP_COMPRESSED_LAYER_MEDIA_TYPE = "$UNCOMPRESSED_LAYER_MEDIA_TYPE+gzip"
//internal const val ZSTD_COMPRESSED_LAYER_MEDIA_TYPE = "UNCOMPRESSED_LAYER_MEDIA_TYPE+zstd"

internal const val DOCKER_MANIFEST_LIST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.list.v2+json"
internal const val DOCKER_MANIFEST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.v2+json"
internal const val DOCKER_CONFIG_MEDIA_TYPE = "application/vnd.docker.container.image.v1+json"
internal const val DOCKER_LAYER_MEDIA_TYPE = "application/vnd.docker.image.rootfs.diff.tar.gzip"
