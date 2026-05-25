package com.ultimaterecovery.pro.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.repository.RecoveredFileRepository
import com.ultimaterecovery.pro.data.repository.Resource
import com.ultimaterecovery.pro.engine.recovery.FileRecoveryEngine
import com.ultimaterecovery.pro.engine.recovery.FoundFileInfo
import com.ultimaterecovery.pro.engine.recovery.RecoveryBatch
import com.ultimaterecovery.pro.engine.recovery.RecoveryProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ──────────────────────────────────────────────
// Filter / Sort
// ──────────────────────────────────────────────

/**
 * Filter criteria for recovered documents and other files.
 */
data class FileFilter(
    val dateStart: Long? = null,
    val dateEnd: Long? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val mimeType: String? = null,
    val extension: String? = null
)

/**
 * Sort options for recovered files.
 */
enum class FileSortBy {
    DATE_NEWEST,
    DATE_OLDEST,
    SIZE_LARGEST,
    SIZE_SMALLEST,
    NAME_A_Z,
    NAME_Z_A,
    TYPE
}

// ──────────────────────────────────────────────
// UI State
// ──────────────────────────────────────────────

/**
 * UI state for the document / file recovery screen.
 */
data class FileRecoveryUiState(
    val files: List<RecoveredFileEntity> = emptyList(),
    val filteredFiles: List<RecoveredFileEntity> = emptyList(),
    val selectedFileIds: Set<Long> = emptySet(),
    val currentFilter: FileFilter = FileFilter(),
    val currentSortBy: FileSortBy = FileSortBy.DATE_NEWEST,
    val activeCategory: FileCategory = FileCategory.DOCUMENT,
    val previewFile: RecoveredFileEntity? = null,
    val isRecovering: Boolean = false,
    val recoveryProgress: RecoveryProgress? = null,
    val recoveryBatch: RecoveryBatch? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

// ──────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────

/**
 * ViewModel for the document and general file recovery screen.
 *
 * Supports switching between document-centric categories
 * (DOCUMENT, AUDIO, ARCHIVE, APK, OTHER) and provides
 * filtering, sorting, selection, and batch recovery.
 */
@HiltViewModel
class FileRecoveryViewModel @Inject constructor(
    private val recoveredFileRepository: RecoveredFileRepository,
    private val fileRecoveryEngine: FileRecoveryEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileRecoveryUiState(isLoading = true))
    val uiState: StateFlow<FileRecoveryUiState> = _uiState.asStateFlow()

    init {
        try {
        loadFiles()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize FileRecoveryViewModel")
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        } catch (_: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Initialization failed")
        }
    }

    // ──────────────────────────────────────────
    // Category switching
    // ──────────────────────────────────────────

    /**
     * Switches the active file category and reloads data.
     */
    fun setCategory(category: FileCategory) {
        _uiState.value = _uiState.value.copy(
            activeCategory = category,
            selectedFileIds = emptySet()
        )
        loadFiles()
    }

    // ──────────────────────────────────────────
    // Loading
    // ──────────────────────────────────────────

    fun loadFiles() {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isLoading = true)
            recoveredFileRepository.getFilesByCategory(_uiState.value.activeCategory)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
                .collect { resource ->
                    when (resource) {
                        is Resource.Success -> {
                            _uiState.value = _uiState.value.copy(
                                files = resource.data,
                                filteredFiles = applyFilterAndSort(
                                    resource.data,
                                    _uiState.value.currentFilter,
                                    _uiState.value.currentSortBy
                                ),
                                isLoading = false
                            )
                        }
                        is Resource.Error -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                error = resource.message
                            )
                        }
                        is Resource.Loading -> { /* keep loading */ }
                    }
                }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Filtering
    // ──────────────────────────────────────────

    fun filterFiles(filter: FileFilter) {
        _uiState.value = _uiState.value.copy(
            currentFilter = filter,
            filteredFiles = applyFilterAndSort(
                _uiState.value.files,
                filter,
                _uiState.value.currentSortBy
            )
        )
    }

    // ──────────────────────────────────────────
    // Sorting
    // ──────────────────────────────────────────

    fun sortFiles(sortBy: FileSortBy) {
        _uiState.value = _uiState.value.copy(
            currentSortBy = sortBy,
            filteredFiles = applyFilterAndSort(
                _uiState.value.files,
                _uiState.value.currentFilter,
                sortBy
            )
        )
    }

    // ──────────────────────────────────────────
    // Selection
    // ──────────────────────────────────────────

    fun selectFile(id: Long) {
        val current = _uiState.value.selectedFileIds.toMutableSet()
        if (id in current) current.remove(id) else current.add(id)
        _uiState.value = _uiState.value.copy(selectedFileIds = current)
    }

    fun selectAll() {
        val allIds = _uiState.value.filteredFiles.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedFileIds = allIds)
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedFileIds = emptySet())
    }

    // ──────────────────────────────────────────
    // Recovery
    // ──────────────────────────────────────────

    fun recoverSelected() {
        val selectedIds = _uiState.value.selectedFileIds
        if (selectedIds.isEmpty()) return

        val selectedFiles = _uiState.value.filteredFiles
            .filter { it.id in selectedIds }

        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(
                isRecovering = true,
                recoveryProgress = null,
                recoveryBatch = null
            )

            val fileInfos = selectedFiles.map { entity ->
                FoundFileInfo(
                    path = entity.filePath,
                    fileName = entity.fileName,
                    fileSize = entity.fileSize,
                    extension = entity.fileExtension,
                    mimeType = entity.mimeType,
                    category = entity.category,
                    thumbnailPath = entity.thumbnailPath,
                    lastModified = entity.originalDate,
                    isRootRequired = entity.isRootAccessed,
                    sourcePath = entity.sourcePath
                )
            }

            fileRecoveryEngine.recoverBatch(fileInfos)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isRecovering = false,
                        error = e.message
                    )
                }
                .collect { progress ->
                    _uiState.value = _uiState.value.copy(recoveryProgress = progress)
                }

            val batchResult = fileRecoveryEngine.recoverBatchAndGetResult(fileInfos)
            _uiState.value = _uiState.value.copy(
                isRecovering = false,
                recoveryBatch = batchResult,
                selectedFileIds = emptySet()
            )
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Preview
    // ──────────────────────────────────────────

    fun previewFile(id: Long) {
        val file = _uiState.value.filteredFiles.firstOrNull { it.id == id }
        _uiState.value = _uiState.value.copy(previewFile = file)
    }

    fun clearPreview() {
        _uiState.value = _uiState.value.copy(previewFile = null)
    }

    // ──────────────────────────────────────────
    // Error
    // ──────────────────────────────────────────

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // ──────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────

    private fun applyFilterAndSort(
        files: List<RecoveredFileEntity>,
        filter: FileFilter,
        sortBy: FileSortBy
    ): List<RecoveredFileEntity> {
        var result = files

        filter.dateStart?.let { start -> result = result.filter { it.originalDate >= start } }
        filter.dateEnd?.let { end -> result = result.filter { it.originalDate <= end } }
        filter.minSize?.let { min -> result = result.filter { it.fileSize >= min } }
        filter.maxSize?.let { max -> result = result.filter { it.fileSize <= max } }
        filter.mimeType?.let { mime -> result = result.filter { it.mimeType.startsWith(mime) } }
        filter.extension?.let { ext -> result = result.filter { it.fileExtension.equals(ext, ignoreCase = true) } }

        result = when (sortBy) {
            FileSortBy.DATE_NEWEST  -> result.sortedByDescending { it.originalDate }
            FileSortBy.DATE_OLDEST  -> result.sortedBy { it.originalDate }
            FileSortBy.SIZE_LARGEST -> result.sortedByDescending { it.fileSize }
            FileSortBy.SIZE_SMALLEST -> result.sortedBy { it.fileSize }
            FileSortBy.NAME_A_Z     -> result.sortedBy { it.fileName.lowercase() }
            FileSortBy.NAME_Z_A     -> result.sortedByDescending { it.fileName.lowercase() }
            FileSortBy.TYPE         -> result.sortedBy { it.fileExtension.lowercase() }
        }

        return result
    }
}
