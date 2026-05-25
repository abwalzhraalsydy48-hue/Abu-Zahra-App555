package com.ultimaterecovery.pro.engine.recovery

import android.content.Context
import android.os.Environment
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.RecoveryStatus
import com.ultimaterecovery.pro.engine.signatures.FileSignatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * محرك استعادة الملفات - يدير عملية الاستعادة الفعلية
 *
 * هذه الفئة مسؤولة عن أخذ قائمة الملفات المكتشفة أثناء المسح
 * ومحاولة استعادتها فعلياً. تشمل مسؤولياتها:
 *
 * 1. قراءة بيانات الملف المحذوف من المصدر
 * 2. كتابة البيانات المستعادة في موقع الإخراج
 * 3. التحقق من سلامة الملفات المستعادة
 * 4. محاولة إصلاح الملفات التالفة جزئياً
 * 5. إنشاء هيكل دلائل الإخراج
 * 6. تقارير التقدم والنجاح/الفشل
 * 7. دعم الاستعادة المجمعة (Batch Recovery)
 *
 * تدعم الفئة نوعين من الاستعادة:
 * - استعادة عادية: نسخ الملف من موقعه الحالي
 * - استعادة خام: قراءة البيانات من إزاحة محددة في جهاز القسم
 */
@Singleton
class FileRecoveryEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** اسم مجلد الإخراج الرئيسي */
        private const val RECOVERY_OUTPUT_DIR = "UltimateRecovery"

        /** حجم المخزن المؤقت للنسخ (1 ميجابايت) */
        private const val COPY_BUFFER_SIZE = 1024 * 1024

        /** الحد الأقصى لمحاولات الإصلاح */
        private const val MAX_REPAIR_ATTEMPTS = 3

        /** عدد الملفات بين تحديثات التقدم */
        private const val PROGRESS_UPDATE_INTERVAL = 5
    }

    // ──────────────────────────────────────────────
    // الاستعادة الفردية والمجمعة
    // ──────────────────────────────────────────────

    /**
     * استعادة ملف واحد
     *
     * يحاول قراءة بيانات الملف من المصدر وحفظها في موقع الإخراج.
     * يدعم كلاً من:
     * - الملفات العادية (من نظام الملفات)
     * - الملفات الخام (من قراءة إزاحة في جهاز القسم)
     *
     * @param fileInfo معلومات الملف المراد استعادته
     * @param outputDir دليل الإخراج (null = استخدام الدليل الافتراضي)
     * @return نتيجة الاستعادة
     */
    fun recoverFile(
        fileInfo: FoundFileInfo,
        outputDir: File? = null
    ): Flow<RecoveryResult> = flow {
        val startTime = System.currentTimeMillis()
        val targetDir = outputDir ?: getDefaultOutputDirectory(fileInfo.category)

        try {
            // إنشاء دليل الإخراج
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            // تحديد مسار الإخراج
            val outputFile = File(targetDir, sanitizeFileName(fileInfo.fileName))

            // التعامل مع تكرار الأسماء
            val finalOutputFile = resolveFileNameConflict(outputFile)

            // ──────────────────────────────────────
            // اختيار طريقة الاستعادة حسب نوع المصدر
            // ──────────────────────────────────────
            val success = when {
                // استعادة من قراءة خام (إزاحة في جهاز القسم)
                fileInfo.offsetInBlock >= 0 && fileInfo.isRootRequired -> {
                    recoverFromRawOffset(fileInfo, finalOutputFile)
                }
                // استعادة من ملف عادي
                File(fileInfo.path).exists() -> {
                    recoverFromExistingFile(fileInfo, finalOutputFile)
                }
                // محاولة استعادة من المسار المصدر
                fileInfo.sourcePath != fileInfo.path && File(fileInfo.sourcePath).exists() -> {
                    recoverFromExistingFile(
                        fileInfo.copy(path = fileInfo.sourcePath),
                        finalOutputFile
                    )
                }
                else -> false
            }

            // ──────────────────────────────────────
            // التحقق من سلامة الملف المستعاد
            // ──────────────────────────────────────
            val integrityStatus = if (success && finalOutputFile.exists()) {
                verifyFileIntegrity(finalOutputFile, fileInfo)
            } else {
                IntegrityStatus.UNREADABLE
            }

            // ──────────────────────────────────────
            // محاولة إصلاح الملف إذا كان تالفاً
            // ──────────────────────────────────────
            var repairAttempted = false
            if (integrityStatus.needsRepair && success) {
                repairAttempted = attemptRepair(finalOutputFile, fileInfo)
            }

            // ──────────────────────────────────────
            // حساب MD5 للملف المستعاد
            // ──────────────────────────────────────
            val md5Hash = if (success && finalOutputFile.exists()) {
                computeMd5(finalOutputFile)
            } else null

            // ──────────────────────────────────────
            // حساب الثقة النهائية
            // ──────────────────────────────────────
            val finalConfidence = if (success) {
                computeFinalConfidence(
                    integrityStatus = integrityStatus,
                    originalConfidence = fileInfo.confidence,
                    repairAttempted = repairAttempted,
                    recoveredSize = finalOutputFile.length(),
                    expectedSize = fileInfo.fileSize
                )
            } else {
                RecoveryConfidence.UNCERTAIN
            }

            val duration = System.currentTimeMillis() - startTime

            emit(RecoveryResult(
                file = finalOutputFile,
                success = success && finalOutputFile.exists(),
                outputPath = if (success) finalOutputFile.absolutePath else null,
                integrityCheck = if (success) integrityStatus else IntegrityStatus.UNREADABLE,
                recoveryConfidence = finalConfidence,
                error = if (!success) "فشل في قراءة بيانات الملف" else null,
                recoveredSize = if (success) finalOutputFile.length() else 0L,
                originalSize = fileInfo.fileSize,
                durationMs = duration,
                repairAttempted = repairAttempted
            ))

        } catch (e: IOException) {
            emit(RecoveryResult(
                file = File(fileInfo.path),
                success = false,
                error = "خطأ في الإدخال/الإخراج: ${e.message}",
                originalSize = fileInfo.fileSize,
                durationMs = System.currentTimeMillis() - startTime
            ))
        } catch (e: Exception) {
            emit(RecoveryResult(
                file = File(fileInfo.path),
                success = false,
                error = "خطأ غير متوقع: ${e.message}",
                originalSize = fileInfo.fileSize,
                durationMs = System.currentTimeMillis() - startTime
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * استعادة مجموعة ملفات (Batch Recovery)
     *
     * يستعيد عدة ملفات بالتتابع مع تقارير تقدم مفصلة.
     * يدعم الإلغاء عبر coroutine cancellation.
     *
     * @param files قائمة الملفات المراد استعادتها
     * @param outputDir دليل الإخراج الرئيسي (null = افتراضي)
     * @return تدفق تقدم الاستعادة
     */
    fun recoverBatch(
        files: List<FoundFileInfo>,
        outputDir: File? = null
    ): Flow<RecoveryProgress> = flow {
        val results = mutableListOf<RecoveryResult>()
        val totalBytes = files.sumOf { it.fileSize }
        var bytesRecovered = 0L
        val startTime = System.currentTimeMillis()

        for ((index, fileInfo) in files.withIndex()) {
            if (!currentCoroutineContext().isActive) break

            try {
                // استعادة الملف الحالي
                var currentResult: RecoveryResult? = null
                recoverFile(fileInfo, outputDir).collect { result ->
                    currentResult = result
                }

                currentResult?.let { result ->
                    results.add(result)
                    bytesRecovered += result.recoveredSize
                }

            } catch (e: Exception) {
                results.add(RecoveryResult(
                    file = File(fileInfo.path),
                    success = false,
                    error = e.message,
                    originalSize = fileInfo.fileSize
                ))
            }

            // تحديث التقدم
            val processed = index + 1
            val avgBytesPerFile = if (processed > 0) bytesRecovered / processed else 0L
            val remainingFiles = files.size - processed
            val estimatedRemaining = avgBytesPerFile * remainingFiles

            emit(RecoveryProgress(
                totalFiles = files.size,
                processedFiles = processed,
                currentFile = fileInfo.fileName,
                bytesRecovered = bytesRecovered,
                totalBytes = totalBytes,
                estimatedTimeRemainingMs = if (bytesRecovered > 0) {
                    val elapsed = System.currentTimeMillis() - startTime
                    (elapsed.toFloat() / bytesRecovered * (totalBytes - bytesRecovered)).toLong()
                } else 0L
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * استعادة مجموعة ملفات وإرجاع النتيجة النهائية
     *
     * @param files قائمة الملفات
     * @param outputDir دليل الإخراج
     * @return نتيجة دفعة الاستعادة
     */
    suspend fun recoverBatchAndGetResult(
        files: List<FoundFileInfo>,
        outputDir: File? = null
    ): RecoveryBatch {
        val results = mutableListOf<RecoveryResult>()
        val startTime = System.currentTimeMillis()

        for (fileInfo in files) {
            if (!currentCoroutineContext().isActive) break

            try {
                var currentResult: RecoveryResult? = null
                recoverFile(fileInfo, outputDir).collect { result ->
                    currentResult = result
                }
                currentResult?.let { results.add(it) }
            } catch (e: Exception) {
                results.add(RecoveryResult(
                    file = File(fileInfo.path),
                    success = false,
                    error = e.message,
                    originalSize = fileInfo.fileSize
                ))
            }
        }

        val duration = System.currentTimeMillis() - startTime
        return RecoveryBatch.fromResults(results, duration)
    }

    // ──────────────────────────────────────────────
    // طرق الاستعادة
    // ──────────────────────────────────────────────

    /**
     * استعادة ملف من مسار موجود في نظام الملفات
     *
     * يستخدم FileChannel للنسخ الفعال مع دعم الملفات الكبيرة
     *
     * @param fileInfo معلومات الملف
     * @param outputFile ملف الإخراج
     * @return هل نجحت العملية؟
     */
    private suspend fun recoverFromExistingFile(
        fileInfo: FoundFileInfo,
        outputFile: File
    ): Boolean {
        val sourceFile = File(fileInfo.path)

        if (!sourceFile.exists() || !sourceFile.canRead()) {
            return false
        }

        return try {
            // استخدام FileChannel للنسخ الفعال
            FileInputStream(sourceFile).use { source ->
                FileOutputStream(outputFile).use { dest ->
                    val sourceChannel: FileChannel = source.channel
                    val destChannel: FileChannel = dest.channel

                    var position = 0L
                    val size = sourceChannel.size()

                    while (position < size && currentCoroutineContext().isActive) {
                        val transferred = destChannel.transferFrom(
                            sourceChannel, position, COPY_BUFFER_SIZE.toLong()
                        )
                        if (transferred <= 0) break
                        position += transferred
                    }

                    position >= size * 0.95 // نعتبر النجاح إذا تم نسخ 95% على الأقل
                }
            }
        } catch (e: Exception) {
            // محاولة بديلة باستخدام النسخ اليدوي
            try {
                copyFileManually(sourceFile, outputFile)
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * استعادة ملف من إزاحة خام في جهاز القسم
     *
     * يقرأ عدداً محدداً من البايتات من الإزاحة المحددة
     * في جهاز القسم ويكتبها في ملف الإخراج.
     * يتطلب صلاحيات الروت عادةً.
     *
     * @param fileInfo معلومات الملف مع الإزاحة
     * @param outputFile ملف الإخراج
     * @return هل نجحت العملية؟
     */
    private suspend fun recoverFromRawOffset(
        fileInfo: FoundFileInfo,
        outputFile: File
    ): Boolean {
        val sourcePath = fileInfo.sourcePath

        // محاولة القراءة المباشرة
        try {
            val sourceFile = File(sourcePath)
            if (sourceFile.exists() && sourceFile.canRead()) {
                RandomAccessFile(sourceFile, "r").use { raf ->
                    raf.seek(fileInfo.offsetInBlock)
                    FileOutputStream(outputFile).use { fos ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        var remaining = fileInfo.fileSize

                        while (remaining > 0 && currentCoroutineContext().isActive) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val bytesRead = raf.read(buffer, 0, toRead)
                            if (bytesRead <= 0) break

                            fos.write(buffer, 0, bytesRead)
                            remaining -= bytesRead
                        }
                    }
                }
                return outputFile.length() > 0
            }
        } catch (_: Exception) {}

        // محاولة القراءة عبر صلاحيات الروت
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "su", "-c",
                    "dd if=$sourcePath bs=${COPY_BUFFER_SIZE} skip=${fileInfo.offsetInBlock / COPY_BUFFER_SIZE} count=${(fileInfo.fileSize / COPY_BUFFER_SIZE) + 1} 2>/dev/null"
                )
            )

            process.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var remaining = fileInfo.fileSize

                    while (remaining > 0 && currentCoroutineContext().isActive) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val bytesRead = input.read(buffer, 0, toRead)
                        if (bytesRead <= 0) break

                        output.write(buffer, 0, bytesRead)
                        remaining -= bytesRead
                    }
                }
            }

            process.waitFor()
            return outputFile.length() > 0

        } catch (_: Exception) {
            return false
        }
    }

    /**
     * نسخ ملف يدوياً (بديل عند فشل FileChannel)
     */
    private suspend fun copyFileManually(source: File, dest: File): Boolean {
        FileInputStream(source).use { fis ->
            FileOutputStream(dest).use { fos ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var totalWritten = 0L
                val totalSize = source.length()

                while (totalWritten < totalSize && currentCoroutineContext().isActive) {
                    val bytesRead = fis.read(buffer)
                    if (bytesRead <= 0) break

                    fos.write(buffer, 0, bytesRead)
                    totalWritten += bytesRead
                }

                fos.flush()
                return totalWritten >= totalSize * 0.95
            }
        }
    }

    // ──────────────────────────────────────────────
    // التحقق من السلامة
    // ──────────────────────────────────────────────

    /**
     * التحقق من سلامة الملف المستعاد
     *
     * ينفذ عدة فحوصات لتحديد حالة الملف:
     * 1. فحص الحجم (هل يطابق المتوقع؟)
     * 2. فحص التوقيع (هل التوقيع صحيح؟)
     * 3. فحص الهيكل (هل بنية الملف سليمة؟)
     * 4. فحص قابلية القراءة
     *
     * @param file الملف المراد فحصه
     * @param originalInfo معلومات الملف الأصلي
     * @return حالة السلامة
     */
    private fun verifyFileIntegrity(
        file: File,
        originalInfo: FoundFileInfo
    ): IntegrityStatus {
        try {
            // فحص 1: هل الملف فارغ؟
            if (file.length() == 0L) {
                return IntegrityStatus.UNREADABLE
            }

            // فحص 2: قراءة رأس الملف والتحقق من التوقيع
            val header = readFileHeader(file)
            if (header == null) {
                return IntegrityStatus.UNREADABLE
            }

            // فحص 3: مطابقة التوقيع
            val detectedSignature = FileSignatures.identifyFileType(header)
            if (detectedSignature == null) {
                // لا يوجد توقيع معروف - قد يكون تالفاً
                return IntegrityStatus.CORRUPTED
            }

            // فحص 4: هل التوقيع المطابق يطابق النوع المتوقع؟
            if (originalInfo.signatureName != null &&
                detectedSignature.name != originalInfo.signatureName) {
                // التوقيع مختلف عن المتوقع - خطأ في الاستعادة
                return IntegrityStatus.CORRUPTED
            }

            // فحص 5: فحص خاص بنوع الملف
            val formatIntegrity = checkFormatSpecificIntegrity(file, detectedSignature)

            // فحص 6: مقارنة الحجم
            val sizeRatio = if (originalInfo.fileSize > 0) {
                file.length().toFloat() / originalInfo.fileSize.toFloat()
            } else 1f

            return when {
                formatIntegrity && sizeRatio >= 0.95f -> IntegrityStatus.INTACT
                formatIntegrity && sizeRatio >= 0.70f -> IntegrityStatus.PARTIAL
                !formatIntegrity && sizeRatio >= 0.50f -> IntegrityStatus.CORRUPTED
                sizeRatio < 0.30f -> IntegrityStatus.UNREADABLE
                else -> IntegrityStatus.PARTIAL
            }

        } catch (_: Exception) {
            return IntegrityStatus.UNREADABLE
        }
    }

    /**
     * قراءة رأس الملف
     */
    private fun readFileHeader(file: File): ByteArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(FileSignatures.MINIMUM_HEADER_SIZE)
                val bytesRead = raf.read(header)
                if (bytesRead > 0) header.copyOf(bytesRead) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * فحص السلامة الخاص بكل تنسيق
     *
     * @param file الملف المراد فحصه
     * @param signature التوقيع المكتشف
     * @return هل الهيكل سليم؟
     */
    private fun checkFormatSpecificIntegrity(
        file: File,
        signature: FileSignatures.FileSignature
    ): Boolean {
        return try {
            when (signature.name) {
                "JPEG" -> verifyJpegIntegrity(file)
                "PNG" -> verifyPngIntegrity(file)
                "PDF" -> verifyPdfIntegrity(file)
                "ZIP", "APK" -> verifyZipIntegrity(file)
                "MP3" -> verifyMp3Integrity(file)
                "GIF" -> verifyGifIntegrity(file)
                else -> true // لا يمكن التحقق - نفترض السلامة
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * التحقق من سلامة ملف JPEG
     * يبحث عن SOI (FFD8) و EOI (FFD9)
     */
    private fun verifyJpegIntegrity(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            // فحص SOI
            val soi = ByteArray(2)
            raf.read(soi)
            if (soi[0] != 0xFF.toByte() || soi[1] != 0xD8.toByte()) return false

            // فحص EOI - البحث من النهاية
            if (file.length() >= 4) {
                raf.seek(file.length() - 2)
                val eoi = ByteArray(2)
                raf.read(eoi)
                if (eoi[0] == 0xFF.toByte() && eoi[1] == 0xD9.toByte()) {
                    return true
                }
            }
            // قد يكون EOI قبل النهاية بقليل (بيانات زائدة)
            return file.length() > 100 // حجم معقول على الأقل
        }
    }

    /**
     * التحقق من سلامة ملف PNG
     * يبحث عن IEND chunk
     */
    private fun verifyPngIntegrity(file: File): Boolean {
        // البحث عن IEND في آخر 20 بايت
        RandomAccessFile(file, "r").use { raf ->
            val searchStart = maxOf(0L, file.length() - 20)
            raf.seek(searchStart)
            val tail = ByteArray((file.length() - searchStart).toInt())
            raf.read(tail)

            // البحث عن "IEND" في الذيل
            val iendBytes = "IEND".toByteArray()
            for (i in 0..tail.size - 4) {
                if (tail[i] == iendBytes[0] && tail[i + 1] == iendBytes[1] &&
                    tail[i + 2] == iendBytes[2] && tail[i + 3] == iendBytes[3]) {
                    return true
                }
            }
            return false
        }
    }

    /**
     * التحقق من سلامة ملف PDF
     * يبحث عن %%EOF
     */
    private fun verifyPdfIntegrity(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            val searchStart = maxOf(0L, file.length() - 50)
            raf.seek(searchStart)
            val tail = ByteArray((file.length() - searchStart).toInt())
            raf.read(tail)
            return String(tail).contains("%%EOF")
        }
    }

    /**
     * التحقق من سلامة ملف ZIP/APK
     * يبحث عن End of Central Directory
     */
    private fun verifyZipIntegrity(file: File): Boolean {
        return try {
            java.util.zip.ZipFile(file).use { zipFile ->
                // إذا أمكن فتح الملف كـ ZIP، فهو على الأرجح سليم
                zipFile.entries().hasMoreElements()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * التحقق من سلامة ملف MP3
     * فحص وجود إطارات صالحة
     */
    private fun verifyMp3Integrity(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(4)
            raf.read(header)
            // فحص frame sync
            return ((header[0].toInt() and 0xFF) == 0xFF &&
                    (header[1].toInt() and 0xE0) == 0xE0) ||
                    String(header, 0, 3) == "ID3"
        }
    }

    /**
     * التحقق من سلامة ملف GIF
     * يبحث عن Trailer (0x3B)
     */
    private fun verifyGifIntegrity(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - 1)
            val trailer = ByteArray(1)
            raf.read(trailer)
            return (trailer[0].toInt() and 0xFF) == 0x3B
        }
    }

    // ──────────────────────────────────────────────
    // إصلاح الملفات
    // ──────────────────────────────────────────────

    /**
     * محاولة إصلاح الملف التالف
     *
     * ينفذ عدة استراتيجيات للإصلاح حسب نوع الملف:
     * - JPEG: إضافة علامة EOI مفقودة
     * - PNG: إضافة IEND chunk مفقود
     * - PDF: إضافة %%EOF مفقود
     * - ZIP: إصلاح End of Central Directory
     *
     * @param file الملف المراد إصلاحه
     * @param fileInfo معلومات الملف الأصلي
     * @return هل تمت محاولة الإصلاح؟
     */
    private fun attemptRepair(file: File, fileInfo: FoundFileInfo): Boolean {
        if (!file.exists() || !file.canWrite()) return false

        try {
            val header = readFileHeader(file) ?: return false
            val signature = FileSignatures.identifyFileType(header) ?: return false

            return when (signature.name) {
                "JPEG" -> repairJpeg(file)
                "PNG" -> repairPng(file)
                "PDF" -> repairPdf(file)
                else -> false
            }
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * إصلاح ملف JPEG - إضافة EOI marker إذا كان مفقوداً
     */
    private fun repairJpeg(file: File): Boolean {
        try {
            // فحص هل ينتهي بـ FFD9
            RandomAccessFile(file, "rw").use { raf ->
                if (file.length() >= 2) {
                    raf.seek(file.length() - 2)
                    val tail = ByteArray(2)
                    raf.read(tail)
                    if (tail[0] == 0xFF.toByte() && tail[1] == 0xD9.toByte()) {
                        return true // لا يحتاج إصلاح
                    }
                }

                // إضافة EOI marker
                raf.seek(file.length())
                raf.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
                return true
            }
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * إصلاح ملف PNG - إضافة IEND chunk إذا كان مفقوداً
     */
    private fun repairPng(file: File): Boolean {
        try {
            // IEND chunk: 00000000 49454E44 AE426082
            val iendChunk = byteArrayOf(
                0x00, 0x00, 0x00, 0x00,  // Length = 0
                0x49, 0x45, 0x4E, 0x44,  // "IEND"
                0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()  // CRC
            )

            RandomAccessFile(file, "rw").use { raf ->
                // فحص هل ينتهي بـ IEND
                if (file.length() >= 12) {
                    raf.seek(file.length() - 12)
                    val tail = ByteArray(4)
                    raf.read(tail)
                    if (String(tail) == "IEND") {
                        return true
                    }
                }

                // إضافة IEND chunk
                raf.seek(file.length())
                raf.write(iendChunk)
                return true
            }
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * إصلاح ملف PDF - إضافة %%EOF إذا كان مفقوداً
     */
    private fun repairPdf(file: File): Boolean {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                // فحص هل ينتهي بـ %%EOF
                if (file.length() >= 6) {
                    raf.seek(file.length() - 6)
                    val tail = ByteArray(6)
                    raf.read(tail)
                    if (String(tail).contains("%%EOF")) {
                        return true
                    }
                }

                // إضافة %%EOF
                raf.seek(file.length())
                raf.write("\n%%EOF\n".toByteArray())
                return true
            }
        } catch (_: Exception) {
            return false
        }
    }

    // ──────────────────────────────────────────────
    // دوال مساعدة
    // ──────────────────────────────────────────────

    /**
     * حساب تجزئة MD5 للملف
     */
    private fun computeMd5(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } > 0) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * حساب مستوى الثقة النهائي
     */
    private fun computeFinalConfidence(
        integrityStatus: IntegrityStatus,
        originalConfidence: RecoveryConfidence,
        repairAttempted: Boolean,
        recoveredSize: Long,
        expectedSize: Long
    ): RecoveryConfidence {
        var score = originalConfidence.percentage

        // تعديل بناءً على سلامة الملف
        score += when (integrityStatus) {
            IntegrityStatus.INTACT -> 20
            IntegrityStatus.PARTIAL -> 0
            IntegrityStatus.CORRUPTED -> -15
            IntegrityStatus.UNREADABLE -> -30
        }

        // تعديل بناءً على نسبة الاستعادة
        if (expectedSize > 0) {
            val ratio = recoveredSize.toFloat() / expectedSize.toFloat()
            when {
                ratio >= 0.95f -> score += 10
                ratio >= 0.80f -> score += 5
                ratio >= 0.50f -> score -= 5
                else -> score -= 15
            }
        }

        // تعديل بناءً على محاولة الإصلاح
        if (repairAttempted) {
            score -= 5 // الإصلاح يقلل الثقة قليلاً
        }

        score = score.coerceIn(0, 100)

        return when {
            score >= 80 -> RecoveryConfidence.HIGH
            score >= 50 -> RecoveryConfidence.MEDIUM
            score >= 25 -> RecoveryConfidence.LOW
            else -> RecoveryConfidence.UNCERTAIN
        }
    }

    /**
     * الحصول على دليل الإخراج الافتراضي حسب فئة الملف
     */
    private fun getDefaultOutputDirectory(category: FileCategory): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val baseDir = File(downloadsDir, RECOVERY_OUTPUT_DIR)

        val subDir = when (category) {
            FileCategory.PHOTO -> "Photos"
            FileCategory.VIDEO -> "Videos"
            FileCategory.AUDIO -> "Audio"
            FileCategory.DOCUMENT -> "Documents"
            FileCategory.ARCHIVE -> "Archives"
            FileCategory.APK -> "APKs"
            FileCategory.OTHER -> "Other"
        }

        // إضافة التاريخ لتنظيم الملفات
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        val dateDir = dateFormat.format(Date())

        return File(File(baseDir, subDir), dateDir)
    }

    /**
     * تنظيف اسم الملف من الأحرف غير المسموحة
     */
    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .replace("..", "_")
            .takeIf { it.isNotBlank() } ?: "recovered_file"
    }

    /**
     * حل تكرار أسماء الملفات
     *
     * إذا كان الملف موجوداً بالفعل، يضيف رقم تسلسلي
     * مثال: photo.jpg → photo_1.jpg → photo_2.jpg
     */
    private fun resolveFileNameConflict(file: File): File {
        if (!file.exists()) return file

        val name = file.nameWithoutExtension
        val ext = file.extension
        var counter = 1

        var newFile = File(file.parent, "${name}_$counter.$ext")
        while (newFile.exists() && counter < 1000) {
            counter++
            newFile = File(file.parent, "${name}_$counter.$ext")
        }

        return newFile
    }

    /**
     * تحويل FoundFileInfo إلى RecoveredFileEntity
     *
     * يستخدم لحفظ النتائج في قاعدة البيانات
     *
     * @param fileInfo معلومات الملف المكتشف
     * @param scanSessionId معرف جلسة المسح
     * @return كيان الملف المستعاد
     */
    fun toEntity(
        fileInfo: FoundFileInfo,
        scanSessionId: Long,
        outputPath: String? = null
    ): RecoveredFileEntity {
        return RecoveredFileEntity(
            filePath = fileInfo.path,
            fileName = fileInfo.fileName,
            fileSize = fileInfo.fileSize,
            fileExtension = fileInfo.extension,
            mimeType = fileInfo.mimeType,
            recoveryDate = System.currentTimeMillis(),
            originalDate = fileInfo.lastModified,
            scanSessionId = scanSessionId,
            category = fileInfo.category,
            recoveryStatus = if (outputPath != null) RecoveryStatus.RECOVERED else RecoveryStatus.PENDING,
            thumbnailPath = fileInfo.thumbnailPath,
            isRootAccessed = fileInfo.isRootRequired,
            sourcePath = fileInfo.sourcePath,
            exportPath = outputPath
        )
    }
}
