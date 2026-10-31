package com.x8bit.bitwarden.data.vault.manager

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A 64-byte user key (AES-256 key followed by an HMAC-SHA256 key).
 */
val USER_KEY_A: ByteArray = ByteArray(64) { it.toByte() }

/**
 * A different 64-byte user key.
 */
val USER_KEY_B: ByteArray = ByteArray(64) { (it * 3 + 1).toByte() }

/**
 * Encrypts [plainText] the way the SDK does for type 2 encrypted strings.
 */
fun encString(plainText: String, userKey: ByteArray): String {
    val iv = ByteArray(16) { 5 }
    val cipherText = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(userKey.copyOf(32), "AES"), IvParameterSpec(iv))
        doFinal(plainText.toByteArray())
    }
    val mac = hmac(key = userKey.copyOfRange(32, 64), data = iv + cipherText)
    val encoder = Base64.getEncoder()
    return "2.${encoder.encodeToString(iv)}|${encoder.encodeToString(cipherText)}|" +
        encoder.encodeToString(mac)
}

/**
 * Decrypts a type 2 encrypted string after checking its MAC.
 */
fun decryptEncString(value: String, userKey: ByteArray): String {
    val decoder = Base64.getDecoder()
    val parts = value.removePrefix("2.").split('|').map { decoder.decode(it) }
    check(hmac(key = userKey.copyOfRange(32, 64), data = parts[0] + parts[1]).contentEquals(parts[2]))
    return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(userKey.copyOf(32), "AES"),
            IvParameterSpec(parts[0]),
        )
        doFinal(parts[1]).toString(Charsets.UTF_8)
    }
}

private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }
