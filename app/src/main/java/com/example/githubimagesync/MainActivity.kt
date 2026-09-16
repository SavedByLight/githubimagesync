package com.example.githubimagesync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.githubimagesync.databinding.ActivityMainBinding
import com.example.githubimagesync.github.GitHubClient
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var imageRepo: ImageRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            appendLog("Permissions granted")
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            appendLog("Permission denied")
        }
    }

    /** Receives progress / status messages from [UploadForegroundService]. */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(UploadForegroundService.EXTRA_MESSAGE) ?: return
            appendLog(msg)
            // When the service reports a terminal status, clear the busy UI
            if (msg.startsWith("Upload finished") ||
                msg.startsWith("ERROR:") ||
                msg == "Upload cancelled"
            ) {
                setBusy(false)
                if (msg.startsWith("Upload finished")) {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        imageRepo = ImageRepository(this)

        // Show device codename
        val codename = imageRepo.deviceCodename()
        binding.textDeviceCodename.text = codename

        // Restore saved settings
        binding.editOwner.setText(prefs.owner)
        binding.editRepo.setText(prefs.repo)
        binding.editToken.setText(prefs.token)
        binding.editEncPassword.setText(prefs.encryptionPassword)

        binding.btnSaveSettings.setOnClickListener { saveSettings() }
        binding.btnSync.setOnClickListener { startSyncDownload() }
        binding.btnUpload.setOnClickListener { startUpload() }

        // Reflect any upload that is already running (e.g. user reopened the app)
        if (UploadForegroundService.isRunning) {
            setBusy(true)
            appendLog("Upload already running in background…")
        }

        // Request permission early
        ensureStoragePermission()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(UploadForegroundService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        // Re-sync busy state in case service finished while we were stopped
        setBusy(UploadForegroundService.isRunning)
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun saveSettings() {
        val owner = binding.editOwner.text?.toString()?.trim().orEmpty()
        val repo = binding.editRepo.text?.toString()?.trim().orEmpty()
        val token = binding.editToken.text?.toString()?.trim().orEmpty()
        val encPassword = binding.editEncPassword.text?.toString().orEmpty()

        if (owner.isBlank() || repo.isBlank() || token.isBlank()) {
            Toast.makeText(this, R.string.missing_config, Toast.LENGTH_SHORT).show()
            return
        }
        prefs.owner = owner
        prefs.repo = repo
        prefs.token = token
        prefs.encryptionPassword = encPassword
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        val encNote = if (CryptoHelper.isEncryptionEnabled(encPassword)) " (encryption ON)" else " (encryption OFF)"
        appendLog("Settings saved for $owner/$repo$encNote")
    }

    private fun ensureStoragePermission(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.READ_MEDIA_IMAGES
            permissions += Manifest.permission.READ_MEDIA_VIDEO
            // Needed for the upload progress notification
            permissions += Manifest.permission.POST_NOTIFICATIONS
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isEmpty()) {
            true
        } else {
            permissionLauncher.launch(missing.toTypedArray())
            false
        }
    }

    private fun requireConfig(): GitHubClient? {
        if (!prefs.isConfigured()) {
            // Try reading from the current EditText values in case user typed but didn't press Save
            val owner = binding.editOwner.text?.toString()?.trim().orEmpty()
            val repo = binding.editRepo.text?.toString()?.trim().orEmpty()
            val token = binding.editToken.text?.toString()?.trim().orEmpty()
            if (owner.isBlank() || repo.isBlank() || token.isBlank()) {
                Toast.makeText(this, R.string.missing_config, Toast.LENGTH_SHORT).show()
                return null
            }
            prefs.owner = owner
            prefs.repo = repo
            prefs.token = token
        }
        // Always pick up latest encryption password from the field (may not have been saved)
        prefs.encryptionPassword = binding.editEncPassword.text?.toString().orEmpty()
        return GitHubClient(prefs.owner, prefs.repo, prefs.token)
    }

    private fun setBusy(busy: Boolean) {
        binding.btnSync.isEnabled = !busy
        binding.btnUpload.isEnabled = !busy
        binding.btnSaveSettings.isEnabled = !busy
        binding.progressBar.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val current = binding.textLog.text?.toString().orEmpty()
            val next = if (current.isBlank()) msg else "$current\n$msg"
            // Keep last ~30 lines
            val lines = next.lines()
            binding.textLog.text = if (lines.size > 30) lines.takeLast(30).joinToString("\n") else next
            binding.textStatus.text = msg
        }
    }

    private fun startSyncDownload() {
        if (UploadForegroundService.isRunning) {
            Toast.makeText(this, "Upload is running in background – wait or stop it first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensureStoragePermission()) return
        val client = requireConfig() ?: return

        setBusy(true)
        appendLog("── Sync (Download) started ──")
        lifecycleScope.launch {
            try {
                val result = imageRepo.syncDownload(
                    client,
                    encryptionPassword = prefs.encryptionPassword
                ) { msg ->
                    appendLog(msg)
                }
                appendLog(
                    "Sync finished: ${result.success} downloaded, " +
                        "${result.skipped} skipped, ${result.failed} failed"
                )
                Toast.makeText(
                    this@MainActivity,
                    "Downloaded ${result.success} · skipped ${result.skipped}",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                appendLog("ERROR: ${e.message}")
                Toast.makeText(this@MainActivity, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    /**
     * Starts a foreground service that keeps uploading even after the user
     * leaves the app or swipes it away. Progress appears in a notification
     * and is also broadcast back to this activity when it is open.
     */
    private fun startUpload() {
        if (UploadForegroundService.isRunning) {
            Toast.makeText(this, "Upload already running in background", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensureStoragePermission()) return
        // Validate & persist settings before handing off to the service
        if (requireConfig() == null) return

        setBusy(true)
        appendLog("── Upload started (background) ──")
        appendLog("You can close the app – upload will continue.")
        Toast.makeText(this, "Upload running in background", Toast.LENGTH_SHORT).show()
        UploadForegroundService.start(this)
    }
}
