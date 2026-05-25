package com.ultimaterecovery.pro.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.ultimaterecovery.pro.data.local.converter.EnumTypeConverters

/**
 * Room entity representing a recovered call log entry.
 *
 * Mirrors the Android CallLog.Calls content provider structure
 * with additional recovery metadata and resolved contact information.
 */
@Entity(
    tableName = CallLogEntity.TABLE_NAME,
    indices = [
        Index(value = [CallLogEntity.COLUMN_NUMBER]),
        Index(value = [CallLogEntity.COLUMN_CALL_TYPE]),
        Index(value = [CallLogEntity.COLUMN_DATE]),
        Index(value = [CallLogEntity.COLUMN_CONTACT_NAME]),
        Index(value = [CallLogEntity.COLUMN_IS_RECOVERED]),
        Index(value = [CallLogEntity.COLUMN_RECOVERY_DATE])
    ]
)
@TypeConverters(EnumTypeConverters::class)
data class CallLogEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = COLUMN_ID)
    val id: Long = 0L,

    @ColumnInfo(name = COLUMN_NUMBER)
    val number: String,

    @ColumnInfo(name = COLUMN_CALL_TYPE)
    val callType: CallType,

    @ColumnInfo(name = COLUMN_DATE)
    val date: Long,

    @ColumnInfo(name = COLUMN_DURATION)
    val duration: Long = 0L,

    @ColumnInfo(name = COLUMN_CONTACT_NAME)
    val contactName: String? = null,

    @ColumnInfo(name = COLUMN_PHONE_ACCOUNT_ID)
    val phoneAccountId: String? = null,

    @ColumnInfo(name = COLUMN_IS_RECOVERED)
    val isRecovered: Boolean = false,

    @ColumnInfo(name = COLUMN_RECOVERY_DATE)
    val recoveryDate: Long,

    @ColumnInfo(name = COLUMN_FEATURES)
    val features: Int = 0,

    @ColumnInfo(name = COLUMN_DATA_USAGE)
    val dataUsage: Long? = null
) {

    companion object {
        const val TABLE_NAME = "call_logs"

        const val COLUMN_ID = "id"
        const val COLUMN_NUMBER = "number"
        const val COLUMN_CALL_TYPE = "call_type"
        const val COLUMN_DATE = "date"
        const val COLUMN_DURATION = "duration"
        const val COLUMN_CONTACT_NAME = "contact_name"
        const val COLUMN_PHONE_ACCOUNT_ID = "phone_account_id"
        const val COLUMN_IS_RECOVERED = "is_recovered"
        const val COLUMN_RECOVERY_DATE = "recovery_date"
        const val COLUMN_FEATURES = "features"
        const val COLUMN_DATA_USAGE = "data_usage"
    }

    /**
     * Maps to Android's CallLog.Calls type constants.
     */
    enum class CallType {
        INCOMING,
        OUTGOING,
        MISSED,
        REJECTED,
        BLOCKED,
        UNKNOWN
    }
}
