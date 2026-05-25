package com.ultimaterecovery.pro.ui.activities

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.airbnb.lottie.LottieDrawable
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultimaterecovery.pro.utils.storage.formatFileSize
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.databinding.ActivityScanBinding
import com.ultimaterecovery.pro.engine.scanner.ScanState
import com.ultimaterecovery.pro.ui.viewmodel.ScanUiState
import com.ultimaterecovery.pro.ui.viewmodel.ScanViewModel
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Dedicated scan activity with full scan lifecycle management.
 *
 * Features:
 * - Scan type selection (Quick / Deep / Signature / Raw / Partition)
 * - Category multi-selection via filter chips
 * - Animated scan progress circle with Lottie
 * - Real-time file count and progress percentage
 * - Pause / Resume / Cancel controls
 * - Found files preview list
 * - Scan results summary on completion
 */
@AndroidEntryPoint
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCAN_TYPE = "extra_scan_type"
    }

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: ActivityScanBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: ScanViewModel by viewModels()

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            _binding = ActivityScanBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupToolbar()
            setupScanTypeSelector()
            setupCategoryChips()
            setupControls()
            observeUiState()

            // Restore scan type from intent if launched from a quick-action
            intent.getStringExtra(EXTRA_SCAN_TYPE)?.let { typeName ->
                try {
                    val scanType = ScanType.valueOf(typeName)
                    viewModel.selectScanType(scanType)
                } catch (_: IllegalArgumentException) { }
            }

        } catch (e: Exception) {
            Timber.e(e, "Error in onCreate")
        } catch (_: Throwable) {
            // Prevent crash
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    // ──────────────────────────────────────────
    // Toolbar
    // ──────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    // ──────────────────────────────────────────
    // Scan type selector
    // ──────────────────────────────────────────

    private fun setupScanTypeSelector() {
        binding.toggleScanType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val scanType = when (checkedId) {
                R.id.btnQuick      -> ScanType.QUICK
                R.id.btnDeep       -> ScanType.DEEP
                R.id.btnSignature  -> ScanType.SIGNATURE
                R.id.btnRaw        -> ScanType.RAW
                R.id.btnPartition  -> ScanType.PARTITION
                else               -> ScanType.QUICK
            }
            viewModel.selectScanType(scanType)
        }
    }

    // ──────────────────────────────────────────
    // Category chips
    // ──────────────────────────────────────────

    private fun setupCategoryChips() {
        val categories = FileCategory.values()
        for (category in categories) {
            val chip = Chip(this).apply {
                text = category.name.lowercase().replaceFirstChar { it.uppercase() }
                isCheckable = true
                setChipIconResource(iconForCategory(category))
                setOnClickListener { viewModel.toggleCategory(category) }
                id = View.generateViewId()
            }
            binding.chipGroupCategories.addView(chip)
        }
    }

    private fun iconForCategory(category: FileCategory): Int = when (category) {
        FileCategory.PHOTO    -> R.drawable.ic_photo
        FileCategory.VIDEO    -> R.drawable.ic_video
        FileCategory.DOCUMENT -> R.drawable.ic_document
        FileCategory.AUDIO    -> R.drawable.ic_audio
        FileCategory.ARCHIVE  -> R.drawable.ic_archive
        FileCategory.APK      -> R.drawable.ic_apk
        FileCategory.OTHER    -> R.drawable.ic_file
    }

    // ──────────────────────────────────────────
    // Controls
    // ──────────────────────────────────────────

    private fun setupControls() {
        binding.btnStartScan.setOnClickListener {
            viewModel.startScan()
        }

        binding.btnPause.setOnClickListener {
            viewModel.pauseScan()
        }

        binding.btnResume.setOnClickListener {
            viewModel.resumeScan()
        }

        binding.btnCancel.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cancel_scan_title)
                .setMessage(R.string.cancel_scan_message)
                .setPositiveButton(R.string.yes) { _, _ -> viewModel.cancelScan() }
                .setNegativeButton(R.string.no, null)
                .show()
        }

        binding.btnSaveResults.setOnClickListener {
            viewModel.saveScanResults()
            Toast.makeText(this, R.string.results_saved, Toast.LENGTH_SHORT).show()
        }
    }

    // ──────────────────────────────────────────
    // UI State observation
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

    private fun renderState(state: ScanUiState) {
        val binding = _binding ?: return
        try {

            val scanState = state.scanState

            when (scanState) {
                is ScanState.Idle -> showIdleState(state)
                is ScanState.Scanning -> showScanningState(scanState, state)
                is ScanState.Paused -> showPausedState(scanState)
                is ScanState.Completed -> showCompletedState(scanState, state)
                is ScanState.Failed -> showFailedState(scanState, state)
                is ScanState.Cancelled -> showCancelledState()
            }

            // Error
            state.error?.let { error ->
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error rendering state")
        } catch (_: Throwable) {
            // Prevent crash
        }
    }

    // ──────────────────────────────────────────
    // State rendering helpers
    // ──────────────────────────────────────────

    private fun showIdleState(state: ScanUiState) {
        binding.layoutScanConfig?.visibility = View.VISIBLE
        binding.layoutScanProgress?.visibility = View.GONE
        binding.layoutScanResults?.visibility = View.GONE
        binding.lottieScanProgress?.visibility = View.GONE
    }

    private fun showScanningState(state: ScanState.Scanning, uiState: ScanUiState) {
        binding.layoutScanConfig?.visibility = View.GONE
        binding.layoutScanProgress?.visibility = View.VISIBLE
        binding.layoutScanResults?.visibility = View.GONE

        // Lottie animation
        binding.lottieScanProgress?.visibility = View.VISIBLE
        binding.lottieScanProgress.playAnimation()

        // Progress circle
        val progressPercent = (state.progress * 100).toInt()
        binding.progressScan.progress = progressPercent
        binding.tvProgressPercent.text = getString(R.string.progress_percent, progressPercent)

        // File count
        binding.tvFilesFound.text = getString(R.string.files_found, state.filesFound)

        // Current path
        binding.tvCurrentPath.text = state.currentPath

        // Control buttons
        binding.btnPause?.visibility = View.VISIBLE
        binding.btnResume?.visibility = View.GONE
        binding.btnCancel?.visibility = View.VISIBLE
        binding.btnSaveResults?.visibility = View.GONE

        // Elapsed time
        val elapsedSec = state.elapsedMs / 1000
        binding.tvElapsedTime.text = getString(R.string.elapsed_time, elapsedSec)
    }

    private fun showPausedState(state: ScanState.Paused) {
        binding.lottieScanProgress.pauseAnimation()

        val progressPercent = (state.progress * 100).toInt()
        binding.progressScan.progress = progressPercent
        binding.tvProgressPercent.text = getString(R.string.progress_percent, progressPercent)
        binding.tvFilesFound.text = getString(R.string.files_found, state.filesFound)
        binding.tvCurrentPath.text = getString(R.string.scan_paused)

        binding.btnPause?.visibility = View.GONE
        binding.btnResume?.visibility = View.VISIBLE
        binding.btnCancel?.visibility = View.VISIBLE
    }

    private fun showCompletedState(state: ScanState.Completed, uiState: ScanUiState) {
        binding.lottieScanProgress.cancelAnimation()
        binding.lottieScanProgress?.visibility = View.GONE

        binding.layoutScanConfig?.visibility = View.GONE
        binding.layoutScanProgress?.visibility = View.GONE
        binding.layoutScanResults?.visibility = View.VISIBLE

        // Summary card
        binding.tvResultTotalFiles.text = getString(R.string.result_total_files, state.totalFiles)
        binding.tvResultTotalSize.text = formatFileSize(state.totalSize)
        binding.tvResultDuration.text = getString(
            R.string.result_duration,
            state.durationMs / 1000
        )
        binding.tvResultScanType.text = state.scanType.name

        // Save button
        binding.btnSaveResults?.visibility = View.VISIBLE
        binding.btnSaveResults.isEnabled = !uiState.isSaving

        // Progress bar for saving
        binding.progressSaving.visibility = if (uiState.isSaving) View.VISIBLE else View.GONE
    }

    private fun showFailedState(state: ScanState.Failed, uiState: ScanUiState) {
        binding.lottieScanProgress.cancelAnimation()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_failed)
            .setMessage(state.error)
            .setPositiveButton(R.string.ok, null)
            .show()

        showIdleState(uiState)
    }

    private fun showCancelledState() {
        binding.lottieScanProgress.cancelAnimation()
        Toast.makeText(this, R.string.scan_cancelled, Toast.LENGTH_SHORT).show()
    }

    // ──────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }
}
