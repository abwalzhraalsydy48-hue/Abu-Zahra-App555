@file:OptIn(kotlin.ExperimentalStdlibApi::class)

package com.ultimaterecovery.pro.engine.scanner

import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.engine.recovery.FoundFileInfo
import com.ultimaterecovery.pro.engine.recovery.RecoveryConfidence
import com.ultimaterecovery.pro.engine.signatures.FileSignatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ماسح التوقيعات - ينفذ عملية File Carving باستخدام التوقيعات
 *
 * File Carving هي تقنية استعادة الملفات التي تعتمد على البحث عن
 * توقيعات الملفات (Magic Numbers) في البيانات الخام بدون الحاجة
 * إلى نظام ملفات. هذه التقنية فعالة جداً في استعادة الملفات
 * التي تم حذف إدخالاتها من جدول نظام الملفات.
 *
 * كيف يعمل ماسح التوقيعات:
 * ┌──────────────────────────────────────────────────────┐
 * │ 1. قراءة البيانات الخام من التخزين                    │
 * │ 2. البحث عن توقيعات بداية الملفات                     │
 * │ 3. عند العثور على توقيع:                              │
 * │    أ. قراءة رأس الملف كاملاً                           │
 * │    ب. استخراج معلومات الحجم إن أمكن                    │
 * │    ج. البحث عن علامة نهاية الملف                       │
 * │    د. استخراج البيانات بين البداية والنهاية             │
 * │ 4. تقدير ثقة الاستعادة                                │
 * │ 5. محاولة إعادة تجميع الملفات المجزأة                 │
 * └──────────────────────────────────────────────────────┘
 *
 * يتميز هذا الماسح بالقدرة على التعامل مع:
 * - الملفات المجزأة (Fragmented Files)
 * - الملفات بدون إدخالات في نظام الملفات
 * - الملفات في المساحة غير المخصصة (Unallocated Space)
 * - الملفات ذات الهياكل المعروفة (JPEG, PNG, PDF...)
 */
@Singleton
class SignatureScanner @Inject constructor() {

    companion object {
        /** حجم الكتلة للقراءة المتسلسلة (64 كيلوبايت) */
        private const val SCAN_BLOCK_SIZE = 64 * 1024

        /** حجم المخزن المؤقت للقراءة (2 ميجابايت) */
        private const val READ_BUFFER_SIZE = 2 * 1024 * 1024

        /** الحد الأدنى لحجم الملف المستعاد */
        private const val MIN_CARVED_FILE_SIZE = 128L

        /** الحد الأقصى لحجم ملف واحد مستعاد */
        private const val MAX_CARVED_FILE_SIZE = 4L * 1024 * 1024 * 1024

        /** المسافة القصوى للبحث عن علامة نهاية (200 ميجابايت) */
        private const val MAX_END_MARKER_SEARCH = 200L * 1024 * 1024

        /** عدد الكتل بين تحديثات التقدم */
        private const val PROGRESS_UPDATE_INTERVAL = 128

        /** حجم نافذة البحث المتداخلة (لتجنب تفويت التوقيعات على الحدود) */
        private const val OVERLAP_SIZE = 1024
    }

    /**
     * مسح بالتوقيعات في المسارات المحددة
     *
     * يقرأ الملفات في المسارات المحددة ويبحث عن توقيعات
     * الملفات في البيانات الخام. مناسب لملفات القرص والنسخ الاحتياطية.
     *
     * @param paths قائمة المسارات المراد مسحها
     * @return تدفق حالة المسح
     */
    suspend fun scanBySignatures(paths: List<String>): Flow<ScanState> = flow {
        val startTime = System.currentTimeMillis()
        val foundFiles = mutableListOf<FoundFileInfo>()
        var totalBytesScanned = 0L
        var totalBytesToScan = 0L

        try {
            // حساب الحجم الإجمالي للمسح
            for (path in paths) {
                val file = File(path)
                if (file.exists()) {
                    totalBytesToScan += file.length()
                }
            }

            if (totalBytesToScan == 0L) {
                emit(ScanState.Completed(
                    results = emptyList(),
                    totalFiles = 0,
                    totalSize = 0L,
                    durationMs = System.currentTimeMillis() - startTime,
                    scanType = ScanType.SIGNATURE
                ))
                return@flow
            }

            emit(ScanState.Scanning(
                progress = 0f,
                currentPath = "بدء مسح التوقيعات...",
                filesFound = 0,
                bytesScanned = 0L,
                totalBytes = totalBytesToScan,
                scanType = ScanType.SIGNATURE
            ))

            // مسح كل مسار على حدة
            for (path in paths) {
                if (!currentCoroutineContext().isActive) break

                val file = File(path)
                if (!file.exists() || !file.canRead()) continue

                emit(ScanState.Scanning(
                    progress = if (totalBytesToScan > 0) totalBytesScanned.toFloat() / totalBytesToScan else 0f,
                    currentPath = path,
                    filesFound = foundFiles.size,
                    bytesScanned = totalBytesScanned,
                    totalBytes = totalBytesToScan,
                    scanType = ScanType.SIGNATURE
                ))

                scanFileForSignatures(file, foundFiles) { bytesRead ->
                    totalBytesScanned += bytesRead
                }

                // تحديث التقدم بعد كل ملف
                emit(ScanState.Scanning(
                    progress = if (totalBytesToScan > 0) totalBytesScanned.toFloat() / totalBytesToScan else 0f,
                    currentPath = path,
                    filesFound = foundFiles.size,
                    bytesScanned = totalBytesScanned,
                    totalBytes = totalBytesToScan,
                    scanType = ScanType.SIGNATURE
                ))
            }

            // إزالة المكررات والإرسال النهائي
            val uniqueFiles = foundFiles
                .distinctBy { "${it.sourcePath}:${it.offsetInBlock}" }
                .sortedByDescending { it.confidence.percentage }

            val elapsed = System.currentTimeMillis() - startTime
            emit(ScanState.Completed(
                results = uniqueFiles,
                totalFiles = uniqueFiles.size,
                totalSize = uniqueFiles.sumOf { it.fileSize },
                durationMs = elapsed,
                scanType = ScanType.SIGNATURE
            ))

        } catch (e: Exception) {
            emit(ScanState.Failed(
                error = e.message ?: "خطأ في مسح التوقيعات",
                scanType = ScanType.SIGNATURE,
                partialResults = foundFiles
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * مسح قسم خام بالكامل
     *
     * يفتح جهاز القسم مباشرة ويقرأ بياناته الخام
     * بايت ببايت بحثاً عن توقيعات الملفات.
     * يتطلب صلاحيات الروت عادةً.
     *
     * @param partitionPath مسار جهاز القسم (مثل /dev/block/sda1)
     * @return تدفق حالة المسح
     */
    fun scanRawPartition(partitionPath: String): Flow<ScanState> = flow {
        val startTime = System.currentTimeMillis()
        val foundFiles = mutableListOf<FoundFileInfo>()
        var totalBytesScanned = 0L

        try {
            val partitionFile = File(partitionPath)

            // محاولة الحصول على حجم القسم
            val partitionSize = getPartitionSize(partitionPath)

            emit(ScanState.Scanning(
                progress = 0f,
                currentPath = partitionPath,
                filesFound = 0,
                bytesScanned = 0L,
                totalBytes = partitionSize,
                scanType = ScanType.RAW
            ))

            // محاولة القراءة المباشرة
            val canReadDirectly = partitionFile.canRead()

            if (canReadDirectly) {
                scanFileForSignatures(partitionFile, foundFiles) { bytesRead ->
                    totalBytesScanned += bytesRead
                }
            } else {
                // محاولة القراءة عبر صلاحيات الروت
                scanPartitionWithRoot(partitionPath, foundFiles, partitionSize) { bytesRead ->
                    totalBytesScanned += bytesRead

                    // تحديث التقدم
                    if (partitionSize > 0 && totalBytesScanned % (PROGRESS_UPDATE_INTERVAL * SCAN_BLOCK_SIZE) < SCAN_BLOCK_SIZE) {
                        val progress = (totalBytesScanned.toFloat() / partitionSize).coerceIn(0f, 1f)
                        emit(ScanState.Scanning(
                            progress = progress,
                            currentPath = partitionPath,
                            filesFound = foundFiles.size,
                            bytesScanned = totalBytesScanned,
                            totalBytes = partitionSize,
                            scanType = ScanType.RAW
                        ))
                    }
                }
            }

            val uniqueFiles = foundFiles
                .distinctBy { "${it.sourcePath}:${it.offsetInBlock}" }
                .sortedByDescending { it.confidence.percentage }

            val elapsed = System.currentTimeMillis() - startTime
            emit(ScanState.Completed(
                results = uniqueFiles,
                totalFiles = uniqueFiles.size,
                totalSize = uniqueFiles.sumOf { it.fileSize },
                durationMs = elapsed,
                scanType = ScanType.RAW
            ))

        } catch (e: Exception) {
            emit(ScanState.Failed(
                error = e.message ?: "خطأ في مسح القسم الخام: $partitionPath",
                scanType = ScanType.RAW,
                partialResults = foundFiles
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * مسح ملف واحد بحثاً عن توقيعات
     *
     * هذه هي الدالة الأساسية للبحث عن التوقيعات.
     * تقرأ الملف في كتل كبيرة مع تداخل لضمان عدم تفويت
     * أي توقيع على حدود الكتل.
     *
     * @param file الملف المراد مسحه
     * @param foundFiles قائمة النتائج المراد الإضافة إليها
     * @param onProgress دالة استدعاء لتتبع التقدم
     */
    private suspend fun scanFileForSignatures(
        file: File,
        foundFiles: MutableList<FoundFileInfo>,
        onProgress: (Long) -> Unit
    ) {
        val fileSize = file.length()
        if (fileSize < FileSignatures.MINIMUM_HEADER_SIZE) return

        try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(READ_BUFFER_SIZE + OVERLAP_SIZE)
                var position = 0L
                var blockCount = 0
                val processedOffsets = mutableSetOf<Long>() // لتجنب تكرار معالجة نفس الإزاحة

                while (position < fileSize && currentCoroutineContext().isActive) {
                    // حساب عدد البايتات المطلوب قراءتها
                    val remaining = fileSize - position
                    val bytesToRead = minOf(buffer.size.toLong(), remaining).toInt()

                    // قراءة الكتلة مع التداخل
                    raf.seek(position)
                    val bytesRead = raf.read(buffer, 0, bytesToRead)
                    if (bytesRead <= 0) break

                    onProgress(bytesRead.toLong())

                    // البحث عن التوقيعات في الكتلة الحالية
                    for (offset in 0 until bytesRead - FileSignatures.MINIMUM_HEADER_SIZE) {
                        if (!currentCoroutineContext().isActive) break

                        val absoluteOffset = position + offset
                        if (absoluteOffset in processedOffsets) continue

                        // نسخ الرأس المحتمل
                        val headerSize = minOf(
                            FileSignatures.MINIMUM_HEADER_SIZE,
                            bytesRead - offset
                        )
                        val header = buffer.copyOfRange(offset, offset + headerSize)

                        // مطابقة التوقيعات
                        val signature = FileSignatures.identifyFileType(header)
                        if (signature != null) {
                            // عثرنا على توقيع! محاولة استخراج الملف
                            val carvedFile = carveFile(
                                raf = raf,
                                startOffset = absoluteOffset,
                                signature = signature,
                                dataSourceSize = fileSize,
                                sourcePath = file.absolutePath
                            )

                            if (carvedFile != null && carvedFile.fileSize >= MIN_CARVED_FILE_SIZE) {
                                foundFiles.add(carvedFile)
                                // تسجيل الإزاحة كمعالجة
                                processedOffsets.add(absoluteOffset)
                                // تخطي مساحة الملف المستخرج
                                position = maxOf(position, absoluteOffset + carvedFile.fileSize)
                            }
                        }
                    }

                    // التقدم مع تداخل لضمان عدم تفويت التوقيعات
                    position += bytesRead - OVERLAP_SIZE
                    if (position < 0) position = 0

                    blockCount++
                }
            }
        } catch (_: Exception) {
            // خطأ في قراءة الملف - نتابع
        }
    }

    /**
     * استخراج ملف من البيانات الخام (File Carving)
     *
     * عند العثور على توقيع بداية ملف، تحاول هذه الدالة استخراج
     * الملف الكامل باتباع هذه الخطوات:
     *
     * 1. تحديد حجم الملف من الرأس (إن أمكن)
     * 2. البحث عن علامة نهاية الملف
     * 3. التحقق من صحة البيانات المستخرجة
     * 4. تقدير مستوى ثقة الاستعادة
     *
     * @param raf ملف الوصول العشوائي
     * @param startOffset إزاحة بداية الملف
     * @param signature التوقيع المطابق
     * @param dataSourceSize الحجم الإجمالي لمصدر البيانات
     * @param sourcePath مسار مصدر البيانات
     * @return معلومات الملف المستخرج أو null
     */
    private suspend fun carveFile(
        raf: RandomAccessFile,
        startOffset: Long,
        signature: FileSignatures.FileSignature,
        dataSourceSize: Long,
        sourcePath: String
    ): FoundFileInfo? {
        try {
            var carvedSize = -1L

            // ──────────────────────────────────────────
            // المرحلة 1: محاولة تحديد الحجم من الرأس
            // ──────────────────────────────────────────
            carvedSize = detectFileSizeFromHeader(raf, startOffset, signature)

            // ──────────────────────────────────────────
            // المرحلة 2: البحث عن علامة نهاية الملف
            // هذا أهم جزء في عملية Carving لأنه يحدد نهاية الملف
            // ──────────────────────────────────────────
            if (signature.endMarker != null) {
                val endMarkerOffset = searchForEndMarker(
                    raf, startOffset, signature.endMarker, dataSourceSize
                )
                if (endMarkerOffset > startOffset) {
                    val markerBasedSize = endMarkerOffset - startOffset + signature.endMarker.size
                    // إذا وجدنا علامة النهاية، نستخدم هذا الحجم (أدق)
                    carvedSize = markerBasedSize
                }
            }

            // ──────────────────────────────────────────
            // المرحلة 3: معالجة خاصة ببعض أنواع الملفات
            // ──────────────────────────────────────────
            if (carvedSize <= 0L) {
                carvedSize = formatSpecificCarving(raf, startOffset, signature, dataSourceSize)
            }

            // ──────────────────────────────────────────
            // المرحلة 4: استخدام تقدير إذا فشلت الطرق السابقة
            // ──────────────────────────────────────────
            if (carvedSize <= 0L) {
                carvedSize = estimateFileSizeFromSignature(signature)
            }

            // ──────────────────────────────────────────
            // المرحلة 5: التحقق من الحدود والقيود
            // ──────────────────────────────────────────
            carvedSize = carvedSize.coerceIn(MIN_CARVED_FILE_SIZE, MAX_CARVED_FILE_SIZE)
            val availableSize = dataSourceSize - startOffset
            if (carvedSize > availableSize) {
                carvedSize = availableSize
            }

            if (carvedSize < MIN_CARVED_FILE_SIZE) {
                return null
            }

            // ──────────────────────────────────────────
            // المرحلة 6: التحقق الأساسي من سلامة البيانات
            // ──────────────────────────────────────────
            val integrityScore = performBasicIntegrityCheck(
                raf, startOffset, carvedSize, signature
            )

            // ──────────────────────────────────────────
            // المرحلة 7: حساب مستوى الثقة
            // ──────────────────────────────────────────
            val confidence = computeConfidence(
                signature = signature,
                carvedSize = carvedSize,
                hasEndMarker = signature.endMarker != null,
                integrityScore = integrityScore,
                startOffset = startOffset
            )

            // ──────────────────────────────────────────
            // المرحلة 8: إنشاء نتيجة الملف المستخرج
            // ──────────────────────────────────────────
            val fileName = generateCarvedFileName(signature, startOffset)

            return FoundFileInfo(
                path = sourcePath,
                fileName = fileName,
                fileSize = carvedSize,
                extension = signature.extensions.first(),
                mimeType = signature.mimeType,
                category = signature.category,
                signatureName = signature.name,
                offsetInBlock = startOffset,
                confidence = confidence,
                isFragment = false, // سيتم تحديثه لاحقاً إذا لزم الأمر
                isRootRequired = sourcePath.startsWith("/dev/"),
                sourcePath = sourcePath,
                metadata = mapOf(
                    "carving_method" to "signature",
                    "offset" to startOffset.toString(),
                    "size" to carvedSize.toString(),
                    "integrity_score" to integrityScore.toString(),
                    "has_end_marker" to (signature.endMarker != null).toString(),
                    "confidence" to confidence.name
                )
            )

        } catch (_: Exception) {
            return null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // تحديد حجم الملف من الرأس
    // ═══════════════════════════════════════════════════════════════

    /**
     * محاولة تحديد حجم الملف من البيانات الوصفية في الرأس
     *
     * بعض تنسيقات الملفات تخزن حجمها في الرأس:
     * - RIFF containers (AVI, WAV, WEBP): 4 بايت لحجم RIFF في الإزاحة 4
     * - PNG: لا يخزن الحجم الكلي مباشرة
     * - MP4/MOV: حجم ftyp box في أول 4 بايت
     * - ZIP: لا يخزن الحجم الكلي مباشرة
     * - PDF: لا يخزن الحجم الكلي مباشرة
     * - GZIP: الحجم الأصلي في آخر 4 بايت (لكننا لا نعرف النهاية بعد)
     * - BMP: حجم الملف في الإزاحة 2 (4 بايت LE)
     * - FLV: حجم الرأس في الإزاحة 5 (4 بايت BE)
     *
     * @param raf ملف الوصول العشوائي
     * @param startOffset إزاحة بداية الملف
     * @param signature التوقيع
     * @return حجم الملف أو -1 إذا لم يمكن تحديده
     */
    private suspend fun detectFileSizeFromHeader(
        raf: RandomAccessFile,
        startOffset: Long,
        signature: FileSignatures.FileSignature
    ): Long {
        try {
            when (signature.name) {
                "BMP" -> {
                    raf.seek(startOffset + 2)
                    val sizeBytes = ByteArray(4)
                    raf.read(sizeBytes)
                    return ByteBuffer.wrap(sizeBytes)
                        .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                }

                "AVI", "WAV", "WEBP" -> {
                    // RIFF container: الحجم في الإزاحة 4 (4 بايت LE) + 8 (حجم رأس RIFF)
                    raf.seek(startOffset + 4)
                    val sizeBytes = ByteArray(4)
                    raf.read(sizeBytes)
                    val riffSize = ByteBuffer.wrap(sizeBytes)
                        .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    return riffSize + 8
                }

                "FLV" -> {
                    raf.seek(startOffset + 5)
                    val sizeBytes = ByteArray(4)
                    raf.read(sizeBytes)
                    val headerSize = ByteBuffer.wrap(sizeBytes)
                        .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    // FLV header size هو حجم الرأس فقط، لكنه يعطي مؤشراً
                    return headerSize.toLong()
                }

                "MP4" -> {
                    // ftyp box size في أول 4 بايت (Big Endian)
                    raf.seek(startOffset)
                    val sizeBytes = ByteArray(4)
                    raf.read(sizeBytes)
                    val ftypSize = ByteBuffer.wrap(sizeBytes)
                        .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    // هذا حجم ftyp فقط وليس الملف بالكامل
                    // لكن إذا كان صغيراً جداً، فهو مؤشر على أننا في البداية الصحيحة
                    if (ftypSize in 8..1024) {
                        // ftyp صالح - لكن لا يمكننا تحديد الحجم الكلي من هنا
                        return -1L
                    }
                }

                "GZ" -> {
                    // GZIP يخزن الحجم الأصلي في آخر 4 بايت من الملف
                    // لكننا لا نعرف نهاية الملف بعد
                    return -1L
                }

                "PNG" -> {
                    // PNG لا يخزن الحجم الكلي مباشرة
                    // لكن يمكننا تقديره من أول IHDR chunk
                    return -1L
                }
            }
        } catch (_: Exception) {}

        return -1L
    }

    // ═══════════════════════════════════════════════════════════════
    // البحث عن علامات نهاية الملفات
    // ═══════════════════════════════════════════════════════════════

    /**
     * البحث عن علامة نهاية الملف في البيانات الخام
     *
     * توقيعات النهاية المهمة:
     * - JPEG: FFD9 (End of Image)
     * - PNG: IEND chunk (00000000 49454E44 AE426082)
     * - PDF: %%EOF (2525454F46)
     * - ZIP: End of Central Directory (504B0506)
     *
     * البحث يتم في كتل كبيرة للكفاءة مع مراعاة
     * عدم تجاوز المسافة القصوى للبحث.
     *
     * @param raf ملف الوصول العشوائي
     * @param startOffset إزاحة بداية الملف
     * @param endMarker علامة النهاية
     * @param dataSourceSize الحجم الإجمالي للبيانات
     * @return إزاحة بداية علامة النهاية أو -1
     */
    private suspend fun searchForEndMarker(
        raf: RandomAccessFile,
        startOffset: Long,
        endMarker: ByteArray,
        dataSourceSize: Long
    ): Long {
        try {
            // لا نبحث قبل مسافة معقولة من البداية
            val searchStart = startOffset + endMarker.size + 16
            val searchEnd = minOf(
                startOffset + MAX_END_MARKER_SEARCH,
                dataSourceSize - endMarker.size
            )

            if (searchStart >= searchEnd) return -1L

            val searchBuffer = ByteArray(READ_BUFFER_SIZE)
            var currentPos = searchStart

            while (currentPos < searchEnd && currentCoroutineContext().isActive) {
                raf.seek(currentPos)
                val bytesRead = raf.read(searchBuffer)
                if (bytesRead <= 0) break

                // البحث عن علامة النهاية في المخزن المؤقت
                for (i in 0..bytesRead - endMarker.size) {
                    var found = true
                    for (j in endMarker.indices) {
                        if (searchBuffer[i + j] != endMarker[j]) {
                            found = false
                            break
                        }
                    }
                    if (found) {
                        return currentPos + i
                    }
                }

                // التقدم مع تداخل
                currentPos += bytesRead - endMarker.size - 1
                if (currentPos < searchStart) currentPos = searchStart
            }
        } catch (_: Exception) {}

        return -1L
    }

    /**
     * معالجة خاصة لبعض تنسيقات الملفات
     *
     * بعض الملفات تحتاج معالجة خاصة لتحديد نهايتها:
     * - JPEG: البحث عن FFD9 مع التعامل مع التضمين (embedding)
     * - PNG: حساب حجم الملف من الأجزاء (chunks)
     * - MP4: البحث عن moov أو mdat box النهائي
     *
     * @param raf ملف الوصول العشوائي
     * @param startOffset إزاحة البداية
     * @param signature التوقيع
     * @param dataSourceSize حجم مصدر البيانات
     * @return حجم الملف المكتشف أو -1
     */
    private suspend fun formatSpecificCarving(
        raf: RandomAccessFile,
        startOffset: Long,
        signature: FileSignatures.FileSignature,
        dataSourceSize: Long
    ): Long {
        try {
            when (signature.name) {
                "JPEG" -> {
                    // البحث عن FFD9 (End of Image)
                    // يجب الانتباه: FFD9 قد يظهر داخل بيانات الصورة المضمنة
                    // لذلك نبحث عن FFD9 التي تأتي بعد FFD8
                    return carveJpeg(raf, startOffset, dataSourceSize)
                }

                "PNG" -> {
                    // حساب حجم PNG من الأجزاء
                    return carvePng(raf, startOffset, dataSourceSize)
                }

                "GIF" -> {
                    // GIF ينتهي بعلامة 0x3B
                    return carveGif(raf, startOffset, dataSourceSize)
                }
            }
        } catch (_: Exception) {}

        return -1L
    }

    /**
     * استخراج ملف JPEG من البيانات الخام
     *
     * JPEG معقد لأنه يحتوي على علامات متعددة (APP0, APP1, DQT, SOF, DHT, SOS...)
     * والعلامة FFD9 هي نهاية الصورة. لكن يجب الحذر من أن FFD9
     * قد يظهر داخل بيانات الصورة المضمنة (embedded thumbnails).
     *
     * الاستراتيجية: نبحث عن FFD9 متبوعاً بتوقيع ملف آخر أو نهاية البيانات
     */
    private suspend fun carveJpeg(
        raf: RandomAccessFile,
        startOffset: Long,
        dataSourceSize: Long
    ): Long {
        try {
            val searchEnd = minOf(startOffset + MAX_END_MARKER_SEARCH, dataSourceSize)
            val buffer = ByteArray(READ_BUFFER_SIZE)
            var pos = startOffset + 4 // تخطي SOI marker

            while (pos < searchEnd && currentCoroutineContext().isActive) {
                raf.seek(pos)
                val bytesRead = raf.read(buffer)
                if (bytesRead <= 1) break

                for (i in 0 until bytesRead - 1) {
                    // البحث عن FF followed by any marker
                    if ((buffer[i].toInt() and 0xFF) == 0xFF) {
                        val marker = buffer[i + 1].toInt() and 0xFF

                        when {
                            // End of Image
                            marker == 0xD9 -> {
                                return pos + i + 2 - startOffset // FFD9 = 2 bytes
                            }
                            // Start of Scan - البيانات المضغوطة تلي هذا
                            marker == 0xDA -> {
                                // قراءة طول segment والقفز فوق البيانات المضغوطة
                                if (i + 3 < bytesRead) {
                                    val segLen = ((buffer[i + 2].toInt() and 0xFF) shl 8) or
                                            (buffer[i + 3].toInt() and 0xFF)
                                    pos += i + segLen + 2
                                    break
                                }
                            }
                            // Restart markers (RST0-RST7) - لا طول لها
                            marker in 0xD0..0xD7 -> {
                                // متابعة بدون قفز
                            }
                            // Other markers مع طول segment
                            marker !in 0x00..0x01 && marker != 0xFF -> {
                                if (i + 3 < bytesRead) {
                                    val segLen = ((buffer[i + 2].toInt() and 0xFF) shl 8) or
                                            (buffer[i + 3].toInt() and 0xFF)
                                    if (segLen >= 2) {
                                        pos += i + segLen + 2
                                        break
                                    }
                                }
                            }
                        }
                    }
                }

                pos += bytesRead - 1
            }
        } catch (_: Exception) {}

        return -1L
    }

    /**
     * استخراج ملف PNG من البيانات الخام
     *
     * PNG يتكون من أجزاء (chunks) كل واحد يحتوي على:
     * - 4 بايت: طول البيانات
     * - 4 بايت: نوع الجزء
     * - N بايت: البيانات
     * - 4 بايت: CRC
     *
     * الملف ينتهي بـ IEND chunk
     */
    private suspend fun carvePng(
        raf: RandomAccessFile,
        startOffset: Long,
        dataSourceSize: Long
    ): Long {
        try {
            // تخطي توقيع PNG (8 بايت)
            var pos = startOffset + 8

            while (pos < dataSourceSize - 12 && currentCoroutineContext().isActive) {
                raf.seek(pos)

                // قراءة طول ونوع الجزء
                val chunkHeader = ByteArray(8)
                val bytesRead = raf.read(chunkHeader)
                if (bytesRead < 8) break

                val chunkLength = ByteBuffer.wrap(chunkHeader, 0, 4)
                    .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val chunkType = String(chunkHeader, 4, 4)

                // التحقق من أن الطول معقول
                if (chunkLength > MAX_CARVED_FILE_SIZE) break

                // حساب حجم الجزء الكامل (طول + نوع + بيانات + CRC)
                val totalChunkSize = 4 + 4 + chunkLength + 4

                // التحقق من IEND (نهاية الملف)
                if (chunkType == "IEND") {
                    return pos + totalChunkSize - startOffset
                }

                // الانتقال إلى الجزء التالي
                pos += totalChunkSize
            }
        } catch (_: Exception) {}

        return -1L
    }

    /**
     * استخراج ملف GIF من البيانات الخام
     *
     * GIF ينتهي بعلامة 0x3B (Trailer)
     * لكن يجب التعامل مع كتل البيانات المتعددة بعناية
     */
    private suspend fun carveGif(
        raf: RandomAccessFile,
        startOffset: Long,
        dataSourceSize: Long
    ): Long {
        try {
            val searchEnd = minOf(startOffset + MAX_END_MARKER_SEARCH, dataSourceSize)
            val buffer = ByteArray(READ_BUFFER_SIZE)
            var pos = startOffset

            while (pos < searchEnd && currentCoroutineContext().isActive) {
                raf.seek(pos)
                val bytesRead = raf.read(buffer)
                if (bytesRead <= 0) break

                for (i in 0 until bytesRead) {
                    // GIF Trailer = 0x3B
                    if ((buffer[i].toInt() and 0xFF) == 0x3B) {
                        return pos + i + 1 - startOffset
                    }
                }

                pos += bytesRead - 1
            }
        } catch (_: Exception) {}

        return -1L
    }

    // ═══════════════════════════════════════════════════════════════
    // فحص السلامة الأساسي وتقدير الثقة
    // ═══════════════════════════════════════════════════════════════

    /**
     * فحص سلامة أساسي للبيانات المستخرجة
     *
     * يقرأ عينة من البيانات ويتحقق من:
     * 1. ليست كلها أصفاراً (قد تكون مساحة محذوفة)
     * 2. ليست كلها 0xFF (قد تكون بيانات غير مهيأة)
     * 3. التنوع الكافي في البايتات (بيانات حقيقية)
     *
     * @param raf ملف الوصول العشوائي
     * @param startOffset إزاحة البداية
     * @param size حجم البيانات
     * @param signature التوقيع
     * @return درجة السلامة من 0 إلى 100
     */
    private fun performBasicIntegrityCheck(
        raf: RandomAccessFile,
        startOffset: Long,
        size: Long,
        signature: FileSignatures.FileSignature
    ): Int {
        var score = 50 // درجة أساسية

        try {
            // قراءة عينة من البداية والمنتصف والنهاية
            val sampleSize = minOf(4096L, size / 3)
            val samples = mutableListOf<ByteArray>()

            // عينة من البداية
            raf.seek(startOffset)
            val startSample = ByteArray(sampleSize.toInt())
            raf.read(startSample)
            samples.add(startSample)

            // عينة من المنتصف
            if (size > sampleSize * 2) {
                raf.seek(startOffset + size / 2)
                val midSample = ByteArray(sampleSize.toInt())
                raf.read(midSample)
                samples.add(midSample)
            }

            // عينة من النهاية
            if (size > sampleSize * 3) {
                raf.seek(startOffset + size - sampleSize)
                val endSample = ByteArray(sampleSize.toInt())
                raf.read(endSample)
                samples.add(endSample)
            }

            // فحص كل عينة
            for (sample in samples) {
                val zeroCount = sample.count { it.toInt() == 0 }
                val ffCount = sample.count { (it.toInt() and 0xFF) == 0xFF }
                val totalBytes = sample.size

                // الكثير من الأصفار = بيانات محذوفة أو فارغة
                if (zeroCount > totalBytes * 0.9) {
                    score -= 20
                } else if (zeroCount > totalBytes * 0.5) {
                    score -= 10
                }

                // الكثير من 0xFF = بيانات غير مهيأة
                if (ffCount > totalBytes * 0.9) {
                    score -= 20
                } else if (ffCount > totalBytes * 0.5) {
                    score -= 10
                }

                // تنوع جيد في البايتات = بيانات حقيقية
                val uniqueBytes = sample.toSet().size
                if (uniqueBytes > 100) {
                    score += 10
                } else if (uniqueBytes > 50) {
                    score += 5
                }
            }

            // فحص خاص بتنسيق JPEG
            if (signature.name == "JPEG") {
                // التحقق من وجود SOI و FFD9
                raf.seek(startOffset)
                val soi = ByteArray(2)
                raf.read(soi)
                if (soi[0] == 0xFF.toByte() && soi[1] == 0xD8.toByte()) {
                    score += 10
                }

                // التحقق من علامة النهاية
                raf.seek(startOffset + size - 2)
                val eoi = ByteArray(2)
                raf.read(eoi)
                if (eoi[0] == 0xFF.toByte() && eoi[1] == 0xD9.toByte()) {
                    score += 15
                }
            }

        } catch (_: Exception) {
            score -= 10
        }

        return score.coerceIn(0, 100)
    }

    /**
     * حساب مستوى ثقة الاستعادة
     *
     * يجمع بين عدة عوامل لحساب الثقة النهائية:
     * - دقة التوقيع
     * - اكتمال الملف (علامة النهاية)
     * - درجة السلامة
     * - محاذاة البيانات
     *
     * @return مستوى الثقة
     */
    private fun computeConfidence(
        signature: FileSignatures.FileSignature,
        carvedSize: Long,
        hasEndMarker: Boolean,
        integrityScore: Int,
        startOffset: Long
    ): RecoveryConfidence {
        var score = 0

        // علامة النهاية = أكبر عامل في الثقة
        if (hasEndMarker) score += 30

        // درجة السلامة
        score += integrityScore / 3

        // محاذاة الكتلة
        if (startOffset % 4096 == 0L) score += 5
        else if (startOffset % 512 == 0L) score += 3

        // طول التوقيع (أطول = أدق)
        if (signature.hexPattern.size >= 8) score += 5
        else if (signature.hexPattern.size >= 4) score += 3

        // حجم معقول
        val avgSize = when (signature.category) {
            FileCategory.PHOTO -> 5_000_000L // 5 MB
            FileCategory.VIDEO -> 100_000_000L // 100 MB
            FileCategory.AUDIO -> 10_000_000L // 10 MB
            else -> 1_000_000L // 1 MB
        }
        if (carvedSize in avgSize / 10..avgSize * 10) score += 5

        return when {
            score >= 75 -> RecoveryConfidence.HIGH
            score >= 50 -> RecoveryConfidence.MEDIUM
            score >= 25 -> RecoveryConfidence.LOW
            else -> RecoveryConfidence.UNCERTAIN
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // دوال مساعدة
    // ═══════════════════════════════════════════════════════════════

    /**
     * تقدير حجم الملف من التوقيع
     */
    private fun estimateFileSizeFromSignature(signature: FileSignatures.FileSignature): Long {
        return when (signature.category) {
            FileCategory.PHOTO -> 5L * 1024 * 1024 // 5 MB
            FileCategory.VIDEO -> 100L * 1024 * 1024 // 100 MB
            FileCategory.AUDIO -> 10L * 1024 * 1024 // 10 MB
            FileCategory.DOCUMENT -> 5L * 1024 * 1024 // 5 MB
            FileCategory.ARCHIVE -> 50L * 1024 * 1024 // 50 MB
            FileCategory.APK -> 50L * 1024 * 1024 // 50 MB
            FileCategory.OTHER -> 5L * 1024 * 1024 // 5 MB
        }
    }

    /**
     * إنشاء اسم ملف للملف المستخرج
     */
    private fun generateCarvedFileName(signature: FileSignatures.FileSignature, offset: Long): String {
        val timestamp = System.currentTimeMillis()
        val offsetHex = offset.toHexString()
        val ext = signature.extensions.first()
        return "recovered_${signature.name}_${timestamp}_${offsetHex}.$ext"
    }

    /**
     * الحصول على حجم القسم
     */
    private suspend fun getPartitionSize(partitionPath: String): Long {
        return try {
            val file = File(partitionPath)
            if (file.exists()) {
                // محاولة قراءة الحجم من /sys/block
                val deviceName = partitionPath.substringAfterLast("/")
                val sizeFile = File("/sys/block/$deviceName/size")
                if (sizeFile.exists() && sizeFile.canRead()) {
                    return sizeFile.readText().trim().toLong() * 512
                }
                file.length()
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * مسح قسم خام باستخدام صلاحيات الروت
     */
    private suspend fun scanPartitionWithRoot(
        partitionPath: String,
        foundFiles: MutableList<FoundFileInfo>,
        estimatedSize: Long,
        onProgress: suspend (Long) -> Unit
    ) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "dd if=$partitionPath bs=$SCAN_BLOCK_SIZE 2>/dev/null")
            )

            val inputStream = process.inputStream
            val buffer = ByteArray(READ_BUFFER_SIZE + OVERLAP_SIZE)
            var position = 0L

            while (currentCoroutineContext().isActive) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead <= 0) break

                onProgress(bytesRead.toLong())

                // البحث عن التوقيعات
                for (offset in 0 until bytesRead - FileSignatures.MINIMUM_HEADER_SIZE) {
                    if (!currentCoroutineContext().isActive) break

                    val headerSize = minOf(FileSignatures.MINIMUM_HEADER_SIZE, bytesRead - offset)
                    val header = buffer.copyOfRange(offset, offset + headerSize)

                    val signature = FileSignatures.identifyFileType(header)
                    if (signature != null) {
                        val fileStartOffset = position + offset
                        val estimatedFileSize = estimateFileSizeFromSignature(signature)
                        val fileName = generateCarvedFileName(signature, fileStartOffset)

                        val fileInfo = FoundFileInfo(
                            path = partitionPath,
                            fileName = fileName,
                            fileSize = estimatedFileSize,
                            extension = signature.extensions.first(),
                            mimeType = signature.mimeType,
                            category = signature.category,
                            signatureName = signature.name,
                            offsetInBlock = fileStartOffset,
                            confidence = RecoveryConfidence.LOW,
                            isFragment = true,
                            isRootRequired = true,
                            sourcePath = partitionPath,
                            metadata = mapOf(
                                "carving_method" to "signature_root",
                                "offset" to fileStartOffset.toString(),
                                "size_estimated" to "true"
                            )
                        )

                        foundFiles.add(fileInfo)
                    }
                }

                position += bytesRead - OVERLAP_SIZE
            }

            inputStream.close()
            process.waitFor()

        } catch (_: Exception) {
            // فشل الوصول عبر الروت
        }
    }

    /**
     * إعادة تجميع الملفات المجزأة
     *
     * عندما يكون الملف مجزأً (موزعاً على مواقع غير متصلة) على القرص،
     * تحاول هذه الدالة إعادة تجميع الأجزاء بناءً على:
     * 1. التوقيع المشترك (نفس النوع)
     * 2. التسلسل المنطقي للبيانات
     * 3. المعلومات من نظام الملفات إن توفرت
     *
     * @param fragments قائمة أجزاء الملف المراد تجميعها
     * @return قائمة بالملفات المعاد تجميعها
     */
    fun reassembleFragments(fragments: List<FoundFileInfo>): List<FoundFileInfo> {
        if (fragments.size <= 1) return fragments

        val reassembled = mutableListOf<FoundFileInfo>()
        val grouped = fragments.groupBy { it.signatureName to it.category }

        for ((key, group) in grouped) {
            if (group.size == 1) {
                reassembled.add(group.first())
                continue
            }

            // ترتيب الأجزاء حسب الإزاحة
            val sorted = group.sortedBy { it.offsetInBlock }

            // محاولة دمج الأجزاء المتجاورة
            val merged = mutableListOf<FoundFileInfo>()
            var current = sorted.first()

            for (i in 1 until sorted.size) {
                val next = sorted[i]
                val gap = next.offsetInBlock - (current.offsetInBlock + current.fileSize)

                // إذا كانت الفجوة صغيرة، نعتبرها جزءاً من نفس الملف
                if (gap in 0..SCAN_BLOCK_SIZE) {
                    current = current.copy(
                        fileSize = (next.offsetInBlock + next.fileSize) - current.offsetInBlock,
                        isFragment = true,
                        confidence = RecoveryConfidence.LOW
                    )
                } else {
                    merged.add(current)
                    current = next
                }
            }
            merged.add(current)
            reassembled.addAll(merged)
        }

        return reassembled
    }

    /**
     * حساب MD5 للبيانات المستخرجة
     *
     * يستخدم للتحقق من صحة البيانات وإزالة المكررات
     *
     * @param raf ملف الوصول العشوائي
     * @param startOffset إزاحة البداية
     * @param size حجم البيانات
     * @return تجزئة MD5 أو null في حالة الفشل
     */
    suspend fun computeMd5Hash(raf: RandomAccessFile, startOffset: Long, size: Long): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            raf.seek(startOffset)

            val buffer = ByteArray(8192)
            var remaining = size

            while (remaining > 0 && currentCoroutineContext().isActive) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val bytesRead = raf.read(buffer, 0, toRead)
                if (bytesRead <= 0) break

                digest.update(buffer, 0, bytesRead)
                remaining -= bytesRead
            }

            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
