package com.ultimaterecovery.pro.di

import android.content.Context
import com.ultimaterecovery.pro.data.local.database.UltimateRecoveryDatabase
import com.ultimaterecovery.pro.data.local.dao.*
import com.ultimaterecovery.pro.data.repository.*
import com.ultimaterecovery.pro.engine.root.RootManager
import com.ultimaterecovery.pro.engine.scanner.*
import com.ultimaterecovery.pro.manager.FileManager
import com.ultimaterecovery.pro.utils.ai.AIRecoveryAssistant
import com.ultimaterecovery.pro.utils.backup.BackupManager
import com.ultimaterecovery.pro.utils.recyclebin.SmartRecycleBin
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): UltimateRecoveryDatabase {
        return UltimateRecoveryDatabase.getDatabase(context)
    }

    @Provides
    fun provideRecoveredFileDao(db: UltimateRecoveryDatabase): RecoveredFileDao = db.recoveredFileDao()

    @Provides
    fun provideScanSessionDao(db: UltimateRecoveryDatabase): ScanSessionDao = db.scanSessionDao()

    @Provides
    fun provideBackupDao(db: UltimateRecoveryDatabase): BackupDao = db.backupDao()

    @Provides
    fun provideCallLogDao(db: UltimateRecoveryDatabase): CallLogDao = db.callLogDao()

    @Provides
    fun provideSmsMessageDao(db: UltimateRecoveryDatabase): SmsMessageDao = db.smsMessageDao()

    @Provides
    fun provideRecycleBinItemDao(db: UltimateRecoveryDatabase): RecycleBinItemDao = db.recycleBinItemDao()

    @Provides
    fun provideAppDataDao(db: UltimateRecoveryDatabase): AppDataDao = db.appDataDao()

    @Provides
    fun provideAccountDataDao(db: UltimateRecoveryDatabase): AccountDataDao = db.accountDataDao()

    @Provides
    fun provideRecoveryHistoryDao(db: UltimateRecoveryDatabase): RecoveryHistoryDao = db.recoveryHistoryDao()

    @Provides
    @Singleton
    fun provideRootManager(@ApplicationContext context: Context): RootManager {
        return RootManager(context)
    }

    @Provides
    @Singleton
    fun provideFileManager(@ApplicationContext context: Context): FileManager {
        return FileManager(context)
    }

    @Provides
    @Singleton
    fun provideQuickScanner(@ApplicationContext context: Context): QuickScanner {
        return QuickScanner(context)
    }

    @Provides
    @Singleton
    fun provideDeepScanner(): DeepScanner {
        return DeepScanner()
    }

    @Provides
    @Singleton
    fun provideAIDeepScanner(
        @ApplicationContext context: Context,
        aiAssistant: AIRecoveryAssistant
    ): AIDeepScanner {
        return AIDeepScanner(context, aiAssistant)
    }

    @Provides
    @Singleton
    fun provideSignatureScanner(): SignatureScanner {
        return SignatureScanner()
    }

    @Provides
    @Singleton
    fun provideMediaScanner(@ApplicationContext context: Context): MediaScanner {
        return MediaScanner(context)
    }

    @Provides
    @Singleton
    fun provideScanEngine(
        quickScanner: QuickScanner,
        deepScanner: DeepScanner,
        signatureScanner: SignatureScanner,
        mediaScanner: MediaScanner
    ): IScanEngine {
        return ScanEngine(quickScanner, deepScanner, signatureScanner, mediaScanner)
    }

    // RecoveredFileRepository provided by Hilt via @Inject constructor

    // RecoveryHistoryRepository provided by Hilt via @Inject constructor

    // ScanSessionRepository provided by Hilt via @Inject constructor

    // BackupManager is provided by Hilt via @Inject constructor
    // No manual provider needed - Hilt resolves all constructor parameters automatically

    // SmartRecycleBin is provided by Hilt via @Inject constructor
    // No manual provider needed - Hilt resolves all constructor parameters automatically
}
