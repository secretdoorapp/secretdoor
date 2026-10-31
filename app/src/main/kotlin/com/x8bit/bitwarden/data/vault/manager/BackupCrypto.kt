package com.x8bit.bitwarden.data.vault.manager

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val HMAC_SHA256: String = "HmacSHA256"
private const val AES_GCM: String = "AES/GCM/NoPadding"
private const val AES_CBC: String = "AES/CBC/PKCS5Padding"
private const val GCM_TAG_BITS: Int = 128
private const val GCM_TAG_BYTES: Int = 16
private const val NONCE_PREFIX_BYTES: Int = 7
private const val AES_BLOCK_BYTES: Int = 16
private const val SYMMETRIC_KEY_BYTES: Int = 32
private const val USER_KEY_BYTES: Int = 64
private const val ENC_STRING_TYPE_AES_CBC_HMAC: String = "2."

/**
 * Size of the plaintext chunks of an encrypted backup payload.
 */
private const val CHUNK_BYTES: Int = 1 shl 20

/**
 * Cryptography used by SecretDoor backup files.
 *
 * The backup payload is encrypted with AES-256-GCM in independent chunks (the "STREAM"
 * construction): each chunk's nonce is a random per-file prefix, the chunk counter and a flag
 * marking the last chunk, so chunks cannot be reordered, dropped or truncated without detection.
 * The file header is authenticated as additional data of every chunk.
 */
internal object BackupCrypto {
    private val secureRandom = SecureRandom()

    /**
     * Returns [size] cryptographically random bytes.
     */
    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    /**
     * Returns a new random nonce prefix for [encryptingStream].
     */
    fun newNoncePrefix(): ByteArray = randomBytes(NONCE_PREFIX_BYTES)

    /**
     * Derives the 256-bit backup file key from the vault's [userKey] (HKDF-SHA256).
     */
    fun deriveBackupKey(userKey: ByteArray, salt: ByteArray): ByteArray =
        hkdfSha256(ikm = userKey, salt = salt, info = "secretdoor-backup-file".toByteArray())

    /**
     * Wraps [output] so everything written to it is encrypted with [key]. Closing the returned
     * stream writes the final chunk and closes [output].
     */
    fun encryptingStream(
        output: OutputStream,
        key: ByteArray,
        noncePrefix: ByteArray,
        associatedData: ByteArray,
    ): OutputStream = ChunkEncryptingOutputStream(
        output = output,
        key = SecretKeySpec(key, "AES"),
        noncePrefix = noncePrefix,
        associatedData = associatedData,
    )

    /**
     * Wraps [input] so reading it returns the plaintext written by [encryptingStream]. Reading
     * throws an [IOException] if the data was modified or the key is wrong.
     */
    fun decryptingStream(
        input: InputStream,
        key: ByteArray,
        noncePrefix: ByteArray,
        associatedData: ByteArray,
    ): InputStream = ChunkDecryptingInputStream(
        input = DataInputStream(input),
        key = SecretKeySpec(key, "AES"),
        noncePrefix = noncePrefix,
        associatedData = associatedData,
    )

    /**
     * Whether [value] is an AES-256-CBC + HMAC-SHA256 encrypted string (type 2).
     */
    fun isEncString(value: String): Boolean =
        value.startsWith(ENC_STRING_TYPE_AES_CBC_HMAC) && value.count { it == '|' } == 2

    /**
     * Decrypts the type 2 encrypted string [value] with the 64-byte user key [from] and encrypts
     * the plaintext again with [to]. Throws a [GeneralSecurityException] if [value] was not
     * encrypted with [from].
     */
    fun rewrapEncString(value: String, from: ByteArray, to: ByteArray): String {
        require(from.size == USER_KEY_BYTES && to.size == USER_KEY_BYTES) {
            "Unsupported key size"
        }
        val parts = value.removePrefix(ENC_STRING_TYPE_AES_CBC_HMAC).split('|')
        if (!isEncString(value) || parts.size != 3) {
            throw GeneralSecurityException("Unsupported encrypted string")
        }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[0])
        val cipherText = decoder.decode(parts[1])
        val mac = decoder.decode(parts[2])
        if (!MessageDigest.isEqual(mac, hmacSha256(macKey(from), iv + cipherText))) {
            throw GeneralSecurityException("Encrypted string does not match the key")
        }
        val plainText = Cipher.getInstance(AES_CBC).run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey(from), "AES"), IvParameterSpec(iv))
            doFinal(cipherText)
        }
        val newIv = randomBytes(AES_BLOCK_BYTES)
        val newCipherText = Cipher.getInstance(AES_CBC).run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey(to), "AES"), IvParameterSpec(newIv))
            doFinal(plainText)
        }
        val newMac = hmacSha256(macKey(to), newIv + newCipherText)
        val encoder = Base64.getEncoder()
        return ENC_STRING_TYPE_AES_CBC_HMAC +
            "${encoder.encodeToString(newIv)}|" +
            "${encoder.encodeToString(newCipherText)}|" +
            encoder.encodeToString(newMac)
    }

    /**
     * Builds the GCM nonce for chunk number [index].
     */
    internal fun nonce(noncePrefix: ByteArray, index: Int, isLast: Boolean): ByteArray =
        ByteBuffer
            .allocate(NONCE_PREFIX_BYTES + Int.SIZE_BYTES + 1)
            .put(noncePrefix)
            .putInt(index)
            .put(if (isLast) 1 else 0)
            .array()

    private fun encKey(userKey: ByteArray): ByteArray = userKey.copyOfRange(0, SYMMETRIC_KEY_BYTES)

    private fun macKey(userKey: ByteArray): ByteArray =
        userKey.copyOfRange(SYMMETRIC_KEY_BYTES, USER_KEY_BYTES)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC_SHA256).run {
            init(SecretKeySpec(key, HMAC_SHA256))
            doFinal(data)
        }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val pseudoRandomKey = hmacSha256(key = salt, data = ikm)
        // One HKDF-Expand block is exactly the 32 bytes needed.
        return hmacSha256(key = pseudoRandomKey, data = info + byteArrayOf(1))
    }
}

/**
 * Encrypts chunks of [CHUNK_BYTES]. A full chunk is only written once more data arrives, so the
 * last chunk (possibly empty) can be marked when the stream is closed.
 */
private class ChunkEncryptingOutputStream(
    private val output: OutputStream,
    private val key: SecretKeySpec,
    private val noncePrefix: ByteArray,
    private val associatedData: ByteArray,
) : OutputStream() {
    private val buffer = ByteArrayOutputStream(CHUNK_BYTES)
    private var index = 0
    private var isClosed = false

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var remaining = len
        while (remaining > 0) {
            if (buffer.size() == CHUNK_BYTES) writeChunk(isLast = false)
            val count = minOf(remaining, CHUNK_BYTES - buffer.size())
            buffer.write(b, offset, count)
            offset += count
            remaining -= count
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        output.use { writeChunk(isLast = true) }
    }

    private fun writeChunk(isLast: Boolean) {
        val cipherText = Cipher.getInstance(AES_GCM).run {
            init(
                Cipher.ENCRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, BackupCrypto.nonce(noncePrefix, index, isLast)),
            )
            updateAAD(associatedData)
            doFinal(buffer.toByteArray())
        }
        output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(cipherText.size).array())
        output.write(cipherText)
        buffer.reset()
        index++
    }
}

/**
 * Decrypts the chunks written by [ChunkEncryptingOutputStream]. The next chunk's length is read
 * ahead to know whether the current chunk is the last one.
 */
private class ChunkDecryptingInputStream(
    private val input: DataInputStream,
    private val key: SecretKeySpec,
    private val noncePrefix: ByteArray,
    private val associatedData: ByteArray,
) : InputStream() {
    private var plainText = ByteArray(0)
    private var position = 0
    private var index = 0
    private var nextLength: Int? = readLength()
    private var isFinished = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (position == plainText.size) {
            if (isFinished) return -1
            readChunk()
        }
        val count = minOf(len, plainText.size - position)
        plainText.copyInto(b, off, position, position + count)
        position += count
        return count
    }

    override fun close() {
        input.close()
    }

    private fun readChunk() {
        val length = nextLength ?: throw IOException("Backup file is truncated")
        if (length < GCM_TAG_BYTES || length > CHUNK_BYTES + GCM_TAG_BYTES) {
            throw IOException("Backup file is damaged")
        }
        val cipherText = ByteArray(length).also { input.readFullyOrThrow(it) }
        nextLength = readLength()
        val isLast = nextLength == null
        plainText = try {
            Cipher.getInstance(AES_GCM).run {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(GCM_TAG_BITS, BackupCrypto.nonce(noncePrefix, index, isLast)),
                )
                updateAAD(associatedData)
                doFinal(cipherText)
            }
        } catch (e: GeneralSecurityException) {
            throw IOException("Backup file is damaged or the key is wrong", e)
        }
        position = 0
        index++
        isFinished = isLast
    }

    private fun readLength(): Int? = try {
        input.readInt()
    } catch (_: EOFException) {
        null
    }
}

private fun DataInputStream.readFullyOrThrow(bytes: ByteArray) {
    try {
        readFully(bytes)
    } catch (e: EOFException) {
        throw IOException("Backup file is truncated", e)
    }
}
