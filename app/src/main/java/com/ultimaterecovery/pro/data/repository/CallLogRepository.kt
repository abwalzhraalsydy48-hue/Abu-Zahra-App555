package com.ultimaterecovery.pro.data.repository

import android.content.Context
import com.ultimaterecovery.pro.data.local.dao.CallLogDao
import com.ultimaterecovery.pro.data.local.entity.CallLogEntity
import com.ultimaterecovery.pro.data.local.entity.CallLogEntity.CallType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for [CallLogEntity] records.
 *
 * Provides reactive queries for browsing, searching, and filtering
 * recovered call log entries, plus suspend functions for recovery and
 * export operations.
 */
@Singleton
class CallLogRepository @Inject constructor(
    private val callLogDao: CallLogDao,
    @ApplicationContext private val context: Context
) {

    // ──────────────────────────────────────────────
    // Reactive queries
    // ──────────────────────────────────────────────

    /**
     * Emits call log entries matching the given [callType].
     */
    fun getCallLogsByType(callType: CallType): Flow<Resource<List<CallLogEntity>>> =
        callLogDao.getByCallType(callType)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load call logs by type")) }

    /**
     * Searches call logs by phone number or contact name.
     * Delegates to the DAO's number-based query; for broader search
     * the caller should combine results from [getByNumber] and [getByContact].
     */
    fun searchCallLogs(query: String): Flow<Resource<List<CallLogEntity>>> =
        callLogDao.getByNumber(query)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to search call logs")) }

    /**
     * Emits call log entries for the given phone [number].
     */
    fun getByNumber(number: String): Flow<Resource<List<CallLogEntity>>> =
        callLogDao.getByNumber(number)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load call logs by number")) }

    /**
     * Emits call log entries whose date falls within [start]..[end] (inclusive).
     */
    fun getByDateRange(start: Long, end: Long): Flow<Resource<List<CallLogEntity>>> =
        callLogDao.getByDateRange(start, end)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load call logs by date range")) }

    /**
     * Emits call log entries associated with the given [contactName].
     */
    fun getByContact(contactName: String): Flow<Resource<List<CallLogEntity>>> =
        callLogDao.getByContactName(contactName)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load call logs by contact")) }

    /**
     * Emits the set of distinct phone numbers found across all call logs.
     */
    fun getUniqueNumbers(): Flow<Resource<List<String>>> =
        callLogDao.getUniqueNumbers()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load unique numbers")) }

    /**
     * Emits the total number of recovered call log entries.
     */
    fun getCount(): Flow<Resource<Int>> =
        callLogDao.getCount()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to get call log count")) }

    // ──────────────────────────────────────────────
    // Mutations
    // ──────────────────────────────────────────────

    /**
     * Persists a list of recovered call logs to the database.
     *
     * Each entry is marked as `isRecovered = true` before insertion.
     *
     * @return [Resource] with the list of generated row IDs.
     */
    suspend fun recoverCallLogs(callLogs: List<CallLogEntity>): Resource<List<Long>> =
        try {
            val recoveredLogs = callLogs.map { log ->
                log.copy(isRecovered = true, recoveryDate = System.currentTimeMillis())
            }
            val ids = callLogDao.insertAll(recoveredLogs)
            Resource.success(ids)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to recover call logs")
        }

    // ──────────────────────────────────────────────
    // Export
    // ──────────────────────────────────────────────

    /**
     * Exports the given [callLogs] to a plain-text file.
     *
     * @return [Resource] with the absolute path of the generated file.
     */
    suspend fun exportToTxt(callLogs: List<CallLogEntity>, fileName: String = "call_log_export.txt"): Resource<String> =
        try {
            withContext(Dispatchers.IO) {
                val exportDir = File(context.getExternalFilesDir(null), "exports")
                if (!exportDir.exists()) exportDir.mkdirs()
                val file = File(exportDir, fileName)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                file.bufferedWriter().use { writer ->
                    writer.write("Call Log Export — ${dateFormat.format(Date())}\n")
                    writer.write("=".repeat(50) + "\n\n")
                    for (log in callLogs) {
                        writer.write("Type: ${log.callType}\n")
                        writer.write("Number: ${log.number}\n")
                        log.contactName?.let { writer.write("Contact: $it\n") }
                        writer.write("Date: ${dateFormat.format(Date(log.date))}\n")
                        writer.write("Duration: ${formatDuration(log.duration)}\n")
                        writer.write("-".repeat(50) + "\n")
                    }
                }
                Resource.success(file.absolutePath)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to export call logs to TXT")
        }

    /**
     * Exports the given [callLogs] to a PDF file.
     *
     * @return [Resource] with the absolute path of the generated file.
     */
    suspend fun exportToPdf(callLogs: List<CallLogEntity>, fileName: String = "call_log_export.pdf"): Resource<String> =
        try {
            withContext(Dispatchers.IO) {
                val exportDir = File(context.getExternalFilesDir(null), "exports")
                if (!exportDir.exists()) exportDir.mkdirs()
                val file = File(exportDir, fileName)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                // Placeholder: write structured text as PDF content.
                // In production, use iText or Android PdfDocument for proper PDF generation.
                file.bufferedWriter().use { writer ->
                    writer.write("%PDF-1.4 Call Log Export\n")
                    writer.write("Generated: ${dateFormat.format(Date())}\n\n")
                    for (log in callLogs) {
                        writer.write("[${log.callType}] ${log.number}")
                        log.contactName?.let { writer.write(" ($it)") }
                        writer.write(" — ${dateFormat.format(Date(log.date))}")
                        writer.write(" — ${formatDuration(log.duration)}\n")
                    }
                }
                Resource.success(file.absolutePath)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to export call logs to PDF")
        }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m ${s}s"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }
}
