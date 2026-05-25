package com.ultimaterecovery.pro.data.local.converter

import androidx.room.TypeConverter
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.BackupStatus
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.BackupType
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.CloudProvider
import com.ultimaterecovery.pro.data.local.entity.CallLogEntity.CallType
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.RecoveryStatus
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanStatus
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity.SmsType
import com.ultimaterecovery.pro.data.local.entity.AppDataEntity.AppDataType

/**
 * Room TypeConverters for all enum types used across database entities.
 *
 * Room cannot persist enum values directly; these converters translate
 * enums to their ordinal representation for storage and back again
 * for deserialization.
 */
object EnumTypeConverters {

    // --- RecoveredFileEntity.FileCategory ---

    @JvmStatic
    @TypeConverter
    fun fromFileCategory(value: FileCategory?): Int? = value?.ordinal

    @JvmStatic
    @TypeConverter
    fun toFileCategory(ordinal: Int?): FileCategory? =
        ordinal?.let { FileCategory.values().getOrNull(it) }

    // --- RecoveredFileEntity.RecoveryStatus ---

    @JvmStatic
    @TypeConverter
    fun fromRecoveryStatus(value: RecoveryStatus?): Int? = value?.ordinal

    @JvmStatic
    @TypeConverter
    fun toRecoveryStatus(ordinal: Int?): RecoveryStatus? =
        ordinal?.let { RecoveryStatus.values().getOrNull(it) }

    // --- ScanSessionEntity.ScanType ---

    @JvmStatic
    @TypeConverter
    fun fromScanType(value: ScanType?): Int? = value?.ordinal

    @JvmStatic
    @TypeConverter
    fun toScanType(ordinal: Int?): ScanType? =
        ordinal?.let { ScanType.values().getOrNull(it) }

    // --- ScanSessionEntity.ScanStatus ---

    @JvmStatic
    @TypeConverter
    fun fromScanStatus(value: ScanStatus?): Int? = value?.ordinal

    @JvmStatic
    @TypeConverter
    fun toScanStatus(ordinal: Int?): ScanStatus? =
        ordinal?.let { ScanStatus.values().getOrNull(it) }

    // --- SmsMessageEntity.SmsType ---

    @JvmStatic
    @TypeConverter
    fun fromSmsType(value: SmsType?): Int? = value?.ordinal

    @JvmStatic
    @TypeConverter
    fun toSmsType(ordinal: Int?): SmsType? =
        ordinal?.let { SmsType.values().getOrNull(it) }

    // --- CallLogEntity.CallType ---

    @JvmStatic
    @TypeConverter
    fun fromCallType(value: CallType?): Int? = value?.ordinal

    @JvmStatic
    @TypeConverter
    fun toCallType(ordinal: Int?): CallType? =
        ordinal?.let { CallType.values().getOrNull(it) }

    // --- AppDataEntity.AppDataType ---

    @JvmStatic
    @TypeConverter
    fun fromAppDataType(value: AppDataType?): Int? = value?.ordinal

    @JvmStatic
    @TypeConverter
    fun toAppDataType(ordinal: Int?): AppDataType? =
        ordinal?.let { AppDataType.values().getOrNull(it) }

    // --- BackupEntity.BackupType ---

    @JvmStatic
    @TypeConverter
    fun fromBackupType(value: BackupType?): Int? = value?.ordinal

    @JvmStatic
    @TypeConverter
    fun toBackupType(ordinal: Int?): BackupType? =
        ordinal?.let { BackupType.values().getOrNull(it) }

    // --- BackupEntity.CloudProvider ---

    @JvmStatic
    @TypeConverter
    fun fromCloudProvider(value: CloudProvider?): Int? = value?.ordinal

    @JvmStatic
    @TypeConverter
    fun toCloudProvider(ordinal: Int?): CloudProvider? =
        ordinal?.let { CloudProvider.values().getOrNull(it) }

    // --- BackupEntity.BackupStatus ---

    @JvmStatic
    @TypeConverter
    fun fromBackupStatus(value: BackupStatus?): Int? = value?.ordinal

    @JvmStatic
    @TypeConverter
    fun toBackupStatus(ordinal: Int?): BackupStatus? =
        ordinal?.let { BackupStatus.values().getOrNull(it) }
}
