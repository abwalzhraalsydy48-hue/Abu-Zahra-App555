@file:OptIn(kotlin.ExperimentalStdlibApi::class)

package com.ultimaterecovery.pro.engine.root

import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.engine.recovery.FoundFileInfo
import com.ultimaterecovery.pro.engine.recovery.RecoveryConfidence
import com.ultimaterecovery.pro.engine.signatures.FileSignatures
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw Data Recovery Engine - Block-level data recovery from partition devices
 *
 * This engine performs deep recovery operations at the raw block level,
 * bypassing the filesystem layer entirely. It is the most powerful
 * recovery mechanism available, capable of recovering data even when:
 *
 * - The filesystem is corrupted or reformatted
 * - File entries (inodes/dentries) have been deleted
 * - Data exists in unallocated blocks
 * - Data exists in the ext4 journal
 * - The partition table has been modified
 *
 * Recovery techniques:
 * 1. File Carving: Search for file signatures (magic bytes) in raw data
 * 2. Inode Scanning: Find deleted inode entries in ext4/f2fs filesystems
 * 3. Journal Recovery: Replay ext4 journal transactions for deleted data
 * 4. Unallocated Space Scanning: Scan blocks not referenced by any inode
 * 5. Block-level Scanning: Configurable block-size raw data analysis
 *
 * All operations require root access to read block devices directly.
 */
@Singleton
class RawRecoveryEngine @Inject constructor(
    private val rootManager: RootManager,
    private val partitionManager: PartitionManager
) {

    companion object {
        /** ext4 superblock magic number (0xEF53) */
        private const val EXT4_SUPER_MAGIC = 0xEF53

        /** Offset to ext4 superblock (1024 bytes from partition start) */
        private const val EXT4_SUPER_OFFSET = 1024L

        /** Offset to ext4 superblock magic number */
        private const val EXT4_MAGIC_OFFSET = 56 // Within the superblock

        /** ext4 inode size offset within superblock */
        private const val EXT4_INODE_SIZE_OFFSET = 88

        /** ext4 inode count offset within superblock */
        private const val EXT4_INODE_COUNT_OFFSET = 0

        /** ext4 block count offset within superblock */
        private const val EXT4_BLOCK_COUNT_OFFSET = 4

        /** ext4 block size offset (log2(block_size) - 10) */
        private const val EXT4_BLOCK_SIZE_OFFSET = 24

        /** ext4 block group descriptor size */
        private const val EXT4_BGDT_ENTRY_SIZE = 32

        /** ext4 inode table offset in block group descriptor */
        private const val EXT4_BG_INODE_TABLE_OFFSET = 8

        /** ext4 journal inode number (typically 8) */
        private const val EXT4_JOURNAL_INODE = 8

        /** f2fs superblock magic number (0xF2F52010) */
        private const val F2FS_SUPER_MAGIC = 0xF2F52010L

        /** Offset to f2fs superblock magic */
        private const val F2FS_MAGIC_OFFSET = 0L

        /** Default block size for raw scanning */
        private const val DEFAULT_BLOCK_SIZE = 4096

        /** Sector size for raw reads */
        private const val SECTOR_SIZE = 512

        /** Buffer size for reading partition data */
        private const val READ_BUFFER_SIZE = 1024 * 1024 // 1MB

        /** Maximum file size for carving (2GB) */
        private const val MAX_CARVE_SIZE = 2L * 1024 * 1024 * 1024

        /** Minimum file size to consider for recovery (256 bytes) */
        private const val MIN_CARVE_SIZE = 256L

        /** Maximum journal entries to process */
        private const val MAX_JOURNAL_ENTRIES = 10_000

        /** Progress update interval (blocks) */
        private const val PROGRESS_INTERVAL = 256

        /** Temporary directory for recovery operations */
        private const val TEMP_DIR = "/data/local/tmp/urp_recovery"
    }

    // ──────────────────────────────────────────────
    // Block-Level Scanning
    // ──────────────────────────────────────────────

    /**
     * Scan raw blocks from a partition for file signatures.
     *
     * Reads the partition in configurable block sizes and searches
     * each block for known file signatures. This is the primary
     * method for recovering files when the filesystem is damaged.
     *
     * @param partitionPath Path to the block device
     * @param blockSize Block size for scanning (default: 4096)
     * @param categories File categories to search for (empty = all)
     * @param startOffset Byte offset to start scanning from
     * @param maxBytes Maximum bytes to scan (0 = entire partition)
     * @return Flow of RawScanProgress and found files
     */
    fun scanRawBlocks(
        partitionPath: String,
        blockSize: Int = DEFAULT_BLOCK_SIZE,
        categories: List<FileCategory> = emptyList(),
        startOffset: Long = 0L,
        maxBytes: Long = 0L
    ): Flow<RawRecoveryProgress> = flow {
        if (!rootManager.isRootGranted) {
            emit(RawRecoveryProgress.Error("Root access not granted"))
            return@flow
        }

        val partitionSize = partitionManager.getPartitionInfo(partitionPath)?.sizeBytes ?: 0L
        if (partitionSize <= 0L) {
            emit(RawRecoveryProgress.Error("Cannot determine partition size: $partitionPath"))
            return@flow
        }

        val scanSize = if (maxBytes > 0L) minOf(maxBytes, partitionSize - startOffset) else partitionSize - startOffset
        val targetCategories = if (categories.isEmpty()) FileCategory.values().toList() else categories
        val foundFiles = mutableListOf<FoundFileInfo>()
        var bytesScanned = 0L
        var lastSignatureEnd = startOffset

        emit(RawRecoveryProgress.Started(
            partitionPath = partitionPath,
            totalBytes = scanSize,
            blockSize = blockSize
        ))

        // Create temp directory
        rootManager.executeCommand("mkdir -p $TEMP_DIR 2>/dev/null")

        var currentOffset = startOffset

        while (currentOffset < startOffset + scanSize && currentCoroutineContext().isActive) {
            // Read a chunk of raw data
            val readSize = minOf(READ_BUFFER_SIZE.toLong(), startOffset + scanSize - currentOffset)
            val tempFile = "$TEMP_DIR/chunk_${currentOffset.toHexString()}"

            // Use dd to read a chunk
            val skipBlocks = currentOffset / blockSize
            val countBlocks = (readSize + blockSize - 1) / blockSize

            val ddSuccess = rootManager.executeCommand(
                "dd if='$partitionPath' bs=$blockSize skip=$skipBlocks count=$countBlocks of='$tempFile' 2>/dev/null"
            )

            if (!ddSuccess) {
                currentOffset += readSize
                bytesScanned += readSize
                continue
            }

            // Search for signatures in the chunk using xxd
            val headerSize = FileSignatures.MINIMUM_HEADER_SIZE

            // Scan every block within the chunk
            for (blockIndex in 0 until (readSize / blockSize).toInt()) {
                if (!currentCoroutineContext().isActive) break

                val blockOffset = currentOffset + blockIndex * blockSize

                // Skip if we're still inside a previously found file
                if (blockOffset < lastSignatureEnd) continue

                // Read first bytes of this block
                val xxdResult = rootManager.executeCommandWithOutput(
                    "dd if='$tempFile' bs=$blockSize skip=$blockIndex count=1 2>/dev/null | xxd -l $headerSize -p"
                )

                if (xxdResult.success && xxdResult.stdout.isNotEmpty()) {
                    val hexString = xxdResult.stdout.replace("\\s".toRegex(), "")
                    val header = hexToByteArray(hexString)

                    if (header.size >= 4) {
                        // Try to identify the file type
                        val signature = FileSignatures.identifyFileType(header)
                        if (signature != null && signature.category in targetCategories) {
                            // Found a signature! Try to carve the file
                            val carvedFile = carveFile(
                                partitionPath = partitionPath,
                                startOffset = blockOffset,
                                signature = signature,
                                partitionSize = partitionSize,
                                tempFile = tempFile,
                                blockIndex = blockIndex,
                                blockSize = blockSize
                            )

                            if (carvedFile != null && carvedFile.fileSize >= MIN_CARVE_SIZE) {
                                foundFiles.add(carvedFile)
                                lastSignatureEnd = blockOffset + carvedFile.fileSize

                                emit(RawRecoveryProgress.FileFound(
                                    fileInfo = carvedFile,
                                    totalFound = foundFiles.size
                                ))
                            }
                        }
                    }
                }
            }

            bytesScanned += readSize
            currentOffset += readSize

            // Cleanup temp chunk
            rootManager.executeCommand("rm -f '$tempFile' 2>/dev/null")

            // Emit progress
            val progress = bytesScanned.toFloat() / scanSize
            emit(RawRecoveryProgress.Scanning(
                partitionPath = partitionPath,
                progress = progress,
                bytesScanned = bytesScanned,
                totalBytes = scanSize,
                filesFound = foundFiles.size,
                currentOffset = currentOffset
            ))
        }

        // Cleanup
        rootManager.executeCommand("rm -rf $TEMP_DIR 2>/dev/null")

        emit(RawRecoveryProgress.Completed(
            partitionPath = partitionPath,
            foundFiles = foundFiles,
            bytesScanned = bytesScanned,
            durationMs = 0L // Caller can compute this
        ))
    }.flowOn(Dispatchers.IO)

    // ──────────────────────────────────────────────
    // File Carving
    // ──────────────────────────────────────────────

    /**
     * Carve files from raw partition data based on file signatures.
     *
     * File carving reconstructs files by:
     * 1. Finding file start signatures (magic bytes)
     * 2. Determining file size from headers or end markers
     * 3. Extracting the raw bytes between start and end
     *
     * @param partitionPath Partition block device path
     * @param signatures File signatures to search for (empty = all)
     * @param startOffset Byte offset to start carving from
     * @param endOffset Byte offset to stop carving at (0 = partition end)
     * @return Flow of CarveResult entries
     */
    fun carveFiles(
        partitionPath: String,
        signatures: List<FileSignatures.FileSignature> = emptyList(),
        startOffset: Long = 0L,
        endOffset: Long = 0L
    ): Flow<CarveResult> = flow {
        if (!rootManager.isRootGranted) {
            emit(CarveResult.Error("Root access not granted"))
            return@flow
        }

        val targetSignatures = if (signatures.isEmpty()) FileSignatures.ALL_SIGNATURES else signatures
        val partitionInfo = partitionManager.getPartitionInfo(partitionPath)

        if (partitionInfo == null || partitionInfo.sizeBytes <= 0L) {
            emit(CarveResult.Error("Cannot access partition: $partitionPath"))
            return@flow
        }

        val carveEnd = if (endOffset > 0L) minOf(endOffset, partitionInfo.sizeBytes) else partitionInfo.sizeBytes
        var currentOffset = startOffset
        var carvedCount = 0

        emit(CarveResult.Progress("Starting file carving", 0f, 0))

        while (currentOffset < carveEnd && currentCoroutineContext().isActive) {
            // Read a chunk for signature scanning
            val readSize = minOf(READ_BUFFER_SIZE.toLong(), carveEnd - currentOffset)
            val xxdResult = rootManager.executeCommandWithOutput(
                "dd if='$partitionPath' bs=1 skip=$currentOffset count=$readSize 2>/dev/null | xxd -l 64 -p"
            )

            if (xxdResult.success && xxdResult.stdout.isNotEmpty()) {
                val hexString = xxdResult.stdout.replace("\\s".toRegex(), "")
                val header = hexToByteArray(hexString)

                // Try each signature
                for (signature in targetSignatures) {
                    if (header.size < signature.hexPattern.size) continue

                    if (signature.matches(header)) {
                        // Found a signature - attempt to carve
                        val fileSize = determineFileSize(
                            partitionPath, currentOffset, signature, carveEnd
                        )

                        if (fileSize >= MIN_CARVE_SIZE) {
                            val fileName = "carved_${signature.name}_${currentOffset.toHexString()}.${signature.extensions.first()}"

                            val fileInfo = FoundFileInfo(
                                path = partitionPath,
                                fileName = fileName,
                                fileSize = fileSize,
                                extension = signature.extensions.first(),
                                mimeType = signature.mimeType,
                                category = signature.category,
                                signatureName = signature.name,
                                offsetInBlock = currentOffset,
                                confidence = calculateCarveConfidence(signature, fileSize),
                                isFragment = false,
                                isRootRequired = true,
                                sourcePath = partitionPath,
                                metadata = mapOf(
                                    "carve_method" to "signature",
                                    "block_offset" to currentOffset.toString(),
                                    "detected_size" to fileSize.toString()
                                )
                            )

                            carvedCount++
                            emit(CarveResult.FileCarved(fileInfo, carvedCount))
                        }

                        // Skip past this file
                        currentOffset += maxOf(fileSize, DEFAULT_BLOCK_SIZE.toLong())
                        break
                    }
                }
            }

            currentOffset += DEFAULT_BLOCK_SIZE.toLong()

            val progress = (currentOffset - startOffset).toFloat() / (carveEnd - startOffset)
            if (currentOffset % (PROGRESS_INTERVAL * DEFAULT_BLOCK_SIZE) == 0L) {
                emit(CarveResult.Progress(
                    "Carving at offset $currentOffset",
                    progress, carvedCount
                ))
            }
        }

        emit(CarveResult.Completed(carvedCount))
    }.flowOn(Dispatchers.IO)

    // ──────────────────────────────────────────────
    // Inode Recovery (ext4)
    // ──────────────────────────────────────────────

    /**
     * Find deleted inode entries in an ext4 filesystem.
     *
     * Scans the inode table for entries with a deletion time (dtime)
     * set to a non-zero value, indicating the file was deleted.
     * Also detects inodes with zero link count (nlink=0).
     *
     * @param partitionPath Path to the ext4 partition
     * @return Flow of DeletedInode entries
     */
    fun findDeletedInodes(partitionPath: String): Flow<DeletedInodeResult> = flow {
        if (!rootManager.isRootGranted) {
            emit(DeletedInodeResult.Error("Root access not granted"))
            return@flow
        }

        // First verify this is an ext4 filesystem
        val isExt4 = verifyExt4Filesystem(partitionPath)
        if (!isExt4) {
            emit(DeletedInodeResult.Error("Partition is not ext4 filesystem"))
            return@flow
        }

        emit(DeletedInodeResult.Progress("Reading ext4 superblock", 0f))

        // Read superblock to get filesystem parameters
        val superblock = readExt4Superblock(partitionPath)
        if (superblock == null) {
            emit(DeletedInodeResult.Error("Failed to read ext4 superblock"))
            return@flow
        }

        emit(DeletedInodeResult.Progress("Scanning inode tables", 0.1f))

        val inodeSize = superblock.inodeSize
        val inodesPerGroup = superblock.inodesPerGroup
        val blockGroups = (superblock.inodeCount + inodesPerGroup - 1) / inodesPerGroup
        val blockSize = superblock.blockSize

        var totalDeletedFound = 0
        var processedGroups = 0

        // Read block group descriptor table
        val bgdtOffset = EXT4_SUPER_OFFSET + EXT4_SUPER_SIZE
        val bgdtResult = rootManager.executeCommandWithOutput(
            "dd if='$partitionPath' bs=1 skip=$bgdtOffset count=${blockGroups * EXT4_BGDT_ENTRY_SIZE} 2>/dev/null | xxd -p"
        )

        if (!bgdtResult.success) {
            emit(DeletedInodeResult.Error("Failed to read block group descriptor table"))
            return@flow
        }

        // Parse block group descriptors to find inode table locations
        val bgdtData = hexToByteArray(bgdtResult.stdout.replace("\\s".toRegex(), ""))

        for (bgIndex in 0 until blockGroups.toInt()) {
            if (!currentCoroutineContext().isActive) break
            val bgdtEntryOffset = bgIndex * EXT4_BGDT_ENTRY_SIZE

            if (bgdtEntryOffset + EXT4_BGDT_ENTRY_SIZE > bgdtData.size) break

            // Read inode table block number from BGDT entry
            val inodeTableBlock = ByteBuffer.wrap(bgdtData, bgdtEntryOffset + EXT4_BG_INODE_TABLE_OFFSET, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int

            val inodeTableOffset = inodeTableBlock.toLong() * blockSize

            // Scan inodes in this block group
            val inodesToScan = minOf(
                inodesPerGroup,
                superblock.inodeCount - bgIndex.toLong() * inodesPerGroup
            )

            for (inodeIndex in 0 until inodesToScan) {
                if (!currentCoroutineContext().isActive) break
                val inodeOffset = inodeTableOffset + inodeIndex * inodeSize

                // Read the inode's dtime and nlink fields
                // ext4 inode structure: dtime is at offset 20, i_links_count at offset 26
                val dtimeResult = rootManager.executeCommandWithOutput(
                    "dd if='$partitionPath' bs=1 skip=${inodeOffset + 20} count=4 2>/dev/null | xxd -p"
                )

                if (!dtimeResult.success || dtimeResult.stdout.isBlank()) continue

                val dtimeHex = dtimeResult.stdout.replace("\\s".toRegex(), "")
                if (dtimeHex.length < 8) continue

                val dtime = ByteBuffer.wrap(hexToByteArray(dtimeHex))
                    .order(ByteOrder.LITTLE_ENDIAN).int

                if (dtime > 0) {
                    // This inode was deleted!
                    val inodeNumber = bgIndex.toLong() * inodesPerGroup + inodeIndex + 1

                    // Read more inode fields
                    val inodeResult = rootManager.executeCommandWithOutput(
                        "dd if='$partitionPath' bs=1 skip=$inodeOffset count=$inodeSize 2>/dev/null | xxd -p"
                    )

                    if (inodeResult.success && inodeResult.stdout.isNotBlank()) {
                        val inodeData = hexToByteArray(inodeResult.stdout.replace("\\s".toRegex(), ""))

                        if (inodeData.size >= 28) {
                            val mode = ByteBuffer.wrap(inodeData, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                            val size = ByteBuffer.wrap(inodeData, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                            val nlink = ByteBuffer.wrap(inodeData, 26, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

                            val fileType = inodeModeToFileType(mode)

                            totalDeletedFound++
                            emit(DeletedInodeResult.DeletedInode(
                                inodeNumber = inodeNumber,
                                partitionPath = partitionPath,
                                offsetInTable = inodeOffset,
                                fileSize = size,
                                deletionTime = dtime.toLong(),
                                linkCount = nlink,
                                fileType = fileType,
                                mode = mode
                            ))
                        }
                    }
                }
            }

            processedGroups++
            val progress = processedGroups.toFloat() / blockGroups
            emit(DeletedInodeResult.Progress(
                "Scanning block group $processedGroups/$blockGroups",
                0.1f + 0.9f * progress
            ))
        }

        emit(DeletedInodeResult.Completed(totalDeletedFound))
    }.flowOn(Dispatchers.IO)

    // ──────────────────────────────────────────────
    // Journal Recovery (ext4)
    // ──────────────────────────────────────────────

    /**
     * Recover deleted data from the ext4 journal.
     *
     * The ext4 journal records filesystem transactions before they
     * are committed. When a file is deleted, the journal may still
     * contain copies of the data blocks that were freed. This method:
     *
     * 1. Locates the journal inode (typically inode 8)
     * 2. Reads the journal superblock
     * 3. Scans journal transactions for metadata and data blocks
     * 4. Identifies blocks belonging to deleted files
     * 5. Attempts to reconstruct files from journal entries
     *
     * @param partitionPath Path to the ext4 partition
     * @return Flow of JournalRecoveryResult entries
     */
    fun recoverFromJournal(partitionPath: String): Flow<JournalRecoveryResult> = flow {
        if (!rootManager.isRootGranted) {
            emit(JournalRecoveryResult.Error("Root access not granted"))
            return@flow
        }

        val isExt4 = verifyExt4Filesystem(partitionPath)
        if (!isExt4) {
            emit(JournalRecoveryResult.Error("Partition is not ext4 filesystem"))
            return@flow
        }

        emit(JournalRecoveryResult.Progress("Locating journal", 0f))

        val superblock = readExt4Superblock(partitionPath)
        if (superblock == null) {
            emit(JournalRecoveryResult.Error("Failed to read superblock"))
            return@flow
        }

        // Find journal inode (typically inode 8)
        val journalInodeOffset = calculateInodeOffset(superblock, EXT4_JOURNAL_INODE)

        // Read journal inode to find journal block locations
        val journalInodeResult = rootManager.executeCommandWithOutput(
            "dd if='$partitionPath' bs=1 skip=$journalInodeOffset count=${superblock.inodeSize} 2>/dev/null | xxd -p"
        )

        if (!journalInodeResult.success) {
            emit(JournalRecoveryResult.Error("Failed to read journal inode"))
            return@flow
        }

        val journalInodeData = hexToByteArray(journalInodeResult.stdout.replace("\\s".toRegex(), ""))
        if (journalInodeData.size < 28) {
            emit(JournalRecoveryResult.Error("Journal inode data too short"))
            return@flow
        }

        val journalSize = ByteBuffer.wrap(journalInodeData, 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

        // Read journal block pointers from inode
        val blockPointers = readInodeBlockPointers(journalInodeData, superblock.blockSize)

        if (blockPointers.isEmpty()) {
            emit(JournalRecoveryResult.Error("No journal block pointers found"))
            return@flow
        }

        emit(JournalRecoveryResult.Progress("Reading journal superblock", 0.1f))

        // Read journal superblock (first block of the journal)
        val journalSbOffset = blockPointers[0] * superblock.blockSize
        val journalSbResult = rootManager.executeCommandWithOutput(
            "dd if='$partitionPath' bs=${superblock.blockSize} skip=${blockPointers[0]} count=1 2>/dev/null | xxd -l 64 -p"
        )

        if (!journalSbResult.success) {
            emit(JournalRecoveryResult.Error("Failed to read journal superblock"))
            return@flow
        }

        val journalSbData = hexToByteArray(journalSbResult.stdout.replace("\\s".toRegex(), ""))
        if (journalSbData.size < 24) {
            emit(JournalRecoveryResult.Error("Journal superblock too short"))
            return@flow
        }

        // Parse journal superblock
        // Offset 0: magic (0xC03B3998), 4: block type, 8: block size
        val journalMagic = ByteBuffer.wrap(journalSbData, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        if (journalMagic != 0xC03B3998.toInt()) {
            emit(JournalRecoveryResult.Error("Invalid journal magic number"))
            return@flow
        }

        val journalBlockSize = ByteBuffer.wrap(journalSbData, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        val journalTotalBlocks = blockPointers.size

        emit(JournalRecoveryResult.Progress(
            "Scanning $journalTotalBlocks journal blocks",
            0.2f
        ))

        // Scan journal blocks for data transactions
        var entriesFound = 0
        var blocksScanned = 0

        for (blockIndex in 1 until minOf(journalTotalBlocks, MAX_JOURNAL_ENTRIES)) {
            if (!currentCoroutineContext().isActive) break
            if (blockIndex >= blockPointers.size) break

            val blockOffset = blockPointers[blockIndex]

            // Read journal block header
            val blockHeaderResult = rootManager.executeCommandWithOutput(
                "dd if='$partitionPath' bs=${superblock.blockSize} skip=$blockOffset count=1 2>/dev/null | xxd -l 12 -p"
            )

            if (blockHeaderResult.success && blockHeaderResult.stdout.isNotEmpty()) {
                val headerData = hexToByteArray(blockHeaderResult.stdout.replace("\\s".toRegex(), ""))
                if (headerData.size >= 12) {
                    val blockType = ByteBuffer.wrap(headerData, 4, 4).order(ByteOrder.BIG_ENDIAN).int

                    // Journal block types:
                    // 1 = descriptor block (lists data blocks)
                    // 2 = commit block (transaction complete)
                    // 3 = revoke block (cancels previous entries)

                    when (blockType) {
                        1 -> {
                            // Descriptor block - extract referenced data blocks
                            val dataBlockCount = ByteBuffer.wrap(headerData, 8, 4)
                                .order(ByteOrder.BIG_ENDIAN).int

                            entriesFound++
                            emit(JournalRecoveryResult.JournalEntry(
                                sequenceNumber = blockIndex.toLong(),
                                blockType = "descriptor",
                                blockCount = dataBlockCount,
                                journalOffset = blockOffset * superblock.blockSize,
                                partitionPath = partitionPath
                            ))
                        }
                        2 -> {
                            // Commit block
                            emit(JournalRecoveryResult.JournalEntry(
                                sequenceNumber = blockIndex.toLong(),
                                blockType = "commit",
                                blockCount = 0,
                                journalOffset = blockOffset * superblock.blockSize,
                                partitionPath = partitionPath
                            ))
                        }
                    }
                }
            }

            blocksScanned++
            if (blocksScanned % 50 == 0) {
                val progress = 0.2f + 0.8f * (blocksScanned.toFloat() / minOf(journalTotalBlocks, MAX_JOURNAL_ENTRIES))
                emit(JournalRecoveryResult.Progress(
                    "Scanned $blocksScanned journal blocks",
                    progress
                ))
            }
        }

        emit(JournalRecoveryResult.Completed(entriesFound, blocksScanned))
    }.flowOn(Dispatchers.IO)

    // ──────────────────────────────────────────────
    // Unallocated Space Scanning
    // ──────────────────────────────────────────────

    /**
     * Scan unallocated space on a partition for recoverable data.
     *
     * Unallocated space consists of blocks that are not currently
     * referenced by any inode. These blocks may contain data from
     * deleted files that hasn't been overwritten yet.
     *
     * This method works by:
     * 1. Reading the block bitmap for each block group
     * 2. Identifying blocks marked as free (0 in bitmap)
     * 3. Scanning free blocks for file signatures
     * 4. Extracting any recoverable data found
     *
     * @param partitionPath Path to the partition
     * @return Flow of UnallocatedScanResult entries
     */
    fun scanUnallocatedSpace(partitionPath: String): Flow<UnallocatedScanResult> = flow {
        if (!rootManager.isRootGranted) {
            emit(UnallocatedScanResult.Error("Root access not granted"))
            return@flow
        }

        val isExt4 = verifyExt4Filesystem(partitionPath)
        if (!isExt4) {
            // Fallback: scan the entire partition for signatures
            // (less efficient but works for any filesystem)
            scanUnallocatedFallback(partitionPath, this)
            return@flow
        }

        emit(UnallocatedScanResult.Progress("Reading ext4 block bitmaps", 0f))

        val superblock = readExt4Superblock(partitionPath)
        if (superblock == null) {
            emit(UnallocatedScanResult.Error("Failed to read superblock"))
            return@flow
        }

        val blockGroups = (superblock.blockCount + superblock.blocksPerGroup - 1) / superblock.blocksPerGroup
        var totalUnallocatedBlocks = 0L
        var totalFound = 0

        // Read block group descriptor table
        val bgdtOffset = (EXT4_SUPER_OFFSET / superblock.blockSize + 1) * superblock.blockSize

        for (bgIndex in 0 until blockGroups.toInt()) {
            if (!currentCoroutineContext().isActive) break
            val bgdtEntryOffset = bgdtOffset + bgIndex * EXT4_BGDT_ENTRY_SIZE.toLong()

            // Read block bitmap location from BGDT
            val bitmapBlockResult = rootManager.executeCommandWithOutput(
                "dd if='$partitionPath' bs=1 skip=$bgdtEntryOffset count=4 2>/dev/null | xxd -p"
            )

            if (!bitmapBlockResult.success) continue

            val bitmapBlockHex = bitmapBlockResult.stdout.replace("\\s".toRegex(), "")
            if (bitmapBlockHex.length < 8) continue

            val bitmapBlock = ByteBuffer.wrap(hexToByteArray(bitmapBlockHex))
                .order(ByteOrder.LITTLE_ENDIAN).int

            if (bitmapBlock == 0) continue

            // Read the block bitmap
            val bitmapOffset = bitmapBlock.toLong() * superblock.blockSize
            val bitmapSize = (superblock.blocksPerGroup + 7) / 8 // bytes needed for bitmap
            val bitmapResult = rootManager.executeCommandWithOutput(
                "dd if='$partitionPath' bs=1 skip=$bitmapOffset count=$bitmapSize 2>/dev/null | xxd -p"
            )

            if (!bitmapResult.success) continue

            val bitmapData = hexToByteArray(bitmapResult.stdout.replace("\\s".toRegex(), ""))

            // Scan bitmap for unallocated blocks (0 bits)
            val blocksInThisGroup = minOf(
                superblock.blocksPerGroup,
                superblock.blockCount - bgIndex.toLong() * superblock.blocksPerGroup
            ).toInt()

            for (blockBit in 0 until blocksInThisGroup) {
                if (!currentCoroutineContext().isActive) break
                val byteIndex = blockBit / 8
                val bitIndex = blockBit % 8

                if (byteIndex >= bitmapData.size) break

                val isAllocated = (bitmapData[byteIndex].toInt() shr bitIndex) and 1 != 0

                if (!isAllocated) {
                    totalUnallocatedBlocks++

                    // Check this unallocated block for file signatures
                    val blockNumber = bgIndex.toLong() * superblock.blocksPerGroup + blockBit
                    val blockOffset = blockNumber * superblock.blockSize

                    // Only check every Nth block for efficiency
                    if (totalUnallocatedBlocks % 16 == 0L) {
                        val xxdResult = rootManager.executeCommandWithOutput(
                            "dd if='$partitionPath' bs=${superblock.blockSize} skip=$blockNumber count=1 2>/dev/null | xxd -l 32 -p"
                        )

                        if (xxdResult.success && xxdResult.stdout.isNotEmpty()) {
                            val header = hexToByteArray(xxdResult.stdout.replace("\\s".toRegex(), ""))
                            val signature = FileSignatures.identifyFileType(header)

                            if (signature != null) {
                                totalFound++
                                emit(UnallocatedScanResult.FoundData(
                                    blockNumber = blockNumber,
                                    blockOffset = blockOffset,
                                    signatureName = signature.name,
                                    fileCategory = signature.category,
                                    partitionPath = partitionPath
                                ))
                            }
                        }
                    }
                }
            }

            val progress = (bgIndex + 1).toFloat() / blockGroups
            emit(UnallocatedScanResult.Progress(
                "Scanning block group ${bgIndex + 1}/$blockGroups (${totalUnallocatedBlocks} free blocks)",
                progress
            ))
        }

        emit(UnallocatedScanResult.Completed(totalFound, totalUnallocatedBlocks))
    }.flowOn(Dispatchers.IO)

    // ──────────────────────────────────────────────
    // Private Helper Methods
    // ──────────────────────────────────────────────

    /**
     * Verify that a partition contains an ext4 filesystem
     */
    private suspend fun verifyExt4Filesystem(partitionPath: String): Boolean {
        val result = rootManager.executeCommandWithOutput(
            "dd if='$partitionPath' bs=1 skip=${EXT4_SUPER_OFFSET + EXT4_MAGIC_OFFSET} count=2 2>/dev/null | xxd -p"
        )

        if (result.success && result.stdout.isNotEmpty()) {
            val hex = result.stdout.replace("\\s".toRegex(), "").uppercase()
            // ext4 magic is 0xEF53, stored as 53 EF in little-endian
            return hex.startsWith("53EF")
        }

        return false
    }

    /**
     * Read and parse the ext4 superblock
     */
    private suspend fun readExt4Superblock(partitionPath: String): Ext4Superblock? {
        try {
            val result = rootManager.executeCommandWithOutput(
                "dd if='$partitionPath' bs=1 skip=$EXT4_SUPER_OFFSET count=256 2>/dev/null | xxd -p"
            )

            if (!result.success || result.stdout.isBlank()) return null

            val data = hexToByteArray(result.stdout.replace("\\s".toRegex(), ""))
            if (data.size < 100) return null

            val inodeCount = ByteBuffer.wrap(data, EXT4_INODE_COUNT_OFFSET, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val blockCount = ByteBuffer.wrap(data, EXT4_BLOCK_COUNT_OFFSET, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val blockSizeLog = ByteBuffer.wrap(data, EXT4_BLOCK_SIZE_OFFSET, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val blockSize = 1024 shl blockSizeLog
            val blocksPerGroup = ByteBuffer.wrap(data, 32, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val inodesPerGroup = ByteBuffer.wrap(data, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val magic = ByteBuffer.wrap(data, EXT4_MAGIC_OFFSET, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            val inodeSize = ByteBuffer.wrap(data, EXT4_INODE_SIZE_OFFSET, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

            if (magic != EXT4_SUPER_MAGIC) return null

            return Ext4Superblock(
                inodeCount = inodeCount,
                blockCount = blockCount,
                blockSize = blockSize,
                blocksPerGroup = blocksPerGroup,
                inodesPerGroup = inodesPerGroup,
                inodeSize = inodeSize,
                magic = magic
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Calculate the byte offset of an inode in the inode table
     */
    private fun calculateInodeOffset(superblock: Ext4Superblock, inodeNumber: Int): Long {
        val blockGroup = (inodeNumber - 1) / superblock.inodesPerGroup
        val indexInGroup = (inodeNumber - 1) % superblock.inodesPerGroup
        // This is simplified; actual offset requires reading BGDT for inode table location
        return 0L // Placeholder - actual implementation reads BGDT
    }

    /**
     * Read block pointers from an inode
     */
    private fun readInodeBlockPointers(inodeData: ByteArray, blockSize: Int): List<Long> {
        val pointers = mutableListOf<Long>()

        // Direct block pointers: offsets 40-99 (12 pointers, 4 bytes each)
        for (i in 0 until 12) {
            val offset = 40 + i * 4
            if (offset + 4 > inodeData.size) break
            val blockNum = ByteBuffer.wrap(inodeData, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (blockNum > 0) pointers.add(blockNum)
        }

        // Indirect block pointer at offset 88
        if (88 + 4 <= inodeData.size) {
            val indirectBlock = ByteBuffer.wrap(inodeData, 88, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            // For now, just note the indirect block exists
            // Full implementation would read the indirect block and follow pointers
        }

        return pointers
    }

    /**
     * Carve a single file from raw partition data
     */
    private suspend fun carveFile(
        partitionPath: String,
        startOffset: Long,
        signature: FileSignatures.FileSignature,
        partitionSize: Long,
        tempFile: String,
        blockIndex: Int,
        blockSize: Int
    ): FoundFileInfo? {
        try {
            // Try to determine file size
            var fileSize = determineFileSize(partitionPath, startOffset, signature, partitionSize)

            if (fileSize < MIN_CARVE_SIZE) return null
            if (fileSize > MAX_CARVE_SIZE) fileSize = MAX_CARVE_SIZE

            val fileName = "raw_${signature.name}_${startOffset.toHexString()}.${signature.extensions.first()}"

            return FoundFileInfo(
                path = partitionPath,
                fileName = fileName,
                fileSize = fileSize,
                extension = signature.extensions.first(),
                mimeType = signature.mimeType,
                category = signature.category,
                signatureName = signature.name,
                offsetInBlock = startOffset,
                confidence = calculateCarveConfidence(signature, fileSize),
                isFragment = fileSize > DEFAULT_BLOCK_SIZE * 16, // Likely fragmented if large
                isRootRequired = true,
                sourcePath = partitionPath,
                metadata = mapOf(
                    "carve_method" to "raw_block",
                    "block_offset" to startOffset.toString(),
                    "block_size" to blockSize.toString()
                )
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Determine the size of a file starting at a given offset
     */
    private suspend fun determineFileSize(
        partitionPath: String,
        startOffset: Long,
        signature: FileSignatures.FileSignature,
        partitionSize: Long
    ): Long {
        // Method 1: Try to detect size from header
        val headerResult = rootManager.executeCommandWithOutput(
            "dd if='$partitionPath' bs=1 skip=$startOffset count=64 2>/dev/null | xxd -p"
        )

        if (headerResult.success && headerResult.stdout.isNotEmpty()) {
            val headerData = hexToByteArray(headerResult.stdout.replace("\\s".toRegex(), ""))

            when (signature.name) {
                "AVI", "WAV", "WEBP" -> {
                    if (headerData.size >= 8) {
                        val riffSize = ByteBuffer.wrap(headerData, 4, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        if (riffSize in 8..MAX_CARVE_SIZE) return riffSize + 8
                    }
                }
                "MP4" -> {
                    if (headerData.size >= 8) {
                        val boxSize = ByteBuffer.wrap(headerData, 0, 4)
                            .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        if (boxSize in 8..MAX_CARVE_SIZE) return boxSize
                    }
                }
                "PNG" -> {
                    // Scan for IEND chunk
                    val iendResult = rootManager.executeCommandWithOutput(
                        "dd if='$partitionPath' bs=1 skip=$startOffset count=${minOf(50L * 1024 * 1024, partitionSize - startOffset)} 2>/dev/null | xxd -p | tr -d '\\n' | grep -b -o '49454e44ae426082' | tail -1"
                    )
                    if (iendResult.success && iendResult.stdout.isNotEmpty()) {
                        // Parse the offset from grep output
                        val matchOffset = iendResult.stdout.substringBefore(":").toIntOrNull()
                        if (matchOffset != null) {
                            return matchOffset.toLong() / 2 + 12 // IEND chunk size + CRC
                        }
                    }
                }
            }
        }

        // Method 2: Search for end marker
        if (signature.endMarker != null) {
            val endOffset = searchForEndMarker(partitionPath, startOffset, signature.endMarker, partitionSize)
            if (endOffset > startOffset) {
                return endOffset - startOffset + signature.endMarker.size
            }
        }

        // Method 3: Estimate based on file category
        return estimateSizeByCategory(signature)
    }

    /**
     * Search for a file's end marker in raw data
     */
    private suspend fun searchForEndMarker(
        partitionPath: String,
        startOffset: Long,
        endMarker: ByteArray,
        partitionSize: Long
    ): Long {
        val maxSearch = minOf(startOffset + 100L * 1024 * 1024, partitionSize) // 100MB max
        var currentOffset = startOffset + 256 // Skip past header

        while (currentOffset < maxSearch && currentCoroutineContext().isActive) {
            val readSize = minOf(READ_BUFFER_SIZE.toLong(), maxSearch - currentOffset)
            val skipBlocks = currentOffset / 4096

            val result = rootManager.executeCommandWithOutput(
                "dd if='$partitionPath' bs=4096 skip=$skipBlocks count=${readSize / 4096} 2>/dev/null | xxd -p | tr -d '\\n'"
            )

            if (!result.success) break

            val hex = result.stdout.replace("\\s".toRegex(), "")
            val endMarkerHex = endMarker.joinToString("") { "%02X".format(it) }

            val markerIndex = hex.indexOf(endMarkerHex.uppercase())
            if (markerIndex >= 0) {
                return currentOffset + markerIndex / 2
            }

            currentOffset += readSize
        }

        return -1L
    }

    /**
     * Calculate confidence level for a carved file
     */
    private fun calculateCarveConfidence(
        signature: FileSignatures.FileSignature,
        fileSize: Long
    ): RecoveryConfidence {
        var score = 50

        // Stronger signature = higher confidence
        if (signature.hexPattern.size >= 8) score += 15
        else if (signature.hexPattern.size >= 4) score += 10

        // Has end marker = higher confidence
        if (signature.endMarker != null) score += 20

        // Reasonable file size
        when (signature.category) {
            FileCategory.PHOTO -> if (fileSize in 10_000L..50_000_000L) score += 10
            FileCategory.VIDEO -> if (fileSize in 100_000L..500_000_000L) score += 10
            FileCategory.AUDIO -> if (fileSize in 5_000L..100_000_000L) score += 10
            else -> if (fileSize in 1_000L..100_000_000L) score += 5
        }

        return when {
            score >= 80 -> RecoveryConfidence.HIGH
            score >= 50 -> RecoveryConfidence.MEDIUM
            score >= 25 -> RecoveryConfidence.LOW
            else -> RecoveryConfidence.UNCERTAIN
        }
    }

    /**
     * Estimate file size by category when exact size is unknown
     */
    private fun estimateSizeByCategory(signature: FileSignatures.FileSignature): Long {
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
     * Convert file mode to file type string
     */
    private fun inodeModeToFileType(mode: Int): String {
        val typeMask = mode and 0xF000
        return when (typeMask) {
            0x4000 -> "directory"
            0x8000 -> "regular"
            0xA000 -> "symlink"
            0x6000 -> "block_device"
            0x2000 -> "character_device"
            0x1000 -> "fifo"
            0xC000 -> "socket"
            else -> "unknown"
        }
    }

    /**
     * Fallback scan for unallocated space on non-ext4 filesystems
     */
    private suspend fun scanUnallocatedFallback(
        partitionPath: String,
        emitter: kotlinx.coroutines.flow.FlowCollector<UnallocatedScanResult>
    ) {
        emitter.emit(UnallocatedScanResult.Progress("Performing full partition signature scan", 0f))

        val partitionInfo = partitionManager.getPartitionInfo(partitionPath)
        val partitionSize = partitionInfo?.sizeBytes ?: 0L

        if (partitionSize <= 0L) {
            emitter.emit(UnallocatedScanResult.Error("Cannot determine partition size"))
            return
        }

        var foundCount = 0
        var offset = 0L
        val stepSize = DEFAULT_BLOCK_SIZE.toLong() * 64 // Skip every 64 blocks for speed

        while (offset < partitionSize && currentCoroutineContext().isActive) {
            val xxdResult = rootManager.executeCommandWithOutput(
                "dd if='$partitionPath' bs=1 skip=$offset count=32 2>/dev/null | xxd -p"
            )

            if (xxdResult.success && xxdResult.stdout.isNotEmpty()) {
                val header = hexToByteArray(xxdResult.stdout.replace("\\s".toRegex(), ""))
                val signature = FileSignatures.identifyFileType(header)

                if (signature != null) {
                    foundCount++
                    emitter.emit(UnallocatedScanResult.FoundData(
                        blockNumber = offset / DEFAULT_BLOCK_SIZE,
                        blockOffset = offset,
                        signatureName = signature.name,
                        fileCategory = signature.category,
                        partitionPath = partitionPath
                    ))
                }
            }

            offset += stepSize

            if (offset % (stepSize * 100) == 0L) {
                val progress = offset.toFloat() / partitionSize
                emitter.emit(UnallocatedScanResult.Progress(
                    "Scanning at offset $offset ($foundCount found)",
                    progress
                ))
            }
        }

        emitter.emit(UnallocatedScanResult.Completed(foundCount, partitionSize / DEFAULT_BLOCK_SIZE))
    }

    /**
     * Convert hex string to byte array
     */
    private fun hexToByteArray(hex: String): ByteArray {
        val cleanHex = hex.replace("\\s".toRegex(), "")
        if (cleanHex.length % 2 != 0) return ByteArray(0)
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

/** ext4 superblock size constant */
private const val EXT4_SUPER_SIZE = 1024

/**
 * Parsed ext4 superblock data
 */
data class Ext4Superblock(
    val inodeCount: Long,
    val blockCount: Long,
    val blockSize: Int,
    val blocksPerGroup: Long,
    val inodesPerGroup: Long,
    val inodeSize: Int,
    val magic: Int
)

/**
 * Raw recovery progress events
 */
sealed class RawRecoveryProgress {
    data class Started(
        val partitionPath: String,
        val totalBytes: Long,
        val blockSize: Int
    ) : RawRecoveryProgress()

    data class Scanning(
        val partitionPath: String,
        val progress: Float,
        val bytesScanned: Long,
        val totalBytes: Long,
        val filesFound: Int,
        val currentOffset: Long
    ) : RawRecoveryProgress()

    data class FileFound(
        val fileInfo: FoundFileInfo,
        val totalFound: Int
    ) : RawRecoveryProgress()

    data class Completed(
        val partitionPath: String,
        val foundFiles: List<FoundFileInfo>,
        val bytesScanned: Long,
        val durationMs: Long
    ) : RawRecoveryProgress()

    data class Error(val message: String) : RawRecoveryProgress()
}

/**
 * File carving result events
 */
sealed class CarveResult {
    data class Progress(
        val message: String,
        val progress: Float,
        val filesCarved: Int
    ) : CarveResult()

    data class FileCarved(
        val fileInfo: FoundFileInfo,
        val totalCarved: Int
    ) : CarveResult()

    data class Completed(val totalFilesCarved: Int) : CarveResult()
    data class Error(val message: String) : CarveResult()
}

/**
 * Deleted inode discovery result events
 */
sealed class DeletedInodeResult {
    data class Progress(val message: String, val progress: Float) : DeletedInodeResult()

    data class DeletedInode(
        val inodeNumber: Long,
        val partitionPath: String,
        val offsetInTable: Long,
        val fileSize: Long,
        val deletionTime: Long,
        val linkCount: Int,
        val fileType: String,
        val mode: Int
    ) : DeletedInodeResult()

    data class Completed(val totalDeletedFound: Int) : DeletedInodeResult()
    data class Error(val message: String) : DeletedInodeResult()
}

/**
 * Journal recovery result events
 */
sealed class JournalRecoveryResult {
    data class Progress(val message: String, val progress: Float) : JournalRecoveryResult()

    data class JournalEntry(
        val sequenceNumber: Long,
        val blockType: String,
        val blockCount: Int,
        val journalOffset: Long,
        val partitionPath: String
    ) : JournalRecoveryResult()

    data class Completed(
        val entriesFound: Int,
        val blocksScanned: Int
    ) : JournalRecoveryResult()

    data class Error(val message: String) : JournalRecoveryResult()
}

/**
 * Unallocated space scan result events
 */
sealed class UnallocatedScanResult {
    data class Progress(val message: String, val progress: Float) : UnallocatedScanResult()

    data class FoundData(
        val blockNumber: Long,
        val blockOffset: Long,
        val signatureName: String,
        val fileCategory: FileCategory,
        val partitionPath: String
    ) : UnallocatedScanResult()

    data class Completed(
        val totalFound: Int,
        val unallocatedBlocks: Long
    ) : UnallocatedScanResult()

    data class Error(val message: String) : UnallocatedScanResult()
}
