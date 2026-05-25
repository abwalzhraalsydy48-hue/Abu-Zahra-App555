package com.ultimaterecovery.pro.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.ultimaterecovery.pro.data.local.converter.EnumTypeConverters

/**
 * Room entity representing a backup entry.
 *
 * Tracks backup operations including local and cloud-based backups,
 * encryption state, and synchronization status.
 */
@Entity(
    tableName = BackupEntity.TABLE_NAME,
    indices = [
        Index(value = [BackupEntity.COLUMN_BACKUP_TYPE]),
        Index(value = [BackupEntity.COLUMN_CLOUD_PROVIDER]),
        Index(value = [BackupEntity.COLUMN_STATUS]),
        Index(value = [BackupEntity.COLUMN_BACKUP_DATE]),
        Index(value = [BackupEntity.COLUMN_IS_ENCRYPTED]),
        Index(value = [BackupEntity.COLUMN_LAST_SYNC_DATE])
    ]
)
@TypeConverters(EnumTypeConverters::class)
data class BackupEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = COLUMN_ID)
    val id: Long = 0L,

    @ColumnInfo(name = COLUMN_BACKUP_NAME)
    val backupName: String,

    @ColumnInfo(name = COLUMN_BACKUP_DATE)
    val backupDate: Long,

    @ColumnInfo(name = COLUMN_BACKUP_SIZE)
    val backupSize: Long = 0L,

    @ColumnInfo(name = COLUMN_BACKUP_PATH)
    val backupPath: String,

    @ColumnInfo(name = COLUMN_BACKUP_TYPE)
    val backupType: BackupType,

    @ColumnInfo(name = COLUMN_CLOUD_PROVIDER)
    val cloudProvider: CloudProvider,

    @ColumnInfo(name = COLUMN_IS_ENCRYPTED)
    val isEncrypted: Boolean = false,

    @ColumnInfo(name = COLUMN_ITEM_COUNT)
    val itemCount: Int = 0,

    @ColumnInfo(name = COLUMN_STATUS)
    val status: BackupStatus,

    @ColumnInfo(name = COLUMN_CLOUD_URL)
    val cloudUrl: String? = null,

    @ColumnInfo(name = COLUMN_LAST_SYNC_DATE)
    val lastSyncDate: Long? = null
) {

    companion object {
        const val TABLE_NAME = "backups"

        const val COLUMN_ID = "id"
        const val COLUMN_BACKUP_NAME = "backup_name"
        const val COLUMN_BACKUP_DATE = "backup_date"
        const val COLUMN_BACKUP_SIZE = "backup_size"
        const val COLUMN_BACKUP_PATH = "backup_path"
        const val COLUMN_BACKUP_TYPE = "backup_type"
        const val COLUMN_CLOUD_PROVIDER = "cloud_provider"
        const val COLUMN_IS_ENCRYPTED = "is_encrypted"
        const val COLUMN_ITEM_COUNT = "item_count"
        const val COLUMN_STATUS = "status"
        const val COLUMN_CLOUD_URL = "cloud_url"
        const val COLUMN_LAST_SYNC_DATE = "last_sync_date"
    }

    /**
     * Defines the scope of data included in the backup.
     */
    enum class BackupType {
        FULL,
        PHOTOS,
        VIDEOS,
        DOCUMENTS,
        SMS,
        CALL_LOG,
        APP_DATA,
        CUSTOM
    }

    /**
     * Supported cloud storage providers for backup upload.
     */
    enum class CloudProvider {
        LOCAL,
        GOOGLE_DRIVE,
        DROPBOX
    }

    /**
     * Represents the current state of a backup operation.
     */
    enum class BackupStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        UPLOADING,
        UPLOADED
    }
}
