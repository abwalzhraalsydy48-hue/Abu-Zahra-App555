package com.ultimaterecovery.pro.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultimaterecovery.pro.data.local.entity.RecycleBinItemEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.repository.RecycleBinRepository
import com.ultimaterecovery.pro.data.repository.Resource
import com.ultimaterecovery.pro.utils.recyclebin.SmartRecycleBin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ──────────────────────────────────────────────
// Filter
// ──────────────────────────────────────────────

/**
 * Filter criteria for recycle bin items.
 */
data class RecycleBinFilter(
    val category: FileCategory? = null,
    val searchQuery: String? = null,
    val showExpiredOnly: Boolean = false
)

// ──────────────────────────────────────────────
// UI State
// ──────────────────────────────────────────────

/**
 * UI state for the recycle bin screen.
 */
data class RecycleBinUiState(
    val items: List<RecycleBinItemEntity> = emptyList(),
    val filteredItems: List<RecycleBinItemEntity> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val currentFilter: RecycleBinFilter = RecycleBinFilter(),
    val searchQuery: String = "",
    val totalStorageUsed: Long = 0L,
    val totalItemCount: Int = 0,
    val autoDeleteDays: Int = SmartRecycleBin.VALID_AUTO_DELETE_DAYS.first(),
    val storageLimitMb: Long = 500L,
    val isMonitoring: Boolean = false,
    val isRestoring: Boolean = false,
    val isDeleting: Boolean = false,
    val isCleaning: Boolean = false,
    val previewItem: RecycleBinItemEntity? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
)

// ──────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────

/**
 * ViewModel for the recycle bin screen.
 *
 * Provides:
 * - Reactive list of recycle bin items from [SmartRecycleBin]
 * - Filter by category and search query
 * - Restore items to their original locations
 * - Permanent (optionally secure) deletion
 * - Auto-cleanup of expired items
 * - Configuration of auto-delete period and storage limit
 * - Monitoring lifecycle control
 */
@HiltViewModel
class RecycleBinViewModel @Inject constructor(
    private val recycleBinRepository: RecycleBinRepository,
    private val smartRecycleBin: SmartRecycleBin
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecycleBinUiState(isLoading = true))
    val uiState: StateFlow<RecycleBinUiState> = _uiState.asStateFlow()

    init {
        try {
        loadItems()
        loadStorageInfo()
        loadSettings()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize RecycleBinViewModel")
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        } catch (_: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Initialization failed")
        }
    }

    // ──────────────────────────────────────────
    // Loading
    // ──────────────────────────────────────────

    fun loadItems() {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isLoading = true)
            smartRecycleBin.getItems()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
                .collect { items ->
                    _uiState.value = _uiState.value.copy(
                        items = items,
                        filteredItems = applyFilter(items, _uiState.value.currentFilter),
                        isLoading = false
                    )
                }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in RecycleBinViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    private fun loadStorageInfo() {
        viewModelScope.launch {
            try {

            val used = smartRecycleBin.getTotalStorageUsed()
            _uiState.value = _uiState.value.copy(totalStorageUsed = used)
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in RecycleBinViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
        viewModelScope.launch {
            try {

            smartRecycleBin.getItemCount().collect { count ->
                _uiState.value = _uiState.value.copy(totalItemCount = count)
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in RecycleBinViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    private fun loadSettings() {
        _uiState.value = _uiState.value.copy(
            autoDeleteDays = smartRecycleBin.getAutoDeleteDays(),
            storageLimitMb = smartRecycleBin.getStorageLimit(),
            isMonitoring = smartRecycleBin.isMonitoring()
        )
    }

    // ──────────────────────────────────────────
    // Filtering
    // ──────────────────────────────────────────

    fun filterItems(filter: RecycleBinFilter) {
        _uiState.value = _uiState.value.copy(currentFilter = filter)

        viewModelScope.launch {
            try {

            when {
                filter.category != null -> {
                    smartRecycleBin.getItemsByCategory(filter.category)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { items ->
                            _uiState.value = _uiState.value.copy(
                                filteredItems = applyFilter(items, filter)
                            )
                        }
                }
                filter.searchQuery != null && filter.searchQuery.isNotBlank() -> {
                    smartRecycleBin.searchItems(filter.searchQuery)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { items ->
                            _uiState.value = _uiState.value.copy(
                                filteredItems = applyFilter(items, filter)
                            )
                        }
                }
                else -> {
                    _uiState.value = _uiState.value.copy(
                        filteredItems = applyFilter(_uiState.value.items, filter)
                    )
                }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in RecycleBinViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        filterItems(_uiState.value.currentFilter.copy(searchQuery = query.ifBlank { null }))
    }

    // ──────────────────────────────────────────
    // Selection
    // ──────────────────────────────────────────

    fun selectItem(id: Long) {
        val current = _uiState.value.selectedIds.toMutableSet()
        if (id in current) current.remove(id) else current.add(id)
        _uiState.value = _uiState.value.copy(selectedIds = current)
    }

    fun selectAll() {
        val allIds = _uiState.value.filteredItems.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedIds = allIds)
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedIds = emptySet())
    }

    // ──────────────────────────────────────────
    // Restore
    // ──────────────────────────────────────────

    /**
     * Restores a single item by its [itemId].
     */
    fun restoreItem(itemId: Long) {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isRestoring = true)
            when (val result = smartRecycleBin.restoreItem(itemId)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        successMessage = "Item restored to ${result.data}"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        error = result.message
                    )
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in RecycleBinViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    /**
     * Restores all selected items.
     */
    fun restoreSelected() {
        val selectedIds = _uiState.value.selectedIds
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isRestoring = true)
            var restoredCount = 0
            for (id in selectedIds) {
                when (smartRecycleBin.restoreItem(id)) {
                    is Resource.Success -> restoredCount++
                    else -> { /* continue with other items */ }
                }
            }
            _uiState.value = _uiState.value.copy(
                isRestoring = false,
                selectedIds = emptySet(),
                successMessage = "$restoredCount items restored"
            )
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in RecycleBinViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Delete
    // ──────────────────────────────────────────

    /**
     * Permanently deletes a single item.
     *
     * @param secureWipe Whether to securely overwrite the file first.
     */
    fun deleteItem(itemId: Long, secureWipe: Boolean = false) {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isDeleting = true)
            when (val result = smartRecycleBin.deletePermanent(itemId, secureWipe)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        successMessage = "Item permanently deleted"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        error = result.message
                    )
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in RecycleBinViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    /**
     * Permanently deletes all selected items.
     */
    fun deleteSelected(secureWipe: Boolean = false) {
        val selectedIds = _uiState.value.selectedIds
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isDeleting = true)
            var deletedCount = 0
            for (id in selectedIds) {
                when (smartRecycleBin.deletePermanent(id, secureWipe)) {
                    is Resource.Success -> deletedCount++
                    else -> { /* continue */ }
                }
            }
            _uiState.value = _uiState.value.copy(
                isDeleting = false,
                selectedIds = emptySet(),
                successMessage = "$deletedCount items permanently deleted"
            )
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in RecycleBinViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────

    /**
     * Cleans up expired items.
     */
    fun cleanExpired() {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isCleaning = true)
            when (val result = smartRecycleBin.cleanExpired()) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isCleaning = false,
                        successMessage = "${result.data} expired items removed"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isCleaning = false,
                        error = result.message
                    )
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in RecycleBinViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Settings
    // ──────────────────────────────────────────

    fun setAutoDeleteDays(days: Int) {
        if (days in SmartRecycleBin.VALID_AUTO_DELETE_DAYS) {
            smartRecycleBin.setAutoDeleteDays(days)
            _uiState.value = _uiState.value.copy(autoDeleteDays = days)
        }
    }

    fun setStorageLimit(mb: Long) {
        smartRecycleBin.setStorageLimit(mb)
        _uiState.value = _uiState.value.copy(storageLimitMb = mb)
    }

    fun startMonitoring() {
        val started = smartRecycleBin.startMonitoring()
        _uiState.value = _uiState.value.copy(isMonitoring = started)
    }

    fun stopMonitoring() {
        smartRecycleBin.stopMonitoring()
        _uiState.value = _uiState.value.copy(isMonitoring = false)
    }

    // ──────────────────────────────────────────
    // Preview
    // ──────────────────────────────────────────

    fun previewItem(id: Long) {
        val item = _uiState.value.filteredItems.firstOrNull { it.id == id }
        _uiState.value = _uiState.value.copy(previewItem = item)
    }

    fun clearPreview() {
        _uiState.value = _uiState.value.copy(previewItem = null)
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

    // ──────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────

    private fun applyFilter(
        items: List<RecycleBinItemEntity>,
        filter: RecycleBinFilter
    ): List<RecycleBinItemEntity> {
        var result = items

        if (filter.showExpiredOnly) {
            val now = System.currentTimeMillis()
            result = result.filter { it.expiryDate <= now }
        }

        return result.sortedByDescending { it.deletedDate }
    }
}
