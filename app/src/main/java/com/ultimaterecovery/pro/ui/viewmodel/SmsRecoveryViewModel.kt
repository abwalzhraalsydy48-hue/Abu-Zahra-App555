package com.ultimaterecovery.pro.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity.SmsType
import com.ultimaterecovery.pro.data.repository.Resource
import com.ultimaterecovery.pro.data.repository.SmsRepository
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
 * Filter criteria for SMS messages.
 */
data class SmsFilter(
    val type: SmsType? = null,
    val address: String? = null,
    val contactName: String? = null,
    val dateStart: Long? = null,
    val dateEnd: Long? = null,
    val searchQuery: String? = null
)

// ──────────────────────────────────────────────
// UI State
// ──────────────────────────────────────────────

/**
 * UI state for the SMS recovery screen.
 */
data class SmsRecoveryUiState(
    val messages: List<SmsMessageEntity> = emptyList(),
    val filteredMessages: List<SmsMessageEntity> = emptyList(),
    val selectedMessageIds: Set<Long> = emptySet(),
    val currentFilter: SmsFilter = SmsFilter(),
    val searchQuery: String = "",
    val uniqueAddresses: List<String> = emptyList(),
    val totalMessageCount: Int = 0,
    val previewMessage: SmsMessageEntity? = null,
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
 * ViewModel for the SMS recovery screen.
 *
 * Provides:
 * - Reactive SMS message list from [SmsRepository]
 * - Filter by type, address, contact, and date range
 * - Full-text search across body and address fields
 * - Multi-select for batch recovery and export
 * - Export to TXT or PDF
 */
@HiltViewModel
class SmsRecoveryViewModel @Inject constructor(
    private val smsRepository: SmsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmsRecoveryUiState(isLoading = true))
    val uiState: StateFlow<SmsRecoveryUiState> = _uiState.asStateFlow()

    init {
        try {
            loadMessages()
            loadMessageCount()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize SmsRecoveryViewModel")
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        } catch (_: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Initialization failed")
        }
    }

    // ──────────────────────────────────────────
    // Loading
    // ──────────────────────────────────────────

    /**
     * Loads all recovered SMS messages.
     */
    fun loadMessages() {
        viewModelScope.launch {
            try {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // Start with all messages of INBOX type as default, then
            // the user can apply filters.
            smsRepository.getMessagesByType(SmsType.INBOX)
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
                                messages = resource.data,
                                filteredMessages = applyFilter(resource.data, _uiState.value.currentFilter),
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
                Timber.e(e, "Failed to load SMS messages")
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed to load messages")
            }
        }
    }

    private fun loadMessageCount() {
        viewModelScope.launch {
            try {
                smsRepository.getCount().collect { resource ->
                    if (resource is Resource.Success) {
                        _uiState.value = _uiState.value.copy(totalMessageCount = resource.data)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load SMS count")
            } catch (_: Throwable) {}
        }
    }

    // ──────────────────────────────────────────
    // Filtering
    // ──────────────────────────────────────────

    /**
     * Applies a filter and reloads data from the repository
     * when the filter targets a server-side queryable field.
     */
    fun filterMessages(filter: SmsFilter) {
        _uiState.value = _uiState.value.copy(currentFilter = filter)

        viewModelScope.launch {
            try {
            when {
                filter.searchQuery != null && filter.searchQuery.isNotBlank() -> {
                    smsRepository.searchMessages(filter.searchQuery)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredMessages = applyFilter(resource.data, filter)
                                )
                            }
                        }
                }
                filter.type != null -> {
                    smsRepository.getMessagesByType(filter.type)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredMessages = applyFilter(resource.data, filter)
                                )
                            }
                        }
                }
                filter.address != null && filter.address.isNotBlank() -> {
                    smsRepository.getMessagesByAddress(filter.address)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredMessages = applyFilter(resource.data, filter)
                                )
                            }
                        }
                }
                filter.contactName != null && filter.contactName.isNotBlank() -> {
                    smsRepository.getMessagesByContact(filter.contactName)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredMessages = applyFilter(resource.data, filter)
                                )
                            }
                        }
                }
                filter.dateStart != null && filter.dateEnd != null -> {
                    smsRepository.getMessagesByDateRange(filter.dateStart, filter.dateEnd)
                        .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                        .collect { resource ->
                            if (resource is Resource.Success) {
                                _uiState.value = _uiState.value.copy(
                                    filteredMessages = applyFilter(resource.data, filter)
                                )
                            }
                        }
                }
                else -> {
                    // No server-side filter; apply client-side
                    _uiState.value = _uiState.value.copy(
                        filteredMessages = applyFilter(_uiState.value.messages, filter)
                    )
                }
            }
            } catch (e: Exception) {
                Timber.e(e, "Failed to filter SMS messages")
                _uiState.value = _uiState.value.copy(error = e.message)
            } catch (_: Throwable) {
                _uiState.value = _uiState.value.copy(error = "Filter failed")
            }
        }
    }

    // ──────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────

    /**
     * Sets the search query and triggers a search.
     */
    fun search(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        filterMessages(_uiState.value.currentFilter.copy(searchQuery = query.ifBlank { null }))
    }

    // ──────────────────────────────────────────
    // Selection
    // ──────────────────────────────────────────

    fun selectMessage(id: Long) {
        val current = _uiState.value.selectedMessageIds.toMutableSet()
        if (id in current) current.remove(id) else current.add(id)
        _uiState.value = _uiState.value.copy(selectedMessageIds = current)
    }

    fun selectAll() {
        val allIds = _uiState.value.filteredMessages.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedMessageIds = allIds)
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedMessageIds = emptySet())
    }

    // ──────────────────────────────────────────
    // Recovery
    // ──────────────────────────────────────────

    /**
     * Recovers the selected SMS messages by persisting them
     * with `isRecovered = true` in the database.
     */
    fun recoverSelected() {
        val selectedIds = _uiState.value.selectedMessageIds
        if (selectedIds.isEmpty()) return

        val selectedMessages = _uiState.value.filteredMessages
            .filter { it.id in selectedIds }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isRecovering = true)
                when (val result = smsRepository.recoverMessages(selectedMessages)) {
                    is Resource.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isRecovering = false,
                            selectedMessageIds = emptySet()
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
                Timber.e(e, "Failed to recover SMS messages")
                _uiState.value = _uiState.value.copy(isRecovering = false, error = e.message)
            } catch (_: Throwable) {
                _uiState.value = _uiState.value.copy(isRecovering = false, error = "Recovery failed")
            }
        }
    }

    // ──────────────────────────────────────────
    // Preview
    // ──────────────────────────────────────────

    fun previewMessage(id: Long) {
        val msg = _uiState.value.filteredMessages.firstOrNull { it.id == id }
        _uiState.value = _uiState.value.copy(previewMessage = msg)
    }

    fun clearPreview() {
        _uiState.value = _uiState.value.copy(previewMessage = null)
    }

    // ──────────────────────────────────────────
    // Export
    // ──────────────────────────────────────────

    /**
     * Exports the selected messages to a plain-text file.
     */
    fun exportToTxt() {
        val selected = if (_uiState.value.selectedMessageIds.isNotEmpty()) {
            _uiState.value.filteredMessages.filter { it.id in _uiState.value.selectedMessageIds }
        } else {
            _uiState.value.filteredMessages
        }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isExporting = true)
                when (val result = smsRepository.exportToTxt(selected)) {
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
                Timber.e(e, "Failed to export SMS to TXT")
                _uiState.value = _uiState.value.copy(isExporting = false, error = e.message)
            } catch (_: Throwable) {
                _uiState.value = _uiState.value.copy(isExporting = false, error = "Export failed")
            }
        }
    }

    /**
     * Exports the selected messages to a PDF file.
     */
    fun exportToPdf() {
        val selected = if (_uiState.value.selectedMessageIds.isNotEmpty()) {
            _uiState.value.filteredMessages.filter { it.id in _uiState.value.selectedMessageIds }
        } else {
            _uiState.value.filteredMessages
        }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isExporting = true)
                when (val result = smsRepository.exportToPdf(selected)) {
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
                Timber.e(e, "Failed to export SMS to PDF")
                _uiState.value = _uiState.value.copy(isExporting = false, error = e.message)
            } catch (_: Throwable) {
                _uiState.value = _uiState.value.copy(isExporting = false, error = "Export failed")
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

    private fun applyFilter(
        messages: List<SmsMessageEntity>,
        filter: SmsFilter
    ): List<SmsMessageEntity> {
        var result = messages

        // Client-side date range filter (complements server-side if both applied)
        filter.dateStart?.let { start -> result = result.filter { it.date >= start } }
        filter.dateEnd?.let { end -> result = result.filter { it.date <= end } }

        // Client-side address filter (supplementary)
        if (filter.address != null && filter.address.isNotBlank()) {
            result = result.filter { it.address.contains(filter.address, ignoreCase = true) }
        }

        return result
    }
}
