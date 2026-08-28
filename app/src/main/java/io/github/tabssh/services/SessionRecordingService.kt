package io.github.tabssh.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import io.github.tabssh.R
import io.github.tabssh.ui.activities.MainActivity
import io.github.tabssh.utils.NotificationHelper
import io.github.tabssh.utils.PowerLockHelper
import io.github.tabssh.utils.VideoRecordingStorage
import io.github.tabssh.utils.logging.Logger

/**
 * Foreground service that owns the [MediaProjection] → [VirtualDisplay] →
 * [MediaRecorder] pipeline for the session video recorder (TODO.AI.md item
 * 53). Started only after the user has already granted the system
 * screen-capture consent dialog (obtained by the Activity via
 * `MediaProjectionManager.createScreenCaptureIntent()` — the consent Intent
 * cannot be shown from a Service).
 *
 * Captures the device's actual on-screen content (the standard behavior of
 * every MediaProjection-based screen recorder — there is no supported API to
 * crop capture to a single [io.github.tabssh.ui.views.TerminalView]'s region
 * without a custom GL compositing pass, so recording mirrors whatever is
 * visible, including if the user switches tabs mid-recording). The paired
 * asciinema `.cast` file (SSH-tab-only) is written independently by
 * [io.github.tabssh.terminal.recording.AsciinemaCastWriter], which the caller
 * starts/stops in lockstep with this service — this service only owns the
 * pixel/mp4 side.
 *
 * Structurally mirrors [VncKeepAliveService]: single [PowerLockHelper] for
 * wake/wifi locks while active, `startForeground` immediately in
 * `onStartCommand`, everything torn down through one `stopForegroundAndSelf`
 * path so the notification never outlives the service. Unlike
 * `VncKeepAliveService` there is no idle sweep — this service does real work
 * (encoding) for its entire lifetime and stops only on explicit user Stop,
 * recorded-tab close, or an unrecoverable capture error.
 */
class SessionRecordingService : Service() {

    private val powerLocks by lazy {
        PowerLockHelper(
            context = this,
            logTag = TAG,
            wakeLockTag = "TabSSH:SessionRecording",
            wifiLockTag = "TabSSH:SessionRecordingWifi"
        )
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFd: ParcelFileDescriptor? = null
    private var currentFilename: String? = null
    private var startedAtMillis: Long = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The system revoked/ended the projection (e.g. user stopped it
            // from the system status-bar "Stop sharing" control) — tear down
            // the same way an explicit ACTION_STOP_RECORDING would.
            Logger.i(TAG, "MediaProjection stopped externally")
            stopRecordingAndSelf()
        }
    }

    companion object {
        private const val TAG = "SessionRecordingService"
        private const val NOTIFICATION_ID = 1004

        const val ACTION_START_RECORDING = "io.github.tabssh.VIDEO_START_RECORDING"
        const val ACTION_STOP_RECORDING = "io.github.tabssh.VIDEO_STOP_RECORDING"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_TAB_TITLE = "tab_title"

        // Broadcast (local, same-process) so the Activity/indicator can react
        // to a stop that originated inside the service (cap hit, projection
        // revoked) without polling.
        const val ACTION_RECORDING_STOPPED = "io.github.tabssh.VIDEO_RECORDING_STOPPED"

        @Volatile
        var isRecording: Boolean = false
            private set

        fun startRecording(context: Context, resultCode: Int, resultData: Intent, tabTitle: String, filename: String) {
            val intent = Intent(context, SessionRecordingService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_TAB_TITLE, tabTitle)
                putExtra(EXTRA_FILENAME, filename)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, SessionRecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_STOP_RECORDING -> {
                // Must still call startForeground() before stopping on API 26+
                // if this start request is itself the one that would otherwise
                // never call it (e.g. a stray stop with nothing running).
                startForeground(NOTIFICATION_ID, buildNotification(0L))
                stopRecordingAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START_RECORDING -> {
                if (isRecording) {
                    Logger.w(TAG, "Start requested while already recording — ignoring")
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification(0L))
                val started = beginCapture(intent)
                if (!started) {
                    stopRecordingAndSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                // Unknown/empty action with nothing to do — satisfy the
                // startForeground() contract, then stop immediately.
                startForeground(NOTIFICATION_ID, buildNotification(0L))
                stopRecordingAndSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun beginCapture(intent: Intent): Boolean {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val filename = intent.getStringExtra(EXTRA_FILENAME)
        val tabTitle = intent.getStringExtra(EXTRA_TAB_TITLE) ?: "TabSSH"

        if (resultData == null || filename == null) {
            Logger.e(TAG, "Missing projection result data or filename — cannot start capture")
            return false
        }

        val fd = VideoRecordingStorage.openPendingVideoFd(this, filename)
        if (fd == null) {
            Logger.e(TAG, "Could not open a writable target for $filename")
            return false
        }

        return try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            projection.registerCallback(projectionCallback, null)

            val (width, height, densityDpi) = displayMetrics()

            val recorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(videoEncodingBitRate())
                setOutputFile(fd.fileDescriptor)
                prepare()
            }

            val display = projection.createVirtualDisplay(
                "TabSSH-SessionRecording",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface, null, null
            )

            recorder.start()

            mediaProjection = projection
            virtualDisplay = display
            mediaRecorder = recorder
            outputFd = fd
            currentFilename = filename
            startedAtMillis = System.currentTimeMillis()
            isRecording = true

            powerLocks.acquireWakeLockIndefinite()
            powerLocks.acquireWifiLock()

            Logger.i(TAG, "Started session video recording: $filename (${width}x$height)")
            updateNotification()
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start capture for $filename", e)
            try { fd.close() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Bitrate for the "Video Recording Quality" setting
     * (`preferences_terminal.xml`'s `video_recording_quality` list — same
     * raw-string-key idiom as `auto_record_sessions`, no typed
     * `PreferencesManager` accessor needed for a single-consumer setting).
     */
    private fun videoEncodingBitRate(): Int {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        return when (prefs.getString("video_recording_quality", "medium")) {
            "low" -> 3_000_000
            "high" -> 16_000_000
            else -> 8_000_000
        }
    }

    private data class Metrics(val width: Int, val height: Int, val densityDpi: Int)

    private fun displayMetrics(): Metrics {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        val size = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            size.set(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
        }
        // Even dimensions required by most H.264 encoders.
        val width = (size.x / 2) * 2
        val height = (size.y / 2) * 2
        return Metrics(width, height, metrics.densityDpi)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.d(TAG, "Service destroyed")
        teardownCapture()
        powerLocks.releaseWakeLock()
        powerLocks.releaseWifiLock()
    }

    private fun stopRecordingAndSelf() {
        val filename = currentFilename
        teardownCapture()
        if (filename != null) {
            VideoRecordingStorage.finalizePendingFile(this, filename)
            sendBroadcast(Intent(ACTION_RECORDING_STOPPED).setPackage(packageName).putExtra(EXTRA_FILENAME, filename))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardownCapture() {
        isRecording = false
        try { mediaRecorder?.stop() } catch (e: Exception) { Logger.e(TAG, "Recorder stop failed", e) }
        try { mediaRecorder?.release() } catch (e: Exception) { Logger.e(TAG, "Recorder release failed", e) }
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (e: Exception) { Logger.e(TAG, "VirtualDisplay release failed", e) }
        virtualDisplay = null
        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (e: Exception) { Logger.e(TAG, "Projection stop failed", e) }
        mediaProjection = null
        try { outputFd?.close() } catch (e: Exception) { Logger.e(TAG, "Output fd close failed", e) }
        outputFd = null
        currentFilename = null
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(startedAtMillis))
    }

    private fun buildNotification(startedAt: Long): Notification {
        val tapTarget = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapTarget,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, SessionRecordingService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (startedAt > 0) "Recording session video" else "Starting session recording…"
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SESSION_RECORDING)
            .setContentTitle("TabSSH")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setUsesChronometer(startedAt > 0)
            .apply { if (startedAt > 0) setWhen(startedAt) }
            .build()
    }
}
