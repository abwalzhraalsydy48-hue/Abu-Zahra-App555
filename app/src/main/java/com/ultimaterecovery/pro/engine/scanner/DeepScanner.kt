@file:OptIn(kotlin.ExperimentalStdlibApi::class)

package com.ultimaterecovery.pro.engine.scanner

import android.os.Environment
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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * الماسح العميق - يقرأ الكتل الخام من التخزين لاستعادة الملفات المحذوفة
 *
 * على عكس الماسح السريع الذي يعتمد على إدخالات نظام الملفات،
 * يقرأ الماسح العميق البيانات الخام مباشرة من القرص ويبحث عن
 * توقيعات الملفات في المساحة غير المخصصة (Unallocated Space).
 *
 * كيف يعمل:
 * 1. يفتح جهاز القسم أو ملف التخزين كبيانات خام
 * 2. يقرأ البيانات كتلة بكتلة (عادةً 4096 بايت لكل كتلة)
 * 3. يبحث عن توقيعات بداية الملفات (Magic Numbers) في كل كتلة
 * 4. عند العثور على توقيع، يحاول استخراج الملف الكامل
 * 5. يتعامل مع الملفات المجزأة (Fragmented Files)
 * 6. يتحقق من علامات نهاية الملفات عند توفرها
 *
 * ملاحظة: هذا النوع من المسح يحتاج عادةً صلاحيات الروت
 * للوصول المباشر إلى أجهزة التخزين (/dev/block/...)
 */
@Singleton
class DeepScanner @Inject constructor() {

    companion object {
        /** حجم الكتلة الافتراضي للقراءة (4 كيلوبايت) */
        private const val DEFAULT_BLOCK_SIZE = 4096

        /** حجم القطاع (512 بايت - المعيار القياسي) */
        private const val SECTOR_SIZE = 512

        /** حجم المخزن المؤقت للقراءة (1 ميجابايت) */
        private const val READ_BUFFER_SIZE = 1024 * 1024

        /** الحد الأقصى لحجم الملف المستعاد (2 جيجابايت) */
        private const val MAX_RECOVERABLE_FILE_SIZE = 2L * 1024 * 1024 * 1024

        /** الحد الأدنى لحجم الملف المراد تضمينه (256 بايت) */
        private const val MIN_RECOVERABLE_FILE_SIZE = 256L

        /** عدد الكتل بين تحديثات التقدم */
        private const val PROGRESS_UPDATE_INTERVAL = 256

        /** الإزاحة القصوى للبحث عن نهاية الملف من نقطة البداية (100 ميجابايت) */
        private const val MAX_END_MARKER_SEARCH_DISTANCE = 100L * 1024 * 1024

        /** الحد الأقصى لعمق البحث عن أجزاء الملف المجزأ */
        private const val MAX_FRAGMENT_SEARCH_BLOCKS = 1024
    }

    /**
     * بدء المسح العميق
     *
     * @param paths قائمة المسارات/الأقسام المراد مسحها
     * @param categories فئات الملفات المراد البحث عنها
     * @return تدفق حالة المسح مع النتائج
     */
    suspend fun scan(
        paths: List<String>,
        categories: List<FileCategory>
    ): Flow<ScanState> = flow {
        val startTime = System.currentTimeMillis()
        val foundFiles = mutableListOf<FoundFileInfo>()
        var totalBytesScanned = 0L
        var totalSize = 0L

        val targetCategories = if (categories.isEmpty()) FileCategory.values().toList() else categories

        try {
            val scanPaths = if (paths.isEmpty()) getDefaultDeepScanPaths() else paths

            for ((pathIndex, scanPath) in scanPaths.withIndex()) {
                if (!currentCoroutineContext().isActive) break

                val file = File(scanPath)

                // التحقق من إمكانية الوصول
                if (!file.exists()) {
                    continue
                }

                // حساب الحجم الإجمالي
                totalSize += file.length()

                emit(ScanState.Scanning(
                    progress = if (totalSize > 0) totalBytesScanned.toFloat() / totalSize else 0f,
                    currentPath = scanPath,
                    filesFound = foundFiles.size,
                    bytesScanned = totalBytesScanned,
                    totalBytes = totalSize,
                    scanType = ScanType.DEEP
                ))

                // محاولة فتح الملف كبيانات خام
                try {
                    scanRawData(scanPath, targetCategories, foundFiles) { bytesRead ->
                        totalBytesScanned += bytesRead

                        // تحديث التقدم بشكل دوري
                        if (totalBytesScanned % (PROGRESS_UPDATE_INTERVAL * DEFAULT_BLOCK_SIZE) == 0L) {
                            val progress = if (totalSize > 0) {
                                totalBytesScanned.toFloat() / totalSize
                            } else 0f

                            emit(ScanState.Scanning(
                                progress = progress.coerceIn(0f, 1f),
                                currentPath = scanPath,
                                filesFound = foundFiles.size,
                                bytesScanned = totalBytesScanned,
                                totalBytes = totalSize,
                                scanType = ScanType.DEEP
                            ))
                        }
                    }
                } catch (e: SecurityException) {
                    // محاولة الوصول عبر صلاحيات الروت
                    try {
                        scanRawDataWithRoot(scanPath, targetCategories, foundFiles) { bytesRead ->
                            totalBytesScanned += bytesRead
                        }
                    } catch (rootException: Exception) {
                        // فشل الوصول حتى مع الروت - نتابع مع المسار التالي
                    }
                }
            }

            // إرسال النتيجة النهائية
            val uniqueFiles = foundFiles.distinctBy { it.path to it.offsetInBlock }
            val elapsed = System.currentTimeMillis() - startTime

            emit(ScanState.Completed(
                results = uniqueFiles,
                totalFiles = uniqueFiles.size,
                totalSize = uniqueFiles.sumOf { it.fileSize },
                durationMs = elapsed,
                scanType = ScanType.DEEP
            ))

        } catch (e: Exception) {
            emit(ScanState.Failed(
                error = e.message ?: "خطأ في المسح العميق",
                scanType = ScanType.DEEP,
                partialResults = foundFiles.distinctBy { it.path to it.offsetInBlock }
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * مسح البيانات الخام من ملف/جهاز
     *
     * هذه هي العملية الأساسية في المسح العميق:
     * 1. يفتح الجهاز/الملف للقراءة الثنائية
     * 2. يقرأ البيانات في مخزن مؤقت كبير
     * 3. يبحث عن توقيعات بداية الملفات في كل كتلة
     * 4. عند العثور على توقيع، يستخرج الملف الكامل
     *
     * @param path مسار الجهاز أو الملف
     * @param categories فئات الملفات المطلوبة
     * @param foundFiles قائمة النتائج
     * @param onBytesScanned دالة استدعاء عند قراءة بايتات جديدة
     */
    private suspend fun scanRawData(
        path: String,
        categories: List<FileCategory>,
        foundFiles: MutableList<FoundFileInfo>,
        onBytesScanned: suspend (Long) -> Unit
    ) {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return

        val fileSize = file.length()
        if (fileSize == 0L) return

        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(READ_BUFFER_SIZE)
            var position = 0L
            var lastSignatureEnd = -1L // لتجنب التداخل بين التوقيعات

            while (position < fileSize && currentCoroutineContext().isActive) {
                val bytesToRead = minOf(buffer.size.toLong(), fileSize - position)
                raf.seek(position)
                val bytesRead = raf.read(buffer, 0, bytesToRead.toInt())

                if (bytesRead <= 0) break

                onBytesScanned(bytesRead.toLong())

                // البحث عن توقيعات الملفات في المخزن المؤقت الحالي
                for (offset in 0 until bytesRead - FileSignatures.MINIMUM_HEADER_SIZE) {
                    if (!currentCoroutineContext().isActive) break

                    // تخطي المنطقة التي تم استخراج ملف منها بالفعل
                    if (position + offset < lastSignatureEnd) continue

                    // نسخ رأس الملف المحتمل
                    val headerSize = minOf(
                        FileSignatures.MINIMUM_HEADER_SIZE,
                        bytesRead - offset
                    )
                    val header = buffer.copyOfRange(offset, offset + headerSize)

                    // محاولة مطابقة التوقيع
                    val signature = FileSignatures.identifyFileType(header)
                    if (signature != null && signature.category in categories) {
                        // عثرنا على توقيع! محاولة استخراج الملف الكامل
                        val fileStartOffset = position + offset
                        val carvedFile = carveFileFromRawData(
                            raf = raf,
                            startOffset = fileStartOffset,
                            signature = signature,
                            fileSize = fileSize,
                            path = path
                        )

                        if (carvedFile != null && carvedFile.fileSize >= MIN_RECOVERABLE_FILE_SIZE) {
                            foundFiles.add(carvedFile)
                            lastSignatureEnd = fileStartOffset + carvedFile.fileSize
                        }
                    }
                }

                // التقدم في القراءة مع تداخل لتجنب تفويت التوقيعات على الحدود
                position += bytesRead - FileSignatures.MINIMUM_HEADER_SIZE
            }
        }
    }

    /**
     * مسح البيانات الخام باستخدام صلاحيات الروت
     *
     * يستخدم أوامر su للوصول إلى أجهزة التخزين المحمية
     *
     * @param path مسار الجهاز
     * @param categories فئات الملفات المطلوبة
     * @param foundFiles قائمة النتائج
     * @param onBytesScanned دالة استدعاء عند قراءة بايتات جديدة
     */
    private suspend fun scanRawDataWithRoot(
        path: String,
        categories: List<FileCategory>,
        foundFiles: MutableList<FoundFileInfo>,
        onBytesScanned: (Long) -> Unit
    ) {
        try {
            // استخدام dd لنسخ البيانات الخام عبر su
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "dd if=$path bs=$READ_BUFFER_SIZE 2>/dev/null")
            )

            val inputStream = process.inputStream
            val buffer = ByteArray(READ_BUFFER_SIZE)
            var position = 0L
            var lastSignatureEnd = -1L

            // قراءة حجم الجهاز
            val deviceSize = getDeviceSize(path)

            while (currentCoroutineContext().isActive) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead <= 0) break

                onBytesScanned(bytesRead.toLong())

                // البحث عن التوقيعات بنفس طريقة scanRawData
                for (offset in 0 until bytesRead - FileSignatures.MINIMUM_HEADER_SIZE) {
                    if (!currentCoroutineContext().isActive) break

                    if (position + offset < lastSignatureEnd) continue

                    val headerSize = minOf(
                        FileSignatures.MINIMUM_HEADER_SIZE,
                        bytesRead - offset
                    )
                    val header = buffer.copyOfRange(offset, offset + headerSize)

                    val signature = FileSignatures.identifyFileType(header)
                    if (signature != null && signature.category in categories) {
                        // في المسح عبر الروت، لا نستطيع البحث عن مؤشر النهاية بسهولة
                        // لذلك نقدر حجم الملف بناءً على التوقيع
                        val estimatedSize = estimateFileSize(signature)
                        val fileStartOffset = position + offset

                        val fileInfo = FoundFileInfo(
                            path = path,
                            fileName = "recovered_${signature.name}_${fileStartOffset.toHexString()}.${signature.extensions.first()}",
                            fileSize = estimatedSize,
                            extension = signature.extensions.first(),
                            mimeType = signature.mimeType,
                            category = signature.category,
                            signatureName = signature.name,
                            offsetInBlock = fileStartOffset,
                            confidence = RecoveryConfidence.LOW, // ثقة منخفضة لأن الحجم تقديري
                            isFragment = true, // يرجح أنه مجزأ
                            isRootRequired = true,
                            sourcePath = path,
                            metadata = mapOf(
                                "scan_method" to "deep_root",
                                "block_offset" to fileStartOffset.toString(),
                                "estimated_size" to "true"
                            )
                        )

                        if (fileInfo.fileSize >= MIN_RECOVERABLE_FILE_SIZE) {
                            foundFiles.add(fileInfo)
                            lastSignatureEnd = fileStartOffset + estimatedSize
                        }
                    }
                }

                position += bytesRead
            }

            inputStream.close()
            process.waitFor()

        } catch (_: Exception) {
            // فشل الوصول عبر الروت
        }
    }

    /**
     * استخراج ملف من البيانات الخام (File Carving)
     *
     * عند العثور على توقيع بداية ملف، تحاول هذه الدالة:
     * 1. تحديد حجم الملف إن أمكن (من الرأس)
     * 2. البحث عن علامة نهاية الملف
     * 3. قراءة البيانات الكاملة للملف
     * 4. التحقق من سلامة البيانات المستخرجة
     *
     * @param raf ملف الوصول العشوائي للبيانات الخام
     * @param startOffset إزاحة بداية الملف في البيانات الخام
     * @param signature التوقيع المطابق
     * @param fileSize الحجم الإجمالي للبيانات الخام
     * @return معلومات الملف المستخرج أو null
     */
    private suspend fun carveFileFromRawData(
        raf: RandomAccessFile,
        startOffset: Long,
        signature: FileSignatures.FileSignature,
        fileSize: Long,
        path: String
    ): FoundFileInfo? {
        try {
            // ──────────────────────────────────────────
            // المرحلة 1: محاولة تحديد حجم الملف من الرأس
            // ──────────────────────────────────────────
            var detectedSize = tryDetectFileSize(raf, startOffset, signature)

            // ──────────────────────────────────────────
            // المرحلة 2: البحث عن علامة نهاية الملف
            // ──────────────────────────────────────────
            if (signature.endMarker != null) {
                val endOffset = findEndMarker(
                    raf, startOffset, signature.endMarker, fileSize
                )
                if (endOffset > startOffset) {
                    detectedSize = endOffset - startOffset + signature.endMarker.size
                }
            }

            // ──────────────────────────────────────────
            // المرحلة 3: التحقق من الحجم والحدود
            // ──────────────────────────────────────────
            if (detectedSize <= 0L) {
                // لم نتمكن من تحديد الحجم - نستخدم تقديراً
                detectedSize = estimateFileSize(signature)
            }

            // التحقق من أن الملف لا يتجاوز الحد الأقصى
            detectedSize = detectedSize.coerceAtMost(MAX_RECOVERABLE_FILE_SIZE)

            // التحقق من أن الملف لا يتجاوز حجم البيانات المتاحة
            val availableSize = fileSize - startOffset
            if (detectedSize > availableSize) {
                detectedSize = availableSize
            }

            if (detectedSize < MIN_RECOVERABLE_FILE_SIZE) {
                return null
            }

            // ──────────────────────────────────────────
            // المرحلة 4: حساب مستوى الثقة
            // ──────────────────────────────────────────
            val confidence = calculateCarvingConfidence(
                signature = signature,
                detectedSize = detectedSize,
                hasEndMarker = signature.endMarker != null,
                startOffset = startOffset
            )

            // ──────────────────────────────────────────
            // المرحلة 5: إنشاء معلومات الملف
            // ──────────────────────────────────────────
            val fileName = "recovered_${signature.name}_${startOffset.toHexString()}.${signature.extensions.first()}"

            return FoundFileInfo(
                path = path,
                fileName = fileName,
                fileSize = detectedSize,
                extension = signature.extensions.first(),
                mimeType = signature.mimeType,
                category = signature.category,
                signatureName = signature.name,
                offsetInBlock = startOffset,
                confidence = confidence,
                isFragment = detectedSize != tryDetectFileSize(raf, startOffset, signature),
                isRootRequired = true,
                sourcePath = path,
                metadata = mapOf(
                    "scan_method" to "deep_carving",
                    "block_offset" to startOffset.toString(),
                    "detected_size" to detectedSize.toString(),
                    "has_end_marker" to (signature.endMarker != null).toString(),
                    "confidence_score" to confidence.percentage.toString()
                )
            )

        } catch (_: Exception) {
            return null
        }
    }

    /**
     * محاولة تحديد حجم الملف من الرأس
     *
     * بعض تنسيقات الملفات تحتوي على معلومات الحجم في الرأس:
     * - PNG: chunk length fields
     * - JPEG: APP markers with length
     * - MP4: ftyp box size
     * - RIFF (AVI/WAV): chunk size at offset 4
     * - ZIP: end of central directory
     *
     * @param raf ملف الوصول العشوائي
     * @param startOffset إزاحة بداية الملف
     * @param signature التوقيع
     * @return حجم الملف المكتشف أو -1 إذا لم يمكن تحديده
     */
    private fun tryDetectFileSize(
        raf: RandomAccessFile,
        startOffset: Long,
        signature: FileSignatures.FileSignature
    ): Long {
        try {
            raf.seek(startOffset)

            when (signature.name) {
                // RIFF containers (AVI, WAV, WEBP): الحجم في البايتات 4-7 + 8
                "AVI", "WAV", "WEBP" -> {
                    if (raf.length() - startOffset >= 12) {
                        raf.seek(startOffset + 4)
                        val sizeBytes = ByteArray(4)
                        raf.read(sizeBytes)
                        val riffSize = ByteBuffer.wrap(sizeBytes)
                            .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        return riffSize + 8 // RIFF header size
                    }
                }

                // MP4/MOV: ftyp box يحتوي على الحجم في أول 4 بايت
                "MP4", "MOV" -> {
                    if (raf.length() - startOffset >= 8) {
                        raf.seek(startOffset)
                        val sizeBytes = ByteArray(4)
                        raf.read(sizeBytes)
                        val boxSize = ByteBuffer.wrap(sizeBytes)
                            .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        if (boxSize in 8..MAX_RECOVERABLE_FILE_SIZE) {
                            return boxSize // هذا حجم ftyp فقط، لكنه مؤشر جيد
                        }
                    }
                }

                // FLAC: حجم التدفق في رأس FLAC
                "FLAC" -> {
                    // FLAC لا يخزن الحجم الكلي في الرأس بسهولة
                    return -1L
                }

                // PDF: البحث عن %%EOF
                "PDF" -> {
                    return -1L // سنبحث عن endMarker بدلاً من ذلك
                }
            }
        } catch (_: Exception) {}

        return -1L
    }

    /**
     * البحث عن علامة نهاية الملف
     *
     * يبحث في البيانات الخام عن علامة النهاية الخاصة بالملف
     * (مثل FFD9 لـ JPEG أو IEND لـ PNG)
     *
     * @param raf ملف الوصول العشوائي
     * @param startOffset نقطة بداية البحث
     * @param endMarker علامة النهاية
     * @param dataSize الحجم الإجمالي للبيانات
     * @return إزاحة نهاية الملف (بعد علامة النهاية) أو -1
     */
    private suspend fun findEndMarker(
        raf: RandomAccessFile,
        startOffset: Long,
        endMarker: ByteArray,
        dataSize: Long
    ): Long {
        try {
            val searchStart = startOffset + endMarker.size // لا نبحث قبل رأس الملف
            val searchEnd = minOf(
                startOffset + MAX_END_MARKER_SEARCH_DISTANCE,
                dataSize - endMarker.size
            )

            // البحث في كتل كبيرة للكفاءة
            val searchBuffer = ByteArray(READ_BUFFER_SIZE)
            var searchPos = searchStart

            while (searchPos < searchEnd && currentCoroutineContext().isActive) {
                raf.seek(searchPos)
                val bytesRead = raf.read(searchBuffer)
                if (bytesRead <= 0) break

                for (i in 0 until bytesRead - endMarker.size) {
                    var matches = true
                    for (j in endMarker.indices) {
                        if (searchBuffer[i + j] != endMarker[j]) {
                            matches = false
                            break
                        }
                    }
                    if (matches) {
                        return searchPos + i
                    }
                }

                // التقدم مع تداخل
                searchPos += bytesRead - endMarker.size
            }
        } catch (_: Exception) {}

        return -1L
    }

    /**
     * تقدير حجم الملف بناءً على التوقيع
     *
     * يستخدم الحجم الأقصى المحدد في التوقيع كمرجع
     * ويعيد قيمة تقديرية معقولة
     */
    private fun estimateFileSize(signature: FileSignatures.FileSignature): Long {
        // نستخدم حد أقصى معقولاً لكل نوع
        return when (signature.category) {
            FileCategory.PHOTO -> minOf(signature.maxFileSize, 50L * 1024 * 1024) // 50 MB
            FileCategory.VIDEO -> minOf(signature.maxFileSize, 500L * 1024 * 1024) // 500 MB
            FileCategory.AUDIO -> minOf(signature.maxFileSize, 30L * 1024 * 1024) // 30 MB
            FileCategory.DOCUMENT -> minOf(signature.maxFileSize, 10L * 1024 * 1024) // 10 MB
            FileCategory.ARCHIVE -> minOf(signature.maxFileSize, 100L * 1024 * 1024) // 100 MB
            FileCategory.APK -> minOf(signature.maxFileSize, 100L * 1024 * 1024) // 100 MB
            FileCategory.OTHER -> 10L * 1024 * 1024 // 10 MB
        }
    }

    /**
     * حساب مستوى ثقة الاستعادة أثناء الـ Carving
     *
     * يعتمد على عدة عوامل:
     * - نوع التوقيع (بعض التوقيعات أكثر موثوقية)
     * - وجود علامة نهاية
     * - حجم الملف المكتشف
     * - محاذاة الإزاحة
     *
     * @param signature التوقيع المطابق
     * @param detectedSize الحجم المكتشف
     * @param hasEndMarker هل تم العثور على علامة نهاية؟
     * @param startOffset إزاحة البداية
     * @return مستوى الثقة
     */
    private fun calculateCarvingConfidence(
        signature: FileSignatures.FileSignature,
        detectedSize: Long,
        hasEndMarker: Boolean,
        startOffset: Long
    ): RecoveryConfidence {
        var score = 50 // درجة أساسية

        // علامة نهاية موجودة = ثقة أعلى بكثير
        if (hasEndMarker) score += 30

        // الحجم المعقول (ليس صغيراً جداً أو كبيراً جداً)
        when (signature.category) {
            FileCategory.PHOTO -> {
                if (detectedSize in 10_000L..50_000_000L) score += 10
            }
            FileCategory.VIDEO -> {
                if (detectedSize in 100_000L..1_000_000_000L) score += 10
            }
            FileCategory.AUDIO -> {
                if (detectedSize in 5_000L..100_000_000L) score += 10
            }
            else -> {
                if (detectedSize in 1_000L..100_000_000L) score += 5
            }
        }

        // محاذاة الإزاحة (الملفات عادةً تبدأ عند حدود الكتلة)
        if (startOffset % DEFAULT_BLOCK_SIZE == 0L) score += 5

        // التوقيعات الفريدة (مثل PNG, FLAC) أعلى ثقة من المشتركة (مثل ZIP/PK)
        if (signature.hexPattern.size >= 6) score += 5

        return when {
            score >= 80 -> RecoveryConfidence.HIGH
            score >= 50 -> RecoveryConfidence.MEDIUM
            score >= 25 -> RecoveryConfidence.LOW
            else -> RecoveryConfidence.UNCERTAIN
        }
    }

    /**
     * الحصول على حجم جهاز التخزين
     *
     * @param path مسار الجهاز
     * @return الحجم بالبايت أو 0 إذا لم يمكن تحديده
     */
    private fun getDeviceSize(path: String): Long {
        return try {
            // محاولة قراءة الحجم من /sys/block
            val deviceName = path.substringAfterLast("/")
            val sizeFile = File("/sys/block/$deviceName/size")
            if (sizeFile.exists() && sizeFile.canRead()) {
                val sectors = sizeFile.readText().trim().toLong()
                return sectors * SECTOR_SIZE
            }

            // محاولة عبر stat
            val file = File(path)
            if (file.exists()) file.length() else 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * الحصول على مسارات المسح العميق الافتراضية
     *
     * يتضمن أجهزة التخزين الرئيسية والثانوية
     */
    private fun getDefaultDeepScanPaths(): List<String> {
        val paths = mutableListOf<String>()

        // التخزين الداخلي
        val internalStorage = Environment.getExternalStorageDirectory().absolutePath
        paths.add(internalStorage)

        // أقسام التخزين المحتملة (تحتاج روت)
        val possiblePartitions = listOf(
            "/dev/block/bootdevice/by-name/userdata",
            "/dev/block/platform/soc/by-name/userdata",
            "/dev/block/by-name/userdata",
            "/dev/block/sda",
            "/dev/block/sdb",
            "/dev/block/mmcblk0",
            "/dev/block/mmcblk0p1",
            "/dev/block/mmcblk1",
            "/dev/block/mmcblk1p1"
        )

        for (partition in possiblePartitions) {
            if (File(partition).exists()) {
                paths.add(partition)
            }
        }

        // التخزين الخارجي (بطاقة SD)
        try {
            val externalDirs = android.os.Environment.getExternalStorageDirectory()
            paths.add(externalDirs.absolutePath)
        } catch (_: Exception) {}

        return paths.distinct().filter { File(it).exists() }
    }

    /**
     * البحث عن أجزاء الملف المجزأ
     *
     * عندما يكون الملف مجزأً (موزعاً على مواقع غير متصلة على القرص)
     * تحاول هذه الدالة إعادة تجميع الأجزاء عن طريق:
     * 1. قراءة البيانات بعد نهاية الجزء الحالي
     * 2. البحث عن بيانات تبدو امتداداً طبيعياً للملف
     * 3. التحقق من التواصل المنطقي للبيانات
     *
     * هذه العملية معقدة ولا تعمل دائماً بشكل مثالي
     *
     * @param raf ملف الوصول العشوائي
     * @param currentPartEnd نهاية الجزء الحالي
     * @param signature توقيع الملف (للمساعدة في التحقق)
     * @param maxSearchBlocks أقصى عدد من الكتل للبحث فيها
     * @return قائمة إزاحات أجزاء الملف الإضافية
     */
    private suspend fun findFileFragments(
        raf: RandomAccessFile,
        currentPartEnd: Long,
        signature: FileSignatures.FileSignature,
        maxSearchBlocks: Int = MAX_FRAGMENT_SEARCH_BLOCKS
    ): List<Long> {
        val fragments = mutableListOf<Long>()
        var searchPos = currentPartEnd
        var blocksSearched = 0

        // البحث عن كتل لا تحتوي على توقيعات ملفات أخرى
        // لكنها تبدو امتداداً للملف الحالي
        while (searchPos < raf.length() && blocksSearched < maxSearchBlocks && currentCoroutineContext().isActive) {
            try {
                raf.seek(searchPos)
                val testBuffer = ByteArray(minOf(DEFAULT_BLOCK_SIZE, (raf.length() - searchPos).toInt()))
                raf.read(testBuffer)

                // فحص هل هذه الكتلة تبدأ بتوقيع ملف آخر
                val otherSignature = FileSignatures.identifyFileType(testBuffer)

                if (otherSignature == null) {
                    // لا يوجد توقيع ملف آخر هنا - قد يكون امتداداً لملفنا
                    // فحص إضافي: هل البيانات تبدو طبيعية؟
                    if (looksLikeContinuation(testBuffer, signature)) {
                        fragments.add(searchPos)
                    }
                }

                searchPos += DEFAULT_BLOCK_SIZE
                blocksSearched++
            } catch (_: Exception) {
                break
            }
        }

        return fragments
    }

    /**
     * فحص سريع هل تبدأ البيانات كامتداد طبيعي لملف
     *
     * هذا فحص إرشادي - ليس دقيقاً 100% لكنه يقلل الإيجابيات الكاذبة
     *
     * @param data البيانات المراد فحصها
     * @param signature توقيع الملف الأصلي
     * @return true إذا بدت البيانات كامتداد طبيعي
     */
    private fun looksLikeContinuation(data: ByteArray, signature: FileSignatures.FileSignature): Boolean {
        // للصور: البايتات يجب أن لا تكون كلها أصفاراً أو كلها FF
        if (signature.category == FileCategory.PHOTO) {
            var nonZeroCount = 0
            var nonFFCount = 0
            for (b in data.take(256)) {
                if (b.toInt() != 0) nonZeroCount++
                if ((b.toInt() and 0xFF) != 0xFF) nonFFCount++
            }
            // على الأقل 10% من البايتات ليست صفراً و 10% ليست FF
            return nonZeroCount > 25 && nonFFCount > 25
        }

        // للفيديو: عادةً يحتوي على بيانات متنوعة
        if (signature.category == FileCategory.VIDEO) {
            var nonZeroCount = 0
            for (b in data.take(512)) {
                if (b.toInt() != 0) nonZeroCount++
            }
            return nonZeroCount > 50 // على الأقل 10% بيانات
        }

        // افتراضي: البيانات ليست كلها أصفاراً
        var nonZeroCount = 0
        for (b in data.take(128)) {
            if (b.toInt() != 0) nonZeroCount++
        }
        return nonZeroCount > 10
    }
}
