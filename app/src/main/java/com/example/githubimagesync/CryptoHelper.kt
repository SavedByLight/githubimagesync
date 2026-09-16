package com.example.githubimagesync

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption helper for media bytes / streams.
 *
 * Format of encrypted payload:
 *   [16-byte salt][12-byte IV][ciphertext + 16-byte GCM tag]
 *
 * Key is derived via PBKDF2-HMAC-SHA256 (100_000 iterations) from the user password + per-file salt.
 * Empty / blank password → no encryption (passthrough).
 */
object CryptoHelper {

    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_LEN = 128 // bits
    private const val KEY_LEN = 256 // bits
    private const val ITERATIONS = 100_000
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGO = "PBKDF2WithHmacSHA256"

    /** Overhead added by encryption (salt + IV + tag). */
    const val OVERHEAD_BYTES = SALT_LEN + IV_LEN + (TAG_LEN / 8)

    fun isEncryptionEnabled(password: String?): Boolean =
        !password.isNullOrBlank()

    /**
     * Encrypts [plain] with [password]. Returns original bytes if password is blank.
     */
    fun encrypt(plain: ByteArray, password: String): ByteArray {
        if (!isEncryptionEnabled(password)) return plain

        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LEN, iv))
        val cipherText = cipher.doFinal(plain)

        return ByteArrayOutputStream(SALT_LEN + IV_LEN + cipherText.size).use { out ->
            out.write(salt)
            out.write(iv)
            out.write(cipherText)
            out.toByteArray()
        }
    }

    /**
     * Decrypts [encrypted] with [password].
     * Returns original bytes if password is blank.
     * Throws on wrong password / corrupted data.
     */
    fun decrypt(encrypted: ByteArray, password: String): ByteArray {
        if (!isEncryptionEnabled(password)) return encrypted
        if (encrypted.size < OVERHEAD_BYTES) {
            throw IllegalArgumentException("Data too short to be encrypted")
        }

        val salt = encrypted.copyOfRange(0, SALT_LEN)
        val iv = encrypted.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val cipherText = encrypted.copyOfRange(SALT_LEN + IV_LEN, encrypted.size)

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN, iv))
        return cipher.doFinal(cipherText)
    }

    /**
     * Encrypts a stream to a temporary file. Caller must delete the returned file when done.
     * Returns null (and does not create a file) if encryption is disabled.
     */
    fun encryptToTempFile(
        openPlainStream: () -> InputStream,
        password: String,
        tempDir: File
    ): File? {
        if (!isEncryptionEnabled(password)) return null

        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LEN, iv))

        val outFile = File.createTempFile("gis_enc_", ".tmp", tempDir)
        try {
            FileOutputStream(outFile).use { fos ->
                fos.write(salt)
                fos.write(iv)
                CipherOutputStream(fos, cipher).use { cos ->
                    openPlainStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) cos.write(buf, 0, n)
                        }
                    }
                }
            }
            return outFile
        } catch (e: Exception) {
            outFile.delete()
            throw e
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(KEY_ALGO)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LEN)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }
}
