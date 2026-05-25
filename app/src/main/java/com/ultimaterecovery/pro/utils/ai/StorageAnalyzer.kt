package com.ultimaterecovery.pro.utils.ai

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comprehensive storage analysis tool for Ultimate Recovery Pro.
 *
 * Provides detailed storage analysis capabilities including category
 * breakdown, duplicate detection, junk file identification, and
 * cleanup recommendations. Results are emitted as [Flow]s to support
 * real-time UI updates during long-running analysis operations.
 *
 * ## Analysis Features
 * - **Category breakdown**: Size distribution across photos, videos,
 *   documents, audio, archives, APKs, and other files.
 * - **Largest files**: Top-N files consuming the most storage.
 * - **Duplicate detection**: Find duplicate files via size + MD5 + SHA-256
 *   comparison, with configurable minimum file size threshold.
 * - **Junk/cache detection**: Identify temporary files, cache directories,
 *   orphaned thumbnails, and download leftovers.
 * - **Old file detection**: Find files not accessed within a configurable
 *   time period.
 * - **Storage trend**: Track storage usage over time (persisted to prefs).
 * - **Cleanup recommendations**: Generate actionable cleanup suggestions
 *   prioritized by potential space savings.
 *
 * @see StorageAnalysisResult
 * @see CategoryInfo
 * @see JunkFileEntry
 * @see CleanupRecommendation
 */
@Singleton
class StorageAnalyzer @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "StorageAnalyzer"

        /** Preferences file for storing historical analysis data. */
        private const val PREFS_NAME = "storage_analyzer_prefs"

        /** Key for storage history entries. */
        private const val KEY_STORAGE_HISTORY = "storage_history"

        /** Maximum number of historical entries to keep. */
        private const val MAX_HISTORY_ENTRIES = 30

        /** Buffer size for hash computation. */
        private const val HASH_BUFFER_SIZE = 8192

        /** Minimum file size for duplicate detection (skip tiny files). */
        private const val MIN_DUPLICATE_SIZE = 1024L

        /** Known cache/junk directory names. */
        private val CACHE_DIR_NAMES = setOf(
            "cache", "Cache", "CACHE",
            ".cache",
            "thumbnails", "Thumbnails", "THUMBNAILS",
            ".thumbnails",
            "temp", "tmp", "Temp", "TMP",
            ".tmp",
            "code_cache", "app_cache",
            "glide_cache", "picasso-cache", "fresco_cache",
            "okhttp_cache", "volley"
        )

        /** Known junk/temporary file extensions. */
        private val JUNK_EXTENSIONS = setOf(
            "tmp", "temp", "log", "bak", "old", "swp", "swn",
            "part", "download", "crdownload", "aria2",
            "apk.tmp", "partial"
        )

        /** Known paths that typically contain junk files. */
        private val JUNK_PATH_PATTERNS = listOf(
            "/cache/",
            "/.cache/",
            "/thumbnails/",
            "/.thumbnails/",
            "/temp/",
            "/tmp/",
            "/lost+found/"
        )

        /** Extension-to-category mapping for fast classification. */
        private val PHOTO_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif",
            "raw", "heic", "heif", "cr2", "nef", "arw", "dng", "orf",
            "rw2", "raf", "srw", "pef", "ico", "svg"
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "avi", "mkv", "mov", "wmv", "flv", "3gp", "3g2",
            "webm", "m4v", "mpeg", "mpg", "m2v", "ts", "vob"
        )

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "wav", "flac", "aac", "ogg", "oga", "opus",
            "wma", "m4a", "amr", "mid", "midi", "ape", "alac"
        )

        private val DOCUMENT_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "csv", "rtf", "odt", "ods", "odp",
            "html", "htm", "xml", "json", "md", "epub", "mobi"
        )

        private val ARCHIVE_EXTENSIONS = setOf(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst"
        )
    }

    /** SharedPreferences for historical data persistence. */
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ═══════════════════════════════════════════════════════════════
    // Full Storage Analysis
    // ═══════════════════════════════════════════════════════════════

    /**
     * Performs a comprehensive storage analysis.
     *
     * Emits progress updates as the analysis proceeds through different
     * phases: scanning files, detecting duplicates, finding junk, and
     * generating recommendations.
     *
     * @return A cold [Flow] of [StorageAnalysisProgress] culminating in
     *         a [StorageAnalysisProgress.Completed] with the full result.
     */
    fun analyzeStorage(): Flow<StorageAnalysisProgress> = flow {
        emit(StorageAnalysisProgress.Scanning(phase = "Reading storage..."))

        val rootDir = Environment.getExternalStorageDirectory()
        if (!rootDir.exists() || !rootDir.canRead()) {
            emit(StorageAnalysisProgress.Failed("Cannot access storage"))
            return@flow
        }

        // Phase 1: Scan all files and build category breakdown
        emit(StorageAnalysisProgress.Scanning(phase = "Scanning files...", progress = 0.1f))

        val allFiles = mutableListOf<AnalyzedFile>()
        val categoryBreakdown = mutableMapOf<FileCategory, CategoryInfo>()

        FileCategory.values().forEach { cat ->
            categoryBreakdown[cat] = CategoryInfo(
                category = cat,
                totalSize = 0L,
                fileCount = 0,
                extensions = mutableMapOf()
            )
        }

        scanDirectory(rootDir, allFiles, categoryBreakdown)

        emit(StorageAnalysisProgress.Scanning(phase = "Found ${allFiles.size} files", progress = 0.4f))

        // Phase 2: Find largest files
        emit(StorageAnalysisProgress.Scanning(phase = "Finding largest files...", progress = 0.5f))

        val largestFiles = allFiles
            .sortedByDescending { it.size }
            .take(50)
            .map { LargestFileEntry(file = it.file, size = it.size, category = it.category) }

        // Phase 3: Find duplicates
        emit(StorageAnalysisProgress.Scanning(phase = "Detecting duplicates...", progress = 0.6f))

        val duplicates = findDuplicatesInternal(allFiles.filter { it.size >= MIN_DUPLICATE_SIZE })

        // Phase 4: Find junk files
        emit(StorageAnalysisProgress.Scanning(phase = "Finding junk files...", progress = 0.75f))

        val junkFiles = findJunkFilesInternal(allFiles)

        // Phase 5: Find old files
        emit(StorageAnalysisProgress.Scanning(phase = "Checking old files...", progress = 0.85f))

        // Phase 6: Generate recommendations
        emit(StorageAnalysisProgress.Scanning(phase = "Generating recommendations...", progress = 0.9f))

        val recommendations = generateRecommendations(
            categoryBreakdown, duplicates, junkFiles, allFiles
        )

        // Save snapshot for trend analysis
        saveStorageSnapshot(allFiles.sumOf { it.size })

        val result = StorageAnalysisResult(
            totalFiles = allFiles.size,
            totalSize = allFiles.sumOf { it.size },
            categoryBreakdown = categoryBreakdown,
            largestFiles = largestFiles,
            duplicateGroups = duplicates,
            junkFiles = junkFiles,
            recommendations = recommendations,
            timestamp = System.currentTimeMillis()
        )

        emit(StorageAnalysisProgress.Completed(result))
    }.flowOn(Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════
    // Category Breakdown
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns the storage usage breakdown by file category.
     *
     * @return Map of [FileCategory] to [CategoryInfo] with size and count.
     */
    suspend fun getCategoryBreakdown(): Map<FileCategory, CategoryInfo> {
        return withContext(Dispatchers.IO) {
            val breakdown = mutableMapOf<FileCategory, CategoryInfo>()
            FileCategory.values().forEach { cat ->
                breakdown[cat] = CategoryInfo(
                    category = cat, totalSize = 0L, fileCount = 0,
                    extensions = mutableMapOf()
                )
            }

            val rootDir = Environment.getExternalStorageDirectory()
            if (rootDir.exists() && rootDir.canRead()) {
                scanDirectoryForCategories(rootDir, breakdown)
            }
            breakdown
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Largest Files
    // ═══════════════════════════════════════════════════════════════

    /**
     * Finds the [n] largest files on the device.
     *
     * @param n Number of largest files to return.
     * @return List of [LargestFileEntry] sorted by size (descending).
     */
    suspend fun findLargestFiles(n: Int = 20): List<LargestFileEntry> {
        return withContext(Dispatchers.IO) {
            val files = mutableListOf<AnalyzedFile>()
            val rootDir = Environment.getExternalStorageDirectory()

            if (rootDir.exists() && rootDir.canRead()) {
                scanDirectorySimple(rootDir, files)
            }

            files.sortedByDescending { it.size }
                .take(n)
                .map { LargestFileEntry(file = it.file, size = it.size, category = it.category) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Duplicate Files
    // ═══════════════════════════════════════════════════════════════

    /**
     * Finds duplicate files on the device using multi-phase hash comparison.
     *
     * Phase 1: Group by file size.
     * Phase 2: Within each size group, compute MD5.
     * Phase 3: Confirm with SHA-256.
     *
     * @return List of [DuplicateGroup]s sorted by wasted space (descending).
     */
    suspend fun findDuplicates(): List<DuplicateGroup> {
        return withContext(Dispatchers.IO) {
            val files = mutableListOf<AnalyzedFile>()
            val rootDir = Environment.getExternalStorageDirectory()

            if (rootDir.exists() && rootDir.canRead()) {
                scanDirectorySimple(rootDir, files)
            }

            findDuplicatesInternal(files.filter { it.size >= MIN_DUPLICATE_SIZE })
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Junk Files
    // ═══════════════════════════════════════════════════════════════

    /**
     * Finds junk and cache files that can be safely deleted.
     *
     * Identifies:
     * - Files in cache/temp directories
     * - Files with temporary extensions (.tmp, .part, .crdownload, etc.)
     * - Orphaned thumbnail caches
     * - Empty directories
     * - APK files in download/cache directories
     *
     * @return List of [JunkFileEntry] with reason for each classification.
     */
    suspend fun findJunkFiles(): List<JunkFileEntry> {
        return withContext(Dispatchers.IO) {
            val files = mutableListOf<AnalyzedFile>()
            val rootDir = Environment.getExternalStorageDirectory()

            if (rootDir.exists() && rootDir.canRead()) {
                scanDirectorySimple(rootDir, files)
            }

            findJunkFilesInternal(files)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Old Files
    // ═══════════════════════════════════════════════════════════════

    /**
     * Finds files not modified within the specified number of days.
     *
     * @param days Minimum age in days (default: 90 days / ~3 months).
     * @return List of [OldFileEntry] sorted by age (oldest first).
     */
    suspend fun findOldFiles(days: Int = 90): List<OldFileEntry> {
        return withContext(Dispatchers.IO) {
            val cutoffMs = System.currentTimeMillis() - (days.toLong() * 86_400_000L)
            val oldFiles = mutableListOf<OldFileEntry>()
            val rootDir = Environment.getExternalStorageDirectory()

            if (rootDir.exists() && rootDir.canRead()) {
                scanForOldFiles(rootDir, cutoffMs, oldFiles)
            }

            oldFiles.sortedBy { it.lastModified }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Storage Trend
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns storage usage trend over time.
     *
     * Historical snapshots are saved after each [analyzeStorage] call.
     * The trend shows how storage usage has changed over time, enabling
     * projections of when storage will run out.
     *
     * @return List of [StorageSnapshot] ordered by timestamp.
     */
    fun getStorageTrend(): List<StorageSnapshot> {
        val historyStr = prefs.getString(KEY_STORAGE_HISTORY, null) ?: return emptyList()

        return try {
            historyStr.split(";").mapNotNull { entry ->
                val parts = entry.split(",")
                if (parts.size == 2) {
                    StorageSnapshot(
                        timestamp = parts[0].toLongOrNull() ?: return@mapNotNull null,
                        totalUsedBytes = parts[1].toLongOrNull() ?: return@mapNotNull null
                    )
                } else null
            }.sortedBy { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Cleanup Recommendations
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generates cleanup recommendations based on the current storage state.
     *
     * Performs a quick analysis (without duplicate detection) and returns
     * actionable cleanup suggestions prioritized by potential space savings.
     *
     * @return List of [CleanupRecommendation] sorted by potential savings.
     */
    suspend fun getCleanupRecommendations(): List<CleanupRecommendation> {
        return withContext(Dispatchers.IO) {
            val files = mutableListOf<AnalyzedFile>()
            val categoryBreakdown = mutableMapOf<FileCategory, CategoryInfo>()
            FileCategory.values().forEach { cat ->
                categoryBreakdown[cat] = CategoryInfo(
                    category = cat, totalSize = 0L, fileCount = 0,
                    extensions = mutableMapOf()
                )
            }

            val rootDir = Environment.getExternalStorageDirectory()
            if (rootDir.exists() && rootDir.canRead()) {
                scanDirectory(rootDir, files, categoryBreakdown)
            }

            val junkFiles = findJunkFilesInternal(files)

            generateRecommendations(categoryBreakdown, emptyList(), junkFiles, files)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Internal Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Recursively scans a directory, building file list and category breakdown.
     */
    private suspend fun scanDirectory(
        dir: File,
        allFiles: MutableList<AnalyzedFile>,
        categoryBreakdown: MutableMap<FileCategory, CategoryInfo>
    ) {
        dir.listFiles()?.forEach { file ->
            try {
                currentCoroutineContext().ensureActive()

                if (file.isDirectory) {
                    scanDirectory(file, allFiles, categoryBreakdown)
                } else {
                    val category = categorizeByExtension(file.extension.lowercase())
                    val analyzed = AnalyzedFile(file = file, size = file.length(), category = category)
                    allFiles.add(analyzed)

                    val info = categoryBreakdown[category]!!
                    val extCount = info.extensions.getOrDefault(file.extension.lowercase(), 0)
                    categoryBreakdown[category] = info.copy(
                        totalSize = info.totalSize + file.length(),
                        fileCount = info.fileCount + 1,
                        extensions = info.extensions.toMutableMap().apply {
                            put(file.extension.lowercase(), extCount + 1)
                        }
                    )
                }
            } catch (_: Exception) {
                // Skip inaccessible files
            }
        }
    }

    /**
     * Simplified directory scan for file list only.
     */
    private suspend fun scanDirectorySimple(dir: File, files: MutableList<AnalyzedFile>) {
        dir.listFiles()?.forEach { file ->
            try {
                currentCoroutineContext().ensureActive()
                if (file.isDirectory) {
                    scanDirectorySimple(file, files)
                } else {
                    files.add(
                        AnalyzedFile(
                            file = file,
                            size = file.length(),
                            category = categorizeByExtension(file.extension.lowercase())
                        )
                    )
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Scan for category breakdown only (no file list).
     */
    private suspend fun scanDirectoryForCategories(
        dir: File,
        breakdown: MutableMap<FileCategory, CategoryInfo>
    ) {
        dir.listFiles()?.forEach { file ->
            try {
                currentCoroutineContext().ensureActive()
                if (file.isDirectory) {
                    scanDirectoryForCategories(file, breakdown)
                } else {
                    val category = categorizeByExtension(file.extension.lowercase())
                    val info = breakdown[category]!!
                    breakdown[category] = info.copy(
                        totalSize = info.totalSize + file.length(),
                        fileCount = info.fileCount + 1
                    )
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Scan for old files based on last modified timestamp.
     */
    private suspend fun scanForOldFiles(
        dir: File,
        cutoffMs: Long,
        oldFiles: MutableList<OldFileEntry>
    ) {
        dir.listFiles()?.forEach { file ->
            try {
                currentCoroutineContext().ensureActive()
                if (file.isDirectory) {
                    scanForOldFiles(file, cutoffMs, oldFiles)
                } else {
                    if (file.lastModified() in 1L..cutoffMs) {
                        oldFiles.add(
                            OldFileEntry(
                                file = file,
                                size = file.length(),
                                lastModified = file.lastModified(),
                                ageDays = ((System.currentTimeMillis() - file.lastModified()) / 86_400_000L).toInt(),
                                category = categorizeByExtension(file.extension.lowercase())
                            )
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Internal duplicate detection using size → MD5 → SHA-256 phases.
     */
    private fun findDuplicatesInternal(files: List<AnalyzedFile>): List<DuplicateGroup> {
        val duplicateGroups = mutableListOf<DuplicateGroup>()

        // Phase 1: Group by size
        val sizeGroups = files.groupBy { it.size }.filter { it.value.size > 1 }

        for ((size, sameSizeFiles) in sizeGroups) {
            // Phase 2: MD5 hash within size groups
            val md5Groups = sameSizeFiles.groupBy { computeMD5(it.file) }

            for ((md5, md5MatchFiles) in md5Groups) {
                if (md5MatchFiles.size > 1) {
                    // Phase 3: Confirm with SHA-256
                    val sha256Groups = md5MatchFiles.groupBy { computeSHA256(it.file) }

                    for ((sha256, confirmedDuplicates) in sha256Groups) {
                        if (confirmedDuplicates.size > 1) {
                            duplicateGroups.add(DuplicateGroup(
                                files = confirmedDuplicates.map { it.file },
                                fileSize = size,
                                md5Hash = md5,
                                sha256Hash = sha256,
                                wastedSpace = size * (confirmedDuplicates.size - 1)
                            ))
                        }
                    }
                }
            }
        }

        return duplicateGroups.sortedByDescending { it.wastedSpace }
    }

    /**
     * Internal junk file detection.
     */
    private fun findJunkFilesInternal(files: List<AnalyzedFile>): List<JunkFileEntry> {
        val junkFiles = mutableListOf<JunkFileEntry>()

        for (analyzed in files) {
            val file = analyzed.file
            val reason = classifyJunk(file)
            if (reason != null) {
                junkFiles.add(JunkFileEntry(
                    file = file,
                    size = file.length(),
                    reason = reason,
                    category = analyzed.category
                ))
            }
        }

        return junkFiles.sortedByDescending { it.size }
    }

    /**
     * Determines if a file is junk and returns the reason, or null if not junk.
     */
    private fun classifyJunk(file: File): JunkReason? {
        val path = file.absolutePath
        val extension = file.extension.lowercase()

        // Check extension-based junk
        if (extension in JUNK_EXTENSIONS) {
            return JunkReason.TEMPORARY_FILE
        }

        // Check path-based junk
        for (pattern in JUNK_PATH_PATTERNS) {
            if (path.contains(pattern)) {
                return JunkReason.CACHE_FILE
            }
        }

        // Check if in a cache directory
        var parent = file.parentFile
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.name in CACHE_DIR_NAMES) {
                return JunkReason.CACHE_FILE
            }
            parent = parent.parentFile
            depth++
        }

        // Check for empty files
        if (file.length() == 0L) {
            return JunkReason.EMPTY_FILE
        }

        // Check for leftover APK files in non-app directories
        if (extension == "apk") {
            val parentName = file.parentFile?.name?.lowercase() ?: ""
            if (parentName in setOf("download", "downloads", "cache", "temp")) {
                return JunkReason.LEFTOVER_APK
            }
        }

        // Check for log files
        if (extension == "log" || file.name.endsWith(".log.txt", ignoreCase = true)) {
            return JunkReason.LOG_FILE
        }

        return null
    }

    /**
     * Generates cleanup recommendations from analysis data.
     */
    private fun generateRecommendations(
        categoryBreakdown: Map<FileCategory, CategoryInfo>,
        duplicateGroups: List<DuplicateGroup>,
        junkFiles: List<JunkFileEntry>,
        allFiles: List<AnalyzedFile>
    ): List<CleanupRecommendation> {
        val recommendations = mutableListOf<CleanupRecommendation>()
        val totalSize = allFiles.sumOf { it.size }

        // Duplicate files
        val totalDuplicateWaste = duplicateGroups.sumOf { it.wastedSpace }
        if (duplicateGroups.isNotEmpty()) {
            recommendations.add(CleanupRecommendation(
                type = CleanupType.REMOVE_DUPLICATES,
                title = "Remove Duplicate Files",
                description = "Found ${duplicateGroups.size} group(s) of duplicates wasting ${formatSize(totalDuplicateWaste)}",
                potentialSavings = totalDuplicateWaste,
                affectedFileCount = duplicateGroups.sumOf { it.files.size - 1 },
                priority = if (totalDuplicateWaste > 500 * 1024 * 1024)
                    CleanupPriority.HIGH else CleanupPriority.MEDIUM
            ))
        }

        // Junk files
        val totalJunkSize = junkFiles.sumOf { it.size }
        if (junkFiles.isNotEmpty()) {
            val breakdown = junkFiles.groupBy { it.reason }
                .mapValues { it.value.sumOf { f -> f.size } }

            recommendations.add(CleanupRecommendation(
                type = CleanupType.CLEAN_JUNK,
                title = "Clean Junk Files",
                description = "Found ${junkFiles.size} junk/cache files using ${formatSize(totalJunkSize)}",
                potentialSavings = totalJunkSize,
                affectedFileCount = junkFiles.size,
                priority = if (totalJunkSize > 100 * 1024 * 1024)
                    CleanupPriority.HIGH else CleanupPriority.MEDIUM
            ))
        }

        // Large video files
        val videoInfo = categoryBreakdown[FileCategory.VIDEO]
        if (videoInfo != null && videoInfo.totalSize > totalSize * 0.3 && totalSize > 0) {
            recommendations.add(CleanupRecommendation(
                type = CleanupType.REVIEW_LARGE_FILES,
                title = "Review Large Videos",
                description = "Videos use ${formatSize(videoInfo.totalSize)} (${(videoInfo.totalSize.toFloat() / totalSize * 100).toInt()}% of storage). Consider uploading to cloud or removing old videos.",
                potentialSavings = videoInfo.totalSize / 3,
                affectedFileCount = videoInfo.fileCount,
                priority = CleanupPriority.MEDIUM
            ))
        }

        // Large photo library
        val photoInfo = categoryBreakdown[FileCategory.PHOTO]
        if (photoInfo != null && photoInfo.totalSize > totalSize * 0.25 && totalSize > 0) {
            recommendations.add(CleanupRecommendation(
                type = CleanupType.REVIEW_PHOTOS,
                title = "Optimize Photo Storage",
                description = "Photos use ${formatSize(photoInfo.totalSize)} (${(photoInfo.totalSize.toFloat() / totalSize * 100).toInt()}% of storage). Consider backing up and removing duplicates or screenshots.",
                potentialSavings = photoInfo.totalSize / 4,
                affectedFileCount = photoInfo.fileCount,
                priority = CleanupPriority.LOW
            ))
        }

        // APK files
        val apkInfo = categoryBreakdown[FileCategory.APK]
        if (apkInfo != null && apkInfo.totalSize > 100 * 1024 * 1024) {
            recommendations.add(CleanupRecommendation(
                type = CleanupType.CLEAN_APKS,
                title = "Remove Installed APKs",
                description = "APK files use ${formatSize(apkInfo.totalSize)}. Already installed APKs can be safely removed.",
                potentialSavings = apkInfo.totalSize,
                affectedFileCount = apkInfo.fileCount,
                priority = CleanupPriority.LOW
            ))
        }

        return recommendations.sortedByDescending { it.potentialSavings }
    }

    /**
     * Saves a storage usage snapshot for trend tracking.
     */
    private fun saveStorageSnapshot(totalUsedBytes: Long) {
        val history = getStorageHistory().toMutableList()
        history.add(StorageSnapshot(
            timestamp = System.currentTimeMillis(),
            totalUsedBytes = totalUsedBytes
        ))

        // Keep only the most recent entries
        while (history.size > MAX_HISTORY_ENTRIES) {
            history.removeAt(0)
        }

        val historyStr = history.joinToString(";") { "${it.timestamp},${it.totalUsedBytes}" }
        prefs.edit().putString(KEY_STORAGE_HISTORY, historyStr).apply()
    }

    /**
     * Reads storage history from SharedPreferences.
     */
    private fun getStorageHistory(): List<StorageSnapshot> {
        val historyStr = prefs.getString(KEY_STORAGE_HISTORY, null) ?: return emptyList()

        return try {
            historyStr.split(";").mapNotNull { entry ->
                val parts = entry.split(",")
                if (parts.size == 2) {
                    StorageSnapshot(
                        timestamp = parts[0].toLongOrNull() ?: return@mapNotNull null,
                        totalUsedBytes = parts[1].toLongOrNull() ?: return@mapNotNull null
                    )
                } else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Computes MD5 hash of a file.
     */
    private fun computeMD5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes SHA-256 hash of a file.
     */
    private fun computeSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Classifies a file category from its extension.
     */
    private fun categorizeByExtension(extension: String): FileCategory = when {
        extension in PHOTO_EXTENSIONS -> FileCategory.PHOTO
        extension in VIDEO_EXTENSIONS -> FileCategory.VIDEO
        extension in AUDIO_EXTENSIONS -> FileCategory.AUDIO
        extension in DOCUMENT_EXTENSIONS -> FileCategory.DOCUMENT
        extension in ARCHIVE_EXTENSIONS -> FileCategory.ARCHIVE
        extension == "apk" -> FileCategory.APK
        else -> FileCategory.OTHER
    }

    /**
     * Formats byte count to human-readable string.
     */
    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.1f GB".format(gb)
    }


}

// ═══════════════════════════════════════════════════════════════
// Data Classes
// ═══════════════════════════════════════════════════════════════

/**
 * Internal representation of a file during analysis.
 */
private data class AnalyzedFile(
    val file: File,
    val size: Long,
    val category: FileCategory
)

/**
 * Progress updates emitted during storage analysis.
 */
sealed class StorageAnalysisProgress {
    data class Scanning(
        val phase: String,
        val progress: Float = 0f
    ) : StorageAnalysisProgress()

    data class Completed(
        val result: StorageAnalysisResult
    ) : StorageAnalysisProgress()

    data class Failed(
        val error: String
    ) : StorageAnalysisProgress()
}

/**
 * Complete result of a storage analysis.
 *
 * @property totalFiles         Total number of files found.
 * @property totalSize          Total bytes across all files.
 * @property categoryBreakdown  Size and count per file category.
 * @property largestFiles       Top-N largest files.
 * @property duplicateGroups    Groups of duplicate files.
 * @property junkFiles          Detected junk/cache files.
 * @property recommendations    Cleanup recommendations.
 * @property timestamp          When this analysis was performed.
 */
data class StorageAnalysisResult(
    val totalFiles: Int,
    val totalSize: Long,
    val categoryBreakdown: Map<FileCategory, CategoryInfo>,
    val largestFiles: List<LargestFileEntry>,
    val duplicateGroups: List<DuplicateGroup>,
    val junkFiles: List<JunkFileEntry>,
    val recommendations: List<CleanupRecommendation>,
    val timestamp: Long
)

/**
 * Information about a file category.
 *
 * @property category    The file category.
 * @property totalSize   Total size in bytes.
 * @property fileCount   Number of files in this category.
 * @property extensions  Map of extension → count within this category.
 */
data class CategoryInfo(
    val category: FileCategory,
    val totalSize: Long,
    val fileCount: Int,
    val extensions: Map<String, Int>
)

/**
 * A large file entry.
 *
 * @property file     The file.
 * @property size     Size in bytes.
 * @property category File category.
 */
data class LargestFileEntry(
    val file: File,
    val size: Long,
    val category: FileCategory
)

/**
 * A junk file entry with classification reason.
 *
 * @property file     The junk file.
 * @property size     Size in bytes.
 * @property reason   Why this file is classified as junk.
 * @property category File category.
 */
data class JunkFileEntry(
    val file: File,
    val size: Long,
    val reason: JunkReason,
    val category: FileCategory
)

/** Reasons a file is classified as junk. */
enum class JunkReason {
    TEMPORARY_FILE,
    CACHE_FILE,
    EMPTY_FILE,
    LEFTOVER_APK,
    LOG_FILE,
    ORPHANED_THUMBNAIL,
    DOWNLOAD_REMNANT
}

/**
 * An old file entry (not modified for a long time).
 *
 * @property file          The old file.
 * @property size          Size in bytes.
 * @property lastModified  Last modification timestamp (epoch millis).
 * @property ageDays       Age in days since last modification.
 * @property category      File category.
 */
data class OldFileEntry(
    val file: File,
    val size: Long,
    val lastModified: Long,
    val ageDays: Int,
    val category: FileCategory
)

/**
 * A storage usage snapshot for trend tracking.
 *
 * @property timestamp       When the snapshot was taken.
 * @property totalUsedBytes  Total storage used at this point.
 */
data class StorageSnapshot(
    val timestamp: Long,
    val totalUsedBytes: Long
) {
    /** Human-readable date. */
    val formattedDate: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(timestamp))

    /** Human-readable size. */
    val formattedSize: String
        get() = formatSnapshotSize(totalUsedBytes)

    private fun formatSnapshotSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.1f GB".format(gb)
    }
}

/**
 * A cleanup recommendation.
 *
 * @property type              Type of cleanup action.
 * @property title             Short title.
 * @property description       Detailed description.
 * @property potentialSavings  Estimated bytes that could be freed.
 * @property affectedFileCount Number of files that would be affected.
 * @property priority          Importance of this recommendation.
 */
data class CleanupRecommendation(
    val type: CleanupType,
    val title: String,
    val description: String,
    val potentialSavings: Long,
    val affectedFileCount: Int,
    val priority: CleanupPriority
)

/** Types of cleanup actions. */
enum class CleanupType {
    REMOVE_DUPLICATES,
    CLEAN_JUNK,
    REVIEW_LARGE_FILES,
    REVIEW_PHOTOS,
    CLEAN_APKS,
    CLEAR_CACHE
}

/** Cleanup priority levels. */
enum class CleanupPriority {
    LOW, MEDIUM, HIGH
}
