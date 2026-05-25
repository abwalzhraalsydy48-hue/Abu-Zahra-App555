package com.ultimaterecovery.pro.utils.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.system.Os
import timber.log.Timber
import java.io.File
import java.text.DecimalFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage utility class for Ultimate Recovery Pro.
 *
 * Provides comprehensive storage information and file management utilities:
 * - Internal and SD card storage info (total, used, free)
 * - Available space queries for any path
 * - Human-readable file size formatting
 * - Storage path discovery and enumeration
 * - SD card availability detection
 * - App-specific directory resolution
 * - Recovery output and temporary directory creation
 *
 * ## Usage
 * ```kotlin
 * val storageUtils = StorageUtils(context)
 *
 * // Get internal storage info
 * val info = storageUtils.getInternalStorageInfo()
 * Log.d(TAG, "Free: ${info.formattedFree} / ${info.formattedTotal}")
 *
 * // Check SD card
 * if (storageUtils.isSdCardAvailable()) {
 *     val sdInfo = storageUtils.getSdCardInfo()
 * }
 *
 * // Create recovery output directory
 * val outputDir = storageUtils.getRecoveryOutputDir()
 * ```
 *
 * @see StorageInfo
 * @see StoragePath
 */
@Singleton
class StorageUtils @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "StorageUtils"

        // ──────────────────────────────────────────
        // Directory Names
        // ──────────────────────────────────────────

        /** App-specific directory name for recovery output. */
        private const val RECOVERY_DIR_NAME = "Recovery"

        /** App-specific directory name for temporary files. */
        private const val TEMP_DIR_NAME = "temp"

        /** App-specific directory name for thumbnails. */
        private const val THUMBNAILS_DIR_NAME = "thumbnails"

        /** App-specific directory name for scan results. */
        private const val SCAN_RESULTS_DIR_NAME = "scan_results"

        // ──────────────────────────────────────────
        // Size Formatting Constants
        // ──────────────────────────────────────────

        private const val KB = 1024L
        private const val MB = KB * 1024
        private const val GB = MB * 1024
        private const val TB = GB * 1024

        /** Size units for display. */
        private val SIZE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

        /** Formatter for size values (2 decimal places). */
        private val SIZE_FORMAT = DecimalFormat("#,##0.#")
    }

    // ═══════════════════════════════════════════════════════════════
    // Internal Storage Info
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns detailed information about the internal storage volume.
     *
     * The internal storage is the device's primary storage partition
     * (typically mounted at `/storage/emulated/0`).
     *
     * @return [StorageInfo] for internal storage.
     */
    fun getInternalStorageInfo(): StorageInfo {
        val internalDir = Environment.getExternalStorageDirectory()
        return getStorageInfoForPath(internalDir, isRemovable = false, label = "Internal Storage")
    }

    // ═══════════════════════════════════════════════════════════════
    // SD Card Info
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns detailed information about the SD card, if available.
     *
     * Attempts to detect the SD card mount point using multiple
     * strategies:
     * 1. System environment `SECONDARY_STORAGE` variable
     * 2. `vold` configuration file parsing
     * 3. Common mount point probing
     *
     * @return [StorageInfo] for SD card, or null if not available.
     */
    fun getSdCardInfo(): StorageInfo? {
        val sdCardPath = getExternalSdCardPath() ?: return null
        val sdDir = File(sdCardPath)

        if (!sdDir.exists() || !sdDir.canRead()) return null

        return getStorageInfoForPath(sdDir, isRemovable = true, label = "SD Card")
    }

    // ═══════════════════════════════════════════════════════════════
    // Available Space
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns the available free space at the given path in bytes.
     *
     * Uses [StatFs] to query the filesystem for available blocks.
     *
     * @param path The filesystem path to check.
     * @return Available bytes, or 0 if the path is invalid.
     */
    fun getAvailableSpace(path: String): Long {
        return try {
            val stat = StatFs(path)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                stat.availableBlocksLong * stat.blockSizeLong
            } else {
                @Suppress("DEPRECATION")
                stat.availableBlocks.toLong() * stat.blockSize.toLong()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get available space for: $path")
            0L
        }
    }

    /**
     * Returns the available free space at the given [File] path.
     *
     * @param file The file/directory to check.
     * @return Available bytes.
     */
    fun getAvailableSpace(file: File): Long {
        return if (file.exists()) {
            file.freeSpace
        } else {
            file.parentFile?.let { getAvailableSpace(it.absolutePath) } ?: 0L
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // File Size Formatting
    // ═══════════════════════════════════════════════════════════════

    /**
     * Formats a byte count into a human-readable string.
     *
     * Examples:
     * - `512` → `"512 B"`
     * - `1536` → `"1.5 KB"`
     * - `1048576` → `"1 MB"`
     * - `1073741824` → `"1 GB"`
     *
     * @param bytes The byte count to format.
     * @return Formatted string with appropriate unit.
     */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "0 B"
        if (bytes < KB) return "$bytes B"

        val value = bytes.toDouble()
        val unitIndex = (Math.log10(value) / Math.log10(KB.toDouble())).toInt()
            .coerceIn(0, SIZE_UNITS.lastIndex)

        val formattedValue = SIZE_FORMAT.format(value / Math.pow(KB.toDouble(), unitIndex.toDouble()))
        return "$formattedValue ${SIZE_UNITS[unitIndex]}"
    }

    /**
     * Formats a byte count into a short string (e.g., "1.5G").
     *
     * Useful for UI constraints where space is limited.
     *
     * @param bytes The byte count to format.
     * @return Short formatted string.
     */
    fun formatSizeShort(bytes: Long): String {
        if (bytes < 0) return "0B"
        if (bytes < KB) return "${bytes}B"
        if (bytes < MB) return "${bytes / KB}K"
        if (bytes < GB) return "${"%.1f".format(bytes / MB.toDouble())}M"
        return "${"%.1f".format(bytes / GB.toDouble())}G"
    }

    // ═══════════════════════════════════════════════════════════════
    // Storage Paths
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns all available storage paths on the device.
     *
     * Includes internal storage and any mounted SD cards.
     *
     * @return List of [StoragePath] objects.
     */
    fun getStoragePaths(): List<StoragePath> {
        val paths = mutableListOf<StoragePath>()

        // Internal storage
        val internalDir = Environment.getExternalStorageDirectory()
        paths.add(StoragePath(
            path = internalDir.absolutePath,
            label = "Internal Storage",
            isRemovable = false,
            isAvailable = internalDir.exists() && internalDir.canRead()
        ))

        // SD card
        val sdCardPath = getExternalSdCardPath()
        if (sdCardPath != null) {
            val sdDir = File(sdCardPath)
            paths.add(StoragePath(
                path = sdCardPath,
                label = "SD Card",
                isRemovable = true,
                isAvailable = sdDir.exists() && sdDir.canRead()
            ))
        }

        return paths
    }

    // ═══════════════════════════════════════════════════════════════
    // SD Card Availability
    // ═══════════════════════════════════════════════════════════════

    /**
     * Checks if an SD card is available and readable.
     *
     * @return `true` if an SD card is mounted and accessible.
     */
    fun isSdCardAvailable(): Boolean {
        val sdCardPath = getExternalSdCardPath() ?: return false
        val sdDir = File(sdCardPath)
        return sdDir.exists() && sdDir.canRead()
    }

    // ═══════════════════════════════════════════════════════════════
    // App-Specific Storage Paths
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns the app-specific external files directory.
     *
     * This directory is automatically cleaned up when the app is
     * uninstalled. It does not require storage permissions on
     * Android 11+.
     *
     * @return The app's external files directory.
     */
    fun getAppExternalFilesDir(): File {
        return context.getExternalFilesDir(null)
            ?: File(context.filesDir, "external")
    }

    /**
     * Returns the app-specific internal files directory.
     *
     * This is private to the app and always available.
     *
     * @return The app's internal files directory.
     */
    fun getAppInternalFilesDir(): File {
        return context.filesDir
    }

    /**
     * Returns the app-specific external cache directory.
     *
     * @return The app's external cache directory.
     */
    fun getAppExternalCacheDir(): File {
        return context.externalCacheDir
            ?: File(context.cacheDir, "external_cache")
    }

    // ═══════════════════════════════════════════════════════════════
    // Recovery Output Directory
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns (and creates if necessary) the directory for recovered
     * file output.
     *
     * Location: `<external_storage>/UltimateRecoveryPro/Recovery/`
     *
     * The directory is organized with subdirectories by file category:
     * - `Photos/`
     * - `Videos/`
     * - `Documents/`
     * - `Audio/`
     * - `Archives/`
     * - `APKs/`
     * - `Other/`
     *
     * @return The recovery output root directory.
     */
    fun getRecoveryOutputDir(): File {
        val rootDir = File(
            Environment.getExternalStorageDirectory(),
            "UltimateRecoveryPro/$RECOVERY_DIR_NAME"
        )

        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }

        // Ensure category subdirectories exist
        val categories = listOf("Photos", "Videos", "Documents", "Audio", "Archives", "APKs", "Other")
        for (category in categories) {
            val categoryDir = File(rootDir, category)
            if (!categoryDir.exists()) {
                categoryDir.mkdirs()
            }
        }

        return rootDir
    }

    /**
     * Returns the recovery output subdirectory for a specific file category.
     *
     * @param category The file category.
     * @return The category-specific recovery output directory.
     */
    fun getRecoveryOutputDirForCategory(category: com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory): File {
        val rootDir = getRecoveryOutputDir()
        val categoryName = when (category) {
            com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory.PHOTO -> "Photos"
            com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory.VIDEO -> "Videos"
            com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory.AUDIO -> "Audio"
            com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory.DOCUMENT -> "Documents"
            com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory.ARCHIVE -> "Archives"
            com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory.APK -> "APKs"
            com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory.OTHER -> "Other"
        }
        val dir = File(rootDir, categoryName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ═══════════════════════════════════════════════════════════════
    // Temporary Directory
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns (and creates if necessary) the temporary file directory.
     *
     * Location: `<app_external_cache>/temp/`
     *
     * This directory should be cleaned up periodically. Files in
     * the cache directory may be deleted by the system when storage
     * is low.
     *
     * @return The temporary files directory.
     */
    fun getTempDir(): File {
        val tempDir = File(context.externalCacheDir ?: context.cacheDir, TEMP_DIR_NAME)
        if (!tempDir.exists()) tempDir.mkdirs()
        return tempDir
    }

    // ═══════════════════════════════════════════════════════════════
    // Cache Directory
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns (and creates if necessary) the app cache directory.
     *
     * Uses the external cache directory if available, falling back
     * to internal cache.
     *
     * @return The cache directory.
     */
    fun getCacheDir(): File {
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return cacheDir
    }

    /**
     * Returns the thumbnails cache directory.
     *
     * @return The thumbnails directory.
     */
    fun getThumbnailsDir(): File {
        val thumbsDir = File(getCacheDir(), THUMBNAILS_DIR_NAME)
        if (!thumbsDir.exists()) thumbsDir.mkdirs()
        return thumbsDir
    }

    /**
     * Returns the scan results directory.
     *
     * @return The scan results directory.
     */
    fun getScanResultsDir(): File {
        val scanDir = File(getAppExternalFilesDir(), SCAN_RESULTS_DIR_NAME)
        if (!scanDir.exists()) scanDir.mkdirs()
        return scanDir
    }

    // ═══════════════════════════════════════════════════════════════
    // Cache Cleanup
    // ═══════════════════════════════════════════════════════════════

    /**
     * Clears all files in the temporary and cache directories.
     *
     * @return The number of bytes freed.
     */
    fun clearCache(): Long {
        var freed = 0L
        freed += deleteDirectoryContents(getTempDir())
        freed += deleteDirectoryContents(getThumbnailsDir())
        return freed
    }

    // ═══════════════════════════════════════════════════════════════
    // Private Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Builds [StorageInfo] from a given root path.
     */
    private fun getStorageInfoForPath(
        path: File,
        isRemovable: Boolean,
        label: String
    ): StorageInfo {
        val stat = StatFs(path.absolutePath)

        val totalBlocks: Long
        val availableBlocks: Long
        val blockSize: Long

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            totalBlocks = stat.blockCountLong
            availableBlocks = stat.availableBlocksLong
            blockSize = stat.blockSizeLong
        } else {
            @Suppress("DEPRECATION")
            totalBlocks = stat.blockCount.toLong()
            @Suppress("DEPRECATION")
            availableBlocks = stat.availableBlocks.toLong()
            @Suppress("DEPRECATION")
            blockSize = stat.blockSize.toLong()
        }

        val totalSpace = totalBlocks * blockSize
        val freeSpace = availableBlocks * blockSize
        val usedSpace = totalSpace - freeSpace

        return StorageInfo(
            path = path.absolutePath,
            totalSpace = totalSpace,
            usedSpace = usedSpace,
            freeSpace = freeSpace,
            isRemovable = isRemovable,
            label = label
        )
    }

    /**
     * Attempts to detect the external SD card mount point.
     *
     * Uses multiple strategies:
     * 1. `SECONDARY_STORAGE` environment variable
     * 2. Common SD card mount points
     * 3. `vold` configuration parsing
     */
    private fun getExternalSdCardPath(): String? {
        // Strategy 1: Environment variable
        val secondaryStorage = System.getenv("SECONDARY_STORAGE")
        if (!secondaryStorage.isNullOrBlank()) {
            val paths = secondaryStorage.split(":")
            for (path in paths) {
                val trimmed = path.trim()
                if (trimmed.isNotBlank() && File(trimmed).exists()) {
                    return trimmed
                }
            }
        }

        // Strategy 2: Common mount points
        val commonPaths = listOf(
            "/storage/sdcard1",
            "/storage/extSdCard",
            "/storage/external_sd",
            "/sdcard2",
            "/sdcard1",
            "/mnt/sdcard1",
            "/mnt/extSdCard",
            "/mnt/external_sd",
            "/mnt/media_rw/extSdCard",
            "/mnt/media_rw/sdcard1",
            "/removable/microsd",
            "/removable/sdcard1"
        )

        for (path in commonPaths) {
            val file = File(path)
            if (file.exists() && file.isDirectory && file.canRead()) {
                // Verify it's actually a different mount from internal storage
                val internalPath = Environment.getExternalStorageDirectory().absolutePath
                if (path != internalPath) {
                    return path
                }
            }
        }

        // Strategy 3: Check /storage/ for non-emulated, non-internal mounts
        val storageDir = File("/storage")
        if (storageDir.exists() && storageDir.isDirectory) {
            val internalPath = Environment.getExternalStorageDirectory().absolutePath
            val selfPrimary = File("/storage/emulated")

            storageDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.canRead() &&
                    dir.absolutePath != internalPath &&
                    dir.absolutePath != "/storage/emulated" &&
                    dir.absolutePath != "/storage/self"
                ) {
                    // Verify it's a real mount point (not a symlink to internal)
                    try {
                        val canonical = dir.canonicalPath
                        if (canonical != Environment.getExternalStorageDirectory().canonicalPath) {
                            return dir.absolutePath
                        }
                    } catch (_: Exception) {
                        // Can't resolve — skip
                    }
                }
            }
        }

        return null
    }

    /**
     * Recursively deletes all files in a directory (but not the directory itself).
     *
     * @return Total bytes freed.
     */
    private fun deleteDirectoryContents(dir: File): Long {
        var freed = 0L
        dir.listFiles()?.forEach { file ->
            freed += if (file.isDirectory) {
                val size = calculateDirectorySize(file)
                deleteRecursive(file)
                size
            } else {
                val size = file.length()
                file.delete()
                size
            }
        }
        return freed
    }

    /**
     * Recursively deletes a file or directory.
     */
    private fun deleteRecursive(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        file.delete()
    }

    /**
     * Recursively calculates the total size of a directory.
     */
    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L

        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                calculateDirectorySize(file)
            } else {
                file.length()
            }
        }
        return size
    }
}

// ═══════════════════════════════════════════════════════════════
// Data Classes
// ═══════════════════════════════════════════════════════════════

/**
 * Storage volume information.
 *
 * @property path        Mount point path.
 * @property totalSpace  Total capacity in bytes.
 * @property usedSpace   Used space in bytes.
 * @property freeSpace   Free (available) space in bytes.
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
    val formattedTotal: String
        get() = formatStorageSize(totalSpace)

    /** Formatted used space. */
    val formattedUsed: String
        get() = formatStorageSize(usedSpace)

    /** Formatted free space. */
    val formattedFree: String
        get() = formatStorageSize(freeSpace)

    /** Whether storage is critically low (< 5% free). */
    val isLow: Boolean
        get() = totalSpace > 0 && freeSpace.toFloat() / totalSpace < 0.05f

    private fun formatStorageSize(bytes: Long): String {
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
 * Represents a storage path on the device.
 *
 * @property path        Absolute path.
 * @property label       Human-readable label.
 * @property isRemovable Whether this is removable storage.
 * @property isAvailable Whether the storage is currently accessible.
 */
data class StoragePath(
    val path: String,
    val label: String,
    val isRemovable: Boolean,
    val isAvailable: Boolean
)

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
