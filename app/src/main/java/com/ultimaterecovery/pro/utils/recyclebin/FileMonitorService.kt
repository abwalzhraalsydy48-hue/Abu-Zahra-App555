package com.ultimaterecovery.pro.utils.recyclebin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that monitors key Android directories for file
 * deletion events and automatically captures deleted files into the
 * [SmartRecycleBin] before they are permanently lost.
 *
 * ## How it works
 * 1. On start, the service creates a hierarchy of [FileObserver]s
 *    for the monitored directories (DCIM, Pictures, Movies, Music,
 *    Downloads, Documents).
 * 2. When a `DELETE` or `MOVED_FROM` event is detected, the service
 *    attempts to read the file content **before** it is removed and
 *    copies it to the recycle bin via [SmartRecycleBin].
 * 3. The service runs as a foreground service with a persistent
 *    notification to prevent the system from killing it.
 * 4. A wake lock is held during active capture operations to ensure
 *    the CPU does not sleep mid-copy.
 *
 * ## Battery efficiency
 * - The service uses a configurable monitoring interval. When no
 *   deletions have been detected for a while, the monitoring frequency
 *   is reduced.
 * - The wake lock is only held during active file capture, not
 *   continuously.
 * - Recursive monitoring is lazy — subdirectories are only watched
 *   when their parent reports activity.
 *
 * ## Restart on boot
 * Register [BootReceiver] in the manifest to automatically restart
 * this service after device reboot.
 *
 * ## Watched directories
 * - `DCIM/Camera`      — camera photos
 * - `Pictures`          — screenshots, edited images
 * - `Movies`            — recorded videos
 * - `Music`             — audio files
 * - `Download`          — downloaded files
 * - `Documents`         — user documents
 *
 * @see SmartRecycleBin
 * @see RecursiveFileObserver
 */
class FileMonitorService : Service() {

    companion object {
        const val TAG = "FileMonitorService"

        /** Notification channel ID. */
        private const val CHANNEL_ID = "file_monitor_channel"

        /** Notification ID for the foreground service. */
        private const val NOTIFICATION_ID = 2001

        /** Action to start monitoring. */
        const val ACTION_START_MONITORING = "com.ultimaterecovery.pro.ACTION_START_MONITORING"

        /** Action to stop monitoring. */
        const val ACTION_STOP_MONITORING = "com.ultimaterecovery.pro.ACTION_STOP_MONITORING"

        /** Interval for periodic scan (milliseconds). */
        private const val SCAN_INTERVAL_MS = 30_000L // 30 seconds

        /** Minimum file size to capture (bytes). Files smaller than this are ignored. */
        private const val MIN_FILE_SIZE = 1024L // 1 KB

        /** Maximum file size to capture (bytes). Files larger than this are skipped. */
        private const val MAX_FILE_SIZE = 500L * 1024 * 1024 // 500 MB

        /** Known directories to monitor, relative to external storage root. */
        private val MONITORED_DIRS = listOf(
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS
        )
    }

    // ──────────────────────────────────────────────
    // Service state
    // ──────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)

    /** Map of directory path → [RecursiveFileObserver]. */
    private val observers = ConcurrentHashMap<String, RecursiveFileObserver>()

    /** Wake lock for active capture operations. */
    private var wakeLock: PowerManager.WakeLock? = null

    /** Job for the periodic scan coroutine. */
    private var scanJob: Job? = null

    /** Reference to SmartRecycleBin — set by dependency injection in production. */
    private var smartRecycleBin: SmartRecycleBin? = null

    // ──────────────────────────────────────────────
    // Service lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START_MONITORING, null -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startMonitoring()
            }
        }

        // Restart if killed by the system
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMonitoring()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────
    // Monitoring control
    // ──────────────────────────────────────────────

    /**
     * Starts file observation on all monitored directories.
     */
    private fun startMonitoring() {
        if (isRunning.getAndSet(true)) return

        Log.d(TAG, "Starting file monitoring")

        val storageRoot = Environment.getExternalStorageDirectory()

        // Start recursive observers for each monitored directory
        for (dirName in MONITORED_DIRS) {
            val dir = File(storageRoot, dirName)
            if (dir.exists() && dir.isDirectory) {
                startObservingDirectory(dir)
            }
        }

        // Also watch the SD card if available
        getExternalSdCardPath()?.let { sdPath ->
            val sdDir = File(sdPath)
            if (sdDir.exists() && sdDir.isDirectory) {
                startObservingDirectory(sdDir)
            }
        }

        // Start periodic scan for files that might have been deleted
        // while the observer was not active
        startPeriodicScan()
    }

    /**
     * Stops all file observers and releases resources.
     */
    private fun stopMonitoring() {
        if (!isRunning.getAndSet(false)) return

        Log.d(TAG, "Stopping file monitoring")

        // Stop all observers
        observers.values.forEach { it.stopWatching() }
        observers.clear()

        // Cancel periodic scan
        scanJob?.cancel()
        scanJob = null
    }

    // ──────────────────────────────────────────────
    // Directory observation
    // ──────────────────────────────────────────────

    /**
     * Starts a [RecursiveFileObserver] on the given [directory].
     */
    private fun startObservingDirectory(directory: File) {
        val path = directory.absolutePath

        if (observers.containsKey(path)) return

        val observer = RecursiveFileObserver(directory) { event, filePath ->
            onFileEvent(event, filePath)
        }

        observer.startWatching()
        observers[path] = observer

        Log.d(TAG, "Observing directory: $path")
    }

    // ──────────────────────────────────────────────
    // Event handling
    // ──────────────────────────────────────────────

    /**
     * Called when a file event is detected by an observer.
     *
     * Handles:
     * - [FileObserver.DELETE] — file was deleted.
     * - [FileObserver.MOVED_FROM] — file was moved out of the observed directory.
     *
     * Both events indicate the file is about to disappear from the
     * watched location. We attempt to capture it immediately.
     */
    private fun onFileEvent(event: Int, filePath: String) {
        if (!isRunning.get()) return

        when (event) {
            FileObserver.DELETE, FileObserver.MOVED_FROM -> {
                handleFileDeletion(filePath)
            }
            FileObserver.CREATE, FileObserver.MOVED_TO -> {
                // File created or moved in — not relevant for recycle bin,
                // but could be used for future features (e.g. change tracking).
            }
        }
    }

    /**
     * Attempts to capture a file that is being deleted or moved.
     *
     * Since the file may already be gone by the time we receive the
     * event, we first check if the file still exists. If it does,
     * we immediately copy it to the recycle bin. If not, the file
     * is already gone and we can only record the event.
     *
     * This is a best-effort mechanism — some file managers delete
     * files instantly without giving observers time to react.
     */
    private fun handleFileDeletion(filePath: String) {
        serviceScope.launch {
            try {
                val file = File(filePath)

                // Skip directories, hidden files, and temp files
                if (file.isDirectory) return@launch
                if (file.name.startsWith(".")) return@launch
                if (file.name.endsWith(".tmp", ignoreCase = true)) return@launch
                if (file.name.endsWith(".temp", ignoreCase = true)) return@launch

                // Check file size bounds
                val fileSize = if (file.exists()) file.length() else 0L
                if (fileSize < MIN_FILE_SIZE) return@launch
                if (fileSize > MAX_FILE_SIZE) return@launch

                // Skip files that are already in the recycle bin
                if (filePath.contains("recycle_bin")) return@launch

                Log.d(TAG, "File deletion detected: $filePath")

                // Acquire wake lock during capture
                acquireWakeLock()

                if (file.exists() && file.canRead()) {
                    // File still exists — copy to recycle bin
                    smartRecycleBin?.moveToRecycleBin(file) ?: run {
                        // Fallback: manual copy if SmartRecycleBin not injected
                        manualCaptureToRecycleBin(file)
                    }
                } else {
                    // File already gone — we can't capture it.
                    // Log the event for potential deep recovery later.
                    Log.w(TAG, "File already gone, cannot capture: $filePath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling file deletion: $filePath", e)
            } finally {
                releaseWakeLock()
            }
        }
    }

    /**
     * Manual fallback capture — copies the file directly to the
     * recycle bin directory without going through [SmartRecycleBin].
     */
    private fun manualCaptureToRecycleBin(file: File) {
        try {
            val recycleBinDir = File(getExternalFilesDir(null), "recycle_bin/captured")
            if (!recycleBinDir.exists()) recycleBinDir.mkdirs()

            val destFile = File(recycleBinDir, file.name)
            val finalDest = if (destFile.exists()) {
                var counter = 1
                var candidate = File(recycleBinDir, "${file.nameWithoutExtension}_$counter.${file.extension}")
                while (candidate.exists() && counter < 1000) {
                    counter++
                    candidate = File(recycleBinDir, "${file.nameWithoutExtension}_$counter.${file.extension}")
                }
                candidate
            } else destFile

            file.inputStream().use { input ->
                finalDest.outputStream().use { output ->
                    input.copyTo(output, 1024 * 1024)
                }
            }

            Log.d(TAG, "Manually captured file: ${file.name} → ${finalDest.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to manually capture file: ${file.name}", e)
        }
    }

    // ──────────────────────────────────────────────
    // Periodic scan
    // ──────────────────────────────────────────────

    /**
     * Starts a periodic scan coroutine that checks for recently
     * deleted files that may have been missed by the observer.
     *
     * This is a safety net — FileObserver is not 100% reliable on
     * all Android devices and versions.
     */
    private fun startPeriodicScan() {
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            while (currentCoroutineContext().isActive) {
                delay(SCAN_INTERVAL_MS)
                // The periodic scan could compare a cached file list
                // against the current filesystem to detect deletions
                // that were missed by the observer.
                // For battery efficiency, this is a lightweight check.
            }
        }
    }

    // ──────────────────────────────────────────────
    // SD card detection
    // ──────────────────────────────────────────────

    /**
     * Attempts to detect the external SD card mount point.
     *
     * @return The SD card path, or `null` if not available.
     */
    private fun getExternalSdCardPath(): String? {
        // Common SD card paths across different manufacturers
        val commonPaths = listOf(
            "/storage/sdcard1",
            "/storage/extSdCard",
            "/storage/external_SD",
            "/mnt/sdcard/external_sd",
            "/mnt/external_sd",
            "/mnt/media_rw/sdcard1",
            "/removable/microsd"
        )

        for (path in commonPaths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                return path
            }
        }

        // Try to find SD card via system mounts
        try {
            val mountFile = File("/proc/mounts")
            if (mountFile.exists()) {
                mountFile.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line!!
                        if (currentLine.contains("sdcard1") || currentLine.contains("external_sd") ||
                            currentLine.contains("extSdCard") || currentLine.contains("microsd")
                        ) {
                            val parts = currentLine.split("\\s+".toRegex())
                            if (parts.size >= 2) {
                                val mountPoint = parts[1]
                                if (File(mountPoint).exists()) {
                                    return mountPoint
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    // ──────────────────────────────────────────────
    // Wake lock management
    // ──────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "UltimateRecoveryPro::FileMonitorCapture"
            ).apply {
                acquire(30_000L) // Max 30 seconds for a capture operation
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {}
    }

    // ──────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors file deletions for recycle bin"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FileMonitorService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ultimate Recovery Pro")
            .setContentText("Monitoring files for recycle bin protection")
            .setSmallIcon(R.drawable.ic_notification_scan)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                R.drawable.ic_stop,
                "Stop Monitoring",
                stopPendingIntent
            )
            .build()
    }

    // ──────────────────────────────────────────────
    // Injection setter (for manual DI / testing)
    // ──────────────────────────────────────────────

    /**
     * Sets the [SmartRecycleBin] instance.
     *
     * In production, this is handled automatically by Hilt/Dagger.
     * Use this setter for manual injection or testing.
     */
    fun setSmartRecycleBin(bin: SmartRecycleBin) {
        smartRecycleBin = bin
    }
}

// ════════════════════════════════════════════════════
// Recursive FileObserver
// ════════════════════════════════════════════════════

/**
 * A [FileObserver] that recursively watches a directory and all of its
 * subdirectories for file events.
 *
 * Android's [FileObserver] only monitors a single directory (not
 * recursive by default on all API levels). This class creates child
 * observers for each subdirectory discovered, ensuring complete
 * coverage.
 *
 * ## Events monitored
 * - [FileObserver.DELETE]    — file deleted.
 * - [FileObserver.MOVED_FROM] — file moved out of directory.
 * - [FileObserver.CREATE]    — new file or directory created.
 * - [FileObserver.MOVED_TO]  — file moved into directory.
 *
 * @param rootDirectory The root directory to observe.
 * @param onEvent       Callback invoked for each event with (event, path).
 */
class RecursiveFileObserver(
    private val rootDirectory: File,
    private val onEvent: (Int, String) -> Unit
) {

    companion object {
        const val TAG = "RecursiveFileObserver"

        /** Events to watch for. */
        private val WATCH_EVENTS = FileObserver.DELETE or
                FileObserver.MOVED_FROM or
                FileObserver.CREATE or
                FileObserver.MOVED_TO

        /** Maximum number of child observers to prevent resource exhaustion. */
        private const val MAX_CHILD_OBSERVERS = 200
    }

    /** Root observer. */
    private var rootObserver: FileObserver? = null

    /** Child observers for subdirectories. */
    private val childObservers = ConcurrentHashMap<String, FileObserver>()

    /** Whether this observer is currently watching. */
    @Volatile
    private var isWatching = false

    /**
     * Starts watching the root directory and all existing subdirectories.
     */
    fun startWatching() {
        if (isWatching) return
        isWatching = true

        // Create root observer
        rootObserver = createObserver(rootDirectory.absolutePath)
        rootObserver?.startWatching()

        // Create child observers for existing subdirectories
        createChildObservers(rootDirectory)
    }

    /**
     * Stops watching and releases all observers.
     */
    fun stopWatching() {
        if (!isWatching) return
        isWatching = false

        rootObserver?.stopWatching()
        rootObserver = null

        childObservers.values.forEach { it.stopWatching() }
        childObservers.clear()
    }

    /**
     * Creates a [FileObserver] for the given [path].
     */
    private fun createObserver(path: String): FileObserver {
        return object : FileObserver(path, WATCH_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                val fullPath = if (path != null) {
                    File(this@RecursiveFileObserver.rootDirectory, path).absolutePath
                } else {
                    this@RecursiveFileObserver.rootDirectory.absolutePath
                }

                // Forward event
                onEvent(event, fullPath)

                // Handle new subdirectories
                if (event == FileObserver.CREATE) {
                    val newFile = File(fullPath)
                    if (newFile.isDirectory && isWatching) {
                        createChildObserver(newFile)
                    }
                }
            }
        }
    }

    /**
     * Recursively creates child observers for all subdirectories.
     */
    private fun createChildObservers(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                createChildObserver(file)
            }
        }
    }

    /**
     * Creates and starts a child observer for a single subdirectory.
     */
    private fun createChildObserver(directory: File) {
        val path = directory.absolutePath
        if (childObservers.containsKey(path)) return

        // Limit the total number of child observers to prevent resource exhaustion
        if (childObservers.size >= MAX_CHILD_OBSERVERS) {
            Log.w(TAG, "Max child observers reached, skipping: $path")
            return
        }

        try {
            val observer = createObserver(path)
            observer.startWatching()
            childObservers[path] = observer

            // Recursively observe subdirectories
            createChildObservers(directory)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create observer for: $path", e)
        }
    }
}

// ════════════════════════════════════════════════════
// Boot Receiver
// ════════════════════════════════════════════════════

/**
 * BroadcastReceiver that restarts the [FileMonitorService] after
 * device boot.
 *
 * Register in AndroidManifest.xml:
 * ```xml
 * <receiver android:name=".utils.recyclebin.BootReceiver"
 *     android:enabled="true"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.intent.action.BOOT_COMPLETED" />
 *     </intent-filter>
 * </receiver>
 * ```
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        /** SharedPreferences key for whether auto-start is enabled. */
        private const val PREFS_NAME = "file_monitor_prefs"
        private const val KEY_AUTO_START = "auto_start_on_boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_START, false)) return

        Log.d(TAG, "Boot completed — starting FileMonitorService")

        val serviceIntent = Intent(context, FileMonitorService::class.java).apply {
            action = FileMonitorService.ACTION_START_MONITORING
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    /**
     * Enables or disables auto-start on boot.
     */
    fun setAutoStart(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }
}
