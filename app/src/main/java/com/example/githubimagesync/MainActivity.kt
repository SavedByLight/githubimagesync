package com.example.githubimagesync

import android.Manifest
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
            appendLog("Storage permission granted")
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            appendLog("Storage permission denied")
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

        binding.btnSaveSettings.setOnClickListener { saveSettings() }
        binding.btnSync.setOnClickListener { startSyncDownload() }
        binding.btnUpload.setOnClickListener { startUpload() }

        // Request permission early
        ensureStoragePermission()
    }

    private fun saveSettings() {
        val owner = binding.editOwner.text?.toString()?.trim().orEmpty()
        val repo = binding.editRepo.text?.toString()?.trim().orEmpty()
        val token = binding.editToken.text?.toString()?.trim().orEmpty()

        if (owner.isBlank() || repo.isBlank() || token.isBlank()) {
            Toast.makeText(this, R.string.missing_config, Toast.LENGTH_SHORT).show()
            return
        }
        prefs.owner = owner
        prefs.repo = repo
        prefs.token = token
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        appendLog("Settings saved for $owner/$repo")
    }

    private fun ensureStoragePermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
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
        if (!ensureStoragePermission()) return
        val client = requireConfig() ?: return

        setBusy(true)
        appendLog("── Sync (Download) started ──")
        lifecycleScope.launch {
            try {
                val result = imageRepo.syncDownload(client) { msg ->
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

    private fun startUpload() {
        if (!ensureStoragePermission()) return
        val client = requireConfig() ?: return

        setBusy(true)
        appendLog("── Upload started ──")
        lifecycleScope.launch {
            try {
                val result = imageRepo.uploadAll(client) { msg ->
                    appendLog(msg)
                }
                appendLog(
                    "Upload finished: ${result.success} uploaded, " +
                        "${result.skipped} skipped, ${result.failed} failed"
                )
                Toast.makeText(
                    this@MainActivity,
                    "Uploaded ${result.success} · skipped ${result.skipped}",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                appendLog("ERROR: ${e.message}")
                Toast.makeText(this@MainActivity, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }
}
