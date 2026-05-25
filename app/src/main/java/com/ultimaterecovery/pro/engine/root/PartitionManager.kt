package com.ultimaterecovery.pro.engine.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Partition Manager - Discovers and manages storage partitions
 *
 * Provides comprehensive partition discovery and management using root access.
 * Capabilities include:
 *
 * - Discover all partitions from /proc/partitions, /proc/mounts, and /dev/block
 * - Get detailed partition info (size, type, mount point, filesystem)
 * - Read raw data from partition block devices
 * - Identify filesystem types (ext4, f2fs, vfat, exfat, ntfs)
 * - Safely unmount and mount partitions
 * - Access block device symlinks in /dev/block/by-name
 *
 * All operations require root access and fall back gracefully when unavailable.
 */
@Singleton
class PartitionManager @Inject constructor(
    private val rootManager: RootManager
) {

    companion object {
        /** System paths for partition information */
        private const val PROC_PARTITIONS = "/proc/partitions"
        private const val PROC_MOUNTS = "/proc/mounts"
        private const val DEV_BLOCK = "/dev/block"
        private const val DEV_BLOCK_BY_NAME = "/dev/block/by-name"
        private const val SYS_BLOCK = "/sys/block"

        /** Alternative by-name paths for different device architectures */
        private val BY_NAME_PATHS = listOf(
            "/dev/block/by-name",
            "/dev/block/bootdevice/by-name",
            "/dev/block/platform/soc/by-name",
            "/dev/block/platform/soc.0/by-name",
            "/dev/block/platform/omap/omap_hsmmc.0/by-name",
            "/dev/block/platform/msm_sdcc.1/by-name",
            "/dev/block/platform/155a0000.ufs/by-name",
            "/dev/block/platform/11120000.ufs/by-name",
            "/dev/block/platform/f723d000.dwmmc0/by-name"
        )

        /** dd command default block size */
        private const val DD_BLOCK_SIZE = 512

        /** Maximum read size for raw block reads (64MB) */
        private const val MAX_RAW_READ_SIZE = 64L * 1024 * 1024

        /** Buffer size for raw data reads (1MB) */
        private const val READ_BUFFER_SIZE = 1024 * 1024

        /** Known filesystem superblock signatures */
        private val FS_SIGNATURES = mapOf(
            // ext4: bytes 0x438-0x43C contain magic number 0xEF53
            "ext4" to Pair(0x438, byteArrayOf(0x53.toByte(), 0xEF.toByte())),
            // f2fs: offset 0 with "KO" magic
            "f2fs" to Pair(0, byteArrayOf(0x10.toByte(), 0x20.toByte(), 0xF5.toByte(), 0xF2.toByte())),
            // vfat: OEM name "MSDOS" or "FAT32" at offset 0x36/0x52
            "vfat" to Pair(0x36, byteArrayOf(0x4D.toByte(), 0x53.toByte(), 0x44.toByte(), 0x4F.toByte(), 0x53.toByte())),
            // exfat: "EXFAT" at offset 3
            "exfat" to Pair(3, byteArrayOf(0x45.toByte(), 0x58.toByte(), 0x46.toByte(), 0x41.toByte(), 0x54.toByte())),
            // ntfs: "NTFS    " at offset 3
            "ntfs" to Pair(3, byteArrayOf(0x4E.toByte(), 0x54.toByte(), 0x46.toByte(), 0x53.toByte()))
        )
    }

    // ──────────────────────────────────────────────
    // Partition Discovery
    // ──────────────────────────────────────────────

    /**
     * Discover all available storage partitions.
     *
     * Aggregates partition information from multiple sources:
     * 1. /proc/partitions - kernel partition table
     * 2. /proc/mounts - currently mounted filesystems
     * 3. /dev/block/by-name - named partition symlinks
     * 4. /sys/block - block device sysfs entries
     *
     * @return List of PartitionInfo with detailed partition metadata
     */
    suspend fun discoverPartitions(): List<PartitionInfo> = withContext(Dispatchers.IO) {
        if (!rootManager.isRootGranted) return@withContext emptyList()

        val partitionMap = mutableMapOf<String, PartitionInfo>()

        // Source 1: /proc/partitions
        parseProcPartitions().forEach { info ->
            partitionMap[info.devicePath] = info
        }

        // Source 2: /proc/mounts (enrich with mount info)
        parseProcMounts().forEach { mountInfo ->
            val existing = partitionMap[mountInfo.devicePath]
            if (existing != null) {
                partitionMap[mountInfo.devicePath] = existing.copy(
                    mountPoint = mountInfo.mountPoint,
                    filesystemType = mountInfo.filesystemType.ifBlank { existing.filesystemType },
                    mountOptions = mountInfo.mountOptions,
                    isMounted = true
                )
            } else {
                partitionMap[mountInfo.devicePath] = mountInfo
            }
        }

        // Source 3: /dev/block/by-name symlinks (add friendly names)
        parseBlockByName().forEach { (name, devicePath) ->
            val existing = partitionMap[devicePath]
            if (existing != null) {
                partitionMap[devicePath] = existing.copy(label = name)
            } else {
                // Partition found by name but not in /proc/partitions
                val size = getPartitionSize(devicePath)
                val fsType = detectFilesystemType(devicePath)
                partitionMap[devicePath] = PartitionInfo(
                    devicePath = devicePath,
                    label = name,
                    sizeBytes = size,
                    filesystemType = fsType
                )
            }
        }

        // Source 4: /sys/block for additional metadata
        enrichFromSysBlock(partitionMap)

        // Filter out unwanted entries and return
        partitionMap.values
            .filter { it.sizeBytes > 0 || it.isMounted }
            .filter { !it.name.startsWith("loop") && !it.name.contains("zram") }
            .sortedByDescending { it.sizeBytes }
    }

    /**
     * Get detailed information about a specific partition.
     *
     * @param devicePath Path to the block device (e.g., /dev/block/sda1)
     * @return PartitionInfo or null if the partition cannot be accessed
     */
    suspend fun getPartitionInfo(devicePath: String): PartitionInfo? = withContext(Dispatchers.IO) {
        if (!rootManager.isRootGranted) return@withContext null

        val size = getPartitionSize(devicePath)
        val fsType = detectFilesystemType(devicePath)
        val mountPoint = getMountPoint(devicePath)
        val label = getPartitionLabel(devicePath)
        val isMounted = mountPoint.isNotEmpty()
        val isReadOnly = isPartitionReadOnly(devicePath)

        PartitionInfo(
            devicePath = devicePath,
            label = label,
            sizeBytes = size,
            filesystemType = fsType,
            mountPoint = mountPoint,
            isMounted = isMounted,
            isReadOnly = isReadOnly,
            sectorSize = getSectorSize(devicePath)
        )
    }

    // ──────────────────────────────────────────────
    // Raw Block Data Access
    // ──────────────────────────────────────────────

    /**
     * Read raw bytes from a partition block device.
     *
     * Uses dd commands via root shell to read arbitrary data
     * from any block device, regardless of filesystem.
     *
     * @param devicePath Path to the block device
     * @param offset Byte offset to start reading from
     * @param size Number of bytes to read (max MAX_RAW_READ_SIZE)
     * @return Raw bytes read from the device, or null on failure
     */
    suspend fun readRawBlock(
        devicePath: String,
        offset: Long = 0L,
        size: Long = MAX_RAW_READ_SIZE
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!rootManager.isRootGranted) return@withContext null

        val readSize = minOf(size, MAX_RAW_READ_SIZE)
        val outputPath = "/data/local/tmp/urp_raw_read_${System.currentTimeMillis()}"

        try {
            // Use dd to read raw data to a temp file
            val skipBlocks = offset / DD_BLOCK_SIZE
            val countBlocks = (readSize + DD_BLOCK_SIZE - 1) / DD_BLOCK_SIZE

            val ddCmd = buildString {
                append("dd if='$devicePath'")
                append(" bs=$DD_BLOCK_SIZE")
                append(" skip=$skipBlocks")
                append(" count=$countBlocks")
                append(" of='$outputPath'")
                append(" 2>/dev/null")
            }

            val ddResult = rootManager.executeCommand(ddCmd)
            if (!ddResult) return@withContext null

            // Read the temp file content
            val contentResult = rootManager.executeCommandWithOutput(
                "wc -c '$outputPath' 2>/dev/null"
            )
            if (!contentResult.success) return@withContext null

            val fileSize = contentResult.stdout.trim().split("\\s".toRegex()).firstOrNull()?.toLongOrNull() ?: 0L
            if (fileSize == 0L) return@withContext null

            // Read raw bytes using xxd and convert back
            val xxdResult = rootManager.executeCommandWithOutput(
                "xxd -p -l $readSize '$outputPath' 2>/dev/null"
            )

            if (xxdResult.success && xxdResult.stdout.isNotEmpty()) {
                val hexString = xxdResult.stdout.replace("\\s".toRegex(), "")
                hexToByteArray(hexString)
            } else null

        } catch (_: Exception) {
            null
        } finally {
            // Clean up temp file
            rootManager.executeCommand("rm -f '$outputPath' 2>/dev/null")
        }
    }

    /**
     * Read raw block data and write directly to an OutputStream.
     *
     * More efficient than readRawBlock for large reads since it streams
     * data directly to the output without holding everything in memory.
     *
     * @param devicePath Path to the block device
     * @param outputStream Target output stream
     * @param offset Byte offset to start reading
     * @param size Number of bytes to read
     * @return Number of bytes actually read
     */
    suspend fun readRawBlockToStream(
        devicePath: String,
        outputStream: OutputStream,
        offset: Long = 0L,
        size: Long = MAX_RAW_READ_SIZE
    ): Long = withContext(Dispatchers.IO) {
        if (!rootManager.isRootGranted) return@withContext 0L

        val outputPath = "/data/local/tmp/urp_stream_${System.currentTimeMillis()}"
        var bytesRead = 0L

        try {
            val skipBlocks = offset / DD_BLOCK_SIZE
            val countBlocks = (size + DD_BLOCK_SIZE - 1) / DD_BLOCK_SIZE

            val ddCmd = "dd if='$devicePath' bs=$DD_BLOCK_SIZE skip=$skipBlocks count=$countBlocks of='$outputPath' 2>/dev/null"
            if (!rootManager.executeCommand(ddCmd)) return@withContext 0L

            // Read the temp file in chunks and write to the output stream
            val tempFile = File(outputPath)
            if (!tempFile.exists()) return@withContext 0L

            tempFile.inputStream().use { input ->
                val buffer = ByteArray(READ_BUFFER_SIZE)
                var remaining = size

                while (remaining > 0 && currentCoroutineContext().isActive) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break

                    outputStream.write(buffer, 0, read)
                    bytesRead += read
                    remaining -= read
                }
            }

            outputStream.flush()
            bytesRead

        } catch (_: Exception) {
            bytesRead
        } finally {
            rootManager.executeCommand("rm -f '$outputPath' 2>/dev/null")
        }
    }

    // ──────────────────────────────────────────────
    // Filesystem Detection
    // ──────────────────────────────────────────────

    /**
     * Detect the filesystem type of a partition.
     *
     * Uses multiple methods for detection:
     * 1. Check /proc/mounts for mounted filesystems
     * 2. Read superblock magic numbers
     * 3. Use blkid command if available
     * 4. Fallback to heuristics based on partition name
     *
     * @param devicePath Path to the block device
     * @return Filesystem type string (e.g., "ext4", "f2fs", "vfat")
     */
    suspend fun getFileSystemType(devicePath: String): String = withContext(Dispatchers.IO) {
        detectFilesystemType(devicePath)
    }

    /**
     * Get all mount points from /proc/mounts.
     *
     * @return Map of device path to mount information
     */
    suspend fun getMountPoints(): Map<String, MountInfo> = withContext(Dispatchers.IO) {
        val mounts = mutableMapOf<String, MountInfo>()

        try {
            val content = rootManager.readFileAsRoot(PROC_MOUNTS) ?: return@withContext emptyMap()

            content.lines().forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val devicePath = parts[0]
                    val mountPoint = parts[1]
                    val fsType = parts[2]
                    val options = parts[3]

                    // Skip virtual filesystems
                    if (devicePath.startsWith("/dev/") || devicePath.startsWith("tmpfs") ||
                        devicePath.startsWith("/sys/") || devicePath.startsWith("/proc/")) {
                        mounts[devicePath] = MountInfo(
                            devicePath = devicePath,
                            mountPoint = mountPoint,
                            filesystemType = fsType,
                            mountOptions = options
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Failed to read mount points
        }

        mounts
    }

    // ──────────────────────────────────────────────
    // Mount/Unmount Operations
    // ──────────────────────────────────────────────

    /**
     * Safely unmount a partition.
     *
     * @param mountPoint The mount point to unmount
     * @param force Whether to force unmount (lazy unmount)
     * @return true if unmount was successful
     */
    suspend fun unmountPartition(mountPoint: String, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (!rootManager.isRootGranted) return@withContext false

            val cmd = if (force) {
                "umount -l '$mountPoint' 2>/dev/null"
            } else {
                "umount '$mountPoint' 2>/dev/null"
            }

            rootManager.executeCommand(cmd)
        }

    /**
     * Safely mount a partition.
     *
     * @param devicePath Path to the block device
     * @param mountPoint Target mount point
     * @param fsType Filesystem type (empty = auto-detect)
     * @param readOnly Whether to mount read-only
     * @return true if mount was successful
     */
    suspend fun mountPartition(
        devicePath: String,
        mountPoint: String,
        fsType: String = "",
        readOnly: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (!rootManager.isRootGranted) return@withContext false

        // Create mount point if it doesn't exist
        rootManager.executeCommand("mkdir -p '$mountPoint' 2>/dev/null")

        val cmd = buildString {
            append("mount")
            if (fsType.isNotEmpty()) append(" -t $fsType")
            if (readOnly) append(" -o ro")
            append(" '$devicePath' '$mountPoint' 2>/dev/null")
        }

        rootManager.executeCommand(cmd)
    }

    // ──────────────────────────────────────────────
    // Private Helper Methods
    // ──────────────────────────────────────────────

    /**
     * Parse /proc/partitions for partition entries
     */
    private suspend fun parseProcPartitions(): List<PartitionInfo> {
        val entries = mutableListOf<PartitionInfo>()

        try {
            val content = rootManager.readFileAsRoot(PROC_PARTITIONS) ?: return emptyList()

            content.lines().drop(2).forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val name = parts[3]
                    if (name.startsWith("loop") || name.contains("zram") || name.startsWith("ram")) return@forEach

                    val devicePath = "/dev/block/$name"
                    val sizeBlocks = parts[2].toLongOrNull() ?: 0L

                    entries.add(PartitionInfo(
                        devicePath = devicePath,
                        name = name,
                        sizeBytes = sizeBlocks * 1024L,
                        major = parts[0].toIntOrNull() ?: 0,
                        minor = parts[1].toIntOrNull() ?: 0
                    ))
                }
            }
        } catch (_: Exception) {}

        return entries
    }

    /**
     * Parse /proc/mounts for mount information
     */
    private suspend fun parseProcMounts(): List<PartitionInfo> {
        val entries = mutableListOf<PartitionInfo>()

        try {
            val content = rootManager.readFileAsRoot(PROC_MOUNTS) ?: return emptyList()

            content.lines().forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val devicePath = parts[0]
                    if (!devicePath.startsWith("/dev/")) return@forEach

                    entries.add(PartitionInfo(
                        devicePath = devicePath,
                        mountPoint = parts[1],
                        filesystemType = parts[2],
                        mountOptions = parts[3],
                        isMounted = true
                    ))
                }
            }
        } catch (_: Exception) {}

        return entries
    }

    /**
     * Parse /dev/block/by-name symlinks for friendly partition names
     */
    private suspend fun parseBlockByName(): Map<String, String> {
        val nameMap = mutableMapOf<String, String>()

        for (byNamePath in BY_NAME_PATHS) {
            val dir = File(byNamePath)
            if (!dir.exists() || !dir.isDirectory) continue

            try {
                val files = rootManager.listFilesAsRoot(byNamePath)
                for (name in files) {
                    val linkPath = "$byNamePath/$name"
                    val result = rootManager.executeCommandWithOutput("readlink -f '$linkPath' 2>/dev/null")
                    if (result.success && result.stdout.isNotEmpty()) {
                        val target = result.stdout.trim()
                        if (target.startsWith("/dev/block/")) {
                            nameMap[name] = target
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return nameMap
    }

    /**
     * Enrich partition info with /sys/block data
     */
    private suspend fun enrichFromSysBlock(partitionMap: MutableMap<String, PartitionInfo>) {
        try {
            val sysBlockDir = File(SYS_BLOCK)
            if (!sysBlockDir.exists() || !sysBlockDir.isDirectory) return

            val blockDevices = rootManager.listFilesAsRoot(SYS_BLOCK)
            for (deviceName in blockDevices) {
                if (deviceName.startsWith("loop") || deviceName.contains("zram")) continue

                // Read device size
                val sizeStr = rootManager.readFileAsRoot("$SYS_BLOCK/$deviceName/size")
                val sizeBytes = sizeStr?.trim()?.toLongOrNull()?.let { it * 512 } ?: 0L

                // Check for partitions under this device
                val partitions = rootManager.listFilesAsRoot("$SYS_BLOCK/$deviceName")
                for (partName in partitions) {
                    if (!partName.startsWith(deviceName)) continue

                    val partDevicePath = "/dev/block/$partName"
                    val partSizeStr = rootManager.readFileAsRoot("$SYS_BLOCK/$deviceName/$partName/size")
                    val partSizeBytes = partSizeStr?.trim()?.toLongOrNull()?.let { it * 512 } ?: 0L

                    val existing = partitionMap[partDevicePath]
                    if (existing != null && existing.sizeBytes == 0L && partSizeBytes > 0L) {
                        partitionMap[partDevicePath] = existing.copy(sizeBytes = partSizeBytes)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Get partition size in bytes using blockdev
     */
    private suspend fun getPartitionSize(devicePath: String): Long {
        val result = rootManager.executeCommandWithOutput("blockdev --getsize64 '$devicePath' 2>/dev/null")
        if (result.success && result.stdout.isNotEmpty()) {
            return result.stdout.trim().toLongOrNull() ?: 0L
        }

        // Fallback: try /sys/block
        val deviceName = devicePath.substringAfterLast("/")
        val parentDevice = deviceName.replaceFirst(Regex("\\d+$"), "")

        val sizeStr = rootManager.readFileAsRoot("$SYS_BLOCK/$parentDevice/$deviceName/size")
            ?: rootManager.readFileAsRoot("$SYS_BLOCK/$deviceName/size")

        return sizeStr?.trim()?.toLongOrNull()?.let { it * 512 } ?: 0L
    }

    /**
     * Detect filesystem type using multiple methods
     */
    private suspend fun detectFilesystemType(devicePath: String): String {
        // Method 1: Check blkid
        val blkidResult = rootManager.executeCommandWithOutput("blkid -o value -s TYPE '$devicePath' 2>/dev/null")
        if (blkidResult.success && blkidResult.stdout.isNotEmpty()) {
            return blkidResult.stdout.trim()
        }

        // Method 2: Check /proc/mounts
        val mounts = getMountPoints()
        mounts[devicePath]?.let { return it.filesystemType }

        // Method 3: Read superblock magic numbers
        val superblockType = detectFilesystemFromSuperblock(devicePath)
        if (superblockType.isNotEmpty()) return superblockType

        // Method 4: Heuristic from partition name
        val deviceName = devicePath.substringAfterLast("/")
        return guessFilesystemFromName(deviceName)
    }

    /**
     * Detect filesystem by reading superblock magic numbers
     */
    private suspend fun detectFilesystemFromSuperblock(devicePath: String): String {
        for ((fsName, signature) in FS_SIGNATURES) {
            val (offset, magic) = signature
            val skipBlocks = offset / DD_BLOCK_SIZE

            val result = rootManager.executeCommandWithOutput(
                "dd if='$devicePath' bs=1 skip=$offset count=${magic.size} 2>/dev/null | xxd -p"
            )

            if (result.success && result.stdout.isNotEmpty()) {
                val hex = result.stdout.replace("\\s".toRegex(), "").uppercase()
                val expectedHex = magic.joinToString("") { "%02X".format(it) }
                if (hex.startsWith(expectedHex)) {
                    return fsName
                }
            }
        }

        return ""
    }

    /**
     * Guess filesystem type from partition name heuristics
     */
    private fun guessFilesystemFromName(name: String): String {
        return when {
            name.contains("userdata", ignoreCase = true) -> "f2fs"
            name.contains("cache", ignoreCase = true) -> "ext4"
            name.contains("system", ignoreCase = true) -> "ext4"
            name.contains("vendor", ignoreCase = true) -> "ext4"
            name.contains("boot", ignoreCase = true) -> ""
            name.contains("recovery", ignoreCase = true) -> ""
            name.contains("efs", ignoreCase = true) -> "ext4"
            name.contains("modem", ignoreCase = true) -> "vfat"
            name.contains("persist", ignoreCase = true) -> "ext4"
            name.contains("sdcard", ignoreCase = true) -> "vfat"
            else -> ""
        }
    }

    /**
     * Get mount point for a device
     */
    private suspend fun getMountPoint(devicePath: String): String {
        val result = rootManager.executeCommandWithOutput(
            "grep '$devicePath' /proc/mounts 2>/dev/null | head -1 | awk '{print \$2}'"
        )
        return if (result.success) result.stdout.trim() else ""
    }

    /**
     * Get partition label/name
     */
    private suspend fun getPartitionLabel(devicePath: String): String {
        // Try blkid first
        val result = rootManager.executeCommandWithOutput("blkid -o value -s LABEL '$devicePath' 2>/dev/null")
        if (result.success && result.stdout.isNotEmpty()) {
            return result.stdout.trim()
        }

        // Check by-name symlinks
        for (byNamePath in BY_NAME_PATHS) {
            val lsResult = rootManager.executeCommandWithOutput("ls -la '$byNamePath' 2>/dev/null")
            if (lsResult.success) {
                for (line in lsResult.stdout.lines()) {
                    if (line.contains(devicePath)) {
                        // Extract the symlink name
                        val name = line.substringAfterLast(" -> ").substringAfterLast("/")
                        val linkName = line.substringAfterLast("/").substringBefore(" ->")
                        if (linkName.isNotEmpty()) return linkName
                    }
                }
            }
        }

        return devicePath.substringAfterLast("/")
    }

    /**
     * Check if a partition is mounted read-only
     */
    private suspend fun isPartitionReadOnly(devicePath: String): Boolean {
        val mountInfo = rootManager.executeCommandWithOutput(
            "grep '$devicePath' /proc/mounts 2>/dev/null | head -1 | awk '{print \$4}'"
        )
        return mountInfo.success && mountInfo.stdout.contains("ro,")
    }

    /**
     * Get sector size for a block device
     */
    private suspend fun getSectorSize(devicePath: String): Int {
        val result = rootManager.executeCommandWithOutput(
            "cat /sys/block/${devicePath.substringAfterLast("/").replaceFirst(Regex("\\d+$"), "")}/queue/logical_block_size 2>/dev/null"
        )
        return if (result.success) result.stdout.trim().toIntOrNull() ?: DD_BLOCK_SIZE else DD_BLOCK_SIZE
    }

    /**
     * Convert hex string to byte array
     */
    private fun hexToByteArray(hex: String): ByteArray {
        val cleanHex = hex.replace("\\s".toRegex(), "")
        val len = cleanHex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) +
                    Character.digit(cleanHex[i + 1], 16)).toByte()
        }
        return data
    }
}

// ──────────────────────────────────────────────
// Data Classes
// ──────────────────────────────────────────────

/**
 * Comprehensive partition information
 *
 * @property devicePath Full path to block device (e.g., /dev/block/sda1)
 * @property name Device name (e.g., sda1)
 * @property label Friendly name from by-name symlink (e.g., "userdata")
 * @property sizeBytes Partition size in bytes
 * @property filesystemType Detected filesystem type (e.g., "ext4", "f2fs")
 * @property mountPoint Current mount point (empty if not mounted)
 * @property mountOptions Mount options string
 * @property isMounted Whether the partition is currently mounted
 * @property isReadOnly Whether the partition is mounted read-only
 * @property major Major device number
 * @property minor Minor device number
 * @property sectorSize Logical sector size in bytes
 * @property uuid Filesystem UUID if available
 */
data class PartitionInfo(
    val devicePath: String,
    val name: String = devicePath.substringAfterLast("/"),
    val label: String = "",
    val sizeBytes: Long = 0L,
    val filesystemType: String = "",
    val mountPoint: String = "",
    val mountOptions: String = "",
    val isMounted: Boolean = false,
    val isReadOnly: Boolean = false,
    val major: Int = 0,
    val minor: Int = 0,
    val sectorSize: Int = 512,
    val uuid: String = ""
) {
    /** Human-readable size string */
    val sizeFormatted: String
        get() = formatSize(sizeBytes)

    /** Whether this is the userdata partition */
    val isUserData: Boolean
        get() = label.equals("userdata", ignoreCase = true) ||
                name.contains("userdata", ignoreCase = true)

    /** Whether this is the system partition */
    val isSystem: Boolean
        get() = label.equals("system", ignoreCase = true) ||
                name.contains("system", ignoreCase = true)

    /** Whether this is a boot partition */
    val isBoot: Boolean
        get() = label.equals("boot", ignoreCase = true) ||
                name.contains("boot", ignoreCase = true)

    /** Whether this partition is likely to contain recoverable user data */
    val isRecoverable: Boolean
        get() = isUserData || label.equals("cache", ignoreCase = true) ||
                filesystemType in listOf("ext4", "f2fs", "vfat", "exfat", "ntfs")

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 40 -> "%.1f TB".format(bytes.toDouble() / (1L shl 40))
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}

/**
 * Mount information for a filesystem
 *
 * @property devicePath Device path
 * @property mountPoint Mount point path
 * @property filesystemType Filesystem type
 * @property mountOptions Mount options string
 */
data class MountInfo(
    val devicePath: String,
    val mountPoint: String,
    val filesystemType: String,
    val mountOptions: String
) {
    /** Whether mounted read-only */
    val isReadOnly: Boolean get() = mountOptions.contains("ro,") || mountOptions.startsWith("ro")

    /** Whether mounted with noatime */
    val isNoAtime: Boolean get() = mountOptions.contains("noatime")
}
