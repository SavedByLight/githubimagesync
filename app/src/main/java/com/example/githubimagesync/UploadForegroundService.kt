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
 * Foreground service that runs media uploads even after the UI is closed.
 *
 * Started from [MainActivity]; shows a persistent notification with progress.
 * When the user swipes the app away or the process is backgrounded, the
 * upload continues until completion (or until the system kills the service
 * under extreme memory pressure).
 */
class UploadForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uploadJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopUpload()
                return START_NOT_STICKY
            }
            else -> {
                // Default: start / continue upload
                if (uploadJob?.isActive == true) {
                    Log.i(TAG, "Upload already running – ignoring new start request")
                    return START_STICKY
                }
                startForegroundWithNotification("Preparing upload…")
                uploadJob = scope.launch { runUpload() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        uploadJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopUpload() {
        uploadJob?.cancel()
        uploadJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isRunning = false
        sendStatusBroadcast("Upload cancelled")
    }

    private suspend fun runUpload() {
        isRunning = true
        sendStatusBroadcast("── Upload started (background) ──")
        try {
            val prefs = Prefs(this)
            if (!prefs.isConfigured()) {
                finishWithError("Not configured – open the app and save settings")
                return
            }
            val client = GitHubClient(prefs.owner, prefs.repo, prefs.token)
            val imageRepo = ImageRepository(this)

            val result = imageRepo.uploadAll(
                client = client,
                encryptionPassword = prefs.encryptionPassword
            ) { msg ->
                updateNotification(msg)
                sendStatusBroadcast(msg)
            }

            val summary =
                "Upload finished: ${result.success} uploaded, " +
                    "${result.skipped} skipped, ${result.failed} failed"
            sendStatusBroadcast(summary)
            showCompletedNotification(summary, success = result.failed == 0)
        } catch (e: CancellationException) {
            sendStatusBroadcast("Upload cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            finishWithError("Upload failed: ${e.message}")
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

    // ─── Notification helpers ────────────────────────────────────────────────

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
            1,
            Intent(this, UploadForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Keep the last line of multi-line progress as the short text
        val short = text.lineSequence().lastOrNull()?.take(80) ?: text.take(80)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Uploading to GitHub")
            .setContentText(short)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
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
        val icon = if (success) android.R.drawable.stat_sys_upload_done
        else android.R.drawable.stat_notify_error
        val title = if (success) "Upload complete" else "Upload finished with errors"

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
            "Uploads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress while uploading photos/videos to GitHub"
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun sendStatusBroadcast(message: String) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "UploadFgService"
        private const val CHANNEL_ID = "upload_channel"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ID_DONE = 1002

        const val ACTION_STOP = "com.example.githubimagesync.STOP_UPLOAD"
        const val ACTION_STATUS = "com.example.githubimagesync.UPLOAD_STATUS"
        const val EXTRA_MESSAGE = "message"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, UploadForegroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, UploadForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
