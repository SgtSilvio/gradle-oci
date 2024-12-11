package io.github.sgtsilvio.gradle.oci.layer

import io.github.sgtsilvio.gradle.oci.metadata.GZIP_COMPRESSED_LAYER_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.metadata.UNCOMPRESSED_LAYER_MEDIA_TYPE
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

enum class OciLayerCompression(internal val extension: String, internal val mediaType: String) {
    NONE("tar", UNCOMPRESSED_LAYER_MEDIA_TYPE) {
        override fun createOutputStream(out: OutputStream) = out
    },
    GZIP("tgz", GZIP_COMPRESSED_LAYER_MEDIA_TYPE) {
        override fun createOutputStream(out: OutputStream) = GZIPOutputStream(out)
//    },
//    ZSTD("tzst", ZSTD_COMPRESSED_LAYER_MEDIA_TYPE) {
//        override fun createOutputStream(out: OutputStream) = ZstdOutputStream(out)
    };

    internal abstract fun createOutputStream(out: OutputStream): OutputStream
}
