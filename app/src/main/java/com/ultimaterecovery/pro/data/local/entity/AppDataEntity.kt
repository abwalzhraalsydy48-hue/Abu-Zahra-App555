package com.ultimaterecovery.pro.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.ultimaterecovery.pro.data.local.converter.EnumTypeConverters

/**
 * Room entity representing recovered application data.
 *
 * Tracks data belonging to installed applications including cache,
 * databases, shared preferences, and other app-specific files.
 */
@Entity(
    tableName = AppDataEntity.TABLE_NAME,
    indices = [
        Index(value = [AppDataEntity.COLUMN_PACKAGE_NAME]),
        Index(value = [AppDataEntity.COLUMN_DATA_TYPE]),
        Index(value = [AppDataEntity.COLUMN_IS_RECOVERED]),
        Index(value = [AppDataEntity.COLUMN_IS_SYSTEM_APP]),
        Index(value = [AppDataEntity.COLUMN_RECOVERY_DATE]),
        Index(value = [AppDataEntity.COLUMN_FILE_PATH])
    ]
)
@TypeConverters(EnumTypeConverters::class)
data class AppDataEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = COLUMN_ID)
    val id: Long = 0L,

    @ColumnInfo(name = COLUMN_PACKAGE_NAME)
    val packageName: String,

    @ColumnInfo(name = COLUMN_APP_NAME)
    val appName: String,

    @ColumnInfo(name = COLUMN_APP_ICON_PATH)
    val appIconPath: String? = null,

    @ColumnInfo(name = COLUMN_DATA_TYPE)
    val dataType: AppDataType,

    @ColumnInfo(name = COLUMN_FILE_PATH)
    val filePath: String,

    @ColumnInfo(name = COLUMN_FILE_SIZE)
    val fileSize: Long = 0L,

    @ColumnInfo(name = COLUMN_IS_RECOVERED)
    val isRecovered: Boolean = false,

    @ColumnInfo(name = COLUMN_RECOVERY_DATE)
    val recoveryDate: Long,

    @ColumnInfo(name = COLUMN_VERSION_NAME)
    val versionName: String? = null,

    @ColumnInfo(name = COLUMN_IS_SYSTEM_APP)
    val isSystemApp: Boolean = false
) {

    companion object {
        const val TABLE_NAME = "app_data"

        const val COLUMN_ID = "id"
        const val COLUMN_PACKAGE_NAME = "package_name"
        const val COLUMN_APP_NAME = "app_name"
        const val COLUMN_APP_ICON_PATH = "app_icon_path"
        const val COLUMN_DATA_TYPE = "data_type"
        const val COLUMN_FILE_PATH = "file_path"
        const val COLUMN_FILE_SIZE = "file_size"
        const val COLUMN_IS_RECOVERED = "is_recovered"
        const val COLUMN_RECOVERY_DATE = "recovery_date"
        const val COLUMN_VERSION_NAME = "version_name"
        const val COLUMN_IS_SYSTEM_APP = "is_system_app"
    }

    /**
     * Categorizes the type of application data being recovered.
     */
    enum class AppDataType {
        CACHE,
        DATABASE,
        SHARED_PREFS,
        FILES,
        OBB,
        EXTERNAL
    }
}
