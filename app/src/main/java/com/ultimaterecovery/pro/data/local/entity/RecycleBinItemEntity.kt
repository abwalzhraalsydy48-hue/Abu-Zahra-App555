package com.ultimaterecovery.pro.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.ultimaterecovery.pro.data.local.converter.EnumTypeConverters

/**
 * Room entity representing an item in the recycle bin.
 *
 * Provides a safety net for deleted items, allowing restoration
 * before the auto-delete expiry date is reached.
 */
@Entity(
    tableName = RecycleBinItemEntity.TABLE_NAME,
    indices = [
        Index(value = [RecycleBinItemEntity.COLUMN_ORIGINAL_PATH]),
        Index(value = [RecycleBinItemEntity.COLUMN_FILE_NAME]),
        Index(value = [RecycleBinItemEntity.COLUMN_CATEGORY]),
        Index(value = [RecycleBinItemEntity.COLUMN_DELETED_DATE]),
        Index(value = [RecycleBinItemEntity.COLUMN_EXPIRY_DATE]),
        Index(value = [RecycleBinItemEntity.COLUMN_IS_RESTORABLE]),
        Index(value = [RecycleBinItemEntity.COLUMN_MIME_TYPE])
    ]
)
@TypeConverters(EnumTypeConverters::class)
data class RecycleBinItemEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = COLUMN_ID)
    val id: Long = 0L,

    @ColumnInfo(name = COLUMN_ORIGINAL_PATH)
    val originalPath: String,

    @ColumnInfo(name = COLUMN_FILE_NAME)
    val fileName: String,

    @ColumnInfo(name = COLUMN_FILE_SIZE)
    val fileSize: Long,

    @ColumnInfo(name = COLUMN_MIME_TYPE)
    val mimeType: String,

    @ColumnInfo(name = COLUMN_DELETED_DATE)
    val deletedDate: Long,

    @ColumnInfo(name = COLUMN_EXPIRY_DATE)
    val expiryDate: Long,

    @ColumnInfo(name = COLUMN_THUMBNAIL_PATH)
    val thumbnailPath: String? = null,

    @ColumnInfo(name = COLUMN_CATEGORY)
    val category: RecoveredFileEntity.FileCategory,

    @ColumnInfo(name = COLUMN_AUTO_DELETE_DAYS)
    val autoDeleteDays: Int = 30,

    @ColumnInfo(name = COLUMN_IS_RESTORABLE)
    val isRestorable: Boolean = true,

    @ColumnInfo(name = COLUMN_METADATA)
    val metadata: String? = null
) {

    companion object {
        const val TABLE_NAME = "recycle_bin_items"

        const val COLUMN_ID = "id"
        const val COLUMN_ORIGINAL_PATH = "original_path"
        const val COLUMN_FILE_NAME = "file_name"
        const val COLUMN_FILE_SIZE = "file_size"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_DELETED_DATE = "deleted_date"
        const val COLUMN_EXPIRY_DATE = "expiry_date"
        const val COLUMN_THUMBNAIL_PATH = "thumbnail_path"
        const val COLUMN_CATEGORY = "category"
        const val COLUMN_AUTO_DELETE_DAYS = "auto_delete_days"
        const val COLUMN_IS_RESTORABLE = "is_restorable"
        const val COLUMN_METADATA = "metadata"
    }
}
