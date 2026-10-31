package com.x8bit.bitwarden.data.vault.manager

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException

class BackupCryptoTest {

    @Test
    fun `encrypted payloads larger than one chunk decrypt to the original bytes`() {
        val plainText = ByteArray(2_500_000) { (it % 251).toByte() }

        val cipherText = encrypt(plainText)

        assertFalse(cipherText.toList().windowed(16).contains(plainText.take(16)))
        assertArrayEquals(plainText, decrypt(cipherText))
    }

    @Test
    fun `an empty payload decrypts to nothing`() {
        assertArrayEquals(ByteArray(0), decrypt(encrypt(ByteArray(0))))
    }

    @Test
    fun `decrypting with the wrong key fails`() {
        val cipherText = encrypt("secret".toByteArray())

        assertThrows<IOException> { decrypt(cipherText, key = ByteArray(32) { 9 }) }
    }

    @Test
    fun `decrypting with different associated data fails`() {
        val cipherText = encrypt("secret".toByteArray())

        assertThrows<IOException> { decrypt(cipherText, associatedData = "other".toByteArray()) }
    }

    @Test
    fun `a modified payload fails to decrypt`() {
        val cipherText = encrypt("secret".toByteArray())
        cipherText[cipherText.size - 1] = (cipherText.last() + 1).toByte()

        assertThrows<IOException> { decrypt(cipherText) }
    }

    @Test
    fun `a payload cut off after a full chunk fails to decrypt`() {
        val cipherText = encrypt(ByteArray(1_100_000))
        // The first chunk: a 4-byte length, one MiB of data and the 16-byte tag.
        val firstChunkOnly = cipherText.copyOf(4 + (1 shl 20) + 16)

        assertThrows<IOException> { decrypt(firstChunkOnly) }
    }

    @Test
    fun `deriveBackupKey depends on the user key and the salt`() {
        val key = BackupCrypto.deriveBackupKey(userKey = USER_KEY_A, salt = SALT)

        assertEquals(32, key.size)
        assertArrayEquals(key, BackupCrypto.deriveBackupKey(userKey = USER_KEY_A, salt = SALT))
        assertNotEquals(
            key.toList(),
            BackupCrypto.deriveBackupKey(userKey = USER_KEY_B, salt = SALT).toList(),
        )
        assertNotEquals(
            key.toList(),
            BackupCrypto.deriveBackupKey(userKey = USER_KEY_A, salt = ByteArray(32)).toList(),
        )
    }

    @Test
    fun `rewrapEncString moves an encrypted string to another key`() {
        val original = encString(plainText = "my password", userKey = USER_KEY_A)

        val rewrapped = BackupCrypto.rewrapEncString(
            value = original,
            from = USER_KEY_A,
            to = USER_KEY_B,
        )

        assertTrue(BackupCrypto.isEncString(rewrapped))
        assertNotEquals(original, rewrapped)
        // Only the new key can read it, and it still holds the same text.
        assertThrows<GeneralSecurityException> {
            BackupCrypto.rewrapEncString(value = rewrapped, from = USER_KEY_A, to = USER_KEY_B)
        }
        val back = BackupCrypto.rewrapEncString(value = rewrapped, from = USER_KEY_B, to = USER_KEY_A)
        assertEquals("my password", decryptEncString(value = back, userKey = USER_KEY_A))
    }

    @Test
    fun `rewrapEncString rejects strings that are not encrypted with the source key`() {
        assertThrows<GeneralSecurityException> {
            BackupCrypto.rewrapEncString(value = "mockName-1", from = USER_KEY_A, to = USER_KEY_B)
        }
        assertThrows<GeneralSecurityException> {
            BackupCrypto.rewrapEncString(
                value = encString(plainText = "x", userKey = USER_KEY_B),
                from = USER_KEY_A,
                to = USER_KEY_B,
            )
        }
    }

    private fun encrypt(plainText: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        BackupCrypto
            .encryptingStream(
                output = output,
                key = KEY,
                noncePrefix = NONCE_PREFIX,
                associatedData = ASSOCIATED_DATA,
            )
            .use { it.write(plainText) }
        return output.toByteArray()
    }

    private fun decrypt(
        cipherText: ByteArray,
        key: ByteArray = KEY,
        associatedData: ByteArray = ASSOCIATED_DATA,
    ): ByteArray =
        BackupCrypto
            .decryptingStream(
                input = ByteArrayInputStream(cipherText),
                key = key,
                noncePrefix = NONCE_PREFIX,
                associatedData = associatedData,
            )
            .use { it.readBytes() }
}

private val KEY = ByteArray(32) { it.toByte() }
private val NONCE_PREFIX = ByteArray(7) { 1 }
private val ASSOCIATED_DATA = "header".toByteArray()
private val SALT = ByteArray(32) { 7 }
