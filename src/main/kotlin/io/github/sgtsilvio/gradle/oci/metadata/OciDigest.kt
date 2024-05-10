package io.github.sgtsilvio.gradle.oci.metadata

import io.github.sgtsilvio.gradle.oci.internal.json.JsonObject
import org.apache.commons.codec.binary.Hex
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.Serializable
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

// id: https://github.com/opencontainers/image-spec/blob/main/descriptor.md#registered-algorithms
// standardName: https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html#messagedigest-algorithms
enum class OciDigestAlgorithm(val id: String, val standardName: String, private val hashByteLength: Int) {
    SHA_256("sha256", "SHA-256", 32),
    SHA_512("sha512", "SHA-512", 64);

    internal fun decode(encodedHash: String): ByteArray = Hex.decodeHex(checkEncodedHash(encodedHash))

    private fun checkEncodedHash(encodedHash: String): String {
        if (encodedHash.length == (hashByteLength * 2)) return encodedHash
        throw IllegalArgumentException("encoded hash '$encodedHash' has wrong length ${encodedHash.length}, $standardName requires ${hashByteLength * 2}")
    }

    internal fun encode(hash: ByteArray): String = Hex.encodeHexString(checkHash(hash))

    internal fun checkHash(hash: ByteArray): ByteArray {
        if (hash.size == hashByteLength) return hash
        throw IllegalArgumentException("hash has wrong length ${hash.size}, $standardName requires $hashByteLength")
    }

    internal fun createMessageDigest(): MessageDigest = MessageDigest.getInstance(standardName)
}

data class OciDigest(val algorithm: OciDigestAlgorithm, val hash: ByteArray) : Serializable {
    val encodedHash get() = algorithm.encode(hash)

    init {
        algorithm.checkHash(hash)
    }

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

    override fun toString() = algorithm.id + ":" + encodedHash
}

internal fun String.toOciDigest(): OciDigest {
    val colonIndex = indexOf(':')
    if (colonIndex == -1) {
        throw IllegalArgumentException("missing ':' in digest '$this'")
    }
    val algorithm = when (substring(0, colonIndex)) {
        OciDigestAlgorithm.SHA_256.id -> OciDigestAlgorithm.SHA_256
        OciDigestAlgorithm.SHA_512.id -> OciDigestAlgorithm.SHA_512
        else -> throw IllegalArgumentException("unsupported algorithm in digest '$this'")
    }
    return OciDigest(algorithm, algorithm.decode(substring(colonIndex + 1)))
}

internal fun MessageDigest.toOciDigest(): OciDigest {
    val algorithm = when (algorithm) {
        OciDigestAlgorithm.SHA_256.standardName -> OciDigestAlgorithm.SHA_256
        OciDigestAlgorithm.SHA_512.standardName -> OciDigestAlgorithm.SHA_512
        else -> throw IllegalArgumentException("unsupported digest algorithm '$algorithm'")
    }
    return OciDigest(algorithm, digest())
}

internal fun ByteArray.calculateOciDigest(algorithm: OciDigestAlgorithm) =
    OciDigest(algorithm, algorithm.createMessageDigest().digest(this))

@OptIn(ExperimentalContracts::class)
internal inline fun OutputStream.calculateOciDigest(
    algorithm: OciDigestAlgorithm,
    block: (DigestOutputStream) -> Unit,
): OciDigest {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val messageDigest = algorithm.createMessageDigest()
    DigestOutputStream(this, messageDigest).use { block(it) }
    return OciDigest(algorithm, messageDigest.digest())
}

private const val BUFFER_SIZE = 4096

internal fun File.calculateOciDigests(algorithms: Iterable<OciDigestAlgorithm>): List<OciDigest> {
    val messageDigests = algorithms.map { it.createMessageDigest() }
    FileInputStream(this).use { inputStream ->
        val buffer = ByteArray(BUFFER_SIZE)
        var read = inputStream.read(buffer, 0, BUFFER_SIZE)
        while (read > -1) {
            for (messageDigest in messageDigests) {
                messageDigest.update(buffer, 0, read)
            }
            read = inputStream.read(buffer, 0, BUFFER_SIZE)
        }
    }
    return messageDigests.map { it.toOciDigest() }
}

internal fun JsonObject.getOciDigest(key: String) = get(key) { asString().toOciDigest() }
