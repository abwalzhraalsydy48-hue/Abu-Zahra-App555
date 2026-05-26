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
import com.ultimaterecovery.pro.engine.recovery.RecoveryConfidence
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ──────────────────────────────────────────────
// UI State - حالة واجهة المستخدم
// ──────────────────────────────────────────────

/**
 * Represents the UI state for the scan results screen.
 *
 * يحتوي على جميع البيانات اللازمة لعرض نتائج المسح
 */
data class ScanResultsUiState(
    // قائمة الملفات المستردة
    val allFiles: List<RecoveredFileEntity> = emptyList(),
    // الملفات المفلترة حسب الفئة المحددة
    val filteredFiles: List<RecoveredFileEntity> = emptyList(),
    // الفئة المحددة حالياً
    val selectedCategory: FileCategory? = null,
    // معرفات الملفات المحددة للاستعادة
    val selectedFileIds: Set<Long> = emptySet(),
    // حالة التحميل
    val isLoading: Boolean = true,
    // حالة الاستعادة
    val isRecovering: Boolean = false,
    // تقدم الاستعادة
    val recoveryProgress: Int = 0,
    // رسالة الخطأ
    val error: String? = null,
    // رسالة النجاح
    val successMessage: String? = null,
    // عدد الملفات حسب الفئة
    val categoryCounts: Map<FileCategory, Int> = emptyMap(),
    // الحجم الإجمالي للملفات المحددة
    val selectedTotalSize: Long = 0L,
    // وضع تحديد الكل
    val isAllSelected: Boolean = false
)

// ──────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────

/**
 * ViewModel for ScanResultsActivity.
 *
 * يدير حالة واجهة عرض نتائج المسح ويدعم:
 * - فلترة الملفات حسب الفئة
 * - التحديد المتعدد للملفات
 * - استعادة الملفات المحددة
 * - حساب إحصائيات الملفات
 */
@HiltViewModel
class ScanResultsViewModel @Inject constructor(
    private val recoveredFileRepository: RecoveredFileRepository,
    private val fileRecoveryEngine: FileRecoveryEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanResultsUiState())
    val uiState: StateFlow<ScanResultsUiState> = _uiState.asStateFlow()

    // معرف جلسة المسح الحالية
    private var currentSessionId: Long = -1L

    // ──────────────────────────────────────────
    // تحميل البيانات
    // ──────────────────────────────────────────

    /**
     * Loads recovered files from the specified scan session.
     *
     * يحمل الملفات المستردة من جلسة المسح المحددة
     */
    fun loadFiles(sessionId: Long) {
        currentSessionId = sessionId
        if (sessionId > 0) {
            loadFilesBySession(sessionId)
        } else {
            loadAllFiles()
        }
    }

    /**
     * Loads files by session ID from the repository.
     *
     * يحمل الملفات حسب معرف جلسة المسح
     */
    private fun loadFilesBySession(sessionId: Long) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }

                // الحصول على الملفات حسب معرف الجلسة
                recoveredFileRepository.getFilesBySession(sessionId)
                    .catch { e ->
                        Timber.e(e, "Error loading recovered files by session")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = e.message ?: "Failed to load files"
                            )
                        }
                    }
                    .collect { resource ->
                        when (resource) {
                            is Resource.Success -> {
                                val files = resource.data ?: emptyList()
                                val categoryCounts = files.groupingBy { it.category }.eachCount()

                                Timber.d("Loaded ${files.size} files for session $sessionId, categories: $categoryCounts")

                                _uiState.update { state ->
                                    state.copy(
                                        allFiles = files,
                                        filteredFiles = if (state.selectedCategory != null) {
                                            files.filter { file -> file.category == state.selectedCategory }
                                        } else {
                                            files
                                        },
                                        categoryCounts = categoryCounts,
                                        isLoading = false,
                                        error = null
                                    )
                                }
                                updateSelectedSize()
                            }
                            is Resource.Error -> {
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        error = resource.message
                                    )
                                }
                            }
                            is Resource.Loading -> {
                                // 保持当前加载状态
                            }
                        }
                    }
            } catch (e: Exception) {
                Timber.e(e, "Error in loadFilesBySession")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "An unexpected error occurred"
                    )
                }
            }
        }
    }

    /**
     * Loads all recovered files from the repository.
     *
     * يحمل جميع الملفات المستردة من المستودع
     */
    private fun loadAllFiles() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }

                // الحصول على الملفات حسب حالة الاسترداد
                recoveredFileRepository.getFilesByStatus(RecoveryStatus.PENDING)
                    .catch { e ->
                        Timber.e(e, "Error loading recovered files")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = e.message ?: "Failed to load files"
                            )
                        }
                    }
                    .collect { resource ->
                        when (resource) {
                            is Resource.Success -> {
                                val files = resource.data ?: emptyList()
                                val categoryCounts = files.groupingBy { it.category }.eachCount()

                                _uiState.update { state ->
                                    state.copy(
                                        allFiles = files,
                                        filteredFiles = if (state.selectedCategory != null) {
                                            files.filter { file -> file.category == state.selectedCategory }
                                        } else {
                                            files
                                        },
                                        categoryCounts = categoryCounts,
                                        isLoading = false,
                                        error = null
                                    )
                                }
                                updateSelectedSize()
                            }
                            is Resource.Error -> {
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        error = resource.message
                                    )
                                }
                            }
                            is Resource.Loading -> {
                                // 保持当前加载状态
                            }
                        }
                    }
            } catch (e: Exception) {
                Timber.e(e, "Error in loadAllFiles")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "An unexpected error occurred"
                    )
                }
            }
        }
    }

    /**
     * Loads files directly from a list (used when passing results from scan).
     *
     * يحمل الملفات مباشرة من قائمة (يستخدم عند تمرير النتائج من المسح)
     */
    fun loadFilesFromList(files: List<RecoveredFileEntity>) {
        val categoryCounts = files.groupingBy { it.category }.eachCount()

        _uiState.update { state ->
            state.copy(
                allFiles = files,
                filteredFiles = if (state.selectedCategory != null) {
                    files.filter { file -> file.category == state.selectedCategory }
                } else {
                    files
                },
                categoryCounts = categoryCounts,
                isLoading = false,
                error = null
            )
        }
        updateSelectedSize()
    }

    // ──────────────────────────────────────────
    // الفلترة والتحديد
    // ──────────────────────────────────────────

    /**
     * Filters files by the specified category.
     *
     * يفلتر الملفات حسب الفئة المحددة
     */
    fun filterByCategory(category: FileCategory?) {
        _uiState.update { state ->
            val filtered = if (category != null) {
                state.allFiles.filter { it.category == category }
            } else {
                state.allFiles
            }
            state.copy(
                selectedCategory = category,
                filteredFiles = filtered,
                selectedFileIds = emptySet(),
                isAllSelected = false
            )
        }
        updateSelectedSize()
    }

    /**
     * Toggles selection for a file.
     *
     * يبدل حالة تحديد ملف
     */
    fun toggleFileSelection(fileId: Long) {
        _uiState.update { state ->
            val newSelectedIds = if (fileId in state.selectedFileIds) {
                state.selectedFileIds - fileId
            } else {
                state.selectedFileIds + fileId
            }
            val isAllSelected = newSelectedIds.size == state.filteredFiles.size &&
                                state.filteredFiles.isNotEmpty()
            state.copy(
                selectedFileIds = newSelectedIds,
                isAllSelected = isAllSelected
            )
        }
        updateSelectedSize()
    }

    /**
     * Selects all files in the current filter.
     *
     * يحدد جميع الملفات في الفلتر الحالي
     */
    fun selectAll() {
        _uiState.update { state ->
            val allIds = state.filteredFiles.map { it.id }.toSet()
            state.copy(
                selectedFileIds = allIds,
                isAllSelected = true
            )
        }
        updateSelectedSize()
    }

    /**
     * Deselects all files.
     *
     * يلغي تحديد جميع الملفات
     */
    fun deselectAll() {
        _uiState.update { state ->
            state.copy(
                selectedFileIds = emptySet(),
                isAllSelected = false
            )
        }
        updateSelectedSize()
    }

    /**
     * Updates the total size of selected files.
     *
     * يحدث الحجم الإجمالي للملفات المحددة
     */
    private fun updateSelectedSize() {
        _uiState.update { state ->
            val totalSize = state.allFiles
                .filter { it.id in state.selectedFileIds }
                .sumOf { it.fileSize }
            state.copy(selectedTotalSize = totalSize)
        }
    }

    // ──────────────────────────────────────────
    // الاستعادة
    // ──────────────────────────────────────────

    /**
     * Recovers all selected files.
     *
     * يستعيد جميع الملفات المحددة
     */
    fun recoverSelectedFiles(outputPath: String? = null) {
        val state = _uiState.value
        if (state.selectedFileIds.isEmpty()) return

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isRecovering = true, recoveryProgress = 0, error = null) }

                val filesToRecover = state.allFiles.filter { it.id in state.selectedFileIds }
                val totalCount = filesToRecover.size
                var recoveredCount = 0

                // استعادة كل ملف على حدة
                filesToRecover.forEach { file ->
                    try {
                        // تحويل RecoveredFileEntity إلى FoundFileInfo
                        val foundFileInfo = file.toFoundFileInfo()

                        var success = false
                        fileRecoveryEngine.recoverFile(
                            fileInfo = foundFileInfo,
                            outputDir = outputPath?.let { java.io.File(it) }
                        ).collect { result ->
                            success = result.success
                        }

                        if (success) {
                            recoveredCount++
                            // تحديث حالة الملف في قاعدة البيانات
                            recoveredFileRepository.setFavorite(file.id, false)
                        }

                        // تحديث التقدم
                        val progress = (recoveredCount * 100) / totalCount
                        _uiState.update { it.copy(recoveryProgress = progress) }

                    } catch (e: Exception) {
                        Timber.e(e, "Error recovering file: ${file.fileName}")
                    }
                }

                // اكتمال الاستعادة
                _uiState.update { state ->
                    state.copy(
                        isRecovering = false,
                        recoveryProgress = 100,
                        successMessage = "Successfully recovered $recoveredCount of $totalCount files",
                        selectedFileIds = emptySet(),
                        isAllSelected = false
                    )
                }

            } catch (e: Exception) {
                Timber.e(e, "Error in recoverSelectedFiles")
                _uiState.update {
                    it.copy(
                        isRecovering = false,
                        error = e.message ?: "Recovery failed"
                    )
                }
            }
        }
    }

    /**
     * Recovers all files.
     *
     * يستعيد جميع الملفات
     */
    fun recoverAllFiles(outputPath: String? = null) {
        selectAll()
        recoverSelectedFiles(outputPath)
    }

    // ──────────────────────────────────────────
    // مساعدات
    // ──────────────────────────────────────────

    /**
     * Clears the error message.
     *
     * يمسح رسالة الخطأ
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clears the success message.
     *
     * يمسح رسالة النجاح
     */
    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }

    /**
     * Returns the count of files for a specific category.
     *
     * يعيد عدد الملفات لفئة معينة
     */
    fun getCategoryCount(category: FileCategory): Int {
        return _uiState.value.categoryCounts[category] ?: 0
    }

    /**
     * Returns the total count of all files.
     *
     * يعيد العدد الإجمالي لجميع الملفات
     */
    fun getTotalFilesCount(): Int {
        return _uiState.value.allFiles.size
    }

    /**
     * Returns the total size of all files.
     *
     * يعيد الحجم الإجمالي لجميع الملفات
     */
    fun getTotalFilesSize(): Long {
        return _uiState.value.allFiles.sumOf { it.fileSize }
    }

    /**
     * Refreshes the file list.
     *
     * يعمل على تحديث قائمة الملفات
     */
    fun refresh() {
        if (currentSessionId > 0) {
            loadFiles(currentSessionId)
        } else {
            loadAllFiles()
        }
    }
}

// ──────────────────────────────────────────────
// Extension Functions
// ──────────────────────────────────────────────

/**
 * Converts RecoveredFileEntity to FoundFileInfo for the recovery engine.
 *
 * يحول كيان الملف المسترد إلى معلومات الملف المكتشف لمحرك الاستعادة
 */
fun RecoveredFileEntity.toFoundFileInfo(): FoundFileInfo {
    return FoundFileInfo(
        path = this.filePath,
        fileName = this.fileName,
        fileSize = this.fileSize,
        extension = this.fileExtension,
        mimeType = this.mimeType,
        category = this.category,
        thumbnailPath = this.thumbnailPath,
        lastModified = this.originalDate,
        isRootRequired = this.isRootAccessed,
        sourcePath = this.sourcePath,
        confidence = RecoveryConfidence.MEDIUM
    )
}
