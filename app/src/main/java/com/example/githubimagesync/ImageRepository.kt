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
import java.io.FileInputStream
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
        saveMediaToGalleryStreaming(fileName, subFolder, isVideo) { out ->
            out.write(bytes)
        }
    }

    /** Streams [source] into the gallery without loading the whole file into RAM. */
    suspend fun saveMediaToGalleryFromFile(
        fileName: String,
        source: File,
        subFolder: String,
        isVideo: Boolean
    ): Uri? = withContext(Dispatchers.IO) {
        saveMediaToGalleryStreaming(fileName, subFolder, isVideo) { out ->
            FileInputStream(source).use { input ->
                input.copyTo(out, bufferSize = 64 * 1024)
            }
        }
    }

    private fun saveMediaToGalleryStreaming(
        fileName: String,
        subFolder: String,
        isVideo: Boolean,
        write: (java.io.OutputStream) -> Unit
    ): Uri? {
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
            val uri = context.contentResolver.insert(collection, values) ?: return null
            try {
                context.contentResolver.openOutputStream(uri)?.use { write(it) }
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
            FileOutputStream(file).use { write(it) }
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

    /**
     * Max files per part folder (GitHub Contents API lists at most ~1000 entries).
     * Layout: `<codename>/p1/…`, `<codename>/p2/…`, …
     */
    companion object {
        const val FILES_PER_PART = 1000
        private val PART_DIR_REGEX = Regex("^p(\\d+)$", RegexOption.IGNORE_CASE)
    }

    /**
     * Inventory of everything under `/<codename>/`:
     * - part folders `p1`, `p2`, …
     * - legacy flat files directly under the codename (pre-partition layout)
     */
    private data class RemoteInventory(
        /** File names already present anywhere under the device folder (no dupes). */
        val names: MutableSet<String>,
        /** part number → current file count in that part folder */
        val partCounts: MutableMap<Int, Int>,
        /** All remote media items (for download). */
        val files: MutableList<ContentItem>
    )

    private suspend fun loadRemoteInventory(
        client: GitHubClient,
        codename: String,
        onProgress: (String) -> Unit
    ): RemoteInventory {
        val names = mutableSetOf<String>()
        val partCounts = mutableMapOf<Int, Int>()
        val files = mutableListOf<ContentItem>()

        onProgress("Scanning /$codename (all part folders) …")
        val top = try {
            client.listDirectory(codename)
        } catch (e: Exception) {
            onProgress("Could not list /$codename: ${e.message}")
            return RemoteInventory(names, partCounts, files)
        }

        // Legacy flat files directly under the codename folder
        for (item in top.filter { it.type == "file" }) {
            names.add(item.name)
            if (isMediaName(item.name)) files.add(item)
        }
        if (top.any { it.type == "file" }) {
            onProgress("  legacy flat files: ${top.count { it.type == "file" }}")
        }

        // Part folders: p1, p2, …
        val partDirs = top.filter { it.type == "dir" && PART_DIR_REGEX.matches(it.name) }
            .mapNotNull { dir ->
                val n = PART_DIR_REGEX.matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull()
                n?.let { it to dir.name }
            }
            .sortedBy { it.first }

        for ((partNum, dirName) in partDirs) {
            val path = "$codename/$dirName"
            try {
                val entries = client.listDirectory(path).filter { it.type == "file" }
                partCounts[partNum] = entries.size
                for (f in entries) {
                    names.add(f.name)
                    if (isMediaName(f.name)) files.add(f)
                }
                onProgress("  /$path → ${entries.size} file(s)")
                if (entries.size >= FILES_PER_PART) {
                    onProgress("    (at capacity – new uploads will use the next part)")
                }
            } catch (e: Exception) {
                onProgress("  Could not list /$path: ${e.message}")
            }
        }

        if (partDirs.isEmpty() && top.none { it.type == "file" }) {
            onProgress("  (empty – will create /$codename/p1)")
        }
        onProgress("Total unique remote names: ${names.size}")
        return RemoteInventory(names, partCounts, files)
    }

    /** Choose part folder for the next new file: fill lowest incomplete part, else open a new one. */
    private fun allocatePartFolder(partCounts: MutableMap<Int, Int>): String {
        if (partCounts.isEmpty()) {
            partCounts[1] = 0
            return "p1"
        }
        // Prefer the smallest part number that still has room
        val open = partCounts.entries
            .filter { it.value < FILES_PER_PART }
            .minByOrNull { it.key }
        if (open != null) {
            return "p${open.key}"
        }
        val next = (partCounts.keys.maxOrNull() ?: 0) + 1
        partCounts[next] = 0
        return "p$next"
    }

    suspend fun uploadAll(
        client: GitHubClient,
        encryptionPassword: String = "",
        onProgress: (String) -> Unit
    ): SyncResult {
        val codename = deviceCodename()
        val media = loadLocalMedia()
        val images = media.count { !it.isVideo }
        val videos = media.count { it.isVideo }
        val encrypt = CryptoHelper.isEncryptionEnabled(encryptionPassword)
        onProgress("Found $images image(s) + $videos video(s)")
        if (encrypt) onProgress("Encryption is ON (AES-256-CTR + HMAC)")

        val inventory = loadRemoteInventory(client, codename, onProgress)
        val remoteNames = inventory.names
        val partCounts = inventory.partCounts
        onProgress("${remoteNames.size} file(s) already on GitHub – duplicates will be skipped")

        var success = 0
        var skipped = 0
        var failed = 0
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        var lfsReady = false
        val tempDir = context.cacheDir

        for ((index, item) in media.withIndex()) {
            val kind = if (item.isVideo) "video" else "image"
            try {
                onProgress("[${index + 1}/${media.size}] ${item.displayName}")

                if (item.displayName in remoteNames) {
                    skipped++
                    onProgress("  skipped (already uploaded)")
                    continue
                }

                val plainSize = if (item.size > 0) item.size else {
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

                if (plainSize <= 0L) {
                    skipped++
                    onProgress("  skipped (empty)")
                    continue
                }

                val part = allocatePartFolder(partCounts)
                val remotePath = "$codename/$part/${item.displayName}"
                onProgress("  → /$remotePath")

                val message = "Upload ${item.displayName} ($kind) from $codename/$part ($timestamp)" +
                    if (encrypt) " [encrypted]" else ""

                if (encrypt) {
                    onProgress("  encrypting …")
                    val encFile = CryptoHelper.encryptToTempFile(
                        openPlainStream = { openMediaStream(item.uri) },
                        password = encryptionPassword,
                        tempDir = tempDir
                    ) ?: throw IllegalStateException("Encryption produced no file")
                    try {
                        val encSize = encFile.length()
                        if (encSize >= GitHubClient.CONTENTS_MAX_BYTES) {
                            if (!lfsReady) {
                                onProgress("  ensuring .gitattributes (LFS) …")
                                client.ensureLfsAttributes()
                                lfsReady = true
                            }
                            val mb = encSize / (1024.0 * 1024.0)
                            onProgress("  using Git LFS (%.1f MB encrypted)".format(mb))
                            client.uploadLargeFile(
                                path = remotePath,
                                sizeBytes = encSize,
                                openStream = { FileInputStream(encFile) },
                                commitMessage = "$message [LFS]",
                                onProgress = onProgress
                            )
                        } else {
                            onProgress("  uploading via Contents API (${encSize / 1024} KB encrypted) …")
                            val bytes = encFile.readBytes()
                            client.uploadFile(
                                path = remotePath,
                                contentBytes = bytes,
                                commitMessage = message,
                                existingSha = null
                            )
                        }
                    } finally {
                        encFile.delete()
                    }
                } else {
                    if (plainSize >= GitHubClient.CONTENTS_MAX_BYTES) {
                        if (!lfsReady) {
                            onProgress("  ensuring .gitattributes (LFS) …")
                            client.ensureLfsAttributes()
                            lfsReady = true
                        }
                        val mb = plainSize / (1024.0 * 1024.0)
                        onProgress("  using Git LFS (%.1f MB)".format(mb))
                        client.uploadLargeFile(
                            path = remotePath,
                            sizeBytes = plainSize,
                            openStream = { openMediaStream(item.uri) },
                            commitMessage = "$message [LFS]",
                            onProgress = onProgress
                        )
                    } else {
                        onProgress("  uploading via Contents API (${plainSize / 1024} KB) …")
                        val bytes = readMediaBytes(item.uri)
                        client.uploadFile(
                            path = remotePath,
                            contentBytes = bytes,
                            commitMessage = message,
                            existingSha = null
                        )
                    }
                }
                success++
                remoteNames.add(item.displayName)
                // Bump count for the part we just wrote into
                val partNum = PART_DIR_REGEX.matchEntire(part)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                partCounts[partNum] = (partCounts[partNum] ?: 0) + 1
                onProgress("  OK ($kind)")
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (msg.contains("sha wasn't supplied", ignoreCase = true) ||
                    msg.contains("already exists", ignoreCase = true) ||
                    msg.contains("422", ignoreCase = true)
                ) {
                    skipped++
                    remoteNames.add(item.displayName)
                    onProgress("  skipped (already on GitHub)")
                } else {
                    failed++
                    Log.e(tag, "Upload failed for ${item.displayName}", e)
                    onProgress("  FAILED: ${e.message}")
                }
            }
        }
        return SyncResult(success, skipped, failed)
    }

    suspend fun syncDownload(
        client: GitHubClient,
        encryptionPassword: String = "",
        onProgress: (String) -> Unit
    ): SyncResult {
        val codename = deviceCodename()
        val decrypt = CryptoHelper.isEncryptionEnabled(encryptionPassword)
        if (decrypt) onProgress("Decryption is ON (AES-256-CTR + HMAC)")

        val inventory = loadRemoteInventory(client, codename, onProgress)
        // One entry per filename – never download the same name twice
        val files = inventory.files
            .distinctBy { it.name }
            .sortedBy { it.path }

        if (files.isEmpty()) {
            onProgress("No media files found under /$codename")
            return SyncResult(0, 0, 0)
        }
        onProgress("Found ${files.size} unique remote media file(s). Checking local library …")

        // Mutable so we skip names we just saved in this run
        val localNames = loadLocalMedia().map { it.displayName }.toMutableSet()
        onProgress("${localNames.size} local media name(s) – those will be skipped")

        var success = 0
        var skipped = 0
        var failed = 0
        val tempDir = context.cacheDir
        for ((index, item) in files.withIndex()) {
            var downloadFile: File? = null
            var plainFile: File? = null
            try {
                onProgress("[${index + 1}/${files.size}] ${item.path}")
                if (item.name in localNames) {
                    skipped++
                    onProgress("  skipped (already on device)")
                    continue
                }
                val url = item.downloadUrl
                    ?: throw IllegalStateException("No download_url for ${item.path}")

                // Stream to disk (LFS-safe) – never load multi-hundred-MB bodies into RAM
                downloadFile = File.createTempFile("gis_dl_", ".tmp", tempDir)
                onProgress("  downloading …")
                client.downloadMediaToFile(url, downloadFile, onProgress)

                val toSave: File = if (decrypt) {
                    onProgress("  decrypting …")
                    plainFile = File.createTempFile("gis_plain_", ".tmp", tempDir)
                    try {
                        CryptoHelper.decryptToFile(downloadFile, plainFile, encryptionPassword)
                    } catch (e: Exception) {
                        throw IllegalStateException(
                            "Decrypt failed (wrong password or not encrypted?): ${e.message}"
                        )
                    }
                    plainFile
                } else {
                    downloadFile
                }

                val video = isVideoName(item.name)
                val kb = toSave.length() / 1024
                saveMediaToGalleryFromFile(item.name, toSave, codename, isVideo = video)
                localNames.add(item.name)
                success++
                onProgress("  saved ($kb KB)")
            } catch (e: Exception) {
                failed++
                Log.e(tag, "Download failed for ${item.name}", e)
                onProgress("  FAILED: ${e.message}")
            } finally {
                downloadFile?.delete()
                plainFile?.delete()
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
