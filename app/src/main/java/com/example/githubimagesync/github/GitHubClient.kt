package com.example.githubimagesync.github

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * GitHub REST Contents API + Git LFS Batch API client.
 *
 * - Files under [CONTENTS_MAX_BYTES] use the Contents API (base64).
 * - Larger files use Git LFS (batch → PUT object → commit pointer).
 *
 * Docs:
 * - https://docs.github.com/en/rest/repos/contents
 * - https://github.com/git-lfs/git-lfs/blob/main/docs/api/batch.md
 */
class GitHubClient(
    private val owner: String,
    private val repo: String,
    private val token: String
) {
    private val gson = Gson()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.SECONDS) // no overall call timeout for big LFS uploads
        .addInterceptor(HttpLoggingInterceptor().apply {
            // BASIC avoids logging multi‑MB bodies
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val lfsJsonMedia = "application/vnd.git-lfs+json".toMediaType()
    private val octetStream = "application/octet-stream".toMediaType()

    private fun apiUrl(path: String): String {
        val clean = path.trimStart('/')
        return "https://api.github.com/repos/$owner/$repo/contents/$clean"
    }

    private fun lfsBatchUrl(): String =
        "https://github.com/$owner/$repo.git/info/lfs/objects/batch"

    private fun authRequestBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "GitHubImageSync-Android")

    private fun lfsRequestBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.git-lfs+json")
            .header("Content-Type", "application/vnd.git-lfs+json")
            .header("User-Agent", "git-lfs/3.4.0 (GitHubImageSync-Android)")

    // ─── Contents API ────────────────────────────────────────────────────────

    suspend fun listDirectory(path: String): List<ContentItem> = withContext(Dispatchers.IO) {
        val request = authRequestBuilder(apiUrl(path)).get().build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> {
                    val body = response.body?.string() ?: return@use emptyList()
                    // Directory → JSON array; single file → object (treat as empty list of siblings)
                    val trimmed = body.trimStart()
                    if (trimmed.startsWith("[")) {
                        val type = object : TypeToken<List<ContentItem>>() {}.type
                        gson.fromJson(body, type)
                    } else {
                        emptyList()
                    }
                }
                404 -> emptyList()
                else -> throw IOException(
                    "GitHub list failed (${response.code}): ${response.body?.string()?.take(300)}"
                )
            }
        }
    }

    suspend fun downloadFile(downloadUrl: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(downloadUrl)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "GitHubImageSync-Android")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed (${response.code})")
            }
            response.body?.bytes() ?: throw IOException("Empty body")
        }
    }

    /**
     * Creates or updates a small file via the Contents API (base64 body).
     * Keep payloads well under the GitHub 100 MB hard limit: base64 expands
     * size by ~4/3 and Android heaps are often only 256 MB, so large files
     * must use [uploadLargeFile] (Git LFS) instead.
     */
    suspend fun uploadFile(
        path: String,
        contentBytes: ByteArray,
        commitMessage: String,
        existingSha: String? = null
    ): CreateOrUpdateFileResponse = withContext(Dispatchers.IO) {
        if (contentBytes.size.toLong() > CONTENTS_MAX_BYTES) {
            throw IOException(
                "File is ${contentBytes.size} bytes – use uploadLargeFile (Git LFS) " +
                    "for files larger than ${CONTENTS_MAX_BYTES / (1024 * 1024)} MB"
            )
        }
        val base64 = Base64.encodeToString(contentBytes, Base64.NO_WRAP)
        val body = CreateOrUpdateFileRequest(
            message = commitMessage,
            content = base64,
            sha = existingSha
        )
        putContents(path, body)
    }

    private fun putContents(
        path: String,
        body: CreateOrUpdateFileRequest
    ): CreateOrUpdateFileResponse {
        val json = gson.toJson(body)
        val request = authRequestBuilder(apiUrl(path))
            .put(json.toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                val err = try {
                    gson.fromJson(responseBody, GitHubError::class.java)?.message
                } catch (_: Exception) {
                    responseBody
                }
                throw IOException("Upload failed (${response.code}): $err")
            }
            return gson.fromJson(responseBody, CreateOrUpdateFileResponse::class.java)
                ?: throw IOException("Empty response")
        }
    }

    suspend fun getFileSha(path: String): String? = withContext(Dispatchers.IO) {
        val request = authRequestBuilder(apiUrl(path)).get().build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> {
                    val body = response.body?.string() ?: return@use null
                    val obj = JsonParser.parseString(body).asJsonObject
                    obj.get("sha")?.asString
                }
                404 -> null
                401, 403 -> {
                    val body = response.body?.string().orEmpty()
                    throw IOException(
                        "Auth failed (${response.code}). Check token / scopes / owner-repo. " +
                            "GitHub: ${body.take(200)}"
                    )
                }
                else -> {
                    val body = response.body?.string().orEmpty()
                    throw IOException("getFileSha failed (${response.code}): ${body.take(200)}")
                }
            }
        }
    }

    suspend fun testAuth(): String = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$owner/$repo"
        val request = authRequestBuilder(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when (response.code) {
                200 -> "OK – authenticated to $owner/$repo"
                401 -> "401 Unauthorized – token invalid/expired."
                403 -> "403 Forbidden – need Contents: Read and write."
                404 -> "404 Not Found – wrong owner/repo or private repo."
                else -> "Unexpected ${response.code}: ${body.take(200)}"
            }
        }
    }

    // ─── Git LFS ─────────────────────────────────────────────────────────────

    /**
     * Ensures `.gitattributes` tracks common media extensions with LFS.
     * Safe to call multiple times (skips if already present with our marker).
     */
    suspend fun ensureLfsAttributes(commitMessage: String = "Enable Git LFS for media files") =
        withContext(Dispatchers.IO) {
            val path = ".gitattributes"
            val existingSha = getFileSha(path)
            val existingContent = if (existingSha != null) {
                try {
                    val req = authRequestBuilder(apiUrl(path)).get().build()
                    client.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) return@use ""
                        val body = response.body?.string() ?: return@use ""
                        val obj = JsonParser.parseString(body).asJsonObject
                        val b64 = obj.get("content")?.asString?.replace("\n", "") ?: return@use ""
                        String(Base64.decode(b64, Base64.DEFAULT))
                    }
                } catch (_: Exception) {
                    ""
                }
            } else {
                ""
            }

            if (existingContent.contains("# GitHubImageSync-LFS")) {
                return@withContext // already configured by this app
            }

            val rules = buildString {
                if (existingContent.isNotBlank()) {
                    append(existingContent.trimEnd())
                    append("\n\n")
                }
                append("# GitHubImageSync-LFS\n")
                val exts = listOf(
                    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp",
                    "mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v"
                )
                for (ext in exts) {
                    append("*.$ext filter=lfs diff=lfs merge=lfs -text\n")
                }
            }

            putContents(
                path,
                CreateOrUpdateFileRequest(
                    message = commitMessage,
                    content = Base64.encodeToString(rules.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                    sha = existingSha
                )
            )
        }

    /**
     * Upload a large file via Git LFS, then commit an LFS pointer at [path] in the repo.
     *
     * @param sizeBytes exact size of the stream
     * @param openStream factory that returns a fresh InputStream of the file bytes (may be called twice: hash + upload)
     */
    suspend fun uploadLargeFile(
        path: String,
        sizeBytes: Long,
        openStream: () -> InputStream,
        commitMessage: String,
        onProgress: ((String) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        onProgress?.invoke("  hashing (SHA-256) …")
        val oid = sha256Hex(openStream)

        onProgress?.invoke("  LFS batch (upload) …")
        val batch = lfsBatch("upload", oid, sizeBytes)
        val obj = batch.objects?.firstOrNull()
            ?: throw IOException("LFS batch returned no objects")
        if (obj.error != null) {
            throw IOException("LFS error: ${obj.error.message} (code ${obj.error.code})")
        }

        val uploadAction = obj.actions?.upload
        if (uploadAction != null) {
            onProgress?.invoke("  LFS transferring $sizeBytes bytes …")
            putLfsObject(uploadAction, sizeBytes, openStream)
            obj.actions.verify?.let { verify ->
                onProgress?.invoke("  LFS verify …")
                postLfsVerify(verify, oid, sizeBytes)
            }
        } else {
            // No upload action → object already exists on the LFS server
            onProgress?.invoke("  LFS object already on server")
        }

        // Commit pointer file via Contents API
        onProgress?.invoke("  committing LFS pointer …")
        val pointer = buildLfsPointer(oid, sizeBytes)
        val existingSha = getFileSha(path)
        putContents(
            path,
            CreateOrUpdateFileRequest(
                message = commitMessage,
                content = Base64.encodeToString(pointer.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                sha = existingSha
            )
        )
    }

    /**
     * Download file bytes. If the remote content is an LFS pointer, fetches the real object via LFS.
     */
    suspend fun downloadMedia(
        downloadUrl: String,
        onProgress: ((String) -> Unit)? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        val raw = downloadFile(downloadUrl)
        val text = try {
            String(raw, Charsets.UTF_8)
        } catch (_: Exception) {
            return@withContext raw
        }
        val pointer = parseLfsPointer(text) ?: return@withContext raw

        onProgress?.invoke("  LFS object ${pointer.first.take(12)}… (${pointer.second} bytes)")
        val batch = lfsBatch("download", pointer.first, pointer.second)
        val obj = batch.objects?.firstOrNull()
            ?: throw IOException("LFS batch (download) returned no objects")
        if (obj.error != null) {
            throw IOException("LFS download error: ${obj.error.message}")
        }
        val downloadAction = obj.actions?.download
            ?: throw IOException("LFS: no download action (object missing on server?)")
        getLfsObject(downloadAction)
    }

    private fun lfsBatch(operation: String, oid: String, size: Long): LfsBatchResponse {
        val body = LfsBatchRequest(
            operation = operation,
            objects = listOf(LfsObjectSpec(oid = oid, size = size))
        )
        val json = gson.toJson(body)
        val request = lfsRequestBuilder(lfsBatchUrl())
            .post(json.toRequestBody(lfsJsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(
                    "LFS batch failed (${response.code}): ${responseBody.take(400)}. " +
                        "Is Git LFS enabled on this repo? Token needs Contents access."
                )
            }
            return gson.fromJson(responseBody, LfsBatchResponse::class.java)
                ?: throw IOException("Empty LFS batch response")
        }
    }

    private fun putLfsObject(
        action: LfsActionLink,
        sizeBytes: Long,
        openStream: () -> InputStream
    ) {
        val body = object : RequestBody() {
            override fun contentType(): MediaType = octetStream
            override fun contentLength(): Long = sizeBytes
            override fun writeTo(sink: BufferedSink) {
                openStream().use { input ->
                    input.source().use { source ->
                        sink.writeAll(source)
                    }
                }
            }
        }
        val builder = Request.Builder().url(action.href).put(body)
        action.header?.forEach { (k, v) -> builder.header(k, v) }
        // Don't override Authorization if the LFS server supplied its own
        if (action.header?.keys?.none { it.equals("Authorization", ignoreCase = true) } != false) {
            builder.header("Authorization", "Bearer $token")
        }
        builder.header("User-Agent", "git-lfs/3.4.0 (GitHubImageSync-Android)")

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    "LFS object upload failed (${response.code}): ${response.body?.string()?.take(300)}"
                )
            }
        }
    }

    private fun postLfsVerify(action: LfsActionLink, oid: String, size: Long) {
        val json = gson.toJson(mapOf("oid" to oid, "size" to size))
        val builder = Request.Builder()
            .url(action.href)
            .post(json.toRequestBody(lfsJsonMedia))
            .header("Accept", "application/vnd.git-lfs+json")
            .header("Content-Type", "application/vnd.git-lfs+json")
            .header("User-Agent", "git-lfs/3.4.0 (GitHubImageSync-Android)")
        action.header?.forEach { (k, v) -> builder.header(k, v) }
        if (action.header?.keys?.none { it.equals("Authorization", ignoreCase = true) } != false) {
            builder.header("Authorization", "Bearer $token")
        }
        client.newCall(builder.build()).execute().use { response ->
            // Some servers return 200, some 204
            if (!response.isSuccessful && response.code != 204) {
                throw IOException(
                    "LFS verify failed (${response.code}): ${response.body?.string()?.take(200)}"
                )
            }
        }
    }

    private fun getLfsObject(action: LfsActionLink): ByteArray {
        val builder = Request.Builder().url(action.href).get()
        action.header?.forEach { (k, v) -> builder.header(k, v) }
        if (action.header?.keys?.none { it.equals("Authorization", ignoreCase = true) } != false) {
            builder.header("Authorization", "Bearer $token")
        }
        builder.header("User-Agent", "git-lfs/3.4.0 (GitHubImageSync-Android)")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("LFS object download failed (${response.code})")
            }
            return response.body?.bytes() ?: throw IOException("Empty LFS body")
        }
    }

    companion object {
        /**
         * Practical max for Contents API on mobile.
         * GitHub allows up to 100 MB, but Base64.encodeToString keeps both the
         * original bytes and the ~4/3-sized string in memory and OOMs on a
         * typical 256 MB heap for large photos/videos. Route anything larger
         * through Git LFS ([uploadLargeFile]).
         */
        const val CONTENTS_MAX_BYTES: Long = 20L * 1024 * 1024

        fun buildLfsPointer(oid: String, size: Long): String =
            "version https://git-lfs.github.com/spec/v1\n" +
                "oid sha256:$oid\n" +
                "size $size\n"

        /** Returns (oidHex, size) if [text] is an LFS pointer, else null. */
        fun parseLfsPointer(text: String): Pair<String, Long>? {
            if (!text.contains("git-lfs.github.com/spec/v1") &&
                !text.contains("oid sha256:")
            ) {
                return null
            }
            var oid: String? = null
            var size: Long? = null
            for (line in text.lineSequence()) {
                val t = line.trim()
                when {
                    t.startsWith("oid sha256:") -> oid = t.removePrefix("oid sha256:").trim()
                    t.startsWith("size ") -> size = t.removePrefix("size ").trim().toLongOrNull()
                }
            }
            return if (oid != null && size != null && oid.length == 64) oid to size else null
        }

        fun sha256Hex(openStream: () -> InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            openStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
    }
}
