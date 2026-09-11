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
    val content: String? = null, // base64 when requesting a single file
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

// ─── Git LFS Batch API ───────────────────────────────────────────────────────

data class LfsBatchRequest(
    val operation: String, // "upload" or "download"
    val transfers: List<String> = listOf("basic"),
    val objects: List<LfsObjectSpec>
)

data class LfsObjectSpec(
    val oid: String, // sha256 hex
    val size: Long
)

data class LfsBatchResponse(
    val transfer: String? = null,
    val objects: List<LfsObjectResult>? = null
)

data class LfsObjectResult(
    val oid: String,
    val size: Long,
    val authenticated: Boolean? = null,
    val actions: LfsActions? = null,
    val error: LfsObjectError? = null
)

data class LfsActions(
    val upload: LfsActionLink? = null,
    val download: LfsActionLink? = null,
    val verify: LfsActionLink? = null
)

data class LfsActionLink(
    val href: String,
    val header: Map<String, String>? = null,
    @SerializedName("expires_in") val expiresIn: Long? = null
)

data class LfsObjectError(
    val code: Int? = null,
    val message: String? = null
)
