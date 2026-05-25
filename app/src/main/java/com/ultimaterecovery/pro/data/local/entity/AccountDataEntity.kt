package com.ultimaterecovery.pro.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing recovered account data.
 *
 * Stores metadata about account information recovered from the
 * Android AccountManager and related credential storage.
 */
@Entity(
    tableName = AccountDataEntity.TABLE_NAME,
    indices = [
        Index(value = [AccountDataEntity.COLUMN_ACCOUNT_TYPE]),
        Index(value = [AccountDataEntity.COLUMN_ACCOUNT_NAME]),
        Index(value = [AccountDataEntity.COLUMN_IS_RECOVERED]),
        Index(value = [AccountDataEntity.COLUMN_IS_SENSITIVE]),
        Index(value = [AccountDataEntity.COLUMN_RECOVERY_DATE]),
        Index(value = [AccountDataEntity.COLUMN_SOURCE])
    ]
)
data class AccountDataEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = COLUMN_ID)
    val id: Long = 0L,

    @ColumnInfo(name = COLUMN_ACCOUNT_TYPE)
    val accountType: String,

    @ColumnInfo(name = COLUMN_ACCOUNT_NAME)
    val accountName: String,

    @ColumnInfo(name = COLUMN_AUTH_TOKEN_TYPE)
    val authTokenType: String? = null,

    @ColumnInfo(name = COLUMN_DATA_PATH)
    val dataPath: String,

    @ColumnInfo(name = COLUMN_DATA_SIZE)
    val dataSize: Long = 0L,

    @ColumnInfo(name = COLUMN_IS_RECOVERED)
    val isRecovered: Boolean = false,

    @ColumnInfo(name = COLUMN_RECOVERY_DATE)
    val recoveryDate: Long,

    @ColumnInfo(name = COLUMN_SOURCE)
    val source: String,

    @ColumnInfo(name = COLUMN_IS_SENSITIVE)
    val isSensitive: Boolean = false
) {

    companion object {
        const val TABLE_NAME = "account_data"

        const val COLUMN_ID = "id"
        const val COLUMN_ACCOUNT_TYPE = "account_type"
        const val COLUMN_ACCOUNT_NAME = "account_name"
        const val COLUMN_AUTH_TOKEN_TYPE = "auth_token_type"
        const val COLUMN_DATA_PATH = "data_path"
        const val COLUMN_DATA_SIZE = "data_size"
        const val COLUMN_IS_RECOVERED = "is_recovered"
        const val COLUMN_RECOVERY_DATE = "recovery_date"
        const val COLUMN_SOURCE = "source"
        const val COLUMN_IS_SENSITIVE = "is_sensitive"
    }
}
