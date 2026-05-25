package com.ultimaterecovery.pro.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultimaterecovery.pro.data.local.entity.AppDataEntity
import com.ultimaterecovery.pro.data.local.entity.AppDataEntity.AppDataType
import com.ultimaterecovery.pro.data.repository.AppDataRepository
import com.ultimaterecovery.pro.data.repository.Resource
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
 * Filter criteria for app data entries.
 */
data class AppDataFilter(
    val packageName: String? = null,
    val dataType: AppDataType? = null,
    val isSystemApp: Boolean? = null,
    val searchQuery: String? = null
)

/**
 * Sort options for app data entries.
 */
enum class AppDataSortBy {
    APP_NAME_A_Z,
    APP_NAME_Z_A,
    SIZE_LARGEST,
    SIZE_SMALLEST,
    DATE_NEWEST,
    DATE_OLDEST,
    TYPE
}

// ──────────────────────────────────────────────
// UI State
// ──────────────────────────────────────────────

/**
 * UI state for the app data recovery screen.
 */
data class AppDataRecoveryUiState(
    val appDataList: List<AppDataEntity> = emptyList(),
    val filteredAppData: List<AppDataEntity> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val currentFilter: AppDataFilter = AppDataFilter(),
    val currentSortBy: AppDataSortBy = AppDataSortBy.APP_NAME_A_Z,
    val searchQuery: String = "",
    val systemApps: List<AppDataEntity> = emptyList(),
    val userApps: List<AppDataEntity> = emptyList(),
    val previewAppData: AppDataEntity? = null,
    val isRecovering: Boolean = false,
    val recoveredCount: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null
)

// ──────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────

/**
 * ViewModel for the app data recovery screen.
 *
 * Provides:
 * - Reactive list of recovered app data from [AppDataRepository]
 * - Filter by package name, data type, and system/user classification
 * - Full-text search across app name and package name
 * - Multi-select for batch recovery
 * - Separate system/user app views
 */
@HiltViewModel
class AppDataRecoveryViewModel @Inject constructor(
    private val appDataRepository: AppDataRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppDataRecoveryUiState(isLoading = true))
    val uiState: StateFlow<AppDataRecoveryUiState> = _uiState.asStateFlow()

    init {
        try {
        loadAppData()
        loadSystemAndUserApps()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AppDataRecoveryViewModel")
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        } catch (_: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Initialization failed")
        }
    }

    // ──────────────────────────────────────────
    // Loading
    // ──────────────────────────────────────────

    fun loadAppData() {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isLoading = true)
            appDataRepository.getAll()
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
                                appDataList = resource.data,
                                filteredAppData = applyFilterAndSort(
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
    Timber.e(e, "Database error in AppDataRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    private fun loadSystemAndUserApps() {
        viewModelScope.launch {
            try {

            appDataRepository.getSystemApps().collect { resource ->
                if (resource is Resource.Success) {
                    _uiState.value = _uiState.value.copy(systemApps = resource.data)
                }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in AppDataRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
        viewModelScope.launch {
            try {

            appDataRepository.getUserApps().collect { resource ->
                if (resource is Resource.Success) {
                    _uiState.value = _uiState.value.copy(userApps = resource.data)
                }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in AppDataRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Filtering
    // ──────────────────────────────────────────

    fun filterAppData(filter: AppDataFilter) {
        _uiState.value = _uiState.value.copy(currentFilter = filter)

        viewModelScope.launch {
            try {

            when {
                filter.searchQuery != null && filter.searchQuery.isNotBlank() -> {
                    appDataRepository.searchApps(filter.searchQuery)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredAppData = applyFilterAndSort(
                                        resource.data, filter, _uiState.value.currentSortBy
                                    )
                                )
                            }
                        }
                }
                filter.packageName != null && filter.packageName.isNotBlank() -> {
                    appDataRepository.getByPackageName(filter.packageName)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredAppData = applyFilterAndSort(
                                        resource.data, filter, _uiState.value.currentSortBy
                                    )
                                )
                            }
                        }
                }
                filter.dataType != null -> {
                    appDataRepository.getByDataType(filter.dataType)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredAppData = applyFilterAndSort(
                                        resource.data, filter, _uiState.value.currentSortBy
                                    )
                                )
                            }
                        }
                }
                filter.isSystemApp == true -> {
                    appDataRepository.getSystemApps()
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredAppData = applyFilterAndSort(
                                        resource.data, filter, _uiState.value.currentSortBy
                                    )
                                )
                            }
                        }
                }
                filter.isSystemApp == false -> {
                    appDataRepository.getUserApps()
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredAppData = applyFilterAndSort(
                                        resource.data, filter, _uiState.value.currentSortBy
                                    )
                                )
                            }
                        }
                }
                else -> {
                    _uiState.value = _uiState.value.copy(
                        filteredAppData = applyFilterAndSort(
                            _uiState.value.appDataList, filter, _uiState.value.currentSortBy
                        )
                    )
                }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in AppDataRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Sorting
    // ──────────────────────────────────────────

    fun sortAppData(sortBy: AppDataSortBy) {
        _uiState.value = _uiState.value.copy(
            currentSortBy = sortBy,
            filteredAppData = applyFilterAndSort(
                _uiState.value.appDataList,
                _uiState.value.currentFilter,
                sortBy
            )
        )
    }

    // ──────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        filterAppData(_uiState.value.currentFilter.copy(searchQuery = query.ifBlank { null }))
    }

    // ──────────────────────────────────────────
    // Selection
    // ──────────────────────────────────────────

    fun selectAppData(id: Long) {
        val current = _uiState.value.selectedIds.toMutableSet()
        if (id in current) current.remove(id) else current.add(id)
        _uiState.value = _uiState.value.copy(selectedIds = current)
    }

    fun selectAll() {
        val allIds = _uiState.value.filteredAppData.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedIds = allIds)
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedIds = emptySet())
    }

    // ──────────────────────────────────────────
    // Recovery
    // ──────────────────────────────────────────

    fun recoverSelected() {
        val selectedIds = _uiState.value.selectedIds
        if (selectedIds.isEmpty()) return

        val selected = _uiState.value.filteredAppData.filter { it.id in selectedIds }

        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isRecovering = true)
            when (val result = appDataRepository.recoverAppData(selected)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isRecovering = false,
                        selectedIds = emptySet(),
                        recoveredCount = result.data.size
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isRecovering = false,
                        error = result.message
                    )
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in AppDataRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Preview
    // ──────────────────────────────────────────

    fun previewAppData(id: Long) {
        val data = _uiState.value.filteredAppData.firstOrNull { it.id == id }
        _uiState.value = _uiState.value.copy(previewAppData = data)
    }

    fun clearPreview() {
        _uiState.value = _uiState.value.copy(previewAppData = null)
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
        items: List<AppDataEntity>,
        filter: AppDataFilter,
        sortBy: AppDataSortBy
    ): List<AppDataEntity> {
        var result = items

        if (filter.isSystemApp != null) {
            result = result.filter { it.isSystemApp == filter.isSystemApp }
        }

        result = when (sortBy) {
            AppDataSortBy.APP_NAME_A_Z -> result.sortedBy { it.appName.lowercase() }
            AppDataSortBy.APP_NAME_Z_A -> result.sortedByDescending { it.appName.lowercase() }
            AppDataSortBy.SIZE_LARGEST -> result.sortedByDescending { it.fileSize }
            AppDataSortBy.SIZE_SMALLEST -> result.sortedBy { it.fileSize }
            AppDataSortBy.DATE_NEWEST -> result.sortedByDescending { it.recoveryDate }
            AppDataSortBy.DATE_OLDEST -> result.sortedBy { it.recoveryDate }
            AppDataSortBy.TYPE -> result.sortedBy { it.dataType.name }
        }

        return result
    }
}
