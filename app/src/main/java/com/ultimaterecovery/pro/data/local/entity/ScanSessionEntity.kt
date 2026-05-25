package com.ultimaterecovery.pro.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.ultimaterecovery.pro.data.local.converter.EnumTypeConverters

/**
 * Room entity representing a scan session.
 *
 * A scan session encapsulates the lifecycle of a single scan operation,
 * tracking progress, sectors scanned, and the type of scan performed.
 */
@Entity(
    tableName = ScanSessionEntity.TABLE_NAME,
    indices = [
        Index(value = [ScanSessionEntity.COLUMN_SCAN_TYPE]),
        Index(value = [ScanSessionEntity.COLUMN_STATUS]),
        Index(value = [ScanSessionEntity.COLUMN_START_TIME]),
        Index(value = [ScanSessionEntity.COLUMN_STORAGE_PATH]),
        Index(value = [ScanSessionEntity.COLUMN_PARTITION_NAME])
    ]
)
@TypeConverters(EnumTypeConverters::class)
data class ScanSessionEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = COLUMN_ID)
    val id: Long = 0L,

    @ColumnInfo(name = COLUMN_START_TIME)
    val startTime: Long,

    @ColumnInfo(name = COLUMN_END_TIME)
    val endTime: Long? = null,

    @ColumnInfo(name = COLUMN_SCAN_TYPE)
    val scanType: ScanType,

    @ColumnInfo(name = COLUMN_STATUS)
    val status: ScanStatus,

    @ColumnInfo(name = COLUMN_TOTAL_FILES_FOUND)
    val totalFilesFound: Int = 0,

    @ColumnInfo(name = COLUMN_TOTAL_SIZE_FOUND)
    val totalSizeFound: Long = 0L,

    @ColumnInfo(name = COLUMN_PROGRESS)
    val progress: Float = 0f,

    @ColumnInfo(name = COLUMN_STORAGE_PATH)
    val storagePath: String,

    @ColumnInfo(name = COLUMN_IS_ROOT_SCAN)
    val isRootScan: Boolean = false,

    @ColumnInfo(name = COLUMN_PARTITION_NAME)
    val partitionName: String? = null,

    @ColumnInfo(name = COLUMN_SECTORS_SCANNED)
    val sectorsScanned: Long = 0L,

    @ColumnInfo(name = COLUMN_TOTAL_SECTORS)
    val totalSectors: Long = 0L,

    @ColumnInfo(name = COLUMN_ERROR_MESSAGE)
    val errorMessage: String? = null
) {

    companion object {
        const val TABLE_NAME = "scan_sessions"

        const val COLUMN_ID = "id"
        const val COLUMN_START_TIME = "start_time"
        const val COLUMN_END_TIME = "end_time"
        const val COLUMN_SCAN_TYPE = "scan_type"
        const val COLUMN_STATUS = "status"
        const val COLUMN_TOTAL_FILES_FOUND = "total_files_found"
        const val COLUMN_TOTAL_SIZE_FOUND = "total_size_found"
        const val COLUMN_PROGRESS = "progress"
        const val COLUMN_STORAGE_PATH = "storage_path"
        const val COLUMN_IS_ROOT_SCAN = "is_root_scan"
        const val COLUMN_PARTITION_NAME = "partition_name"
        const val COLUMN_SECTORS_SCANNED = "sectors_scanned"
        const val COLUMN_TOTAL_SECTORS = "total_sectors"
        const val COLUMN_ERROR_MESSAGE = "error_message"
    }

    /**
     * Defines the type of scan to be performed.
     */
    enum class ScanType {
        QUICK,
        DEEP,
        PARTITION,
        RAW,
        SIGNATURE
    }

    /**
     * Represents the current state of a scan session.
     */
    enum class ScanStatus {
        RUNNING,
        PAUSED,
        COMPLETED,
        CANCELLED,
        FAILED
    }
}
