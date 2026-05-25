package com.ultimaterecovery.pro.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.ultimaterecovery.pro.data.local.converter.EnumTypeConverters

/**
 * Room entity representing a recovery history record.
 *
 * Provides an audit trail of all recovery operations performed,
 * including success/failure status and timing information.
 */
@Entity(
    tableName = RecoveryHistoryEntity.TABLE_NAME,
    indices = [
        Index(value = [RecoveryHistoryEntity.COLUMN_RECOVERY_DATE]),
        Index(value = [RecoveryHistoryEntity.COLUMN_FILE_TYPE]),
        Index(value = [RecoveryHistoryEntity.COLUMN_IS_SUCCESSFUL]),
        Index(value = [RecoveryHistoryEntity.COLUMN_SCAN_TYPE]),
        Index(value = [RecoveryHistoryEntity.COLUMN_SOURCE_PATH]),
        Index(value = [RecoveryHistoryEntity.COLUMN_DESTINATION_PATH])
    ]
)
@TypeConverters(EnumTypeConverters::class)
data class RecoveryHistoryEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = COLUMN_ID)
    val id: Long = 0L,

    @ColumnInfo(name = COLUMN_RECOVERY_DATE)
    val recoveryDate: Long,

    @ColumnInfo(name = COLUMN_FILE_TYPE)
    val fileType: String,

    @ColumnInfo(name = COLUMN_FILE_COUNT)
    val fileCount: Int,

    @ColumnInfo(name = COLUMN_TOTAL_SIZE)
    val totalSize: Long,

    @ColumnInfo(name = COLUMN_SOURCE_PATH)
    val sourcePath: String,

    @ColumnInfo(name = COLUMN_DESTINATION_PATH)
    val destinationPath: String,

    @ColumnInfo(name = COLUMN_IS_SUCCESSFUL)
    val isSuccessful: Boolean,

    @ColumnInfo(name = COLUMN_ERROR_MESSAGE)
    val errorMessage: String? = null,

    @ColumnInfo(name = COLUMN_DURATION)
    val duration: Long = 0L,

    @ColumnInfo(name = COLUMN_SCAN_TYPE)
    val scanType: ScanSessionEntity.ScanType
) {

    companion object {
        const val TABLE_NAME = "recovery_history"

        const val COLUMN_ID = "id"
        const val COLUMN_RECOVERY_DATE = "recovery_date"
        const val COLUMN_FILE_TYPE = "file_type"
        const val COLUMN_FILE_COUNT = "file_count"
        const val COLUMN_TOTAL_SIZE = "total_size"
        const val COLUMN_SOURCE_PATH = "source_path"
        const val COLUMN_DESTINATION_PATH = "destination_path"
        const val COLUMN_IS_SUCCESSFUL = "is_successful"
        const val COLUMN_ERROR_MESSAGE = "error_message"
        const val COLUMN_DURATION = "duration"
        const val COLUMN_SCAN_TYPE = "scan_type"
    }
}
