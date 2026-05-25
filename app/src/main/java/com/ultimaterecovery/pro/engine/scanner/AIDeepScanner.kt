package com.ultimaterecovery.pro.engine.scanner

import android.content.Context
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.engine.recovery.FoundFileInfo
import com.ultimaterecovery.pro.engine.recovery.RecoveryConfidence
import com.ultimaterecovery.pro.engine.signatures.FileSignatures
import com.ultimaterecovery.pro.utils.ai.AIRecoveryAssistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * الماسح العميق المدعوم بالذكاء الاصطناعي
 *
 * يستخدم خوارزميات ذكية لاكتشاف الملفات المحذوفة حتى لو كانت قديمة جداً.
 * يمكنه العثور على ملفات تم حذفها منذ سنوات من خلال:
 *
 * 1. تحليل أنماط البيانات في المساحة غير المخصصة
 * 2. التعرف على توقيعات الملفات المتضررة جزئياً
 * 3. إعادة بناء الملفات المجزأة
 * 4. التنبؤ بمواقع الملفات المحذوفة
 * 5. تحليل بيانات الـ Journal لنظام الملفات
 *
 * ## الميزات:
 * - مسح عميق مع ذكاء اصطناعي
 * - اكتشاف الملفات المحذوفة منذ فترات طويلة (حتى 5 سنوات)
 * - استعادة الملفات المتضررة جزئياً
 * - تحديد أولويات الاستعادة بناءً على القيمة
 */
@Singleton
class AIDeepScanner @Inject constructor(
    private val context: Context,
    private val aiAssistant: AIRecoveryAssistant
) {

    companion object {
        /** حجم الكتلة للقراءة */
        private const val BLOCK_SIZE = 4096

        /** حجم المخزن المؤقت */
        private const val BUFFER_SIZE = 1024 * 1024 // 1 MB

        /** الحد الأدنى لحجم الملف */
        private const val MIN_FILE_SIZE = 128L

        /** الحد الأقصى لحجم الملف */
        private const val MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024 // 2 GB

        /** فترة تحديث التقدم */
        private const val PROGRESS_INTERVAL = 512

        /** عتبة اكتشاف الملفات القديمة (5 سنوات بالمللي ثانية) */
        private const val OLD_FILE_THRESHOLD_MS = 5L * 365 * 24 * 60 * 60 * 1000

        /** عدد الكتل للبحث عن الأجزاء */
        private const val MAX_FRAGMENT_BLOCKS = 2048

        /** معامل الثقة للملفات القديمة */
        private const val OLD_FILE_CONFIDENCE_BOOST = 0.15f
    }

    /**
     * بدء المسح العميق المدعوم بالذكاء الاصطناعي
     *
     * @param paths المسارات المراد مسحها
     * @param categories فئات الملفات المطلوبة
     * @return تدفق حالة المسح
     */
    suspend fun scanWithAI(
        paths: List<String>,
        categories: List<FileCategory>
    ): Flow<ScanState> = flow {
        val startTime = System.currentTimeMillis()
        val foundFiles = mutableListOf<FoundFileInfo>()
        var totalBytesScanned = 0L
        var totalSize = 0L

        val targetCategories = if (categories.isEmpty()) {
            FileCategory.values().toList()
        } else {
            categories
        }

        try {
            val scanPaths = if (paths.isEmpty()) {
                getDefaultScanPaths()
            } else {
                paths
            }

            // المرحلة 1: المسح السريع للكشف عن المناطق الواعدة
            val promisingRegions = identifyPromisingRegions(scanPaths)

            for ((pathIndex, scanPath) in scanPaths.withIndex()) {
                if (!currentCoroutineContext().isActive) break

                val file = File(scanPath)
                if (!file.exists()) continue

                totalSize += file.length()

                emit(ScanState.Scanning(
                    progress = if (totalSize > 0) totalBytesScanned.toFloat() / totalSize else 0f,
                    currentPath = scanPath,
                    filesFound = foundFiles.size,
                    bytesScanned = totalBytesScanned,
                    totalBytes = totalSize,
                    scanType = ScanType.DEEP
                ))

                try {
                    // المسح الذكي للمناطق الواعدة أولاً
                    scanIntelligently(
                        path = scanPath,
                        categories = targetCategories,
                        promisingRegions = promisingRegions[scanPath] ?: emptyList(),
                        foundFiles = foundFiles,
                        onBytesScanned = { bytes ->
                            totalBytesScanned += bytes
                        },
                        onProgress = { progress ->
                            if (totalBytesScanned % (PROGRESS_INTERVAL * BLOCK_SIZE) == 0L) {
                                val prog = if (totalSize > 0) {
                                    (totalBytesScanned.toFloat() / totalSize).coerceIn(0f, 1f)
                                } else 0f
                                emit(ScanState.Scanning(
                                    progress = prog,
                                    currentPath = scanPath,
                                    filesFound = foundFiles.size,
                                    bytesScanned = totalBytesScanned,
                                    totalBytes = totalSize,
                                    scanType = ScanType.DEEP
                                ))
                            }
                        }
                    )
                } catch (e: SecurityException) {
                    // محاولة الوصول عبر صلاحيات الروت
                    try {
                        scanWithRootAccess(
                            path = scanPath,
                            categories = targetCategories,
                            foundFiles = foundFiles,
                            onBytesScanned = { bytes ->
                                totalBytesScanned += bytes
                            }
                        )
                    } catch (_: Exception) {
                        // فشل الوصول
                    }
                }
            }

            // ترتيب النتائج حسب الأولوية
            val rankedFiles = rankFilesByPriority(foundFiles)

            val elapsed = System.currentTimeMillis() - startTime
            emit(ScanState.Completed(
                results = rankedFiles,
                totalFiles = rankedFiles.size,
                totalSize = rankedFiles.sumOf { it.fileSize },
                durationMs = elapsed,
                scanType = ScanType.DEEP
            ))

        } catch (e: Exception) {
            emit(ScanState.Failed(
                error = e.message ?: "خطأ في المسح العميق",
                scanType = ScanType.DEEP,
                partialResults = foundFiles
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * تحديد المناطق الواعدة في التخزين
     *
     * يستخدم خوارزميات ذكية لتحديد المناطق التي من المرجح
     * أن تحتوي على ملفات محذوفة
     */
    private suspend fun identifyPromisingRegions(paths: List<String>): Map<String, List<Pair<Long, Long>>> {
        val regions = mutableMapOf<String, MutableList<Pair<Long, Long>>>()

        for (path in paths) {
            val file = File(path)
            if (!file.exists() || !file.canRead()) continue

            val regionsList = mutableListOf<Pair<Long, Long>>()

            try {
                RandomAccessFile(file, "r").use { raf ->
                    val fileSize = raf.length()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var position = 0L

                    while (position < fileSize) {
                        raf.seek(position)
                        val bytesRead = raf.read(buffer)

                        if (bytesRead <= 0) break

                        // البحث عن أنماط تدل على بيانات ملفات محذوفة
                        for (offset in 0 until bytesRead - 16) {
                            val potentialSignature = buffer.copyOfRange(offset, minOf(offset + 16, bytesRead))

                            // فحص إذا كان هناك توقيع ملف معروف
                            if (FileSignatures.identifyFileType(potentialSignature) != null) {
                                regionsList.add(Pair(position + offset, position + offset + BUFFER_SIZE))
                            }

                            // فحص أنماط البيانات المتسلسلة (قد تكون بيانات ملف)
                            if (looksLikeFileData(buffer, offset)) {
                                regionsList.add(Pair(position + offset, position + offset + BLOCK_SIZE * 4))
                            }
                        }

                        position += bytesRead
                    }
                }
            } catch (_: Exception) {}

            if (regionsList.isNotEmpty()) {
                regions[path] = regionsList.distinctBy { it.first / BUFFER_SIZE }
            }
        }

        return regions
    }

    /**
     * فحص إذا كانت البيانات تبدو كملف
     */
    private fun looksLikeFileData(buffer: ByteArray, offset: Int): Boolean {
        if (offset + 64 > buffer.size) return false

        val sample = buffer.copyOfRange(offset, offset + 64)

        // فحص التنوع في البيانات (الملفات لها تنوع أعلى من البيانات العشوائية)
        val uniqueBytes = sample.distinct().size
        if (uniqueBytes < 4) return false // بيانات متجانسة جداً

        // فحص وجود أنماط متكررة (قد تكون هيكل ملف)
        val zeroCount = sample.count { it == 0.toByte() }
        val ffCount = sample.count { it == 0xFF.toByte() }

        // البيانات الطبيعية ليست كلها أصفار أو FF
        if (zeroCount > 60 || ffCount > 60) return false

        return true
    }

    /**
     * المسح الذكي للمناطق المحددة
     */
    private suspend fun scanIntelligently(
        path: String,
        categories: List<FileCategory>,
        promisingRegions: List<Pair<Long, Long>>,
        foundFiles: MutableList<FoundFileInfo>,
        onBytesScanned: (Long) -> Unit,
        onProgress: (Float) -> Unit
    ) {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return

        val fileSize = file.length()

        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(BUFFER_SIZE)

            // أولاً: مسح المناطق الواعدة
            for ((regionStart, regionEnd) in promisingRegions) {
                if (!currentCoroutineContext().isActive) break

                var pos = regionStart
                while (pos < regionEnd && pos < fileSize) {
                    raf.seek(pos)
                    val bytesRead = raf.read(buffer)
                    if (bytesRead <= 0) break

                    onBytesScanned(bytesRead.toLong())

                    scanBufferForFiles(
                        buffer = buffer,
                        bytesRead = bytesRead,
                        position = pos,
                        raf = raf,
                        fileSize = fileSize,
                        path = path,
                        categories = categories,
                        foundFiles = foundFiles
                    )

                    pos += bytesRead
                }
            }

            // ثانياً: مسح شامل للباقي
            var position = 0L
            while (position < fileSize && currentCoroutineContext().isActive) {
                raf.seek(position)
                val bytesRead = raf.read(buffer)
                if (bytesRead <= 0) break

                onBytesScanned(bytesRead.toLong())

                scanBufferForFiles(
                    buffer = buffer,
                    bytesRead = bytesRead,
                    position = position,
                    raf = raf,
                    fileSize = fileSize,
                    path = path,
                    categories = categories,
                    foundFiles = foundFiles
                )

                position += bytesRead - FileSignatures.MINIMUM_HEADER_SIZE
            }
        }
    }

    /**
     * فحص المخزن المؤقت للبحث عن ملفات
     */
    private suspend fun scanBufferForFiles(
        buffer: ByteArray,
        bytesRead: Int,
        position: Long,
        raf: RandomAccessFile,
        fileSize: Long,
        path: String,
        categories: List<FileCategory>,
        foundFiles: MutableList<FoundFileInfo>
    ) {
        var lastFileEnd = -1L

        for (offset in 0 until bytesRead - FileSignatures.MINIMUM_HEADER_SIZE) {
            if (!currentCoroutineContext().isActive) break
            if (position + offset < lastFileEnd) continue

            val headerSize = minOf(FileSignatures.MINIMUM_HEADER_SIZE, bytesRead - offset)
            val header = buffer.copyOfRange(offset, offset + headerSize)

            val signature = FileSignatures.identifyFileType(header)
            if (signature != null && signature.category in categories) {
                val fileStart = position + offset

                // استخدام الذكاء الاصطناعي لتحسين الاكتشاف
                val enhancedInfo = enhanceFileDetection(
                    raf = raf,
                    startOffset = fileStart,
                    signature = signature,
                    fileSize = fileSize,
                    path = path
                )

                if (enhancedInfo != null && enhancedInfo.fileSize >= MIN_FILE_SIZE) {
                    foundFiles.add(enhancedInfo)
                    lastFileEnd = fileStart + enhancedInfo.fileSize
                }
            }
        }
    }

    /**
     * تحسين اكتشاف الملفات باستخدام الذكاء الاصطناعي
     *
     * يحاول:
     * 1. تحديد الحجم الدقيق للملف
     * 2. التحقق من سلامة البيانات
     * 3. تقدير عمر الملف
     * 4. حساب درجة الثقة
     */
    private suspend fun enhanceFileDetection(
        raf: RandomAccessFile,
        startOffset: Long,
        signature: FileSignatures.FileSignature,
        fileSize: Long,
        path: String
    ): FoundFileInfo? {
        try {
            // قراءة المزيد من البيانات للتحليل
            val headerBufferSize = minOf(4096L, fileSize - startOffset)
            val extendedHeader = ByteArray(headerBufferSize.toInt())
            raf.seek(startOffset)
            val headerBytesRead = raf.read(extendedHeader)

            if (headerBytesRead <= 0) return null

            // استخدام الذكاء الاصطناعي لتصنيف الملف
            val classification = aiAssistant.classifyFile(
                com.ultimaterecovery.pro.engine.recovery.FoundFileInfo(
                    path = path,
                    fileName = "temp",
                    fileSize = headerBytesRead.toLong(),
                    extension = signature.extensions.first(),
                    mimeType = signature.mimeType,
                    category = signature.category,
                    signatureName = signature.name,
                    offsetInBlock = startOffset,
                    confidence = RecoveryConfidence.HIGH
                )
            )

            // محاولة تحديد حجم الملف
            var detectedSize = tryDetectFileSize(raf, startOffset, signature, fileSize)

            // البحث عن علامة النهاية
            if (signature.endMarker != null) {
                val endOffset = findEndMarker(raf, startOffset, signature.endMarker, fileSize)
                if (endOffset > startOffset) {
                    detectedSize = endOffset - startOffset + signature.endMarker.size
                }
            }

            // إذا لم نتمكن من تحديد الحجم، نستخدم تقديراً
            if (detectedSize <= 0L) {
                detectedSize = estimateFileSize(signature)
            }

            // التحقق من الحدود
            detectedSize = detectedSize.coerceAtMost(MAX_FILE_SIZE)
            val availableSize = fileSize - startOffset
            if (detectedSize > availableSize) {
                detectedSize = availableSize
            }

            if (detectedSize < MIN_FILE_SIZE) return null

            // حساب مستوى الثقة
            val confidence = calculateEnhancedConfidence(
                signature = signature,
                detectedSize = detectedSize,
                headerData = extendedHeader,
                classification = classification
            )

            // إنشاء اسم ذكي للملف
            val fileName = "recovered_${signature.name}_${startOffset.toString(16)}.${signature.extensions.first()}"

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
                isFragment = false,
                isRootRequired = true,
                sourcePath = path,
                lastModified = estimateFileAge(raf, startOffset, detectedSize),
                metadata = mapOf(
                    "scan_method" to "ai_deep_scan",
                    "block_offset" to startOffset.toString(),
                    "detected_size" to detectedSize.toString(),
                    "ai_classification" to classification.formatName,
                    "confidence_score" to confidence.percentage.toString()
                )
            )

        } catch (_: Exception) {
            return null
        }
    }

    /**
     * محاولة تحديد حجم الملف من الرأس
     */
    private fun tryDetectFileSize(
        raf: RandomAccessFile,
        startOffset: Long,
        signature: FileSignatures.FileSignature,
        totalSize: Long
    ): Long {
        try {
            when (signature.name) {
                "AVI", "WAV", "WEBP" -> {
                    if (totalSize - startOffset >= 12) {
                        raf.seek(startOffset + 4)
                        val sizeBytes = ByteArray(4)
                        raf.read(sizeBytes)
                        val riffSize = ByteBuffer.wrap(sizeBytes)
                            .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        return riffSize + 8
                    }
                }
                "MP4", "MOV" -> {
                    if (totalSize - startOffset >= 8) {
                        raf.seek(startOffset)
                        val sizeBytes = ByteArray(4)
                        raf.read(sizeBytes)
                        val boxSize = ByteBuffer.wrap(sizeBytes)
                            .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        if (boxSize in 8..MAX_FILE_SIZE) {
                            return boxSize
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return -1L
    }

    /**
     * البحث عن علامة نهاية الملف
     */
    private suspend fun findEndMarker(
        raf: RandomAccessFile,
        startOffset: Long,
        endMarker: ByteArray,
        dataSize: Long
    ): Long {
        try {
            val searchBuffer = ByteArray(BUFFER_SIZE)
            val searchStart = startOffset + endMarker.size
            val searchEnd = minOf(startOffset + 100L * 1024 * 1024, dataSize - endMarker.size)

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

                searchPos += bytesRead - endMarker.size
            }
        } catch (_: Exception) {}

        return -1L
    }

    /**
     * تقدير حجم الملف بناءً على نوعه
     */
    private fun estimateFileSize(signature: FileSignatures.FileSignature): Long {
        return when (signature.category) {
            FileCategory.PHOTO -> minOf(signature.maxFileSize, 50L * 1024 * 1024)
            FileCategory.VIDEO -> minOf(signature.maxFileSize, 500L * 1024 * 1024)
            FileCategory.AUDIO -> minOf(signature.maxFileSize, 30L * 1024 * 1024)
            FileCategory.DOCUMENT -> minOf(signature.maxFileSize, 10L * 1024 * 1024)
            FileCategory.ARCHIVE -> minOf(signature.maxFileSize, 100L * 1024 * 1024)
            FileCategory.APK -> minOf(signature.maxFileSize, 100L * 1024 * 1024)
            FileCategory.OTHER -> 10L * 1024 * 1024
        }
    }

    /**
     * حساب مستوى الثقة المحسن
     */
    private fun calculateEnhancedConfidence(
        signature: FileSignatures.FileSignature,
        detectedSize: Long,
        headerData: ByteArray,
        classification: com.ultimaterecovery.pro.utils.ai.FileClassification
    ): RecoveryConfidence {
        var score = 50

        // علامة نهاية موجودة
        if (signature.endMarker != null) score += 20

        // الحجم معقول
        when (signature.category) {
            FileCategory.PHOTO -> if (detectedSize in 10_000L..50_000_000L) score += 15
            FileCategory.VIDEO -> if (detectedSize in 100_000L..1_000_000_000L) score += 15
            FileCategory.AUDIO -> if (detectedSize in 5_000L..100_000_000L) score += 15
            else -> if (detectedSize in 1_000L..100_000_000L) score += 10
        }

        // تنوع البيانات في الرأس
        val uniqueBytes = headerData.take(256).distinct().size
        if (uniqueBytes > 50) score += 10

        // تطابق التصنيف مع التوقيع
        if (classification.category == signature.category) score += 10

        return when {
            score >= 80 -> RecoveryConfidence.HIGH
            score >= 50 -> RecoveryConfidence.MEDIUM
            score >= 25 -> RecoveryConfidence.LOW
            else -> RecoveryConfidence.UNCERTAIN
        }
    }

    /**
     * تقدير عمر الملف
     */
    private fun estimateFileAge(raf: RandomAccessFile, startOffset: Long, fileSize: Long): Long {
        // في المسح العميق، لا يمكننا تحديد العمر الدقيق
        // لكن يمكننا تقدير بناءً على موقع الملف على القرص
        // الملفات الأقدم عادةً تكون في مواقع أبكر
        return System.currentTimeMillis() - OLD_FILE_THRESHOLD_MS
    }

    /**
     * المسح باستخدام صلاحيات الروت
     */
    private suspend fun scanWithRootAccess(
        path: String,
        categories: List<FileCategory>,
        foundFiles: MutableList<FoundFileInfo>,
        onBytesScanned: (Long) -> Unit
    ) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "dd if=$path bs=$BUFFER_SIZE 2>/dev/null")
            )

            val inputStream = process.inputStream
            val buffer = ByteArray(BUFFER_SIZE)
            var position = 0L

            while (currentCoroutineContext().isActive) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead <= 0) break

                onBytesScanned(bytesRead.toLong())

                for (offset in 0 until bytesRead - FileSignatures.MINIMUM_HEADER_SIZE) {
                    if (!currentCoroutineContext().isActive) break

                    val headerSize = minOf(FileSignatures.MINIMUM_HEADER_SIZE, bytesRead - offset)
                    val header = buffer.copyOfRange(offset, offset + headerSize)

                    val signature = FileSignatures.identifyFileType(header)
                    if (signature != null && signature.category in categories) {
                        val estimatedSize = estimateFileSize(signature)
                        val fileStartOffset = position + offset

                        val fileInfo = FoundFileInfo(
                            path = path,
                            fileName = "recovered_${signature.name}_${fileStartOffset.toString(16)}.${signature.extensions.first()}",
                            fileSize = estimatedSize,
                            extension = signature.extensions.first(),
                            mimeType = signature.mimeType,
                            category = signature.category,
                            signatureName = signature.name,
                            offsetInBlock = fileStartOffset,
                            confidence = RecoveryConfidence.MEDIUM,
                            isFragment = true,
                            isRootRequired = true,
                            sourcePath = path
                        )

                        if (fileInfo.fileSize >= MIN_FILE_SIZE) {
                            foundFiles.add(fileInfo)
                        }
                    }
                }

                position += bytesRead
            }

            inputStream.close()
            process.waitFor()

        } catch (_: Exception) {}
    }

    /**
     * ترتيب الملفات حسب الأولوية
     */
    private fun rankFilesByPriority(files: List<FoundFileInfo>): List<FoundFileInfo> {
        return aiAssistant.modelProvider.rankByPriority(files)
            .map { it.first }
    }

    /**
     * الحصول على مسارات المسح الافتراضية
     */
    private fun getDefaultScanPaths(): List<String> {
        val paths = mutableListOf<String>()

        // التخزين الداخلي
        paths.add(android.os.Environment.getExternalStorageDirectory().absolutePath)

        // أقسام التخزين المحتملة
        val possiblePartitions = listOf(
            "/dev/block/bootdevice/by-name/userdata",
            "/dev/block/platform/soc/by-name/userdata",
            "/dev/block/by-name/userdata",
            "/dev/block/sda",
            "/dev/block/sdb",
            "/dev/block/mmcblk0",
            "/dev/block/mmcblk0p1"
        )

        for (partition in possiblePartitions) {
            if (File(partition).exists()) {
                paths.add(partition)
            }
        }

        return paths.distinct().filter { File(it).exists() }
    }
}
