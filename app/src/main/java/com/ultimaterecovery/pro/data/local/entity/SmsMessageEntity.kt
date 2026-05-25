package com.ultimaterecovery.pro.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity
import com.ultimaterecovery.pro.data.local.converter.EnumTypeConverters

/**
 * Room entity representing a recovered SMS message.
 *
 * Stores all fields from the Android SMS content provider
 * along with recovery metadata and resolved contact names.
 */
@Entity(
    tableName = SmsMessageEntity.TABLE_NAME,
    indices = [
        Index(value = [SmsMessageEntity.COLUMN_ADDRESS]),
        Index(value = [SmsMessageEntity.COLUMN_DATE]),
        Index(value = [SmsMessageEntity.COLUMN_TYPE]),
        Index(value = [SmsMessageEntity.COLUMN_THREAD_ID]),
        Index(value = [SmsMessageEntity.COLUMN_IS_RECOVERED]),
        Index(value = [SmsMessageEntity.COLUMN_CONTACT_NAME]),
        Index(value = [SmsMessageEntity.COLUMN_RECOVERY_DATE])
    ]
)
@TypeConverters(EnumTypeConverters::class)
data class SmsMessageEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = COLUMN_ID)
    val id: Long = 0L,

    @ColumnInfo(name = COLUMN_ADDRESS)
    val address: String,

    @ColumnInfo(name = COLUMN_BODY)
    val body: String,

    @ColumnInfo(name = COLUMN_DATE)
    val date: Long,

    @ColumnInfo(name = COLUMN_DATE_SENT)
    val dateSent: Long? = null,

    @ColumnInfo(name = COLUMN_TYPE)
    val type: SmsType,

    @ColumnInfo(name = COLUMN_THREAD_ID)
    val threadId: Long,

    @ColumnInfo(name = COLUMN_READ)
    val read: Boolean = false,

    @ColumnInfo(name = COLUMN_SUBJECT)
    val subject: String? = null,

    @ColumnInfo(name = COLUMN_SERVICE_CENTER)
    val serviceCenter: String? = null,

    @ColumnInfo(name = COLUMN_IS_RECOVERED)
    val isRecovered: Boolean = false,

    @ColumnInfo(name = COLUMN_RECOVERY_DATE)
    val recoveryDate: Long,

    @ColumnInfo(name = COLUMN_CONTACT_NAME)
    val contactName: String? = null
) {

    companion object {
        const val TABLE_NAME = "sms_messages"

        const val COLUMN_ID = "id"
        const val COLUMN_ADDRESS = "address"
        const val COLUMN_BODY = "body"
        const val COLUMN_DATE = "date"
        const val COLUMN_DATE_SENT = "date_sent"
        const val COLUMN_TYPE = "type"
        const val COLUMN_THREAD_ID = "thread_id"
        const val COLUMN_READ = "read"
        const val COLUMN_SUBJECT = "subject"
        const val COLUMN_SERVICE_CENTER = "service_center"
        const val COLUMN_IS_RECOVERED = "is_recovered"
        const val COLUMN_RECOVERY_DATE = "recovery_date"
        const val COLUMN_CONTACT_NAME = "contact_name"
    }

    /**
     * Maps to Android's SMS message type constants from Telephony.Sms.
     */
    enum class SmsType {
        INBOX,
        SENT,
        DRAFT,
        OUTBOX,
        FAILED,
        QUEUED
    }
}
