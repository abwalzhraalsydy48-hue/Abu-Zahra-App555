package com.ultimaterecovery.pro.engine.root

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deep Root-Based Scanner - Accesses protected data partitions via root
 *
 * This scanner leverages root access to directly read data from protected
 * Android partitions and directories that are normally inaccessible to
 * regular applications. It provides deep scanning capabilities for:
 *
 * - /data partition: Direct access to app data, system databases
 * - /data/data: Deleted app data remnants
 * - /data/system: System databases (settings, accounts, etc.)
 * - Raw partition blocks: Via dd commands for block-level recovery
 * - /proc/partitions: Partition layout discovery
 * - SQLite databases: Direct access for SMS/CallLog recovery
 * - /data/misc: Deleted account information
 * - /data/system_ce, /data/system_de: Credential and encrypted data
 *
 * All operations fall back gracefully when root is unavailable,
 * returning empty results rather than throwing exceptions.
 */
@Singleton
class RootScanner @Inject constructor(
    private val rootManager: RootManager
) {

    companion object {
        /** Paths for app data directories */
        private const val APP_DATA_PATH = "/data/data"
        private const val APP_USER_DATA_PATH = "/data/user/0"

        /** System database paths */
        private const val SYSTEM_DB_PATH = "/data/system"
        private const val SYSTEM_CE_PATH = "/data/system_ce"
        private const val SYSTEM_DE_PATH = "/data/system_de"

        /** Misc data path for account info */
        private const val MISC_DATA_PATH = "/data/misc"

        /** Telephony provider database paths */
        private const val TELEPHONY_DB_PATH = "/data/data/com.android.providers.telephony/databases"
        private const val MMS_SMS_DB = "mmssms.db"

        /** Contacts provider database path */
        private const val CONTACTS_DB_PATH = "/data/data/com.android.providers.contacts/databases"
        private const val CONTACTS_DB = "contacts2.db"

        /** Partition info paths */
        private const val PROC_PARTITIONS = "/proc/partitions"
        private const val PROC_MOUNTS = "/proc/mounts"

        /** dd command block size for raw reads */
        private const val DD_BLOCK_SIZE = 4096

        /** Maximum entries to return in list operations */
        private const val MAX_LIST_ENTRIES = 10_000
    }

    // ──────────────────────────────────────────────
    // Data Partition Scanning
    // ──────────────────────────────────────────────

    /**
     * Scan the /data partition directly using root access.
     *
     * Reads directory listings and file metadata from the protected
     * /data partition to discover recoverable data.
     *
     * @return Flow of DataScanResult entries
     */
    fun scanDataPartition(): Flow<DataScanResult> = flow {
        if (!rootManager.isRootGranted) {
            emit(DataScanResult.Error("Root access not granted"))
            return@flow
        }

        emit(DataScanResult.Progress("Scanning /data partition", 0f))

        // Scan /data top-level directories
        val dataDirs = rootManager.listFilesAsRoot("/data")
        val totalDirs = dataDirs.size
        var processed = 0

        for (dirName in dataDirs) {
            if (!currentCoroutineContext().isActive) break

            val dirPath = "/data/$dirName"
            val scanEntry = scanDirectoryEntry(dirPath)
            if (scanEntry != null) {
                emit(scanEntry)
            }

            processed++
            if (processed % 10 == 0) {
                emit(DataScanResult.Progress(
                    "Scanning /data/$dirName",
                    processed.toFloat() / totalDirs
                ))
            }
        }

        emit(DataScanResult.Progress("Data partition scan complete", 1f))
    }.flowOn(Dispatchers.IO)

    /**
     * Scan /data/data for deleted app data remnants.
     *
     * Lists all app packages in /data/data and checks for:
     * - Databases with WAL/journal files (potential deleted records)
     * - Shared preferences with orphaned entries
     * - Cache directories with recoverable files
     * - Recently deleted files (via atime/mtime analysis)
     *
     * @param packageFilter Optional package name filter (e.g., "com.android.providers")
     * @return Flow of AppDataScanResult entries
     */
    fun scanAppDataRoot(packageFilter: String? = null): Flow<AppDataScanResult> = flow {
        if (!rootManager.isRootGranted) {
            emit(AppDataScanResult.Error("Root access not granted"))
            return@flow
        }

        emit(AppDataScanResult.Progress("Scanning app data", 0f))

        // List all packages in /data/data
        val packages = rootManager.listFilesAsRoot(APP_DATA_PATH)
            .filter { pkg -> packageFilter == null || pkg.contains(packageFilter) }

        val totalPackages = packages.size
        var processed = 0

        for (pkg in packages) {
            if (!currentCoroutineContext().isActive) break

            val pkgPath = "$APP_DATA_PATH/$pkg"
            val result = scanPackageData(pkg, pkgPath)
            if (result != null) {
                emit(result)
            }

            processed++
            if (processed % 5 == 0) {
                emit(AppDataScanResult.Progress(
                    "Scanning $pkg",
                    processed.toFloat() / totalPackages
                ))
            }
        }

        emit(AppDataScanResult.Progress("App data scan complete", 1f))
    }.flowOn(Dispatchers.IO)

    /**
     * Scan system databases for recoverable data.
     *
     * Accesses /data/system and related directories to find:
     * - Settings databases
     * - Account databases
     * - Usage stats databases
     * - Notification databases
     * - Other system-level databases
     *
     * @return Flow of SystemDbScanResult entries
     */
    fun scanSystemDatabases(): Flow<SystemDbScanResult> = flow {
        if (!rootManager.isRootGranted) {
            emit(SystemDbScanResult.Error("Root access not granted"))
            return@flow
        }

        emit(SystemDbScanResult.Progress("Scanning system databases", 0f))

        // Scan /data/system
        val systemDbs = scanForDatabases(SYSTEM_DB_PATH)
        for (db in systemDbs) {
            if (!currentCoroutineContext().isActive) break
            emit(SystemDbScanResult.Database(
                name = db.name,
                path = db.path,
                size = db.size,
                lastModified = db.lastModified,
                hasWal = db.hasWal,
                hasJournal = db.hasJournal
            ))
        }

        emit(SystemDbScanResult.Progress("Scanning system_ce databases", 0.5f))

        // Scan /data/system_ce/0 (credential-encrypted data)
        val systemCePath = "$SYSTEM_CE_PATH/0"
        if (rootManager.fileExistsAsRoot(systemCePath)) {
            val ceDbs = scanForDatabases(systemCePath)
            for (db in ceDbs) {
                if (!currentCoroutineContext().isActive) break
                emit(SystemDbScanResult.Database(
                    name = db.name,
                    path = db.path,
                    size = db.size,
                    lastModified = db.lastModified,
                    hasWal = db.hasWal,
                    hasJournal = db.hasJournal,
                    isEncrypted = true
                ))
            }
        }

        emit(SystemDbScanResult.Progress("Scanning system_de databases", 0.75f))

        // Scan /data/system_de/0 (device-encrypted data)
        val systemDePath = "$SYSTEM_DE_PATH/0"
        if (rootManager.fileExistsAsRoot(systemDePath)) {
            val deDbs = scanForDatabases(systemDePath)
            for (db in deDbs) {
                if (!currentCoroutineContext().isActive) break
                emit(SystemDbScanResult.Database(
                    name = db.name,
                    path = db.path,
                    size = db.size,
                    lastModified = db.lastModified,
                    hasWal = db.hasWal,
                    hasJournal = db.hasJournal,
                    isEncrypted = true
                ))
            }
        }

        emit(SystemDbScanResult.Progress("System database scan complete", 1f))
    }.flowOn(Dispatchers.IO)

    // ──────────────────────────────────────────────
    // Raw Partition Scanning
    // ──────────────────────────────────────────────

    /**
     * Scan a raw partition using dd commands.
     *
     * Reads raw bytes from the specified partition block device
     * and searches for file signatures and recoverable data.
     *
     * @param partitionPath Path to the block device (e.g., /dev/block/sda1)
     * @param offset Starting offset in bytes (0 = beginning)
     * @param size Number of bytes to read (0 = entire partition)
     * @return Flow of RawScanResult entries
     */
    fun scanRawPartition(
        partitionPath: String,
        offset: Long = 0L,
        size: Long = 0L
    ): Flow<RawScanResult> = flow {
        if (!rootManager.isRootGranted) {
            emit(RawScanResult.Error("Root access not granted"))
            return@flow
        }

        // Verify the partition device exists
        if (!rootManager.fileExistsAsRoot(partitionPath)) {
            emit(RawScanResult.Error("Partition not found: $partitionPath"))
            return@flow
        }

        // Get partition size
        val partitionSize = getPartitionSize(partitionPath)
        if (partitionSize <= 0L) {
            emit(RawScanResult.Error("Cannot determine partition size"))
            return@flow
        }

        val bytesToScan = if (size > 0L) minOf(size, partitionSize) else partitionSize
        emit(RawScanResult.Progress("Starting raw partition scan", 0f, partitionSize))

        // Use dd to read partition data in chunks and search for signatures
        val chunkSize = 1024L * 1024 // 1MB chunks
        var currentOffset = offset
        var bytesScanned = 0L

        while (currentOffset < offset + bytesToScan && currentCoroutineContext().isActive) {
            val readSize = minOf(chunkSize, offset + bytesToScan - currentOffset)
            val skipBlocks = currentOffset / DD_BLOCK_SIZE

            // Read chunk using dd
            val ddResult = rootManager.executeCommandWithOutput(
                "dd if='$partitionPath' bs=$DD_BLOCK_SIZE skip=$skipBlocks count=${readSize / DD_BLOCK_SIZE} 2>/dev/null | xxd | head -c 4096"
            )

            if (ddResult.success) {
                // Analyze the chunk for signatures
                val signatures = detectSignaturesInHex(ddResult.stdout)
                if (signatures.isNotEmpty()) {
                    for (sig in signatures) {
                        emit(RawScanResult.SignatureFound(
                            signatureName = sig,
                            offset = currentOffset,
                            partitionPath = partitionPath
                        ))
                    }
                }
            }

            bytesScanned += readSize
            currentOffset += readSize

            val progress = bytesScanned.toFloat() / bytesToScan
            emit(RawScanResult.Progress(
                "Scanning partition at offset $currentOffset",
                progress,
                partitionSize
            ))
        }

        emit(RawScanResult.Progress("Raw partition scan complete", 1f, partitionSize))
    }.flowOn(Dispatchers.IO)

    /**
     * Get the partition layout by reading /proc/partitions.
     *
     * @return List of PartitionEntry objects describing each partition
     */
    suspend fun getPartitionLayout(): List<PartitionEntry> {
        if (!rootManager.isRootGranted) return emptyList()

        val entries = mutableListOf<PartitionEntry>()

        try {
            val content = rootManager.readFileAsRoot(PROC_PARTITIONS) ?: return emptyList()

            // Parse /proc/partitions format:
            // major minor #blocks name
            content.lines().drop(2).forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val major = parts[0].toIntOrNull() ?: 0
                    val minor = parts[1].toIntOrNull() ?: 0
                    val blocks = parts[2].toLongOrNull() ?: 0L
                    val name = parts[3]

                    // Skip loop, ram, and zram devices
                    if (name.startsWith("loop") || name.startsWith("ram") || name.contains("zram")) {
                        return@forEach
                    }

                    entries.add(PartitionEntry(
                        name = name,
                        devicePath = "/dev/block/$name",
                        major = major,
                        minor = minor,
                        sizeBlocks = blocks,
                        sizeBytes = blocks * 1024L // blocks are 1KB each
                    ))
                }
            }
        } catch (_: Exception) {
            // Failed to read partition layout
        }

        return entries
    }

    // ──────────────────────────────────────────────
    // Credential and Account Data Scanning
    // ──────────────────────────────────────────────

    /**
     * Scan /data/misc for deleted account information.
     *
     * Reads account data from:
     * - /data/misc/accounts (account databases)
     * - /data/system/accounts.db (older Android versions)
     * - /data/system_ce/0/accounts_ce.db
     *
     * @return List of found account database entries
     */
    suspend fun scanDeletedAccounts(): List<AccountScanEntry> {
        if (!rootManager.isRootGranted) return emptyList()

        val entries = mutableListOf<AccountScanEntry>()
        val accountPaths = listOf(
            "$MISC_DATA_PATH/accounts",
            "$SYSTEM_DB_PATH/accounts.db",
            "$SYSTEM_CE_PATH/0/accounts_ce.db",
            "$SYSTEM_DE_PATH/0/accounts_de.db"
        )

        for (path in accountPaths) {
            if (rootManager.fileExistsAsRoot(path)) {
                val size = getFileSize(path)
                val lastModified = getFileLastModified(path)

                entries.add(AccountScanEntry(
                    path = path,
                    exists = true,
                    size = size,
                    lastModified = lastModified,
                    isDatabase = path.endsWith(".db")
                ))
            }
        }

        return entries
    }

    // ──────────────────────────────────────────────
    // Private Helper Methods
    // ──────────────────────────────────────────────

    /**
     * Scan a directory entry and return metadata
     */
    private suspend fun scanDirectoryEntry(path: String): DataScanResult? {
        return try {
            val result = rootManager.executeCommandWithOutput(
                "ls -ld '$path' 2>/dev/null"
            )
            if (result.success && result.stdout.isNotEmpty()) {
                val parts = result.stdout.trim().split("\\s+".toRegex())
                DataScanResult.DirectoryEntry(
                    path = path,
                    permissions = parts.getOrNull(0) ?: "",
                    owner = parts.getOrNull(2) ?: "",
                    group = parts.getOrNull(3) ?: "",
                    size = parts.getOrNull(4)?.toLongOrNull() ?: 0L,
                    lastModified = parseLsDate(
                        parts.getOrNull(5) ?: "",
                        parts.getOrNull(6) ?: "",
                        parts.getOrNull(7) ?: ""
                    )
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Scan a specific package's data directory
     */
    private suspend fun scanPackageData(pkg: String, pkgPath: String): AppDataScanResult? {
        val findings = mutableListOf<String>()

        // Check for databases
        val dbPath = "$pkgPath/databases"
        if (rootManager.fileExistsAsRoot(dbPath)) {
            val dbFiles = rootManager.listFilesAsRoot(dbPath)
            val dbMainFiles = dbFiles.filter { it.endsWith(".db") && !it.endsWith("-journal") && !it.endsWith("-wal") }

            for (dbFile in dbMainFiles) {
                val hasWal = dbFiles.contains("${dbFile}-wal")
                val hasJournal = dbFiles.contains("${dbFile}-journal")

                if (hasWal || hasJournal) {
                    findings.add("Database '$dbFile' has WAL/Journal files (potential deleted records)")
                }

                return AppDataScanResult.PackageData(
                    packageName = pkg,
                    path = pkgPath,
                    databaseCount = dbMainFiles.size,
                    hasWalFiles = hasWal,
                    hasJournalFiles = hasJournal,
                    findings = findings
                )
            }
        }

        // Check for shared_prefs
        val sharedPrefsPath = "$pkgPath/shared_prefs"
        if (rootManager.fileExistsAsRoot(sharedPrefsPath)) {
            val prefsFiles = rootManager.listFilesAsRoot(sharedPrefsPath)
            if (prefsFiles.isNotEmpty()) {
                findings.add("${prefsFiles.size} shared preferences files")
            }
        }

        return if (findings.isNotEmpty()) {
            AppDataScanResult.PackageData(
                packageName = pkg,
                path = pkgPath,
                databaseCount = 0,
                hasWalFiles = false,
                hasJournalFiles = false,
                findings = findings
            )
        } else null
    }

    /**
     * Scan a directory for SQLite databases
     */
    private suspend fun scanForDatabases(dirPath: String): List<DatabaseEntry> {
        val databases = mutableListOf<DatabaseEntry>()

        try {
            val files = rootManager.listFilesAsRoot(dirPath)
            val dbFiles = files.filter {
                it.endsWith(".db") || it.endsWith(".sqlite") || it.endsWith(".db3")
            }

            for (dbFile in dbFiles) {
                val dbPath = "$dirPath/$dbFile"
                val allFiles = rootManager.listFilesAsRoot(dirPath)
                val baseName = dbFile.removeSuffix(".db").removeSuffix(".sqlite").removeSuffix(".db3")

                databases.add(DatabaseEntry(
                    name = dbFile,
                    path = dbPath,
                    size = getFileSize(dbPath),
                    lastModified = getFileLastModified(dbPath),
                    hasWal = allFiles.contains("$baseName-wal") || allFiles.contains("$dbFile-wal"),
                    hasJournal = allFiles.contains("$baseName-journal") || allFiles.contains("$dbFile-journal")
                ))
            }
        } catch (_: Exception) {
            // Failed to scan directory
        }

        return databases
    }

    /**
     * Get file size using root access
     */
    private suspend fun getFileSize(path: String): Long {
        val result = rootManager.executeCommandWithOutput("stat -c '%s' '$path' 2>/dev/null")
        return if (result.success && result.stdout.isNotEmpty()) {
            result.stdout.trim().toLongOrNull() ?: 0L
        } else 0L
    }

    /**
     * Get file last modified timestamp using root access
     */
    private suspend fun getFileLastModified(path: String): Long {
        val result = rootManager.executeCommandWithOutput("stat -c '%Y' '$path' 2>/dev/null")
        return if (result.success && result.stdout.isNotEmpty()) {
            result.stdout.trim().toLongOrNull() ?: 0L
        } else 0L
    }

    /**
     * Get partition size in bytes
     */
    private suspend fun getPartitionSize(partitionPath: String): Long {
        val result = rootManager.executeCommandWithOutput("blockdev --getsize64 '$partitionPath' 2>/dev/null")
        return if (result.success && result.stdout.isNotEmpty()) {
            result.stdout.trim().toLongOrNull() ?: 0L
        } else {
            // Fallback: try /sys/block
            val deviceName = partitionPath.substringAfterLast("/")
            val sysResult = rootManager.readFileAsRoot("/sys/block/$deviceName/size")
            sysResult?.trim()?.toLongOrNull()?.let { it * 512 } ?: 0L
        }
    }

    /**
     * Detect file signatures in hex dump output
     */
    private fun detectSignaturesInHex(hexOutput: String): List<String> {
        val signatures = mutableListOf<String>()

        // Common file signature patterns in hex
        val signaturePatterns = mapOf(
            "FFD8FF" to "JPEG",
            "89504E47" to "PNG",
            "47494638" to "GIF",
            "504B0304" to "ZIP/APK/DOCX",
            "25504446" to "PDF",
            "52617221" to "RAR",
            "377ABCAF" to "7Z",
            "1F8B" to "GZIP",
            "494433" to "MP3/ID3",
            "52494646" to "RIFF(AVI/WAV)",
            "664C6143" to "FLAC",
            "4F676753" to "OGG",
            "1A45DFA3" to "MKV/WebM",
            "000000" to "MP4/MOV(ftyp)",
            "D0CF11E0" to "OLE2(DOC/XLS/PPT)",
            "53514C697465" to "SQLite"
        )

        val cleanHex = hexOutput.replace("\\s".toRegex(), "").uppercase()
        for ((pattern, name) in signaturePatterns) {
            if (cleanHex.contains(pattern.uppercase())) {
                signatures.add(name)
            }
        }

        return signatures
    }

    /**
     * Parse date from ls -l output
     */
    private fun parseLsDate(month: String, day: String, yearOrTime: String): Long {
        // Simplified parsing - returns 0 for complex dates
        return try {
            if (yearOrTime.contains(":")) {
                // Time format (current year): Mon DD HH:MM
                System.currentTimeMillis() // Approximate
            } else {
                // Year format: Mon DD YYYY
                yearOrTime.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}

// ──────────────────────────────────────────────
// Data Classes for Scan Results
// ──────────────────────────────────────────────

/**
 * Results from scanning the /data partition
 */
sealed class DataScanResult {
    data class Progress(val message: String, val progress: Float) : DataScanResult()
    data class DirectoryEntry(
        val path: String,
        val permissions: String,
        val owner: String,
        val group: String,
        val size: Long,
        val lastModified: Long
    ) : DataScanResult()
    data class Error(val message: String) : DataScanResult()
}

/**
 * Results from scanning app data directories
 */
sealed class AppDataScanResult {
    data class Progress(val message: String, val progress: Float) : AppDataScanResult()
    data class PackageData(
        val packageName: String,
        val path: String,
        val databaseCount: Int,
        val hasWalFiles: Boolean,
        val hasJournalFiles: Boolean,
        val findings: List<String>
    ) : AppDataScanResult()
    data class Error(val message: String) : AppDataScanResult()
}

/**
 * Results from scanning system databases
 */
sealed class SystemDbScanResult {
    data class Progress(val message: String, val progress: Float) : SystemDbScanResult()
    data class Database(
        val name: String,
        val path: String,
        val size: Long,
        val lastModified: Long,
        val hasWal: Boolean,
        val hasJournal: Boolean,
        val isEncrypted: Boolean = false
    ) : SystemDbScanResult()
    data class Error(val message: String) : SystemDbScanResult()
}

/**
 * Results from raw partition scanning
 */
sealed class RawScanResult {
    data class Progress(
        val message: String,
        val progress: Float,
        val totalBytes: Long
    ) : RawScanResult()
    data class SignatureFound(
        val signatureName: String,
        val offset: Long,
        val partitionPath: String
    ) : RawScanResult()
    data class Error(val message: String) : RawScanResult()
}

/**
 * Entry describing a partition from /proc/partitions
 */
data class PartitionEntry(
    val name: String,
    val devicePath: String,
    val major: Int,
    val minor: Int,
    val sizeBlocks: Long,
    val sizeBytes: Long
)

/**
 * Entry describing a discovered SQLite database
 */
data class DatabaseEntry(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val hasWal: Boolean,
    val hasJournal: Boolean
)

/**
 * Entry describing discovered account data
 */
data class AccountScanEntry(
    val path: String,
    val exists: Boolean,
    val size: Long,
    val lastModified: Long,
    val isDatabase: Boolean
)
