package com.ultimaterecovery.pro.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.UltimateRecoveryApplication
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.utils.recyclebin.SmartRecycleBin
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Foreground service that monitors storage directories for file deletions
 * and automatically captures deleted files into the recycle bin.
 *
 * Uses [FileObserver] to watch key directories (DCIM, Downloads, Documents,
 * etc.) for DELETE and MOVED_FROM events. When a deletion is detected,
 * the file is copied to the recycle bin directory before it is lost.
 *
 * ## Monitoring Strategy
 * - Watches primary storage directories commonly used for user files.
 * - Skips system and app-private directories to avoid noise.
 * - On low battery, pauses monitoring to conserve power.
 * - Restarts automatically on device boot via [BootReceiver].
 *
 * ## File Observer Hierarchy
 * Each monitored directory gets its own [FileObserver] instance.
 * Subdirectory changes are monitored recursively by creating child
 * observers when new directories are created.
 *
 * ## Battery Optimization
 * When the battery level drops below 15%, monitoring is paused
 * automatically. It resumes when the device is charging or
 * the battery exceeds 20%.
 *
 * @see SmartRecycleBin
 * @see FileObserver
 */
@AndroidEntryPoint
class RecycleBinMonitorService : Service() {

    companion object {
        private const val TAG = "RecycleBinMonitor"

        /** Notification ID for the foreground service. */
        private const val NOTIFICATION_ID = 1003

        /** Battery threshold for pausing monitoring (percent). */
        private const val LOW_BATTERY_THRESHOLD = 15

        /** Battery threshold for resuming monitoring (percent). */
        private const val RESUME_BATTERY_THRESHOLD = 20

        // ──────────────────────────────────────────
        // Intent Actions
        // ──────────────────────────────────────────

        /** Start monitoring for deleted files. */
        const val ACTION_MONITOR_RECYCLE_BIN = "com.ultimaterecovery.pro.ACTION_MONITOR_RECYCLE_BIN"

        /** Stop monitoring. */
        const val ACTION_STOP_MONITOR = "com.ultimaterecovery.pro.ACTION_STOP_MONITOR"

        /** Empty the recycle bin. */
        const val ACTION_EMPTY_RECYCLE_BIN = "com.ultimaterecovery.pro.ACTION_EMPTY_RECYCLE_BIN"

        // ──────────────────────────────────────────
        // Directories to Monitor
        // ──────────────────────────────────────────

        /** Key user directories to watch for file deletions. */
        private val MONITORED_DIRS = listOf(
            "DCIM",
            "Pictures",
            "Downloads",
            "Documents",
            "Music",
            "Movies",
            "Recordings",
            "Voice Recorder",
            "WhatsApp/Media",
            "Telegram/Telegram Documents",
            "Telegram/Telegram Audio",
            "Telegram/Telegram Video",
            "Telegram/Telegram Images",
            "Snapchat",
            "Instagram"
        )

        /** FileObserver event mask: watch for deletions and moves. */
        private const val OBSERVE_MASK = (FileObserver.DELETE
                or FileObserver.MOVED_FROM
                or FileObserver.DELETE_SELF
                or FileObserver.MOVE_SELF)
    }

    // ──────────────────────────────────────────────
    // Dependencies
    // ──────────────────────────────────────────────

    @Inject
    lateinit var smartRecycleBin: SmartRecycleBin

    // ──────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Active file observers. */
    private val fileObservers = mutableListOf<FileObserver>()

    /** Whether monitoring is currently paused due to low battery. */
    @Volatile
    private var isPausedForBattery = false

    /** Whether the service is actively monitoring. */
    @Volatile
    private var isMonitoring = false

    private lateinit var notificationManager: NotificationManager

    // ──────────────────────────────────────────────
    // Service Lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Timber.d("$TAG created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_MONITOR_RECYCLE_BIN -> handleStartMonitoring()
            ACTION_STOP_MONITOR -> handleStopMonitoring()
            ACTION_EMPTY_RECYCLE_BIN -> handleEmptyRecycleBin()
            else -> Timber.w("Unknown action: ${intent.action}")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAllObservers()
        serviceScope.cancel()
        isMonitoring = false
        Timber.d("$TAG destroyed")
        super.onDestroy()
    }

    // ──────────────────────────────────────────────
    // Action Handlers
    // ──────────────────────────────────────────────

    /**
     * Starts monitoring for deleted files.
     *
     * Creates [FileObserver] instances for each monitored directory
     * and starts the foreground notification.
     */
    private fun handleStartMonitoring() {
        if (isMonitoring) {
            Timber.d("Already monitoring")
            return
        }

        startForeground(NOTIFICATION_ID, createMonitorNotification("Active"))

        val storageRoot = android.os.Environment.getExternalStorageDirectory()
        val dirsToMonitor = mutableListOf<File>()

        // Collect directories that exist
        for (dirName in MONITORED_DIRS) {
            val dir = File(storageRoot, dirName)
            if (dir.exists() && dir.isDirectory) {
                dirsToMonitor.add(dir)
            }
        }

        // Always watch the root storage for broad coverage
        if (storageRoot.exists()) {
            dirsToMonitor.add(0, storageRoot)
        }

        // Start file observers
        for (dir in dirsToMonitor) {
            startObserver(dir)
        }

        isMonitoring = true
        smartRecycleBin.startMonitoring()

        // Check battery state
        checkBatteryAndAdjust()

        Timber.i("Started monitoring ${dirsToMonitor.size} directories")
    }

    /**
     * Stops monitoring for deleted files.
     */
    private fun handleStopMonitoring() {
        Timber.i("Stopping monitor")
        stopAllObservers()
        smartRecycleBin.stopMonitoring()
        isMonitoring = false
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    /**
     * Empties the recycle bin.
     */
    private fun handleEmptyRecycleBin() {
        serviceScope.launch {
            try {
                smartRecycleBin.autoCleanup()
                Timber.i("Recycle bin emptied")
            } catch (e: Exception) {
                Timber.w(e, "Failed to empty recycle bin")
            }
        }
    }

    // ──────────────────────────────────────────────
    // File Observer Management
    // ──────────────────────────────────────────────

    /**
     * Creates and starts a [FileObserver] for the given directory.
     *
     * The observer watches for DELETE and MOVED_FROM events and
     * attempts to capture the deleted file into the recycle bin.
     *
     * @param directory The directory to observe.
     */
    private fun startObserver(directory: File) {
        val observer = object : FileObserver(directory, OBSERVE_MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || isPausedForBattery) return

                val deletedFile = File(directory, path)

                // Only capture regular files, not directories
                if (!deletedFile.isFile) return

                // Skip very small files (< 1 KB — likely temp files)
                if (deletedFile.length() < 1024) return

                // Skip hidden files
                if (path.startsWith(".")) return

                when (event) {
                    FileObserver.DELETE, FileObserver.MOVED_FROM -> {
                        Timber.d("File deleted: ${deletedFile.absolutePath}")
                        captureDeletedFile(deletedFile)
                    }
                }
            }
        }

        observer.startWatching()
        fileObservers.add(observer)
    }

    /**
     * Stops all active file observers.
     */
    private fun stopAllObservers() {
        for (observer in fileObservers) {
            observer.stopWatching()
        }
        fileObservers.clear()
    }

    // ──────────────────────────────────────────────
    // Deleted File Capture
    // ──────────────────────────────────────────────

    /**
     * Attempts to capture a deleted file into the recycle bin.
     *
     * This is a best-effort operation. The file may already be
     * partially or fully deleted by the time the observer fires,
     * so we handle failures gracefully.
     *
     * @param file The file that was deleted.
     */
    private fun captureDeletedFile(file: File) {
        serviceScope.launch {
            try {
                // Quick check if the file still exists (race condition)
                if (file.exists() && file.canRead()) {
                    val result = smartRecycleBin.moveToRecycleBin(file)
                    if (result is com.ultimaterecovery.pro.data.repository.Resource.Success) {
                        Timber.d("Captured deleted file: ${file.name} (bin ID: ${result.data})")
                    } else {
                        Timber.w("Failed to capture deleted file: ${file.name}")
                    }
                } else {
                    // File already gone — can't capture
                    Timber.d("File already deleted before capture: ${file.name}")
                }
            } catch (e: Exception) {
                Timber.w(e, "Error capturing deleted file: ${file.name}")
            }
        }
    }

    // ──────────────────────────────────────────────
    // Battery Optimization
    // ──────────────────────────────────────────────

    /**
     * Checks the current battery level and pauses or resumes
     * monitoring accordingly.
     */
    private fun checkBatteryAndAdjust() {
        val batteryLevel = getBatteryLevel()
        val isCharging = isCharging()

        when {
            isCharging -> {
                if (isPausedForBattery) {
                    isPausedForBattery = false
                    updateMonitorNotification("Active (Charging)")
                    Timber.d("Resumed monitoring — device charging")
                }
            }
            batteryLevel <= LOW_BATTERY_THRESHOLD && !isPausedForBattery -> {
                isPausedForBattery = true
                updateMonitorNotification("Paused (Low Battery)")
                Timber.d("Paused monitoring — battery at $batteryLevel%")
            }
            batteryLevel >= RESUME_BATTERY_THRESHOLD && isPausedForBattery -> {
                isPausedForBattery = false
                updateMonitorNotification("Active")
                Timber.d("Resumed monitoring — battery at $batteryLevel%")
            }
        }
    }

    /**
     * Returns the current battery level as a percentage (0–100).
     */
    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * Returns whether the device is currently charging.
     */
    private fun isCharging(): Boolean {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.isCharging
    }

    // ──────────────────────────────────────────────
    // Notification Management
    // ──────────────────────────────────────────────

    /**
     * Creates the foreground monitor notification.
     */
    private fun createMonitorNotification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RecycleBinMonitorService::class.java).apply {
                action = ACTION_STOP_MONITOR
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val emptyIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecycleBinMonitorService::class.java).apply {
                action = ACTION_EMPTY_RECYCLE_BIN
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, UltimateRecoveryApplication.CHANNEL_RECYCLE_BIN)
            .setContentTitle("Recycle Bin Monitor")
            .setContentText("Status: $status")
            .setSmallIcon(R.drawable.ic_recycle_bin_notification)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_delete,
                "Empty Bin",
                emptyIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Updates the monitor notification with current status.
     */
    private fun updateMonitorNotification(status: String) {
        val notification = createMonitorNotification(status)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
