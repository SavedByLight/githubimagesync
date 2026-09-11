package com.example.githubimagesync.github

import com.google.gson.annotations.SerializedName

/**
 * Response when listing a directory or getting a single file.
 * For directories the API returns a JSON array of these objects.
 */
data class ContentItem(
    val name: String,
    val path: String,
    val sha: String,
    val size: Long,
    val type: String, // "file" or "dir"
    @SerializedName("download_url") val downloadUrl: String?,
    val content: String? = null, // base64 when requesting a single file with ?ref=
    val encoding: String? = null
)

/**
 * Body for creating or updating a file via the Contents API.
 */
data class CreateOrUpdateFileRequest(
    val message: String,
    val content: String, // base64-encoded file content
    val sha: String? = null, // required when updating an existing file
    val branch: String? = null
)

data class CreateOrUpdateFileResponse(
    val content: ContentItem?,
    val commit: CommitInfo?
)

data class CommitInfo(
    val sha: String?,
    val message: String?
)

data class GitHubError(
    val message: String?,
    val documentation_url: String?
)
