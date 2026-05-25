package com.ultimaterecovery.pro.data.repository

import android.content.Context
import com.ultimaterecovery.pro.data.local.dao.SmsMessageDao
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity.SmsType
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
 * Repository for [SmsMessageEntity] records.
 *
 * Provides reactive queries for browsing, searching, and filtering
 * recovered SMS messages, plus suspend functions for recovery, and
 * exporting messages to TXT or PDF.
 */
@Singleton
class SmsRepository @Inject constructor(
    private val smsMessageDao: SmsMessageDao,
    @ApplicationContext private val context: Context
) {

    // ──────────────────────────────────────────────
    // Reactive queries
    // ──────────────────────────────────────────────

    /**
     * Emits messages of the given [type] (INBOX, SENT, …).
     */
    fun getMessagesByType(type: SmsType): Flow<Resource<List<SmsMessageEntity>>> =
        smsMessageDao.getByType(type)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load messages by type")) }

    /**
     * Full-text search across message body and address fields.
     */
    fun searchMessages(query: String): Flow<Resource<List<SmsMessageEntity>>> =
        smsMessageDao.searchMessages(query)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to search messages")) }

    /**
     * Emits all messages from the given [address] (phone number).
     */
    fun getMessagesByAddress(address: String): Flow<Resource<List<SmsMessageEntity>>> =
        smsMessageDao.getByAddress(address)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load messages by address")) }

    /**
     * Emits messages whose date falls within [start]..[end] (inclusive).
     */
    fun getMessagesByDateRange(start: Long, end: Long): Flow<Resource<List<SmsMessageEntity>>> =
        smsMessageDao.getByDateRange(start, end)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load messages by date range")) }

    /**
     * Emits messages associated with the given [contactName].
     */
    fun getMessagesByContact(contactName: String): Flow<Resource<List<SmsMessageEntity>>> =
        smsMessageDao.getByContactName(contactName)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load messages by contact")) }

    /**
     * Emits the total number of recovered SMS messages.
     */
    fun getCount(): Flow<Resource<Int>> =
        smsMessageDao.getCount()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to get SMS count")) }

    // ──────────────────────────────────────────────
    // Mutations
    // ──────────────────────────────────────────────

    /**
     * Persists a list of recovered SMS messages to the database.
     *
     * Each message is marked as `isRecovered = true` before insertion.
     *
     * @return [Resource] with the list of generated row IDs.
     */
    suspend fun recoverMessages(messages: List<SmsMessageEntity>): Resource<List<Long>> =
        try {
            val recoveredMessages = messages.map { msg ->
                msg.copy(isRecovered = true, recoveryDate = System.currentTimeMillis())
            }
            val ids = smsMessageDao.insertAll(recoveredMessages)
            Resource.success(ids)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to recover messages")
        }

    // ──────────────────────────────────────────────
    // Export
    // ──────────────────────────────────────────────

    /**
     * Exports the given [messages] to a plain-text file.
     *
     * @return [Resource] with the absolute path of the generated file.
     */
    suspend fun exportToTxt(messages: List<SmsMessageEntity>, fileName: String = "sms_export.txt"): Resource<String> =
        try {
            withContext(Dispatchers.IO) {
                val exportDir = File(context.getExternalFilesDir(null), "exports")
                if (!exportDir.exists()) exportDir.mkdirs()
                val file = File(exportDir, fileName)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                file.bufferedWriter().use { writer ->
                    writer.write("SMS Export — ${dateFormat.format(Date())}\n")
                    writer.write("=" .repeat(50) + "\n\n")
                    for (msg in messages) {
                        writer.write("Type: ${msg.type}\n")
                        writer.write("Address: ${msg.address}\n")
                        msg.contactName?.let { writer.write("Contact: $it\n") }
                        writer.write("Date: ${dateFormat.format(Date(msg.date))}\n")
                        msg.subject?.let { writer.write("Subject: $it\n") }
                        writer.write("Body: ${msg.body}\n")
                        writer.write("-".repeat(50) + "\n")
                    }
                }
                Resource.success(file.absolutePath)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to export SMS to TXT")
        }

    /**
     * Exports the given [messages] to a PDF file.
     *
     * This is a lightweight implementation that writes structured text
     * content. A full production version would use a PDF rendering library.
     *
     * @return [Resource] with the absolute path of the generated file.
     */
    suspend fun exportToPdf(messages: List<SmsMessageEntity>, fileName: String = "sms_export.pdf"): Resource<String> =
        try {
            withContext(Dispatchers.IO) {
                val exportDir = File(context.getExternalFilesDir(null), "exports")
                if (!exportDir.exists()) exportDir.mkdirs()
                val file = File(exportDir, fileName)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                // Placeholder: write structured text as PDF content.
                // In production, use iText or Android PdfDocument for proper PDF generation.
                file.bufferedWriter().use { writer ->
                    writer.write("%PDF-1.4 SMS Export\n")
                    writer.write("Generated: ${dateFormat.format(Date())}\n\n")
                    for (msg in messages) {
                        writer.write("[${msg.type}] ${msg.address} — ${dateFormat.format(Date(msg.date))}\n")
                        writer.write("${msg.body}\n\n")
                    }
                }
                Resource.success(file.absolutePath)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to export SMS to PDF")
        }
}
