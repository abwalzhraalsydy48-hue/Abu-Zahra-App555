package com.ultimaterecovery.pro.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultimaterecovery.pro.data.local.entity.BackupEntity
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.BackupStatus
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.BackupType
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.CloudProvider
import com.ultimaterecovery.pro.data.repository.BackupRepository
import com.ultimaterecovery.pro.data.repository.Resource
import com.ultimaterecovery.pro.utils.backup.BackupManager
import com.ultimaterecovery.pro.utils.backup.BackupManager.BackupConfig
import com.ultimaterecovery.pro.utils.backup.BackupManager.BackupProgress
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
 * UI state for the backup screen.
 */
data class BackupUiState(
    val backupHistory: List<BackupEntity> = emptyList(),
    val completedBackups: List<BackupEntity> = emptyList(),
    val currentProgress: BackupProgress? = null,
    val isCreating: Boolean = false,
    val isRestoring: Boolean = false,
    val isUploading: Boolean = false,
    val isDeleting: Boolean = false,
    val selectedBackupId: Long? = null,
    val backupConfig: BackupConfig = BackupConfig(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
)

// ──────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────

/**
 * ViewModel for the backup screen.
 *
 * Provides:
 * - Create backup with configurable type, encryption, and compression
 * - Restore from local or cloud backup
 * - Cloud upload/download (Google Drive, Dropbox)
 * - Backup history and completed backups listing
 * - Real-time progress tracking via [BackupProgress]
 * - Backup deletion (local + cloud)
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState(isLoading = true))
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    init {
        try {
        loadBackupHistory()
        loadCompletedBackups()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize BackupViewModel")
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        } catch (_: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Initialization failed")
        }
    }

    // ──────────────────────────────────────────
    // Loading
    // ──────────────────────────────────────────

    fun loadBackupHistory() {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isLoading = true)
            backupManager.getBackupHistory()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
                .collect { backups ->
                    _uiState.value = _uiState.value.copy(
                        backupHistory = backups.sortedByDescending { it.backupDate },
                        isLoading = false
                    )
                }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in BackupViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    private fun loadCompletedBackups() {
        viewModelScope.launch {
            try {

            backupRepository.getCompletedBackups()
                .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                .collect { resource ->
                    if (resource is Resource.Success) {
                        _uiState.value = _uiState.value.copy(
                            completedBackups = resource.data.sortedByDescending { it.backupDate }
                        )
                    }
                }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in BackupViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Configuration
    // ──────────────────────────────────────────

    fun updateConfig(config: BackupConfig) {
        _uiState.value = _uiState.value.copy(backupConfig = config)
    }

    fun setBackupType(type: BackupType) {
        _uiState.value = _uiState.value.copy(
            backupConfig = _uiState.value.backupConfig.copy(backupType = type)
        )
    }

    fun setCloudProvider(provider: CloudProvider) {
        _uiState.value = _uiState.value.copy(
            backupConfig = _uiState.value.backupConfig.copy(cloudProvider = provider)
        )
    }

    fun setEncryption(enabled: Boolean, password: String? = null) {
        _uiState.value = _uiState.value.copy(
            backupConfig = _uiState.value.backupConfig.copy(
                enableEncryption = enabled,
                password = password
            )
        )
    }

    // ──────────────────────────────────────────
    // Create Backup
    // ──────────────────────────────────────────

    /**
     * Creates a new backup using the current [BackupConfig].
     *
     * Emits real-time [BackupProgress] updates through the UI state.
     */
    fun createBackup() {
        val config = _uiState.value.backupConfig
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isCreating = true, currentProgress = null)

            backupManager.createBackup(config)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isCreating = false,
                        error = e.message
                    )
                }
                .collect { progress ->
                    _uiState.value = _uiState.value.copy(currentProgress = progress)

                    when (progress.phase) {
                        BackupProgress.Phase.COMPLETED -> {
                            _uiState.value = _uiState.value.copy(
                                isCreating = false,
                                successMessage = "Backup created successfully"
                            )
                        }
                        BackupProgress.Phase.FAILED -> {
                            _uiState.value = _uiState.value.copy(
                                isCreating = false,
                                error = "Backup creation failed"
                            )
                        }
                        else -> { /* in-progress phases */ }
                    }
                }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in BackupViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Restore
    // ──────────────────────────────────────────

    /**
     * Restores a backup identified by [backupId].
     *
     * For encrypted backups, [password] must be supplied.
     * Optionally restores to [outputDir] instead of original paths.
     */
    fun restoreBackup(backupId: Long, password: String? = null, outputDir: File? = null) {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(
                isRestoring = true,
                selectedBackupId = backupId,
                currentProgress = null
            )

            backupManager.restoreBackup(backupId, password, outputDir)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        error = e.message
                    )
                }
                .collect { progress ->
                    _uiState.value = _uiState.value.copy(currentProgress = progress)

                    when (progress.phase) {
                        BackupProgress.Phase.COMPLETED -> {
                            _uiState.value = _uiState.value.copy(
                                isRestoring = false,
                                successMessage = "Backup restored successfully"
                            )
                        }
                        BackupProgress.Phase.FAILED -> {
                            _uiState.value = _uiState.value.copy(
                                isRestoring = false,
                                error = "Backup restore failed"
                            )
                        }
                        else -> { /* in-progress phases */ }
                    }
                }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in BackupViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Cloud operations
    // ──────────────────────────────────────────

    /**
     * Uploads a local backup to a cloud provider.
     */
    fun uploadToCloud(backupId: Long, provider: CloudProvider) {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isUploading = true)
            when (val result = backupManager.uploadToCloud(backupId, provider)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        successMessage = "Backup uploaded to cloud"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        error = result.message
                    )
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in BackupViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    /**
     * Downloads a backup from a cloud provider.
     */
    fun downloadFromCloud(backupId: Long, provider: CloudProvider) {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isUploading = true)
            when (val result = backupManager.downloadFromCloud(backupId, provider)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        successMessage = "Backup downloaded from cloud"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        error = result.message
                    )
                }
                is Resource.Loading -> { /* keep state */ }
            }
        
            } catch (e: Exception) {
    Timber.e(e, "Database error in BackupViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Delete
    // ──────────────────────────────────────────

    fun deleteBackup(backupId: Long) {
        viewModelScope.launch {
            try {

            _uiState.value = _uiState.value.copy(isDeleting = true)
            when (val result = backupManager.deleteBackup(backupId)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        successMessage = "Backup deleted"
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
    Timber.e(e, "Database error in BackupViewModel")
    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (_: Throwable) {
    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected error")
            }
        }
    }

    // ──────────────────────────────────────────
    // Selection
    // ──────────────────────────────────────────

    fun selectBackup(id: Long) {
        _uiState.value = _uiState.value.copy(selectedBackupId = id)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedBackupId = null)
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
