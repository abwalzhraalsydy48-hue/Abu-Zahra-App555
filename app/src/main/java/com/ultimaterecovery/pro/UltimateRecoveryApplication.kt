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
import com.ultimaterecovery.pro.engine.root.RootManager
import com.ultimaterecovery.pro.utils.recyclebin.RecycleBinCleanupWorker
import com.ultimaterecovery.pro.utils.recyclebin.SmartRecycleBin
import com.ultimaterecovery.pro.utils.backup.BackupManager
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
 * تم تحسين هذه الفئة للعمل على جميع الأجهزة بما في ذلك itel P55 وغيرها.
 * جميع عمليات التهيئة محاطة بـ try-catch لمنع أي crash.
 */
@HiltAndroidApp
class UltimateRecoveryApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "UltimateRecoveryApp"
        const val CHANNEL_SCAN = "scan_channel"
        const val CHANNEL_BACKUP = "backup_channel"
        const val CHANNEL_RECYCLE_BIN = "recycle_bin_channel"
        const val CHANNEL_RECOVERY = "recovery_channel"
        private const val WORK_RECYCLE_BIN_CLEANUP = "recycle_bin_auto_cleanup"
        private const val WORK_AUTO_BACKUP = "auto_backup"
        private const val PREFS_NAME = "ultimate_recovery_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var rootManager: RootManager

    @Inject
    lateinit var smartRecycleBin: SmartRecycleBin

    @Inject
    lateinit var backupManager: BackupManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = try {
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN)
                .build()
        } catch (e: Exception) {
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.WARN)
                .build()
        }

    override fun onCreate() {
        super.onCreate()
        
        // منع أي crash من أي عملية تهيئة
        try {
            installCrashHandler()
        } catch (_: Exception) {}

        try {
            initTimber()
        } catch (_: Exception) {}

        try {
            setupNotificationChannels()
        } catch (_: Exception) {}

        try {
            initTheme()
        } catch (_: Exception) {}

        try {
            schedulePeriodicTasks()
        } catch (_: Exception) {}

        try {
            checkRootAvailability()
        } catch (_: Exception) {}
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(object : Timber.DebugTree() {
                override fun createStackElementTag(element: StackTraceElement): String {
                    return "URP-${super.createStackElementTag(element)}:${element.lineNumber}"
                }
            })
        }
    }

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

    fun schedulePeriodicTasks() {
        try {
            val workManager = WorkManager.getInstance(this)

            val cleanupConstraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(false)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val cleanupWork = PeriodicWorkRequestBuilder<RecycleBinCleanupWorker>(
                24, TimeUnit.HOURS
            )
                .setConstraints(cleanupConstraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_RECYCLE_BIN_CLEANUP,
                ExistingPeriodicWorkPolicy.KEEP,
                cleanupWork
            )
        } catch (_: Exception) {}
    }

    fun initTheme() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mode = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            AppCompatDelegate.setDefaultNightMode(mode)
        } catch (_: Exception) {}
    }

    fun setThemeMode(mode: Int) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        } catch (_: Exception) {}
    }

    private fun checkRootAvailability() {
        appScope.launch {
            try {
                rootManager.isRootAvailable()
            } catch (_: Exception) {}
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                android.util.Log.e(TAG, "Uncaught exception on thread: ${thread.name}", throwable)
            } catch (_: Exception) {}

            // للـ main thread فقط، نحاول إغلاق التطبيق بأمان
            if (thread.name == "main") {
                try {
                    defaultHandler?.uncaughtException(thread, throwable)
                } catch (_: Exception) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(1)
                }
            }
        }
    }
}
