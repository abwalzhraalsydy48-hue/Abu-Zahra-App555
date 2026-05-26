package com.ultimaterecovery.pro.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultimaterecovery.pro.data.local.entity.CallLogEntity
import com.ultimaterecovery.pro.data.local.entity.CallLogEntity.CallType
import com.ultimaterecovery.pro.data.repository.CallLogRepository
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
// Filter
// ──────────────────────────────────────────────

/**
 * Filter criteria for call log entries.
 */
data class CallLogFilter(
    val callType: CallType? = null,
    val number: String? = null,
    val contactName: String? = null,
    val dateStart: Long? = null,
    val dateEnd: Long? = null,
    val searchQuery: String? = null
)

// ──────────────────────────────────────────────
// UI State
// ──────────────────────────────────────────────

/**
 * UI state for the call log recovery screen.
 */
data class CallLogRecoveryUiState(
    val callLogs: List<CallLogEntity> = emptyList(),
    val filteredCallLogs: List<CallLogEntity> = emptyList(),
    val selectedLogIds: Set<Long> = emptySet(),
    val currentFilter: CallLogFilter = CallLogFilter(),
    val searchQuery: String = "",
    val uniqueNumbers: List<String> = emptyList(),
    val totalCount: Int = 0,
    val previewLog: CallLogEntity? = null,
    val isRecovering: Boolean = false,
    val isExporting: Boolean = false,
    val exportPath: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

// ──────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────

/**
 * ViewModel for the call log recovery screen.
 *
 * Provides:
 * - Reactive call log list from [CallLogRepository]
 * - Filter by call type, number, contact, and date range
 * - Full-text search
 * - Multi-select for batch recovery and export
 * - Export to TXT or PDF
 */
@HiltViewModel
class CallLogRecoveryViewModel @Inject constructor(
    private val callLogRepository: CallLogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CallLogRecoveryUiState(isLoading = true))
    val uiState: StateFlow<CallLogRecoveryUiState> = _uiState.asStateFlow()

    init {
        try {
        loadCallLogs()
        loadCount()
        loadUniqueNumbers()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize CallLogRecoveryViewModel")
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        } catch (_: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Initialization failed")
        }
    }

    // ──────────────────────────────────────────
    // Loading
    // ──────────────────────────────────────────

    fun loadCallLogs() {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isLoading = true)
            callLogRepository.getCallLogsByType(CallType.INCOMING)
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
                                callLogs = resource.data,
                                filteredCallLogs = applyFilter(resource.data, _uiState.value.currentFilter),
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
    Timber.e(e, "Database error in CallLogRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    private fun loadCount() {
        viewModelScope.launch {
            try {

            callLogRepository.getCount().collect { resource ->
                if (resource is Resource.Success) {
                    _uiState.value = _uiState.value.copy(totalCount = resource.data)
                }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in CallLogRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    private fun loadUniqueNumbers() {
        viewModelScope.launch {
            try {

            callLogRepository.getUniqueNumbers().collect { resource ->
                if (resource is Resource.Success) {
                    _uiState.value = _uiState.value.copy(uniqueNumbers = resource.data)
                }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in CallLogRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Filtering
    // ──────────────────────────────────────────

    fun filterCallLogs(filter: CallLogFilter) {
        _uiState.value = _uiState.value.copy(currentFilter = filter)

        viewModelScope.launch {
            try {

            when {
                filter.searchQuery != null && filter.searchQuery.isNotBlank() -> {
                    callLogRepository.searchCallLogs(filter.searchQuery)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredCallLogs = applyFilter(resource.data, filter)
                                )
                            }
                        }
                }
                filter.callType != null -> {
                    callLogRepository.getCallLogsByType(filter.callType)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredCallLogs = applyFilter(resource.data, filter)
                                )
                            }
                        }
                }
                filter.number != null && filter.number.isNotBlank() -> {
                    callLogRepository.getByNumber(filter.number)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredCallLogs = applyFilter(resource.data, filter)
                                )
                            }
                        }
                }
                filter.dateStart != null && filter.dateEnd != null -> {
                    callLogRepository.getByDateRange(filter.dateStart, filter.dateEnd)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredCallLogs = applyFilter(resource.data, filter)
                                )
                            }
                        }
                }
                filter.contactName != null && filter.contactName.isNotBlank() -> {
                    callLogRepository.getByContact(filter.contactName)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredCallLogs = applyFilter(resource.data, filter)
                                )
                            }
                        }
                }
                else -> {
                    _uiState.value = _uiState.value.copy(
                        filteredCallLogs = applyFilter(_uiState.value.callLogs, filter)
                    )
                }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in CallLogRecoveryViewModel")
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
        filterCallLogs(_uiState.value.currentFilter.copy(searchQuery = query.ifBlank { null }))
    }

    // ──────────────────────────────────────────
    // Selection
    // ──────────────────────────────────────────

    fun selectLog(id: Long) {
        val current = _uiState.value.selectedLogIds.toMutableSet()
        if (id in current) current.remove(id) else current.add(id)
        _uiState.value = _uiState.value.copy(selectedLogIds = current)
    }

    fun selectAll() {
        val allIds = _uiState.value.filteredCallLogs.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedLogIds = allIds)
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedLogIds = emptySet())
    }

    // ──────────────────────────────────────────
    // Recovery
    // ──────────────────────────────────────────

    fun recoverSelected() {
        val selectedIds = _uiState.value.selectedLogIds
        if (selectedIds.isEmpty()) return

        val selectedLogs = _uiState.value.filteredCallLogs
            .filter { it.id in selectedIds }

        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isRecovering = true)
            when (val result = callLogRepository.recoverCallLogs(selectedLogs)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isRecovering = false,
                        selectedLogIds = emptySet()
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
    Timber.e(e, "Database error in CallLogRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Preview
    // ──────────────────────────────────────────

    fun previewLog(id: Long) {
        val log = _uiState.value.filteredCallLogs.firstOrNull { it.id == id }
        _uiState.value = _uiState.value.copy(previewLog = log)
    }

    fun clearPreview() {
        _uiState.value = _uiState.value.copy(previewLog = null)
    }

    // ──────────────────────────────────────────
    // Export
    // ──────────────────────────────────────────

    fun exportToTxt() {
        val selected = if (_uiState.value.selectedLogIds.isNotEmpty()) {
            _uiState.value.filteredCallLogs.filter { it.id in _uiState.value.selectedLogIds }
        } else {
            _uiState.value.filteredCallLogs
        }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isExporting = true)
            when (val result = callLogRepository.exportToTxt(selected)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        exportPath = result.data
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        error = result.message
                    )
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in CallLogRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    fun exportToPdf() {
        val selected = if (_uiState.value.selectedLogIds.isNotEmpty()) {
            _uiState.value.filteredCallLogs.filter { it.id in _uiState.value.selectedLogIds }
        } else {
            _uiState.value.filteredCallLogs
        }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isExporting = true)
            when (val result = callLogRepository.exportToPdf(selected)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        exportPath = result.data
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        error = result.message
                    )
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in CallLogRecoveryViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Error
    // ──────────────────────────────────────────

    /**
     * Filters call logs by type (convenience method).
     */
    fun filterByType(type: com.ultimaterecovery.pro.data.local.entity.CallLogEntity.CallType?) {
        filterCallLogs(_uiState.value.currentFilter.copy(callType = type))
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // ──────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────

    private fun applyFilter(
        logs: List<CallLogEntity>,
        filter: CallLogFilter
    ): List<CallLogEntity> {
        var result = logs
        filter.dateStart?.let { start -> result = result.filter { it.date >= start } }
        filter.dateEnd?.let { end -> result = result.filter { it.date <= end } }
        if (!filter.number.isNullOrBlank()) {
            result = result.filter { it.number.contains(filter.number, ignoreCase = true) }
        }
        return result
    }
}
