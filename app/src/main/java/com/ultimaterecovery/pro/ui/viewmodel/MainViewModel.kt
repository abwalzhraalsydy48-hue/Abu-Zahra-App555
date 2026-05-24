package com.ultimaterecovery.pro.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.data.repository.RecoveryHistoryRepository
import com.ultimaterecovery.pro.data.repository.RecoveredFileRepository
import com.ultimaterecovery.pro.data.repository.Resource
import com.ultimaterecovery.pro.data.repository.ScanSessionRepository
import com.ultimaterecovery.pro.engine.root.RootManager
import com.ultimaterecovery.pro.engine.root.RootState
import com.ultimaterecovery.pro.engine.scanner.IScanEngine
import com.ultimaterecovery.pro.manager.FileManager
import com.ultimaterecovery.pro.manager.FileManager.StorageInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

// ──────────────────────────────────────────────
// UI State
// ──────────────────────────────────────────────

/**
 * UI state for the main screen.
 *
 * Aggregates storage information, quick stats, root status,
 * and loading/error indicators into a single immutable snapshot
 * that the UI can observe.
 */
data class MainUiState(
    val storageInfo: List<StorageInfo> = emptyList(),
    val totalRecovered: Int = 0,
    val totalRecoveredSize: Long = 0L,
    val lastScanDate: Long? = null,
    val lastScanType: ScanType? = null,
    val categoryCounts: Map<FileCategory, Int> = emptyMap(),
    val rootState: RootState = RootState.Unknown,
    val isRootAvailable: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * One-shot navigation or notification events emitted from the ViewModel.
 *
 * Using [SharedFlow] ensures that configuration changes (e.g. rotation)
 * don't re-trigger navigation.
 */
sealed class MainNavigationEvent {
    data class NavigateToScan(val scanType: ScanType = ScanType.QUICK) : MainNavigationEvent()
    data class NavigateToRecovery(val category: FileCategory) : MainNavigationEvent()
    data object NavigateToDeepScan : MainNavigationEvent()
    data class ShowMessage(val message: String) : MainNavigationEvent()
}

// ──────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────

/**
 * ViewModel for the main/dashboard screen.
 *
 * Exposes [MainUiState] as a [StateFlow] and provides methods for:
 * - Loading storage info and quick stats
 * - Checking root access
 * - Triggering quick and deep scans
 * - Navigating to scan/recovery modules
 *
 * All long-running work is performed on [viewModelScope] with
 * IO-dispatched coroutines, and results are reduced into a single
 * [MainUiState] emission for deterministic UI rendering.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val recoveredFileRepository: RecoveredFileRepository,
    private val recoveryHistoryRepository: RecoveryHistoryRepository,
    private val scanSessionRepository: ScanSessionRepository,
    private val scanEngine: IScanEngine,
    private val rootManager: RootManager,
    private val fileManager: FileManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState(isLoading = true))
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<MainNavigationEvent>()
    val navigationEvents: SharedFlow<MainNavigationEvent> = _navigationEvents.asSharedFlow()

    // ──────────────────────────────────────────
    // Initialization
    // ──────────────────────────────────────────

    init {
        try {
            loadStorageInfo()
        } catch (e: Exception) {
            Timber.e(e, "Failed to load storage info")
        }
        try {
            loadQuickStats()
        } catch (e: Exception) {
            Timber.e(e, "Failed to load quick stats")
        }
        try {
            checkRootStatus()
        } catch (e: Exception) {
            Timber.e(e, "Failed to check root status")
        }
        try {
            observeRootState()
        } catch (e: Exception) {
            Timber.e(e, "Failed to observe root state")
        }
        try {
            observeLastScan()
        } catch (e: Exception) {
            Timber.e(e, "Failed to observe last scan")
        }
    }

    // ──────────────────────────────────────────
    // Storage
    // ──────────────────────────────────────────

    /**
     * Loads storage volume information (internal + SD card).
     */
    fun loadStorageInfo() {
        viewModelScope.launch {
            try {
                val volumes = withContext(Dispatchers.IO) {
                    fileManager.getStorageVolumes()
                }
                _uiState.value = _uiState.value.copy(storageInfo = volumes)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load storage volumes")
                _uiState.value = _uiState.value.copy(
                    storageInfo = emptyList(),
                    error = "Failed to load storage info"
                )
            }
        }
    }

    // ──────────────────────────────────────────
    // Quick Stats
    // ──────────────────────────────────────────

    /**
     * Loads aggregate recovery statistics: total count, total size,
     * per-category counts.
     */
    fun loadQuickStats() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)

                // Collect total recovered file stats
                recoveredFileRepository.getStats().catch { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message,
                        isLoading = false
                    )
                }.collect { resource ->
                    when (resource) {
                        is Resource.Success -> {
                            _uiState.value = _uiState.value.copy(
                                totalRecovered = resource.data.totalCount,
                                totalRecoveredSize = resource.data.totalSize,
                                isLoading = false
                            )
                        }
                        is Resource.Error -> {
                            _uiState.value = _uiState.value.copy(
                                error = resource.message,
                                isLoading = false
                            )
                        }
                        is Resource.Loading -> { /* keep current loading state */ }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load quick stats")
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }

        // Collect per-category file counts
        viewModelScope.launch {
            try {
                val categoryMap = mutableMapOf<FileCategory, Int>()
                for (category in FileCategory.entries) {
                    recoveredFileRepository.getFilesByCategory(category).catch { e ->
                        Timber.e(e, "Failed to load category: $category")
                    }.collect { resource ->
                        if (resource is Resource.Success) {
                            categoryMap[category] = resource.data.size
                            _uiState.value = _uiState.value.copy(
                                categoryCounts = categoryMap.toMap()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load category counts")
            }
        }
    }

    // ──────────────────────────────────────────
    // Root Status
    // ──────────────────────────────────────────

    /**
     * Checks whether the device has root access.
     */
    fun checkRootStatus() {
        viewModelScope.launch {
            try {
                val result = rootManager.isRootAvailable()
                _uiState.value = _uiState.value.copy(
                    isRootAvailable = result.isRooted
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to check root status")
                _uiState.value = _uiState.value.copy(isRootAvailable = false)
            }
        }
    }

    /**
     * Observes the reactive [RootManager.rootState] flow so the UI
     * automatically reflects changes when the user grants or revokes root.
     */
    private fun observeRootState() {
        viewModelScope.launch {
            rootManager.rootState.catch { e ->
                Timber.e(e, "Failed to observe root state")
            }.collect { state ->
                _uiState.value = _uiState.value.copy(
                    rootState = state,
                    isRootAvailable = state is RootState.Granted || state is RootState.Available
                )
            }
        }
    }

    // ──────────────────────────────────────────
    // Last Scan
    // ──────────────────────────────────────────

    /**
     * Observes the most recently completed scan session to display
     * "Last scan" information on the dashboard.
     */
    private fun observeLastScan() {
        viewModelScope.launch {
            scanSessionRepository.getCompletedSessions().catch { e ->
                Timber.e(e, "Failed to observe last scan")
            }.collect { resource ->
                when (resource) {
                    is Resource.Success -> {
                        val lastSession = resource.data.maxByOrNull { it.endTime ?: 0L }
                        _uiState.value = _uiState.value.copy(
                            lastScanDate = lastSession?.endTime,
                            lastScanType = lastSession?.scanType
                        )
                    }
                    else -> { /* ignore */ }
                }
            }
        }
    }

    // ──────────────────────────────────────────
    // Scan triggers
    // ──────────────────────────────────────────

    /**
     * Starts a quick scan using the [IScanEngine].
     *
     * The scan runs on the default storage paths. On completion
     * the user is navigated to the results screen.
     */
    fun startQuickScan() {
        viewModelScope.launch {
            val paths = listOf(
                android.os.Environment.getExternalStorageDirectory().absolutePath
            )
            scanEngine.startQuickScan(paths).catch { e ->
                _navigationEvents.emit(
                    MainNavigationEvent.ShowMessage("Quick scan failed: ${e.message}")
                )
            }.collect { /* ScanEngine updates its own StateFlow; UI observes that */ }
            _navigationEvents.emit(MainNavigationEvent.NavigateToScan(ScanType.QUICK))
        }
    }

    /**
     * Starts a deep scan (requires root on most devices).
     */
    fun startDeepScan() {
        viewModelScope.launch {
            _navigationEvents.emit(MainNavigationEvent.NavigateToDeepScan)
        }
    }

    // ──────────────────────────────────────────
    // Navigation helpers
    // ──────────────────────────────────────────

    /**
     * Requests navigation to a specific recovery module.
     */
    fun navigateToRecovery(category: FileCategory) {
        viewModelScope.launch {
            _navigationEvents.emit(MainNavigationEvent.NavigateToRecovery(category))
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
