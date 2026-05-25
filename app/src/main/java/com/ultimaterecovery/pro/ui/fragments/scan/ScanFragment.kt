package com.ultimaterecovery.pro.ui.fragments.scan

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
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.databinding.FragmentScanBinding
import com.ultimaterecovery.pro.engine.scanner.ScanState
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
 */
@AndroidEntryPoint
class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ScanViewModel by viewModels()

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
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupScanTypeSelector() {
        binding.btnQuickScan.setOnClickListener { viewModel.selectScanType(ScanType.QUICK) }
        binding.btnDeepScan.setOnClickListener { viewModel.selectScanType(ScanType.DEEP) }
    }

    private fun setupCategoryChips() {
        val categories = FileCategory.values()
        for (category in categories) {
            val chip = Chip(requireContext()).apply {
                text = category.name.lowercase().replaceFirstChar { it.uppercase() }
                isCheckable = true
                id = View.generateViewId()
                setOnClickListener { viewModel.toggleCategory(category) }
            }
            binding.chipGroupFilter.addView(chip)
        }
    }

    private fun setupControls() {
        binding.btnStartScan.setOnClickListener {
            viewModel.startScan()
        }
    }

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

            state.error?.let { error ->
                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error rendering state")
        }
    }

    private fun showIdleState() {
        binding.layoutScanProgress.visibility = View.GONE
        binding.btnStartScan.visibility = View.VISIBLE
    }

    private fun showScanningState(state: ScanState.Scanning) {
        binding.layoutScanProgress.visibility = View.VISIBLE
        binding.btnStartScan.visibility = View.GONE

        val progressPercent = (state.progress * 100).toInt()
        binding.progressScan.progress = progressPercent
        binding.textProgressPercent.text = getString(R.string.progress_percent, progressPercent)
        binding.textFilesFound.text = getString(R.string.files_found, state.filesFound)
    }

    private fun showPausedState(state: ScanState.Paused) {
        binding.layoutScanProgress.visibility = View.VISIBLE
        binding.btnStartScan.visibility = View.GONE

        val progressPercent = (state.progress * 100).toInt()
        binding.progressScan.progress = progressPercent
        binding.textProgressPercent.text = getString(R.string.progress_percent, progressPercent)
        binding.textFilesFound.text = getString(R.string.files_found, state.filesFound)
    }

    private fun showCompletedState(state: ScanState.Completed) {
        binding.layoutScanProgress.visibility = View.GONE
        binding.btnStartScan.visibility = View.VISIBLE

        Toast.makeText(
            requireContext(),
            getString(R.string.scan_complete_summary, state.totalFiles, formatFileSize(state.totalSize)),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showFailedState(state: ScanState.Failed) {
        binding.layoutScanProgress.visibility = View.GONE
        binding.btnStartScan.visibility = View.VISIBLE

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.scan_failed)
            .setMessage(state.error)
            .setPositiveButton(R.string.ok, null)
            .show()
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
