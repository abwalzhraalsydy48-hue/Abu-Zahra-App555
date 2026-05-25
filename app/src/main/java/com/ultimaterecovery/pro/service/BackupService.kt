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
import com.ultimaterecovery.pro.data.local.entity.BackupEntity
import com.ultimaterecovery.pro.data.repository.BackupRepository
import com.ultimaterecovery.pro.utils.backup.BackupManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service for backup and restore operations.
 *
 * Manages the full lifecycle of backup tasks:
 * - **Create backup**: Archives selected data types with optional encryption.
 * - **Upload to cloud**: Uploads the backup archive to Google Drive or Dropbox.
 * - **Restore backup**: Downloads and extracts a backup archive.
 *
 * All operations run in the background with a persistent notification
 * showing the current phase and progress. The service supports
 * cancellation through a notification action button.
 *
 * ## Notification
 * The foreground notification shows:
 * - Current phase (Collecting → Compressing → Encrypting → Uploading / Restoring)
 * - Progress percentage and file count
 * - A "Cancel" action to abort the operation
 *
 * ## Cancellation
 * Setting [isCancelled] to true causes the next emission from the
 * [BackupManager] flow to be ignored and the service to stop gracefully.
 *
 * @see BackupManager
 * @see BackupManager.BackupProgress
 */
@AndroidEntryPoint
class BackupService : Service() {

    companion object {
        private const val TAG = "BackupService"

        /** Notification ID for the foreground service. */
        private const val NOTIFICATION_ID = 1002

        // ──────────────────────────────────────────
        // Intent Actions
        // ──────────────────────────────────────────

        /** Start a backup creation. */
        const val ACTION_START_BACKUP = "com.ultimaterecovery.pro.ACTION_START_BACKUP"

        /** Stop/cancel a running backup. */
        const val ACTION_STOP_BACKUP = "com.ultimaterecovery.pro.ACTION_STOP_BACKUP"

        /** Start a restore operation. */
        const val ACTION_RESTORE_BACKUP = "com.ultimaterecovery.pro.ACTION_RESTORE_BACKUP"

        // ──────────────────────────────────────────
        // Intent Extra Keys
        // ──────────────────────────────────────────

        /** Backup type: FULL, PHOTOS, VIDEOS, etc. */
        const val EXTRA_BACKUP_TYPE = "backup_type"

        /** Whether to encrypt the backup. */
        const val EXTRA_ENABLE_ENCRYPTION = "enable_encryption"

        /** Encryption password. */
        const val EXTRA_PASSWORD = "password"

        /** Compression level (0–9). */
        const val EXTRA_COMPRESSION_LEVEL = "compression_level"

        /** Cloud provider: LOCAL, GOOGLE_DRIVE, DROPBOX. */
        const val EXTRA_CLOUD_PROVIDER = "cloud_provider"

        /** Whether this is an incremental backup. */
        const val EXTRA_INCREMENTAL = "incremental"

        /** Database ID of the backup to restore. */
        const val EXTRA_BACKUP_ID = "backup_id"

        /** Decryption password for restore. */
        const val EXTRA_RESTORE_PASSWORD = "restore_password"

        /** Output directory for restore. */
        const val EXTRA_RESTORE_OUTPUT_DIR = "restore_output_dir"

        // ──────────────────────────────────────────
        // Broadcast Actions
        // ──────────────────────────────────────────

        /** Broadcast: backup progress updated. */
        const val BROADCAST_BACKUP_PROGRESS = "com.ultimaterecovery.pro.BACKUP_PROGRESS"

        /** Broadcast: backup completed. */
        const val BROADCAST_BACKUP_COMPLETED = "com.ultimaterecovery.pro.BACKUP_COMPLETED"

        /** Broadcast: backup failed. */
        const val BROADCAST_BACKUP_FAILED = "com.ultimaterecovery.pro.BACKUP_FAILED"

        /** Broadcast: restore completed. */
        const val BROADCAST_RESTORE_COMPLETED = "com.ultimaterecovery.pro.RESTORE_COMPLETED"

        // ──────────────────────────────────────────
        // Broadcast Extra Keys
        // ──────────────────────────────────────────

        const val EXTRA_PHASE = "phase"
        const val EXTRA_PROGRESS_FRACTION = "progress_fraction"
        const val EXTRA_FILES_PROCESSED = "files_processed"
        const val EXTRA_TOTAL_FILES = "total_files"
        const val EXTRA_BACKUP_ID_RESULT = "backup_id_result"
        const val EXTRA_ERROR_MESSAGE = "error_message"
    }

    // ──────────────────────────────────────────────
    // Dependencies
    // ──────────────────────────────────────────────

    @Inject
    lateinit var backupManager: BackupManager

    @Inject
    lateinit var backupRepository: BackupRepository

    // ──────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var isCancelled = false

    @Volatile
    private var isRunning = false

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
            ACTION_START_BACKUP -> handleStartBackup(intent)
            ACTION_STOP_BACKUP -> handleStopBackup()
            ACTION_RESTORE_BACKUP -> handleRestoreBackup(intent)
            else -> Timber.w("Unknown action: ${intent.action}")
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        isRunning = false
        Timber.d("$TAG destroyed")
        super.onDestroy()
    }

    // ──────────────────────────────────────────────
    // Backup Creation
    // ──────────────────────────────────────────────

    /**
     * Handles the start backup action.
     *
     * Parses backup configuration from the intent and starts the
     * backup flow with real-time progress updates.
     */
    private fun handleStartBackup(intent: Intent) {
        if (isRunning) {
            Timber.w("Backup already in progress")
            return
        }

        val backupTypeName = intent.getStringExtra(EXTRA_BACKUP_TYPE) ?: BackupEntity.BackupType.FULL.name
        val backupType = try {
            BackupEntity.BackupType.valueOf(backupTypeName)
        } catch (_: IllegalArgumentException) {
            BackupEntity.BackupType.FULL
        }

        val enableEncryption = intent.getBooleanExtra(EXTRA_ENABLE_ENCRYPTION, false)
        val password = intent.getStringExtra(EXTRA_PASSWORD)
        val compressionLevel = intent.getIntExtra(EXTRA_COMPRESSION_LEVEL, 6)
        val cloudProviderName = intent.getStringExtra(EXTRA_CLOUD_PROVIDER) ?: BackupEntity.CloudProvider.LOCAL.name
        val cloudProvider = try {
            BackupEntity.CloudProvider.valueOf(cloudProviderName)
        } catch (_: IllegalArgumentException) {
            BackupEntity.CloudProvider.LOCAL
        }
        val incremental = intent.getBooleanExtra(EXTRA_INCREMENTAL, false)

        val config = BackupManager.BackupConfig(
            backupType = backupType,
            enableEncryption = enableEncryption,
            password = password,
            compressionLevel = compressionLevel,
            cloudProvider = cloudProvider,
            incremental = incremental
        )

        startForeground(NOTIFICATION_ID, createBackupNotification("Preparing", 0, 0))
        isRunning = true
        isCancelled = false

        Timber.i("Starting backup: type=$backupType, encrypted=$enableEncryption, cloud=$cloudProvider")

        serviceScope.launch {
            backupManager.createBackup(config)
                .catch { e ->
                    if (e !is CancellationException) {
                        Timber.e(e, "Backup failed")
                        updateFailedNotification(e.message ?: "Backup failed")
                        broadcastFailure(e.message ?: "Backup failed")
                    }
                    stopOperation()
                }
                .collect { progress ->
                    if (isCancelled) {
                        stopOperation()
                        return@collect
                    }

                    val phaseLabel = progress.phase.name.lowercase().replaceFirstChar { it.uppercase() }
                    updateBackupNotification(
                        phaseLabel,
                        progress.filesProcessed,
                        progress.totalFiles,
                        progress.progress
                    )
                    broadcastProgress(
                        phase = progress.phase.name,
                        progressFraction = progress.progress,
                        filesProcessed = progress.filesProcessed,
                        totalFiles = progress.totalFiles,
                        backupId = progress.backupId
                    )

                    if (progress.phase == BackupManager.BackupProgress.Phase.COMPLETED) {
                        Timber.i("Backup completed: ${progress.filesProcessed} files")
                        updateCompletedNotification("Backup Complete", progress.filesProcessed)
                        broadcastCompleted(progress.backupId, progress.filesProcessed)
                        stopOperation()
                    }

                    if (progress.phase == BackupManager.BackupProgress.Phase.FAILED) {
                        Timber.e("Backup failed")
                        updateFailedNotification("Backup operation failed")
                        broadcastFailure("Backup operation failed")
                        stopOperation()
                    }
                }
        }
    }

    // ──────────────────────────────────────────────
    // Backup Restore
    // ──────────────────────────────────────────────

    /**
     * Handles the restore backup action.
     *
     * Downloads (if needed), decrypts, and extracts the backup archive.
     */
    private fun handleRestoreBackup(intent: Intent) {
        if (isRunning) {
            Timber.w("Backup operation already in progress")
            return
        }

        val backupId = intent.getLongExtra(EXTRA_BACKUP_ID, -1)
        if (backupId < 0) {
            Timber.e("Invalid backup ID for restore")
            stopSelf()
            return
        }

        val password = intent.getStringExtra(EXTRA_RESTORE_PASSWORD)
        val outputDir = intent.getStringExtra(EXTRA_RESTORE_OUTPUT_DIR)

        startForeground(NOTIFICATION_ID, createBackupNotification("Restoring", 0, 0))
        isRunning = true
        isCancelled = false

        Timber.i("Starting restore for backup ID: $backupId")

        val outputDirFile = if (outputDir != null) java.io.File(outputDir) else null

        serviceScope.launch {
            backupManager.restoreBackup(backupId, password, outputDirFile)
                .catch { e ->
                    if (e !is CancellationException) {
                        Timber.e(e, "Restore failed")
                        updateFailedNotification("Restore failed: ${e.message}")
                        broadcastFailure("Restore failed: ${e.message}")
                    }
                    stopOperation()
                }
                .collect { progress ->
                    if (isCancelled) {
                        stopOperation()
                        return@collect
                    }

                    val phaseLabel = progress.phase.name.lowercase().replaceFirstChar { it.uppercase() }
                    updateBackupNotification(
                        phaseLabel,
                        progress.filesProcessed,
                        progress.totalFiles,
                        progress.progress
                    )

                    if (progress.phase == BackupManager.BackupProgress.Phase.COMPLETED) {
                        Timber.i("Restore completed: ${progress.filesProcessed} files")
                        updateCompletedNotification("Restore Complete", progress.filesProcessed)
                        broadcastRestoreCompleted(progress.filesProcessed)
                        stopOperation()
                    }

                    if (progress.phase == BackupManager.BackupProgress.Phase.FAILED) {
                        Timber.e("Restore failed")
                        updateFailedNotification("Restore operation failed")
                        broadcastFailure("Restore operation failed")
                        stopOperation()
                    }
                }
        }
    }

    // ──────────────────────────────────────────────
    // Cancel
    // ──────────────────────────────────────────────

    /**
     * Handles the stop/cancel action.
     */
    private fun handleStopBackup() {
        Timber.i("Cancelling backup operation")
        isCancelled = true
        stopOperation()
    }

    // ──────────────────────────────────────────────
    // Notification Management
    // ──────────────────────────────────────────────

    /**
     * Creates the foreground backup notification.
     */
    private fun createBackupNotification(phase: String, processed: Int, total: Int): Notification {
        val progressPercent = if (total > 0) (processed.toFloat() / total * 100).toInt() else 0

        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BackupService::class.java).apply {
                action = ACTION_STOP_BACKUP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, UltimateRecoveryApplication.CHANNEL_BACKUP)
            .setContentTitle("Backup: $phase")
            .setContentText("$processed / $total files")
            .setSmallIcon(R.drawable.ic_notification_backup)
            .setOngoing(true)
            .setProgress(100, progressPercent, total <= 0)
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
     * Updates the backup notification with current progress.
     */
    private fun updateBackupNotification(
        phase: String,
        processed: Int,
        total: Int,
        progressFraction: Float
    ) {
        val progressPercent = (progressFraction * 100).toInt()
        val notification = NotificationCompat.Builder(this, UltimateRecoveryApplication.CHANNEL_BACKUP)
            .setContentTitle("Backup: $phase")
            .setContentText("$processed / $total files • $progressPercent%")
            .setSmallIcon(R.drawable.ic_notification_backup)
            .setOngoing(true)
            .setProgress(100, progressPercent, total <= 0)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Updates the notification to show operation completion.
     */
    private fun updateCompletedNotification(title: String, fileCount: Int) {
        val notification = NotificationCompat.Builder(this, UltimateRecoveryApplication.CHANNEL_BACKUP)
            .setContentTitle(title)
            .setContentText("$fileCount files processed")
            .setSmallIcon(R.drawable.ic_notification_backup)
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Updates the notification to show operation failure.
     */
    private fun updateFailedNotification(error: String) {
        val notification = NotificationCompat.Builder(this, UltimateRecoveryApplication.CHANNEL_BACKUP)
            .setContentTitle("Backup Failed")
            .setContentText(error.take(100))
            .setSmallIcon(R.drawable.ic_notification_backup)
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

    private fun broadcastProgress(
        phase: String,
        progressFraction: Float,
        filesProcessed: Int,
        totalFiles: Int,
        backupId: Long
    ) {
        val intent = Intent(BROADCAST_BACKUP_PROGRESS).apply {
            putExtra(EXTRA_PHASE, phase)
            putExtra(EXTRA_PROGRESS_FRACTION, progressFraction)
            putExtra(EXTRA_FILES_PROCESSED, filesProcessed)
            putExtra(EXTRA_TOTAL_FILES, totalFiles)
            putExtra(EXTRA_BACKUP_ID_RESULT, backupId)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastCompleted(backupId: Long, fileCount: Int) {
        val intent = Intent(BROADCAST_BACKUP_COMPLETED).apply {
            putExtra(EXTRA_BACKUP_ID_RESULT, backupId)
            putExtra(EXTRA_FILES_PROCESSED, fileCount)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastRestoreCompleted(fileCount: Int) {
        val intent = Intent(BROADCAST_RESTORE_COMPLETED).apply {
            putExtra(EXTRA_FILES_PROCESSED, fileCount)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastFailure(error: String) {
        val intent = Intent(BROADCAST_BACKUP_FAILED).apply {
            putExtra(EXTRA_ERROR_MESSAGE, error)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ──────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────

    private fun stopOperation() {
        isRunning = false
        isCancelled = false
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }
}
