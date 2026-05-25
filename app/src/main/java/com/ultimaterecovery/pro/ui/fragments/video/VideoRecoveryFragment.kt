package com.ultimaterecovery.pro.ui.fragments.video

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultimaterecovery.pro.utils.storage.formatFileSize
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity
import com.ultimaterecovery.pro.databinding.FragmentVideoRecoveryBinding
import com.ultimaterecovery.pro.databinding.ItemRecoveredVideoBinding
import com.ultimaterecovery.pro.ui.activities.PreviewActivity
import com.ultimaterecovery.pro.ui.viewmodel.VideoRecoveryUiState
import com.ultimaterecovery.pro.ui.viewmodel.VideoRecoveryViewModel
import com.ultimaterecovery.pro.ui.viewmodel.VideoSortBy
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

/**
 * Video recovery fragment.
 *
 * Features:
 * - Grid layout of recovered videos with thumbnails
 * - Duration overlay on each thumbnail
 * - Play icon overlay
 * - Multi-select with checkbox
 * - Sort and filter options
 * - Empty state with illustration
 * - SwipeRefreshLayout
 */
@AndroidEntryPoint
class VideoRecoveryFragment : Fragment() {

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: FragmentVideoRecoveryBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: VideoRecoveryViewModel by viewModels()

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private lateinit var videoAdapter: VideoAdapter

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            _binding = FragmentVideoRecoveryBinding.inflate(inflater, container, false)
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
        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
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
    // Toolbar
    // ──────────────────────────────────────────

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.setSupportActionBar(binding.toolbar)
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            setTitle(R.string.video_recovery)
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    // ──────────────────────────────────────────
    // RecyclerView
    // ──────────────────────────────────────────

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter(
            onClick = { video -> onVideoClicked(video) },
            onLongClick = { video -> onVideoLongClicked(video) },
            onSelect = { id -> viewModel.selectVideo(id) }
        )
        binding.recyclerViewVideos.apply {
            adapter = videoAdapter
            layoutManager = GridLayoutManager(requireContext(), 2)
            setHasFixedSize(true)
        }
    }

    // ──────────────────────────────────────────
    // Swipe refresh
    // ──────────────────────────────────────────

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadVideos()
        }
    }

    // ──────────────────────────────────────────
    // Controls
    // ──────────────────────────────────────────

    private fun setupControls() {
        binding.fabRecover.setOnClickListener { viewModel.recoverSelected() }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnDeselectAll.setOnClickListener { viewModel.deselectAll() }
        binding.btnSort.setOnClickListener { showSortDialog() }
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

    private fun renderState(state: VideoRecoveryUiState) {
        try {
        val binding = _binding ?: return
            binding.swipeRefresh.isRefreshing = false

            if (state.isLoading) {
                binding.shimmerFrameLayout?.visibility = View.VISIBLE
                binding.shimmerFrameLayout.startShimmer()
                binding.recyclerViewVideos?.visibility = View.GONE
            } else {
                binding.shimmerFrameLayout?.visibility = View.GONE
                binding.shimmerFrameLayout.stopShimmer()
                binding.recyclerViewVideos?.visibility = View.VISIBLE
            }

            videoAdapter.submitList(state.filteredVideos)

            val selectedCount = state.selectedVideoIds.size
            if (selectedCount > 0) {
                binding.layoutSelectionBar?.visibility = View.VISIBLE
                binding.tvSelectedCount.text = getString(R.string.selected_count, selectedCount)
                binding.fabRecover?.visibility = View.VISIBLE
            } else {
                binding.layoutSelectionBar?.visibility = View.GONE
                binding.fabRecover?.visibility = View.GONE
            }

            if (state.filteredVideos.isEmpty() && !state.isLoading) {
                binding.layoutEmptyState?.visibility = View.VISIBLE
                binding.recyclerViewVideos?.visibility = View.GONE
            } else {
                binding.layoutEmptyState?.visibility = View.GONE
            }

            // Recovery progress
            state.recoveryProgress?.let { progress ->
                binding.layoutRecoveryProgress?.visibility = View.VISIBLE
                val percent = if (progress.totalFiles > 0) {
                    (progress.processedFiles * 100 / progress.totalFiles)
                } else 0
                binding.progressRecovery.progress = percent
                binding.tvRecoveryProgress.text = getString(R.string.recovery_progress, percent)
            } ?: run {
                binding.layoutRecoveryProgress?.visibility = View.GONE
            }

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
    // Video click handlers
    // ──────────────────────────────────────────

    private fun onVideoClicked(video: RecoveredFileEntity) {
        if (viewModel.uiState.value.selectedVideoIds.isNotEmpty()) {
            viewModel.selectVideo(video.id)
        } else {
            openPreview(video)
        }
    }

    private fun onVideoLongClicked(video: RecoveredFileEntity): Boolean {
        viewModel.selectVideo(video.id)
        return true
    }

    private fun openPreview(video: RecoveredFileEntity) {
        val intent = Intent(requireContext(), PreviewActivity::class.java).apply {
            putExtra(PreviewActivity.EXTRA_FILE_PATH, video.filePath)
            putExtra(PreviewActivity.EXTRA_FILE_NAME, video.fileName)
            putExtra(PreviewActivity.EXTRA_FILE_SIZE, video.fileSize)
            putExtra(PreviewActivity.EXTRA_MIME_TYPE, video.mimeType)
            putExtra(PreviewActivity.EXTRA_FILE_ID, video.id)
        }
        startActivity(intent)
    }

    // ──────────────────────────────────────────
    // Sort dialog
    // ──────────────────────────────────────────

    private fun showSortDialog() {
        val sortOptions = VideoSortBy.values().map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sort_by)
            .setItems(sortOptions) { _, which ->
                viewModel.sortVideos(VideoSortBy.values()[which])
            }
            .show()
    }

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private inner class VideoAdapter(
        private val onClick: (RecoveredFileEntity) -> Unit,
        private val onLongClick: (RecoveredFileEntity) -> Boolean,
        private val onSelect: (Long) -> Unit
    ) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

        private var items: List<RecoveredFileEntity> = emptyList()

        fun submitList(newItems: List<RecoveredFileEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
            val binding = ItemRecoveredVideoBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VideoViewHolder(binding)
        }

        override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VideoViewHolder(
            private val binding: ItemRecoveredVideoBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(video: RecoveredFileEntity) {
                val isSelected = video.id in viewModel.uiState.value.selectedVideoIds

                // Thumbnail
                val thumbPath = video.thumbnailPath ?: video.filePath
                Glide.with(binding.root.context)
                    .load(File(thumbPath))
                    .placeholder(R.drawable.ic_photo_placeholder)
                    .error(R.drawable.ic_broken_image)
                    .centerCrop()
                    .into(binding.ivVideoThumbnail)

                // Play icon overlay
                binding.ivPlayIcon.visibility = View.VISIBLE

                // Duration overlay — placeholder; in production, extract from metadata
                binding.tvDuration?.visibility = View.VISIBLE
                binding.tvDuration.text = "0:00"

                // File name
                binding.tvFileName.text = video.fileName

                // File size
                binding.tvFileSize.text = formatFileSize(video.fileSize)

                // Selection overlay
                binding.checkboxOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.checkboxOverlay.isChecked = isSelected
                binding.viewSelectionDim.visibility = if (isSelected) View.VISIBLE else View.GONE

                // Click handlers
                binding.root.setOnClickListener { onClick(video) }
                binding.root.setOnLongClickListener { onLongClick(video) }
                binding.checkboxOverlay.setOnClickListener { onSelect(video.id) }
            }
        }
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
