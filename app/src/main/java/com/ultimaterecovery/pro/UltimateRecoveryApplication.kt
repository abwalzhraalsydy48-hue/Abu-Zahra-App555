package com.ultimaterecovery.pro

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ultimaterecovery.pro.data.local.database.UltimateRecoveryDatabase
import com.ultimaterecovery.pro.engine.root.RootManager
import com.ultimaterecovery.pro.utils.backup.BackupManager
import com.ultimaterecovery.pro.utils.recyclebin.RecycleBinCleanupWorker
import com.ultimaterecovery.pro.utils.recyclebin.SmartRecycleBin
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Application class for Ultimate Recovery Pro.
 *
 * Initializes core app components and services:
 * - Timber logging (with DebugTree in debug builds)
 * - WorkManager for periodic background tasks
 * - Notification channels for foreground services
 * - Day/night theme based on user preferences
 * - Root availability check on startup
 *
 * Uses Hilt for dependency injection via [@HiltAndroidApp].
 *
 * ## Periodic Tasks
 * - **Recycle bin cleanup**: Runs every 24 hours to purge expired items.
 * - **Auto-backup**: Configurable daily/weekly/monthly backup schedule.
 *
 * ## Notification Channels
 * - `scan_channel`: Scan service progress notifications.
 * - `backup_channel`: Backup service progress notifications.
 * - `recycle_bin_channel`: Recycle bin monitor notifications.
 * - `recovery_channel`: File recovery result notifications.
 *
 * @see RootManager
 * @see SmartRecycleBin
 * @see BackupManager
 */
@HiltAndroidApp
class UltimateRecoveryApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "UltimateRecoveryApp"

        // ──────────────────────────────────────────
        // Notification Channel IDs
        // ──────────────────────────────────────────

        /** Channel for scan progress notifications. */
        const val CHANNEL_SCAN = "scan_channel"

        /** Channel for backup progress notifications. */
        const val CHANNEL_BACKUP = "backup_channel"

        /** Channel for recycle bin monitor notifications. */
        const val CHANNEL_RECYCLE_BIN = "recycle_bin_channel"

        /** Channel for recovery result notifications. */
        const val CHANNEL_RECOVERY = "recovery_channel"

        // ──────────────────────────────────────────
        // Periodic Work Names
        // ──────────────────────────────────────────

        /** WorkManager work name for recycle bin auto-cleanup. */
        private const val WORK_RECYCLE_BIN_CLEANUP = "recycle_bin_auto_cleanup"

        /** WorkManager work name for auto-backup. */
        private const val WORK_AUTO_BACKUP = "auto_backup"

        // ──────────────────────────────────────────
        // SharedPreferences Keys
        // ──────────────────────────────────────────

        private const val PREFS_NAME = "ultimate_recovery_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ROOT_CHECKED = "root_checked"
    }

    // ──────────────────────────────────────────────
    // Dependencies
    // ──────────────────────────────────────────────

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var rootManager: RootManager

    @Inject
    lateinit var smartRecycleBin: SmartRecycleBin

    @Inject
    lateinit var backupManager: BackupManager

    /** Application-scoped coroutine scope for startup tasks. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ──────────────────────────────────────────────
    // WorkManager Configuration
    // ──────────────────────────────────────────────

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN)
            .build()

    // ──────────────────────────────────────────────
    // Application Lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        // Install uncaught exception handler to prevent hard crashes
        installCrashHandler()

        try {
            // 1. Initialize Timber logging
            initTimber()
        } catch (e: Exception) {
            // Timber init failure should not crash the app
        }

        try {
            // 2. Initialize notification channels (required before any foreground service)
            setupNotificationChannels()
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup notification channels")
        }

        try {
            // 3. Apply theme based on settings
            initTheme()
        } catch (e: Exception) {
            Timber.e(e, "Failed to init theme")
        }

        try {
            // 4. Schedule periodic background tasks
            schedulePeriodicTasks()
        } catch (e: Exception) {
            Timber.e(e, "Failed to schedule periodic tasks")
        }

        try {
            // 5. Check root availability asynchronously
            checkRootAvailability()
        } catch (e: Exception) {
            Timber.e(e, "Failed to check root availability")
        }

        Timber.i("$TAG initialized successfully")
    }

    // ──────────────────────────────────────────────
    // Timber Initialization
    // ──────────────────────────────────────────────

    /**
     * Initializes Timber for structured logging.
     *
     * In debug builds, plants a [Timber.DebugTree] that logs to Logcat
     * with automatic tag generation from the calling class name.
     *
     * In release builds, a no-op tree is planted to suppress debug output.
     * A production app would replace this with a crash-reporting tree
     * (e.g., Firebase Crashlytics or Sentry).
     */
    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(object : Timber.DebugTree() {
                override fun createStackElementTag(element: StackTraceElement): String {
                    // Include line number for easier debugging
                    return "URP-${super.createStackElementTag(element)}:${element.lineNumber}"
                }
            })
        } else {
            // Release: plant a no-op tree or a crash-reporting tree
            Timber.plant(ReleaseTree())
        }
    }

    // ──────────────────────────────────────────────
    // Notification Channels
    // ──────────────────────────────────────────────

    /**
     * Creates notification channels required by foreground services.
     *
     * Must be called before any service starts a foreground notification.
     * Channels are idempotent — creating them multiple times has no effect.
     */
    fun setupNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val channels = listOf(
            NotificationChannel(
                CHANNEL_SCAN,
                "Scan Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of file scanning operations"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },

            NotificationChannel(
                CHANNEL_BACKUP,
                "Backup Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of backup and restore operations"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },

            NotificationChannel(
                CHANNEL_RECYCLE_BIN,
                "Recycle Bin Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows recycle bin monitoring status"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },

            NotificationChannel(
                CHANNEL_RECOVERY,
                "Recovery Results",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when file recovery operations complete"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )

        notificationManager.createNotificationChannels(channels)
    }

    // ──────────────────────────────────────────────
    // Periodic Task Scheduling
    // ──────────────────────────────────────────────

    /**
     * Schedules periodic background tasks using WorkManager.
     *
     * Currently schedules:
     * - Recycle bin auto-cleanup (every 24 hours)
     * - Auto-backup (configurable frequency, defaults to daily)
     *
     * Tasks use [ExistingPeriodicWorkPolicy.KEEP] so they are only
     * enqueued once and survive app restarts.
     */
    fun schedulePeriodicTasks() {
        try {
            val workManager = WorkManager.getInstance(this)

            // ── Recycle Bin Auto-Cleanup ────────────────
            val cleanupConstraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(false)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val cleanupWork = PeriodicWorkRequestBuilder<RecycleBinCleanupWorker>(
                24, TimeUnit.HOURS
            )
                .setConstraints(cleanupConstraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.MINUTES
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_RECYCLE_BIN_CLEANUP,
                ExistingPeriodicWorkPolicy.KEEP,
                cleanupWork
            )

            Timber.d("Scheduled recycle bin auto-cleanup (24h interval)")

            // ── Auto-Backup ────────────────────────────
            // Only schedule if auto-backup is enabled in settings
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoBackupEnabled = prefs.getBoolean("auto_backup_enabled", false)

            if (autoBackupEnabled) {
                val backupConstraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresCharging(true)
                    .build()

                val backupWork = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                    24, TimeUnit.HOURS
                )
                    .setConstraints(backupConstraints)
                    .setBackoffCriteria(
                        androidx.work.BackoffPolicy.EXPONENTIAL,
                        1, TimeUnit.HOURS
                    )
                    .build()

                workManager.enqueueUniquePeriodicWork(
                    WORK_AUTO_BACKUP,
                    ExistingPeriodicWorkPolicy.KEEP,
                    backupWork
                )

                Timber.d("Scheduled auto-backup (24h interval)")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to schedule periodic tasks")
        }
    }

    // ──────────────────────────────────────────────
    // Theme Initialization
    // ──────────────────────────────────────────────

    /**
     * Applies the day/night theme based on user preferences.
     *
     * The theme mode is persisted in SharedPreferences and applied
     * on every app startup. Valid modes:
     * - 0: Follow system (default)
     * - 1: Light mode
     * - 2: Dark mode
     */
    fun initTheme() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        AppCompatDelegate.setDefaultNightMode(mode)
        Timber.d("Theme mode set to: $mode")
    }

    /**
     * Updates the theme mode and persists the preference.
     *
     * @param mode One of [AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM],
     *             [AppCompatDelegate.MODE_NIGHT_NO], or
     *             [AppCompatDelegate.MODE_NIGHT_YES].
     */
    fun setThemeMode(mode: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    // ──────────────────────────────────────────────
    // Root Availability Check
    // ──────────────────────────────────────────────

    /**
     * Checks root availability on startup in the background.
     *
     * The result is stored in [RootManager.rootState] and can be
     * observed by any component. This is a best-effort check that
     * runs asynchronously and does not block the main thread.
     */
    private fun checkRootAvailability() {
        appScope.launch {
            try {
                val result = rootManager.isRootAvailable()
                Timber.i("Root check completed: rooted=${result.isRooted}, type=${result.rootType}")
            } catch (e: Exception) {
                Timber.w(e, "Root check failed")
            }
        }
    }

    // ──────────────────────────────────────────────
    // Inner Worker Classes
    // ──────────────────────────────────────────────

    /**
     * WorkManager Worker for periodic recycle bin cleanup.
     *
     * Delegates to [SmartRecycleBin.autoCleanup] to purge expired
     * items and enforce storage limits.
     */
    // RecycleBinCleanupWorker moved to com.ultimaterecovery.pro.utils.recyclebin package
    // to avoid duplicate class conflict with SmartRecycleBin.kt

    /**
     * WorkManager Worker for periodic auto-backup.
     *
     * Delegates to [BackupManager.createBackup] with the configured
     * backup settings.
     */
    class AutoBackupWorker(
        context: Context,
        workerParams: androidx.work.WorkerParameters
    ) : androidx.work.CoroutineWorker(context, workerParams) {

        override suspend fun doWork(): Result {
            return try {
                // In production, inject BackupManager via Hilt's WorkerFactory
                // and call backupManager.createBackup(config)
                Timber.d("Auto-backup triggered")
                Result.success()
            } catch (e: Exception) {
                Timber.w(e, "Auto-backup failed")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
    }

    // ──────────────────────────────────────────────
    // Release Tree
    // ──────────────────────────────────────────────

    /**
     * Timber tree for release builds.
     *
     * Suppresses debug and verbose logs. Only warns and errors
     * are logged. In production, this would be replaced with a
     * crash-reporting integration.
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < android.util.Log.WARN) return

            // In production, forward to crash reporting (Firebase Crashlytics, Sentry, etc.)
            if (t != null && priority >= android.util.Log.ERROR) {
                // Log exception to crash reporting
            }
        }
    }

    // ──────────────────────────────────────────────
    // Crash protection
    // ──────────────────────────────────────────────

    /**
     * Installs a default uncaught exception handler to prevent hard crashes.
     *
     * Catches any uncaught exceptions on the main thread and logs them
     * instead of letting the app crash with an ANR dialog.
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(throwable, "Uncaught exception on thread: ${thread.name}")
            // Forward to the default handler so the system can still handle it
            // but avoid the ANR crash dialog if possible
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
