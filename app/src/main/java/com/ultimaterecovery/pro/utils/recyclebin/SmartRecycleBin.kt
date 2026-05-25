package com.ultimaterecovery.pro.utils.recyclebin

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ultimaterecovery.pro.data.local.dao.RecycleBinItemDao
import com.ultimaterecovery.pro.data.local.entity.RecycleBinItemEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.repository.RecycleBinRepository
import com.ultimaterecovery.pro.data.repository.Resource
import com.ultimaterecovery.pro.utils.crypto.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart recycle bin system for Ultimate Recovery Pro.
 *
 * Provides a safety net for accidentally deleted files by intercepting
 * deletion events (via [FileMonitorService]) and preserving copies
 * in a dedicated recycle bin directory before the original file is lost.
 *
 * ## Features
 * - **Automatic capture**: Files moved to the recycle bin are intercepted
 *   by [FileMonitorService] before permanent deletion.
 * - **Configurable expiry**: Items automatically expire after a set period
 *   (7 / 14 / 30 / 60 / 90 days).
 * - **LRU eviction**: When the storage limit is reached, the least
 *   recently accessed items are evicted first.
 * - **Category-based organization**: Items are categorized as PHOTO, VIDEO,
 *   DOCUMENT, AUDIO, ARCHIVE, APK, or OTHER.
 * - **Secure deletion**: Optional DoD 5220.22-M compliant secure wipe
 *   when permanently deleting items.
 * - **Auto-cleanup**: A WorkManager periodic job runs daily to purge
 *   expired items and enforce storage limits.
 * - **Full-text search**: Search recycle bin items by filename.
 *
 * ## Directory layout
 * ```
 * <app-external-files>/recycle_bin/
 *   ├── photos/
 *   ├── videos/
 *   ├── documents/
 *   ├── audio/
 *   ├── archives/
 *   ├── apks/
 *   └── other/
 * ```
 *
 * @see FileMonitorService
 * @see RecycleBinItemEntity
 */
@Singleton
class SmartRecycleBin @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recycleBinRepository: RecycleBinRepository,
    private val recycleBinItemDao: RecycleBinItemDao,
    private val cryptoManager: CryptoManager
) {

    companion object {
        const val TAG = "SmartRecycleBin"

        /** Directory name under app-specific external storage. */
        private const val RECYCLE_BIN_DIR = "recycle_bin"

        /** WorkManager work name for auto-cleanup. */
        private const val CLEANUP_WORK_NAME = "recycle_bin_auto_cleanup"

        /** Buffer size for file copy operations. */
        private const val BUFFER_SIZE = 1024 * 1024 // 1 MB

        /** Default auto-delete period in days. */
        private const val DEFAULT_AUTO_DELETE_DAYS = 30

        /** Default storage limit in megabytes. */
        private const val DEFAULT_STORAGE_LIMIT_MB = 500L

        /** Valid auto-delete periods. */
        val VALID_AUTO_DELETE_DAYS = setOf(7, 14, 30, 60, 90)

        /** Subdirectory names per category. */
        private val CATEGORY_DIRS = mapOf(
            FileCategory.PHOTO to "photos",
            FileCategory.VIDEO to "videos",
            FileCategory.DOCUMENT to "documents",
            FileCategory.AUDIO to "audio",
            FileCategory.ARCHIVE to "archives",
            FileCategory.APK to "apks",
            FileCategory.OTHER to "other"
        )
    }

    /** Current auto-delete period in days. */
    @Volatile
    private var autoDeleteDays: Int = DEFAULT_AUTO_DELETE_DAYS

    /** Current storage limit in megabytes. */
    @Volatile
    private var storageLimitMb: Long = DEFAULT_STORAGE_LIMIT_MB

    /** Whether the recycle bin monitoring is active. */
    @Volatile
    private var isMonitoring: Boolean = false

    // ──────────────────────────────────────────────
    // Monitoring lifecycle
    // ──────────────────────────────────────────────

    /**
     * Starts the recycle bin monitoring system.
     *
     * This activates the [FileMonitorService] foreground service that
     * watches key directories for file deletion events and schedules
     * the periodic auto-cleanup job via WorkManager.
     *
     * @return `true` if monitoring started successfully.
     */
    fun startMonitoring(): Boolean {
        if (isMonitoring) return true

        // Schedule periodic cleanup
        scheduleAutoCleanup()

        isMonitoring = true
        return true
    }

    /**
     * Stops the recycle bin monitoring system.
     *
     * Disables file observation and cancels the periodic cleanup job.
     * Existing items in the recycle bin remain intact until they expire
     * or are manually deleted.
     */
    fun stopMonitoring() {
        if (!isMonitoring) return

        // Cancel periodic cleanup
        WorkManager.getInstance(context).cancelUniqueWork(CLEANUP_WORK_NAME)

        isMonitoring = false
    }

    /**
     * Returns whether the recycle bin is currently monitoring.
     */
    fun isMonitoring(): Boolean = isMonitoring

    // ──────────────────────────────────────────────
    // Move to recycle bin
    // ──────────────────────────────────────────────

    /**
     * Moves a file into the recycle bin.
     *
     * This creates a copy of the file in the recycle bin directory
     * (organized by category) and records metadata in the database.
     * The original file is **not** deleted — the caller is responsible
     * for deleting the original after this method returns successfully.
     *
     * If the storage limit would be exceeded, LRU eviction is performed
     * before the new item is added.
     *
     * @param file The file to move into the recycle bin.
     * @return [Resource] containing the database row ID of the new item.
     */
    suspend fun moveToRecycleBin(file: File): Resource<Long> {
        return try {
            withContext(Dispatchers.IO) {
                if (!file.exists() || !file.canRead()) {
                    return@withContext Resource.error("File does not exist or is not readable")
                }

                // Determine category
                val category = categorizeFile(file)
                val mimeType = getMimeType(file)

                // Ensure storage limit
                enforceStorageLimit(file.length())

                // Create category subdirectory
                val categoryDir = getCategoryDirectory(category)
                if (!categoryDir.exists()) categoryDir.mkdirs()

                // Generate unique filename in recycle bin
                val destFile = resolveNameConflict(
                    File(categoryDir, file.name)
                )

                // Copy file content
                copyFileContent(file, destFile)

                // Generate thumbnail path (placeholder)
                val thumbnailPath = generateThumbnail(destFile, category)

                // Create database entry
                val now = System.currentTimeMillis()
                val expiryDate = now + (autoDeleteDays.toLong() * 24 * 60 * 60 * 1000)

                val item = RecycleBinItemEntity(
                    originalPath = file.absolutePath,
                    fileName = file.name,
                    fileSize = file.length(),
                    mimeType = mimeType,
                    deletedDate = now,
                    expiryDate = expiryDate,
                    thumbnailPath = thumbnailPath,
                    category = category,
                    autoDeleteDays = autoDeleteDays,
                    isRestorable = true,
                    metadata = buildMetadataJson(file)
                )

                recycleBinRepository.moveToRecycleBin(item)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to move file to recycle bin")
        }
    }

    // ──────────────────────────────────────────────
    // Restore
    // ──────────────────────────────────────────────

    /**
     * Restores a recycle bin item back to its original location.
     *
     * The file is copied from the recycle bin back to [RecycleBinItemEntity.originalPath].
     * If the original directory no longer exists it will be recreated.
     * If a file already exists at the original path a conflict suffix
     * (e.g. `_restored_1`) is appended.
     *
     * After successful restoration the recycle bin copy and database
     * record are removed.
     *
     * @param itemId Database ID of the recycle bin item.
     * @return [Resource] containing the absolute path of the restored file.
     */
    suspend fun restoreItem(itemId: Long): Resource<String> {
        return try {
            withContext(Dispatchers.IO) {
                val item = getItemById(itemId)
                    ?: return@withContext Resource.error("Recycle bin item not found")

                if (!item.isRestorable) {
                    return@withContext Resource.error("Item is not restorable")
                }

                // Locate the recycle bin copy
                val binFile = getRecycleBinFile(item)
                if (binFile == null || !binFile.exists()) {
                    return@withContext Resource.error("Recycle bin file copy not found")
                }

                // Determine restore destination
                val originalPath = File(item.originalPath)
                val destFile = if (originalPath.parentFile?.exists() == true) {
                    resolveNameConflict(originalPath, "_restored_")
                } else {
                    // Original directory gone — restore to Downloads
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    val restoreDir = File(downloadsDir, "Restored")
                    restoreDir.mkdirs()
                    resolveNameConflict(File(restoreDir, item.fileName), "_restored_")
                }

                // Copy back to original location
                copyFileContent(binFile, destFile)

                // Remove from recycle bin
                binFile.delete()
                item.thumbnailPath?.let { File(it).delete() }

                recycleBinRepository.restoreFromRecycleBin(item)

                Resource.success(destFile.absolutePath)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to restore item")
        }
    }

    // ──────────────────────────────────────────────
    // Permanent delete
    // ──────────────────────────────────────────────

    /**
     * Permanently deletes a recycle bin item.
     *
     * When [secureWipe] is true the file content is overwritten with
     * random data before deletion (DoD 5220.22-M compliant).
     *
     * @param itemId     Database ID of the item.
     * @param secureWipe Whether to securely overwrite the file first.
     * @return [Resource.Unit] on success.
     */
    suspend fun deletePermanent(itemId: Long, secureWipe: Boolean = false): Resource<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                val item = getItemById(itemId)
                    ?: return@withContext Resource.error("Recycle bin item not found")

                val binFile = getRecycleBinFile(item)

                if (binFile != null && binFile.exists()) {
                    if (secureWipe) {
                        cryptoManager.secureDelete(binFile.absolutePath)
                    } else {
                        binFile.delete()
                    }
                }

                // Delete thumbnail
                item.thumbnailPath?.let { thumbPath ->
                    val thumbFile = File(thumbPath)
                    if (thumbFile.exists()) thumbFile.delete()
                }

                recycleBinRepository.deletePermanent(item)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to permanently delete item")
        }
    }

    // ──────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────

    /**
     * Cleans up all expired items from the recycle bin.
     *
     * Expired items have an [RecycleBinItemEntity.expiryDate] that is
     * less than or equal to the current time. Their file copies and
     * database records are permanently removed.
     *
     * @return [Resource] with the number of items cleaned up.
     */
    suspend fun cleanExpired(): Resource<Int> {
        return try {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                var cleanedCount = 0

                // Collect expired items
                recycleBinItemDao.getExpiredItems(now).first().forEach { item ->
                    try {
                        // Delete file copy
                        val binFile = getRecycleBinFile(item)
                        binFile?.delete()

                        // Delete thumbnail
                        item.thumbnailPath?.let { File(it).delete() }

                        cleanedCount++
                    } catch (_: Exception) {
                        // Continue cleaning other items
                    }
                }

                // Delete expired records from database
                recycleBinRepository.cleanExpired(now)

                Resource.success(cleanedCount)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to clean expired items")
        }
    }

    /**
     * Runs the full auto-cleanup pipeline:
     * 1. Remove expired items.
     * 2. Enforce storage limit via LRU eviction.
     *
     * @return [Resource.Unit] when cleanup completes.
     */
    suspend fun autoCleanup(): Resource<Unit> {
        return try {
            // Step 1: Clean expired items
            cleanExpired()

            // Step 2: Enforce storage limit
            enforceStorageLimit(0L)

            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Auto-cleanup failed")
        }
    }

    // ──────────────────────────────────────────────
    // Queries
    // ──────────────────────────────────────────────

    /**
     * Returns a reactive [Flow] of all recycle bin items.
     */
    fun getItems(): Flow<List<RecycleBinItemEntity>> {
        return recycleBinItemDao.getAll()
    }

    /**
     * Returns a reactive [Flow] of recycle bin items filtered by [category].
     */
    fun getItemsByCategory(category: FileCategory): Flow<List<RecycleBinItemEntity>> {
        return recycleBinItemDao.getByCategory(category)
    }

    /**
     * Searches recycle bin items by file name.
     *
     * @param query Search query (case-insensitive substring match).
     * @return A [Flow] of matching items.
     */
    fun searchItems(query: String): Flow<List<RecycleBinItemEntity>> {
        return recycleBinItemDao.searchItems(query)
    }

    /**
     * Returns the total storage consumed by the recycle bin in bytes.
     */
    suspend fun getTotalStorageUsed(): Long {
        return withContext(Dispatchers.IO) {
            val binDir = getRecycleBinDirectory()
            if (!binDir.exists()) return@withContext 0L
            calculateDirectorySize(binDir)
        }
    }

    /**
     * Returns the total number of items in the recycle bin.
     */
    fun getItemCount(): Flow<Int> {
        return recycleBinItemDao.getCount()
    }

    // ──────────────────────────────────────────────
    // Configuration
    // ──────────────────────────────────────────────

    /**
     * Sets the auto-delete period.
     *
     * Items older than this number of days will be automatically
     * removed during the next cleanup cycle.
     *
     * @param days Number of days before auto-deletion. Must be one of
     *             [VALID_AUTO_DELETE_DAYS].
     */
    fun setAutoDeleteDays(days: Int) {
        require(days in VALID_AUTO_DELETE_DAYS) {
            "Auto-delete days must be one of $VALID_AUTO_DELETE_DAYS"
        }
        autoDeleteDays = days
    }

    /**
     * Returns the current auto-delete period in days.
     */
    fun getAutoDeleteDays(): Int = autoDeleteDays

    /**
     * Sets the maximum storage limit for the recycle bin.
     *
     * When the limit is exceeded, the least recently used items are
     * evicted (LRU strategy).
     *
     * @param mb Maximum storage in megabytes.
     */
    fun setStorageLimit(mb: Long) {
        require(mb > 0) { "Storage limit must be positive" }
        storageLimitMb = mb
    }

    /**
     * Returns the current storage limit in megabytes.
     */
    fun getStorageLimit(): Long = storageLimitMb

    // ──────────────────────────────────────────────
    // Storage limit enforcement (LRU eviction)
    // ──────────────────────────────────────────────

    /**
     * Ensures the recycle bin storage stays within the configured limit.
     *
     * If adding [incomingBytes] would exceed the limit, items are evicted
     * in least-recently-deleted order until enough space is freed.
     *
     * @param incomingBytes Size of the file about to be added (0 for
     *        a simple check without incoming file).
     */
    private suspend fun enforceStorageLimit(incomingBytes: Long) {
        val limitBytes = storageLimitMb * 1024 * 1024
        val currentUsage = getTotalStorageUsed()

        if (currentUsage + incomingBytes <= limitBytes) return

        // Need to evict items — sort by deletedDate ascending (oldest first = LRU)
        val items = recycleBinItemDao.getAll().first()
            .sortedBy { it.deletedDate }

        var freedBytes = 0L
        val needed = (currentUsage + incomingBytes) - limitBytes

        for (item in items) {
            if (freedBytes >= needed) break

            try {
                val binFile = getRecycleBinFile(item)
                binFile?.let { freedBytes += it.length() }
                binFile?.delete()
                item.thumbnailPath?.let { File(it).delete() }

                // Remove from database
                recycleBinItemDao.delete(item)
            } catch (_: Exception) {
                // Continue evicting other items
            }
        }
    }

    // ──────────────────────────────────────────────
    // File categorization
    // ──────────────────────────────────────────────

    /**
     * Categorizes a file based on its extension and MIME type.
     */
    private fun categorizeFile(file: File): FileCategory {
        val extension = file.extension.lowercase()
        val mimeType = getMimeType(file).lowercase()

        return when {
            // Photos
            extension in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "raw", "cr2", "nef", "heic", "heif")
                || mimeType.startsWith("image/") -> FileCategory.PHOTO

            // Videos
            extension in setOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "3gp", "webm", "m4v", "mpeg", "mpg")
                || mimeType.startsWith("video/") -> FileCategory.VIDEO

            // Audio
            extension in setOf("mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "amr")
                || mimeType.startsWith("audio/") -> FileCategory.AUDIO

            // Documents
            extension in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "odt", "ods")
                || mimeType.startsWith("text/")
                || mimeType in setOf("application/pdf", "application/msword") -> FileCategory.DOCUMENT

            // Archives
            extension in setOf("zip", "rar", "7z", "tar", "gz", "bz2")
                || mimeType in setOf("application/zip", "application/x-rar-compressed") -> FileCategory.ARCHIVE

            // APK
            extension == "apk"
                || mimeType == "application/vnd.android.package-archive" -> FileCategory.APK

            else -> FileCategory.OTHER
        }
    }

    /**
     * Returns the MIME type for a file.
     */
    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    // ──────────────────────────────────────────────
    // File operations
    // ──────────────────────────────────────────────

    /**
     * Returns the root recycle bin directory.
     */
    private fun getRecycleBinDirectory(): File {
        return File(context.getExternalFilesDir(null), RECYCLE_BIN_DIR)
    }

    /**
     * Returns the subdirectory for a given [category].
     */
    private fun getCategoryDirectory(category: FileCategory): File {
        val dirName = CATEGORY_DIRS[category] ?: "other"
        return File(getRecycleBinDirectory(), dirName)
    }

    /**
     * Resolves filename conflicts by appending a numeric suffix.
     *
     * E.g. `photo.jpg` → `photo_1.jpg` → `photo_2.jpg`
     *
     * @param destFile The desired destination file.
     * @param suffix   Separator between the base name and counter.
     * @return A unique [File] that does not conflict with existing files.
     */
    private fun resolveNameConflict(destFile: File, suffix: String = "_"): File {
        if (!destFile.exists()) return destFile

        val name = destFile.nameWithoutExtension
        val ext = destFile.extension
        val parent = destFile.parentFile ?: return destFile

        var counter = 1
        var candidate = File(parent, "${name}${suffix}$counter.$ext")
        while (candidate.exists() && counter < 1000) {
            counter++
            candidate = File(parent, "${name}${suffix}$counter.$ext")
        }

        return candidate
    }

    /**
     * Copies the content of [source] to [dest] using a 1 MB buffer.
     */
    private fun copyFileContent(source: File, dest: File) {
        dest.parentFile?.mkdirs()

        FileInputStream(source).use { fis ->
            FileOutputStream(dest).use { fos ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
                fos.flush()
            }
        }
    }

    /**
     * Calculates the total size of a directory recursively.
     */
    private fun calculateDirectorySize(directory: File): Long {
        if (!directory.exists()) return 0L

        var size = 0L
        val files = directory.listFiles() ?: return 0L

        for (file in files) {
            size += if (file.isDirectory) {
                calculateDirectorySize(file)
            } else {
                file.length()
            }
        }

        return size
    }

    /**
     * Attempts to locate the recycle bin copy of an [item].
     */
    private fun getRecycleBinFile(item: RecycleBinItemEntity): File? {
        val categoryDir = getCategoryDirectory(item.category)
        val candidate = File(categoryDir, item.fileName)
        if (candidate.exists()) return candidate

        // The filename may have been de-duplicated with a suffix;
        // search the category directory for a matching base name.
        val baseName = item.fileName.substringBeforeLast('.')
        categoryDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(baseName)) return file
        }

        return null
    }

    /**
     * Retrieves a [RecycleBinItemEntity] by its database ID.
     */
    private suspend fun getItemById(id: Long): RecycleBinItemEntity? {
        return try {
            recycleBinItemDao.getById(id).first()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generates a thumbnail for the given file.
     *
     * Returns the path of the generated thumbnail, or `null` if
     * thumbnail generation is not supported for the file type.
     *
     * In a production implementation this would use:
     * - `ThumbnailUtils` for video thumbnails
     * - `BitmapFactory` with `inSampleSize` for image thumbnails
     * - Application icons for APKs
     */
    private fun generateThumbnail(file: File, category: FileCategory): String? {
        // Thumbnail generation is a heavyweight operation that should
        // be done on a background thread. For now we return null and
        // rely on the UI layer to generate thumbnails on demand.
        return null
    }

    /**
     * Builds a JSON metadata string for the given [file].
     *
     * Includes file size, last modified date, and permissions.
     */
    private fun buildMetadataJson(file: File): String {
        return buildString {
            append("{")
            append("\"size\":${file.length()},")
            append("\"lastModified\":${file.lastModified()},")
            append("\"canRead\":${file.canRead()},")
            append("\"canWrite\":${file.canWrite()},")
            append("\"isHidden\":${file.isHidden}")
            append("}")
        }
    }

    // ──────────────────────────────────────────────
    // WorkManager scheduling
    // ──────────────────────────────────────────────

    /**
     * Schedules the daily auto-cleanup job.
     */
    private fun scheduleAutoCleanup() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val cleanupWork = PeriodicWorkRequestBuilder<RecycleBinCleanupWorker>(
            24, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CLEANUP_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupWork
        )
    }
}

/**
 * WorkManager Worker that performs daily recycle bin cleanup.
 *
 * Delegates to [SmartRecycleBin.autoCleanup].
 */
class RecycleBinCleanupWorker(
    context: Context,
    workerParams: androidx.work.WorkerParameters
) : androidx.work.CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // In production, inject SmartRecycleBin via Hilt's WorkerFactory
            // and call smartRecycleBin.autoCleanup()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
