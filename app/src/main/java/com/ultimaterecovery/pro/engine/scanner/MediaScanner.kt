package com.ultimaterecovery.pro.engine.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Environment
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.engine.recovery.FoundFileInfo
import com.ultimaterecovery.pro.engine.recovery.RecoveryConfidence
import com.ultimaterecovery.pro.engine.signatures.FileSignatures
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ماسح الوسائط المتخصص - مخصص للصور والفيديو والصوت
 *
 * هذا الماسح مُحسَّن خصيصاً لاستعادة ملفات الوسائط (الصور والفيديو والصوت)
 * ويتميز عن الماسح السريع العام بما يلي:
 *
 * 1. استخراج الصور المصغرة (Thumbnails) حتى لو كان الملف الأصلي تالفاً
 * 2. قراءة بيانات EXIF من الصور (التاريخ، الموقع، الكاميرا...)
 * 3. إنشاء صور مصغرة للفيديو من الإطارات
 * 4. استخراج البيانات الوصفية للصوت (الفنان، الألبوم، المدة...)
 * 5. مسح مجلدات تطبيقات المراسلة (WhatsApp, Telegram, Signal...)
 * 6. فحص ذاكرة التخزين المؤقت الخاصة بالوسائط
 * 7. التعامل مع ملفات الوسائط المؤقتة وغير المكتملة
 *
 * كيف يعمل:
 * ┌────────────────────────────────────────────────────┐
 * │ المرحلة 1: مسح دلائل الوسائط القياسية             │
 * │ المرحلة 2: مسح دلائل تطبيقات المراسلة             │
 * │ المرحلة 3: فحص ذاكرة التخزين المؤقت للوسائط       │
 * │ المرحلة 4: استخراج البيانات الوصفية                │
 * │ المرحلة 5: إنشاء الصور المصغرة                     │
 * │ المرحلة 6: تصنيف النتائج وتقدير الثقة             │
 * └────────────────────────────────────────────────────┘
 */
@Singleton
class MediaScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** الحد الأدنى لحجم الصورة (1 كيلوبايت) */
        private const val MIN_IMAGE_SIZE = 1024L

        /** الحد الأدنى لحجم الفيديو (10 كيلوبايت) */
        private const val MIN_VIDEO_SIZE = 10 * 1024L

        /** الحد الأدنى لحجم الصوت (5 كيلوبايت) */
        private const val MIN_AUDIO_SIZE = 5 * 1024L

        /** حجم رأس الملف للتعرف على النوع */
        private const val HEADER_SIZE = 512

        /** حجم الصورة المصغرة */
        private const val THUMBNAIL_WIDTH = 256
        private const val THUMBNAIL_HEIGHT = 256

        /** دليل الصور المصغرة */
        private const val THUMBNAIL_DIR_NAME = ".thumbnails"
    }

    // ═══════════════════════════════════════════════════════════════
    // مسح الوسائط
    // ═══════════════════════════════════════════════════════════════

    /**
     * مسح شامل لملفات الوسائط
     *
     * يبحث عن الصور والفيديو والصوت المحذوف في جميع
     * المواقع المحتملة مع استخراج البيانات الوصفية والصور المصغرة
     *
     * @param categories فئات الوسائط المراد البحث عنها
     * @return تدفق حالة المسح
     */
    fun scanMedia(categories: List<FileCategory> = listOf(FileCategory.PHOTO, FileCategory.VIDEO, FileCategory.AUDIO)): Flow<ScanState> = flow {
        val startTime = System.currentTimeMillis()
        val foundFiles = mutableListOf<FoundFileInfo>()

        val targetCategories = if (categories.isEmpty()) {
            listOf(FileCategory.PHOTO, FileCategory.VIDEO, FileCategory.AUDIO)
        } else {
            categories.filter { it in listOf(FileCategory.PHOTO, FileCategory.VIDEO, FileCategory.AUDIO) }
        }

        if (targetCategories.isEmpty()) {
            emit(ScanState.Completed(
                results = emptyList(),
                totalFiles = 0,
                totalSize = 0L,
                durationMs = 0L,
                scanType = ScanType.QUICK
            ))
            return@flow
        }

        try {
            // ═══════════════════════════════════════════
            // المرحلة 1: مسح دلائل الوسائط القياسية
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.1f,
                currentPath = "مسح دلائل الوسائط...",
                filesFound = 0,
                scanType = ScanType.QUICK
            ))

            val mediaDirs = getMediaDirectories()
            for (dir in mediaDirs) {
                if (!currentCoroutineContext().isActive) break
                scanMediaDirectory(dir, foundFiles, targetCategories, maxDepth = 5)
            }

            // ═══════════════════════════════════════════
            // المرحلة 2: مسح دلائل تطبيقات المراسلة
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.3f,
                currentPath = "مسح تطبيقات المراسلة...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            val messagingDirs = getMessagingAppDirectories()
            for (dir in messagingDirs) {
                if (!currentCoroutineContext().isActive) break
                scanMediaDirectory(
                    dir, foundFiles, targetCategories,
                    maxDepth = 5,
                    isAppSpecific = true
                )
            }

            // ═══════════════════════════════════════════
            // المرحلة 3: فحص ذاكرة التخزين المؤقت للوسائط
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.5f,
                currentPath = "فحص ذاكرة الوسائط المؤقتة...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            val cacheDirs = getMediaCacheDirectories()
            for (dir in cacheDirs) {
                if (!currentCoroutineContext().isActive) break
                scanMediaDirectory(
                    dir, foundFiles, targetCategories,
                    maxDepth = 4,
                    confidence = RecoveryConfidence.LOW
                )
            }

            // ═══════════════════════════════════════════
            // المرحلة 4: مسح الصور المصغرة المخزنة
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.7f,
                currentPath = "فحص الصور المصغرة...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            val thumbnailDirs = getThumbnailDirectories()
            for (dir in thumbnailDirs) {
                if (!currentCoroutineContext().isActive) break
                scanThumbnailsDirectory(dir, foundFiles)
            }

            // ═══════════════════════════════════════════
            // المرحلة 5: مسح مجلدات المحذوفات
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.85f,
                currentPath = "مسح المحذوفات...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            val trashDirs = getTrashDirectories()
            for (dir in trashDirs) {
                if (!currentCoroutineContext().isActive) break
                scanMediaDirectory(
                    dir, foundFiles, targetCategories,
                    maxDepth = 4,
                    confidence = RecoveryConfidence.HIGH,
                    isLikelyDeleted = true
                )
            }

            // ═══════════════════════════════════════════
            // المرحلة 6: استخراج البيانات الوصفية والصور المصغرة
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.95f,
                currentPath = "استخراج البيانات الوصفية...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            // استخراج البيانات الوصفية لكل ملف
            val enrichedFiles = foundFiles.map { fileInfo ->
                enrichWithMetadata(fileInfo)
            }

            // إزالة المكررات والإرسال النهائي
            val uniqueFiles = enrichedFiles
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
                error = e.message ?: "خطأ في مسح الوسائط",
                scanType = ScanType.QUICK,
                partialResults = foundFiles
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * مسح مخصص لاستعادة الصور المحذوفة
     *
     * يركز فقط على الصور مع استخراج بيانات EXIF
     * والصور المصغرة
     *
     * @return تدفق حالة المسح
     */
    fun scanDeletedPhotos(): Flow<ScanState> = flow {
        scanMedia(listOf(FileCategory.PHOTO)).collect { state ->
            emit(state)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * مسح مخصص لاستعادة الفيديوهات المحذوفة
     *
     * يركز فقط على الفيديو مع إنشاء صور مصغرة من الإطارات
     *
     * @return تدفق حالة المسح
     */
    fun scanDeletedVideos(): Flow<ScanState> = flow {
        scanMedia(listOf(FileCategory.VIDEO)).collect { state ->
            emit(state)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * مسح مخصص لاستعادة الملفات الصوتية المحذوفة
     *
     * @return تدفق حالة المسح
     */
    fun scanDeletedAudio(): Flow<ScanState> = flow {
        scanMedia(listOf(FileCategory.AUDIO)).collect { state ->
            emit(state)
        }
    }.flowOn(Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════
    // مسح الدلائل
    // ═══════════════════════════════════════════════════════════════

    /**
     * مسح دليل الوسائط
     *
     * يعبر شجرة الدليل ويبحث عن ملفات الوسائط
     * مع تصنيفها واستخراج معلوماتها
     *
     * @param directory الدليل المراد مسحه
     * @param foundFiles قائمة النتائج
     * @param categories فئات الملفات المطلوبة
     * @param maxDepth أقصى عمق للمسح
     * @param confidence مستوى الثقة
     * @param isLikelyDeleted هل الملفات يرجح أنها محذوفة
     * @param isAppSpecific هل الدليل خاص بتطبيق
     */
    private suspend fun scanMediaDirectory(
        directory: File,
        foundFiles: MutableList<FoundFileInfo>,
        categories: List<FileCategory>,
        maxDepth: Int = 5,
        confidence: RecoveryConfidence = RecoveryConfidence.MEDIUM,
        isLikelyDeleted: Boolean = false,
        isAppSpecific: Boolean = false
    ) {
        if (maxDepth <= 0) return
        if (!directory.exists() || !directory.isDirectory || !directory.canRead()) return

        try {
            directory.listFiles()?.forEach { file ->
                if (!currentCoroutineContext().isActive) return

                try {
                    if (file.isDirectory) {
                        // تخطي دلائل النظام والدلائل المخفية غير المهمة
                        val name = file.name
                        if (name == "Android" || name == ".android_secure") return@forEach

                        scanMediaDirectory(
                            file, foundFiles, categories,
                            maxDepth - 1, confidence, isLikelyDeleted, isAppSpecific
                        )
                    } else if (file.isFile) {
                        // فحص نوع الملف وحجمه
                        val category = categorizeMediaFile(file)
                        if (category != null && category in categories) {
                            val minSize = when (category) {
                                FileCategory.PHOTO -> MIN_IMAGE_SIZE
                                FileCategory.VIDEO -> MIN_VIDEO_SIZE
                                FileCategory.AUDIO -> MIN_AUDIO_SIZE
                                else -> 1024L
                            }

                            if (file.length() >= minSize) {
                                val fileInfo = createMediaFileInfo(
                                    file = file,
                                    category = category,
                                    confidence = confidence,
                                    isLikelyDeleted = isLikelyDeleted,
                                    isAppSpecific = isAppSpecific
                                )
                                foundFiles.add(fileInfo)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // تجاهل أخطاء الملفات الفردية
                }
            }
        } catch (_: SecurityException) {
            // لا صلاحية وصول
        }
    }

    /**
     * مسح دليل الصور المصغرة
     *
     * الصور المصغرة قد تكون المصدر الوحيد لاستعادة الصور
     * المحذوفة إذا تم حذف الملفات الأصلية
     *
     * @param directory دليل الصور المصغرة
     * @param foundFiles قائمة النتائج
     */
    private suspend fun scanThumbnailsDirectory(
        directory: File,
        foundFiles: MutableList<FoundFileInfo>
    ) {
        if (!directory.exists() || !directory.isDirectory || !directory.canRead()) return

        try {
            directory.listFiles()?.forEach { file ->
                if (!currentCoroutineContext().isActive) return

                try {
                    if (file.isFile && file.length() >= MIN_IMAGE_SIZE) {
                        // التحقق من أن الملف صورة مصغرة فعلية
                        val header = readFileHeader(file)
                        val signature = header?.let { FileSignatures.identifyFileType(it) }

                        if (signature != null && signature.category == FileCategory.PHOTO) {
                            val fileInfo = FoundFileInfo(
                                path = file.absolutePath,
                                fileName = "thumb_${file.name}",
                                fileSize = file.length(),
                                extension = signature.extensions.first(),
                                mimeType = signature.mimeType,
                                category = FileCategory.PHOTO,
                                signatureName = signature.name,
                                confidence = RecoveryConfidence.LOW, // صورة مصغرة = ثقة منخفضة
                                thumbnailPath = file.absolutePath, // هي نفسها الصورة المصغرة
                                lastModified = file.lastModified(),
                                isRootRequired = false,
                                sourcePath = file.absolutePath,
                                metadata = mapOf(
                                    "is_thumbnail" to "true",
                                    "thumbnail_source" to directory.name
                                )
                            )
                            foundFiles.add(fileInfo)
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // استخراج البيانات الوصفية
    // ═══════════════════════════════════════════════════════════════

    /**
     * إثراء معلومات الملف بالبيانات الوصفية
     *
     * يحاول استخراج بيانات إضافية حسب نوع الملف:
     * - الصور: EXIF data (التاريخ، الموقع، الكاميرا، الأبعاد)
     * - الفيديو: المدة، الدقة، الترميز
     * - الصوت: الفنان، الألبوم، المدة، معدل العينات
     *
     * @param fileInfo معلومات الملف الأصلية
     * @return معلومات الملف المُثراة بالبيانات الوصفية
     */
    private fun enrichWithMetadata(fileInfo: FoundFileInfo): FoundFileInfo {
        val metadata = fileInfo.metadata.toMutableMap()

        try {
            when (fileInfo.category) {
                FileCategory.PHOTO -> {
                    // استخراج أبعاد الصورة وبيانات EXIF
                    extractImageMetadata(fileInfo, metadata)
                }
                FileCategory.VIDEO -> {
                    // استخراج معلومات الفيديو
                    extractVideoMetadata(fileInfo, metadata)
                }
                FileCategory.AUDIO -> {
                    // استخراج معلومات الصوت
                    extractAudioMetadata(fileInfo, metadata)
                }
                else -> {}
            }
        } catch (_: Exception) {
            // فشل استخراج البيانات الوصفية - لا نوقف العملية
        }

        return fileInfo.copy(metadata = metadata)
    }

    /**
     * استخراج البيانات الوصفية للصور
     *
     * يقرأ أبعاد الصورة ومعلومات EXIF الأساسية:
     * - الأبعاد (العرض × الارتفاع)
     * - تاريخ الالتقاط (من EXIF إن وُجد)
     * - الشركة المصنعة للكاميرا
     * - الطراز
     * - إحداثيات GPS
     * - الاتجاه (Orientation)
     *
     * @param fileInfo معلومات الملف
     * @param metadata خريطة البيانات الوصفية (تُعدَّل مباشرة)
     */
    private fun extractImageMetadata(fileInfo: FoundFileInfo, metadata: MutableMap<String, String>) {
        val file = File(fileInfo.path)
        if (!file.exists() || !file.canRead()) return

        try {
            // استخدام BitmapFactory لقراءة الأبعاد فقط (كفاءة عالية)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true // لا نحمّل الصورة بالكامل
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            if (options.outWidth > 0 && options.outHeight > 0) {
                metadata["width"] = options.outWidth.toString()
                metadata["height"] = options.outHeight.toString()
                metadata["aspect_ratio"] = String.format("%.2f", options.outWidth.toFloat() / options.outHeight.toFloat())
            }

            if (!options.outMimeType.isNullOrEmpty()) {
                metadata["detected_mime"] = options.outMimeType
            }

            // محاولة استخراج بيانات EXIF باستخدام ExifInterface
            try {
                val exif = android.media.ExifInterface(file.absolutePath)

                // تاريخ الالتقاط
                val datetime = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME)
                if (!datetime.isNullOrEmpty()) {
                    metadata["date_taken"] = datetime
                }

                // الكاميرا
                val make = exif.getAttribute(android.media.ExifInterface.TAG_MAKE)
                if (!make.isNullOrEmpty()) {
                    metadata["camera_make"] = make
                }

                val model = exif.getAttribute(android.media.ExifInterface.TAG_MODEL)
                if (!model.isNullOrEmpty()) {
                    metadata["camera_model"] = model
                }

                // الاتجاه
                val orientation = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
                metadata["orientation"] = orientation.toString()

                // GPS
                val latLongArray = FloatArray(2)
                val hasLatLong = exif.getLatLong(latLongArray)
                if (hasLatLong) {
                    metadata["gps_latitude"] = latLongArray[0].toString()
                    metadata["gps_longitude"] = latLongArray[1].toString()
                }

                // حجم الصورة الأصلي
                val imageWidth = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_IMAGE_WIDTH, -1
                )
                val imageHeight = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_IMAGE_LENGTH, -1
                )
                if (imageWidth > 0 && imageHeight > 0) {
                    metadata["exif_width"] = imageWidth.toString()
                    metadata["exif_height"] = imageHeight.toString()
                }

                // الفلاش
                val flash = exif.getAttribute(android.media.ExifInterface.TAG_FLASH)
                if (!flash.isNullOrEmpty()) {
                    metadata["flash"] = flash
                }

            } catch (_: Exception) {
                // EXIF غير متوفر أو تالف
            }

        } catch (_: Exception) {
            // فشل قراءة الصورة
        }
    }

    /**
     * استخراج البيانات الوصفية للفيديو
     *
     * يستخدم MediaMetadataRetriever لاستخراج:
     * - المدة
     * - الدقة (العرض × الارتفاع)
     * - معدل الإطارات
     * - الترميز
     *
     * @param fileInfo معلومات الملف
     * @param metadata خريطة البيانات الوصفية
     */
    private fun extractVideoMetadata(fileInfo: FoundFileInfo, metadata: MutableMap<String, String>) {
        val file = File(fileInfo.path)
        if (!file.exists() || !file.canRead()) return

        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)

            // المدة
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (!duration.isNullOrEmpty()) {
                metadata["duration_ms"] = duration
                val durationSec = duration.toLongOrNull()?.div(1000) ?: 0L
                metadata["duration_formatted"] = formatDuration(durationSec)
            }

            // الدقة
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (!width.isNullOrEmpty()) metadata["video_width"] = width
            if (!height.isNullOrEmpty()) metadata["video_height"] = height

            // معدل الإطارات
            val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            if (!frameRate.isNullOrEmpty()) metadata["frame_rate"] = frameRate

            // تاريخ الإنشاء
            val date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            if (!date.isNullOrEmpty()) metadata["date_created"] = date

            // الترميز
            val videoCodec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            if (!videoCodec.isNullOrEmpty()) metadata["video_codec"] = videoCodec

            // الدوران
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            if (!rotation.isNullOrEmpty()) metadata["rotation"] = rotation

            // محاولة إنشاء صورة مصغرة من إطار
            try {
                val thumbnail = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (thumbnail != null) {
                    val thumbPath = saveThumbnail(thumbnail, fileInfo.fileName)
                    if (thumbPath != null) {
                        metadata["has_video_thumbnail"] = "true"
                    }
                    thumbnail.recycle()
                }
            } catch (_: Exception) {}

        } catch (_: Exception) {
            // فشل استخراج البيانات الوصفية
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * استخراج البيانات الوصفية للصوت
     *
     * يستخدم MediaMetadataRetriever لاستخراج:
     * - الفنان
     * - الألبوم
     * - العنوان
     * - المدة
     * - رقم المسار
     * - سنة الإصدار
     *
     * @param fileInfo معلومات الملف
     * @param metadata خريطة البيانات الوصفية
     */
    private fun extractAudioMetadata(fileInfo: FoundFileInfo, metadata: MutableMap<String, String>) {
        val file = File(fileInfo.path)
        if (!file.exists() || !file.canRead()) return

        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)

            // الفنان
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            if (!artist.isNullOrEmpty()) metadata["artist"] = artist

            // الألبوم
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            if (!album.isNullOrEmpty()) metadata["album"] = album

            // العنوان
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            if (!title.isNullOrEmpty()) metadata["title"] = title

            // المدة
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (!duration.isNullOrEmpty()) {
                metadata["duration_ms"] = duration
                val durationSec = duration.toLongOrNull()?.div(1000) ?: 0L
                metadata["duration_formatted"] = formatDuration(durationSec)
            }

            // رقم المسار
            val track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            if (!track.isNullOrEmpty()) metadata["track_number"] = track

            // سنة الإصدار
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            if (!year.isNullOrEmpty()) metadata["year"] = year

            // النوع
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            if (!genre.isNullOrEmpty()) metadata["genre"] = genre

            // معدل البت
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            if (!bitrate.isNullOrEmpty()) metadata["bitrate"] = bitrate

            // محاولة استخراج صورة الألبوم
            try {
                val albumArt = retriever.embeddedPicture
                if (albumArt != null) {
                    metadata["has_album_art"] = "true"
                    metadata["album_art_size"] = albumArt.size.toString()
                }
            } catch (_: Exception) {}

        } catch (_: Exception) {
            // فشل استخراج البيانات الوصفية
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // إنشاء الصور المصغرة
    // ═══════════════════════════════════════════════════════════════

    /**
     * إنشاء صورة مصغرة لملف وسائط
     *
     * @param fileInfo معلومات الملف
     * @return مسار الصورة المصغرة أو null
     */
    fun generateThumbnail(fileInfo: FoundFileInfo): String? {
        return when (fileInfo.category) {
            FileCategory.PHOTO -> generateImageThumbnail(fileInfo)
            FileCategory.VIDEO -> generateVideoThumbnail(fileInfo)
            else -> null
        }
    }

    /**
     * إنشاء صورة مصغرة من ملف صورة
     *
     * يستخدم تقنية أخذ العينات (Sampling) لقراءة الصورة
     * بحجم صغير بدلاً من تحميلها بالكامل
     */
    private fun generateImageThumbnail(fileInfo: FoundFileInfo): String? {
        val file = File(fileInfo.path)
        if (!file.exists() || !file.canRead()) return null

        try {
            // أولاً: قراءة الأبعاد
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            // حساب معدل أخذ العينات
            options.inSampleSize = calculateInSampleSize(
                options.outWidth, options.outHeight,
                THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT
            )
            options.inJustDecodeBounds = false

            // قراءة الصورة المصغرة
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

            return saveThumbnail(bitmap, fileInfo.fileName).also {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * إنشاء صورة مصغرة من ملف فيديو
     */
    private fun generateVideoThumbnail(fileInfo: FoundFileInfo): String? {
        val file = File(fileInfo.path)
        if (!file.exists() || !file.canRead()) return null

        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)

            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null

            // تغيير حجم الصورة
            val scaled = Bitmap.createScaledBitmap(
                bitmap, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, true
            )

            return saveThumbnail(scaled, fileInfo.fileName).also {
                bitmap.recycle()
                if (scaled !== bitmap) scaled.recycle()
            }
        } catch (_: Exception) {
            return null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    /**
     * حفظ الصورة المصغرة في التخزين المؤقت
     */
    private fun saveThumbnail(bitmap: Bitmap, fileName: String): String? {
        return try {
            val thumbDir = File(context.cacheDir, "recovery_thumbnails")
            if (!thumbDir.exists()) thumbDir.mkdirs()

            val thumbFile = File(thumbDir, "thumb_${System.currentTimeMillis()}_${fileName.hashCode()}.jpg")
            FileOutputStream(thumbFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos)
                fos.flush()
            }
            thumbFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * حساب معدل أخذ العينات لتغيير حجم الصورة بكفاءة
     */
    private fun calculateInSampleSize(
        srcWidth: Int, srcHeight: Int,
        reqWidth: Int, reqHeight: Int
    ): Int {
        var inSampleSize = 1

        if (srcWidth > reqWidth || srcHeight > reqHeight) {
            val halfWidth = srcWidth / 2
            val halfHeight = srcHeight / 2

            while ((halfWidth / inSampleSize) >= reqWidth &&
                   (halfHeight / inSampleSize) >= reqHeight) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    // ═══════════════════════════════════════════════════════════════
    // دوال اكتشاف الدلائل
    // ═══════════════════════════════════════════════════════════════

    /**
     * الحصول على دلائل الوسائط القياسية
     */
    private fun getMediaDirectories(): List<File> {
        val dirs = mutableListOf<File>()
        val externalStorage = Environment.getExternalStorageDirectory()

        // الدلائل الرئيسية
        val standardPaths = listOf(
            "${externalStorage}/DCIM/Camera",
            "${externalStorage}/DCIM/Screenshots",
            "${externalStorage}/DCIM/ScreenRecorder",
            "${externalStorage}/Pictures/Screenshots",
            "${externalStorage}/Pictures/Edit",
            "${externalStorage}/Pictures/Facebook",
            "${externalStorage}/Pictures/Instagram",
            "${externalStorage}/Pictures/Snapchat",
            "${externalStorage}/Movies",
            "${externalStorage}/Music",
            "${externalStorage}/Recordings",
            "${externalStorage}/Download",
            "${externalStorage}/Podcasts",
            "${externalStorage}/Audiobooks",
            "${externalStorage}/Notifications",
            "${externalStorage}/Ringtones",
            "${externalStorage}/Alarms"
        )

        for (path in standardPaths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) dirs.add(dir)
        }

        // إضافة دلائل التخزين الخارجي (بطاقة SD)
        try {
            context.getExternalFilesDirs(null)?.forEach { externalDir ->
                externalDir?.let {
                    val storageRoot = it.absolutePath.substringBefore("/Android")
                    val sdPaths = listOf(
                        "$storageRoot/DCIM",
                        "$storageRoot/Pictures",
                        "$storageRoot/Movies",
                        "$storageRoot/Music"
                    )
                    for (path in sdPaths) {
                        val dir = File(path)
                        if (dir.exists() && dir.isDirectory) dirs.add(dir)
                    }
                }
            }
        } catch (_: Exception) {}

        return dirs.distinctBy { it.absolutePath }
    }

    /**
     * الحصول على دلائل تطبيقات المراسلة
     *
     * يبحث عن مجلدات الوسائط الخاصة بتطبيقات:
     * WhatsApp, WhatsApp Business, Telegram, Signal, Viber,
     * Instagram, Snapchat, Discord, LINE, WeChat
     */
    private fun getMessagingAppDirectories(): List<File> {
        val dirs = mutableListOf<File>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        // WhatsApp - أهم تطبيق مراسلة
        val whatsappPaths = listOf(
            "$externalStorage/WhatsApp/Media/WhatsApp Images",
            "$externalStorage/WhatsApp/Media/WhatsApp Video",
            "$externalStorage/WhatsApp/Media/WhatsApp Audio",
            "$externalStorage/WhatsApp/Media/WhatsApp Voice Notes",
            "$externalStorage/WhatsApp/Media/WhatsApp Documents",
            "$externalStorage/WhatsApp/Media/WhatsApp Animated Gifs",
            "$externalStorage/WhatsApp/Media/WallPaper",
            "$externalStorage/WhatsApp/Media/WhatsApp Stickers",
            "$externalStorage/WhatsApp/Media/Profile Photos",
            "$externalStorage/WhatsApp/Media/Private",
            "$externalStorage/WhatsApp/Databases",
            "$externalStorage/WhatsApp Business/Media/WhatsApp Business Images",
            "$externalStorage/WhatsApp Business/Media/WhatsApp Business Video",
            "$externalStorage/WhatsApp Business/Media/WhatsApp Business Audio"
        )

        // Telegram
        val telegramPaths = listOf(
            "$externalStorage/Telegram/Telegram Images",
            "$externalStorage/Telegram/Telegram Video",
            "$externalStorage/Telegram/Telegram Audio",
            "$externalStorage/Telegram/Telegram Documents",
            "$externalStorage/Telegram/Telegram Animated Gifs",
            "$externalStorage/Telegram/Telegram Stickers",
            "$externalStorage/Telegram/Telegram Files"
        )

        // Signal
        val signalPaths = listOf(
            "$externalStorage/Signal/Images",
            "$externalStorage/Signal/Videos",
            "$externalStorage/Signal/Audio"
        )

        // تطبيقات أخرى
        val otherPaths = listOf(
            "$externalStorage/Viber/media",
            "$externalStorage/Instagram",
            "$externalStorage/Snapchat",
            "$externalStorage/Discord",
            "$externalStorage/LINE",
            "$externalStorage/WeChat",
            "$externalStorage/KakaoTalk",
            "$externalStorage/Skype",
            "$externalStorage/Facebook",
            "$externalStorage/Messenger",
            "$externalStorage/Microsoft Teams"
        )

        val allPaths = whatsappPaths + telegramPaths + signalPaths + otherPaths

        for (path in allPaths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) dirs.add(dir)
        }

        return dirs
    }

    /**
     * الحصول على دلائل ذاكرة التخزين المؤقت للوسائط
     */
    private fun getMediaCacheDirectories(): List<File> {
        val dirs = mutableListOf<File>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        // ذاكرة التخزين المؤقت للوسائط
        val cachePaths = listOf(
            "$externalStorage/Android/data/com.google.android.apps.photos/cache",
            "$externalStorage/Android/data/com.google.android.apps.docs/cache",
            "$externalStorage/Android/data/com.android.gallery3d/cache",
            "$externalStorage/Android/data/com.sec.android.gallery3d/cache",
            "$externalStorage/Android/data/com.android.camera/cache",
            "$externalStorage/Android/data/com.google.android.GoogleCamera/cache",
            "$externalStorage/Android/data/com.whatsapp/cache",
            "$externalStorage/Android/data/org.telegram.messenger/cache"
        )

        for (path in cachePaths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) dirs.add(dir)
        }

        // البحث عن دلائل cache في Android/data
        try {
            val androidData = File("$externalStorage/Android/data")
            if (androidData.exists() && androidData.isDirectory) {
                androidData.listFiles()?.forEach { appDir ->
                    val cacheDir = File(appDir, "cache")
                    if (cacheDir.exists() && cacheDir.isDirectory) {
                        dirs.add(cacheDir)
                    }
                    val filesDir = File(appDir, "files")
                    if (filesDir.exists() && filesDir.isDirectory) {
                        dirs.add(filesDir)
                    }
                }
            }
        } catch (_: Exception) {}

        return dirs.distinctBy { it.absolutePath }
    }

    /**
     * الحصول على دلائل الصور المصغرة
     */
    private fun getThumbnailDirectories(): List<File> {
        val dirs = mutableListOf<File>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        val thumbPaths = listOf(
            "$externalStorage/DCIM/.thumbnails",
            "$externalStorage/Pictures/.thumbnails",
            "$externalStorage/DCIM/.thumbdata",
            "$externalStorage/Android/data/com.android.gallery3d/files/.thumbnails",
            "$externalStorage/Android/data/com.sec.android.gallery3d/files/.thumbnails"
        )

        for (path in thumbPaths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) dirs.add(dir)
        }

        return dirs
    }

    /**
     * الحصول على دلائل المحذوفات
     */
    private fun getTrashDirectories(): List<File> {
        val dirs = mutableListOf<File>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        val trashPaths = listOf(
            "$externalStorage/.Trash",
            "$externalStorage/.trash",
            "$externalStorage/DCIM/.Trash",
            "$externalStorage/Pictures/.Trash",
            "$externalStorage/WhatsApp/.Trash",
            "$externalStorage/WhatsApp/Media/.Trash",
            "$externalStorage/Telegram/.Trash",
            "$externalStorage/Recently Deleted",
            "$externalStorage/.recently_deleted",
            "$externalStorage/Android/data/com.android.providers.media/trash"
        )

        for (path in trashPaths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) dirs.add(dir)
        }

        return dirs
    }

    // ═══════════════════════════════════════════════════════════════
    // دوال مساعدة
    // ═══════════════════════════════════════════════════════════════

    /**
     * تصنيف ملف الوسائط بناءً على الامتداد والتوقيع
     */
    private fun categorizeMediaFile(file: File): FileCategory? {
        val extension = file.extension.lowercase()

        // تصنيف سريع بناءً على الامتداد
        val category = when (extension) {
            in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif",
                "tiff", "tif", "cr2", "nef", "arw", "dng", "orf", "rw2",
                "pef", "raf", "srw", "svg", "ico", "raw") -> FileCategory.PHOTO

            in setOf("mp4", "avi", "mkv", "mov", "flv", "wmv", "3gp", "3g2",
                "m4v", "mpg", "mpeg", "webm", "ts", "vob") -> FileCategory.VIDEO

            in setOf("mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus",
                "amr", "awb", "mid", "ape", "alac") -> FileCategory.AUDIO

            else -> null
        }

        if (category != null) return category

        // إذا لم يكن الامتداد معروفاً، نحاول التعرف بالتوقيع
        val header = readFileHeader(file) ?: return null
        val signature = FileSignatures.identifyFileType(header) ?: return null

        return if (signature.category in listOf(FileCategory.PHOTO, FileCategory.VIDEO, FileCategory.AUDIO)) {
            signature.category
        } else null
    }

    /**
     * إنشاء FoundFileInfo لملف وسائط
     */
    private fun createMediaFileInfo(
        file: File,
        category: FileCategory,
        confidence: RecoveryConfidence,
        isLikelyDeleted: Boolean,
        isAppSpecific: Boolean
    ): FoundFileInfo {
        val header = readFileHeader(file)
        val signature = header?.let { FileSignatures.identifyFileType(it) }

        // تحديد اسم التطبيق المصدر إذا كان من مجلد تطبيق
        val appSource = if (isAppSpecific) {
            detectAppSource(file.absolutePath)
        } else null

        val actualConfidence = when {
            isLikelyDeleted -> confidence
            file.isHidden -> RecoveryConfidence.MEDIUM
            isAppSpecific -> RecoveryConfidence.MEDIUM
            else -> RecoveryConfidence.HIGH
        }

        return FoundFileInfo(
            path = file.absolutePath,
            fileName = file.name,
            fileSize = file.length(),
            extension = signature?.extensions?.first() ?: file.extension.lowercase(),
            mimeType = signature?.mimeType ?: "*/*",
            category = category,
            signatureName = signature?.name,
            confidence = actualConfidence,
            thumbnailPath = null, // سيتم إنشاؤه لاحقاً عند الحاجة
            lastModified = file.lastModified(),
            isRootRequired = false,
            sourcePath = file.absolutePath,
            metadata = buildMap {
                put("is_hidden", file.isHidden.toString())
                put("parent_dir", file.parent ?: "")
                if (appSource != null) put("app_source", appSource)
                if (isLikelyDeleted) put("likely_deleted", "true")
                signature?.let { put("signature_name", it.name) }
            }
        )
    }

    /**
     * تحديد التطبيق المصدر من مسار الملف
     */
    private fun detectAppSource(path: String): String? {
        return when {
            path.contains("WhatsApp") -> "WhatsApp"
            path.contains("Telegram") -> "Telegram"
            path.contains("Signal") -> "Signal"
            path.contains("Viber") -> "Viber"
            path.contains("Instagram") -> "Instagram"
            path.contains("Snapchat") -> "Snapchat"
            path.contains("Discord") -> "Discord"
            path.contains("LINE") -> "LINE"
            path.contains("WeChat") -> "WeChat"
            path.contains("KakaoTalk") -> "KakaoTalk"
            path.contains("Skype") -> "Skype"
            path.contains("Facebook") -> "Facebook"
            path.contains("Messenger") -> "Messenger"
            else -> null
        }
    }

    /**
     * قراءة رأس الملف
     */
    private fun readFileHeader(file: File): ByteArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(HEADER_SIZE)
                val bytesRead = raf.read(header)
                if (bytesRead > 0) header.copyOf(bytesRead) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * تنسيق المدة بشكل مقروء
     *
     * @param seconds المدة بالثواني
     * @return نص منسق (مثل: "3:45" أو "1:02:30")
     */
    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60

        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", m, s)
        }
    }
}
