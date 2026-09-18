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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.githubimagesync.databinding.ActivityMainBinding
import com.example.githubimagesync.github.GitHubClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Progress from [UploadForegroundService] and [DownloadForegroundService]. */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(UploadForegroundService.EXTRA_MESSAGE) ?: return
            appendLog(msg)
            if (isTerminalStatus(msg)) {
                setBusy(false)
                if (msg.startsWith("Upload finished") || msg.startsWith("Download finished")) {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun isTerminalStatus(msg: String): Boolean =
        msg.startsWith("Upload finished") ||
            msg.startsWith("Download finished") ||
            msg.startsWith("ERROR:") ||
            msg == "Upload cancelled" ||
            msg == "Download cancelled"

    private fun anyServiceRunning(): Boolean =
        UploadForegroundService.isRunning || DownloadForegroundService.isRunning

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        imageRepo = ImageRepository(this)

        val codename = imageRepo.deviceCodename()
        binding.textDeviceCodename.text = codename

        binding.editOwner.setText(prefs.owner)
        binding.editRepo.setText(prefs.repo)
        binding.editToken.setText(prefs.token)
        binding.editEncPassword.setText(prefs.encryptionPassword)

        binding.btnSaveSettings.setOnClickListener { saveSettings() }
        binding.btnSync.setOnClickListener { startSyncDownload() }
        binding.btnDownloadOther.setOnClickListener { startDownloadFromOtherDevice() }
        binding.btnUpload.setOnClickListener { startUpload() }

        when {
            UploadForegroundService.isRunning -> {
                setBusy(true)
                appendLog("Upload already running in background…")
            }
            DownloadForegroundService.isRunning -> {
                setBusy(true)
                appendLog("Download already running in background…")
            }
        }

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
        setBusy(anyServiceRunning())
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
        prefs.encryptionPassword = binding.editEncPassword.text?.toString().orEmpty()
        return GitHubClient(prefs.owner, prefs.repo, prefs.token)
    }

    private fun setBusy(busy: Boolean) {
        binding.btnSync.isEnabled = !busy
        binding.btnDownloadOther.isEnabled = !busy
        binding.btnUpload.isEnabled = !busy
        binding.btnSaveSettings.isEnabled = !busy
        binding.progressBar.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val current = binding.textLog.text?.toString().orEmpty()
            val next = if (current.isBlank()) msg else "$current\n$msg"
            val lines = next.lines()
            binding.textLog.text = if (lines.size > 30) lines.takeLast(30).joinToString("\n") else next
            binding.textStatus.text = msg
        }
    }

    /**
     * Starts a foreground service so download continues after the app is closed.
     * Duplicates (same filename already on the device) are skipped.
     * Downloads from this device's own codename folder.
     */
    private fun startSyncDownload() {
        if (anyServiceRunning()) {
            Toast.makeText(
                this,
                if (UploadForegroundService.isRunning) "Upload is running – wait or stop it first"
                else "Download already running in background",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!ensureStoragePermission()) return
        if (requireConfig() == null) return

        setBusy(true)
        appendLog("── Sync (Download) started (background) ──")
        appendLog("You can close the app – download will continue.")
        Toast.makeText(this, "Download running in background", Toast.LENGTH_SHORT).show()
        DownloadForegroundService.start(this, targetCodename = null)
    }

    /**
     * Lists other device folders in the repo and lets the user pick one to download from.
     */
    private fun startDownloadFromOtherDevice() {
        if (anyServiceRunning()) {
            Toast.makeText(
                this,
                if (UploadForegroundService.isRunning) "Upload is running – wait or stop it first"
                else "Download already running in background",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!ensureStoragePermission()) return
        val client = requireConfig() ?: return

        setBusy(true)
        appendLog(getString(R.string.listing_devices))
        lifecycleScope.launch {
            val folders = try {
                withContext(Dispatchers.IO) {
                    imageRepo.listDeviceFolders(client) { msg ->
                        runOnUiThread { appendLog(msg) }
                    }
                }
            } catch (e: Exception) {
                appendLog("ERROR listing devices: ${e.message}")
                setBusy(false)
                Toast.makeText(this@MainActivity, "Failed to list devices: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            val own = imageRepo.deviceCodename()
            // Prefer showing other devices first; still allow picking own if desired
            val choices = folders.sortedWith(
                compareBy<String> { it.equals(own, ignoreCase = true) }.thenBy { it }
            )

            if (choices.isEmpty()) {
                appendLog(getString(R.string.no_other_devices))
                setBusy(false)
                Toast.makeText(this@MainActivity, R.string.no_other_devices, Toast.LENGTH_LONG).show()
                return@launch
            }

            setBusy(false)
            val labels = choices.map { name ->
                if (name.equals(own, ignoreCase = true)) "$name (this device)" else name
            }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.choose_device_title)
                .setItems(labels) { _, which ->
                    val selected = choices[which]
                    setBusy(true)
                    appendLog("── Download from /$selected started (background) ──")
                    appendLog("You can close the app – download will continue.")
                    Toast.makeText(
                        this@MainActivity,
                        "Downloading from /$selected …",
                        Toast.LENGTH_SHORT
                    ).show()
                    DownloadForegroundService.start(this@MainActivity, targetCodename = selected)
                }
                .setOnCancelListener { setBusy(false) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> setBusy(false) }
                .show()
        }
    }

    private fun startUpload() {
        if (anyServiceRunning()) {
            Toast.makeText(
                this,
                if (DownloadForegroundService.isRunning) "Download is running – wait or stop it first"
                else "Upload already running in background",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!ensureStoragePermission()) return
        if (requireConfig() == null) return

        setBusy(true)
        appendLog("── Upload started (background) ──")
        appendLog("You can close the app – upload will continue.")
        Toast.makeText(this, "Upload running in background", Toast.LENGTH_SHORT).show()
        UploadForegroundService.start(this)
    }
}
