package com.ultimaterecovery.pro.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.databinding.ActivityScanResultsBinding
import com.ultimaterecovery.pro.ui.adapters.CategoryTab
import com.ultimaterecovery.pro.ui.adapters.RecoveredFilesAdapter
import com.ultimaterecovery.pro.ui.adapters.getCategoryDisplayName
import com.ultimaterecovery.pro.ui.adapters.getCategoryIcon
import com.ultimaterecovery.pro.ui.viewmodel.ScanResultsUiState
import com.ultimaterecovery.pro.ui.viewmodel.ScanResultsViewModel
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DecimalFormat

// ──────────────────────────────────────────────
// Scan Results Activity
// ──────────────────────────────────────────────

/**
 * Activity for displaying and managing recovered files after a scan.
 *
 * نشاط عرض وإدارة الملفات المستردة بعد المسح
 *
 * Features:
 * - عرض الملفات المستردة في قائمة مع تبويبات للفئات
 * - عرض الصور المصغرة للصور والفيديوهات
 * - دعم التحديد المتعدد للاستعادة
 * - عرض تفاصيل الملف (الاسم، الحجم، التاريخ، النوع)
 * - زر "استعادة المحدد"
 */
@AndroidEntryPoint
class ScanResultsActivity : AppCompatActivity() {

    // ──────────────────────────────────────────
    // Companion Object
    // ──────────────────────────────────────────

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_FILES_COUNT = "extra_files_count"
        const val EXTRA_TOTAL_SIZE = "extra_total_size"
    }

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: ActivityScanResultsBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: ScanResultsViewModel by viewModels()

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private lateinit var filesAdapter: RecoveredFilesAdapter

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            _binding = ActivityScanResultsBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupToolbar()
            setupRecyclerView()
            setupTabs()
            setupControls()
            observeUiState()

            // تحميل البيانات
            val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
            if (sessionId > 0) {
                viewModel.loadFiles(sessionId)
            }

        } catch (e: Exception) {
            Timber.e(e, "Error in onCreate")
            showErrorAndFinish("Failed to initialize screen")
        } catch (_: Throwable) {
            showErrorAndFinish("An unexpected error occurred")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    // ──────────────────────────────────────────
    // Toolbar Setup
    // ──────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    // ──────────────────────────────────────────
    // RecyclerView Setup
    // ──────────────────────────────────────────

    private fun setupRecyclerView() {
        filesAdapter = RecoveredFilesAdapter(
            onItemClick = { file -> onFileClicked(file) },
            onItemLongClick = { file -> onFileLongClicked(file) },
            onSelectionChange = { fileId -> viewModel.toggleFileSelection(fileId) }
        )

        binding.recyclerViewFiles.apply {
            adapter = filesAdapter
            layoutManager = LinearLayoutManager(this@ScanResultsActivity)
            setHasFixedSize(true)
            itemAnimator = null // تعطيل الرسوم المتحركة لأداء أفضل
        }
    }

    // ──────────────────────────────────────────
    // Tabs Setup
    // ──────────────────────────────────────────

    private fun setupTabs() {
        // إضافة تبويب "الكل"
        binding.tabLayoutCategories.addTab(
            binding.tabLayoutCategories.newTab()
                .setText(R.string.scan_result_filter_all)
                .setIcon(R.drawable.ic_file)
        )

        // إضافة تبويبات الفئات
        FileCategory.values().forEach { category ->
            binding.tabLayoutCategories.addTab(
                binding.tabLayoutCategories.newTab()
                    .setText(getCategoryDisplayName(category))
                    .setIcon(getCategoryIcon(category))
            )
        }

        // معالج تحديد التبويب
        binding.tabLayoutCategories.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val position = tab?.position ?: 0
                val category = if (position == 0) null else FileCategory.values().getOrNull(position - 1)
                viewModel.filterByCategory(category)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // ──────────────────────────────────────────
    // Controls Setup
    // ──────────────────────────────────────────

    private fun setupControls() {
        // زر استعادة المحدد
        binding.fabRecoverSelected.setOnClickListener {
            showRecoveryConfirmationDialog()
        }

        // زر تحديد الكل
        binding.btnSelectAll.setOnClickListener {
            viewModel.selectAll()
        }

        // زر إلغاء تحديد الكل
        binding.btnDeselectAll.setOnClickListener {
            viewModel.deselectAll()
        }

        // زر المسح مرة أخرى
        binding.btnScanAgain.setOnClickListener {
            navigateToScanActivity()
        }
    }

    // ──────────────────────────────────────────
    // UI State Observation
    // ──────────────────────────────────────────

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: ScanResultsUiState) {
        val binding = _binding ?: return

        try {
            // حالة التحميل
            renderLoadingState(state)

            // حالة الفراغ
            renderEmptyState(state)

            // قائمة الملفات
            renderFilesList(state)

            // حالة التحديد
            renderSelectionState(state)

            // حالة الاستعادة
            renderRecoveryState(state)

            // الأخطاء والرسائل
            renderMessages(state)

            // تحديث شريط الملخص
            updateSummary(state)

            // تحديث التبويبات
            updateTabs(state)

        } catch (e: Exception) {
            Timber.e(e, "Error rendering state")
        }
    }

    private fun renderLoadingState(state: ScanResultsUiState) {
        binding.layoutLoading.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.layoutContent.visibility = if (state.isLoading) View.GONE else View.VISIBLE
        binding.shimmerLayout.visibility = View.GONE

        if (state.isLoading) {
            binding.shimmerLayout.visibility = View.VISIBLE
            binding.shimmerLayout.startShimmer()
        } else {
            binding.shimmerLayout.stopShimmer()
        }
    }

    private fun renderEmptyState(state: ScanResultsUiState) {
        val isEmpty = state.filteredFiles.isEmpty() && !state.isLoading
        binding.layoutEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerViewFiles.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun renderFilesList(state: ScanResultsUiState) {
        filesAdapter.submitList(state.filteredFiles)
        filesAdapter.updateSelectedIds(state.selectedFileIds)
    }

    private fun renderSelectionState(state: ScanResultsUiState) {
        val hasSelection = state.selectedFileIds.isNotEmpty()

        // شريط التحديد
        binding.layoutSelectionBar.visibility = if (hasSelection) View.VISIBLE else View.GONE

        // زر الاستعادة
        binding.fabRecoverSelected.visibility = if (hasSelection) View.VISIBLE else View.GONE

        // نص المحدد
        if (hasSelection) {
            val selectedSize = formatFileSize(state.selectedTotalSize)
            binding.textSelectedSummary.text = getString(
                R.string.scan_result_selected,
                state.selectedFileIds.size
            ) + " ($selectedSize)"
        }

        // نص عدد المحددين في البطاقة
        binding.textSelectedCount.visibility = if (hasSelection) View.VISIBLE else View.GONE
        binding.textSelectedCount.text = getString(
            R.string.scan_result_selected,
            state.selectedFileIds.size
        )
    }

    private fun renderRecoveryState(state: ScanResultsUiState) {
        binding.layoutRecoveryProgress.visibility = if (state.isRecovering) View.VISIBLE else View.GONE

        if (state.isRecovering) {
            binding.progressRecovery.progress = state.recoveryProgress
            binding.textRecoveryProgress.text = getString(
                R.string.progress_recovering
            ) + " ${state.recoveryProgress}%"
        }
    }

    private fun renderMessages(state: ScanResultsUiState) {
        // رسالة الخطأ
        state.error?.let { error ->
            Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_retry) { viewModel.clearError() }
                .show()
            viewModel.clearError()
        }

        // رسالة النجاح
        state.successMessage?.let { message ->
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            viewModel.clearSuccessMessage()
        }
    }

    private fun updateSummary(state: ScanResultsUiState) {
        val totalCount = state.allFiles.size
        val totalSize = formatFileSize(state.allFiles.sumOf { it.fileSize })

        binding.textTotalFiles.text = getString(
            R.string.scan_result_total,
            totalCount,
            totalSize
        )
    }

    private fun updateTabs(state: ScanResultsUiState) {
        // تحديث عدادات التبويبات
        val tabLayout = binding.tabLayoutCategories

        // تبويب "الكل"
        tabLayout.getTabAt(0)?.let { tab ->
            val count = state.allFiles.size
            tab.text = "${getString(R.string.scan_result_filter_all)} ($count)"
        }

        // تبويبات الفئات
        FileCategory.values().forEachIndexed { index, category ->
            val tabIndex = index + 1 // +1 لأن التبويب الأول هو "الكل"
            tabLayout.getTabAt(tabIndex)?.let { tab ->
                val count = state.categoryCounts[category] ?: 0
                tab.text = "${getCategoryDisplayName(category)} ($count)"
                tab.view.visibility = if (count > 0) View.VISIBLE else View.GONE
            }
        }
    }

    // ──────────────────────────────────────────
    // Click Handlers
    // ──────────────────────────────────────────

    private fun onFileClicked(file: RecoveredFileEntity) {
        // فتح معاينة الملف
        openPreview(file)
    }

    private fun onFileLongClicked(file: RecoveredFileEntity): Boolean {
        // تفعيل وضع التحديد
        viewModel.toggleFileSelection(file.id)
        return true
    }

    private fun openPreview(file: RecoveredFileEntity) {
        val intent = Intent(this, PreviewActivity::class.java).apply {
            putExtra(PreviewActivity.EXTRA_FILE_PATH, file.filePath)
            putExtra(PreviewActivity.EXTRA_FILE_NAME, file.fileName)
            putExtra(PreviewActivity.EXTRA_FILE_SIZE, file.fileSize)
            putExtra(PreviewActivity.EXTRA_MIME_TYPE, file.mimeType)
            putExtra(PreviewActivity.EXTRA_FILE_ID, file.id)
        }
        startActivity(intent)
    }

    // ──────────────────────────────────────────
    // Recovery
    // ──────────────────────────────────────────

    private fun showRecoveryConfirmationDialog() {
        val state = viewModel.uiState.value
        val selectedCount = state.selectedFileIds.size
        val totalSize = formatFileSize(state.selectedTotalSize)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_confirm_recovery_title)
            .setMessage(getString(R.string.dialog_confirm_recovery_message, selectedCount, totalSize))
            .setPositiveButton(R.string.action_recover) { _, _ ->
                viewModel.recoverSelectedFiles()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ──────────────────────────────────────────
    // Navigation
    // ──────────────────────────────────────────

    private fun navigateToScanActivity() {
        val intent = Intent(this, ScanActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun showErrorAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    // ──────────────────────────────────────────
    // Helper Methods
    // ──────────────────────────────────────────

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${DecimalFormat("#.#").format(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${DecimalFormat("#.#").format(mb)} MB"
        val gb = mb / 1024.0
        return "${DecimalFormat("#.##").format(gb)} GB"
    }
}
