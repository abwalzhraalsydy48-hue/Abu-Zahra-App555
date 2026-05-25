package com.ultimaterecovery.pro.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.databinding.FragmentHomeBinding
import com.ultimaterecovery.pro.engine.root.RootState
import com.ultimaterecovery.pro.manager.FileManager.StorageInfo
import com.ultimaterecovery.pro.ui.activities.ScanActivity
import com.ultimaterecovery.pro.ui.viewmodel.MainUiState
import com.ultimaterecovery.pro.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Home screen fragment.
 *
 * Features:
 * - Storage usage circular progress with available/used/total
 * - Quick action cards: Scan, Photo Recovery, Video Recovery, File Recovery, SMS Recovery, Call Log Recovery
 * - Root status banner (if available)
 * - Recent recoveries list
 * - Quick stats (total recovered, last scan, categories)
 * - Beautiful card-based layout
 * - Shimmer loading effect
 */
@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupQuickActions()
        setupSwipeRefresh()
        observeUiState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupQuickActions() {
        // Quick Scan - opens ScanActivity
        binding.cardQuickScan.setOnClickListener {
            val intent = Intent(requireContext(), ScanActivity::class.java).apply {
                putExtra(ScanActivity.EXTRA_SCAN_TYPE, ScanType.QUICK.name)
            }
            startActivity(intent)
        }

        // Photo Recovery - navigates to PhotoRecoveryFragment
        binding.cardPhotoRecovery.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_homeFragment_to_photoRecoveryFragment)
            } catch (e: Exception) {
                // Fallback to ScanActivity with photo type
                val intent = Intent(requireContext(), ScanActivity::class.java).apply {
                    putExtra(ScanActivity.EXTRA_SCAN_TYPE, "PHOTO")
                }
                startActivity(intent)
            }
        }

        // Video Recovery - navigates to VideoRecoveryFragment
        binding.cardVideoRecovery.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_homeFragment_to_videoRecoveryFragment)
            } catch (e: Exception) {
                val intent = Intent(requireContext(), ScanActivity::class.java).apply {
                    putExtra(ScanActivity.EXTRA_SCAN_TYPE, "VIDEO")
                }
                startActivity(intent)
            }
        }

        // File Recovery - navigates to FileRecoveryFragment
        binding.cardFileRecovery.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_homeFragment_to_fileRecoveryFragment)
            } catch (e: Exception) {
                val intent = Intent(requireContext(), ScanActivity::class.java).apply {
                    putExtra(ScanActivity.EXTRA_SCAN_TYPE, "DOCUMENT")
                }
                startActivity(intent)
            }
        }

        // SMS Recovery - navigates to SmsRecoveryFragment
        binding.cardSmsRecovery.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_homeFragment_to_smsRecoveryFragment)
            } catch (e: Exception) {
                val intent = Intent(requireContext(), ScanActivity::class.java).apply {
                    putExtra(ScanActivity.EXTRA_SCAN_TYPE, "SMS")
                }
                startActivity(intent)
            }
        }

        // Call Log Recovery - navigates to CallLogRecoveryFragment
        binding.cardCallLogRecovery.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_homeFragment_to_callLogRecoveryFragment)
            } catch (e: Exception) {
                val intent = Intent(requireContext(), ScanActivity::class.java).apply {
                    putExtra(ScanActivity.EXTRA_SCAN_TYPE, "CALL_LOG")
                }
                startActivity(intent)
            }
        }

        // Deep Scan - navigates to ScanFragment with deep scan mode
        binding.cardDeepScan.setOnClickListener {
            val intent = Intent(requireContext(), ScanActivity::class.java).apply {
                putExtra(ScanActivity.EXTRA_SCAN_TYPE, ScanType.DEEP.name)
            }
            startActivity(intent)
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadStorageInfo()
            viewModel.loadQuickStats()
            viewModel.checkRootStatus()
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    try {
                        renderState(state)
                    } catch (e: Exception) {
                        // Render state failure should not crash the fragment
                    }
                }
            }
        }
    }

    private fun renderState(state: MainUiState) {
        try {
            // Shimmer loading
            if (state.isLoading) {
                binding.shimmerFrameLayout?.visibility = View.VISIBLE
                binding.shimmerFrameLayout.startShimmer()
                binding.nestedScrollView?.visibility = View.GONE
            } else {
                binding.shimmerFrameLayout?.visibility = View.GONE
                binding.shimmerFrameLayout.stopShimmer()
                binding.nestedScrollView?.visibility = View.VISIBLE
            }

            // Swipe refresh
            binding.swipeRefresh.isRefreshing = false

            // Storage usage
            renderStorageUsage(state.storageInfo)

            // Quick stats
            binding.tvTotalRecovered.text = state.totalRecovered.toString()
            binding.tvTotalRecoveredSize.text = formatFileSize(state.totalRecoveredSize)

            // Last scan
            state.lastScanDate?.let { timestamp ->
                binding.tvLastScanDate.text = DateFormat.getDateTimeInstance()
                    .format(Date(timestamp))
                binding.tvLastScanType.text = state.lastScanType?.name ?: ""
                binding.layoutLastScan?.visibility = View.VISIBLE
            } ?: run {
                binding.layoutLastScan?.visibility = View.GONE
            }

            // Category counts
            renderCategoryCounts(state.categoryCounts)

            // Root status banner
            renderRootBanner(state.rootState, state.isRootAvailable)
        } catch (e: Exception) {
            // Render failures should not crash the fragment
        }
    }

    private fun renderStorageUsage(storageInfo: List<StorageInfo>) {
        if (storageInfo.isEmpty()) {
            binding.cardStorageUsage?.visibility = View.GONE
            return
        }

        binding.cardStorageUsage?.visibility = View.VISIBLE

        val primary = storageInfo.firstOrNull() ?: return
        val usedPercent = if (primary.totalSpace > 0) {
            ((primary.usedSpace.toFloat() / primary.totalSpace) * 100).toInt()
        } else 0

        binding.progressStorage.progress = usedPercent
        binding.tvStorageUsedPercent.text = getString(R.string.storage_percent, usedPercent)
        binding.tvStorageUsed.text = formatFileSize(primary.usedSpace)
        binding.tvStorageTotal.text = formatFileSize(primary.totalSpace)
        binding.tvStorageAvailable.text = formatFileSize(primary.freeSpace)

        // Secondary storage (SD card)
        if (storageInfo.size > 1) {
            val secondary = storageInfo[1]
            binding.layoutSecondaryStorage?.visibility = View.VISIBLE
            val secPercent = if (secondary.totalSpace > 0) {
                ((secondary.usedSpace.toFloat() / secondary.totalSpace) * 100).toInt()
            } else 0
            binding.progressSecondaryStorage.progress = secPercent
            binding.tvSecondaryStorageUsedPercent.text = getString(R.string.storage_percent, secPercent)
            binding.tvSecondaryStorageLabel.text = secondary.label
        } else {
            binding.layoutSecondaryStorage?.visibility = View.GONE
        }
    }

    private fun renderCategoryCounts(counts: Map<FileCategory, Int>) {
        binding.tvPhotoCount.text = (counts[FileCategory.PHOTO] ?: 0).toString()
        binding.tvVideoCount.text = (counts[FileCategory.VIDEO] ?: 0).toString()
        binding.tvDocumentCount.text = (counts[FileCategory.DOCUMENT] ?: 0).toString()
        binding.tvAudioCount.text = (counts[FileCategory.AUDIO] ?: 0).toString()
    }

    private fun renderRootBanner(rootState: RootState, isRootAvailable: Boolean) {
        when (rootState) {
            is RootState.Granted -> {
                binding.cardRootBanner?.visibility = View.VISIBLE
                binding.tvRootBannerTitle.text = getString(R.string.root_access_granted)
                binding.tvRootBannerDescription.text = getString(R.string.root_granted_desc)
                binding.ivRootIcon.setImageResource(R.drawable.ic_check_circle)
            }
            is RootState.Available -> {
                binding.cardRootBanner?.visibility = View.VISIBLE
                binding.tvRootBannerTitle.text = getString(R.string.root_available)
                binding.tvRootBannerDescription.text = getString(R.string.root_available_desc)
                binding.ivRootIcon.setImageResource(R.drawable.ic_warning)
            }
            is RootState.NotAvailable -> {
                binding.cardRootBanner?.visibility = View.GONE
            }
            RootState.Unknown -> {
                binding.cardRootBanner?.visibility = View.GONE
            }
            is RootState.Denied -> {
                binding.cardRootBanner?.visibility = View.GONE
            }
            is RootState.Revoked -> {
                binding.cardRootBanner?.visibility = View.GONE
            }
        }
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
