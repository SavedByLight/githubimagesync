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
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalMedia(
    val id: Long,
    val displayName: String,
    val uri: Uri,
    val size: Long,
    val dateAdded: Long,
    val isVideo: Boolean
)

data class SyncResult(
    val success: Int,
    val skipped: Int,
    val failed: Int
)

class ImageRepository(private val context: Context) {

    private val tag = "ImageRepository"

    fun deviceCodename(): String {
        val device = Build.DEVICE
        return if (device.isNullOrBlank()) "unknown_device" else device
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .lowercase(Locale.US)
    }

    suspend fun loadLocalMedia(): List<LocalMedia> = withContext(Dispatchers.IO) {
        val media = mutableListOf<LocalMedia>()
        media += queryMediaStore(images = true)
        media += queryMediaStore(images = false)
        media.sortedByDescending { it.dateAdded }
    }

    private fun queryMediaStore(images: Boolean): List<LocalMedia> {
        val collection = if (images) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        }

        val idColName = if (images) MediaStore.Images.Media._ID else MediaStore.Video.Media._ID
        val nameColName =
            if (images) MediaStore.Images.Media.DISPLAY_NAME else MediaStore.Video.Media.DISPLAY_NAME
        val sizeColName =
            if (images) MediaStore.Images.Media.SIZE else MediaStore.Video.Media.SIZE
        val dateColName =
            if (images) MediaStore.Images.Media.DATE_ADDED else MediaStore.Video.Media.DATE_ADDED

        val projection = arrayOf(idColName, nameColName, sizeColName, dateColName)
        val sortOrder = "$dateColName DESC"
        val result = mutableListOf<LocalMedia>()

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(idColName)
            val nameCol = cursor.getColumnIndexOrThrow(nameColName)
            val sizeCol = cursor.getColumnIndexOrThrow(sizeColName)
            val dateCol = cursor.getColumnIndexOrThrow(dateColName)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                    ?: if (images) "image_$id.jpg" else "video_$id.mp4"
                val size = cursor.getLong(sizeCol)
                val date = cursor.getLong(dateCol)
                val contentUri = ContentUris.withAppendedId(collection, id)
                result.add(LocalMedia(id, name, contentUri, size, date, isVideo = !images))
            }
        }
        return result
    }

    private fun openMediaStream(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open $uri")

    suspend fun readMediaBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        openMediaStream(uri).use { it.readBytes() }
    }

    suspend fun saveMediaToGallery(
        fileName: String,
        bytes: ByteArray,
        subFolder: String,
        isVideo: Boolean
    ): Uri? = withContext(Dispatchers.IO) {
        val baseDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val relativePath = "$baseDir/GitHubSync/$subFolder"
        val mime = guessMime(fileName, isVideo)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                if (isVideo) {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                    put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(collection, values) ?: return@withContext null
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                if (isVideo) {
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                } else {
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(baseDir),
                "GitHubSync/$subFolder"
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            val values = ContentValues().apply {
                if (isVideo) {
                    put(MediaStore.Video.Media.DATA, file.absolutePath)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                } else {
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                }
            }
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            context.contentResolver.insert(collection, values)
        }
    }

    private fun guessMime(name: String, isVideo: Boolean): String {
        val lower = name.lowercase(Locale.US)
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".heic") || lower.endsWith(".heif") -> "image/heif"
            lower.endsWith(".bmp") -> "image/bmp"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".3gp") -> "video/3gpp"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".avi") -> "video/x-msvideo"
            isVideo -> "video/mp4"
            else -> "image/jpeg"
        }
    }

    suspend fun uploadAll(
        client: GitHubClient,
        onProgress: (String) -> Unit
    ): SyncResult {
        val codename = deviceCodename()
        val folder = codename
        val media = loadLocalMedia()
        val images = media.count { !it.isVideo }
        val videos = media.count { it.isVideo }
        onProgress("Found $images image(s) + $videos video(s). Checking /$folder …")

        val remoteNames = try {
            client.listDirectory(folder)
                .filter { it.type == "file" }
                .map { it.name }
                .toSet()
        } catch (e: Exception) {
            onProgress("Could not list remote folder: ${e.message}")
            emptySet()
        }
        onProgress("${remoteNames.size} file(s) already on GitHub – those will be skipped")

        var success = 0
        var skipped = 0
        var failed = 0
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        var lfsReady = false

        for ((index, item) in media.withIndex()) {
            val kind = if (item.isVideo) "video" else "image"
            try {
                onProgress("[${index + 1}/${media.size}] ${item.displayName}")

                if (item.displayName in remoteNames) {
                    skipped++
                    onProgress("  skipped (already uploaded)")
                    continue
                }

                val size = if (item.size > 0) item.size else {
                    openMediaStream(item.uri).use { stream ->
                        var total = 0L
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            total += n
                        }
                        total
                    }
                }

                if (size <= 0L) {
                    skipped++
                    onProgress("  skipped (empty)")
                    continue
                }

                val remotePath = "$folder/${item.displayName}"
                val message = "Upload ${item.displayName} ($kind) from $codename ($timestamp)"

                if (size >= GitHubClient.CONTENTS_MAX_BYTES) {
                    if (!lfsReady) {
                        onProgress("  ensuring .gitattributes (LFS) …")
                        client.ensureLfsAttributes()
                        lfsReady = true
                    }
                    val mb = size / (1024.0 * 1024.0)
                    onProgress("  using Git LFS (%.1f MB)".format(mb))
                    client.uploadLargeFile(
                        path = remotePath,
                        sizeBytes = size,
                        openStream = { openMediaStream(item.uri) },
                        commitMessage = "$message [LFS]",
                        onProgress = onProgress
                    )
                } else {
                    onProgress("  uploading via Contents API (${size / 1024} KB) …")
                    val bytes = readMediaBytes(item.uri)
                    client.uploadFile(
                        path = remotePath,
                        contentBytes = bytes,
                        commitMessage = message,
                        existingSha = null
                    )
                }
                success++
                onProgress("  OK ($kind)")
            } catch (e: Exception) {
                failed++
                Log.e(tag, "Upload failed for ${item.displayName}", e)
                onProgress("  FAILED: ${e.message}")
            }
        }
        return SyncResult(success, skipped, failed)
    }

    suspend fun syncDownload(
        client: GitHubClient,
        onProgress: (String) -> Unit
    ): SyncResult {
        val codename = deviceCodename()
        onProgress("Listing /$codename on GitHub …")
        val items: List<ContentItem> = client.listDirectory(codename)
        val files = items.filter { it.type == "file" && isMediaName(it.name) }

        if (files.isEmpty()) {
            onProgress("No media files found in /$codename")
            return SyncResult(0, 0, 0)
        }
        onProgress("Found ${files.size} remote media file(s). Checking local library …")

        val localNames = loadLocalMedia().map { it.displayName }.toSet()

        var success = 0
        var skipped = 0
        var failed = 0
        for ((index, item) in files.withIndex()) {
            try {
                onProgress("[${index + 1}/${files.size}] ${item.name}")
                if (item.name in localNames) {
                    skipped++
                    onProgress("  skipped (already on device)")
                    continue
                }
                val url = item.downloadUrl
                    ?: throw IllegalStateException("No download_url for ${item.path}")
                val bytes = client.downloadMedia(url, onProgress)
                val video = isVideoName(item.name)
                saveMediaToGallery(item.name, bytes, codename, isVideo = video)
                success++
                onProgress("  saved (${bytes.size / 1024} KB)")
            } catch (e: Exception) {
                failed++
                Log.e(tag, "Download failed for ${item.name}", e)
                onProgress("  FAILED: ${e.message}")
            }
        }
        return SyncResult(success, skipped, failed)
    }

    private fun isImageName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp") ||
                lower.endsWith(".gif") || lower.endsWith(".heic") ||
                lower.endsWith(".heif") || lower.endsWith(".bmp")
    }

    private fun isVideoName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
                lower.endsWith(".webm") || lower.endsWith(".3gp") ||
                lower.endsWith(".mov") || lower.endsWith(".avi") ||
                lower.endsWith(".m4v")
    }

    private fun isMediaName(name: String): Boolean = isImageName(name) || isVideoName(name)
}
