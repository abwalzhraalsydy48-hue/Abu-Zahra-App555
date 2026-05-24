package com.ultimaterecovery.pro.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.webkit.MimeTypeMap
import com.ultimaterecovery.pro.utils.storage.formatFileSize
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.repository.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.Stack
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Professional file manager for Ultimate Recovery Pro.
 *
 * Provides a comprehensive set of file management operations including
 * browsing, search, batch operations, compression, storage analysis,
 * and bookmarking — all with proper error handling and progress
 * reporting via Kotlin [Flow].
 *
 * ## Browsing
 * - Internal storage, external storage, and SD card
 * - Hidden file visibility toggle
 * - Multiple sort criteria (name, size, date, type)
 *
 * ## File operations
 * - Copy, move, delete, rename
 * - Batch operations on multiple files
 * - Create files and folders
 *
 * ## Search
 * - Recursive filename search with extension filters
 * - Cancelable via coroutine cancellation
 *
 * ## Compression
 * - ZIP creation from multiple files/directories
 * - ZIP extraction with conflict resolution
 *
 * ## Storage analysis
 * - Total / used / free space per storage volume
 * - Largest files discovery
 * - Category-based size breakdown
 *
 * ## Bookmarks
 * - Persist favorite directories for quick access
 *
 * @see FileItem
 * @see StorageInfo
 * @see StorageAnalysis
 */
@Singleton
class FileManager @Inject constructor(
    private val context: Context
) {

    companion object {
        const val TAG = "FileManager"

        /** Buffer size for file copy/move operations (1 MB). */
        private const val BUFFER_SIZE = 1024 * 1024

        /** Buffer size for ZIP operations. */
        private const val ZIP_BUFFER_SIZE = 8192

        /** Preferences file for bookmarks. */
        private const val PREFS_NAME = "file_manager_prefs"

        /** Preferences key for bookmarked directories. */
        private const val KEY_BOOKMARKS = "bookmarked_dirs"

        /** Preferences key for show hidden files. */
        private const val KEY_SHOW_HIDDEN = "show_hidden_files"

        /** Preferences key for sort order. */
        private const val KEY_SORT_ORDER = "sort_order"
    }

    // ──────────────────────────────────────────────
    // Data models
    // ──────────────────────────────────────────────

    /**
     * Represents a file or directory with metadata for display.
     *
     * @property file       Underlying [File] object.
     * @property name       Display name.
     * @property path       Absolute path.
     * @property isDirectory Whether this is a directory.
     * @property size       File size in bytes (0 for directories).
     * @property lastModified Epoch millis of last modification.
     * @property mimeType   MIME type (inferred from extension).
     * @property isHidden   Whether the file is hidden.
     * @property fileCategory Logical category for grouping.
     * @property permissions Human-readable permission string.
     */
    data class FileItem(
        val file: File,
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val mimeType: String,
        val isHidden: Boolean,
        val fileCategory: FileCategory,
        val permissions: String
    ) {
        /** Human-readable size string (e.g. "1.5 MB"). */
        val formattedSize: String
            get() = formatFileSize(size)

        /** Human-readable date string. */
        val formattedDate: String
            get() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(java.util.Date(lastModified))

        /** File extension (empty string for directories). */
        val extension: String
            get() = if (isDirectory) "" else file.extension.lowercase()
    }

    /**
     * Sort order for file listings.
     */
    enum class SortOrder {
        NAME_ASC, NAME_DESC,
        SIZE_ASC, SIZE_DESC,
        DATE_ASC, DATE_DESC,
        TYPE_ASC, TYPE_DESC
    }

    /**
     * Storage volume information.
     *
     * @property path        Mount point path.
     * @property totalSpace  Total capacity in bytes.
     * @property usedSpace   Used space in bytes.
     * @property freeSpace   Free space in bytes.
     * @property isRemovable Whether this is removable storage (SD card).
     * @property label       Display label.
     */
    data class StorageInfo(
        val path: String,
        val totalSpace: Long,
        val usedSpace: Long,
        val freeSpace: Long,
        val isRemovable: Boolean,
        val label: String
    ) {
        /** Usage percentage (0–100). */
        val usagePercent: Int
            get() = if (totalSpace > 0) ((usedSpace.toFloat() / totalSpace) * 100).toInt() else 0

        /** Formatted total space. */
        val formattedTotal: String get() = formatFileSize(totalSpace)

        /** Formatted used space. */
        val formattedUsed: String get() = formatFileSize(usedSpace)

        /** Formatted free space. */
        val formattedFree: String get() = formatFileSize(freeSpace)
    }

    /**
     * Comprehensive storage analysis result.
     *
     * @property storageInfo     Per-volume storage information.
     * @property largestFiles    Top-N largest files on the device.
     * @property categoryBreakdown Size breakdown by file category.
     */
    data class StorageAnalysis(
        val storageInfo: List<StorageInfo>,
        val largestFiles: List<FileItem>,
        val categoryBreakdown: Map<FileCategory, Long>
    ) {
        /** Formatted breakdown with human-readable sizes. */
        val formattedBreakdown: Map<FileCategory, String>
            get() = categoryBreakdown.mapValues { formatFileSize(it.value) }

        /** Total size across all categories. */
        val totalAnalyzedSize: Long
            get() = categoryBreakdown.values.sum()
    }

    /**
     * Progress update for batch operations.
     *
     * @property total     Total number of items to process.
     * @property processed Number of items processed so far.
     * @property currentFile Name of the current file being processed.
     * @property operation Type of operation being performed.
     */
    data class BatchProgress(
        val total: Int,
        val processed: Int,
        val currentFile: String,
        val operation: String
    ) {
        /** Progress ratio 0.0 – 1.0. */
        val progress: Float
            get() = if (total > 0) processed.toFloat() / total else 0f
    }

    // ──────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Whether hidden files should be shown. */
    var showHiddenFiles: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HIDDEN, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()

    /** Current sort order. */
    var sortOrder: SortOrder
        get() = SortOrder.valueOf(prefs.getString(KEY_SORT_ORDER, SortOrder.NAME_ASC.name)!!)
        set(value) = prefs.edit().putString(KEY_SORT_ORDER, value.name).apply()

    // ──────────────────────────────────────────────
    // Browsing
    // ──────────────────────────────────────────────

    /**
     * Lists files in the given [path] directory.
     *
     * Returns a sorted list of [FileItem] objects representing the
     * contents of the directory. Directories are listed first,
     * followed by files, both sorted according to [sortOrder].
     *
     * Hidden files are included only when [showHiddenFiles] is true.
     *
     * @param path Absolute directory path.
     * @return [Resource] with the list of [FileItem]s.
     */
    suspend fun listFiles(path: String): Resource<List<FileItem>> {
        return try {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                return Resource.error("Directory does not exist: $path")
            }
            if (!dir.canRead()) {
                return Resource.error("Cannot read directory: $path")
            }

            val files = dir.listFiles()
                ?.filter { showHiddenFiles || !it.name.startsWith(".") }
                ?.map { toFileItem(it) }
                ?: return Resource.error("Failed to list files in: $path")

            val sorted = sortFileItems(files)
            Resource.success(sorted)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to list files")
        }
    }

    /**
     * Returns the default starting directory for the file browser
     * (the external storage root).
     */
    fun getDefaultDirectory(): File {
        return Environment.getExternalStorageDirectory()
    }

    /**
     * Returns information about all available storage volumes.
     */
    fun getStorageVolumes(): List<StorageInfo> {
        return try {
            val volumes = mutableListOf<StorageInfo>()

            // Internal storage
            val internal = Environment.getExternalStorageDirectory()
            volumes.add(getStorageInfo(internal, isRemovable = false, label = "Internal Storage"))

            // SD card (if available)
            getExternalSdCardPath()?.let { sdPath ->
                val sdDir = File(sdPath)
                if (sdDir.exists()) {
                    volumes.add(getStorageInfo(sdDir, isRemovable = true, label = "SD Card"))
                }
            }

            volumes
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ──────────────────────────────────────────────
    // File operations
    // ──────────────────────────────────────────────

    /**
     * Copies a file or directory from [src] to [dst].
     *
     * If [src] is a directory, its entire contents (including
     * subdirectories) are copied recursively.
     *
     * @param src Source file or directory path.
     * @param dst Destination file or directory path.
     * @return [Resource.Unit] on success.
     */
    suspend fun copyFile(src: String, dst: String): Resource<Unit> {
        return try {
            val source = File(src)
            val destination = File(dst)

            if (!source.exists()) return Resource.error("Source does not exist: $src")
            if (destination.exists()) return Resource.error("Destination already exists: $dst")

            if (source.isDirectory) {
                copyDirectory(source, destination)
            } else {
                destination.parentFile?.mkdirs()
                copySingleFile(source, destination)
            }

            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to copy file")
        }
    }

    /**
     * Moves a file or directory from [src] to [dst].
     *
     * First attempts an atomic rename. If that fails (e.g. cross-device
     * move), falls back to copy + delete.
     *
     * @param src Source file or directory path.
     * @param dst Destination path.
     * @return [Resource.Unit] on success.
     */
    suspend fun moveFile(src: String, dst: String): Resource<Unit> {
        return try {
            val source = File(src)
            val destination = File(dst)

            if (!source.exists()) return Resource.error("Source does not exist: $src")

            // Try atomic rename first
            destination.parentFile?.mkdirs()
            if (source.renameTo(destination)) {
                return Resource.success(Unit)
            }

            // Fallback: copy + delete (for cross-device moves)
            if (source.isDirectory) {
                copyDirectory(source, destination)
                deleteRecursive(source)
            } else {
                copySingleFile(source, destination)
                source.delete()
            }

            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to move file")
        }
    }

    /**
     * Deletes a file or directory at [path].
     *
     * Directories are deleted recursively (all contents removed).
     *
     * @param path Absolute path to delete.
     * @return [Resource.Unit] on success.
     */
    suspend fun deleteFile(path: String): Resource<Unit> {
        return try {
            val file = File(path)
            if (!file.exists()) return Resource.error("File does not exist: $path")

            val deleted = if (file.isDirectory) {
                deleteRecursive(file)
            } else {
                file.delete()
            }

            if (deleted) Resource.success(Unit)
            else Resource.error("Failed to delete: $path")
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to delete file")
        }
    }

    /**
     * Renames a file or directory.
     *
     * @param path    Absolute path of the file to rename.
     * @param newName New name (just the filename, not the full path).
     * @return [Resource] with the new absolute path.
     */
    suspend fun renameFile(path: String, newName: String): Resource<String> {
        return try {
            val source = File(path)
            if (!source.exists()) return Resource.error("File does not exist: $path")

            val sanitized = newName.replace(Regex("[<>:\"/\\\\|?*]"), "_")
            val destination = File(source.parentFile, sanitized)

            if (destination.exists()) return Resource.error("A file with that name already exists")

            if (source.renameTo(destination)) {
                Resource.success(destination.absolutePath)
            } else {
                Resource.error("Failed to rename file")
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to rename file")
        }
    }

    /**
     * Creates a new directory at [path].
     *
     * @param path Absolute path of the new directory.
     * @return [Resource.Unit] on success.
     */
    suspend fun createFolder(path: String): Resource<Unit> {
        return try {
            val dir = File(path)
            if (dir.exists()) return Resource.error("Directory already exists: $path")
            if (dir.mkdirs()) Resource.success(Unit)
            else Resource.error("Failed to create directory: $path")
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to create folder")
        }
    }

    /**
     * Creates a new empty file at [path].
     *
     * @param path Absolute path of the new file.
     * @return [Resource.Unit] on success.
     */
    suspend fun createFile(path: String): Resource<Unit> {
        return try {
            val file = File(path)
            if (file.exists()) return Resource.error("File already exists: $path")
            file.parentFile?.mkdirs()
            if (file.createNewFile()) Resource.success(Unit)
            else Resource.error("Failed to create file: $path")
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to create file")
        }
    }

    // ──────────────────────────────────────────────
    // Batch operations
    // ──────────────────────────────────────────────

    /**
     * Copies multiple files/directories to a destination directory.
     *
     * Emits [BatchProgress] updates as each file is processed.
     *
     * @param sources    List of source paths.
     * @param destDir    Destination directory path.
     * @return A cold [Flow] of [BatchProgress].
     */
    fun batchCopy(sources: List<String>, destDir: String): Flow<BatchProgress> = flow {
        val dest = File(destDir)
        if (!dest.exists()) dest.mkdirs()

        for ((index, srcPath) in sources.withIndex()) {
            if (!currentCoroutineContext().isActive) break

            val source = File(srcPath)
            val destination = File(dest, source.name)

            try {
                if (source.isDirectory) {
                    copyDirectory(source, destination)
                } else {
                    copySingleFile(source, destination)
                }
            } catch (_: Exception) {}

            emit(BatchProgress(
                total = sources.size,
                processed = index + 1,
                currentFile = source.name,
                operation = "Copying"
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Moves multiple files/directories to a destination directory.
     *
     * @param sources List of source paths.
     * @param destDir Destination directory path.
     * @return A cold [Flow] of [BatchProgress].
     */
    fun batchMove(sources: List<String>, destDir: String): Flow<BatchProgress> = flow {
        val dest = File(destDir)
        if (!dest.exists()) dest.mkdirs()

        for ((index, srcPath) in sources.withIndex()) {
            if (!currentCoroutineContext().isActive) break

            val source = File(srcPath)
            val destination = File(dest, source.name)

            try {
                // Try rename first
                if (!source.renameTo(destination)) {
                    // Fallback: copy + delete
                    if (source.isDirectory) {
                        copyDirectory(source, destination)
                        deleteRecursive(source)
                    } else {
                        copySingleFile(source, destination)
                        source.delete()
                    }
                }
            } catch (_: Exception) {}

            emit(BatchProgress(
                total = sources.size,
                processed = index + 1,
                currentFile = source.name,
                operation = "Moving"
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Deletes multiple files/directories.
     *
     * @param paths List of paths to delete.
     * @return A cold [Flow] of [BatchProgress].
     */
    fun batchDelete(paths: List<String>): Flow<BatchProgress> = flow {
        for ((index, path) in paths.withIndex()) {
            if (!currentCoroutineContext().isActive) break

            val file = File(path)
            try {
                if (file.isDirectory) {
                    deleteRecursive(file)
                } else {
                    file.delete()
                }
            } catch (_: Exception) {}

            emit(BatchProgress(
                total = paths.size,
                processed = index + 1,
                currentFile = file.name,
                operation = "Deleting"
            ))
        }
    }.flowOn(Dispatchers.IO)

    // ──────────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────────

    /**
     * Recursively searches for files matching [query] under [path].
     *
     * The search is case-insensitive and matches against the filename.
     * Optional [extensionFilter] restricts results to specific file types.
     *
     * Results are emitted incrementally as they are found.
     *
     * @param query           Search term (substring match on filename).
     * @param path            Root directory to search.
     * @param extensionFilter Optional set of file extensions to include (without dots).
     * @param maxDepth        Maximum directory depth to search (-1 = unlimited).
     * @return A cold [Flow] of matching [FileItem]s.
     */
    fun searchFiles(
        query: String,
        path: String,
        extensionFilter: Set<String>? = null,
        maxDepth: Int = -1
    ): Flow<FileItem> = flow {
        val rootDir = File(path)
        if (!rootDir.exists() || !rootDir.isDirectory) return@flow

        val lowerQuery = query.lowercase()
        val stack = Stack<Pair<File, Int>>()
        stack.push(Pair(rootDir, 0))

        while (stack.isNotEmpty() && currentCoroutineContext().isActive) {
            val (dir, depth) = stack.pop()

            dir.listFiles()?.forEach { file ->
                if (!currentCoroutineContext().isActive) return@forEach

                val nameMatch = file.name.lowercase().contains(lowerQuery)
                val extMatch = extensionFilter == null ||
                        file.extension.lowercase() in extensionFilter

                if (nameMatch && extMatch) {
                    emit(toFileItem(file))
                }

                if (file.isDirectory && (maxDepth < 0 || depth < maxDepth)) {
                    stack.push(Pair(file, depth + 1))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ──────────────────────────────────────────────
    // Storage analysis
    // ──────────────────────────────────────────────

    /**
     * Performs a comprehensive storage analysis.
     *
     * Scans the primary storage to find the largest files and
     * compute a category-based size breakdown.
     *
     * @param topN Number of largest files to return.
     * @return [Resource] with [StorageAnalysis].
     */
    suspend fun getStorageAnalysis(topN: Int = 20): Resource<StorageAnalysis> {
        return try {
            val storageVolumes = getStorageVolumes()
            val largestFiles = mutableListOf<FileItem>()
            val categorySizes = mutableMapOf<FileCategory, Long>()

            // Initialize all categories to 0
            FileCategory.entries.forEach { categorySizes[it] = 0L }

            // Scan primary storage
            val rootDir = Environment.getExternalStorageDirectory()
            scanForAnalysis(rootDir, largestFiles, categorySizes, topN)

            // Sort largest files by size (descending)
            largestFiles.sortByDescending { it.size }

            Resource.success(StorageAnalysis(
                storageInfo = storageVolumes,
                largestFiles = largestFiles.take(topN),
                categoryBreakdown = categorySizes
            ))
        } catch (e: Exception) {
            Resource.error(e.message ?: "Storage analysis failed")
        }
    }

    /**
     * Recursively scans directories for storage analysis.
     */
    private fun scanForAnalysis(
        dir: File,
        largestFiles: MutableList<FileItem>,
        categorySizes: MutableMap<FileCategory, Long>,
        topN: Int
    ) {
        dir.listFiles()?.forEach { file ->
            try {
                if (file.isDirectory) {
                    scanForAnalysis(file, largestFiles, categorySizes, topN)
                } else {
                    val item = toFileItem(file)
                    val category = item.fileCategory
                    categorySizes[category] = (categorySizes[category] ?: 0L) + file.length()

                    // Add to largest files candidate list
                    if (largestFiles.size < topN * 2) {
                        largestFiles.add(item)
                    } else if (file.length() > (largestFiles.minByOrNull { it.size }?.size ?: 0L)) {
                        largestFiles.add(item)
                        // Trim to keep list bounded
                        if (largestFiles.size > topN * 2) {
                            largestFiles.sortByDescending { it.size }
                            while (largestFiles.size > topN) {
                                largestFiles.removeAt(largestFiles.size - 1)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Skip inaccessible files
            }
        }
    }

    // ──────────────────────────────────────────────
    // Compression
    // ──────────────────────────────────────────────

    /**
     * Compresses multiple files/directories into a ZIP archive.
     *
     * @param files      List of source file/directory paths.
     * @param outputPath Output ZIP file path.
     * @return A cold [Flow] of [BatchProgress].
     */
    fun compressFiles(files: List<String>, outputPath: String): Flow<BatchProgress> = flow {
        val zipFile = File(outputPath)
        zipFile.parentFile?.mkdirs()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zipOut ->
            var processed = 0

            for (srcPath in files) {
                if (!currentCoroutineContext().isActive) break

                val source = File(srcPath)
                if (!source.exists()) continue

                if (source.isDirectory) {
                    compressDirectory(source, source.name, zipOut)
                } else {
                    addFileToZip(source, source.name, zipOut)
                }

                processed++
                emit(BatchProgress(
                    total = files.size,
                    processed = processed,
                    currentFile = source.name,
                    operation = "Compressing"
                ))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Decompresses a ZIP archive to the specified output directory.
     *
     * Existing files are overwritten if they conflict with entries
     * in the archive.
     *
     * @param zipPath  Path to the ZIP file.
     * @param outputDir Target directory for extraction.
     * @return A cold [Flow] of [BatchProgress].
     */
    fun decompressFile(zipPath: String, outputDir: String): Flow<BatchProgress> = flow {
        val zipFile = File(zipPath)
        if (!zipFile.exists()) return@flow

        val outDir = File(outputDir)
        if (!outDir.exists()) outDir.mkdirs()

        var totalEntries = 0
        var processedEntries = 0

        // First pass: count entries
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipIn ->
            while (zipIn.nextEntry != null) {
                totalEntries++
                zipIn.closeEntry()
            }
        }

        // Second pass: extract
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipIn ->
            var entry: ZipEntry?
            while (zipIn.nextEntry.also { entry = it } != null) {
                if (!currentCoroutineContext().isActive) break

                val currentEntry = entry!!
                val outFile = File(outDir, currentEntry.name)

                // Security: prevent zip slip
                if (!outFile.canonicalPath.startsWith(outDir.canonicalPath)) {
                    zipIn.closeEntry()
                    continue
                }

                if (currentEntry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                        val buffer = ByteArray(ZIP_BUFFER_SIZE)
                        var bytesRead: Int
                        while (zipIn.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                    }
                }

                zipIn.closeEntry()
                processedEntries++

                emit(BatchProgress(
                    total = totalEntries,
                    processed = processedEntries,
                    currentFile = currentEntry.name,
                    operation = "Extracting"
                ))
            }
        }
    }.flowOn(Dispatchers.IO)

    // ──────────────────────────────────────────────
    // File details and opening
    // ──────────────────────────────────────────────

    /**
     * Returns detailed information about a file.
     *
     * @param path Absolute file path.
     * @return [Resource] with [FileItem].
     */
    suspend fun getFileDetails(path: String): Resource<FileItem> {
        return try {
            val file = File(path)
            if (!file.exists()) return Resource.error("File does not exist: $path")
            Resource.success(toFileItem(file))
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to get file details")
        }
    }

    /**
     * Opens a file with the system's default handler.
     *
     * Falls back to a chooser if no default handler is set.
     *
     * @param path File path to open.
     * @return [Resource.Unit] on success.
     */
    fun openFile(path: String): Resource<Unit> {
        return try {
            val file = File(path)
            if (!file.exists()) return Resource.error("File does not exist")

            val uri = getFileUri(file)
            val mimeType = getMimeType(file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })

            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to open file")
        }
    }

    /**
     * Shares one or more files via the system share sheet.
     *
     * @param paths List of file paths to share.
     * @return [Resource.Unit] on success.
     */
    fun shareFiles(paths: List<String>): Resource<Unit> {
        return try {
            if (paths.isEmpty()) return Resource.error("No files to share")

            val uris = paths.map { getFileUri(File(it)) }

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = getMimeType(File(paths[0]))
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }

            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            context.startActivity(Intent.createChooser(intent, "Share files").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })

            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to share files")
        }
    }

    // ──────────────────────────────────────────────
    // Bookmarks
    // ──────────────────────────────────────────────

    /**
     * Returns all bookmarked directory paths.
     */
    fun getBookmarks(): List<String> {
        return prefs.getStringSet(KEY_BOOKMARKS, emptySet())?.toList() ?: emptyList()
    }

    /**
     * Adds a directory path to bookmarks.
     *
     * @param path Directory path to bookmark.
     */
    fun addBookmark(path: String) {
        val bookmarks = prefs.getStringSet(KEY_BOOKMARKS, emptySet())?.toMutableSet() ?: mutableSetOf()
        bookmarks.add(path)
        prefs.edit().putStringSet(KEY_BOOKMARKS, bookmarks).apply()
    }

    /**
     * Removes a directory path from bookmarks.
     *
     * @param path Directory path to remove.
     */
    fun removeBookmark(path: String) {
        val bookmarks = prefs.getStringSet(KEY_BOOKMARKS, emptySet())?.toMutableSet() ?: mutableSetOf()
        bookmarks.remove(path)
        prefs.edit().putStringSet(KEY_BOOKMARKS, bookmarks).apply()
    }

    /**
     * Checks if a directory is bookmarked.
     */
    fun isBookmarked(path: String): Boolean {
        return prefs.getStringSet(KEY_BOOKMARKS, emptySet())?.contains(path) == true
    }

    // ──────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────

    /**
     * Converts a [File] to a [FileItem].
     */
    private fun toFileItem(file: File): FileItem {
        return FileItem(
            file = file,
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) 0L else file.length(),
            lastModified = file.lastModified(),
            mimeType = getMimeType(file),
            isHidden = file.name.startsWith("."),
            fileCategory = categorizeFile(file),
            permissions = getPermissionsString(file)
        )
    }

    /**
     * Sorts a list of [FileItem]s according to [sortOrder].
     *
     * Directories always come first regardless of sort order.
     */
    private fun sortFileItems(items: List<FileItem>): List<FileItem> {
        val (dirs, files) = items.partition { it.isDirectory }

        val comparator: Comparator<FileItem> = when (sortOrder) {
            SortOrder.NAME_ASC -> compareBy { it.name.lowercase() }
            SortOrder.NAME_DESC -> compareByDescending { it.name.lowercase() }
            SortOrder.SIZE_ASC -> compareBy { it.size }
            SortOrder.SIZE_DESC -> compareByDescending { it.size }
            SortOrder.DATE_ASC -> compareBy { it.lastModified }
            SortOrder.DATE_DESC -> compareByDescending { it.lastModified }
            SortOrder.TYPE_ASC -> compareBy { it.mimeType }
            SortOrder.TYPE_DESC -> compareByDescending { it.mimeType }
        }

        return dirs.sortedWith(comparator) + files.sortedWith(comparator)
    }

    /**
     * Copies a single file using NIO [FileChannel] for efficiency.
     */
    private fun copySingleFile(source: File, destination: File) {
        FileInputStream(source).use { fis ->
            FileOutputStream(destination).use { fos ->
                val sourceChannel: FileChannel = fis.channel
                val destChannel: FileChannel = fos.channel

                var position = 0L
                val size = sourceChannel.size()

                while (position < size) {
                    val transferred = destChannel.transferFrom(sourceChannel, position, BUFFER_SIZE.toLong())
                    if (transferred <= 0) break
                    position += transferred
                }
            }
        }
    }

    /**
     * Copies a directory recursively.
     */
    private fun copyDirectory(source: File, destination: File) {
        if (!destination.exists()) destination.mkdirs()

        source.listFiles()?.forEach { child ->
            val destChild = File(destination, child.name)
            if (child.isDirectory) {
                copyDirectory(child, destChild)
            } else {
                copySingleFile(child, destChild)
            }
        }
    }

    /**
     * Deletes a file or directory recursively.
     *
     * @return `true` if the deletion succeeded.
     */
    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }
        return file.delete()
    }

    /**
     * Compresses a directory into a ZIP archive.
     */
    private fun compressDirectory(dir: File, basePath: String, zipOut: ZipOutputStream) {
        dir.listFiles()?.forEach { file ->
            val entryPath = "$basePath/${file.name}"
            if (file.isDirectory) {
                zipOut.putNextEntry(ZipEntry("$entryPath/"))
                zipOut.closeEntry()
                compressDirectory(file, entryPath, zipOut)
            } else {
                addFileToZip(file, entryPath, zipOut)
            }
        }
    }

    /**
     * Adds a single file to a ZIP archive.
     */
    private fun addFileToZip(file: File, entryName: String, zipOut: ZipOutputStream) {
        zipOut.putNextEntry(ZipEntry(entryName))
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(ZIP_BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                zipOut.write(buffer, 0, bytesRead)
            }
        }
        zipOut.closeEntry()
    }

    /**
     * Returns the MIME type for a file based on its extension.
     */
    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    /**
     * Categorizes a file based on its extension.
     */
    private fun categorizeFile(file: File): FileCategory {
        if (file.isDirectory) return FileCategory.OTHER

        val ext = file.extension.lowercase()
        return when {
            ext in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "raw", "heic", "heif") -> FileCategory.PHOTO
            ext in setOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "3gp", "webm", "m4v") -> FileCategory.VIDEO
            ext in setOf("mp3", "wav", "flac", "aac", "ogg", "wma", "m4a") -> FileCategory.AUDIO
            ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf") -> FileCategory.DOCUMENT
            ext in setOf("zip", "rar", "7z", "tar", "gz") -> FileCategory.ARCHIVE
            ext == "apk" -> FileCategory.APK
            else -> FileCategory.OTHER
        }
    }

    /**
     * Returns a human-readable permissions string for a file.
     */
    private fun getPermissionsString(file: File): String {
        val sb = StringBuilder()
        if (file.canRead()) sb.append("r") else sb.append("-")
        if (file.canWrite()) sb.append("w") else sb.append("-")
        if (file.canExecute()) sb.append("x") else sb.append("-")
        return sb.toString()
    }

    /**
     * Returns a content URI for the given file using FileProvider.
     */
    private fun getFileUri(file: File): Uri {
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Returns [StorageInfo] for a given storage root.
     */
    private fun getStorageInfo(file: File, isRemovable: Boolean, label: String): StorageInfo {
        val stat = StatFs(file.absolutePath)
        val totalSpace = stat.totalBytes
        val freeSpace = stat.availableBytes
        val usedSpace = totalSpace - freeSpace

        return StorageInfo(
            path = file.absolutePath,
            totalSpace = totalSpace,
            usedSpace = usedSpace,
            freeSpace = freeSpace,
            isRemovable = isRemovable,
            label = label
        )
    }

    /**
     * Attempts to detect the external SD card mount point.
     */
    private fun getExternalSdCardPath(): String? {
        val commonPaths = listOf(
            "/storage/sdcard1", "/storage/extSdCard",
            "/storage/external_SD", "/mnt/sdcard/external_sd",
            "/mnt/external_sd"
        )
        for (path in commonPaths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory && dir.canRead()) return path
        }
        return null
    }

    // ──────────────────────────────────────────────
    // Formatting utilities
    // ──────────────────────────────────────────────

    /**
     * Formats a byte count into a human-readable size string.
     *
     * E.g. `formatFileSize(1536)` → `"1.5 KB"`
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            .coerceIn(0, units.size - 1)

        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.getDefault(), "%.1f %s", value, units[digitGroups])
    }
}
