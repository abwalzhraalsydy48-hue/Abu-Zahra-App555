package com.ultimaterecovery.pro.engine.scanner

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
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
 * Quick Scanner - Searches the file system for deleted/lost files
 *
 * This scanner uses multiple strategies to find deleted files quickly:
 *
 * 1. Scan MediaStore for entries where the actual file is MISSING (deleted but entry remains)
 * 2. Scan trash/deleted folders (.Trash, .deleted, $RECYCLE.BIN, etc.)
 * 3. Scan thumbnail cache directories (may contain thumbnails of deleted photos)
 * 4. Scan .nomedia directories (hidden from gallery, potential recovery candidates)
 * 5. Scan temp/cache directories (temporary files that may be recoverable)
 * 6. Scan app-specific deleted folders (WhatsApp .Trash, Telegram recent, etc.)
 *
 * IMPORTANT: This scanner does NOT scan regular folders like DCIM/Camera or Pictures
 * for existing files - it ONLY looks for deleted/lost/recoverable files.
 */
@Singleton
class QuickScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** File header size for type identification (512 bytes) */
        private const val HEADER_READ_SIZE = 512

        /** Minimum file size to include (1 KB) */
        private const val MIN_FILE_SIZE = 1024L

        /** Trash folder name patterns */
        private val TRASH_PATTERNS = listOf(
            ".Trash", ".trash", ".Trashes",
            "Trash", "trash", "Trashes",
            ".deleted", "deleted",
            ".recently_deleted", "recently_deleted",
            ".recent", "recent",
            "Recently Deleted", "RecentlyDeleted",
            ".Recycle", "Recycle", "\$RECYCLE.BIN",
            ".Trash-1000", ".Trash-1001",
            "Deleted", ".Deleted",
            "bin", ".bin" // Some apps use 'bin' for deleted items
        )

        /** Thumbnail cache directory patterns */
        private val THUMBNAIL_DIRS = listOf(
            ".thumbnails", "thumbnails", ".thumbdata",
            "thumb", ".thumb", "Thumb",
            "cache", ".cache"
        )

        /** Temp/cache directory patterns */
        private val CACHE_PATTERNS = listOf(
            "cache", "Cache", ".cache",
            "temp", "Temp", ".temp", "tmp",
            "backup", "Backup", ".backup"
        )

        /** App-specific trash folders */
        private val APP_TRASH_PATHS = listOf(
            // WhatsApp
            "WhatsApp/.Trash",
            "WhatsApp/Media/.Trash",
            "WhatsApp/.deleted",
            "WhatsApp Business/.Trash",
            "WhatsApp Business/Media/.Trash",
            // Telegram
            "Telegram/.Trash",
            "Telegram/.deleted",
            // Signal
            "Signal/.Trash",
            // Viber
            "Viber/.Trash",
            // General
            "Android/data/com.whatsapp/files/.Trash",
            "Android/data/org.telegram.messenger/files/.Trash"
        )

        /** Patterns for recently deleted files (filename contains these) */
        private val DELETED_FILE_PATTERNS = listOf(
            ".deleted", ".trash", ".tmp", ".temp",
            ".bak", ".backup", ".old", "_deleted",
            "_trash", "_old", "_backup"
        )

        /** Directories to SKIP (these contain normal existing files) */
        private val SKIP_DIRECTORIES = setOf(
            "Camera", "camera", "Screenshots", "screenshots",
            "ScreenRecorder", "Edit", "Photo Editor",
            "Movies", "Music", "Podcasts", "Audiobooks",
            "Notifications", "Ringtones", "Alarms"
        )
    }

    /**
     * Start the quick scan
     *
     * Scans for deleted/recoverable files only, NOT all existing files.
     *
     * @param paths List of paths to scan (if empty, uses default recovery paths)
     * @param categories File categories to search for (empty = all categories)
     * @return Flow of scan state with results
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

        val targetCategories = if (categories.isEmpty()) FileCategory.values().toList() else categories

        try {
            // ═══════════════════════════════════════════
            // Phase 1: Scan MediaStore for MISSING files
            // (Files that exist in database but actual file is deleted)
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.05f,
                currentPath = "Scanning for deleted media entries...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            scanMediaStoreForDeletedFiles(foundFiles, targetCategories)

            // ═══════════════════════════════════════════
            // Phase 2: Scan trash/deleted folders
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.20f,
                currentPath = "Scanning trash folders...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            for (trashDir in findTrashDirectories()) {
                if (!currentCoroutineContext().isActive) return@flow
                val dir = File(trashDir)
                if (dir.exists() && dir.isDirectory) {
                    scanDirectory(
                        dir, foundFiles, scannedPaths, targetCategories,
                        maxDepth = 5,
                        confidence = RecoveryConfidence.HIGH,
                        isLikelyDeleted = true
                    )
                    directoriesScanned++
                }
            }

            // ═══════════════════════════════════════════
            // Phase 3: Scan app-specific trash folders
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.35f,
                currentPath = "Scanning app deleted folders...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            for (appTrashPath in findAppTrashDirectories()) {
                if (!currentCoroutineContext().isActive) return@flow
                val dir = File(appTrashPath)
                if (dir.exists() && dir.isDirectory) {
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
            // Phase 4: Scan thumbnail cache directories
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.50f,
                currentPath = "Scanning thumbnail caches...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            for (thumbDir in findThumbnailDirectories()) {
                if (!currentCoroutineContext().isActive) return@flow
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
            // Phase 5: Scan .nomedia directories
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.65f,
                currentPath = "Scanning hidden directories...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            for (nomediaDir in findNomediaDirectories()) {
                if (!currentCoroutineContext().isActive) return@flow
                val dir = File(nomediaDir)
                if (dir.exists() && dir.isDirectory) {
                    scanDirectory(
                        dir, foundFiles, scannedPaths, targetCategories,
                        maxDepth = 4,
                        confidence = RecoveryConfidence.MEDIUM,
                        isLikelyDeleted = false
                    )
                    directoriesScanned++
                }
            }

            // ═══════════════════════════════════════════
            // Phase 6: Scan temp/cache directories
            // ═══════════════════════════════════════════
            emit(ScanState.Scanning(
                progress = 0.80f,
                currentPath = "Scanning cache directories...",
                filesFound = foundFiles.size,
                scanType = ScanType.QUICK
            ))

            for (cacheDir in findCacheDirectories()) {
                if (!currentCoroutineContext().isActive) return@flow
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
            // Phase 7: Scan user-specified paths (only for deleted indicators)
            // ═══════════════════════════════════════════
            if (paths.isNotEmpty()) {
                emit(ScanState.Scanning(
                    progress = 0.90f,
                    currentPath = "Scanning specified paths...",
                    filesFound = foundFiles.size,
                    scanType = ScanType.QUICK
                ))

                for (path in paths) {
                    if (!currentCoroutineContext().isActive) return@flow
                    val dir = File(path)
                    if (dir.exists() && dir.isDirectory && path !in scannedPaths) {
                        // Only scan for files with deleted indicators in user paths
                        scanDirectoryForDeletedFilesOnly(
                            dir, foundFiles, scannedPaths, targetCategories, maxDepth = 4
                        )
                        directoriesScanned++
                    }
                }
            }

            // ═══════════════════════════════════════════
            // Remove duplicates and return results
            // ═══════════════════════════════════════════
            val uniqueFiles = foundFiles
                .distinctBy { Triple(it.path, it.fileSize, it.lastModified) }
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
                error = e.message ?: "Quick scan error",
                scanType = ScanType.QUICK,
                partialResults = foundFiles.distinctBy { Triple(it.path, it.fileSize, it.lastModified) }
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Scan MediaStore for files where the entry exists but the actual file is MISSING
     * This is the primary way to find recently deleted files
     */
    private suspend fun scanMediaStoreForDeletedFiles(
        foundFiles: MutableList<FoundFileInfo>,
        categories: List<FileCategory>
    ) {
        // Scan images for missing files
        if (categories.isEmpty() || FileCategory.PHOTO in categories) {
            scanMediaStoreForMissingFiles(
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

        // Scan videos for missing files
        if (categories.isEmpty() || FileCategory.VIDEO in categories) {
            scanMediaStoreForMissingFiles(
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

        // Scan audio for missing files
        if (categories.isEmpty() || FileCategory.AUDIO in categories) {
            scanMediaStoreForMissingFiles(
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

        // Scan downloads for missing files (Android 10+)
        if (categories.isEmpty() || FileCategory.DOCUMENT in categories) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                scanMediaStoreForMissingFiles(
                    uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection = arrayOf(
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DATA,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.DATE_MODIFIED,
                        MediaStore.Downloads.MIME_TYPE
                    ),
                    category = FileCategory.DOCUMENT,
                    foundFiles = foundFiles
                )
            }
        }
    }

    /**
     * Scan a MediaStore collection for entries where the actual file is missing
     */
    private fun scanMediaStoreForMissingFiles(
        uri: Uri,
        projection: Array<String>,
        category: FileCategory,
        foundFiles: MutableList<FoundFileInfo>
    ) {
        try {
            val cursor: Cursor? = context.contentResolver.query(
                uri, projection, null, null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val dataColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val mimeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                while (it.moveToNext()) {
                    try {
                        val path = it.getString(dataColumn) ?: continue
                        val name = it.getString(nameColumn) ?: continue
                        val size = it.getLong(sizeColumn)
                        val date = it.getLong(dateColumn) * 1000
                        val mimeType = it.getString(mimeColumn)

                        val file = File(path)
                        
                        // KEY: Only include if the file is MISSING (deleted but entry remains)
                        // This is the primary way to find deleted files!
                        if (file.exists()) continue
                        
                        // Skip if already found
                        if (foundFiles.any { f -> f.path == path }) continue

                        val extension = File(path).extension.lowercase()

                        foundFiles.add(FoundFileInfo(
                            path = path,
                            fileName = name,
                            fileSize = size,
                            extension = extension,
                            mimeType = mimeType ?: getMimeTypeForExtension(extension),
                            category = category,
                            signatureName = null,
                            confidence = RecoveryConfidence.HIGH, // High confidence - was in MediaStore
                            lastModified = date,
                            isRootRequired = false,
                            sourcePath = path,
                            metadata = mapOf(
                                "source" to "mediastore_deleted",
                                "file_name" to name,
                                "file_size" to size.toString(),
                                "file_missing" to "true"
                            )
                        ))
                    } catch (_: Exception) {
                        // Skip problematic entries
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore MediaStore errors
        }
    }

    /**
     * Scan a directory for files - used for trash/cache directories
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

        // Skip directories that contain normal existing files
        val dirName = directory.name
        if (dirName in SKIP_DIRECTORIES && !isLikelyDeleted) return

        scannedPaths.add(directory.canonicalPath)

        val files = directory.listFiles() ?: return

        for (file in files) {
            if (!currentCoroutineContext().isActive) return

            try {
                if (file.isDirectory) {
                    // Recursively scan subdirectories
                    scanDirectory(
                        file, foundFiles, scannedPaths, categories,
                        maxDepth - 1, confidence, isLikelyDeleted
                    )
                } else if (file.isFile && file.length() >= MIN_FILE_SIZE) {
                    val fileInfo = identifyFile(file, categories, confidence, isLikelyDeleted)
                    if (fileInfo != null) {
                        foundFiles.add(fileInfo)
                    }
                }
            } catch (_: SecurityException) {
                // Ignore unreadable files
            } catch (_: Exception) {
                // Ignore individual errors
            }
        }
    }

    /**
     * Scan a directory ONLY for files with deleted indicators
     * Used for user-specified paths - we don't want to scan all files, only potential deleted ones
     */
    private suspend fun scanDirectoryForDeletedFilesOnly(
        directory: File,
        foundFiles: MutableList<FoundFileInfo>,
        scannedPaths: MutableSet<String>,
        categories: List<FileCategory>,
        maxDepth: Int = 4
    ) {
        if (maxDepth <= 0) return
        if (!directory.exists() || !directory.isDirectory) return
        if (directory.canonicalPath in scannedPaths) return
        if (!directory.canRead()) return

        // Skip standard media directories - they contain normal files
        val dirName = directory.name
        if (dirName in SKIP_DIRECTORIES) return

        scannedPaths.add(directory.canonicalPath)

        val files = directory.listFiles() ?: return

        for (file in files) {
            if (!currentCoroutineContext().isActive) return

            try {
                if (file.isDirectory) {
                    // Check if directory name suggests it's a trash/deleted folder
                    val lowerDirName = file.name.lowercase()
                    val isTrashDir = TRASH_PATTERNS.any { pattern -> 
                        lowerDirName.contains(pattern.lowercase()) 
                    }
                    
                    if (isTrashDir) {
                        // This is a trash folder - scan all files
                        scanDirectory(
                            file, foundFiles, scannedPaths, categories,
                            maxDepth - 1, RecoveryConfidence.HIGH, true
                        )
                    } else {
                        // Not a trash folder - continue looking for trash folders
                        scanDirectoryForDeletedFilesOnly(
                            file, foundFiles, scannedPaths, categories, maxDepth - 1
                        )
                    }
                } else if (file.isFile && file.length() >= MIN_FILE_SIZE) {
                    // Check if file name suggests it's deleted
                    val lowerFileName = file.name.lowercase()
                    val isDeletedFile = DELETED_FILE_PATTERNS.any { pattern ->
                        lowerFileName.contains(pattern)
                    }
                    
                    // Also check if file is in a trash-like path
                    val parentPath = file.parent?.lowercase() ?: ""
                    val isInTrashPath = TRASH_PATTERNS.any { pattern ->
                        parentPath.contains(pattern.lowercase())
                    }
                    
                    if (isDeletedFile || isInTrashPath) {
                        val fileInfo = identifyFile(
                            file, categories, 
                            RecoveryConfidence.HIGH, 
                            true
                        )
                        if (fileInfo != null) {
                            foundFiles.add(fileInfo)
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore individual errors
            }
        }
    }

    /**
     * Identify a file using signatures
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

            // First: Try to identify by extension (faster)
            var category = categorizeByExtension(extension)

            // Second: Verify with signatures (more accurate)
            val header = readFileHeader(file)
            val signature = if (header != null) {
                FileSignatures.identifyFileType(header, category)
            } else null

            // If we found a signature, use its info
            if (signature != null) {
                category = signature.category
            }

            // Check if category is requested
            if (categories.isNotEmpty() && category !in categories) {
                return null
            }

            // Determine confidence level
            val actualConfidence = when {
                isLikelyDeleted -> confidence
                file.isHidden -> RecoveryConfidence.MEDIUM
                else -> confidence
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
                metadata = buildMetadataMap(file, signature, isLikelyDeleted)
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Read file header (first bytes)
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
     * Categorize file by extension
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
     * Get MIME type from extension
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
     * Build metadata map for file
     */
    private fun buildMetadataMap(
        file: File, 
        signature: FileSignatures.FileSignature?,
        isLikelyDeleted: Boolean
    ): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        metadata["file_name"] = file.name
        metadata["file_size"] = file.length().toString()
        metadata["last_modified"] = file.lastModified().toString()
        metadata["is_hidden"] = file.isHidden.toString()
        metadata["is_readable"] = file.canRead().toString()
        metadata["parent_dir"] = file.parent ?: ""
        metadata["is_likely_deleted"] = isLikelyDeleted.toString()
        signature?.let {
            metadata["signature_name"] = it.name
            metadata["detected_mime"] = it.mimeType
        }
        return metadata
    }

    // ──────────────────────────────────────────────
    // Directory discovery functions
    // ──────────────────────────────────────────────

    /**
     * Find trash/deleted directories
     */
    private fun findTrashDirectories(): List<String> {
        val dirs = mutableListOf<String>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        // Standard trash folders
        for (pattern in TRASH_PATTERNS) {
            dirs.add("$externalStorage/$pattern")
            dirs.add("$externalStorage/DCIM/$pattern")
            dirs.add("$externalStorage/Pictures/$pattern")
            dirs.add("$externalStorage/Movies/$pattern")
            dirs.add("$externalStorage/Music/$pattern")
            dirs.add("$externalStorage/Downloads/$pattern")
        }

        // Android 11+ media trash folder
        dirs.add("$externalStorage/Android/data/com.android.providers.media/trash")

        // Search for additional trash folders
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

        return dirs.filter { File(it).exists() }.distinct()
    }

    /**
     * Find app-specific trash directories
     */
    private fun findAppTrashDirectories(): List<String> {
        val dirs = mutableListOf<String>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        for (appPath in APP_TRASH_PATHS) {
            dirs.add("$externalStorage/$appPath")
        }

        // WhatsApp specific: .Sent and .Private folders may contain deleted files
        dirs.add("$externalStorage/WhatsApp/Media/WhatsApp Images/.Sent")
        dirs.add("$externalStorage/WhatsApp/Media/WhatsApp Images/Private")
        dirs.add("$externalStorage/WhatsApp/Media/WhatsApp Video/.Sent")

        return dirs.filter { File(it).exists() }.distinct()
    }

    /**
     * Find thumbnail directories
     */
    private fun findThumbnailDirectories(): List<String> {
        val dirs = mutableListOf<String>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        for (pattern in THUMBNAIL_DIRS) {
            dirs.add("$externalStorage/DCIM/$pattern")
            dirs.add("$externalStorage/Pictures/$pattern")
        }

        // Android thumbnail directory
        try {
            val thumbDataDir = File("$externalStorage/DCIM/.thumbnails")
            if (thumbDataDir.exists()) {
                dirs.add(thumbDataDir.absolutePath)
            }
        } catch (_: Exception) {}

        return dirs.filter { File(it).exists() }.distinct()
    }

    /**
     * Find directories containing .nomedia file
     * These are hidden from gallery and may contain recoverable files
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
     * Find cache/temp directories
     */
    private fun findCacheDirectories(): List<String> {
        val dirs = mutableListOf<String>()
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath

        // App cache directories
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

        // System cache directories
        try {
            dirs.add(context.cacheDir.absolutePath)
            context.externalCacheDir?.let { dirs.add(it.absolutePath) }
        } catch (_: Exception) {}

        return dirs.filter { File(it).exists() }.distinct()
    }
}
