package io.github.sgtsilvio.gradle.oci.metadata

internal const val INDEX_MEDIA_TYPE = "application/vnd.oci.image.index.v1+json"
internal const val MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json"
internal const val CONFIG_MEDIA_TYPE = "application/vnd.oci.image.config.v1+json"
internal const val LAYER_MEDIA_TYPE_PREFIX = "application/vnd.oci.image.layer.v1"
internal const val UNCOMPRESSED_LAYER_MEDIA_TYPE = "$LAYER_MEDIA_TYPE_PREFIX.tar"
internal const val GZIP_COMPRESSED_LAYER_MEDIA_TYPE = "$LAYER_MEDIA_TYPE_PREFIX.tar+gzip"
//internal const val ZSTD_COMPRESSED_LAYER_MEDIA_TYPE = "$LAYER_MEDIA_TYPE_PREFIX.tar+zstd"
