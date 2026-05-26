package com.ultimaterecovery.pro.engine.scanner

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.engine.recovery.FoundFileInfo
import com.ultimaterecovery.pro.engine.recovery.RecoveryConfidence
import com.ultimaterecovery.pro.engine.signatures.FileSignatures
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * الماسح السريع - يبحث في نظام الملفات عن الملفات المحذوفة
 *
 * يستخدم هذا الماسح عدة استراتيجيات للعثور على الملفات المحذوفة بسرعة:
 *
 * 1. مسح الدلائل القياسية (DCIM, Pictures, Movies, Music, Downloads, Documents)
 * 2. فحص ذاكرة التخزين المؤقت للصور المصغرة (Thumbnail Cache)
 * 3. مسح الدلائل المخفية بملف .nomedia
 * 4. فحص مجلدات "المحذوفة مؤخراً" (Recently Deleted / Trash)
 * 5. مسح مجلدات تطبيقات المراسلة (WhatsApp, Telegram)
 * 6. فحص ملفات الوسائط المؤقتة والمخزنة مؤقتاً
 *
 * المسح السريع أسرع بكثير من المسح العميق لأنه يعتمد على
 * إدخالات نظام الملفات الموجودة بدلاً من قراءة الكتل الخام.
 */
@Singleton
class QuickScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** حجم رأس الملف المقروء للتعرف على النوع (512 بايت) */
        private const val HEADER_READ_SIZE = 512

        /** الحد الأدنى لحجم الملف المراد تضمينه (1 كيلوبايت) */
        private const val MIN_FILE_SIZE = 1024L

        /** أنماط أسماء مجلدات المحذوفات */
        private val TRASH_PATTERNS = listOf(
            ".Trash", ".trash", ".Trashes",
            "Trash", "trash", "Trashes",
            ".deleted", "deleted",
            ".recently_deleted", "recently_deleted",
            ".recent", "recent",
            "Recently Deleted", "RecentlyDeleted",
            ".Recycle", "Recycle", "\$RECYCLE.BIN",
            ".Trash-1000", ".Trash-1001" // Linux trash directories
        )

        /** أنماط مجلدات ذاكرة التخزين المؤقت للصور المصغرة */
        private val THUMBNAIL_DIRS = listOf(
            ".thumbnails", "thumbnails", ".thumbdata",
            "thumb", ".thumb"
        )

        /** أنماط دلائل التطبيقات الخاصة بالوسائط */
        private val APP_MEDIA_DIRS = listOf(
            "WhatsApp", "Telegram", "Viber", "Signal",
            "Instagram", "Snapchat", "Facebook",
            "Skype", "LINE", "KakaoTalk", "WeChat",
            "Discord", "Slack", "Microsoft Teams"
        )

        /** أنماط دلائل التخزين المؤقت */
        private val CACHE_PATTERNS = listOf(
            "cache", "Cache", ".cache",
            "temp", "Temp", ".temp", "tmp",
            "backup", "Backup", ".backup"
        )
    }

    /**
     * بدء المسح السريع
     *
     * يقوم بمسح شامل للمسارات المحددة مع التركيز على
     * الدلائل التي يحتمل وجود ملفات محذوفة فيها.
     *
     * @param paths قائمة المسارات المراد مسحها
     * @param categories فئات الملفات المراد البحث عنها (فارغ = جميع الفئات)
     * @return تدفق حالة المسح مع النتائج
     */
    fun scan(
        paths: List<String>,
        categories: List<FileCategory>
    ): Flow<ScanState> = flow {
        val startTime = System.currentTimeMillis()
        val foundFiles = mutableListOf<FoundFileInfo>()
        val scannedPaths = mutableSetOf<String>()
        var totalBytesScanned = 0L
        var directoriesScanned = 0

        // تحديد المسارات الافتراضية إذا لم يتم تحديد مسارات
        val scanPaths = if (paths.isEmpty()) getDefaultScanPaths() else paths

        // تحديد التوقيعات المطلوبة حسب الفئات
        val targetCategories = if (categories.isEmpty()) FileCategory.values().toList() else categories

        try {
            // ═══════════════════════════════════════════
            // المرحلة 1: مسح MediaStore (بدون حاجة للروت)
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.02f,
                currentPath = "مسح مكتبة الوسائط...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            scanMediaStore(foundFiles, targetCategories)

            // ═══════════════════════════════════════════
            // المرحلة 2: مسح الدلائل القياسية للوسائط
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.10f,
                currentPath = "مسح الدلائل القياسية...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            for (standardDir in getStandardMediaDirectories()) {
                val dir = File(standardDir)
                if (dir.exists() && dir.isDirectory) {
                    scanDirectory(dir, foundFiles, scannedPaths, targetCategories, maxDepth = 5)
                    directoriesScanned++
                }
            }

            // ═══════════════════════════════════════════
            // المرحلة 3: مسح مجلدات المحذوفات
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.25f,
                currentPath = "مسح مجلدات المحذوفات...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            for (trashDir in findTrashDirectories()) {
                val dir = File(trashDir)
                if (dir.exists() && dir.isDirectory) {
                    // ملفات المحذوفات لها أولوية عالية - يرجح أنها محذوفة حديثاً
                    scanDirectory(
                        dir, foundFiles, scannedPaths, targetCategories,
                        maxDepth = 4,
                        confidence = RecoveryConfidence.HIGH,
                        isLikelyDeleted = true
                    )
                    directoriesScanned++
                }
            }

            // ═══════════════════════════════════════════
            // المرحلة 4: مسح ذاكرة التخزين المؤقت للصور المصغرة
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.40f,
                currentPath = "مسح ذاكرة الصور المصغرة...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            for (thumbDir in findThumbnailDirectories()) {
                val dir = File(thumbDir)
                if (dir.exists() && dir.isDirectory) {
                    scanDirectory(
                        dir, foundFiles, scannedPaths, targetCategories,
                        maxDepth = 3,
                        confidence = RecoveryConfidence.MEDIUM,
                        isLikelyDeleted = true
                    )
                    directoriesScanned++
                }
            }

            // ═══════════════════════════════════════════
            // المرحلة 5: مسح الدلائل المخفية (.nomedia)
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.55f,
                currentPath = "مسح الدلائل المخفية...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            for (nomediaDir in findNomediaDirectories()) {
                val dir = File(nomediaDir)
                if (dir.exists() && dir.isDirectory) {
                    scanDirectory(
                        dir, foundFiles, scannedPaths, targetCategories,
                        maxDepth = 4,
                        confidence = RecoveryConfidence.MEDIUM,
                        isLikelyDeleted = false // قد تكون مقصودة الإخفاء
                    )
                    directoriesScanned++
                }
            }

            // ═══════════════════════════════════════════
            // المرحلة 6: مسح مجلدات تطبيقات المراسلة
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.70f,
                currentPath = "مسح مجلدات التطبيقات...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            for (appDir in findAppMediaDirectories()) {
                val dir = File(appDir)
                if (dir.exists() && dir.isDirectory) {
                    scanDirectory(
                        dir, foundFiles, scannedPaths, targetCategories,
                        maxDepth = 5,
                        confidence = RecoveryConfidence.MEDIUM
                    )
                    directoriesScanned++
                }
            }

            // ═══════════════════════════════════════════
            // المرحلة 7: مسح المسارات المحددة من المستخدم
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.80f,
                currentPath = "مسح المسارات المحددة...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            for (path in scanPaths) {
                val dir = File(path)
                if (dir.exists() && dir.isDirectory && path !in scannedPaths) {
                    scanDirectory(dir, foundFiles, scannedPaths, targetCategories, maxDepth = 6)
                    directoriesScanned++
                }
            }

            // ═══════════════════════════════════════════
            // المرحلة 8: مسح دلائل التخزين المؤقت
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.90f,
                currentPath = "مسح ذاكرة التخزين المؤقت...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            for (cacheDir in findCacheDirectories()) {
                val dir = File(cacheDir)
                if (dir.exists() && dir.isDirectory) {
                    scanDirectory(
                        dir, foundFiles, scannedPaths, targetCategories,
                        maxDepth = 4,
                        confidence = RecoveryConfidence.LOW,
                        isLikelyDeleted = true
                    )
                    directoriesScanned++
                }
            }

            // ═══════════════════════════════════════════
            // إزالة المكررات وإرسال النتيجة النهائية
            // ═══════════════════════════════════════════
            val uniqueFiles = foundFiles
                .distinctBy { it.path }
                .sortedByDescending { it.lastModified }

            val elapsed = System.currentTimeMillis() - startTime
            emit(ScanState.Completed(
                results = uniqueFiles,
                totalFiles = uniqueFiles.size,
                totalSize = uniqueFiles.sumOf { it.fileSize },
                durationMs = elapsed,
                scanType = ScanType.QUICK
            ))

        } catch (e: Exception) {
            emit(ScanState.Failed(
                error = e.message ?: "خطأ في المسح السريع",
                scanType = ScanType.QUICK,
                partialResults = foundFiles.distinctBy { it.path }
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * مسح دليل واحد بحثاً عن ملفات
     *
     * يعبر شجرة الملفات بشكل متكرر ويفحص كل ملف
     * لتحديد ما إذا كان ملف وسائط محذوفاً أو مخفياً
     *
     * @param directory الدليل المراد مسحه
     * @param foundFiles قائمة النتائج المراد إضافة الملفات إليها
     * @param scannedPaths مجموعة المسارات التي تم مسحها (لتجنب التكرار)
     * @param categories فئات الملفات المطلوبة
     * @param maxDepth أقصى عمق للمسح المتكرر
     * @param confidence مستوى الثقة الافتراضي للملفات المكتشفة
     * @param isLikelyDeleted هل الملفات في هذا الدليل يرجح أنها محذوفة؟
     */
    private suspend fun scanDirectory(
        directory: File,
        foundFiles: MutableList<FoundFileInfo>,
        scannedPaths: MutableSet<String>,
        categories: List<FileCategory>,
        maxDepth: Int = 5,
        confidence: RecoveryConfidence = RecoveryConfidence.MEDIUM,
        isLikelyDeleted: Boolean = false
    ) {
        if (maxDepth <= 0) return
        if (!directory.exists() || !directory.isDirectory) return
        if (directory.canonicalPath in scannedPaths) return
        if (!directory.canRead()) return

        scannedPaths.add(directory.canonicalPath)

        val files = directory.listFiles() ?: return

        for (file in files) {
            if (!currentCoroutineContext().isActive) return // فحص الإلغاء

            try {
                if (file.isDirectory) {
                    // المسح المتكرر في الدلائل الفرعية
                    scanDirectory(
                        file, foundFiles, scannedPaths, categories,
                        maxDepth - 1, confidence, isLikelyDeleted
                    )
                } else if (file.isFile && file.length() >= MIN_FILE_SIZE) {
                    // فحص الملف وتحديد نوعه
                    val fileInfo = identifyFile(file, categories, confidence, isLikelyDeleted)
                    if (fileInfo != null) {
                        foundFiles.add(fileInfo)
                    }
                }
            } catch (_: SecurityException) {
                // تجاهل الملفات التي لا يمكن قراءتها
            } catch (_: Exception) {
                // تجاهل الأخطاء الفردية واستكمال المسح
            }
        }
    }

    /**
     * التعرف على نوع الملف باستخدام التوقيعات
     *
     * يقرأ رأس الملف ويقارنه مع قاعدة بيانات التوقيعات
     * لتحديد نوع الملف الفعلي (قد يختلف عن الامتداد)
     *
     * @param file الملف المراد التعرف عليه
     * @param categories فئات الملفات المطلوبة
     * @param confidence مستوى الثقة
     * @param isLikelyDeleted هل يرجح أن الملف محذوف
     * @return معلومات الملف أو null إذا لم يكن من الأنواع المطلوبة
     */
    private fun identifyFile(
        file: File,
        categories: List<FileCategory>,
        confidence: RecoveryConfidence,
        isLikelyDeleted: Boolean
    ): FoundFileInfo? {
        try {
            val extension = file.extension.lowercase()
            val fileSize = file.length()

            // أولاً: محاولة التعرف بواسطة الامتداد (أسرع)
            var category = categorizeByExtension(extension)

            // ثانياً: التحقق بواسطة التوقيعات (أدق)
            val header = readFileHeader(file)
            val signature = if (header != null) {
                FileSignatures.identifyFileType(header, category)
            } else null

            // إذا وجدنا توقيعاً، نستخدم معلوماته
            if (signature != null) {
                category = signature.category
            }

            // التحقق من أن الفئة مطلوبة
            if (categories.isNotEmpty() && category !in categories) {
                return null
            }

            // التحقق من أن الملف قابل للاستعادة
            // الملفات في دلائل المحذوفات أو المخفية هي المرشحة الرئيسية
            val actualConfidence = when {
                isLikelyDeleted -> confidence
                file.isHidden -> RecoveryConfidence.MEDIUM
                else -> RecoveryConfidence.HIGH // ملف موجود ويمكن الوصول إليه
            }

            return FoundFileInfo(
                path = file.absolutePath,
                fileName = file.name,
                fileSize = fileSize,
                extension = extension,
                mimeType = signature?.mimeType ?: getMimeTypeForExtension(extension),
                category = category,
                signatureName = signature?.name,
                confidence = actualConfidence,
                lastModified = file.lastModified(),
                isRootRequired = false,
                sourcePath = file.absolutePath,
                metadata = buildMetadataMap(file, signature)
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * قراءة رأس الملف (البايتات الأولى)
     *
     * @param file الملف المراد قراءة رأسه
     * @return مصفوفة البايتات أو null في حالة الفشل
     */
    private fun readFileHeader(file: File): ByteArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(HEADER_READ_SIZE)
                val bytesRead = raf.read(header)
                if (bytesRead > 0) header.copyOf(bytesRead) else null
            }
        } catch (_: Exception) {
            try {
                FileInputStream(file).use { fis ->
                    val header = ByteArray(HEADER_READ_SIZE)
                    val bytesRead = fis.read(header)
                    if (bytesRead > 0) header.copyOf(bytesRead) else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * تصنيف الملف بناءً على الامتداد فقط (سريع لكن أقل دقة)
     */
    private fun categorizeByExtension(extension: String): FileCategory {
        return when (extension) {
            in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif",
                "tiff", "tif", "cr2", "nef", "arw", "dng", "orf", "rw2",
                "pef", "raf", "srw", "svg", "ico", "raw") -> FileCategory.PHOTO

            in setOf("mp4", "avi", "mkv", "mov", "flv", "wmv", "3gp", "3g2",
                "m4v", "mpg", "mpeg", "webm", "ts", "vob", "asf") -> FileCategory.VIDEO

            in setOf("mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus",
                "amr", "awb", "mid", "midi", "ape", "alac") -> FileCategory.AUDIO

            in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
                "txt", "rtf", "odt", "ods", "odp", "csv", "xml",
                "html", "htm", "json", "md") -> FileCategory.DOCUMENT

            in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz",
                "lzma", "cab", "iso", "dmg", "tgz") -> FileCategory.ARCHIVE

            "apk" -> FileCategory.APK

            else -> FileCategory.OTHER
        }
    }

    /**
     * الحصول على نوع MIME من الامتداد
     */
    private fun getMimeTypeForExtension(extension: String): String {
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "avi" -> "video/avi"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    /**
     * بناء خريطة البيانات الوصفية للملف
     */
    private fun buildMetadataMap(file: File, signature: FileSignatures.FileSignature?): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        metadata["file_name"] = file.name
        metadata["file_size"] = file.length().toString()
        metadata["last_modified"] = file.lastModified().toString()
        metadata["is_hidden"] = file.isHidden.toString()
        metadata["is_readable"] = file.canRead().toString()
        metadata["parent_dir"] = file.parent ?: ""
        signature?.let {
            metadata["signature_name"] = it.name
            metadata["detected_mime"] = it.mimeType
        }
        return metadata
    }

    // ──────────────────────────────────────────────
    // دوال اكتشاف الدلائل
    // ──────────────────────────────────────────────

    /**
     * الحصول على مسارات المسح الافتراضية
     */
    private fun getDefaultScanPaths(): List<String> {
        val paths = mutableListOf<String>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        // الدلائل القياسية
        paths.add("$externalStorage/DCIM")
        paths.add("$externalStorage/Pictures")
        paths.add("$externalStorage/Movies")
        paths.add("$externalStorage/Music")
        paths.add("$externalStorage/Downloads")
        paths.add("$externalStorage/Documents")
        paths.add("$externalStorage/Recordings")
        paths.add("$externalStorage/Audiobooks")
        paths.add("$externalStorage/Podcasts")
        paths.add("$externalStorage/Notifications")
        paths.add("$externalStorage/Ringtones")
        paths.add("$externalStorage/Alarms")

        return paths.filter { File(it).exists() }
    }

    /**
     * الحصول على دلائل الوسائط القياسية
     */
    private fun getStandardMediaDirectories(): List<String> {
        val dirs = mutableListOf<String>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        dirs.add("$externalStorage/DCIM/Camera")
        dirs.add("$externalStorage/DCIM/Screenshots")
        dirs.add("$externalStorage/DCIM/ScreenRecorder")
        dirs.add("$externalStorage/Pictures/Screenshots")
        dirs.add("$externalStorage/Pictures/Edit")
        dirs.add("$externalStorage/Movies")
        dirs.add("$externalStorage/Music")
        dirs.add("$externalStorage/Downloads")
        dirs.add("$externalStorage/Documents")

        // إضافة دلائل التخزين الثانوي (بطاقة SD)
        try {
            val externalDirs = context.getExternalFilesDirs(null)
            for (dir in externalDirs) {
                dir?.let {
                    val storageRoot = it.absolutePath.substringBefore("/Android")
                    dirs.add("$storageRoot/DCIM")
                    dirs.add("$storageRoot/Pictures")
                    dirs.add("$storageRoot/Movies")
                    dirs.add("$storageRoot/Music")
                    dirs.add("$storageRoot/Downloads")
                }
            }
        } catch (_: Exception) {}

        return dirs.filter { File(it).exists() }
    }

    /**
     * البحث عن مجلدات المحذوفات
     */
    private fun findTrashDirectories(): List<String> {
        val dirs = mutableListOf<String>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        // مجلدات المحذوفات في التخزين الرئيسي
        for (pattern in TRASH_PATTERNS) {
            dirs.add("$externalStorage/$pattern")
            dirs.add("$externalStorage/DCIM/$pattern")
            dirs.add("$externalStorage/Pictures/$pattern")
        }

        // مجلد محذوفات أندرويد الحديث (Android 11+)
        dirs.add("$externalStorage/Android/data/com.android.providers.media/trash")

        // مجلدات المحذوفات الخاصة بالتطبيقات
        dirs.add("$externalStorage/WhatsApp/.Trash")
        dirs.add("$externalStorage/WhatsApp/Media/.Trash")
        dirs.add("$externalStorage/Telegram/.Trash")

        // البحث عن مجلدات محذوفات غير معروفة
        try {
            val root = File(externalStorage)
            root.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    val name = file.name.lowercase()
                    if (TRASH_PATTERNS.any { pattern -> name.contains(pattern.lowercase()) }) {
                        dirs.add(file.absolutePath)
                    }
                }
            }
        } catch (_: Exception) {}

        return dirs.filter { File(it).exists() }
    }

    /**
     * البحث عن دلائل الصور المصغرة
     */
    private fun findThumbnailDirectories(): List<String> {
        val dirs = mutableListOf<String>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        for (pattern in THUMBNAIL_DIRS) {
            dirs.add("$externalStorage/DCIM/$pattern")
            dirs.add("$externalStorage/Pictures/$pattern")
        }

        // دلائل الصور المصغرة الخاصة بأندرويد
        try {
            val thumbDataDir = File("$externalStorage/DCIM/.thumbnails")
            if (thumbDataDir.exists()) {
                dirs.add(thumbDataDir.absolutePath)
            }
        } catch (_: Exception) {}

        return dirs.filter { File(it).exists() }
    }

    /**
     * البحث عن الدلائل التي تحتوي على ملف .nomedia
     * هذه الدلائل مخفية من معرض الصور وقد تحتوي على ملفات مهمة
     */
    private fun findNomediaDirectories(): List<String> {
        val dirs = mutableListOf<String>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        fun searchForNomedia(root: File, depth: Int = 0) {
            if (depth > 4) return
            if (!root.exists() || !root.isDirectory || !root.canRead()) return

            try {
                root.listFiles()?.forEach { file ->
                    if (file.isDirectory) {
                        val nomediaFile = File(file, ".nomedia")
                        if (nomediaFile.exists()) {
                            dirs.add(file.absolutePath)
                        }
                        searchForNomedia(file, depth + 1)
                    }
                }
            } catch (_: Exception) {}
        }

        searchForNomedia(File(externalStorage))
        return dirs.distinct()
    }

    /**
     * البحث عن دلائل وسائط التطبيقات
     */
    private fun findAppMediaDirectories(): List<String> {
        val dirs = mutableListOf<String>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        for (app in APP_MEDIA_DIRS) {
            // WhatsApp
            if (app == "WhatsApp") {
                dirs.add("$externalStorage/WhatsApp/Media")
                dirs.add("$externalStorage/WhatsApp/Media/WhatsApp Images")
                dirs.add("$externalStorage/WhatsApp/Media/WhatsApp Video")
                dirs.add("$externalStorage/WhatsApp/Media/WhatsApp Audio")
                dirs.add("$externalStorage/WhatsApp/Media/WhatsApp Documents")
                dirs.add("$externalStorage/WhatsApp/Media/WhatsApp Animated Gifs")
                dirs.add("$externalStorage/WhatsApp/Media/WhatsApp Voice Notes")
                dirs.add("$externalStorage/WhatsApp Business/Media")
                dirs.add("$externalStorage/WhatsApp/Media/WallPaper")
            }
            // Telegram
            else if (app == "Telegram") {
                dirs.add("$externalStorage/Telegram/Telegram Images")
                dirs.add("$externalStorage/Telegram/Telegram Video")
                dirs.add("$externalStorage/Telegram/Telegram Audio")
                dirs.add("$externalStorage/Telegram/Telegram Documents")
                dirs.add("$externalStorage/Telegram/Telegram Animated Gifs")
                dirs.add("$externalStorage/Telegram/Telegram Stickers")
            }
            // تطبيقات أخرى
            else {
                dirs.add("$externalStorage/$app/Media")
                dirs.add("$externalStorage/$app/$app Media")
                dirs.add("$externalStorage/$app/Images")
                dirs.add("$externalStorage/$app/Videos")
            }
        }

        return dirs.filter { File(it).exists() }
    }

    /**
     * البحث عن دلائل التخزين المؤقت
     */
    private fun findCacheDirectories(): List<String> {
        val dirs = mutableListOf<String>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        // دلائل التخزين المؤقت للتطبيقات
        try {
            val androidData = File("$externalStorage/Android/data")
            if (androidData.exists() && androidData.isDirectory) {
                androidData.listFiles()?.forEach { appDir ->
                    if (appDir.isDirectory && appDir.canRead()) {
                        for (cachePattern in CACHE_PATTERNS) {
                            val cacheDir = File(appDir, cachePattern)
                            if (cacheDir.exists() && cacheDir.isDirectory) {
                                dirs.add(cacheDir.absolutePath)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // دلائل التخزين المؤقت الخاصة بالنظام
        try {
            dirs.add(context.cacheDir.absolutePath)
            context.externalCacheDir?.let { dirs.add(it.absolutePath) }
        } catch (_: Exception) {}

        return dirs.filter { File(it).exists() }.distinct()
    }

    // ──────────────────────────────────────────────
    // MediaStore API (بدون حاجة للروت)
    // ──────────────────────────────────────────────

    /**
     * مسح MediaStore للوصول إلى ملفات الوسائط بدون حاجة للروت
     *
     * يستخدم ContentResolver للوصول إلى جميع ملفات الوسائط
     * المسجلة في قاعدة بيانات النظام
     *
     * @param foundFiles قائمة النتائج
     * @param categories فئات الملفات المطلوبة
     */
    private fun scanMediaStore(
        foundFiles: MutableList<FoundFileInfo>,
        categories: List<FileCategory>
    ) {
        // مسح الصور
        if (categories.isEmpty() || FileCategory.PHOTO in categories) {
            scanMediaStoreCollection(
                uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.MIME_TYPE
                ),
                category = FileCategory.PHOTO,
                foundFiles = foundFiles
            )
        }

        // مسح الفيديوهات
        if (categories.isEmpty() || FileCategory.VIDEO in categories) {
            scanMediaStoreCollection(
                uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.MIME_TYPE
                ),
                category = FileCategory.VIDEO,
                foundFiles = foundFiles
            )
        }

        // مسح الملفات الصوتية
        if (categories.isEmpty() || FileCategory.AUDIO in categories) {
            scanMediaStoreCollection(
                uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.MIME_TYPE
                ),
                category = FileCategory.AUDIO,
                foundFiles = foundFiles
            )
        }

        // مسح الملفات المحملة (قد تحتوي على مستندات)
        if (categories.isEmpty() || FileCategory.DOCUMENT in categories) {
            scanDownloadsCollection(foundFiles, categories)
        }
    }

    /**
     * مسح مجموعة من MediaStore
     */
    private fun scanMediaStoreCollection(
        uri: Uri,
        projection: Array<String>,
        category: FileCategory,
        foundFiles: MutableList<FoundFileInfo>
    ) {
        try {
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val mimeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                while (it.moveToNext()) {
                    try {
                        val path = it.getString(dataColumn)
                        val name = it.getString(nameColumn)
                        val size = it.getLong(sizeColumn)
                        val date = it.getLong(dateColumn) * 1000 // تحويل إلى milliseconds
                        val mimeType = it.getString(mimeColumn)

                        // التحقق من وجود الملف
                        val file = File(path)
                        if (!file.exists() || file.length() < MIN_FILE_SIZE) continue

                        // التحقق من عدم وجود الملف مسبقاً
                        if (foundFiles.any { f -> f.path == path }) continue

                        val extension = file.extension.lowercase()

                        foundFiles.add(FoundFileInfo(
                            path = path,
                            fileName = name,
                            fileSize = size,
                            extension = extension,
                            mimeType = mimeType ?: getMimeTypeForExtension(extension),
                            category = category,
                            signatureName = null,
                            confidence = RecoveryConfidence.HIGH,
                            lastModified = date,
                            isRootRequired = false,
                            sourcePath = path,
                            metadata = mapOf(
                                "source" to "mediastore",
                                "file_name" to name,
                                "file_size" to size.toString()
                            )
                        ))
                    } catch (_: Exception) {
                        // تجاهل الملفات ذات المشاكل
                    }
                }
            }
        } catch (_: Exception) {
            // تجاهل أخطاء MediaStore
        }
    }

    /**
     * مسح مجلد التحميلات عبر MediaStore
     */
    private fun scanDownloadsCollection(
        foundFiles: MutableList<FoundFileInfo>,
        categories: List<FileCategory>
    ) {
        try {
            // مسح ملفات التحميلات
            val downloadsUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                // للإصدارات الأقدم، نستخدم مسار التحميلات مباشرة
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir.exists() && downloadsDir.isDirectory) {
                    scanDirectory(
                        downloadsDir,
                        foundFiles,
                        mutableSetOf(),
                        categories,
                        maxDepth = 3
                    )
                }
                return
            }

            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DATA,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED,
                MediaStore.Downloads.MIME_TYPE
            )

            val cursor: Cursor? = context.contentResolver.query(
                downloadsUri,
                projection,
                null,
                null,
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val dataColumn = it.getColumnIndexOrThrow(MediaStore.Downloads.DATA)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateColumn = it.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                val mimeColumn = it.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)

                while (it.moveToNext()) {
                    try {
                        val path = it.getString(dataColumn)
                        val name = it.getString(nameColumn)
                        val size = it.getLong(sizeColumn)
                        val date = it.getLong(dateColumn) * 1000
                        val mimeType = it.getString(mimeColumn)

                        val file = File(path)
                        if (!file.exists() || file.length() < MIN_FILE_SIZE) continue
                        if (foundFiles.any { f -> f.path == path }) continue

                        val extension = file.extension.lowercase()
                        val category = categorizeByExtension(extension)

                        // فقط الملفات من الفئات المطلوبة
                        if (categories.isNotEmpty() && category !in categories) continue

                        foundFiles.add(FoundFileInfo(
                            path = path,
                            fileName = name,
                            fileSize = size,
                            extension = extension,
                            mimeType = mimeType ?: getMimeTypeForExtension(extension),
                            category = category,
                            signatureName = null,
                            confidence = RecoveryConfidence.HIGH,
                            lastModified = date,
                            isRootRequired = false,
                            sourcePath = path,
                            metadata = mapOf(
                                "source" to "downloads",
                                "file_name" to name,
                                "file_size" to size.toString()
                            )
                        ))
                    } catch (_: Exception) {
                        // تجاهل الملفات ذات المشاكل
                    }
                }
            }
        } catch (_: Exception) {
            // تجاهل أخطاء Downloads
        }
    }
}
