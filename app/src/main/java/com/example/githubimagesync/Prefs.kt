package com.example.githubimagesync

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("github_prefs", Context.MODE_PRIVATE)

    var owner: String
        get() = prefs.getString(KEY_OWNER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OWNER, value.trim()).apply()

    var repo: String
        get() = prefs.getString(KEY_REPO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REPO, value.trim()).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** Optional password used to encrypt media before upload / decrypt on download. */
    var encryptionPassword: String
        get() = prefs.getString(KEY_ENC_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ENC_PASSWORD, value).apply()

    fun isConfigured(): Boolean =
        owner.isNotBlank() && repo.isNotBlank() && token.isNotBlank()

    companion object {
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_TOKEN = "token"
        private const val KEY_ENC_PASSWORD = "enc_password"
    }
}
