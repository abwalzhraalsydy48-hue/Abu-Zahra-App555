package com.ultimaterecovery.pro.engine.root

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.Telephony
import com.ultimaterecovery.pro.data.local.entity.CallLogEntity
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity.SmsType
import com.ultimaterecovery.pro.libsustub.SuFile
import com.ultimaterecovery.pro.libsustub.SuFileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMS and Call Log Recovery Engine - Recovers deleted messages and call logs via root
 *
 * This engine uses root access to directly access the SQLite databases
 * used by Android's telephony and contacts providers, enabling recovery
 * of deleted records that are no longer accessible through the standard
 * ContentProvider APIs.
 *
 * Recovery methods:
 * 1. WAL/Journal parsing: Read Write-Ahead Log and rollback journal files
 *    for records that have been deleted from the main database but still
 *    exist in the WAL/journal
 * 2. Free page scanning: Scan SQLite free pages for deleted record data
 * 3. Orphaned record detection: Find records in the database that reference
 *    deleted threads or have null/empty required fields
 * 4. Raw page parsing: Parse raw SQLite B-tree pages for deleted cells
 *
 * Target databases:
 * - /data/data/com.android.providers.telephony/databases/mmssms.db (SMS/MMS)
 * - /data/data/com.android.providers.contacts/databases/contacts2.db (Call logs)
 * - /data/user_de/0/com.android.providers.telephony/databases/mmssms.db (Direct boot)
 *
 * All operations fall back to ContentProvider queries when root is unavailable.
 */
@Singleton
class SmsCallLogRecovery @Inject constructor(
    private val context: Context,
    private val rootManager: RootManager
) {

    companion object {
        /** SMS database paths */
        private val SMS_DB_PATHS = listOf(
            "/data/data/com.android.providers.telephony/databases/mmssms.db",
            "/data/user_de/0/com.android.providers.telephony/databases/mmssms.db",
            "/data/user/0/com.android.providers.telephony/databases/mmssms.db"
        )

        /** Call log database paths (contacts2.db contains calls table) */
        private val CALL_LOG_DB_PATHS = listOf(
            "/data/data/com.android.providers.contacts/databases/contacts2.db",
            "/data/user_de/0/com.android.providers.contacts/databases/contacts2.db",
            "/data/user/0/com.android.providers.contacts/databases/contacts2.db"
        )

        /** SQLite header magic string */
        private const val SQLITE_HEADER = "SQLite format 3\u0000"

        /** SQLite page size offset in header */
        private const val SQLITE_PAGE_SIZE_OFFSET = 16

        /** SQLite database size offset in header (in pages) */
        private const val SQLITE_DB_SIZE_OFFSET = 28

        /** SQLite free page list offset in header */
        private const val SQLITE_FREE_PAGE_OFFSET = 36

        /** Maximum database size to copy for analysis (50MB) */
        private const val MAX_DB_COPY_SIZE = 50L * 1024 * 1024

        /** Maximum WAL file size to process (10MB) */
        private const val MAX_WAL_SIZE = 10L * 1024 * 1024

        /** Temporary directory for database copies */
        private const val TEMP_RECOVERY_DIR = "sms_calllog_recovery"
    }

    // ──────────────────────────────────────────────
    // SMS Recovery
    // ──────────────────────────────────────────────

    /**
     * Recover deleted SMS messages using root access.
     *
     * Attempts multiple recovery strategies:
     * 1. Parse WAL (Write-Ahead Log) file for uncommitted deleted records
     * 2. Parse journal file for rolled-back transactions
     * 3. Scan SQLite free pages for deleted cell data
     * 4. Check for orphaned records in the main database
     *
     * @return Flow of SmsRecoveryResult entries
     */
    fun recoverDeletedSms(): Flow<SmsRecoveryResult> = flow {
        emit(SmsRecoveryResult.Progress("Starting SMS recovery", 0f))

        val dbPath = findSmsDatabase()
        if (dbPath == null) {
            // Fallback to ContentProvider
            emit(SmsRecoveryResult.Progress("Root access unavailable, using ContentProvider fallback", 0.5f))
            val fallbackResults = recoverSmsViaContentProvider()
            for (sms in fallbackResults) {
                emit(SmsRecoveryResult.SmsItem(sms, "content_provider"))
            }
            emit(SmsRecoveryResult.Completed(fallbackResults.size, 0))
            return@flow
        }

        val allRecovered = mutableListOf<RecoveredSms>()

        // Strategy 1: Scan the WAL file
        emit(SmsRecoveryResult.Progress("Scanning WAL file for deleted SMS", 0.2f))
        val walResults = scanSmsWalFile(dbPath)
        allRecovered.addAll(walResults)
        for (sms in walResults) {
            emit(SmsRecoveryResult.SmsItem(sms, "wal_file"))
        }

        // Strategy 2: Scan the journal file
        emit(SmsRecoveryResult.Progress("Scanning journal file for deleted SMS", 0.4f))
        val journalResults = scanSmsJournalFile(dbPath)
        allRecovered.addAll(journalResults)
        for (sms in journalResults) {
            emit(SmsRecoveryResult.SmsItem(sms, "journal_file"))
        }

        // Strategy 3: Scan free pages in the database
        emit(SmsRecoveryResult.Progress("Scanning free pages for deleted SMS", 0.6f))
        val freePageResults = scanSmsFreePages(dbPath)
        allRecovered.addAll(freePageResults)
        for (sms in freePageResults) {
            emit(SmsRecoveryResult.SmsItem(sms, "free_page"))
        }

        // Strategy 4: Check for orphaned records
        emit(SmsRecoveryResult.Progress("Checking for orphaned SMS records", 0.8f))
        val orphanResults = scanSmsOrphanedRecords(dbPath)
        allRecovered.addAll(orphanResults)
        for (sms in orphanResults) {
            emit(SmsRecoveryResult.SmsItem(sms, "orphaned_record"))
        }

        // Deduplicate results
        val uniqueResults = allRecovered.distinctBy { it.address to it.date to it.body }

        emit(SmsRecoveryResult.Completed(uniqueResults.size, allRecovered.size - uniqueResults.size))
    }.flowOn(Dispatchers.IO)

    /**
     * Scan the SMS database directly using root access.
     *
     * Opens the mmssms.db database via root and queries it for all
     * records, including those that might be missed by ContentProvider
     * queries (e.g., records with null thread IDs).
     *
     * @return List of all SMS records found in the database
     */
    suspend fun scanSmsDatabase(): List<RecoveredSms> = withContext(Dispatchers.IO) {
        val dbPath = findSmsDatabase() ?: return@withContext emptyList()
        val results = mutableListOf<RecoveredSms>()

        try {
            // Copy database to app's accessible directory for reading
            val localCopy = copyDatabaseToLocal(dbPath, "mmssms_scan.db") ?: return@withContext emptyList()

            try {
                val db = SQLiteDatabase.openDatabase(
                    localCopy.absolutePath, null,
                    SQLiteDatabase.OPEN_READONLY
                )

                try {
                    val cursor = db.rawQuery(
                        "SELECT _id, address, body, date, date_sent, type, thread_id, " +
                        "read, subject, service_center FROM sms ORDER BY date DESC",
                        null
                    )

                    cursor.use {
                        val idIdx = it.getColumnIndex("_id")
                        val addrIdx = it.getColumnIndex("address")
                        val bodyIdx = it.getColumnIndex("body")
                        val dateIdx = it.getColumnIndex("date")
                        val dateSentIdx = it.getColumnIndex("date_sent")
                        val typeIdx = it.getColumnIndex("type")
                        val threadIdx = it.getColumnIndex("thread_id")
                        val readIdx = it.getColumnIndex("read")
                        val subjectIdx = it.getColumnIndex("subject")
                        val scIdx = it.getColumnIndex("service_center")

                        while (it.moveToNext()) {
                            try {
                                results.add(RecoveredSms(
                                    id = if (idIdx >= 0) it.getLong(idIdx) else 0L,
                                    address = if (addrIdx >= 0) it.getString(addrIdx) ?: "" else "",
                                    body = if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else "",
                                    date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L,
                                    dateSent = if (dateSentIdx >= 0 && !it.isNull(dateSentIdx)) it.getLong(dateSentIdx) else null,
                                    type = mapSmsType(if (typeIdx >= 0) it.getInt(typeIdx) else 1),
                                    threadId = if (threadIdx >= 0) it.getLong(threadIdx) else 0L,
                                    read = if (readIdx >= 0) it.getInt(readIdx) == 1 else false,
                                    subject = if (subjectIdx >= 0) it.getString(subjectIdx) else null,
                                    serviceCenter = if (scIdx >= 0) it.getString(scIdx) else null,
                                    isRecovered = false,
                                    recoveryMethod = "direct_query"
                                ))
                            } catch (_: Exception) {}
                        }
                    }
                } finally {
                    db.close()
                }
            } finally {
                localCopy.delete()
            }
        } catch (_: Exception) {
            // Database query failed
        }

        results
    }

    // ──────────────────────────────────────────────
    // Call Log Recovery
    // ──────────────────────────────────────────────

    /**
     * Recover deleted call log entries using root access.
     *
     * Uses the same recovery strategies as SMS recovery:
     * 1. WAL file parsing
     * 2. Journal file parsing
     * 3. Free page scanning
     * 4. Orphaned record detection
     *
     * @return Flow of CallLogRecoveryResult entries
     */
    fun recoverDeletedCallLogs(): Flow<CallLogRecoveryResult> = flow {
        emit(CallLogRecoveryResult.Progress("Starting call log recovery", 0f))

        val dbPath = findCallLogDatabase()
        if (dbPath == null) {
            // Fallback to ContentProvider
            emit(CallLogRecoveryResult.Progress("Using ContentProvider fallback", 0.5f))
            val fallbackResults = recoverCallLogsViaContentProvider()
            for (call in fallbackResults) {
                emit(CallLogRecoveryResult.CallLogItem(call, "content_provider"))
            }
            emit(CallLogRecoveryResult.Completed(fallbackResults.size, 0))
            return@flow
        }

        val allRecovered = mutableListOf<RecoveredCallLog>()

        // Strategy 1: Scan WAL file
        emit(CallLogRecoveryResult.Progress("Scanning WAL file for deleted call logs", 0.2f))
        val walResults = scanCallLogWalFile(dbPath)
        allRecovered.addAll(walResults)
        for (call in walResults) {
            emit(CallLogRecoveryResult.CallLogItem(call, "wal_file"))
        }

        // Strategy 2: Scan journal file
        emit(CallLogRecoveryResult.Progress("Scanning journal file for deleted call logs", 0.4f))
        val journalResults = scanCallLogJournalFile(dbPath)
        allRecovered.addAll(journalResults)
        for (call in journalResults) {
            emit(CallLogRecoveryResult.CallLogItem(call, "journal_file"))
        }

        // Strategy 3: Scan free pages
        emit(CallLogRecoveryResult.Progress("Scanning free pages for deleted call logs", 0.6f))
        val freePageResults = scanCallLogFreePages(dbPath)
        allRecovered.addAll(freePageResults)
        for (call in freePageResults) {
            emit(CallLogRecoveryResult.CallLogItem(call, "free_page"))
        }

        // Strategy 4: Check for orphaned records
        emit(CallLogRecoveryResult.Progress("Checking for orphaned call log records", 0.8f))
        val orphanResults = scanCallLogOrphanedRecords(dbPath)
        allRecovered.addAll(orphanResults)
        for (call in orphanResults) {
            emit(CallLogRecoveryResult.CallLogItem(call, "orphaned_record"))
        }

        // Deduplicate
        val uniqueResults = allRecovered.distinctBy { it.number to it.date }

        emit(CallLogRecoveryResult.Completed(uniqueResults.size, allRecovered.size - uniqueResults.size))
    }.flowOn(Dispatchers.IO)

    /**
     * Scan the call log database directly using root access.
     *
     * @return List of all call log records found
     */
    suspend fun scanCallLogDatabase(): List<RecoveredCallLog> = withContext(Dispatchers.IO) {
        val dbPath = findCallLogDatabase() ?: return@withContext emptyList()
        val results = mutableListOf<RecoveredCallLog>()

        try {
            val localCopy = copyDatabaseToLocal(dbPath, "contacts2_scan.db") ?: return@withContext emptyList()

            try {
                val db = SQLiteDatabase.openDatabase(
                    localCopy.absolutePath, null,
                    SQLiteDatabase.OPEN_READONLY
                )

                try {
                    val cursor = db.rawQuery(
                        "SELECT _id, number, date, duration, type, name, " +
                        "phone_account_id, features, data_usage " +
                        "FROM calls ORDER BY date DESC",
                        null
                    )

                    cursor.use {
                        val idIdx = it.getColumnIndex("_id")
                        val numIdx = it.getColumnIndex("number")
                        val dateIdx = it.getColumnIndex("date")
                        val durIdx = it.getColumnIndex("duration")
                        val typeIdx = it.getColumnIndex("type")
                        val nameIdx = it.getColumnIndex("name")
                        val paiIdx = it.getColumnIndex("phone_account_id")
                        val featIdx = it.getColumnIndex("features")
                        val duIdx = it.getColumnIndex("data_usage")

                        while (it.moveToNext()) {
                            try {
                                results.add(RecoveredCallLog(
                                    id = if (idIdx >= 0) it.getLong(idIdx) else 0L,
                                    number = if (numIdx >= 0) it.getString(numIdx) ?: "" else "",
                                    date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L,
                                    duration = if (durIdx >= 0) it.getLong(durIdx) else 0L,
                                    callType = mapCallType(if (typeIdx >= 0) it.getInt(typeIdx) else 0),
                                    contactName = if (nameIdx >= 0) it.getString(nameIdx) else null,
                                    phoneAccountId = if (paiIdx >= 0) it.getString(paiIdx) else null,
                                    features = if (featIdx >= 0) it.getInt(featIdx) else 0,
                                    dataUsage = if (duIdx >= 0 && !it.isNull(duIdx)) it.getLong(duIdx) else null,
                                    isRecovered = false,
                                    recoveryMethod = "direct_query"
                                ))
                            } catch (_: Exception) {}
                        }
                    }
                } finally {
                    db.close()
                }
            } finally {
                localCopy.delete()
            }
        } catch (_: Exception) {}

        results
    }

    // ──────────────────────────────────────────────
    // SQLite Page Parsing for Deleted Records
    // ──────────────────────────────────────────────

    /**
     * Parse deleted SQLite records from database pages.
     *
     * When records are deleted in SQLite, the cell data may remain in
     * the page until it is overwritten. This method scans raw database
     * pages for cell data that appears to be valid SMS or call log
     * records but is not referenced by any active B-tree cell.
     *
     * @param dbPath Path to the SQLite database
     * @return List of deleted SMS records found in page data
     */
    suspend fun parseDeletedSqliteRecords(dbPath: String): List<RawDeletedRecord> = withContext(Dispatchers.IO) {
        val records = mutableListOf<RawDeletedRecord>()

        if (!rootManager.isRootGranted) return@withContext emptyList()

        try {
            // Read the database header to get page size
            val headerResult = rootManager.executeCommandWithOutput(
                "dd if='$dbPath' bs=1 skip=$SQLITE_PAGE_SIZE_OFFSET count=2 2>/dev/null | xxd -p"
            )

            if (!headerResult.success) return@withContext emptyList()

            val headerHex = headerResult.stdout.replace("\\s".toRegex(), "")
            val pageSizeBytes = hexToByteArray(headerHex)
            if (pageSizeBytes.size < 2) return@withContext emptyList()

            val pageSize = ByteBuffer.wrap(pageSizeBytes).order(ByteOrder.BIG_ENDIAN).short.toInt()
            val effectivePageSize = if (pageSize == 1) 65536 else pageSize

            // Read free page list info from header
            val freePageResult = rootManager.executeCommandWithOutput(
                "dd if='$dbPath' bs=1 skip=$SQLITE_FREE_PAGE_OFFSET count=8 2>/dev/null | xxd -p"
            )

            if (!freePageResult.success) return@withContext emptyList()

            val freePageData = hexToByteArray(freePageResult.stdout.replace("\\s".toRegex(), ""))
            if (freePageData.size < 8) return@withContext emptyList()

            val freePageCount = ByteBuffer.wrap(freePageData, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            val firstFreePage = ByteBuffer.wrap(freePageData, 4, 4).order(ByteOrder.BIG_ENDIAN).int

            if (freePageCount == 0) return@withContext emptyList()

            // Read each free page and parse for deleted records
            var currentPage = firstFreePage
            var pagesProcessed = 0

            while (currentPage > 0 && pagesProcessed < freePageCount && currentCoroutineContext().isActive) {
                val pageOffset = (currentPage - 1).toLong() * effectivePageSize

                // Read the page content
                val pageResult = rootManager.executeCommandWithOutput(
                    "dd if='$dbPath' bs=$effectivePageSize skip=${pageOffset / effectivePageSize} count=1 2>/dev/null | xxd -p"
                )

                if (pageResult.success && pageResult.stdout.isNotEmpty()) {
                    val pageData = hexToByteArray(pageResult.stdout.replace("\\s".toRegex(), ""))

                    // Parse the free page - first 4 bytes are the next free page pointer
                    if (pageData.size >= 4) {
                        val nextPage = ByteBuffer.wrap(pageData, 0, 4).order(ByteOrder.BIG_ENDIAN).int

                        // Scan page content for recognizable record patterns
                        val deletedRecords = extractRecordsFromPage(pageData, effectivePageSize, currentPage)
                        records.addAll(deletedRecords)

                        currentPage = nextPage
                        pagesProcessed++
                    } else {
                        break
                    }
                } else {
                    break
                }
            }

        } catch (_: Exception) {}

        records
    }

    // ──────────────────────────────────────────────
    // Private Helper Methods
    // ──────────────────────────────────────────────

    /**
     * Find the SMS database path
     */
    private suspend fun findSmsDatabase(): String? {
        if (!rootManager.isRootGranted) return null

        for (path in SMS_DB_PATHS) {
            if (rootManager.fileExistsAsRoot(path)) return path
        }
        return null
    }

    /**
     * Find the call log database path
     */
    private suspend fun findCallLogDatabase(): String? {
        if (!rootManager.isRootGranted) return null

        for (path in CALL_LOG_DB_PATHS) {
            if (rootManager.fileExistsAsRoot(path)) return path
        }
        return null
    }

    /**
     * Copy a root-protected database to the app's local directory
     */
    private suspend fun copyDatabaseToLocal(dbPath: String, localName: String): File? {
        return try {
            val localDir = File(context.cacheDir, TEMP_RECOVERY_DIR)
            if (!localDir.exists()) localDir.mkdirs()

            val localFile = File(localDir, localName)

            // Use root to copy the database file
            val copyCmd = "cp '$dbPath' '${localFile.absolutePath}' 2>/dev/null && " +
                    "chmod 666 '${localFile.absolutePath}' 2>/dev/null"

            if (!rootManager.executeCommand(copyCmd)) return null

            // Also copy WAL and SHM files if they exist
            rootManager.executeCommand("cp '${dbPath}-wal' '${localFile.absolutePath}-wal' 2>/dev/null")
            rootManager.executeCommand("cp '${dbPath}-shm' '${localFile.absolutePath}-shm' 2>/dev/null")
            rootManager.executeCommand("chmod 666 '${localFile.absolutePath}-wal' 2>/dev/null")
            rootManager.executeCommand("chmod 666 '${localFile.absolutePath}-shm' 2>/dev/null")

            // Close any open WAL by checkpointing
            rootManager.executeCommand("sqlite3 '${localFile.absolutePath}' 'PRAGMA wal_checkpoint(FULL);' 2>/dev/null")

            if (localFile.exists() && localFile.length() > 0) localFile else null
        } catch (_: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────────
    // WAL File Scanning
    // ──────────────────────────────────────────────

    /**
     * Scan SMS WAL file for deleted records
     */
    private suspend fun scanSmsWalFile(dbPath: String): List<RecoveredSms> {
        return scanWalFile(dbPath) { pageData ->
            parseSmsRecordsFromData(pageData)
        }
    }

    /**
     * Scan call log WAL file for deleted records
     */
    private suspend fun scanCallLogWalFile(dbPath: String): List<RecoveredCallLog> {
        return scanWalFile(dbPath) { pageData ->
            parseCallLogRecordsFromData(pageData)
        }
    }

    /**
     * Generic WAL file scanner
     *
     * SQLite WAL files contain modified pages before they are checkpointed
     * back into the main database. Deleted records may still exist in
     * these pages even after being removed from the main B-tree.
     */
    private suspend fun <T> scanWalFile(dbPath: String, parser: (ByteArray) -> List<T>): List<T> {
        val results = mutableListOf<T>()

        try {
            val walPath = "$dbPath-wal"
            if (!rootManager.fileExistsAsRoot(walPath)) return emptyList()

            // Read WAL header (32 bytes)
            val walHeaderResult = rootManager.executeCommandWithOutput(
                "dd if='$walPath' bs=1 skip=0 count=32 2>/dev/null | xxd -p"
            )

            if (!walHeaderResult.success) return emptyList()

            val walHeader = hexToByteArray(walHeaderResult.stdout.replace("\\s".toRegex(), ""))
            if (walHeader.size < 32) return emptyList()

            // Parse WAL header
            val magic = ByteBuffer.wrap(walHeader, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            val fileFormat = ByteBuffer.wrap(walHeader, 4, 4).order(ByteOrder.BIG_ENDIAN).int
            val pageSize = ByteBuffer.wrap(walHeader, 8, 4).order(ByteOrder.BIG_ENDIAN).int
            val checkpointSeq = ByteBuffer.wrap(walHeader, 12, 4).order(ByteOrder.BIG_ENDIAN).int

            if (pageSize <= 0 || pageSize > 65536) return emptyList()

            // WAL frame header is 24 bytes, each frame = header + page
            val frameSize = 24 + pageSize
            var offset = 32L // Start after WAL header

            // Read WAL size
            val walSizeResult = rootManager.executeCommandWithOutput("stat -c '%s' '$walPath' 2>/dev/null")
            val walSize = walSizeResult.stdout.trim().toLongOrNull() ?: 0L

            if (walSize <= 32L || walSize > MAX_WAL_SIZE) return emptyList()

            val totalFrames = ((walSize - 32) / frameSize).toInt()

            for (frameIndex in 0 until totalFrames) {
                if (!currentCoroutineContext().isActive) break
                val frameOffset = offset + frameIndex * frameSize

                // Read the page data (skip frame header)
                val pageDataResult = rootManager.executeCommandWithOutput(
                    "dd if='$walPath' bs=$pageSize skip=${(frameOffset + 24) / pageSize} count=1 2>/dev/null | xxd -p"
                )

                if (pageDataResult.success && pageDataResult.stdout.isNotEmpty()) {
                    val pageData = hexToByteArray(pageDataResult.stdout.replace("\\s".toRegex(), ""))
                    if (pageData.isNotEmpty()) {
                        results.addAll(parser(pageData))
                    }
                }
            }

        } catch (_: Exception) {}

        return results
    }

    // ──────────────────────────────────────────────
    // Journal File Scanning
    // ──────────────────────────────────────────────

    /**
     * Scan SMS journal file for deleted records
     */
    private suspend fun scanSmsJournalFile(dbPath: String): List<RecoveredSms> {
        return scanJournalFile(dbPath) { data -> parseSmsRecordsFromData(data) }
    }

    /**
     * Scan call log journal file for deleted records
     */
    private suspend fun scanCallLogJournalFile(dbPath: String): List<RecoveredCallLog> {
        return scanJournalFile(dbPath) { data -> parseCallLogRecordsFromData(data) }
    }

    /**
     * Generic journal file scanner
     *
     * SQLite rollback journals contain the original page data before
     * modifications. If a transaction that deleted records was rolled
     * back, the journal may contain the pre-deletion data.
     */
    private suspend fun <T> scanJournalFile(dbPath: String, parser: (ByteArray) -> List<T>): List<T> {
        val results = mutableListOf<T>()

        try {
            val journalPath = "$dbPath-journal"
            if (!rootManager.fileExistsAsRoot(journalPath)) return emptyList()

            // Read journal header
            val journalSizeResult = rootManager.executeCommandWithOutput("stat -c '%s' '$journalPath' 2>/dev/null")
            val journalSize = journalSizeResult.stdout.trim().toLongOrNull() ?: 0L

            if (journalSize <= 0) return emptyList()

            // Read the entire journal (usually small)
            // For larger journals, read in chunks
            val readSize = minOf(journalSize, MAX_WAL_SIZE)
            val journalDataResult = rootManager.executeCommandWithOutput(
                "dd if='$journalPath' bs=1 count=$readSize 2>/dev/null | xxd -p"
            )

            if (journalDataResult.success && journalDataResult.stdout.isNotEmpty()) {
                val journalData = hexToByteArray(journalDataResult.stdout.replace("\\s".toRegex(), ""))
                if (journalData.isNotEmpty()) {
                    results.addAll(parser(journalData))
                }
            }

        } catch (_: Exception) {}

        return results
    }

    // ──────────────────────────────────────────────
    // Free Page Scanning
    // ──────────────────────────────────────────────

    private suspend fun scanSmsFreePages(dbPath: String): List<RecoveredSms> {
        val rawRecords = parseDeletedSqliteRecords(dbPath)
        return rawRecords.mapNotNull { record ->
            parseSmsFromRawRecord(record)
        }
    }

    private suspend fun scanCallLogFreePages(dbPath: String): List<RecoveredCallLog> {
        val rawRecords = parseDeletedSqliteRecords(dbPath)
        return rawRecords.mapNotNull { record ->
            parseCallLogFromRawRecord(record)
        }
    }

    // ──────────────────────────────────────────────
    // Orphaned Record Scanning
    // ──────────────────────────────────────────────

    private suspend fun scanSmsOrphanedRecords(dbPath: String): List<RecoveredSms> {
        val results = mutableListOf<RecoveredSms>()

        try {
            val localCopy = copyDatabaseToLocal(dbPath, "mmssms_orphan.db") ?: return emptyList()

            try {
                val db = SQLiteDatabase.openDatabase(
                    localCopy.absolutePath, null,
                    SQLiteDatabase.OPEN_READONLY
                )

                try {
                    val cursor = db.rawQuery(
                        "SELECT s._id, s.address, s.body, s.date, s.date_sent, s.type, " +
                        "s.thread_id, s.read, s.subject, s.service_center " +
                        "FROM sms s LEFT JOIN threads t ON s.thread_id = t._id " +
                        "WHERE t._id IS NULL OR s.thread_id IS NULL OR s.thread_id = 0",
                        null
                    )

                    cursor.use {
                        val idIdx = it.getColumnIndex("_id")
                        val addrIdx = it.getColumnIndex("address")
                        val bodyIdx = it.getColumnIndex("body")
                        val dateIdx = it.getColumnIndex("date")
                        val dateSentIdx = it.getColumnIndex("date_sent")
                        val typeIdx = it.getColumnIndex("type")
                        val threadIdx = it.getColumnIndex("thread_id")
                        val readIdx = it.getColumnIndex("read")
                        val subjectIdx = it.getColumnIndex("subject")
                        val scIdx = it.getColumnIndex("service_center")

                        while (it.moveToNext()) {
                            try {
                                results.add(RecoveredSms(
                                    id = if (idIdx >= 0) it.getLong(idIdx) else 0L,
                                    address = if (addrIdx >= 0) it.getString(addrIdx) ?: "" else "",
                                    body = if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else "",
                                    date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L,
                                    dateSent = if (dateSentIdx >= 0 && !it.isNull(dateSentIdx)) it.getLong(dateSentIdx) else null,
                                    type = mapSmsType(if (typeIdx >= 0) it.getInt(typeIdx) else 1),
                                    threadId = if (threadIdx >= 0) it.getLong(threadIdx) else 0L,
                                    read = if (readIdx >= 0) it.getInt(readIdx) == 1 else false,
                                    subject = if (subjectIdx >= 0) it.getString(subjectIdx) else null,
                                    serviceCenter = if (scIdx >= 0) it.getString(scIdx) else null,
                                    isRecovered = true,
                                    recoveryMethod = "orphaned_record"
                                ))
                            } catch (_: Exception) {}
                        }
                    }
                } finally {
                    db.close()
                }
            } finally {
                localCopy.delete()
            }
        } catch (_: Exception) {}

        return results
    }

    private suspend fun scanCallLogOrphanedRecords(dbPath: String): List<RecoveredCallLog> {
        val results = mutableListOf<RecoveredCallLog>()

        try {
            val localCopy = copyDatabaseToLocal(dbPath, "contacts2_orphan.db") ?: return emptyList()

            try {
                val db = SQLiteDatabase.openDatabase(
                    localCopy.absolutePath, null,
                    SQLiteDatabase.OPEN_READONLY
                )

                try {
                    val cursor = db.rawQuery(
                        "SELECT _id, number, date, duration, type, name, " +
                        "phone_account_id, features, data_usage " +
                        "FROM calls WHERE number IS NULL OR number = '' OR " +
                        "type NOT IN (1,2,3,5,6,7)",
                        null
                    )

                    cursor.use {
                        val idIdx = it.getColumnIndex("_id")
                        val numIdx = it.getColumnIndex("number")
                        val dateIdx = it.getColumnIndex("date")
                        val durIdx = it.getColumnIndex("duration")
                        val typeIdx = it.getColumnIndex("type")
                        val nameIdx = it.getColumnIndex("name")
                        val paiIdx = it.getColumnIndex("phone_account_id")
                        val featIdx = it.getColumnIndex("features")
                        val duIdx = it.getColumnIndex("data_usage")

                        while (it.moveToNext()) {
                            try {
                                results.add(RecoveredCallLog(
                                    id = if (idIdx >= 0) it.getLong(idIdx) else 0L,
                                    number = if (numIdx >= 0) it.getString(numIdx) ?: "" else "",
                                    date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L,
                                    duration = if (durIdx >= 0) it.getLong(durIdx) else 0L,
                                    callType = mapCallType(if (typeIdx >= 0) it.getInt(typeIdx) else 0),
                                    contactName = if (nameIdx >= 0) it.getString(nameIdx) else null,
                                    phoneAccountId = if (paiIdx >= 0) it.getString(paiIdx) else null,
                                    features = if (featIdx >= 0) it.getInt(featIdx) else 0,
                                    dataUsage = if (duIdx >= 0 && !it.isNull(duIdx)) it.getLong(duIdx) else null,
                                    isRecovered = true,
                                    recoveryMethod = "orphaned_record"
                                ))
                            } catch (_: Exception) {}
                        }
                    }
                } finally {
                    db.close()
                }
            } finally {
                localCopy.delete()
            }
        } catch (_: Exception) {}

        return results
    }

    // ──────────────────────────────────────────────
    // Data Parsing from Raw Page Data
    // ──────────────────────────────────────────────

    /**
     * Parse SMS records from raw page data
     *
     * Looks for recognizable SMS record patterns in raw SQLite page data.
     * SMS records in the database have a specific structure with fields
     * for address, body, date, type, etc.
     */
    private fun parseSmsRecordsFromData(data: ByteArray): List<RecoveredSms> {
        val results = mutableListOf<RecoveredSms>()

        try {
            // Search for phone number patterns in the data
            // Phone numbers are typically stored as strings preceded by length
            val dataStr = String(data, Charsets.ISO_8859_1)

            // Look for patterns that resemble SMS records
            // A phone number followed by message text and a timestamp
            val phonePattern = Regex("""[+]?[\d\s\-()]{7,15}""")
            val phoneMatches = phonePattern.findAll(dataStr)

            for (match in phoneMatches) {
                val phoneStart = match.range.first
                val phoneNumber = match.value.trim()

                if (phoneNumber.length < 7) continue

                // Try to extract surrounding context as potential SMS data
                val contextStart = maxOf(0, phoneStart - 100)
                val contextEnd = minOf(data.size, phoneStart + 500)
                val context = data.copyOfRange(contextStart, contextEnd)

                // Try to find a timestamp near the phone number
                val timestamp = extractTimestamp(context)

                // Try to find message body near the phone number
                val body = extractTextBody(context, phoneNumber.length)

                if (body.isNotEmpty() || timestamp > 0L) {
                    results.add(RecoveredSms(
                        id = 0,
                        address = phoneNumber,
                        body = body,
                        date = timestamp,
                        type = SmsType.INBOX, // Default assumption
                        threadId = 0,
                        isRecovered = true,
                        recoveryMethod = "raw_page_parse"
                    ))
                }
            }
        } catch (_: Exception) {}

        return results
    }

    /**
     * Parse call log records from raw page data
     */
    private fun parseCallLogRecordsFromData(data: ByteArray): List<RecoveredCallLog> {
        val results = mutableListOf<RecoveredCallLog>()

        try {
            val dataStr = String(data, Charsets.ISO_8859_1)
            val phonePattern = Regex("""[+]?[\d\s\-()]{7,15}""")
            val phoneMatches = phonePattern.findAll(dataStr)

            for (match in phoneMatches) {
                val phoneNumber = match.value.trim()
                if (phoneNumber.length < 7) continue

                val contextStart = maxOf(0, match.range.first - 100)
                val contextEnd = minOf(data.size, match.range.first + 300)
                val context = data.copyOfRange(contextStart, contextEnd)

                val timestamp = extractTimestamp(context)
                val duration = extractDuration(context)

                if (timestamp > 0L) {
                    results.add(RecoveredCallLog(
                        id = 0,
                        number = phoneNumber,
                        date = timestamp,
                        duration = duration,
                        callType = CallLogEntity.CallType.UNKNOWN,
                        isRecovered = true,
                        recoveryMethod = "raw_page_parse"
                    ))
                }
            }
        } catch (_: Exception) {}

        return results
    }

    /**
     * Extract a timestamp from raw data
     *
     * Looks for 8-byte Unix timestamp values in the data.
     * Android typically stores timestamps as milliseconds since epoch.
     */
    private fun extractTimestamp(data: ByteArray): Long {
        try {
            // Search for 8-byte long values that look like reasonable timestamps
            // SMS timestamps are typically after 2010-01-01 (1262304000000)
            // and before current time + 1 year
            val minTimestamp = 1262304000000L // 2010-01-01
            val maxTimestamp = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000

            for (offset in 0 until data.size - 8) {
                try {
                    val timestamp = ByteBuffer.wrap(data, offset, 8)
                        .order(ByteOrder.BIG_ENDIAN).long

                    if (timestamp in minTimestamp..maxTimestamp) {
                        return timestamp
                    }

                    // Also try little-endian
                    val timestampLE = ByteBuffer.wrap(data, offset, 8)
                        .order(ByteOrder.LITTLE_ENDIAN).long

                    if (timestampLE in minTimestamp..maxTimestamp) {
                        return timestampLE
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        return 0L
    }

    /**
     * Extract call duration from raw data
     */
    private fun extractDuration(data: ByteArray): Long {
        try {
            for (offset in 0 until data.size - 8) {
                val value = ByteBuffer.wrap(data, offset, 8)
                    .order(ByteOrder.LITTLE_ENDIAN).long
                // Reasonable call duration: 0 to 24 hours in seconds
                if (value in 0..86400L) {
                    return value
                }
            }
        } catch (_: Exception) {}

        return 0L
    }

    /**
     * Extract text body from raw data near a phone number
     */
    private fun extractTextBody(data: ByteArray, phoneLength: Int): String {
        try {
            // Look for UTF-8 text after the phone number
            // SMS bodies are stored as strings with length prefix
            val phoneIndex = indexOfPhoneNumber(data)
            if (phoneIndex < 0) return ""

            val bodyStart = phoneIndex + phoneLength + 10 // Skip past number and potential metadata
            if (bodyStart >= data.size) return ""

            val bodyEnd = minOf(data.size, bodyStart + 1000) // Max 1000 bytes for SMS body
            val bodyBytes = data.copyOfRange(bodyStart, bodyEnd)

            // Try to decode as UTF-8
            val text = String(bodyBytes, Charsets.UTF_8)

            // Clean up non-printable characters
            val cleaned = text.takeWhile { it.isLetterOrDigit() || it.isWhitespace() || it in ".,!?;:'\"-()@/" }
            return cleaned.trim().takeIf { it.length >= 5 } ?: ""
        } catch (_: Exception) {
            return ""
        }
    }

    private fun indexOfPhoneNumber(data: ByteArray): Int {
        // Simplified: search for digit sequences
        for (i in data.indices) {
            if (data[i].toInt() and 0xFF in 0x30..0x39) { // ASCII digits
                var count = 0
                var j = i
                while (j < data.size && (data[j].toInt() and 0xFF in 0x30..0x39 || data[j].toInt() and 0xFF == 0x2B || data[j].toInt() and 0xFF == 0x2D)) {
                    count++
                    j++
                }
                if (count >= 7) return i
            }
        }
        return -1
    }

    /**
     * Extract an SMS record from a raw deleted record
     */
    private fun parseSmsFromRawRecord(record: RawDeletedRecord): RecoveredSms? {
        try {
            val dataStr = String(record.data, Charsets.ISO_8859_1)
            val phonePattern = Regex("""[+]?[\d\s\-()]{7,15}""")
            val phoneMatch = phonePattern.find(dataStr) ?: return null

            return RecoveredSms(
                id = 0,
                address = phoneMatch.value.trim(),
                body = extractTextBody(record.data, phoneMatch.value.length),
                date = extractTimestamp(record.data),
                type = SmsType.INBOX,
                threadId = 0,
                isRecovered = true,
                recoveryMethod = "free_page",
                pageNumber = record.pageNumber
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Extract a call log record from a raw deleted record
     */
    private fun parseCallLogFromRawRecord(record: RawDeletedRecord): RecoveredCallLog? {
        try {
            val dataStr = String(record.data, Charsets.ISO_8859_1)
            val phonePattern = Regex("""[+]?[\d\s\-()]{7,15}""")
            val phoneMatch = phonePattern.find(dataStr) ?: return null

            return RecoveredCallLog(
                id = 0,
                number = phoneMatch.value.trim(),
                date = extractTimestamp(record.data),
                duration = extractDuration(record.data),
                callType = CallLogEntity.CallType.UNKNOWN,
                isRecovered = true,
                recoveryMethod = "free_page",
                pageNumber = record.pageNumber
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Extract records from a free page
     */
    private fun extractRecordsFromPage(pageData: ByteArray, pageSize: Int, pageNumber: Int): List<RawDeletedRecord> {
        val records = mutableListOf<RawDeletedRecord>()

        // Skip the first 4 bytes (next free page pointer) and 2 bytes (fragment count)
        // The rest of the page may contain deleted cell data
        if (pageData.size > 8) {
            // Take a reasonable chunk of the page as potential record data
            // Start after the free page header (8 bytes)
            val dataStart = 8
            val dataEnd = minOf(pageData.size, pageSize)

            if (dataEnd > dataStart) {
                records.add(RawDeletedRecord(
                    pageNumber = pageNumber,
                    offsetInPage = dataStart,
                    data = pageData.copyOfRange(dataStart, dataEnd),
                    recordType = "free_page_cell"
                ))
            }
        }

        // Also check if there are fragmented free blocks in the page
        // Free blocks have a 2-byte header pointing to the next free block
        try {
            var blockOffset = 8 // After free page header
            while (blockOffset < pageData.size - 4 && blockOffset > 0) {
                val nextBlock = ByteBuffer.wrap(pageData, blockOffset, 2)
                    .order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

                val blockSize = ByteBuffer.wrap(pageData, blockOffset + 2, 2)
                    .order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

                if (blockSize > 4 && blockOffset + blockSize <= pageData.size) {
                    records.add(RawDeletedRecord(
                        pageNumber = pageNumber,
                        offsetInPage = blockOffset,
                        data = pageData.copyOfRange(blockOffset + 4, blockOffset + blockSize),
                        recordType = "free_block"
                    ))
                }

                if (nextBlock == 0 || nextBlock <= blockOffset) break
                blockOffset = nextBlock
            }
        } catch (_: Exception) {}

        return records
    }

    // ──────────────────────────────────────────────
    // ContentProvider Fallbacks
    // ──────────────────────────────────────────────

    /**
     * Recover SMS via ContentProvider (fallback when root unavailable)
     */
    private fun recoverSmsViaContentProvider(): List<RecoveredSms> {
        val results = mutableListOf<RecoveredSms>()

        try {
            val contentResolver = context.contentResolver
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.DATE_SENT,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.THREAD_ID,
                    Telephony.Sms.READ,
                    Telephony.Sms.SUBJECT,
                    Telephony.Sms.SERVICE_CENTER
                ),
                null, null, "${Telephony.Sms.DATE} DESC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    try {
                        results.add(RecoveredSms(
                            id = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms._ID)),
                            address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "",
                            body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "",
                            date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                            dateSent = it.getColumnIndex(Telephony.Sms.DATE_SENT).takeIf { idx -> idx >= 0 }
                                ?.let { idx -> it.getLong(idx) }.takeIf { v -> v != null && v > 0 },
                            type = mapSmsType(it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))),
                            threadId = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
                            read = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1,
                            subject = it.getColumnIndex(Telephony.Sms.SUBJECT).takeIf { idx -> idx >= 0 }
                                ?.let { idx -> it.getString(idx) },
                            serviceCenter = it.getColumnIndex(Telephony.Sms.SERVICE_CENTER).takeIf { idx -> idx >= 0 }
                                ?.let { idx -> it.getString(idx) },
                            isRecovered = false,
                            recoveryMethod = "content_provider"
                        ))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        return results
    }

    /**
     * Recover call logs via ContentProvider (fallback when root unavailable)
     */
    private fun recoverCallLogsViaContentProvider(): List<RecoveredCallLog> {
        val results = mutableListOf<RecoveredCallLog>()

        try {
            val contentResolver = context.contentResolver
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(
                    android.provider.CallLog.Calls._ID,
                    android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.DATE,
                    android.provider.CallLog.Calls.DURATION,
                    android.provider.CallLog.Calls.TYPE,
                    android.provider.CallLog.Calls.CACHED_NAME,
                    android.provider.CallLog.Calls.PHONE_ACCOUNT_ID,
                    android.provider.CallLog.Calls.FEATURES,
                    android.provider.CallLog.Calls.DATA_USAGE
                ),
                null, null, "${android.provider.CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    try {
                        results.add(RecoveredCallLog(
                            id = it.getLong(it.getColumnIndexOrThrow(android.provider.CallLog.Calls._ID)),
                            number = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER)) ?: "",
                            date = it.getLong(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE)),
                            duration = it.getLong(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DURATION)),
                            callType = mapCallType(it.getInt(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.TYPE))),
                            contactName = it.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME).takeIf { idx -> idx >= 0 }
                                ?.let { idx -> it.getString(idx) },
                            phoneAccountId = it.getColumnIndex(android.provider.CallLog.Calls.PHONE_ACCOUNT_ID).takeIf { idx -> idx >= 0 }
                                ?.let { idx -> it.getString(idx) },
                            features = it.getColumnIndex(android.provider.CallLog.Calls.FEATURES).takeIf { idx -> idx >= 0 }
                                ?.let { idx -> it.getInt(idx) } ?: 0,
                            dataUsage = it.getColumnIndex(android.provider.CallLog.Calls.DATA_USAGE).takeIf { idx -> idx >= 0 }
                                ?.let { idx -> it.getLong(idx) }.takeIf { v -> v != null && v > 0 },
                            isRecovered = false,
                            recoveryMethod = "content_provider"
                        ))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        return results
    }

    // ──────────────────────────────────────────────
    // Type Mapping Utilities
    // ──────────────────────────────────────────────

    private fun mapSmsType(type: Int): SmsType = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> SmsType.INBOX
        Telephony.Sms.MESSAGE_TYPE_SENT -> SmsType.SENT
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> SmsType.DRAFT
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> SmsType.OUTBOX
        Telephony.Sms.MESSAGE_TYPE_FAILED -> SmsType.FAILED
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> SmsType.QUEUED
        else -> SmsType.INBOX
    }

    private fun mapCallType(type: Int): CallLogEntity.CallType = when (type) {
        android.provider.CallLog.Calls.INCOMING_TYPE -> CallLogEntity.CallType.INCOMING
        android.provider.CallLog.Calls.OUTGOING_TYPE -> CallLogEntity.CallType.OUTGOING
        android.provider.CallLog.Calls.MISSED_TYPE -> CallLogEntity.CallType.MISSED
        android.provider.CallLog.Calls.REJECTED_TYPE -> CallLogEntity.CallType.REJECTED
        android.provider.CallLog.Calls.BLOCKED_TYPE -> CallLogEntity.CallType.BLOCKED
        else -> CallLogEntity.CallType.UNKNOWN
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val cleanHex = hex.replace("\\s".toRegex(), "")
        if (cleanHex.length % 2 != 0) return ByteArray(0)
        val len = cleanHex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) +
                    Character.digit(cleanHex[i + 1], 16)).toByte()
        }
        return data
    }
}

// ──────────────────────────────────────────────
// Data Classes
// ──────────────────────────────────────────────

/**
 * A recovered SMS message
 */
data class RecoveredSms(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long? = null,
    val type: SmsType,
    val threadId: Long = 0,
    val read: Boolean = false,
    val subject: String? = null,
    val serviceCenter: String? = null,
    val isRecovered: Boolean,
    val recoveryMethod: String,
    val pageNumber: Int? = null
) {
    /** Convert to database entity */
    fun toEntity(): SmsMessageEntity = SmsMessageEntity(
        address = address,
        body = body,
        date = date,
        dateSent = dateSent,
        type = type,
        threadId = threadId,
        read = read,
        subject = subject,
        serviceCenter = serviceCenter,
        isRecovered = isRecovered,
        recoveryDate = System.currentTimeMillis()
    )
}

/**
 * A recovered call log entry
 */
data class RecoveredCallLog(
    val id: Long,
    val number: String,
    val date: Long,
    val duration: Long = 0L,
    val callType: CallLogEntity.CallType,
    val contactName: String? = null,
    val phoneAccountId: String? = null,
    val features: Int = 0,
    val dataUsage: Long? = null,
    val isRecovered: Boolean,
    val recoveryMethod: String,
    val pageNumber: Int? = null
) {
    /** Convert to database entity */
    fun toEntity(): CallLogEntity = CallLogEntity(
        number = number,
        callType = callType,
        date = date,
        duration = duration,
        contactName = contactName,
        phoneAccountId = phoneAccountId,
        isRecovered = isRecovered,
        recoveryDate = System.currentTimeMillis(),
        features = features,
        dataUsage = dataUsage
    )
}

/**
 * A raw deleted record extracted from SQLite free pages
 */
data class RawDeletedRecord(
    val pageNumber: Int,
    val offsetInPage: Int,
    val data: ByteArray,
    val recordType: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawDeletedRecord) return false
        return pageNumber == other.pageNumber && offsetInPage == other.offsetInPage
    }

    override fun hashCode(): Int = 31 * pageNumber + offsetInPage
}

/**
 * SMS recovery result events
 */
sealed class SmsRecoveryResult {
    data class Progress(val message: String, val progress: Float) : SmsRecoveryResult()
    data class SmsItem(val sms: RecoveredSms, val method: String) : SmsRecoveryResult()
    data class Completed(val totalRecovered: Int, val duplicatesRemoved: Int) : SmsRecoveryResult()
    data class Error(val message: String) : SmsRecoveryResult()
}

/**
 * Call log recovery result events
 */
sealed class CallLogRecoveryResult {
    data class Progress(val message: String, val progress: Float) : CallLogRecoveryResult()
    data class CallLogItem(val callLog: RecoveredCallLog, val method: String) : CallLogRecoveryResult()
    data class Completed(val totalRecovered: Int, val duplicatesRemoved: Int) : CallLogRecoveryResult()
    data class Error(val message: String) : CallLogRecoveryResult()
}
