package com.example.githubimagesync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.githubimagesync.github.GitHubClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs media downloads even after the UI is closed.
 * Mirrors [UploadForegroundService]: progress notification + Stop action.
 */
class DownloadForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopDownload()
                return START_NOT_STICKY
            }
            else -> {
                if (downloadJob?.isActive == true) {
                    Log.i(TAG, "Download already running – ignoring new start request")
                    return START_STICKY
                }
                targetCodename = intent?.getStringExtra(EXTRA_CODENAME)
                startForegroundWithNotification("Preparing download…")
                downloadJob = scope.launch { runDownload() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopDownload() {
        downloadJob?.cancel()
        downloadJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isRunning = false
        sendStatusBroadcast("Download cancelled")
    }

    private var targetCodename: String? = null

    private suspend fun runDownload() {
        isRunning = true
        val fromLabel = targetCodename?.let { "from /$it" } ?: "from this device"
        sendStatusBroadcast("── Sync (Download) started (background) $fromLabel ──")
        try {
            val prefs = Prefs(this)
            if (!prefs.isConfigured()) {
                finishWithError("Not configured – open the app and save settings")
                return
            }
            val client = GitHubClient(prefs.owner, prefs.repo, prefs.token)
            val imageRepo = ImageRepository(this)

            val result = imageRepo.syncDownload(
                client = client,
                encryptionPassword = prefs.encryptionPassword,
                targetCodename = targetCodename
            ) { msg ->
                updateNotification(msg)
                sendStatusBroadcast(msg)
            }

            val summary =
                "Download finished: ${result.success} downloaded, " +
                    "${result.skipped} skipped, ${result.failed} failed"
            sendStatusBroadcast(summary)
            showCompletedNotification(summary, success = result.failed == 0)
        } catch (e: CancellationException) {
            sendStatusBroadcast("Download cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            finishWithError("Download failed: ${e.message}")
        } finally {
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finishWithError(message: String) {
        sendStatusBroadcast("ERROR: $message")
        showCompletedNotification(message, success = false)
    }

    private fun startForegroundWithNotification(text: String) {
        createChannel()
        val notification = buildProgressNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildProgressNotification(text))
    }

    private fun buildProgressNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, DownloadForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val short = text.lineSequence().lastOrNull()?.take(80) ?: text.take(80)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading from GitHub")
            .setContentText(short)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showCompletedNotification(text: String, success: Boolean) {
        createChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val icon = if (success) android.R.drawable.stat_sys_download_done
        else android.R.drawable.stat_notify_error
        val title = if (success) "Download complete" else "Download finished with errors"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(icon)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_DONE, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress while downloading photos/videos from GitHub"
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun sendStatusBroadcast(message: String) {
        // Same action as upload so MainActivity can listen once
        val intent = Intent(UploadForegroundService.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(UploadForegroundService.EXTRA_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "DownloadFgService"
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1003
        private const val NOTIFICATION_ID_DONE = 1004

        const val ACTION_STOP = "com.example.githubimagesync.STOP_DOWNLOAD"
        const val EXTRA_CODENAME = "extra_codename"

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * @param targetCodename optional device folder to download from.
         *                       Null / blank = this device's own codename.
         */
        fun start(context: Context, targetCodename: String? = null) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                if (!targetCodename.isNullOrBlank()) {
                    putExtra(EXTRA_CODENAME, targetCodename)
                }
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
