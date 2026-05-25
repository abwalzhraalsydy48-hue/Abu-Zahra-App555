package com.ultimaterecovery.pro.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.RecoveryStatus
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
 * Filter criteria for recovered photos.
 */
data class PhotoFilter(
    val dateStart: Long? = null,
    val dateEnd: Long? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val mimeType: String? = null
)

/**
 * Sort options for recovered photos.
 */
enum class PhotoSortBy {
    DATE_NEWEST,
    DATE_OLDEST,
    SIZE_LARGEST,
    SIZE_SMALLEST,
    NAME_A_Z,
    NAME_Z_A
}

// ──────────────────────────────────────────────
// UI State
// ──────────────────────────────────────────────

/**
 * UI state for the photo recovery screen.
 */
data class PhotoRecoveryUiState(
    val photos: List<RecoveredFileEntity> = emptyList(),
    val filteredPhotos: List<RecoveredFileEntity> = emptyList(),
    val selectedPhotoIds: Set<Long> = emptySet(),
    val currentFilter: PhotoFilter = PhotoFilter(),
    val currentSortBy: PhotoSortBy = PhotoSortBy.DATE_NEWEST,
    val previewPhoto: RecoveredFileEntity? = null,
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
 * ViewModel for the photo recovery screen.
 *
 * Provides:
 * - Reactive list of recovered photos via [RecoveredFileRepository]
 * - Client-side filtering by date, size, and MIME type
 * - Sort by date, size, or name
 * - Multi-selection for batch recovery
 * - Photo preview state
 * - Batch recovery with progress tracking via [FileRecoveryEngine]
 */
@HiltViewModel
class PhotoRecoveryViewModel @Inject constructor(
    private val recoveredFileRepository: RecoveredFileRepository,
    private val fileRecoveryEngine: FileRecoveryEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhotoRecoveryUiState(isLoading = true))
    val uiState: StateFlow<PhotoRecoveryUiState> = _uiState.asStateFlow()

    init {
        try {
        loadPhotos()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize PhotoRecoveryViewModel")
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        } catch (_: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Initialization failed")
        }
    }

    // ──────────────────────────────────────────
    // Loading
    // ──────────────────────────────────────────

    /**
     * Loads recovered photos from the repository by querying the
     * PHOTO category and applying the current filter/sort.
     */
    fun loadPhotos() {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isLoading = true)
            recoveredFileRepository.getFilesByCategory(FileCategory.PHOTO)
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
                                photos = resource.data,
                                filteredPhotos = applyFilterAndSort(
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
    Timber.e(e, "Database error in PhotoRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Filtering
    // ──────────────────────────────────────────

    /**
     * Applies a [filter] to the photo list.
     *
     * Filtering is performed client-side for instant feedback.
     * The repository is the source of truth; the filter only
     * narrows the displayed subset.
     */
    fun filterPhotos(filter: PhotoFilter) {
        _uiState.value = _uiState.value.copy(
            currentFilter = filter,
            filteredPhotos = applyFilterAndSort(
                _uiState.value.photos,
                filter,
                _uiState.value.currentSortBy
            )
        )
    }

    // ──────────────────────────────────────────
    // Sorting
    // ──────────────────────────────────────────

    /**
     * Sorts the photo list according to [sortBy].
     */
    fun sortPhotos(sortBy: PhotoSortBy) {
        _uiState.value = _uiState.value.copy(
            currentSortBy = sortBy,
            filteredPhotos = applyFilterAndSort(
                _uiState.value.photos,
                _uiState.value.currentFilter,
                sortBy
            )
        )
    }

    // ──────────────────────────────────────────
    // Selection
    // ──────────────────────────────────────────

    /**
     * Toggles the selection state of a photo by its [id].
     */
    fun selectPhoto(id: Long) {
        val current = _uiState.value.selectedPhotoIds.toMutableSet()
        if (id in current) current.remove(id) else current.add(id)
        _uiState.value = _uiState.value.copy(selectedPhotoIds = current)
    }

    /**
     * Selects all currently filtered photos.
     */
    fun selectAll() {
        val allIds = _uiState.value.filteredPhotos.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedPhotoIds = allIds)
    }

    /**
     * Deselects all photos.
     */
    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedPhotoIds = emptySet())
    }

    // ──────────────────────────────────────────
    // Recovery
    // ──────────────────────────────────────────

    /**
     * Recovers all selected photos to the default output directory.
     *
     * Uses [FileRecoveryEngine.recoverBatch] for efficient batch
     * processing with real-time progress updates.
     */
    fun recoverSelected() {
        val selectedIds = _uiState.value.selectedPhotoIds
        if (selectedIds.isEmpty()) return

        val selectedPhotos = _uiState.value.filteredPhotos
            .filter { it.id in selectedIds }

        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(
                isRecovering = true,
                recoveryProgress = null,
                recoveryBatch = null
            )

            val fileInfos = selectedPhotos.map { entity ->
                FoundFileInfo(
                    path = entity.filePath,
                    fileName = entity.fileName,
                    fileSize = entity.fileSize,
                    extension = entity.fileExtension,
                    mimeType = entity.mimeType,
                    category = FileCategory.PHOTO,
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

            // Final batch result
            val batchResult = fileRecoveryEngine.recoverBatchAndGetResult(fileInfos)
            _uiState.value = _uiState.value.copy(
                isRecovering = false,
                recoveryBatch = batchResult,
                selectedPhotoIds = emptySet()
            )
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in PhotoRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Preview
    // ──────────────────────────────────────────

    /**
     * Sets the photo to preview (full-screen view).
     */
    fun previewPhoto(id: Long) {
        val photo = _uiState.value.filteredPhotos.firstOrNull { it.id == id }
        _uiState.value = _uiState.value.copy(previewPhoto = photo)
    }

    /**
     * Clears the preview state.
     */
    fun clearPreview() {
        _uiState.value = _uiState.value.copy(previewPhoto = null)
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

    /**
     * Applies the given [filter] and [sortBy] to a list of photos.
     */
    private fun applyFilterAndSort(
        photos: List<RecoveredFileEntity>,
        filter: PhotoFilter,
        sortBy: PhotoSortBy
    ): List<RecoveredFileEntity> {
        var result = photos

        // Apply date range filter
        filter.dateStart?.let { start ->
            result = result.filter { it.originalDate >= start }
        }
        filter.dateEnd?.let { end ->
            result = result.filter { it.originalDate <= end }
        }

        // Apply size filter
        filter.minSize?.let { min ->
            result = result.filter { it.fileSize >= min }
        }
        filter.maxSize?.let { max ->
            result = result.filter { it.fileSize <= max }
        }

        // Apply MIME type filter
        filter.mimeType?.let { mime ->
            result = result.filter { it.mimeType.startsWith(mime) }
        }

        // Sort
        result = when (sortBy) {
            PhotoSortBy.DATE_NEWEST  -> result.sortedByDescending { it.originalDate }
            PhotoSortBy.DATE_OLDEST  -> result.sortedBy { it.originalDate }
            PhotoSortBy.SIZE_LARGEST -> result.sortedByDescending { it.fileSize }
            PhotoSortBy.SIZE_SMALLEST -> result.sortedBy { it.fileSize }
            PhotoSortBy.NAME_A_Z     -> result.sortedBy { it.fileName.lowercase() }
            PhotoSortBy.NAME_Z_A     -> result.sortedByDescending { it.fileName.lowercase() }
        }

        return result
    }
}
