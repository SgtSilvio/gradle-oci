package io.github.sgtsilvio.gradle.oci.metadata

import io.github.sgtsilvio.gradle.oci.internal.json.JsonObject
import org.apache.commons.codec.binary.Hex
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

// id: https://github.com/opencontainers/image-spec/blob/main/descriptor.md#registered-algorithms
// hashAlgorithmName: https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html#messagedigest-algorithms
enum class OciDigestAlgorithm(val id: String, private val hashAlgorithmName: String, private val hashByteSize: Int) {
    SHA_256("sha256", "SHA-256", 32),
    SHA_512("sha512", "SHA-512", 64);

    internal fun encodeHash(hash: ByteArray): String = Hex.encodeHexString(validateHash(hash))

    internal fun decodeHash(encodedHash: String): ByteArray = Hex.decodeHex(validateEncodedHash(encodedHash))

    internal fun validateHash(hash: ByteArray): ByteArray {
        if (hash.size != hashByteSize) {
            throw IllegalArgumentException("\"${Hex.encodeHexString(hash)}\" is not a valid OCI $id digest hash: it must have size $hashByteSize.")
        }
        return hash
    }

    private fun validateEncodedHash(encodedHash: String): String {
        if (encodedHash.length != (hashByteSize * 2)) {
            throw IllegalArgumentException("\"$encodedHash\" is not a valid OCI $id digest encoded hash: it must have length ${hashByteSize * 2}.")
        }
        if (!encodedHash.all { c -> ((c >= '0') && (c <= '9')) || ((c >= 'a') && (c <= 'f')) }) {
            throw IllegalArgumentException("\"$encodedHash\" is not a valid OCI $id digest encoded hash: it must match `[a-f0-9]`.")
        }
        return encodedHash
    }

    internal fun createMessageDigest(): MessageDigest = MessageDigest.getInstance(hashAlgorithmName)

    override fun toString() = id
}

class OciDigest(val algorithm: OciDigestAlgorithm, val hash: ByteArray) {
    val encodedHash get() = algorithm.encodeHash(hash)

    init {
        algorithm.validateHash(hash)
    }

    override fun equals(other: Any?) =
        (this === other) || ((other is OciDigest) && (algorithm == other.algorithm) && hash.contentEquals(other.hash))

    override fun hashCode() = algorithm.hashCode() * 31 + hash.contentHashCode()

    override fun toString() = "${algorithm.id}:$encodedHash"
}

internal fun String.toOciDigest(): OciDigest {
    val colonIndex = indexOf(':')
    if (colonIndex == -1) {
        throw IllegalArgumentException("\"$this\" is not a valid OCI digest: it must contain a ':' character.")
    }
    val algorithm = when (val algorithmId = substring(0, colonIndex)) {
        OciDigestAlgorithm.SHA_256.id -> OciDigestAlgorithm.SHA_256
        OciDigestAlgorithm.SHA_512.id -> OciDigestAlgorithm.SHA_512
        else -> throw IllegalArgumentException("\"$algorithmId\" is not a supported OCI digest algorithm.")
    }
    return OciDigest(algorithm, algorithm.decodeHash(substring(colonIndex + 1)))
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
    DigestOutputStream(this, messageDigest).use(block)
    return OciDigest(algorithm, messageDigest.digest())
}

internal fun JsonObject.getOciDigest(key: String) = get(key) { asString().toOciDigest() }
