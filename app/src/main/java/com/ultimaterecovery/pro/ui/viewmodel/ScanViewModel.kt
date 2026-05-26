package com.ultimaterecovery.pro.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.data.repository.RecoveredFileRepository
import com.ultimaterecovery.pro.data.repository.Resource
import com.ultimaterecovery.pro.data.repository.ScanSessionRepository
import com.ultimaterecovery.pro.engine.recovery.FoundFileInfo
import com.ultimaterecovery.pro.engine.scanner.IScanEngine
import com.ultimaterecovery.pro.engine.scanner.ScanState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ──────────────────────────────────────────────
// UI State
// ──────────────────────────────────────────────

/**
 * UI state for the scan screen.
 *
 * Represents the full lifecycle of a scan operation from
 * idle through scanning/paused to completed/failed.
 */
data class ScanUiState(
    val scanState: ScanState = ScanState.Idle,
    val selectedScanType: ScanType = ScanType.QUICK,
    val selectedCategories: List<FileCategory> = emptyList(),
    val scanResults: List<FoundFileInfo> = emptyList(),
    val savedResults: List<RecoveredFileEntity> = emptyList(),
    val isSaving: Boolean = false,
    val error: String? = null
)

// ──────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────

/**
 * ViewModel for the scan screen.
 *
 * Manages the scan lifecycle (start, pause, resume, cancel),
 * tracks scan progress in real-time via the [IScanEngine],
 * and persists completed scan results through the
 * [ScanSessionRepository] and [RecoveredFileRepository].
 *
 * The UI observes [ScanUiState.scanState] which mirrors the
 * engine's [ScanState] sealed class — guaranteeing that the UI
 * always renders the correct scan phase without race conditions.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scanEngine: IScanEngine,
    private val scanSessionRepository: ScanSessionRepository,
    private val recoveredFileRepository: RecoveredFileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    /** Tracks the database ID of the current scan session, if any. */
    private var currentSessionId: Long? = null

    init {
        try {
            observeScanEngineState()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize ScanViewModel")
        } catch (_: Throwable) {
            // Safe default: no observation
        }
    }

    // ──────────────────────────────────────────
    // Scan type & category selection
    // ──────────────────────────────────────────

    /**
     * Sets the desired scan type for the next scan.
     */
    fun selectScanType(type: ScanType) {
        _uiState.value = _uiState.value.copy(selectedScanType = type)
    }

    /**
     * Toggles a [FileCategory] in the selection filter.
     *
     * An empty selection means "all categories" — the scan engine
     * will search for every file type.
     */
    fun toggleCategory(category: FileCategory) {
        val current = _uiState.value.selectedCategories.toMutableList()
        if (category in current) {
            current.remove(category)
        } else {
            current.add(category)
        }
        _uiState.value = _uiState.value.copy(selectedCategories = current)
    }

    // ──────────────────────────────────────────
    // Scan lifecycle
    // ──────────────────────────────────────────

    /**
     * Starts a scan of the given [type] for the specified [categories].
     *
     * Creates a [ScanSessionEntity] in the database before launching
     * the scan engine. The engine's [ScanState] is observed reactively.
     *
     * @param type       Quick, Deep, Signature, Raw, or Partition scan.
     * @param categories File categories to include (empty = all).
     */
    fun startScan(
        type: ScanType = _uiState.value.selectedScanType,
        categories: List<FileCategory> = _uiState.value.selectedCategories
    ) {
        if (_uiState.value.scanState is ScanState.Scanning) {
            Timber.w("Scan already in progress, ignoring startScan() call")
            return
        }

        // Reset previous results but immediately set Scanning state
        // to avoid a flash of Idle state in the UI
        _uiState.value = _uiState.value.copy(
            scanState = ScanState.Scanning(scanType = type),
            scanResults = emptyList(),
            error = null,
            selectedScanType = type,
            selectedCategories = categories
        )

        viewModelScope.launch {
            try {
            // Create a database session record
            try {
                val session = ScanSessionEntity(
                    startTime = System.currentTimeMillis(),
                    scanType = type,
                    status = ScanSessionEntity.ScanStatus.RUNNING,
                    storagePath = android.os.Environment.getExternalStorageDirectory().absolutePath,
                    isRootScan = type in listOf(ScanType.DEEP, ScanType.RAW, ScanType.PARTITION)
                )
                val createResult = scanSessionRepository.createSession(session)
                if (createResult is Resource.Success) {
                    currentSessionId = createResult.data
                }
            } catch (dbE: Exception) {
                Timber.e(dbE, "Failed to create scan session record")
                // Continue with scan even if session record fails
            } catch (_: Throwable) {
                // Database might be unavailable - continue anyway
            }

            val paths = listOf(
                android.os.Environment.getExternalStorageDirectory().absolutePath
            )

            val scanFlow = try {
                when (type) {
                    ScanType.QUICK     -> scanEngine.startQuickScan(paths, categories)
                    ScanType.DEEP      -> scanEngine.startDeepScan(paths, categories)
                    ScanType.SIGNATURE -> scanEngine.startSignatureScan(paths)
                    ScanType.RAW       -> scanEngine.startRawScan(paths.firstOrNull() ?: "/dev/block/sda1")
                    ScanType.PARTITION -> scanEngine.startPartitionScan()
                }
            } catch (e: Exception) {
                Timber.e(e, "Scan engine failed to start")
                _uiState.value = _uiState.value.copy(
                    scanState = ScanState.Failed(
                        error = e.message ?: "Failed to start scan engine",
                        scanType = type
                    ),
                    error = e.message
                )
                return@launch
            } catch (_: Throwable) {
                _uiState.value = _uiState.value.copy(
                    scanState = ScanState.Failed(
                        error = "Failed to start scan engine",
                        scanType = type
                    ),
                    error = "Failed to start scan engine"
                )
                return@launch
            }

            scanFlow
                .catch { e ->
                    Timber.e(e, "Scan flow error")
                    _uiState.value = _uiState.value.copy(
                        scanState = ScanState.Failed(
                            error = e.message ?: "Scan failed unexpectedly",
                            scanType = type
                        ),
                        error = e.message
                    )
                    try {
                        currentSessionId?.let { id ->
                            scanSessionRepository.cancelSession(id)
                        }
                    } catch (dbE: Exception) {
                        Timber.e(dbE, "Failed to cancel session after scan error")
                    } catch (_: Throwable) {}
                }
                .collect { state ->
                    _uiState.value = _uiState.value.copy(scanState = state)

                    when (state) {
                        is ScanState.Completed -> {
                            _uiState.value = _uiState.value.copy(
                                scanResults = state.results
                            )
                            try {
                                currentSessionId?.let { id ->
                                    scanSessionRepository.completeSession(id)
                                    scanSessionRepository.updateProgress(
                                        id,
                                        progress = 1f,
                                        sectorsScanned = 0L,
                                        totalFilesFound = state.totalFiles,
                                        totalSizeFound = state.totalSize
                                    )
                                }
                            } catch (dbE: Exception) {
                                Timber.e(dbE, "Failed to update completed session")
                            } catch (_: Throwable) {}
                        }
                        is ScanState.Failed -> {
                            _uiState.value = _uiState.value.copy(error = state.error)
                            try {
                                currentSessionId?.let { id ->
                                    scanSessionRepository.cancelSession(id)
                                }
                            } catch (dbE: Exception) {
                                Timber.e(dbE, "Failed to cancel failed session")
                            } catch (_: Throwable) {}
                        }
                        is ScanState.Scanning -> {
                            // State already updated above via copy(scanState = state)
                            Timber.d("Scan progress: ${(state.progress * 100).toInt()}%, found: ${state.filesFound}")
                        }
                        is ScanState.Paused -> {
                            Timber.d("Scan paused at ${state.progress}")
                        }
                        is ScanState.Cancelled -> {
                            Timber.d("Scan cancelled")
                        }
                        is ScanState.Idle -> {
                            // Engine reset to idle - unlikely during our scan but handle gracefully
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Scan operation failed")
                _uiState.value = _uiState.value.copy(
                    scanState = ScanState.Failed(
                        error = e.message ?: "Scan failed unexpectedly",
                        scanType = type
                    ),
                    error = e.message
                )
            } catch (_: Throwable) {
                _uiState.value = _uiState.value.copy(
                    scanState = ScanState.Failed(
                        error = "Scan failed unexpectedly",
                        scanType = type
                    ),
                    error = "Scan failed unexpectedly"
                )
            }
        }
    }

    /**
     * Pauses the currently running scan.
     */
    fun pauseScan() {
        try {
            scanEngine.pauseScan()
        } catch (e: Exception) {
            Timber.e(e, "Failed to pause scan")
        } catch (_: Throwable) {
            // ScanEngine might throw if it's in a bad state
        }
    }

    /**
     * Resumes a previously paused scan.
     */
    fun resumeScan() {
        try {
            scanEngine.resumeScan()
        } catch (e: Exception) {
            Timber.e(e, "Failed to resume scan")
        } catch (_: Throwable) {
            // ScanEngine might throw if it's in a bad state
        }
    }

    /**
     * Cancels the currently running or paused scan.
     */
    fun cancelScan() {
        try {
            scanEngine.cancelScan()
        } catch (e: Exception) {
            Timber.e(e, "Failed to cancel scan on engine")
        } catch (_: Throwable) {
            // Engine might be in a bad state
        }
        viewModelScope.launch {
            try {
                currentSessionId?.let { id ->
                    scanSessionRepository.cancelSession(id)
                }
                currentSessionId = null
            } catch (e: Exception) {
                Timber.e(e, "Failed to cancel scan session")
                currentSessionId = null
            } catch (_: Throwable) {
                currentSessionId = null
            }
        }
    }

    // ──────────────────────────────────────────
    // Results
    // ──────────────────────────────────────────

    /**
     * Returns the scan results as a snapshot list.
     *
     * The results are also available via [ScanUiState.scanResults].
     * This method exists for convenience when the UI needs to
     * fetch results on demand after the scan completes.
     */
    fun getScanResults(): List<FoundFileInfo> {
        return _uiState.value.scanResults
    }

    /**
     * Returns the current scan session ID.
     *
     * Returns -1 if no session is active.
     */
    fun getCurrentSessionId(): Long {
        return currentSessionId ?: -1L
    }

    /**
     * Saves the scan results to the database as [RecoveredFileEntity]
     * records linked to the current scan session.
     */
    fun saveScanResults() {
        val results = _uiState.value.scanResults
        if (results.isEmpty()) return

        viewModelScope.launch {
            try {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val sessionId = currentSessionId ?: 0L

            val entities = results.map { info ->
                RecoveredFileEntity(
                    filePath = info.path,
                    fileName = info.fileName,
                    fileSize = info.fileSize,
                    fileExtension = info.extension,
                    mimeType = info.mimeType,
                    recoveryDate = System.currentTimeMillis(),
                    originalDate = info.lastModified,
                    scanSessionId = sessionId,
                    category = info.category,
                    recoveryStatus = RecoveredFileEntity.RecoveryStatus.PENDING,
                    thumbnailPath = info.thumbnailPath,
                    isRootAccessed = info.isRootRequired,
                    sourcePath = info.sourcePath
                )
            }

            when (val result = recoveredFileRepository.recoverFiles(entities)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = result.message
                    )
                }
                is Resource.Loading -> { /* already saving */ }
            }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save scan results")
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            } catch (_: Throwable) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = "Failed to save results")
            }
        }
    }

    // ──────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────

    /**
     * Observes the [IScanEngine] state flow and syncs it with [ScanUiState].
     *
     * This ensures that external state changes (e.g. the engine
     * transitioning from Scanning → Paused) are reflected in the UI
     * even when they originate outside the [startScan] collector.
     */
    private fun observeScanEngineState() {
        viewModelScope.launch {
            try {
                scanEngine.getScanState().collect { state ->
                    // Only update if the state differs from what we already have
                    if (_uiState.value.scanState != state) {
                        _uiState.value = _uiState.value.copy(scanState = state)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to observe scan engine state")
            } catch (_: Throwable) {
                // Safe default: stop observing
            }
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
