package com.ultimaterecovery.pro.utils.ai

import android.content.Context
import android.webkit.MimeTypeMap
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.engine.recovery.FoundFileInfo
import com.ultimaterecovery.pro.engine.recovery.IntegrityStatus
import com.ultimaterecovery.pro.engine.recovery.RecoveryConfidence
import com.ultimaterecovery.pro.engine.signatures.FileSignatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI-powered recovery assistant for Ultimate Recovery Pro.
 *
 * Provides intelligent analysis and recommendations for recovered files
 * using heuristic-based rule engines. The architecture is designed to
 * support future ML model integration through the [AIModelProvider]
 * interface without requiring changes to the public API.
 *
 * ## Capabilities
 * - **File classification**: Automatically categorize files by type using
 *   file signatures (magic numbers) and MIME type analysis.
 * - **Corruption detection**: Identify damaged/corrupted files by validating
 *   structural integrity markers and content heuristics.
 * - **Repair suggestions**: Recommend repair strategies based on file type
 *   and corruption pattern.
 * - **Smart naming**: Generate meaningful names for recovered files whose
 *   original names are unknown.
 * - **Duplicate detection**: Find duplicate files using MD5/SHA-256 hash
 *   comparison.
 * - **Storage analysis**: Provide insights on storage usage, largest files,
 *   and cleanup opportunities.
 * - **Recovery priority scoring**: Rank files by recoverability based on
 *   type, size, condition, and user value heuristics.
 * - **Scan recommendations**: Suggest scan strategies based on quick scan
 *   results and device conditions.
 * - **File grouping**: Organize recovered files into logical groups for
 *   easier browsing and batch operations.
 *
 * ## Future ML Integration
 * The [AIModelProvider] interface defines the contract for plugging in
 * machine-learning models (e.g. TensorFlow Lite) for enhanced classification,
 * corruption prediction, and user-behavior learning. Currently only the
 * heuristic-based [HeuristicModelProvider] is implemented.
 *
 * @see AIModelProvider
 * @see HeuristicModelProvider
 * @see FileClassification
 * @see CorruptionReport
 * @see RepairSuggestion
 * @see RecoveryPriority
 * @see StorageInsight
 */
@Singleton
class AIRecoveryAssistant @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "AIRecoveryAssistant"

        /** Minimum header bytes to read for signature-based classification. */
        private const val HEADER_READ_SIZE = 64

        /** Minimum file size to consider for duplicate detection (skip tiny files). */
        private const val MIN_DUPLICATE_CHECK_SIZE = 1024L // 1 KB

        /** Buffer size for hash computation. */
        private const val HASH_BUFFER_SIZE = 8192

        /** Priority score boundaries. */
        private const val PRIORITY_CRITICAL = 90
        private const val PRIORITY_HIGH = 70
        private const val PRIORITY_MEDIUM = 40
        private const val PRIORITY_LOW = 20

        /** Threshold for recommending Deep Scan (files found in Quick Scan). */
        private const val DEEP_SCAN_RECOMMENDATION_THRESHOLD = 10

        /** Category weights for priority scoring (higher = more valuable). */
        private val CATEGORY_WEIGHTS = mapOf(
            FileCategory.PHOTO to 85,
            FileCategory.VIDEO to 80,
            FileCategory.DOCUMENT to 75,
            FileCategory.AUDIO to 65,
            FileCategory.APK to 50,
            FileCategory.ARCHIVE to 40,
            FileCategory.OTHER to 25
        )

        /** Known junk/cache directory names. */
        private val JUNK_DIR_NAMES = setOf(
            "cache", "Cache", "CACHE",
            "thumbnails", "Thumbnails", "THUMBNAILS",
            "temp", "tmp", "Temp", "TMP",
            ".cache", ".tmp",
            "backup_cache", "recovery_cache"
        )

        /** Known junk file extensions. */
        private val JUNK_EXTENSIONS = setOf(
            "tmp", "temp", "log", "bak", "old", "swp", "swn",
            "part", "download", "crdownload"
        )

        /** Common date patterns found in file names for smart naming. */
        private val DATE_PATTERNS = listOf(
            Regex("""(\d{4})[_-](\d{2})[_-](\d{2})"""),
            Regex("""(\d{2})[_-](\d{2})[_-](\d{4})"""),
            Regex("""(\d{4})(\d{2})(\d{2})"""),
            Regex("""IMG[_-](\d{8})"""),
            Regex("""VID[_-](\d{8})"""),
            Regex("""Screenshot[_-](\d{4})[_-](\d{2})[_-](\d{2})""")
        )
    }

    // ──────────────────────────────────────────────
    // AI Model Provider (pluggable for future ML)
    // ──────────────────────────────────────────────

    /**
     * Interface for AI model providers.
     *
     * Implementations can range from simple heuristic engines to
     * full TensorFlow Lite models. The assistant delegates classification
     * and analysis tasks to the current provider.
     */
    interface AIModelProvider {
        /** Classify a file given its metadata and header bytes. */
        fun classify(header: ByteArray, extension: String, mimeType: String): FileClassification

        /** Predict corruption likelihood for a file (0.0 = clean, 1.0 = fully corrupted). */
        fun predictCorruption(header: ByteArray, fileSize: Long, expectedSize: Long?): Float

        /** Rank files by user-value priority. */
        fun rankByPriority(files: List<FoundFileInfo>): List<Pair<FoundFileInfo, Int>>
    }

    /**
     * Heuristic-based model provider using file signatures, rule engines,
     * and statistical analysis. No ML model required.
     */
    inner class HeuristicModelProvider : AIModelProvider {

        override fun classify(
            header: ByteArray,
            extension: String,
            mimeType: String
        ): FileClassification {
            // Try signature-based identification first (most reliable)
            val signature = FileSignatures.identifyFileType(header)
            if (signature != null) {
                return FileClassification(
                    category = signature.category,
                    formatName = signature.name,
                    extensions = signature.extensions,
                    mimeType = signature.mimeType,
                    confidence = RecoveryConfidence.HIGH,
                    method = ClassificationMethod.SIGNATURE
                )
            }

            // Fallback: MIME type analysis
            if (mimeType.isNotBlank() && mimeType != "application/octet-stream") {
                val category = categoryFromMimeType(mimeType)
                return FileClassification(
                    category = category,
                    formatName = mimeType.substringAfter("/").uppercase(),
                    extensions = listOf(extension),
                    mimeType = mimeType,
                    confidence = RecoveryConfidence.MEDIUM,
                    method = ClassificationMethod.MIME_TYPE
                )
            }

            // Fallback: extension-based classification
            val category = categoryFromExtension(extension)
            return FileClassification(
                category = category,
                formatName = extension.uppercase(),
                extensions = listOf(extension),
                mimeType = mimeType.ifBlank { "application/octet-stream" },
                confidence = RecoveryConfidence.LOW,
                method = ClassificationMethod.EXTENSION
            )
        }

        override fun predictCorruption(
            header: ByteArray,
            fileSize: Long,
            expectedSize: Long?
        ): Float {
            var corruptionScore = 0f

            // Heuristic 1: Size mismatch
            if (expectedSize != null && expectedSize > 0) {
                val sizeRatio = fileSize.toFloat() / expectedSize.toFloat()
                when {
                    sizeRatio < 0.3f -> corruptionScore += 0.5f
                    sizeRatio < 0.7f -> corruptionScore += 0.3f
                    sizeRatio > 1.5f -> corruptionScore += 0.2f
                }
            }

            // Heuristic 2: Empty or zero-filled header
            if (header.isNotEmpty()) {
                val zeroCount = header.count { it == 0.toByte() }
                val zeroRatio = zeroCount.toFloat() / header.size
                if (zeroRatio > 0.9f) corruptionScore += 0.4f
            }

            // Heuristic 3: No recognizable signature
            val signature = FileSignatures.identifyFileType(header)
            if (signature == null && fileSize > MIN_DUPLICATE_CHECK_SIZE) {
                corruptionScore += 0.3f
            }

            return corruptionScore.coerceIn(0f, 1f)
        }

        override fun rankByPriority(files: List<FoundFileInfo>): List<Pair<FoundFileInfo, Int>> {
            return files.map { file ->
                val score = calculatePriorityScore(file)
                file to score
            }.sortedByDescending { it.second }
        }
    }

    /** Current AI model provider — replace with ML implementation when available. */
    @Volatile
    private var modelProvider: AIModelProvider = HeuristicModelProvider()

    /**
     * Sets a custom [AIModelProvider] for enhanced AI operations.
     *
     * Use this to plug in a TensorFlow Lite model or any other
     * ML backend without modifying the assistant's public API.
     *
     * @param provider The new model provider.
     */
    fun setModelProvider(provider: AIModelProvider) {
        modelProvider = provider
    }

    // ──────────────────────────────────────────────
    // File Classification
    // ──────────────────────────────────────────────

    /**
     * Classifies a file by reading its header bytes and analyzing
     * signatures, MIME type, and extension.
     *
     * @param file The file to classify.
     * @return [FileClassification] with category, format, and confidence.
     */
    suspend fun classifyFile(file: File): FileClassification = withContext(Dispatchers.IO) {
        val header = readFileHeader(file)
        val extension = file.extension.lowercase()
        val mimeType = getMimeType(file)

        modelProvider.classify(header, extension, mimeType)
    }

    /**
     * Classifies a file given its [FoundFileInfo] (no disk access needed
     * if metadata is already available).
     */
    fun classifyFile(info: FoundFileInfo): FileClassification {
        val category = info.category
        return FileClassification(
            category = category,
            formatName = info.signatureName ?: info.extension.uppercase(),
            extensions = listOf(info.extension),
            mimeType = info.mimeType,
            confidence = info.confidence,
            method = if (info.signatureName != null)
                ClassificationMethod.SIGNATURE
            else
                ClassificationMethod.MIME_TYPE
        )
    }

    // ──────────────────────────────────────────────
    // Corruption Detection
    // ──────────────────────────────────────────────

    /**
     * Detects corruption in a file by examining its header, structure,
     * and content patterns.
     *
     * @param file The file to check.
     * @return [CorruptionReport] with corruption status and details.
     */
    suspend fun detectCorruption(file: File): CorruptionReport = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) {
            return@withContext CorruptionReport(
                file = file,
                status = IntegrityStatus.UNREADABLE,
                corruptionScore = 1.0f,
                issues = listOf("File does not exist or is not readable"),
                repairPossible = false
            )
        }

        val header = readFileHeader(file)
        val fileSize = file.length()
        val issues = mutableListOf<String>()
        var corruptionScore = 0f

        // Check 1: Header signature validation
        val signature = FileSignatures.identifyFileType(header)
        if (signature == null && fileSize > MIN_DUPLICATE_CHECK_SIZE) {
            issues.add("No recognizable file signature found in header")
            corruptionScore += 0.3f
        } else if (signature != null) {
            // Validate end marker if the format defines one
            if (signature.endMarker != null && fileSize > signature.maxFileSize && signature.maxFileSize > 0) {
                issues.add("File exceeds maximum expected size for ${signature.name}")
                corruptionScore += 0.15f
            }
        }

        // Check 2: Extension vs signature mismatch
        val extension = file.extension.lowercase()
        if (signature != null && extension !in signature.extensions && extension.isNotBlank()) {
            issues.add("Extension (.$extension) does not match detected format (${signature.name})")
            corruptionScore += 0.2f
        }

        // Check 3: Zero-filled or suspiciously uniform content
        if (header.isNotEmpty()) {
            val distinctBytes = header.distinct().size
            if (distinctBytes <= 2 && fileSize > 1024) {
                issues.add("Header contains very few distinct byte values (possibly zero-filled)")
                corruptionScore += 0.4f
            }
        }

        // Check 4: Truncated file detection for known formats
        if (signature?.endMarker != null) {
            val hasEndMarker = checkEndMarker(file, signature.endMarker)
            if (!hasEndMarker) {
                issues.add("End-of-file marker missing (file may be truncated)")
                corruptionScore += 0.25f
            }
        }

        // Check 5: Extremely small file for its type
        if (signature != null && fileSize < 100 && signature.category != FileCategory.DOCUMENT) {
            issues.add("File is unusually small for a ${signature.name} file")
            corruptionScore += 0.15f
        }

        // Use model provider for additional prediction
        val modelScore = modelProvider.predictCorruption(header, fileSize, null)
        corruptionScore = ((corruptionScore + modelScore) / 2f).coerceIn(0f, 1f)

        val status = when {
            corruptionScore < 0.15f -> IntegrityStatus.INTACT
            corruptionScore < 0.4f -> IntegrityStatus.PARTIAL
            corruptionScore < 0.7f -> IntegrityStatus.CORRUPTED
            else -> IntegrityStatus.UNREADABLE
        }

        CorruptionReport(
            file = file,
            status = status,
            corruptionScore = corruptionScore,
            issues = issues,
            repairPossible = status == IntegrityStatus.PARTIAL || status == IntegrityStatus.CORRUPTED
        )
    }

    // ──────────────────────────────────────────────
    // Repair Suggestions
    // ──────────────────────────────────────────────

    /**
     * Suggests repair strategies for a corrupted or damaged file.
     *
     * @param file The damaged file.
     * @return [RepairSuggestion] with recommended actions.
     */
    suspend fun suggestRepair(file: File): RepairSuggestion = withContext(Dispatchers.IO) {
        val corruptionReport = detectCorruption(file)
        val header = readFileHeader(file)
        val signature = FileSignatures.identifyFileType(header)
        val extension = file.extension.lowercase()

        val strategies = mutableListOf<RepairStrategy>()
        var estimatedSuccessRate = 0f
        var complexity = RepairComplexity.LOW

        when (signature?.category ?: categoryFromExtension(extension)) {
            FileCategory.PHOTO -> {
                if (corruptionReport.issues.any { it.contains("truncated", ignoreCase = true) }) {
                    strategies.add(RepairStrategy(
                        name = "Truncated Image Recovery",
                        description = "Attempt to recover visible portion of truncated image by reconstructing image headers",
                        action = RepairAction.RECONSTRUCT_HEADER,
                        estimatedSuccessRate = 0.7f
                    ))
                }
                if (corruptionReport.issues.any { it.contains("marker", ignoreCase = true) }) {
                    strategies.add(RepairStrategy(
                        name = "Marker Rebuild",
                        description = "Rebuild missing JPEG/PNG markers from remaining data blocks",
                        action = RepairAction.REBUILD_MARKERS,
                        estimatedSuccessRate = 0.6f
                    ))
                }
                strategies.add(RepairStrategy(
                    name = "Partial Extraction",
                    description = "Extract whatever image data is still readable, potentially recovering a partial image",
                    action = RepairAction.EXTRACT_PARTIAL,
                    estimatedSuccessRate = 0.8f
                ))
                estimatedSuccessRate = 0.6f
            }

            FileCategory.VIDEO -> {
                strategies.add(RepairStrategy(
                    name = "Index Rebuild",
                    description = "Rebuild the video index/moov atom to allow playback of recoverable frames",
                    action = RepairAction.REBUILD_INDEX,
                    estimatedSuccessRate = 0.5f
                ))
                strategies.add(RepairStrategy(
                    name = "Frame Extraction",
                    description = "Extract individual playable video frames from the file",
                    action = RepairAction.EXTRACT_PARTIAL,
                    estimatedSuccessRate = 0.65f
                ))
                estimatedSuccessRate = 0.5f
                complexity = RepairComplexity.MEDIUM
            }

            FileCategory.DOCUMENT -> {
                if (extension in setOf("docx", "xlsx", "pptx")) {
                    strategies.add(RepairStrategy(
                        name = "ZIP Structure Repair",
                        description = "Repair the ZIP container structure; Office Open XML files are ZIP archives",
                        action = RepairAction.RECONSTRUCT_HEADER,
                        estimatedSuccessRate = 0.7f
                    ))
                }
                if (extension == "pdf") {
                    strategies.add(RepairStrategy(
                        name = "PDF Cross-Reference Rebuild",
                        description = "Rebuild the PDF xref table to restore document navigation",
                        action = RepairAction.REBUILD_INDEX,
                        estimatedSuccessRate = 0.6f
                    ))
                }
                strategies.add(RepairStrategy(
                    name = "Text Extraction",
                    description = "Extract raw text content from the document, discarding formatting",
                    action = RepairAction.EXTRACT_PARTIAL,
                    estimatedSuccessRate = 0.75f
                ))
                estimatedSuccessRate = 0.65f
            }

            FileCategory.AUDIO -> {
                strategies.add(RepairStrategy(
                    name = "Audio Header Reconstruction",
                    description = "Reconstruct audio file header with correct codec parameters",
                    action = RepairAction.RECONSTRUCT_HEADER,
                    estimatedSuccessRate = 0.55f
                ))
                strategies.add(RepairStrategy(
                    name = "Stream Recovery",
                    description = "Recover playable audio stream by skipping corrupted frames",
                    action = RepairAction.EXTRACT_PARTIAL,
                    estimatedSuccessRate = 0.6f
                ))
                estimatedSuccessRate = 0.55f
            }

            FileCategory.ARCHIVE -> {
                strategies.add(RepairStrategy(
                    name = "Archive Partial Extraction",
                    description = "Extract undamaged entries from the archive, skipping corrupted ones",
                    action = RepairAction.EXTRACT_PARTIAL,
                    estimatedSuccessRate = 0.5f
                ))
                estimatedSuccessRate = 0.4f
                complexity = RepairComplexity.HIGH
            }

            else -> {
                strategies.add(RepairStrategy(
                    name = "Raw Data Extraction",
                    description = "Extract raw binary data from the file for manual analysis",
                    action = RepairAction.EXTRACT_PARTIAL,
                    estimatedSuccessRate = 0.3f
                ))
                estimatedSuccessRate = 0.3f
                complexity = RepairComplexity.HIGH
            }
        }

        // Adjust success rate based on corruption severity
        estimatedSuccessRate *= (1f - corruptionReport.corruptionScore * 0.5f)

        RepairSuggestion(
            file = file,
            corruptionReport = corruptionReport,
            strategies = strategies,
            estimatedSuccessRate = estimatedSuccessRate.coerceIn(0f, 1f),
            complexity = complexity,
            recommendedAction = strategies.maxByOrNull { it.estimatedSuccessRate }?.action
                ?: RepairAction.EXTRACT_PARTIAL
        )
    }

    // ──────────────────────────────────────────────
    // Duplicate Detection
    // ──────────────────────────────────────────────

    /**
     * Finds duplicate files among the given list using hash comparison.
     *
     * Files are first grouped by size, then by MD5 hash for efficient
     * comparison. Only files above [MIN_DUPLICATE_CHECK_SIZE] are
     * checked to avoid wasting time on tiny files.
     *
     * @param files List of files to check for duplicates.
     * @return List of [DuplicateGroup]s, each containing a set of
     *         duplicate files.
     */
    suspend fun findDuplicates(files: List<File>): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val eligibleFiles = files.filter { it.exists() && it.length() >= MIN_DUPLICATE_CHECK_SIZE }

        // Phase 1: Group by file size (fast pre-filter)
        val sizeGroups = eligibleFiles.groupBy { it.length() }
            .filter { it.value.size > 1 }

        // Phase 2: Within each size group, compute MD5 hashes
        val duplicateGroups = mutableListOf<DuplicateGroup>()

        for ((size, sameSizeFiles) in sizeGroups) {
            val hashGroups = sameSizeFiles.groupBy { computeMD5(it) }

            for ((hash, hashMatchFiles) in hashGroups) {
                if (hashMatchFiles.size > 1) {
                    // Phase 3: For candidates, confirm with SHA-256
                    val confirmedGroups = hashMatchFiles
                        .groupBy { computeSHA256(it) }
                        .filter { it.value.size > 1 }

                    for ((sha256Hash, duplicateFiles) in confirmedGroups) {
                        duplicateGroups.add(DuplicateGroup(
                            files = duplicateFiles,
                            fileSize = size,
                            md5Hash = hash,
                            sha256Hash = sha256Hash,
                            wastedSpace = size * (duplicateFiles.size - 1)
                        ))
                    }
                }
            }
        }

        duplicateGroups.sortedByDescending { it.wastedSpace }
    }

    // ──────────────────────────────────────────────
    // Storage Analysis
    // ──────────────────────────────────────────────

    /**
     * Performs a comprehensive storage analysis with AI-generated insights.
     *
     * @return [StorageInsight] with breakdown, largest files, duplicates, and recommendations.
     */
    suspend fun analyzeStorage(): StorageInsight = withContext(Dispatchers.IO) {
        val storageRoot = android.os.Environment.getExternalStorageDirectory()
        if (!storageRoot.exists() || !storageRoot.canRead()) {
            return@withContext StorageInsight.empty()
        }

        val categoryBreakdown = mutableMapOf<FileCategory, Long>()
        FileCategory.values().forEach { categoryBreakdown[it] = 0L }

        val allFiles = mutableListOf<File>()
        val largestFiles = mutableListOf<Pair<File, Long>>()
        val junkFiles = mutableListOf<File>()
        var totalSize = 0L

        // Walk storage tree
        walkDirectory(storageRoot, allFiles, categoryBreakdown, largestFiles, junkFiles)

        totalSize = allFiles.sumOf { it.length() }

        // Find duplicates among largest files first (most impactful)
        val duplicateGroups = findDuplicates(
            allFiles.sortedByDescending { it.length() }.take(500)
        )

        val totalDuplicateWaste = duplicateGroups.sumOf { it.wastedSpace }
        val totalJunkSize = junkFiles.sumOf { it.length() }

        // Generate recommendations
        val recommendations = generateStorageRecommendations(
            categoryBreakdown, totalDuplicateWaste, totalJunkSize, totalSize, duplicateGroups.size
        )

        StorageInsight(
            totalStorageUsed = totalSize,
            categoryBreakdown = categoryBreakdown,
            largestFiles = largestFiles.sortedByDescending { it.second }.take(20)
                .map { FileInfo(it.first, it.second) },
            duplicateGroups = duplicateGroups,
            junkFiles = junkFiles.map { FileInfo(it, it.length()) },
            totalDuplicateWaste = totalDuplicateWaste,
            totalJunkSize = totalJunkSize,
            recommendations = recommendations
        )
    }

    // ──────────────────────────────────────────────
    // Recovery Priority Scoring
    // ──────────────────────────────────────────────

    /**
     * Scores a file's recovery priority based on type, size, condition,
     * and user-value heuristics.
     *
     * Score range: 0–100 (higher = more important to recover).
     *
     * @param file The file to score.
     * @return [RecoveryPriority] with score and tier.
     */
    suspend fun scoreRecoveryPriority(file: File): RecoveryPriority = withContext(Dispatchers.IO) {
        val info = FoundFileInfo(
            path = file.absolutePath,
            fileName = file.name,
            fileSize = file.length(),
            extension = file.extension.lowercase(),
            mimeType = getMimeType(file),
            category = categoryFromExtension(file.extension.lowercase()),
            lastModified = file.lastModified()
        )
        scoreRecoveryPriority(info)
    }

    /**
     * Scores a file's recovery priority from its [FoundFileInfo].
     */
    fun scoreRecoveryPriority(file: FoundFileInfo): RecoveryPriority {
        val score = calculatePriorityScore(file)
        val tier = when {
            score >= PRIORITY_CRITICAL -> PriorityTier.CRITICAL
            score >= PRIORITY_HIGH -> PriorityTier.HIGH
            score >= PRIORITY_MEDIUM -> PriorityTier.MEDIUM
            score >= PRIORITY_LOW -> PriorityTier.LOW
            else -> PriorityTier.MINIMAL
        }

        return RecoveryPriority(
            file = file,
            score = score,
            tier = tier,
            factors = buildPriorityFactors(file)
        )
    }

    // ──────────────────────────────────────────────
    // Scan Recommendations
    // ──────────────────────────────────────────────

    /**
     * Generates scan recommendations based on the results of a Quick Scan.
     *
     * If the Quick Scan found few results, a Deep Scan is recommended.
     * If specific file categories are underrepresented, targeted scans
     * are suggested.
     *
     * @param quickScanResults Results from a completed Quick Scan.
     * @param hasRoot Whether root access is available.
     * @return [ScanRecommendation] with suggested next steps.
     */
    fun getScanRecommendation(
        quickScanResults: List<FoundFileInfo>? = null,
        hasRoot: Boolean = false
    ): ScanRecommendation {
        val recommendations = mutableListOf<ScanTypeRecommendation>()

        if (quickScanResults == null || quickScanResults.isEmpty()) {
            // No Quick Scan done yet or no results
            recommendations.add(ScanTypeRecommendation(
                scanType = ScanType.DEEP,
                reason = "Quick Scan found no files. Deep Scan reads raw disk blocks and can find files whose directory entries have been overwritten.",
                priority = RecommendationPriority.HIGH,
                estimatedDuration = "5–30 minutes",
                requiresRoot = true
            ))
            recommendations.add(ScanTypeRecommendation(
                scanType = ScanType.SIGNATURE,
                reason = "Signature Scan searches for file headers in unallocated space without requiring directory entries.",
                priority = RecommendationPriority.MEDIUM,
                estimatedDuration = "10–45 minutes",
                requiresRoot = false
            ))
            return ScanRecommendation(
                recommendedScanType = ScanType.DEEP,
                recommendations = recommendations,
                confidence = RecoveryConfidence.MEDIUM
            )
        }

        // Analyze Quick Scan results
        val totalFound = quickScanResults.size
        val categoryCounts = quickScanResults.groupingBy { it.category }.eachCount()

        // Check if Deep Scan would be beneficial
        if (totalFound < DEEP_SCAN_RECOMMENDATION_THRESHOLD) {
            recommendations.add(ScanTypeRecommendation(
                scanType = ScanType.DEEP,
                reason = "Quick Scan only found $totalFound files. Deep Scan can discover files in deleted directory entries and unallocated space.",
                priority = RecommendationPriority.HIGH,
                estimatedDuration = "5–30 minutes",
                requiresRoot = true
            ))
        }

        // Check for missing categories
        val missingCategories = FileCategory.values().filter {
            it != FileCategory.OTHER && it !in categoryCounts
        }
        if (missingCategories.isNotEmpty()) {
            val missingNames = missingCategories.map { it.name.lowercase() }
            recommendations.add(ScanTypeRecommendation(
                scanType = ScanType.SIGNATURE,
                reason = "No ${missingNames.joinToString(", ")} files were found. A Signature Scan specifically targets these file types.",
                priority = RecommendationPriority.MEDIUM,
                estimatedDuration = "10–45 minutes",
                requiresRoot = false
            ))
        }

        // If root is available, suggest partition scan for thorough recovery
        if (hasRoot) {
            recommendations.add(ScanTypeRecommendation(
                scanType = ScanType.PARTITION,
                reason = "Root access is available. Partition Scan reads raw disk sectors for maximum recovery potential.",
                priority = RecommendationPriority.MEDIUM,
                estimatedDuration = "30–120 minutes",
                requiresRoot = true
            ))
        }

        // If many files found but mostly OTHER category, suggest targeted scan
        val otherRatio = (categoryCounts[FileCategory.OTHER] ?: 0).toFloat() / totalFound.coerceAtLeast(1)
        if (otherRatio > 0.5f) {
            recommendations.add(ScanTypeRecommendation(
                scanType = ScanType.SIGNATURE,
                reason = "Over 50% of found files are uncategorized. A Signature Scan can properly identify these files.",
                priority = RecommendationPriority.MEDIUM,
                estimatedDuration = "10–45 minutes",
                requiresRoot = false
            ))
        }

        val bestScanType = when {
            totalFound < DEEP_SCAN_RECOMMENDATION_THRESHOLD && hasRoot -> ScanType.DEEP
            missingCategories.isNotEmpty() -> ScanType.SIGNATURE
            hasRoot -> ScanType.PARTITION
            else -> ScanType.QUICK
        }

        return ScanRecommendation(
            recommendedScanType = bestScanType,
            recommendations = recommendations,
            confidence = if (totalFound > 0) RecoveryConfidence.HIGH else RecoveryConfidence.MEDIUM
        )
    }

    // ──────────────────────────────────────────────
    // File Grouping
    // ──────────────────────────────────────────────

    /**
     * Groups recovered files into logical categories for organized browsing.
     *
     * Files are grouped by:
     * 1. Category (Photo, Video, Document, etc.)
     * 2. Date (today, this week, this month, older)
     * 3. Size (small, medium, large)
     *
     * @param files List of files to group.
     * @return Map of group names to their file lists.
     */
    fun groupFiles(files: List<FoundFileInfo>): Map<String, List<FoundFileInfo>> {
        val groups = mutableMapOf<String, MutableList<FoundFileInfo>>()

        // Group by category
        for (file in files) {
            val categoryLabel = when (file.category) {
                FileCategory.PHOTO -> "Photos"
                FileCategory.VIDEO -> "Videos"
                FileCategory.AUDIO -> "Audio"
                FileCategory.DOCUMENT -> "Documents"
                FileCategory.ARCHIVE -> "Archives"
                FileCategory.APK -> "APKs"
                FileCategory.OTHER -> "Other Files"
            }
            groups.getOrPut(categoryLabel) { mutableListOf() }.add(file)
        }

        return groups
    }

    /**
     * Groups files by time period for chronological browsing.
     *
     * @param files List of files to group.
     * @return Map of time period labels to their file lists.
     */
    fun groupFilesByDate(files: List<FoundFileInfo>): Map<String, List<FoundFileInfo>> {
        val now = System.currentTimeMillis()
        val oneDayMs = 86_400_000L
        val oneWeekMs = oneDayMs * 7
        val oneMonthMs = oneDayMs * 30

        val groups = mutableMapOf<String, MutableList<FoundFileInfo>>()

        for (file in files) {
            val age = now - file.lastModified
            val label = when {
                file.lastModified <= 0L -> "Unknown Date"
                age <= oneDayMs -> "Today"
                age <= oneWeekMs -> "This Week"
                age <= oneMonthMs -> "This Month"
                age <= oneMonthMs * 3 -> "Last 3 Months"
                age <= oneMonthMs * 6 -> "Last 6 Months"
                age <= oneMonthMs * 12 -> "Last Year"
                else -> "Older"
            }
            groups.getOrPut(label) { mutableListOf() }.add(file)
        }

        return groups
    }

    /**
     * Groups files by size range for storage analysis.
     *
     * @param files List of files to group.
     * @return Map of size range labels to their file lists.
     */
    fun groupFilesBySize(files: List<FoundFileInfo>): Map<String, List<FoundFileInfo>> {
        val groups = mutableMapOf<String, MutableList<FoundFileInfo>>()

        for (file in files) {
            val label = when {
                file.fileSize < 1024 -> "Tiny (< 1 KB)"
                file.fileSize < 1024 * 1024 -> "Small (< 1 MB)"
                file.fileSize < 10 * 1024 * 1024 -> "Medium (1–10 MB)"
                file.fileSize < 100 * 1024 * 1024 -> "Large (10–100 MB)"
                file.fileSize < 1024L * 1024 * 1024 -> "Very Large (100 MB–1 GB)"
                else -> "Huge (> 1 GB)"
            }
            groups.getOrPut(label) { mutableListOf() }.add(file)
        }

        return groups
    }

    // ──────────────────────────────────────────────
    // Smart File Naming
    // ──────────────────────────────────────────────

    /**
     * Generates a smart name for a recovered file whose original name
     * is unknown or meaningless (e.g., a numeric string from raw carving).
     *
     * The name is derived from:
     * 1. File type/format (from signature analysis)
     * 2. Date information extracted from the file or its path
     * 3. Sequence number to ensure uniqueness
     *
     * @param file The file to name.
     * @return A meaningful file name with appropriate extension.
     */
    suspend fun generateSmartName(file: File): String = withContext(Dispatchers.IO) {
        val header = readFileHeader(file)
        val signature = FileSignatures.identifyFileType(header)
        val extension = signature?.extensions?.firstOrNull() ?: file.extension.lowercase().ifBlank { "bin" }

        // Try to extract date from the file's metadata
        val lastModified = file.lastModified()
        val dateStr = if (lastModified > 0) {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            sdf.format(Date(lastModified))
        } else {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            sdf.format(Date())
        }

        // Build the base name
        val typePrefix = when (signature?.category ?: categoryFromExtension(file.extension.lowercase())) {
            FileCategory.PHOTO -> "IMG"
            FileCategory.VIDEO -> "VID"
            FileCategory.AUDIO -> "AUD"
            FileCategory.DOCUMENT -> "DOC"
            FileCategory.ARCHIVE -> "ARC"
            FileCategory.APK -> "APK"
            FileCategory.OTHER -> "FILE"
        }

        val formatHint = signature?.name?.lowercase()?.takeIf {
            it !in setOf("txt", "bin")
        }?.let { "_$it" } ?: ""

        // Ensure uniqueness with a hash suffix
        val hashSuffix = computeMD5(file).take(6)

        "${typePrefix}${formatHint}_${dateStr}_$hashSuffix.$extension"
    }

    // ──────────────────────────────────────────────
    // Private Helpers
    // ──────────────────────────────────────────────

    /**
     * Reads the first [HEADER_READ_SIZE] bytes from a file.
     */
    private fun readFileHeader(file: File): ByteArray {
        if (!file.exists() || !file.canRead()) return ByteArray(0)

        return try {
            val header = ByteArray(HEADER_READ_SIZE)
            FileInputStream(file).use { input ->
                val bytesRead = input.read(header)
                if (bytesRead < header.size) {
                    header.copyOf(bytesRead.coerceAtLeast(0))
                } else {
                    header
                }
            }
        } catch (_: IOException) {
            ByteArray(0)
        }
    }

    /**
     * Checks if a file ends with the specified end marker bytes.
     */
    private fun checkEndMarker(file: File, endMarker: ByteArray): Boolean {
        if (!file.exists() || file.length() < endMarker.size) return false

        return try {
            val tail = ByteArray(endMarker.size)
            FileInputStream(file).use { input ->
                input.skip(file.length() - endMarker.size)
                val bytesRead = input.read(tail)
                if (bytesRead == endMarker.size) {
                    tail.contentEquals(endMarker)
                } else false
            }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Computes MD5 hash of a file.
     */
    private fun computeMD5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
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
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns the MIME type for a file based on its extension.
     */
    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    /**
     * Maps a MIME type to a [FileCategory].
     */
    private fun categoryFromMimeType(mimeType: String): FileCategory = when {
        mimeType.startsWith("image/") -> FileCategory.PHOTO
        mimeType.startsWith("video/") -> FileCategory.VIDEO
        mimeType.startsWith("audio/") -> FileCategory.AUDIO
        mimeType.startsWith("text/") -> FileCategory.DOCUMENT
        mimeType == "application/pdf" -> FileCategory.DOCUMENT
        mimeType.contains("word") || mimeType.contains("document") -> FileCategory.DOCUMENT
        mimeType.contains("sheet") || mimeType.contains("excel") -> FileCategory.DOCUMENT
        mimeType.contains("presentation") || mimeType.contains("powerpoint") -> FileCategory.DOCUMENT
        mimeType == "application/zip" || mimeType.contains("rar") ||
            mimeType.contains("7z") || mimeType.contains("tar") -> FileCategory.ARCHIVE
        mimeType == "application/vnd.android.package-archive" -> FileCategory.APK
        else -> FileCategory.OTHER
    }

    /**
     * Maps a file extension to a [FileCategory].
     */
    private fun categoryFromExtension(extension: String): FileCategory = when {
        extension in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif", "raw", "heic", "heif", "cr2", "nef", "arw", "dng", "orf", "rw2", "raf", "srw") -> FileCategory.PHOTO
        extension in setOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "3gp", "3g2", "webm", "m4v", "mpeg", "mpg", "m2v") -> FileCategory.VIDEO
        extension in setOf("mp3", "wav", "flac", "aac", "ogg", "oga", "opus", "wma", "m4a", "amr") -> FileCategory.AUDIO
        extension in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "odp", "html", "htm", "xml", "json") -> FileCategory.DOCUMENT
        extension in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") -> FileCategory.ARCHIVE
        extension == "apk" -> FileCategory.APK
        else -> FileCategory.OTHER
    }

    /**
     * Calculates a priority score (0–100) for a file based on multiple factors.
     */
    private fun calculatePriorityScore(file: FoundFileInfo): Int {
        var score = 0

        // Factor 1: Category weight (0–85 points)
        score += CATEGORY_WEIGHTS[file.category] ?: 25

        // Factor 2: File size bonus (larger = more valuable, up to 10 points)
        val sizeBonus = when {
            file.fileSize >= 100 * 1024 * 1024 -> 10 // > 100 MB
            file.fileSize >= 10 * 1024 * 1024 -> 8   // > 10 MB
            file.fileSize >= 1024 * 1024 -> 5         // > 1 MB
            file.fileSize >= 100 * 1024 -> 3           // > 100 KB
            else -> 1
        }
        score += sizeBonus

        // Factor 3: Confidence adjustment (up to ±5 points)
        val confidenceAdjust = when (file.confidence) {
            RecoveryConfidence.HIGH -> 5
            RecoveryConfidence.MEDIUM -> 0
            RecoveryConfidence.LOW -> -3
            RecoveryConfidence.UNCERTAIN -> -5
        }
        score += confidenceAdjust

        // Factor 4: Recency bonus (recently modified files are more valuable)
        val age = System.currentTimeMillis() - file.lastModified
        val recencyBonus = when {
            file.lastModified <= 0L -> 0
            age <= 86_400_000L -> 5       // < 1 day
            age <= 86_400_000L * 7 -> 3   // < 1 week
            age <= 86_400_000L * 30 -> 1  // < 1 month
            else -> 0
        }
        score += recencyBonus

        return score.coerceIn(0, 100)
    }

    /**
     * Builds a list of human-readable factors that contributed to the priority score.
     */
    private fun buildPriorityFactors(file: FoundFileInfo): List<String> {
        val factors = mutableListOf<String>()

        factors.add("Category: ${file.category.name} (weight ${CATEGORY_WEIGHTS[file.category] ?: 25})")

        val sizeDesc = when {
            file.fileSize >= 100 * 1024 * 1024 -> "Very large file"
            file.fileSize >= 10 * 1024 * 1024 -> "Large file"
            file.fileSize >= 1024 * 1024 -> "Medium file"
            else -> "Small file"
        }
        factors.add(sizeDesc)

        factors.add("Recovery confidence: ${file.confidence.name}")

        if (file.lastModified > 0L) {
            val age = System.currentTimeMillis() - file.lastModified
            val recency = when {
                age <= 86_400_000L -> "Very recent"
                age <= 86_400_000L * 7 -> "Recent"
                age <= 86_400_000L * 30 -> "Moderately recent"
                else -> "Old"
            }
            factors.add("Age: $recency")
        }

        return factors
    }

    /**
     * Recursively walks a directory tree, accumulating file data.
     */
    private fun walkDirectory(
        dir: File,
        allFiles: MutableList<File>,
        categoryBreakdown: MutableMap<FileCategory, Long>,
        largestFiles: MutableList<Pair<File, Long>>,
        junkFiles: MutableList<File>
    ) {
        dir.listFiles()?.forEach { file ->
            try {
                if (file.isDirectory) {
                    // Check if this is a junk directory
                    if (file.name in JUNK_DIR_NAMES) {
                        collectFilesRecursive(file, junkFiles)
                    } else {
                        walkDirectory(file, allFiles, categoryBreakdown, largestFiles, junkFiles)
                    }
                } else {
                    allFiles.add(file)

                    val category = categoryFromExtension(file.extension.lowercase())
                    categoryBreakdown[category] = (categoryBreakdown[category] ?: 0L) + file.length()

                    // Track large files (top candidates)
                    if (file.length() > 10 * 1024 * 1024) { // > 10 MB
                        largestFiles.add(file to file.length())
                    }

                    // Check for junk files
                    if (file.extension.lowercase() in JUNK_EXTENSIONS) {
                        junkFiles.add(file)
                    }
                }
            } catch (_: Exception) {
                // Skip inaccessible files
            }
        }
    }

    /**
     * Recursively collects all files under a directory into the target list.
     */
    private fun collectFilesRecursive(dir: File, target: MutableList<File>) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                collectFilesRecursive(file, target)
            } else {
                target.add(file)
            }
        }
    }

    /**
     * Generates human-readable storage cleanup recommendations.
     */
    private fun generateStorageRecommendations(
        categoryBreakdown: Map<FileCategory, Long>,
        totalDuplicateWaste: Long,
        totalJunkSize: Long,
        totalSize: Long,
        duplicateGroupCount: Int
    ): List<StorageRecommendation> {
        val recommendations = mutableListOf<StorageRecommendation>()

        // Duplicate files recommendation
        if (duplicateGroupCount > 0) {
            recommendations.add(StorageRecommendation(
                type = RecommendationType.CLEAN_DUPLICATES,
                title = "Remove Duplicate Files",
                description = "Found $duplicateGroupCount group(s) of duplicate files wasting ${formatSize(totalDuplicateWaste)}",
                potentialSavings = totalDuplicateWaste,
                priority = if (totalDuplicateWaste > 500 * 1024 * 1024)
                    RecommendationPriority.HIGH else RecommendationPriority.MEDIUM
            ))
        }

        // Junk files recommendation
        if (totalJunkSize > 0) {
            recommendations.add(StorageRecommendation(
                type = RecommendationType.CLEAN_JUNK,
                title = "Clean Junk Files",
                description = "Found ${formatSize(totalJunkSize)} in temporary and cache files",
                potentialSavings = totalJunkSize,
                priority = if (totalJunkSize > 100 * 1024 * 1024)
                    RecommendationPriority.HIGH else RecommendationPriority.MEDIUM
            ))
        }

        // Large videos recommendation
        val videoSize = categoryBreakdown[FileCategory.VIDEO] ?: 0L
        if (videoSize > totalSize * 0.4) {
            recommendations.add(StorageRecommendation(
                type = RecommendationType.REVIEW_LARGE_FILES,
                title = "Review Large Videos",
                description = "Videos occupy ${formatSize(videoSize)} (${(videoSize.toFloat() / totalSize * 100).toInt()}% of storage). Consider backing up or removing old videos.",
                potentialSavings = videoSize / 2,
                priority = RecommendationPriority.MEDIUM
            ))
        }

        return recommendations.sortedByDescending { it.potentialSavings }
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
 * Result of file classification.
 *
 * @property category    Classified file category.
 * @property formatName  Detected format name (e.g. "JPEG", "MP4").
 * @property extensions  Possible extensions for this format.
 * @property mimeType    Detected or inferred MIME type.
 * @property confidence  Classification confidence level.
 * @property method      Classification method used.
 */
data class FileClassification(
    val category: FileCategory,
    val formatName: String,
    val extensions: List<String>,
    val mimeType: String,
    val confidence: RecoveryConfidence,
    val method: ClassificationMethod
)

/** Method used to classify a file. */
enum class ClassificationMethod {
    SIGNATURE,   // Magic number / file signature
    MIME_TYPE,   // MIME type from ContentResolver
    EXTENSION,   // File extension heuristic
    ML_MODEL     // Machine learning model (future)
}

/**
 * Report on a file's corruption status.
 *
 * @property file            The examined file.
 * @property status          Integrity status.
 * @property corruptionScore 0.0 (clean) to 1.0 (fully corrupted).
 * @property issues          List of detected issues.
 * @property repairPossible  Whether repair is possible.
 */
data class CorruptionReport(
    val file: File,
    val status: IntegrityStatus,
    val corruptionScore: Float,
    val issues: List<String>,
    val repairPossible: Boolean
)

/**
 * Repair suggestion for a damaged file.
 *
 * @property file               The damaged file.
 * @property corruptionReport   Detailed corruption analysis.
 * @property strategies         Available repair strategies.
 * @property estimatedSuccessRate Overall estimated success rate.
 * @property complexity         Repair complexity level.
 * @property recommendedAction  The best repair action to try first.
 */
data class RepairSuggestion(
    val file: File,
    val corruptionReport: CorruptionReport,
    val strategies: List<RepairStrategy>,
    val estimatedSuccessRate: Float,
    val complexity: RepairComplexity,
    val recommendedAction: RepairAction
)

/**
 * A single repair strategy.
 *
 * @property name                 Strategy name.
 * @property description          Human-readable description.
 * @property action               Type of repair action.
 * @property estimatedSuccessRate Estimated probability of success.
 */
data class RepairStrategy(
    val name: String,
    val description: String,
    val action: RepairAction,
    val estimatedSuccessRate: Float
)

/** Types of repair actions. */
enum class RepairAction {
    RECONSTRUCT_HEADER,  // Rebuild file header
    REBUILD_MARKERS,     // Rebuild format-specific markers
    REBUILD_INDEX,       // Rebuild file index/metadata
    EXTRACT_PARTIAL,     // Extract readable portion
    RAW_RECOVERY         // Raw binary data extraction
}

/** Repair complexity levels. */
enum class RepairComplexity {
    LOW, MEDIUM, HIGH
}

/**
 * A group of duplicate files.
 *
 * @property files       All files that are duplicates of each other.
 * @property fileSize    Size of each file (they are all identical).
 * @property md5Hash     MD5 hash shared by all files in the group.
 * @property sha256Hash  SHA-256 hash for confirmed duplicate status.
 * @property wastedSpace Total space wasted by keeping duplicates.
 */
data class DuplicateGroup(
    val files: List<File>,
    val fileSize: Long,
    val md5Hash: String,
    val sha256Hash: String,
    val wastedSpace: Long
)

/**
 * Comprehensive storage insight result.
 *
 * @property totalStorageUsed    Total bytes scanned.
 * @property categoryBreakdown   Size per file category.
 * @property largestFiles        Top largest files found.
 * @property duplicateGroups     Groups of duplicate files.
 * @property junkFiles           Temporary/cache/junk files found.
 * @property totalDuplicateWaste Bytes wasted by duplicates.
 * @property totalJunkSize       Bytes in junk files.
 * @property recommendations     Cleanup recommendations.
 */
data class StorageInsight(
    val totalStorageUsed: Long,
    val categoryBreakdown: Map<FileCategory, Long>,
    val largestFiles: List<FileInfo>,
    val duplicateGroups: List<DuplicateGroup>,
    val junkFiles: List<FileInfo>,
    val totalDuplicateWaste: Long,
    val totalJunkSize: Long,
    val recommendations: List<StorageRecommendation>
) {
    companion object {
        fun empty() = StorageInsight(
            totalStorageUsed = 0L,
            categoryBreakdown = emptyMap(),
            largestFiles = emptyList(),
            duplicateGroups = emptyList(),
            junkFiles = emptyList(),
            totalDuplicateWaste = 0L,
            totalJunkSize = 0L,
            recommendations = emptyList()
        )
    }
}

/**
 * Simplified file info for storage analysis results.
 *
 * @property file The underlying file.
 * @property size File size in bytes.
 */
data class FileInfo(
    val file: File,
    val size: Long
)

/**
 * A storage cleanup recommendation.
 *
 * @property type             Recommendation type.
 * @property title            Short title.
 * @property description      Detailed description.
 * @property potentialSavings Estimated bytes that could be freed.
 * @property priority         Importance of this recommendation.
 */
data class StorageRecommendation(
    val type: RecommendationType,
    val title: String,
    val description: String,
    val potentialSavings: Long,
    val priority: RecommendationPriority
)

/** Types of storage recommendations. */
enum class RecommendationType {
    CLEAN_DUPLICATES,
    CLEAN_JUNK,
    REVIEW_LARGE_FILES,
    MOVE_TO_CLOUD,
    CLEAR_CACHE
}

/** Recommendation priority levels. */
enum class RecommendationPriority {
    LOW, MEDIUM, HIGH
}

/**
 * Recovery priority for a file.
 *
 * @property file    The scored file.
 * @property score   Priority score (0–100, higher = more important).
 * @property tier    Priority tier derived from the score.
 * @property factors Factors that contributed to the score.
 */
data class RecoveryPriority(
    val file: FoundFileInfo,
    val score: Int,
    val tier: PriorityTier,
    val factors: List<String>
)

/** Priority tiers. */
enum class PriorityTier {
    CRITICAL,  // 90–100
    HIGH,      // 70–89
    MEDIUM,    // 40–69
    LOW,       // 20–39
    MINIMAL    // 0–19
}

/**
 * Scan recommendation result.
 *
 * @property recommendedScanType The most appropriate scan type to try next.
 * @property recommendations     All applicable scan recommendations.
 * @property confidence          Confidence level of the recommendation.
 */
data class ScanRecommendation(
    val recommendedScanType: ScanType,
    val recommendations: List<ScanTypeRecommendation>,
    val confidence: RecoveryConfidence
)

/**
 * A recommendation for a specific scan type.
 *
 * @property scanType          Recommended scan type.
 * @property reason            Why this scan type is recommended.
 * @property priority          How important this recommendation is.
 * @property estimatedDuration Human-readable estimated duration.
 * @property requiresRoot      Whether root access is needed.
 */
data class ScanTypeRecommendation(
    val scanType: ScanType,
    val reason: String,
    val priority: RecommendationPriority,
    val estimatedDuration: String,
    val requiresRoot: Boolean
)
