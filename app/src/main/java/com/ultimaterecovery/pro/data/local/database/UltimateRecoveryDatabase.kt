package com.ultimaterecovery.pro.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.JournalMode
import androidx.room.TypeConverters
import com.ultimaterecovery.pro.data.local.converter.EnumTypeConverters
import com.ultimaterecovery.pro.data.local.dao.AccountDataDao
import com.ultimaterecovery.pro.data.local.dao.AppDataDao
import com.ultimaterecovery.pro.data.local.dao.BackupDao
import com.ultimaterecovery.pro.data.local.dao.CallLogDao
import com.ultimaterecovery.pro.data.local.dao.RecycleBinItemDao
import com.ultimaterecovery.pro.data.local.dao.RecoveredFileDao
import com.ultimaterecovery.pro.data.local.dao.RecoveryHistoryDao
import com.ultimaterecovery.pro.data.local.dao.ScanSessionDao
import com.ultimaterecovery.pro.data.local.dao.SmsMessageDao
import com.ultimaterecovery.pro.data.local.entity.AccountDataEntity
import com.ultimaterecovery.pro.data.local.entity.AppDataEntity
import com.ultimaterecovery.pro.data.local.entity.BackupEntity
import com.ultimaterecovery.pro.data.local.entity.CallLogEntity
import com.ultimaterecovery.pro.data.local.entity.RecycleBinItemEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveryHistoryEntity
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity

/**
 * Room database for Ultimate Recovery Pro.
 *
 * Serves as the main access point for the underlying SQLite database,
 * managing all entity tables and exposing DAO instances for each
 * data domain.
 *
 * ## Migration Strategy
 * When schema changes are required:
 * 1. Increment [VERSION].
 * 2. Add a new [androidx.room.migration.Migration] in the companion
 *    object that migrates from the old version to the new one.
 * 3. Pass the migration to the `.addMigrations()` builder call inside
 *    [getDatabase].
 * 4. For destructive fallback during development, use
 *    `.fallbackToDestructiveMigration()` — **never** ship this to
 *    production as it will destroy user data.
 *
 * @see EnumTypeConverters for enum persistence logic
 */
@Database(
    entities = [
        RecoveredFileEntity::class,
        ScanSessionEntity::class,
        SmsMessageEntity::class,
        CallLogEntity::class,
        AppDataEntity::class,
        AccountDataEntity::class,
        BackupEntity::class,
        RecycleBinItemEntity::class,
        RecoveryHistoryEntity::class
    ],
    version = UltimateRecoveryDatabase.VERSION,
    exportSchema = true
)
@TypeConverters(EnumTypeConverters::class)
abstract class UltimateRecoveryDatabase : RoomDatabase() {

    // ──────────────────────────────────────────────
    // DAO accessors
    // ──────────────────────────────────────────────

    abstract fun recoveredFileDao(): RecoveredFileDao
    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun smsMessageDao(): SmsMessageDao
    abstract fun callLogDao(): CallLogDao
    abstract fun appDataDao(): AppDataDao
    abstract fun accountDataDao(): AccountDataDao
    abstract fun backupDao(): BackupDao
    abstract fun recycleBinItemDao(): RecycleBinItemDao
    abstract fun recoveryHistoryDao(): RecoveryHistoryDao

    companion object {
        const val VERSION = 1

        @Volatile
        private var INSTANCE: UltimateRecoveryDatabase? = null

        /**
         * Returns the singleton [UltimateRecoveryDatabase] instance,
         * creating it on first access.
         *
         * Thread-safe via double-checked locking with [@Volatile] +
         * [@Synchronized].
         *
         * @param context Application or activity context used to build
         *   the Room database. Internally uses
         *   [Context.getApplicationContext] to avoid leaking activity
         *   references.
         */
        @JvmStatic
        @Synchronized
        fun getDatabase(context: Context): UltimateRecoveryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: try {
                    Room.databaseBuilder(
                        context.applicationContext,
                        UltimateRecoveryDatabase::class.java,
                        "ultimate_recovery_db"
                    )
                        .setJournalMode(JournalMode.TRUNCATE)
                        .fallbackToDestructiveMigration()
                        .addCallback(SafeDatabaseCallback())
                        .build()
                        .also { INSTANCE = it }
                } catch (e: Exception) {
                    // If database creation fails, delete and recreate
                    android.util.Log.e("UltimateRecoveryDB", "Database creation failed, attempting destructive fallback", e)
                    try {
                        context.applicationContext.deleteDatabase("ultimate_recovery_db")
                        Room.databaseBuilder(
                            context.applicationContext,
                            UltimateRecoveryDatabase::class.java,
                            "ultimate_recovery_db"
                        )
                            .setJournalMode(JournalMode.TRUNCATE)
                            .fallbackToDestructiveMigration()
                            .addCallback(SafeDatabaseCallback())
                            .build()
                            .also { INSTANCE = it }
                    } catch (e2: Exception) {
                        android.util.Log.e("UltimateRecoveryDB", "Database recreation also failed", e2)
                        throw e2
                    }
                }
            }
        }

        /**
         * Forces the singleton to be recreated on the next call to
         * [getDatabase]. Intended **only** for use in tests.
         */
        @JvmStatic
        fun destroyInstance() {
            INSTANCE = null
        }

        /**
         * Executes a SQL statement safely, catching any exceptions
         * that might occur on devices with incompatible SQLite implementations.
         *
         * Some devices (itel, Huawei, etc.) use SQLite implementations or
         * file systems that don't support WAL journal mode, causing the
         * "SQLITE_OK[0]: Cannot perform queries" error.
         */
        private fun safeExecSQL(connection: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) {
            try {
                connection.execSQL(sql)
            } catch (e: Exception) {
                // PRAGMA execution failure should not crash the app
                // Some devices don't support certain PRAGMA operations
            }
        }
    }

    /**
     * Fallback database callback that uses TRUNCATE journal mode instead of WAL.
     * Used when the primary database creation fails on devices
     * that don't support WAL journal mode.
     */
    private class SafeDatabaseCallback : RoomDatabase.Callback() {

        override fun onCreate(connection: androidx.sqlite.db.SupportSQLiteDatabase) {
            super.onCreate(connection)
            // Explicitly set TRUNCATE mode for maximum compatibility
            safeExecSQL(connection, "PRAGMA journal_mode=TRUNCATE")
            safeExecSQL(connection, "PRAGMA foreign_keys=ON")
        }

        override fun onOpen(connection: androidx.sqlite.db.SupportSQLiteDatabase) {
            super.onOpen(connection)
            safeExecSQL(connection, "PRAGMA foreign_keys=ON")
        }

        private fun safeExecSQL(connection: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) {
            try {
                connection.execSQL(sql)
            } catch (e: Exception) {
                // PRAGMA execution failure should not crash the app
            }
        }
    }
}
