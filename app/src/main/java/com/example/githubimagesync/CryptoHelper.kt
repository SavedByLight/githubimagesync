package com.example.githubimagesync

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming-friendly encryption for media files.
 *
 * Uses AES-256-CTR (true streaming) + HMAC-SHA256 (encrypt-then-MAC).
 *
 * Format of encrypted payload:
 *   [16-byte salt][16-byte IV][ciphertext][32-byte HMAC-SHA256]
 *
 * The HMAC covers (IV || ciphertext). Key material is derived via
 * PBKDF2-HMAC-SHA256 (100 000 iterations) → 64 bytes:
 *   first 32 → AES key, next 32 → HMAC key.
 *
 * Empty / blank password → no encryption (passthrough).
 *
 * Why not GCM? Android’s Conscrypt GCM implementation buffers the entire
 * plaintext in memory, causing OOM on large videos / photos.
 */
object CryptoHelper {

    private const val SALT_LEN = 16
    private const val IV_LEN = 16          // AES block size (CTR)
    private const val HMAC_LEN = 32        // SHA-256
    private const val KEY_MATERIAL_LEN = 64 // 32 AES + 32 HMAC
    private const val ITERATIONS = 100_000
    private const val CIPHER_TRANSFORM = "AES/CTR/NoPadding"
    private const val KEY_ALGO = "PBKDF2WithHmacSHA256"
    private const val HMAC_ALGO = "HmacSHA256"

    /** Overhead added by encryption (salt + IV + HMAC). */
    const val OVERHEAD_BYTES = SALT_LEN + IV_LEN + HMAC_LEN

    fun isEncryptionEnabled(password: String?): Boolean =
        !password.isNullOrBlank()

    /**
     * Encrypts [plain] with [password]. Returns original bytes if password is blank.
     */
    fun encrypt(plain: ByteArray, password: String): ByteArray {
        if (!isEncryptionEnabled(password)) return plain

        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val (aesKey, hmacKey) = deriveKeys(password, salt)
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, IvParameterSpec(iv))
        val cipherText = cipher.doFinal(plain)

        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(hmacKey)
        mac.update(iv)
        mac.update(cipherText)
        val tag = mac.doFinal()

        return ByteArrayOutputStream(OVERHEAD_BYTES + cipherText.size).use { out ->
            out.write(salt)
            out.write(iv)
            out.write(cipherText)
            out.write(tag)
            out.toByteArray()
        }
    }

    /**
     * Decrypts [encrypted] with [password].
     * Returns original bytes if password is blank.
     * Throws on wrong password / corrupted data / truncated payload.
     */
    fun decrypt(encrypted: ByteArray, password: String): ByteArray {
        if (!isEncryptionEnabled(password)) return encrypted
        if (encrypted.size < OVERHEAD_BYTES) {
            throw IllegalArgumentException("Data too short to be encrypted (${encrypted.size} bytes)")
        }

        val salt = encrypted.copyOfRange(0, SALT_LEN)
        val iv = encrypted.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val cipherText = encrypted.copyOfRange(SALT_LEN + IV_LEN, encrypted.size - HMAC_LEN)
        val tag = encrypted.copyOfRange(encrypted.size - HMAC_LEN, encrypted.size)

        val (aesKey, hmacKey) = deriveKeys(password, salt)

        // Verify HMAC first (constant-time compare)
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(hmacKey)
        mac.update(iv)
        mac.update(cipherText)
        val expected = mac.doFinal()
        if (!constantTimeEquals(expected, tag)) {
            throw IllegalArgumentException("HMAC verification failed – wrong password or corrupted data")
        }

        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(iv))
        return cipher.doFinal(cipherText)
    }

    /**
     * Decrypts [encFile] into [outFile] with constant memory (two sequential passes).
     * If [password] is blank, copies [encFile] to [outFile] unchanged.
     */
    fun decryptToFile(encFile: File, outFile: File, password: String) {
        if (!isEncryptionEnabled(password)) {
            encFile.copyTo(outFile, overwrite = true)
            return
        }
        val total = encFile.length()
        if (total < OVERHEAD_BYTES) {
            throw IllegalArgumentException("Data too short to be encrypted ($total bytes)")
        }
        val cipherTextLen = total - OVERHEAD_BYTES

        val salt = ByteArray(SALT_LEN)
        val iv = ByteArray(IV_LEN)
        val tag = ByteArray(HMAC_LEN)

        // Pass 1: read header, verify HMAC over (IV || ciphertext)
        FileInputStream(encFile).use { fis ->
            fis.readFully(salt)
            fis.readFully(iv)
            val (_, hmacKey) = deriveKeys(password, salt)
            val mac = Mac.getInstance(HMAC_ALGO)
            mac.init(hmacKey)
            mac.update(iv)
            val buf = ByteArray(64 * 1024)
            var remaining = cipherTextLen
            while (remaining > 0) {
                val n = fis.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw IllegalArgumentException("Unexpected EOF while verifying HMAC")
                mac.update(buf, 0, n)
                remaining -= n
            }
            fis.readFully(tag)
            if (!constantTimeEquals(mac.doFinal(), tag)) {
                throw IllegalArgumentException("HMAC verification failed – wrong password or corrupted data")
            }
        }

        // Pass 2: decrypt ciphertext → outFile
        FileInputStream(encFile).use { fis ->
            fis.readFully(salt)
            fis.readFully(iv)
            val (aesKey, _) = deriveKeys(password, salt)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(iv))
            FileOutputStream(outFile).use { fos ->
                CipherOutputStream(fos, cipher).use { cos ->
                    val buf = ByteArray(64 * 1024)
                    var remaining = cipherTextLen
                    while (remaining > 0) {
                        val n = fis.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        cos.write(buf, 0, n)
                        remaining -= n
                    }
                }
            }
        }
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n < 0) throw IllegalArgumentException("Unexpected EOF")
            off += n
        }
    }

    /**
     * Encrypts a stream to a temporary file. True streaming – constant memory.
     * Caller must delete the returned file when done.
     * Returns null (and does not create a file) if encryption is disabled.
     */
    fun encryptToTempFile(
        openPlainStream: () -> InputStream,
        password: String,
        tempDir: File
    ): File? {
        if (!isEncryptionEnabled(password)) return null

        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val (aesKey, hmacKey) = deriveKeys(password, salt)
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, IvParameterSpec(iv))

        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(hmacKey)
        mac.update(iv) // HMAC starts with IV

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

            // Now the file contains: salt | IV | ciphertext
            // Compute HMAC over (IV || ciphertext) and append the tag.
            // We already fed IV into the Mac; now feed ciphertext.
            FileInputStream(outFile).use { fis ->
                // Skip salt + IV
                fis.skip((SALT_LEN + IV_LEN).toLong())
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = fis.read(buf)
                    if (n < 0) break
                    if (n > 0) mac.update(buf, 0, n)
                }
            }
            val tag = mac.doFinal()

            // Append tag
            FileOutputStream(outFile, /* append = */ true).use { fos ->
                fos.write(tag)
            }

            return outFile
        } catch (e: Exception) {
            outFile.delete()
            throw e
        }
    }

    private fun deriveKeys(password: String, salt: ByteArray): Pair<SecretKeySpec, SecretKeySpec> {
        val factory = SecretKeyFactory.getInstance(KEY_ALGO)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_MATERIAL_LEN * 8)
        val material = factory.generateSecret(spec).encoded
        val aesKey = SecretKeySpec(material, 0, 32, "AES")
        val hmacKey = SecretKeySpec(material, 32, 32, HMAC_ALGO)
        return aesKey to hmacKey
    }

    /** Constant-time comparison to avoid timing attacks on the HMAC. */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
