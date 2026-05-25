package com.ultimaterecovery.pro.ui.fragments.scan

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieDrawable
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultimaterecovery.pro.utils.storage.formatFileSize
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.databinding.FragmentScanBinding
import com.ultimaterecovery.pro.engine.scanner.ScanState
import com.ultimaterecovery.pro.ui.activities.ScanActivity
import com.ultimaterecovery.pro.ui.viewmodel.ScanUiState
import com.ultimaterecovery.pro.ui.viewmodel.ScanViewModel
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Scan fragment with scan type selection and progress display.
 *
 * Embedded in the main navigation graph as one of the 5 bottom-nav tabs.
 * Delegates to [ScanViewModel] for scan lifecycle management.
 * For the full-screen scan experience, [ScanActivity] is used instead.
 */
@AndroidEntryPoint
class ScanFragment : Fragment() {

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: ScanViewModel by viewModels()

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            _binding = FragmentScanBinding.inflate(inflater, container, false)
            return binding.root
        } catch (e: Exception) {
            Timber.e(e, "Error in onCreateView")
            return View(context)
        } catch (_: Throwable) {
            return View(context)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        try {
        super.onViewCreated(view, savedInstanceState)
        setupScanTypeSelector()
        setupCategoryChips()
        setupControls()
        observeUiState()

        } catch (e: Exception) {
            Timber.e(e, "Error in onViewCreated")
        } catch (_: Throwable) {
            // Prevent crash
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ──────────────────────────────────────────
    // Scan type selector
    // ──────────────────────────────────────────

    private fun setupScanTypeSelector() {
        // Quick/Deep scan button listeners
        binding.btnQuickScan.setOnClickListener { viewModel.selectScanType(ScanType.QUICK) }
        binding.btnDeepScan.setOnClickListener { viewModel.selectScanType(ScanType.DEEP) }

        // Additional scan type buttons
        binding.btnSignature.setOnClickListener { viewModel.selectScanType(ScanType.SIGNATURE) }
        binding.btnRaw.setOnClickListener { viewModel.selectScanType(ScanType.RAW) }
        binding.btnPartition.setOnClickListener { viewModel.selectScanType(ScanType.PARTITION) }
    }

    // ──────────────────────────────────────────
    // Category chips
    // ──────────────────────────────────────────

    private fun setupCategoryChips() {
        val categories = FileCategory.values()
        for (category in categories) {
            val chip = Chip(requireContext()).apply {
                text = category.name.lowercase().replaceFirstChar { it.uppercase() }
                isCheckable = true
                id = View.generateViewId()
                setOnClickListener { viewModel.toggleCategory(category) }
            }
            binding.chipGroupCategories.addView(chip)
        }
    }

    // ──────────────────────────────────────────
    // Controls
    // ──────────────────────────────────────────

    private fun setupControls() {
        binding.btnStartScan.setOnClickListener {
            // Launch the dedicated ScanActivity for full scan experience
            val scanType = viewModel.uiState.value.selectedScanType
            val intent = Intent(requireContext(), ScanActivity::class.java).apply {
                putExtra(ScanActivity.EXTRA_SCAN_TYPE, scanType.name)
            }
            startActivity(intent)
        }

        // Quick scan button — starts scan directly in-fragment
        binding.btnQuickScan.setOnClickListener {
            viewModel.startScan(ScanType.QUICK)
        }
    }

    // ──────────────────────────────────────────
    // UI State observation
    // ──────────────────────────────────────────

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: ScanUiState) {
        try {
        val binding = _binding ?: return
            when (val scanState = state.scanState) {
                is ScanState.Idle -> showIdleState()
                is ScanState.Scanning -> showScanningState(scanState)
                is ScanState.Paused -> showPausedState(scanState)
                is ScanState.Completed -> showCompletedState(scanState)
                is ScanState.Failed -> showFailedState(scanState)
                is ScanState.Cancelled -> {
                    showIdleState()
                    Toast.makeText(requireContext(), R.string.scan_cancelled, Toast.LENGTH_SHORT).show()
                }
            }

            // Error handling
            state.error?.let { error ->
                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }

        } catch (e: Exception) {
            Timber.e(e, "Error rendering state")
        } catch (_: Throwable) {
            // Prevent crash
        }
    }

    // ──────────────────────────────────────────
    // State rendering
    // ──────────────────────────────────────────

    private fun showIdleState() {
        binding.layoutScanConfig?.visibility = View.VISIBLE
        binding.layoutScanProgress?.visibility = View.GONE
        binding.layoutScanResults?.visibility = View.GONE
        binding.lottieScanProgress?.visibility = View.GONE
    }

    private fun showScanningState(state: ScanState.Scanning) {
        binding.layoutScanConfig?.visibility = View.GONE
        binding.layoutScanProgress?.visibility = View.VISIBLE
        binding.layoutScanResults?.visibility = View.GONE

        binding.lottieScanProgress?.visibility = View.VISIBLE
        binding.lottieScanProgress.playAnimation()

        val progressPercent = (state.progress * 100).toInt()
        binding.progressScan.progress = progressPercent
        binding.tvProgressPercent.text = getString(R.string.progress_percent, progressPercent)
        binding.tvFilesFound.text = getString(R.string.files_found, state.filesFound)
        binding.tvCurrentPath.text = state.currentPath
    }

    private fun showPausedState(state: ScanState.Paused) {
        binding.lottieScanProgress.pauseAnimation()
        val progressPercent = (state.progress * 100).toInt()
        binding.progressScan.progress = progressPercent
        binding.tvProgressPercent.text = getString(R.string.progress_percent, progressPercent)
        binding.tvFilesFound.text = getString(R.string.files_found, state.filesFound)
        binding.tvCurrentPath.text = getString(R.string.scan_paused)
    }

    private fun showCompletedState(state: ScanState.Completed) {
        binding.lottieScanProgress.cancelAnimation()
        binding.lottieScanProgress?.visibility = View.GONE

        binding.layoutScanConfig?.visibility = View.GONE
        binding.layoutScanProgress?.visibility = View.GONE
        binding.layoutScanResults?.visibility = View.VISIBLE

        binding.tvResultTotalFiles.text = getString(R.string.result_total_files, state.totalFiles)
        binding.tvResultTotalSize.text = formatFileSize(state.totalSize)
        binding.tvResultDuration.text = getString(R.string.result_duration, state.durationMs / 1000)
        binding.tvResultScanType.text = state.scanType.name

        binding.btnSaveResults.setOnClickListener { viewModel.saveScanResults() }
    }

    private fun showFailedState(state: ScanState.Failed) {
        binding.lottieScanProgress.cancelAnimation()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.scan_failed)
            .setMessage(state.error)
            .setPositiveButton(R.string.ok) { _, _ -> showIdleState() }
            .show()
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
