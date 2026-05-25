package com.ultimaterecovery.pro.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.ultimaterecovery.pro.data.local.converter.EnumTypeConverters

/**
 * Room entity representing a recovered file.
 *
 * Tracks all metadata about files discovered during scan sessions,
 * including their recovery status, category, and export state.
 */
@Entity(
    tableName = RecoveredFileEntity.TABLE_NAME,
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = [ScanSessionEntity.COLUMN_ID],
            childColumns = [RecoveredFileEntity.COLUMN_SCAN_SESSION_ID],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = [RecoveredFileEntity.COLUMN_SCAN_SESSION_ID]),
        Index(value = [RecoveredFileEntity.COLUMN_FILE_PATH]),
        Index(value = [RecoveredFileEntity.COLUMN_CATEGORY]),
        Index(value = [RecoveredFileEntity.COLUMN_RECOVERY_STATUS]),
        Index(value = [RecoveredFileEntity.COLUMN_MD5_HASH]),
        Index(value = [RecoveredFileEntity.COLUMN_FILE_EXTENSION]),
        Index(value = [RecoveredFileEntity.COLUMN_IS_FAVORITE]),
        Index(value = [RecoveredFileEntity.COLUMN_RECOVERY_DATE])
    ]
)
@TypeConverters(EnumTypeConverters::class)
data class RecoveredFileEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = COLUMN_ID)
    val id: Long = 0L,

    @ColumnInfo(name = COLUMN_FILE_PATH)
    val filePath: String,

    @ColumnInfo(name = COLUMN_FILE_NAME)
    val fileName: String,

    @ColumnInfo(name = COLUMN_FILE_SIZE)
    val fileSize: Long,

    @ColumnInfo(name = COLUMN_FILE_EXTENSION)
    val fileExtension: String,

    @ColumnInfo(name = COLUMN_MIME_TYPE)
    val mimeType: String,

    @ColumnInfo(name = COLUMN_RECOVERY_DATE)
    val recoveryDate: Long,

    @ColumnInfo(name = COLUMN_ORIGINAL_DATE)
    val originalDate: Long,

    @ColumnInfo(name = COLUMN_SCAN_SESSION_ID)
    val scanSessionId: Long,

    @ColumnInfo(name = COLUMN_CATEGORY)
    val category: FileCategory,

    @ColumnInfo(name = COLUMN_RECOVERY_STATUS)
    val recoveryStatus: RecoveryStatus,

    @ColumnInfo(name = COLUMN_THUMBNAIL_PATH)
    val thumbnailPath: String? = null,

    @ColumnInfo(name = COLUMN_IS_ROOT_ACCESSED)
    val isRootAccessed: Boolean = false,

    @ColumnInfo(name = COLUMN_SOURCE_PATH)
    val sourcePath: String,

    @ColumnInfo(name = COLUMN_MD5_HASH)
    val md5Hash: String? = null,

    @ColumnInfo(name = COLUMN_IS_FAVORITE)
    val isFavorite: Boolean = false,

    @ColumnInfo(name = COLUMN_IS_EXPORTED)
    val isExported: Boolean = false,

    @ColumnInfo(name = COLUMN_EXPORT_PATH)
    val exportPath: String? = null
) {

    companion object {
        const val TABLE_NAME = "recovered_files"

        const val COLUMN_ID = "id"
        const val COLUMN_FILE_PATH = "file_path"
        const val COLUMN_FILE_NAME = "file_name"
        const val COLUMN_FILE_SIZE = "file_size"
        const val COLUMN_FILE_EXTENSION = "file_extension"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_RECOVERY_DATE = "recovery_date"
        const val COLUMN_ORIGINAL_DATE = "original_date"
        const val COLUMN_SCAN_SESSION_ID = "scan_session_id"
        const val COLUMN_CATEGORY = "category"
        const val COLUMN_RECOVERY_STATUS = "recovery_status"
        const val COLUMN_THUMBNAIL_PATH = "thumbnail_path"
        const val COLUMN_IS_ROOT_ACCESSED = "is_root_accessed"
        const val COLUMN_SOURCE_PATH = "source_path"
        const val COLUMN_MD5_HASH = "md5_hash"
        const val COLUMN_IS_FAVORITE = "is_favorite"
        const val COLUMN_IS_EXPORTED = "is_exported"
        const val COLUMN_EXPORT_PATH = "export_path"
    }

    /**
     * Categorizes recovered files by their general type.
     */
    enum class FileCategory {
        PHOTO,
        VIDEO,
        DOCUMENT,
        AUDIO,
        ARCHIVE,
        APK,
        SMS,
        CALL_LOG,
        OTHER
    }

    /**
     * Represents the current recovery state of a file.
     */
    enum class RecoveryStatus {
        PENDING,
        RECOVERED,
        FAILED
    }
}
