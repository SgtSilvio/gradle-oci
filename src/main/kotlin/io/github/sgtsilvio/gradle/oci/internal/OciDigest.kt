package io.github.sgtsilvio.gradle.oci.internal

import org.apache.commons.codec.binary.Hex
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

// https://github.com/opencontainers/image-spec/blob/main/descriptor.md#registered-algorithms
// https://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#MessageDigest
enum class OciDigestAlgorithm(val algorithmName: String, val ociPrefix: String) {
    SHA_256("SHA-256", "sha256:"),
    SHA_512("SHA-512", "sha512:");

    fun decode(string: String): ByteArray = Hex.decodeHex(string)
    fun encode(bytes: ByteArray): String = Hex.encodeHexString(bytes)
    fun createMessageDigest(): MessageDigest = MessageDigest.getInstance(algorithmName)
}

data class OciDigest(val algorithm: OciDigestAlgorithm, val hash: ByteArray) {
    override fun equals(other: Any?) = when {
        this === other -> true
        other !is OciDigest -> false
        algorithm != other.algorithm -> false
        !hash.contentEquals(other.hash) -> false
        else -> true
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + hash.contentHashCode()
        return result
    }

    override fun toString() = algorithm.ociPrefix + algorithm.encode(hash)
}

fun String.toOciDigest() = when {
    startsWith(OciDigestAlgorithm.SHA_256.ociPrefix) -> OciDigestAlgorithm.SHA_256
    startsWith(OciDigestAlgorithm.SHA_512.ociPrefix) -> OciDigestAlgorithm.SHA_512
    else -> throw IllegalArgumentException("unsupported digest algorithm in digest '$this'")
}.let { algorithm -> OciDigest(algorithm, algorithm.decode(substring(algorithm.ociPrefix.length))) }

fun ByteArray.calculateOciDigest(algorithm: OciDigestAlgorithm) =
    OciDigest(algorithm, algorithm.createMessageDigest().digest(this))

@OptIn(ExperimentalContracts::class)
inline fun OutputStream.calculateOciDigest(
    algorithm: OciDigestAlgorithm,
    block: (DigestOutputStream) -> Unit,
): OciDigest {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val messageDigest = algorithm.createMessageDigest()
    DigestOutputStream(this, messageDigest).use { block.invoke(it) }
    return OciDigest(algorithm, messageDigest.digest())
}
