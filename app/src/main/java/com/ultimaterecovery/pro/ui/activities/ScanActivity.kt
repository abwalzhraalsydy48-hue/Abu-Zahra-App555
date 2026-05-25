package com.ultimaterecovery.pro.ui.activities

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
 */
@AndroidEntryPoint
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCAN_TYPE = "extra_scan_type"
    }

    private var _binding: ActivityScanBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ScanViewModel by viewModels()

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

            intent.getStringExtra(EXTRA_SCAN_TYPE)?.let { typeName ->
                try {
                    val scanType = ScanType.valueOf(typeName)
                    viewModel.selectScanType(scanType)
                } catch (_: IllegalArgumentException) { }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error in onCreate")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupScanTypeSelector() {
        binding.cardQuickScan.setOnClickListener { viewModel.selectScanType(ScanType.QUICK) }
        binding.cardDeepScan.setOnClickListener { viewModel.selectScanType(ScanType.DEEP) }
        binding.cardSignatureScan.setOnClickListener { viewModel.selectScanType(ScanType.SIGNATURE) }
        binding.cardRawScan.setOnClickListener { viewModel.selectScanType(ScanType.RAW) }
        binding.cardPartitionScan.setOnClickListener { viewModel.selectScanType(ScanType.PARTITION) }
    }

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

    private fun setupControls() {
        binding.btnStartScan.setOnClickListener {
            viewModel.startScan()
        }

        binding.btnPauseScan.setOnClickListener {
            viewModel.pauseScan()
        }

        binding.btnResumeScan.setOnClickListener {
            viewModel.resumeScan()
        }

        binding.btnCancelScan.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cancel_scan_title)
                .setMessage(R.string.cancel_scan_message)
                .setPositiveButton(R.string.yes) { _, _ -> viewModel.cancelScan() }
                .setNegativeButton(R.string.no, null)
                .show()
        }
    }

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
            when (val scanState = state.scanState) {
                is ScanState.Idle -> showIdleState()
                is ScanState.Scanning -> showScanningState(scanState)
                is ScanState.Paused -> showPausedState(scanState)
                is ScanState.Completed -> showCompletedState(scanState)
                is ScanState.Failed -> showFailedState(scanState)
                is ScanState.Cancelled -> showCancelledState()
            }

            state.error?.let { error ->
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error rendering state")
        }
    }

    private fun showIdleState() {
        binding.layoutScanProgress.visibility = View.GONE
        binding.layoutScanControls.visibility = View.GONE
        binding.btnStartScan.visibility = View.VISIBLE
        binding.cardResultsSummary.visibility = View.GONE
    }

    private fun showScanningState(state: ScanState.Scanning) {
        binding.layoutScanProgress.visibility = View.VISIBLE
        binding.layoutScanControls.visibility = View.VISIBLE
        binding.btnStartScan.visibility = View.GONE
        binding.cardResultsSummary.visibility = View.GONE

        val progressPercent = (state.progress * 100).toInt()
        binding.progressScan.progress = progressPercent
        binding.textScanPercentage.text = getString(R.string.progress_percent, progressPercent)
        binding.textFileCount.text = getString(R.string.files_found, state.filesFound)
        binding.textScanPath.text = state.currentPath

        binding.btnPauseScan.visibility = View.VISIBLE
        binding.btnResumeScan.visibility = View.GONE
        binding.btnCancelScan.visibility = View.VISIBLE
    }

    private fun showPausedState(state: ScanState.Paused) {
        binding.layoutScanProgress.visibility = View.VISIBLE
        binding.layoutScanControls.visibility = View.VISIBLE
        binding.btnStartScan.visibility = View.GONE

        val progressPercent = (state.progress * 100).toInt()
        binding.progressScan.progress = progressPercent
        binding.textScanPercentage.text = getString(R.string.progress_percent, progressPercent)
        binding.textFileCount.text = getString(R.string.files_found, state.filesFound)

        binding.btnPauseScan.visibility = View.GONE
        binding.btnResumeScan.visibility = View.VISIBLE
        binding.btnCancelScan.visibility = View.VISIBLE
    }

    private fun showCompletedState(state: ScanState.Completed) {
        binding.layoutScanProgress.visibility = View.GONE
        binding.layoutScanControls.visibility = View.GONE
        binding.btnStartScan.visibility = View.VISIBLE
        binding.cardResultsSummary.visibility = View.VISIBLE

        binding.textResultsSummary.text = getString(
            R.string.scan_complete_summary,
            state.totalFiles,
            formatFileSize(state.totalSize)
        )
    }

    private fun showFailedState(state: ScanState.Failed) {
        showIdleState()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_failed)
            .setMessage(state.error)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showCancelledState() {
        showIdleState()
        Toast.makeText(this, R.string.scan_cancelled, Toast.LENGTH_SHORT).show()
    }

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
