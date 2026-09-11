package com.example.githubimagesync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.githubimagesync.github.ContentItem
import com.example.githubimagesync.github.GitHubClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalImage(
    val id: Long,
    val displayName: String,
    val uri: Uri,
    val size: Long,
    val dateAdded: Long
)

class ImageRepository(private val context: Context) {

    private val tag = "ImageRepository"

    /** Device industrial design / codename, e.g. "raven", "cheetah", "pixel7" */
    fun deviceCodename(): String {
        val device = Build.DEVICE
        return if (device.isNullOrBlank()) "unknown_device" else device
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .lowercase(Locale.US)
    }

    /**
     * Query all images from the device MediaStore (requires READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE).
     */
    suspend fun loadLocalImages(): List<LocalImage> = withContext(Dispatchers.IO) {
        val images = mutableListOf<LocalImage>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "image_$id.jpg"
                val size = cursor.getLong(sizeCol)
                val date = cursor.getLong(dateCol)
                val contentUri = ContentUris.withAppendedId(collection, id)
                images.add(LocalImage(id, name, contentUri, size, date))
            }
        }
        images
    }

    /**
     * Read the raw bytes of a MediaStore image.
     */
    suspend fun readImageBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot open $uri")
    }

    /**
     * Save downloaded image bytes into the public Pictures/GitHubSync/<codename>/ folder
     * so they appear in the Gallery.
     */
    suspend fun saveImageToGallery(
        fileName: String,
        bytes: ByteArray,
        subFolder: String
    ): Uri? = withContext(Dispatchers.IO) {
        val relativePath = "${Environment.DIRECTORY_PICTURES}/GitHubSync/$subFolder"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, guessMime(fileName))
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values) ?: return@withContext null
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "GitHubSync/$subFolder"
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            // Notify MediaStore
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATA, file.absolutePath)
                put(MediaStore.Images.Media.MIME_TYPE, guessMime(fileName))
            }
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".heic", true) || name.endsWith(".heif", true) -> "image/heif"
        else -> "image/jpeg"
    }

    // ─── High-level sync operations ───────────────────────────────────────────

    /**
     * Upload all local images into the GitHub folder named after the device codename.
     * Existing files with the same name are overwritten (SHA is fetched first).
     */
    suspend fun uploadAll(
        client: GitHubClient,
        onProgress: (String) -> Unit
    ): Pair<Int, Int> {
        val codename = deviceCodename()
        val folder = codename // e.g. "raven"
        val images = loadLocalImages()
        onProgress("Found ${images.size} local image(s). Uploading to /$folder …")

        var success = 0
        var failed = 0
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

        for ((index, image) in images.withIndex()) {
            try {
                onProgress("[${index + 1}/${images.size}] Uploading ${image.displayName} …")
                val bytes = readImageBytes(image.uri)
                // Skip empty or huge files (> 50 MB – GitHub soft limit for Contents API is 100 MB)
                if (bytes.isEmpty()) {
                    onProgress("  skipped (empty)")
                    continue
                }
                if (bytes.size > 50 * 1024 * 1024) {
                    onProgress("  skipped (too large > 50 MB)")
                    continue
                }

                val remotePath = "$folder/${image.displayName}"
                val existingSha = client.getFileSha(remotePath)
                client.uploadFile(
                    path = remotePath,
                    contentBytes = bytes,
                    commitMessage = "Upload ${image.displayName} from $codename ($timestamp)",
                    existingSha = existingSha
                )
                success++
                onProgress("  OK")
            } catch (e: Exception) {
                failed++
                Log.e(tag, "Upload failed for ${image.displayName}", e)
                onProgress("  FAILED: ${e.message}")
            }
        }
        return success to failed
    }

    /**
     * Download every file that looks like an image from the device-codename folder
     * on GitHub and save it into the phone's gallery.
     */
    suspend fun syncDownload(
        client: GitHubClient,
        onProgress: (String) -> Unit
    ): Pair<Int, Int> {
        val codename = deviceCodename()
        onProgress("Listing /$codename on GitHub …")
        val items: List<ContentItem> = client.listDirectory(codename)
        val files = items.filter { it.type == "file" && isImageName(it.name) }

        if (files.isEmpty()) {
            onProgress("No image files found in /$codename")
            return 0 to 0
        }
        onProgress("Found ${files.size} remote image(s). Downloading …")

        var success = 0
        var failed = 0
        for ((index, item) in files.withIndex()) {
            try {
                onProgress("[${index + 1}/${files.size}] ${item.name}")
                val url = item.downloadUrl
                    ?: throw IllegalStateException("No download_url for ${item.path}")
                val bytes = client.downloadFile(url)
                saveImageToGallery(item.name, bytes, codename)
                success++
                onProgress("  saved")
            } catch (e: Exception) {
                failed++
                Log.e(tag, "Download failed for ${item.name}", e)
                onProgress("  FAILED: ${e.message}")
            }
        }
        return success to failed
    }

    private fun isImageName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp") ||
                lower.endsWith(".gif") || lower.endsWith(".heic") ||
                lower.endsWith(".heif") || lower.endsWith(".bmp")
    }
}
