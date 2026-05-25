package com.ultimaterecovery.pro.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultimaterecovery.pro.data.repository.RecoveryHistoryRepository
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
// Theme / Language / Security enums
// ──────────────────────────────────────────────

enum class AppTheme { LIGHT, DARK, SYSTEM }

enum class AppLanguage(val code: String, val displayName: String) {
    ENGLISH("en", "English"),
    ARABIC("ar", "العربية"),
    AUTO("auto", "System Default")
}

enum class SecurityLevel { NONE, PIN, BIOMETRIC, PIN_AND_BIOMETRIC }

// ──────────────────────────────────────────────
// UI State
// ──────────────────────────────────────────────

/**
 * UI state for the settings screen.
 */
data class SettingsUiState(
    // Appearance
    val theme: AppTheme = AppTheme.SYSTEM,
    val language: AppLanguage = AppLanguage.AUTO,
    val dynamicColor: Boolean = true,

    // Scanning
    val autoScanOnStart: Boolean = false,
    val defaultScanType: String = "QUICK",
    val scanNotificationEnabled: Boolean = true,

    // Recovery
    val autoSaveResults: Boolean = true,
    val defaultRecoveryPath: String = "",
    val overwriteExistingFiles: Boolean = false,
    val verifyIntegrityAfterRecovery: Boolean = true,

    // Recycle bin
    val recycleBinAutoDeleteDays: Int = 30,
    val recycleBinStorageLimitMb: Long = 500,
    val secureDeleteEnabled: Boolean = false,
    val recycleBinMonitoring: Boolean = true,

    // Backup
    val autoBackupEnabled: Boolean = false,
    val backupFrequency: String = "WEEKLY",
    val backupEncryptionEnabled: Boolean = false,
    val lastBackupDate: Long? = null,

    // Security
    val securityLevel: SecurityLevel = SecurityLevel.NONE,
    val biometricEnabled: Boolean = false,
    val lockOnAppSwitch: Boolean = false,

    // History
    val totalRecoveryCount: Int = 0,
    val totalRecoverySize: Long = 0L,
    val recoveryHistoryEnabled: Boolean = true,

    // About
    val appVersion: String = "",
    val buildNumber: String = "",

    // State
    val isClearingHistory: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
)

// ──────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────

/**
 * ViewModel for the settings screen.
 *
 * Manages all application preferences organized into sections:
 * - Appearance (theme, language, dynamic color)
 * - Scanning defaults
 * - Recovery options
 * - Recycle bin configuration
 * - Backup settings
 * - Security
 * - About
 *
 * Preferences are persisted via SharedPreferences and loaded
 * on initialization. Recovery stats are pulled from
 * [RecoveryHistoryRepository].
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val recoveryHistoryRepository: RecoveryHistoryRepository
) : AndroidViewModel(application) {

    private val prefs by lazy {
        application.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
    }

    private val _uiState = MutableStateFlow(SettingsUiState(isLoading = true))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        loadRecoveryStats()
        loadAppInfo()
    }

    // ──────────────────────────────────────────
    // Load persisted settings
    // ──────────────────────────────────────────

    private fun loadSettings() {
        _uiState.value = _uiState.value.copy(
            // Appearance
            theme = AppTheme.valueOf(prefs.getString("theme", AppTheme.SYSTEM.name) ?: AppTheme.SYSTEM.name),
            language = AppLanguage.values().firstOrNull {
                it.code == prefs.getString("language", AppLanguage.AUTO.code)
            } ?: AppLanguage.AUTO,
            dynamicColor = prefs.getBoolean("dynamic_color", true),

            // Scanning
            autoScanOnStart = prefs.getBoolean("auto_scan_on_start", false),
            defaultScanType = prefs.getString("default_scan_type", "QUICK") ?: "QUICK",
            scanNotificationEnabled = prefs.getBoolean("scan_notification_enabled", true),

            // Recovery
            autoSaveResults = prefs.getBoolean("auto_save_results", true),
            defaultRecoveryPath = prefs.getString("default_recovery_path", "") ?: "",
            overwriteExistingFiles = prefs.getBoolean("overwrite_existing_files", false),
            verifyIntegrityAfterRecovery = prefs.getBoolean("verify_integrity", true),

            // Recycle bin
            recycleBinAutoDeleteDays = prefs.getInt("recycle_bin_auto_delete_days", 30),
            recycleBinStorageLimitMb = prefs.getLong("recycle_bin_storage_limit_mb", 500),
            secureDeleteEnabled = prefs.getBoolean("secure_delete_enabled", false),
            recycleBinMonitoring = prefs.getBoolean("recycle_bin_monitoring", true),

            // Backup
            autoBackupEnabled = prefs.getBoolean("auto_backup_enabled", false),
            backupFrequency = prefs.getString("backup_frequency", "WEEKLY") ?: "WEEKLY",
            backupEncryptionEnabled = prefs.getBoolean("backup_encryption_enabled", false),
            lastBackupDate = if (prefs.contains("last_backup_date")) prefs.getLong("last_backup_date", 0L) else null,

            // Security
            securityLevel = SecurityLevel.valueOf(
                prefs.getString("security_level", SecurityLevel.NONE.name) ?: SecurityLevel.NONE.name
            ),
            biometricEnabled = prefs.getBoolean("biometric_enabled", false),
            lockOnAppSwitch = prefs.getBoolean("lock_on_app_switch", false),

            // History
            recoveryHistoryEnabled = prefs.getBoolean("recovery_history_enabled", true),

            isLoading = false
        )
    }

    private fun loadRecoveryStats() {
        viewModelScope.launch {
            try {
                recoveryHistoryRepository.getStats()
                    .catch { e ->
                        Timber.e(e, "Failed to load recovery stats")
                        _uiState.value = _uiState.value.copy(
                            totalRecoveryCount = 0,
                            totalRecoverySize = 0L,
                            isLoading = false
                        )
                    }
                    .collect { resource ->
                        if (resource is Resource.Success) {
                            _uiState.value = _uiState.value.copy(
                                totalRecoveryCount = resource.data.totalRecoveredCount,
                                totalRecoverySize = resource.data.totalRecoveredSize
                            )
                        } else if (resource is Resource.Error) {
                            _uiState.value = _uiState.value.copy(
                                totalRecoveryCount = 0,
                                totalRecoverySize = 0L,
                                isLoading = false
                            )
                        }
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load recovery stats")
                _uiState.value = _uiState.value.copy(
                    totalRecoveryCount = 0,
                    totalRecoverySize = 0L,
                    isLoading = false
                )
            } catch (_: Throwable) {
                // Catch SQLiteException and other Errors
                _uiState.value = _uiState.value.copy(
                    totalRecoveryCount = 0,
                    totalRecoverySize = 0L,
                    isLoading = false
                )
            }
        }
    }

    private fun loadAppInfo() {
        val context = getApplication<Application>()
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            _uiState.value = _uiState.value.copy(
                appVersion = packageInfo.versionName ?: "Unknown",
                buildNumber = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toString()
                }
            )
        } catch (_: Exception) {
            _uiState.value = _uiState.value.copy(appVersion = "Unknown")
        }
    }

    // ──────────────────────────────────────────
    // Appearance
    // ──────────────────────────────────────────

    fun setTheme(theme: AppTheme) {
        prefs.edit().putString("theme", theme.name).apply()
        _uiState.value = _uiState.value.copy(theme = theme)
    }

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString("language", language.code).apply()
        _uiState.value = _uiState.value.copy(language = language)
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
        _uiState.value = _uiState.value.copy(dynamicColor = enabled)
    }

    // ──────────────────────────────────────────
    // Scanning
    // ──────────────────────────────────────────

    fun setAutoScanOnStart(enabled: Boolean) {
        prefs.edit().putBoolean("auto_scan_on_start", enabled).apply()
        _uiState.value = _uiState.value.copy(autoScanOnStart = enabled)
    }

    fun setDefaultScanType(type: String) {
        prefs.edit().putString("default_scan_type", type).apply()
        _uiState.value = _uiState.value.copy(defaultScanType = type)
    }

    fun setScanNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("scan_notification_enabled", enabled).apply()
        _uiState.value = _uiState.value.copy(scanNotificationEnabled = enabled)
    }

    // ──────────────────────────────────────────
    // Recovery
    // ──────────────────────────────────────────

    fun setAutoSaveResults(enabled: Boolean) {
        prefs.edit().putBoolean("auto_save_results", enabled).apply()
        _uiState.value = _uiState.value.copy(autoSaveResults = enabled)
    }

    fun setDefaultRecoveryPath(path: String) {
        prefs.edit().putString("default_recovery_path", path).apply()
        _uiState.value = _uiState.value.copy(defaultRecoveryPath = path)
    }

    fun setOverwriteExistingFiles(enabled: Boolean) {
        prefs.edit().putBoolean("overwrite_existing_files", enabled).apply()
        _uiState.value = _uiState.value.copy(overwriteExistingFiles = enabled)
    }

    fun setVerifyIntegrity(enabled: Boolean) {
        prefs.edit().putBoolean("verify_integrity", enabled).apply()
        _uiState.value = _uiState.value.copy(verifyIntegrityAfterRecovery = enabled)
    }

    // ──────────────────────────────────────────
    // Recycle bin
    // ──────────────────────────────────────────

    fun setRecycleBinAutoDeleteDays(days: Int) {
        prefs.edit().putInt("recycle_bin_auto_delete_days", days).apply()
        _uiState.value = _uiState.value.copy(recycleBinAutoDeleteDays = days)
    }

    fun setRecycleBinStorageLimit(mb: Long) {
        prefs.edit().putLong("recycle_bin_storage_limit_mb", mb).apply()
        _uiState.value = _uiState.value.copy(recycleBinStorageLimitMb = mb)
    }

    fun setSecureDeleteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("secure_delete_enabled", enabled).apply()
        _uiState.value = _uiState.value.copy(secureDeleteEnabled = enabled)
    }

    fun setRecycleBinMonitoring(enabled: Boolean) {
        prefs.edit().putBoolean("recycle_bin_monitoring", enabled).apply()
        _uiState.value = _uiState.value.copy(recycleBinMonitoring = enabled)
    }

    // ──────────────────────────────────────────
    // Backup
    // ──────────────────────────────────────────

    fun setAutoBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_backup_enabled", enabled).apply()
        _uiState.value = _uiState.value.copy(autoBackupEnabled = enabled)
    }

    fun setBackupFrequency(frequency: String) {
        prefs.edit().putString("backup_frequency", frequency).apply()
        _uiState.value = _uiState.value.copy(backupFrequency = frequency)
    }

    fun setBackupEncryptionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("backup_encryption_enabled", enabled).apply()
        _uiState.value = _uiState.value.copy(backupEncryptionEnabled = enabled)
    }

    // ──────────────────────────────────────────
    // Security
    // ──────────────────────────────────────────

    fun setSecurityLevel(level: SecurityLevel) {
        prefs.edit().putString("security_level", level.name).apply()
        _uiState.value = _uiState.value.copy(securityLevel = level)
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("biometric_enabled", enabled).apply()
        _uiState.value = _uiState.value.copy(biometricEnabled = enabled)
    }

    fun setLockOnAppSwitch(enabled: Boolean) {
        prefs.edit().putBoolean("lock_on_app_switch", enabled).apply()
        _uiState.value = _uiState.value.copy(lockOnAppSwitch = enabled)
    }

    // ──────────────────────────────────────────
    // History
    // ──────────────────────────────────────────

    fun setRecoveryHistoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("recovery_history_enabled", enabled).apply()
        _uiState.value = _uiState.value.copy(recoveryHistoryEnabled = enabled)
    }

    fun clearRecoveryHistory() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isClearingHistory = true)
            when (val result = recoveryHistoryRepository.clearHistory()) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isClearingHistory = false,
                        totalRecoveryCount = 0,
                        totalRecoverySize = 0L,
                        successMessage = "Recovery history cleared"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isClearingHistory = false,
                        error = result.message
                    )
                }
                is Resource.Loading -> { /* keep state */ }
            }
        }
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
