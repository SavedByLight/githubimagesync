package com.example.githubimagesync.github

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the GitHub REST Contents API.
 *
 * Docs: https://docs.github.com/en/rest/repos/contents
 */
class GitHubClient(
    private val owner: String,
    private val repo: String,
    private val token: String
) {
    private val gson = Gson()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun apiUrl(path: String): String {
        val clean = path.trimStart('/')
        return "https://api.github.com/repos/$owner/$repo/contents/$clean"
    }

    private fun authRequestBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "GitHubImageSync-Android")

    /**
     * Lists items in a directory. Returns empty list if the path does not exist (404).
     */
    suspend fun listDirectory(path: String): List<ContentItem> = withContext(Dispatchers.IO) {
        val request = authRequestBuilder(apiUrl(path)).get().build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> {
                    val body = response.body?.string() ?: return@use emptyList()
                    val type = object : TypeToken<List<ContentItem>>() {}.type
                    gson.fromJson(body, type)
                }
                404 -> emptyList()
                else -> throw IOException("GitHub list failed (${response.code}): ${response.body?.string()}")
            }
        }
    }

    /**
     * Downloads raw bytes of a file using its download_url (or falls back to Contents API).
     */
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
     * Creates or updates a file at the given path.
     * Content must be the raw bytes; they are base64-encoded here.
     *
     * @return the SHA of the new blob / commit info on success
     */
    suspend fun uploadFile(
        path: String,
        contentBytes: ByteArray,
        commitMessage: String,
        existingSha: String? = null
    ): CreateOrUpdateFileResponse = withContext(Dispatchers.IO) {
        val base64 = Base64.encodeToString(contentBytes, Base64.NO_WRAP)
        val body = CreateOrUpdateFileRequest(
            message = commitMessage,
            content = base64,
            sha = existingSha
        )
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
            gson.fromJson(responseBody, CreateOrUpdateFileResponse::class.java)
                ?: throw IOException("Empty response")
        }
    }

    /**
     * Returns the current SHA of a file if it exists, otherwise null.
     * Needed when we want to update (overwrite) an existing file.
     */
    suspend fun getFileSha(path: String): String? = withContext(Dispatchers.IO) {
        val request = authRequestBuilder(apiUrl(path)).get().build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> {
                    val body = response.body?.string() ?: return@use null
                    // Single file response is an object, not an array
                    val obj = JsonParser.parseString(body).asJsonObject
                    obj.get("sha")?.asString
                }
                404 -> null
                401, 403 -> {
                    val body = response.body?.string().orEmpty()
                    throw IOException(
                        "Auth failed (${response.code}). Check your token, scopes, and that " +
                        "owner/repo are correct. GitHub said: ${body.take(200)}"
                    )
                }
                else -> {
                    val body = response.body?.string().orEmpty()
                    throw IOException("getFileSha failed (${response.code}): ${body.take(200)}")
                }
            }
        }
    }

    /** Quick connectivity / auth check against the repo root. */
    suspend fun testAuth(): String = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$owner/$repo"
        val request = authRequestBuilder(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when (response.code) {
                200 -> "OK – authenticated to $owner/$repo"
                401 -> "401 Unauthorized – token is invalid, expired, or missing. Create a new PAT."
                403 -> "403 Forbidden – token lacks permission for this repo. Need Contents: Read and write."
                404 -> "404 Not Found – wrong owner/repo, or token cannot see a private repo."
                else -> "Unexpected ${response.code}: ${body.take(200)}"
            }
        }
    }
}

