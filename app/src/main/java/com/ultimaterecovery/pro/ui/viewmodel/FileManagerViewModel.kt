package com.ultimaterecovery.pro.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.repository.Resource
import com.ultimaterecovery.pro.manager.FileManager
import com.ultimaterecovery.pro.manager.FileManager.BatchProgress
import com.ultimaterecovery.pro.manager.FileManager.FileItem
import com.ultimaterecovery.pro.manager.FileManager.SortOrder
import com.ultimaterecovery.pro.manager.FileManager.StorageAnalysis
import com.ultimaterecovery.pro.manager.FileManager.StorageInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

// ──────────────────────────────────────────────
// UI State
// ──────────────────────────────────────────────

/**
 * UI state for the file manager screen.
 */
data class FileManagerUiState(
    val currentPath: String = "",
    val pathStack: List<String> = emptyList(),
    val files: List<FileItem> = emptyList(),
    val searchResults: List<FileItem> = emptyList(),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val selectedFileIds: Set<String> = emptySet(),
    val storageVolumes: List<StorageInfo> = emptyList(),
    val storageAnalysis: StorageAnalysis? = null,
    val bookmarks: List<String> = emptyList(),
    val sortOrder: SortOrder = SortOrder.NAME_ASC,
    val showHiddenFiles: Boolean = false,
    val batchProgress: BatchProgress? = null,
    val isBatchOperation: Boolean = false,
    val previewFile: FileItem? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
)

// ──────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────

/**
 * ViewModel for the file manager screen.
 *
 * Provides:
 * - Directory browsing with navigation stack
 * - File operations: copy, move, delete, rename, create
 * - Recursive search with extension filters
 * - Storage analysis and category breakdown
 * - Batch operations with progress tracking
 * - Bookmark management for favorite directories
 * - Sort order and hidden-file visibility toggles
 */
@HiltViewModel
class FileManagerViewModel @Inject constructor(
    private val fileManager: FileManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FileManagerUiState(
            currentPath = fileManager.getDefaultDirectory().absolutePath,
            isLoading = true
        )
    )
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    init {
        try {
        loadStorageVolumes()
        loadBookmarks()
        browseDirectory(fileManager.getDefaultDirectory().absolutePath)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize FileManagerViewModel")
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        } catch (_: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Initialization failed")
        }
    }

    // ──────────────────────────────────────────
    // Browsing
    // ──────────────────────────────────────────

    /**
     * Navigates into [path] and lists its contents.
     *
     * The previous path is pushed onto the navigation stack
     * so the user can navigate back.
     */
    fun browseDirectory(path: String) {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = fileManager.listFiles(path)) {
                is Resource.Success -> {
                    val stack = if (_uiState.value.currentPath != path) {
                        _uiState.value.pathStack + _uiState.value.currentPath
                    } else {
                        _uiState.value.pathStack
                    }
                    _uiState.value = _uiState.value.copy(
                        currentPath = path,
                        pathStack = stack,
                        files = result.data,
                        isLoading = false,
                        searchQuery = "",
                        isSearching = false,
                        searchResults = emptyList()
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
                is Resource.Loading -> { /* keep loading */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    /**
     * Navigates to the parent directory.
     */
    fun navigateUp() {
        val stack = _uiState.value.pathStack
        if (stack.isNotEmpty()) {
            val parentPath = stack.last()
            _uiState.value = _uiState.value.copy(
                pathStack = stack.dropLast(1)
            )
            browseDirectory(parentPath)
        }
    }

    /**
     * Returns whether the user can navigate up from the current directory.
     */
    fun canNavigateUp(): Boolean = _uiState.value.pathStack.isNotEmpty()

    // ──────────────────────────────────────────
    // File operations
    // ──────────────────────────────────────────

    fun copyFile(src: String, dst: String) {
        viewModelScope.launch {
            try {

            when (val result = fileManager.copyFile(src, dst)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        successMessage = "File copied successfully"
                    )
                    browseDirectory(_uiState.value.currentPath) // refresh
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    fun moveFile(src: String, dst: String) {
        viewModelScope.launch {
            try {

            when (val result = fileManager.moveFile(src, dst)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        successMessage = "File moved successfully"
                    )
                    browseDirectory(_uiState.value.currentPath)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    fun deleteFile(path: String) {
        viewModelScope.launch {
            try {

            when (val result = fileManager.deleteFile(path)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        successMessage = "File deleted"
                    )
                    browseDirectory(_uiState.value.currentPath)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    fun renameFile(path: String, newName: String) {
        viewModelScope.launch {
            try {

            when (val result = fileManager.renameFile(path, newName)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        successMessage = "File renamed"
                    )
                    browseDirectory(_uiState.value.currentPath)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    fun createFolder(path: String) {
        viewModelScope.launch {
            try {

            when (val result = fileManager.createFolder(path)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        successMessage = "Folder created"
                    )
                    browseDirectory(_uiState.value.currentPath)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    fun createFile(path: String) {
        viewModelScope.launch {
            try {

            when (val result = fileManager.createFile(path)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        successMessage = "File created"
                    )
                    browseDirectory(_uiState.value.currentPath)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Batch operations
    // ──────────────────────────────────────────

    fun batchCopy(sources: List<String>, destDir: String) {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isBatchOperation = true)
            fileManager.batchCopy(sources, destDir)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isBatchOperation = false,
                        error = e.message
                    )
                }
                .collect { progress ->
                    _uiState.value = _uiState.value.copy(batchProgress = progress)
                    if (progress.processed >= progress.total) {
                        _uiState.value = _uiState.value.copy(
                            isBatchOperation = false,
                            successMessage = "Batch copy completed"
                        )
                        browseDirectory(_uiState.value.currentPath)
                    }
                }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    fun batchMove(sources: List<String>, destDir: String) {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isBatchOperation = true)
            fileManager.batchMove(sources, destDir)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isBatchOperation = false,
                        error = e.message
                    )
                }
                .collect { progress ->
                    _uiState.value = _uiState.value.copy(batchProgress = progress)
                    if (progress.processed >= progress.total) {
                        _uiState.value = _uiState.value.copy(
                            isBatchOperation = false,
                            successMessage = "Batch move completed"
                        )
                        browseDirectory(_uiState.value.currentPath)
                    }
                }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    fun batchDelete(paths: List<String>) {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isBatchOperation = true)
            fileManager.batchDelete(paths)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isBatchOperation = false,
                        error = e.message
                    )
                }
                .collect { progress ->
                    _uiState.value = _uiState.value.copy(batchProgress = progress)
                    if (progress.processed >= progress.total) {
                        _uiState.value = _uiState.value.copy(
                            isBatchOperation = false,
                            successMessage = "Batch delete completed"
                        )
                        browseDirectory(_uiState.value.currentPath)
                    }
                }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────

    fun search(query: String, extensionFilter: Set<String>? = null) {
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                isSearching = false,
                searchQuery = "",
                searchResults = emptyList()
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            isSearching = true
        )

        viewModelScope.launch {
            try {

            val results = mutableListOf<FileItem>()
            fileManager.searchFiles(query, _uiState.value.currentPath, extensionFilter)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        error = e.message
                    )
                }
                .collect { fileItem ->
                    results.add(fileItem)
                    _uiState.value = _uiState.value.copy(searchResults = results.toList())
                }
            _uiState.value = _uiState.value.copy(isSearching = false)
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    fun clearSearch() {
        _uiState.value = _uiState.value.copy(
            searchQuery = "",
            isSearching = false,
            searchResults = emptyList()
        )
    }

    // ──────────────────────────────────────────
    // Storage analysis
    // ──────────────────────────────────────────

    fun loadStorageVolumes() {
        val volumes = fileManager.getStorageVolumes()
        _uiState.value = _uiState.value.copy(storageVolumes = volumes)
    }

    fun analyzeStorage(topN: Int = 20) {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = fileManager.getStorageAnalysis(topN)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        storageAnalysis = result.data,
                        isLoading = false
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
                is Resource.Loading -> { /* keep loading */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Bookmarks
    // ──────────────────────────────────────────

    fun loadBookmarks() {
        _uiState.value = _uiState.value.copy(bookmarks = fileManager.getBookmarks())
    }

    fun addBookmark(path: String) {
        fileManager.addBookmark(path)
        loadBookmarks()
    }

    fun removeBookmark(path: String) {
        fileManager.removeBookmark(path)
        loadBookmarks()
    }

    fun isBookmarked(path: String): Boolean = fileManager.isBookmarked(path)

    // ──────────────────────────────────────────
    // Settings
    // ──────────────────────────────────────────

    fun setSortOrder(order: SortOrder) {
        fileManager.sortOrder = order
        _uiState.value = _uiState.value.copy(sortOrder = order)
        browseDirectory(_uiState.value.currentPath)
    }

    fun toggleShowHiddenFiles() {
        val newValue = !_uiState.value.showHiddenFiles
        fileManager.showHiddenFiles = newValue
        _uiState.value = _uiState.value.copy(showHiddenFiles = newValue)
        browseDirectory(_uiState.value.currentPath)
    }

    // ──────────────────────────────────────────
    // Selection
    // ──────────────────────────────────────────

    fun selectFile(path: String) {
        val current = _uiState.value.selectedFileIds.toMutableSet()
        if (path in current) current.remove(path) else current.add(path)
        _uiState.value = _uiState.value.copy(selectedFileIds = current)
    }

    fun selectAllFiles() {
        val allPaths = _uiState.value.files.map { it.path }.toSet()
        _uiState.value = _uiState.value.copy(selectedFileIds = allPaths)
    }

    fun deselectAllFiles() {
        _uiState.value = _uiState.value.copy(selectedFileIds = emptySet())
    }

    // ──────────────────────────────────────────
    // Preview
    // ──────────────────────────────────────────

    fun previewFile(path: String) {
        viewModelScope.launch {
            try {

            when (val result = fileManager.getFileDetails(path)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(previewFile = result.data)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in FileManagerViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    fun clearPreview() {
        _uiState.value = _uiState.value.copy(previewFile = null)
    }

    // ──────────────────────────────────────────
    // Messages
    // ──────────────────────────────────────────

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearSuccessMessage() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }
}
