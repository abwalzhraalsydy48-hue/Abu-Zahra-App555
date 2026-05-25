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
 * Filter criteria for recovered videos.
 */
data class VideoFilter(
    val dateStart: Long? = null,
    val dateEnd: Long? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val mimeType: String? = null,
    val minDurationMs: Long? = null,
    val maxDurationMs: Long? = null
)

/**
 * Sort options for recovered videos.
 */
enum class VideoSortBy {
    DATE_NEWEST,
    DATE_OLDEST,
    SIZE_LARGEST,
    SIZE_SMALLEST,
    NAME_A_Z,
    NAME_Z_A,
    DURATION_LONGEST,
    DURATION_SHORTEST
}

// ──────────────────────────────────────────────
// UI State
// ──────────────────────────────────────────────

/**
 * UI state for the video recovery screen.
 *
 * Extends the photo recovery pattern with video-specific features
 * such as thumbnail extraction metadata and duration display.
 */
data class VideoRecoveryUiState(
    val videos: List<RecoveredFileEntity> = emptyList(),
    val filteredVideos: List<RecoveredFileEntity> = emptyList(),
    val selectedVideoIds: Set<Long> = emptySet(),
    val currentFilter: VideoFilter = VideoFilter(),
    val currentSortBy: VideoSortBy = VideoSortBy.DATE_NEWEST,
    val previewVideo: RecoveredFileEntity? = null,
    val thumbnailPaths: Map<Long, String> = emptyMap(),
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
 * ViewModel for the video recovery screen.
 *
 * Similar to [PhotoRecoveryViewModel] but tailored for video files
 * with additional features:
 * - Video-specific MIME type filtering (mp4, avi, mkv, etc.)
 * - Duration-based filtering and sorting
 * - Thumbnail extraction for video preview
 * - Batch recovery with progress tracking
 */
@HiltViewModel
class VideoRecoveryViewModel @Inject constructor(
    private val recoveredFileRepository: RecoveredFileRepository,
    private val fileRecoveryEngine: FileRecoveryEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoRecoveryUiState(isLoading = true))
    val uiState: StateFlow<VideoRecoveryUiState> = _uiState.asStateFlow()

    init {
        try {
        loadVideos()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize VideoRecoveryViewModel")
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        } catch (_: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Initialization failed")
        }
    }

    // ──────────────────────────────────────────
    // Loading
    // ──────────────────────────────────────────

    /**
     * Loads recovered videos from the repository.
     */
    fun loadVideos() {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isLoading = true)
            recoveredFileRepository.getFilesByCategory(FileCategory.VIDEO)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
                .collect { resource ->
                    when (resource) {
                        is Resource.Success -> {
                            // Build thumbnail map from entity data
                            val thumbs = resource.data
                                .filter { !it.thumbnailPath.isNullOrEmpty() }
                                .associate { it.id to it.thumbnailPath!! }

                            _uiState.value = _uiState.value.copy(
                                videos = resource.data,
                                filteredVideos = applyFilterAndSort(
                                    resource.data,
                                    _uiState.value.currentFilter,
                                    _uiState.value.currentSortBy
                                ),
                                thumbnailPaths = thumbs,
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
    Timber.e(e, "Database error in VideoRecoveryViewModel")
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
     * Applies a [filter] to the video list.
     */
    fun filterVideos(filter: VideoFilter) {
        _uiState.value = _uiState.value.copy(
            currentFilter = filter,
            filteredVideos = applyFilterAndSort(
                _uiState.value.videos,
                filter,
                _uiState.value.currentSortBy
            )
        )
    }

    // ──────────────────────────────────────────
    // Sorting
    // ──────────────────────────────────────────

    /**
     * Sorts the video list according to [sortBy].
     */
    fun sortVideos(sortBy: VideoSortBy) {
        _uiState.value = _uiState.value.copy(
            currentSortBy = sortBy,
            filteredVideos = applyFilterAndSort(
                _uiState.value.videos,
                _uiState.value.currentFilter,
                sortBy
            )
        )
    }

    // ──────────────────────────────────────────
    // Selection
    // ──────────────────────────────────────────

    fun selectVideo(id: Long) {
        val current = _uiState.value.selectedVideoIds.toMutableSet()
        if (id in current) current.remove(id) else current.add(id)
        _uiState.value = _uiState.value.copy(selectedVideoIds = current)
    }

    fun selectAll() {
        val allIds = _uiState.value.filteredVideos.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedVideoIds = allIds)
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedVideoIds = emptySet())
    }

    // ──────────────────────────────────────────
    // Recovery
    // ──────────────────────────────────────────

    /**
     * Recovers all selected videos using the [FileRecoveryEngine].
     */
    fun recoverSelected() {
        val selectedIds = _uiState.value.selectedVideoIds
        if (selectedIds.isEmpty()) return

        val selectedVideos = _uiState.value.filteredVideos
            .filter { it.id in selectedIds }

        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(
                isRecovering = true,
                recoveryProgress = null,
                recoveryBatch = null
            )

            val fileInfos = selectedVideos.map { entity ->
                FoundFileInfo(
                    path = entity.filePath,
                    fileName = entity.fileName,
                    fileSize = entity.fileSize,
                    extension = entity.fileExtension,
                    mimeType = entity.mimeType,
                    category = FileCategory.VIDEO,
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
                selectedVideoIds = emptySet()
            )
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in VideoRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Preview & Thumbnail
    // ──────────────────────────────────────────

    /**
     * Sets the video to preview.
     */
    fun previewVideo(id: Long) {
        val video = _uiState.value.filteredVideos.firstOrNull { it.id == id }
        _uiState.value = _uiState.value.copy(previewVideo = video)
    }

    fun clearPreview() {
        _uiState.value = _uiState.value.copy(previewVideo = null)
    }

    /**
     * Extracts a thumbnail for the given video and updates the
     * thumbnail map.
     *
     * In production this would use [android.media.ThumbnailUtils]
     * or MediaMetadataRetriever to generate a frame from the video.
     */
    fun extractThumbnail(id: Long) {
        val video = _uiState.value.filteredVideos.firstOrNull { it.id == id }
        if (video?.thumbnailPath != null) return // already extracted

        // Placeholder: in production, use MediaMetadataRetriever
        // to extract a frame and save it to a thumbnail cache.
        viewModelScope.launch {
            try {

            val updatedThumbs = _uiState.value.thumbnailPaths.toMutableMap()
            // Simulated thumbnail path
            updatedThumbs[id] = video?.filePath ?: ""
            _uiState.value = _uiState.value.copy(thumbnailPaths = updatedThumbs)
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in VideoRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
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
        videos: List<RecoveredFileEntity>,
        filter: VideoFilter,
        sortBy: VideoSortBy
    ): List<RecoveredFileEntity> {
        var result = videos

        filter.dateStart?.let { start -> result = result.filter { it.originalDate >= start } }
        filter.dateEnd?.let { end -> result = result.filter { it.originalDate <= end } }
        filter.minSize?.let { min -> result = result.filter { it.fileSize >= min } }
        filter.maxSize?.let { max -> result = result.filter { it.fileSize <= max } }
        filter.mimeType?.let { mime -> result = result.filter { it.mimeType.startsWith(mime) } }
        // Duration filtering would require metadata extraction; placeholder for now.
        // filter.minDurationMs / maxDurationMs would filter on extracted duration metadata.

        result = when (sortBy) {
            VideoSortBy.DATE_NEWEST     -> result.sortedByDescending { it.originalDate }
            VideoSortBy.DATE_OLDEST     -> result.sortedBy { it.originalDate }
            VideoSortBy.SIZE_LARGEST    -> result.sortedByDescending { it.fileSize }
            VideoSortBy.SIZE_SMALLEST   -> result.sortedBy { it.fileSize }
            VideoSortBy.NAME_A_Z        -> result.sortedBy { it.fileName.lowercase() }
            VideoSortBy.NAME_Z_A        -> result.sortedByDescending { it.fileName.lowercase() }
            VideoSortBy.DURATION_LONGEST,
            VideoSortBy.DURATION_SHORTEST -> result // requires metadata; fallback to current order
        }

        return result
    }
}
