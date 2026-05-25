package com.ultimaterecovery.pro.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.UltimateRecoveryApplication
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity
import com.ultimaterecovery.pro.data.repository.ScanSessionRepository
import com.ultimaterecovery.pro.engine.recovery.FoundFileInfo
import com.ultimaterecovery.pro.engine.scanner.IScanEngine
import com.ultimaterecovery.pro.engine.scanner.ScanEngine
import com.ultimaterecovery.pro.engine.scanner.ScanState
import com.ultimaterecovery.pro.ui.activities.ScanActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service for executing storage scan operations.
 *
 * Runs the scan engine in the background with a persistent notification
 * showing real-time progress. Supports pause, resume, and cancel
 * operations through notification action buttons and broadcast intents.
 *
 * ## Lifecycle
 * 1. Activity starts the service via [ACTION_START_SCAN] with scan parameters.
 * 2. Service creates a foreground notification and begins scanning.
 * 3. Progress updates are reflected in the notification and broadcast
 *    to any listening UI components.
 * 4. On completion, the notification is updated with results and the
 *    service stops itself.
 *
 * ## Notification Actions
 * - **Pause/Resume**: Toggles scan pause state.
 * - **Cancel**: Cancels the scan and stops the service.
 *
 * ## Scan Results
 * On successful completion, scan results are saved to the database
 * via [ScanSessionRepository] and a completion broadcast is sent.
 *
 * @see ScanEngine
 * @see ScanState
 */
@AndroidEntryPoint
class ScanService : Service() {

    companion object {
        private const val TAG = "ScanService"

        /** Notification ID for the foreground service. */
        private const val NOTIFICATION_ID = 1001

        // ──────────────────────────────────────────
        // Intent Actions
        // ──────────────────────────────────────────

        /** Start a scan operation. */
        const val ACTION_START_SCAN = "com.ultimaterecovery.pro.ACTION_START_SCAN"

        /** Stop/cancel a running scan. */
        const val ACTION_STOP_SCAN = "com.ultimaterecovery.pro.ACTION_STOP_SCAN"

        /** Pause a running scan. */
        const val ACTION_PAUSE_SCAN = "com.ultimaterecovery.pro.ACTION_PAUSE_SCAN"

        /** Resume a paused scan. */
        const val ACTION_RESUME_SCAN = "com.ultimaterecovery.pro.ACTION_RESUME_SCAN"

        // ──────────────────────────────────────────
        // Intent Extra Keys
        // ──────────────────────────────────────────

        /** Scan type: quick, deep, signature, raw, partition. */
        const val EXTRA_SCAN_TYPE = "scan_type"

        /** Comma-separated list of paths to scan. */
        const val EXTRA_SCAN_PATHS = "scan_paths"

        /** Comma-separated list of file categories to scan. */
        const val EXTRA_CATEGORIES = "categories"

        /** Partition path for raw scan. */
        const val EXTRA_PARTITION_PATH = "partition_path"

        // ──────────────────────────────────────────
        // Broadcast Actions
        // ──────────────────────────────────────────

        /** Broadcast: scan progress updated. */
        const val BROADCAST_SCAN_PROGRESS = "com.ultimaterecovery.pro.SCAN_PROGRESS"

        /** Broadcast: scan completed. */
        const val BROADCAST_SCAN_COMPLETED = "com.ultimaterecovery.pro.SCAN_COMPLETED"

        /** Broadcast: scan failed. */
        const val BROADCAST_SCAN_FAILED = "com.ultimaterecovery.pro.SCAN_FAILED"

        // ──────────────────────────────────────────
        // Broadcast Extra Keys
        // ──────────────────────────────────────────

        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_FILES_FOUND = "files_found"
        const val EXTRA_CURRENT_PATH = "current_path"
        const val EXTRA_TOTAL_FILES = "total_files"
        const val EXTRA_TOTAL_SIZE = "total_size"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_ERROR = "error"
    }

    // ──────────────────────────────────────────────
    // Dependencies
    // ──────────────────────────────────────────────

    @Inject
    lateinit var scanEngine: IScanEngine

    @Inject
    lateinit var scanSessionRepository: ScanSessionRepository

    // ──────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isPaused = false
    private var isScanning = false
    private var currentScanType: ScanSessionEntity.ScanType = ScanSessionEntity.ScanType.QUICK

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
            ACTION_START_SCAN -> handleStartScan(intent)
            ACTION_STOP_SCAN -> handleStopScan()
            ACTION_PAUSE_SCAN -> handlePauseScan()
            ACTION_RESUME_SCAN -> handleResumeScan()
            else -> Timber.w("Unknown action: ${intent.action}")
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        isScanning = false
        Timber.d("$TAG destroyed")
        super.onDestroy()
    }

    // ──────────────────────────────────────────────
    // Action Handlers
    // ──────────────────────────────────────────────

    /**
     * Handles the start scan action.
     *
     * Parses scan parameters from the intent, starts the foreground
     * notification, and launches the scan engine.
     */
    private fun handleStartScan(intent: Intent) {
        if (isScanning) {
            Timber.w("Scan already in progress")
            return
        }

        val scanTypeName = intent.getStringExtra(EXTRA_SCAN_TYPE) ?: ScanSessionEntity.ScanType.QUICK.name
        currentScanType = try {
            ScanSessionEntity.ScanType.valueOf(scanTypeName)
        } catch (_: IllegalArgumentException) {
            ScanSessionEntity.ScanType.QUICK
        }

        val pathsStr = intent.getStringExtra(EXTRA_SCAN_PATHS) ?: ""
        val paths = if (pathsStr.isNotBlank()) pathsStr.split(",").map { it.trim() } else emptyList()

        val categoriesStr = intent.getStringExtra(EXTRA_CATEGORIES) ?: ""
        val categories = if (categoriesStr.isNotBlank()) {
            categoriesStr.split(",").mapNotNull {
                try { RecoveredFileEntity.FileCategory.valueOf(it.trim()) } catch (_: Exception) { null }
            }
        } else emptyList()

        val partitionPath = intent.getStringExtra(EXTRA_PARTITION_PATH)

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createScanNotification(0f, 0, ""))

        isScanning = true
        isPaused = false

        Timber.i("Starting $currentScanType scan on paths: $paths")

        serviceScope.launch {
            val scanFlow = when (currentScanType) {
                ScanSessionEntity.ScanType.QUICK -> scanEngine.startQuickScan(paths, categories)
                ScanSessionEntity.ScanType.DEEP -> scanEngine.startDeepScan(paths, categories)
                ScanSessionEntity.ScanType.SIGNATURE -> scanEngine.startSignatureScan(paths)
                ScanSessionEntity.ScanType.RAW -> {
                    if (partitionPath != null) scanEngine.startRawScan(partitionPath)
                    else {
                        broadcastError("No partition path specified for raw scan")
                        stopScan()
                        return@launch
                    }
                }
                ScanSessionEntity.ScanType.PARTITION -> scanEngine.startPartitionScan()
            }

            scanFlow
                .catch { e ->
                    Timber.e(e, "Scan flow error")
                    broadcastError(e.message ?: "Scan failed unexpectedly")
                    stopScan()
                }
                .collect { state ->
                    handleScanState(state)
                }
        }
    }

    /**
     * Handles scan state updates from the engine.
     */
    private fun handleScanState(state: ScanState) {
        when (state) {
            is ScanState.Scanning -> {
                updateScanNotification(state.progress, state.filesFound, state.currentPath)
                broadcastProgress(state.progress, state.filesFound, state.currentPath)
            }
            is ScanState.Paused -> {
                isPaused = true
                updateScanNotification(state.progress, state.filesFound, "Paused")
            }
            is ScanState.Completed -> {
                Timber.i("Scan completed: ${state.totalFiles} files found, ${state.totalSize} bytes")
                saveScanResults(state)
                updateCompletedNotification(state.totalFiles, state.totalSize, state.durationMs)
                broadcastCompleted(state.totalFiles, state.totalSize, state.durationMs)
                stopScan()
            }
            is ScanState.Failed -> {
                Timber.e("Scan failed: ${state.error}")
                updateFailedNotification(state.error)
                broadcastError(state.error)
                stopScan()
            }
            is ScanState.Cancelled -> {
                Timber.i("Scan cancelled")
                stopScan()
            }
            is ScanState.Idle -> {
                // No action needed
            }
        }
    }

    /**
     * Handles the stop/cancel action.
     */
    private fun handleStopScan() {
        Timber.i("Stopping scan")
        scanEngine.cancelScan()
        stopScan()
    }

    /**
     * Handles the pause action.
     */
    private fun handlePauseScan() {
        if (!isScanning || isPaused) return
        Timber.i("Pausing scan")
        scanEngine.pauseScan()
        isPaused = true
    }

    /**
     * Handles the resume action.
     */
    private fun handleResumeScan() {
        if (!isScanning || !isPaused) return
        Timber.i("Resuming scan")
        scanEngine.resumeScan()
        isPaused = false
    }

    // ──────────────────────────────────────────────
    // Notification Management
    // ──────────────────────────────────────────────

    /**
     * Creates the foreground scan notification with progress info.
     */
    private fun createScanNotification(progress: Float, filesFound: Int, currentPath: String): Notification {
        val progressPercent = (progress * 100).toInt()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ScanActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScanService::class.java).apply {
                action = if (isPaused) ACTION_RESUME_SCAN else ACTION_PAUSE_SCAN
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScanService::class.java).apply {
                action = ACTION_STOP_SCAN
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeLabel = if (isPaused) "Resume" else "Pause"

        return NotificationCompat.Builder(this, UltimateRecoveryApplication.CHANNEL_SCAN)
            .setContentTitle("Scanning Storage")
            .setContentText("$filesFound files found • $progressPercent%")
            .setSubText(currentPath.takeLast(50))
            .setSmallIcon(R.drawable.ic_scan_notification)
            .setOngoing(true)
            .setProgress(100, progressPercent, false)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                pauseResumeLabel,
                pauseResumeIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelIntent
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Updates the scan notification with current progress.
     */
    private fun updateScanNotification(progress: Float, filesFound: Int, currentPath: String) {
        val notification = createScanNotification(progress, filesFound, currentPath)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Updates the notification to show scan completion.
     */
    private fun updateCompletedNotification(totalFiles: Int, totalSize: Long, durationMs: Long) {
        val sizeStr = formatSize(totalSize)
        val durationSec = durationMs / 1000

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ScanActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, UltimateRecoveryApplication.CHANNEL_SCAN)
            .setContentTitle("Scan Complete")
            .setContentText("$totalFiles files found ($sizeStr) in ${durationSec}s")
            .setSmallIcon(R.drawable.ic_scan_notification)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Updates the notification to show scan failure.
     */
    private fun updateFailedNotification(error: String) {
        val notification = NotificationCompat.Builder(this, UltimateRecoveryApplication.CHANNEL_SCAN)
            .setContentTitle("Scan Failed")
            .setContentText(error.take(100))
            .setSmallIcon(R.drawable.ic_scan_notification)
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // ──────────────────────────────────────────────
    // Broadcast Helpers
    // ──────────────────────────────────────────────

    /**
     * Broadcasts scan progress to any listening UI components.
     */
    private fun broadcastProgress(progress: Float, filesFound: Int, currentPath: String) {
        val intent = Intent(BROADCAST_SCAN_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_FILES_FOUND, filesFound)
            putExtra(EXTRA_CURRENT_PATH, currentPath)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /**
     * Broadcasts scan completion with result summary.
     */
    private fun broadcastCompleted(totalFiles: Int, totalSize: Long, durationMs: Long) {
        val intent = Intent(BROADCAST_SCAN_COMPLETED).apply {
            putExtra(EXTRA_TOTAL_FILES, totalFiles)
            putExtra(EXTRA_TOTAL_SIZE, totalSize)
            putExtra(EXTRA_DURATION_MS, durationMs)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /**
     * Broadcasts a scan error.
     */
    private fun broadcastError(error: String) {
        val intent = Intent(BROADCAST_SCAN_FAILED).apply {
            putExtra(EXTRA_ERROR, error)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ──────────────────────────────────────────────
    // Result Persistence
    // ──────────────────────────────────────────────

    /**
     * Saves scan results to the database via [ScanSessionRepository].
     */
    private fun saveScanResults(state: ScanState.Completed) {
        serviceScope.launch {
            try {
                val session = com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity(
                    startTime = System.currentTimeMillis() - state.durationMs,
                    endTime = System.currentTimeMillis(),
                    scanType = currentScanType,
                    status = ScanSessionEntity.ScanStatus.COMPLETED,
                    totalFilesFound = state.totalFiles,
                    totalSizeFound = state.totalSize,
                    progress = 1f,
                    storagePath = state.results.firstOrNull()?.path ?: ""
                )
                scanSessionRepository.createSession(session)
                Timber.d("Scan session saved to database")
            } catch (e: Exception) {
                Timber.w(e, "Failed to save scan session")
            }
        }
    }

    // ──────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────

    /**
     * Stops the scan and removes the foreground service.
     */
    private fun stopScan() {
        isScanning = false
        isPaused = false
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    /**
     * Formats byte count to human-readable string.
     */
    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.1f GB".format(gb)
    }
}
