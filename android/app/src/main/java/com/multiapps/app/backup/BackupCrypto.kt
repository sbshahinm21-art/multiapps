package com.multiapps.app.backup

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup file format (all binary):
 *   "MABK" (4 bytes) | version (1 byte = 1) | iterations (4 bytes, big endian)
 *   | salt (16 bytes) | iv (12 bytes) | AES-256-GCM ciphertext + 16-byte tag
 * The key is derived from the user's backup password with PBKDF2-HMAC-SHA256.
 * Pure JVM code (no Android classes) so it is covered by unit tests.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    const val DEFAULT_ITERATIONS = 310_000
    private const val HEADER = 4 + 1 + 4 + 16 + 12

    class WrongPasswordException : Exception("Wrong password, or the file is damaged.")
    class NotABackupException : Exception("This file is not a Multi Apps backup.")

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(password.size >= 8) { "Backup password must be at least 8 characters." }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(iv).array()
        cipher.updateAAD(header) // the header is authenticated too
        return header + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        if (data.size < HEADER + 16 || !data.copyOfRange(0, 4).contentEquals(MAGIC)) throw NotABackupException()
        val buf = ByteBuffer.wrap(data)
        buf.position(4)
        val version = buf.get()
        if (version != VERSION) throw NotABackupException()
        val iterations = buf.getInt()
        if (iterations < 10_000 || iterations > 5_000_000) throw NotABackupException()
        val salt = ByteArray(16).also { buf.get(it) }
        val iv = ByteArray(12).also { buf.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(data, 0, HEADER)
        return try {
            cipher.doFinal(data, HEADER, data.size - HEADER)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }
}
