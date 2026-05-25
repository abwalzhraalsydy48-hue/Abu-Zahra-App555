package com.ultimaterecovery.pro.ui.fragments.photo

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity
import com.ultimaterecovery.pro.databinding.FragmentPhotoRecoveryBinding
import com.ultimaterecovery.pro.databinding.ItemPhotoGridBinding
import com.ultimaterecovery.pro.databinding.BottomSheetPhotoFilterBinding
import com.ultimaterecovery.pro.engine.recovery.RecoveryProgress
import com.ultimaterecovery.pro.ui.activities.PreviewActivity
import com.ultimaterecovery.pro.ui.viewmodel.PhotoFilter
import com.ultimaterecovery.pro.ui.viewmodel.PhotoRecoveryUiState
import com.ultimaterecovery.pro.ui.viewmodel.PhotoRecoveryViewModel
import com.ultimaterecovery.pro.ui.viewmodel.PhotoSortBy
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Photo recovery fragment.
 *
 * Features:
 * - Grid layout of recovered photos with thumbnails
 * - Multi-select with checkbox overlay
 * - Filter bottom sheet (date range, size, type)
 * - Sort dropdown
 * - Select all / deselect all
 * - Recover button with count badge
 * - Empty state with illustration
 * - SwipeRefreshLayout
 */
@AndroidEntryPoint
class PhotoRecoveryFragment : Fragment() {

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: FragmentPhotoRecoveryBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: PhotoRecoveryViewModel by viewModels()

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private lateinit var photoAdapter: PhotoAdapter

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            _binding = FragmentPhotoRecoveryBinding.inflate(inflater, container, false)
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
        setupMenu()
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
            setTitle(R.string.photo_recovery)
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
        photoAdapter = PhotoAdapter(
            onClick = { photo -> onPhotoClicked(photo) },
            onLongClick = { photo -> onPhotoLongClicked(photo) },
            onSelect = { id -> viewModel.selectPhoto(id) }
        )
        binding.recyclerViewPhotos.apply {
            adapter = photoAdapter
            layoutManager = GridLayoutManager(requireContext(), 3)
            setHasFixedSize(true)
        }
    }

    // ──────────────────────────────────────────
    // Swipe refresh
    // ──────────────────────────────────────────

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadPhotos()
        }
    }

    // ──────────────────────────────────────────
    // Controls
    // ──────────────────────────────────────────

    private fun setupControls() {
        binding.fabRecover.setOnClickListener {
            viewModel.recoverSelected()
        }

        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnDeselectAll.setOnClickListener { viewModel.deselectAll() }

        binding.btnFilter.setOnClickListener { showFilterBottomSheet() }
        binding.btnSort.setOnClickListener { showSortDialog() }
    }

    // ──────────────────────────────────────────
    // Menu
    // ──────────────────────────────────────────

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_photo_recovery, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_select_all -> { viewModel.selectAll(); true }
                    R.id.action_filter -> { showFilterBottomSheet(); true }
                    R.id.action_sort -> { showSortDialog(); true }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
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

    private fun renderState(state: PhotoRecoveryUiState) {
        try {
        val binding = _binding ?: return
            // Swipe refresh
            binding.swipeRefresh.isRefreshing = false

            // Loading
            if (state.isLoading) {
                binding.shimmerFrameLayout?.visibility = View.VISIBLE
                binding.shimmerFrameLayout.startShimmer()
                binding.recyclerViewPhotos?.visibility = View.GONE
            } else {
                binding.shimmerFrameLayout?.visibility = View.GONE
                binding.shimmerFrameLayout.stopShimmer()
                binding.recyclerViewPhotos?.visibility = View.VISIBLE
            }

            // Photos list
            photoAdapter.submitList(state.filteredPhotos)

            // Selection
            val selectedCount = state.selectedPhotoIds.size
            if (selectedCount > 0) {
                binding.layoutSelectionBar?.visibility = View.VISIBLE
                binding.tvSelectedCount.text = getString(R.string.selected_count, selectedCount)
                binding.fabRecover?.visibility = View.VISIBLE
            } else {
                binding.layoutSelectionBar?.visibility = View.GONE
                binding.fabRecover?.visibility = View.GONE
            }

            // Empty state
            if (state.filteredPhotos.isEmpty() && !state.isLoading) {
                binding.layoutEmptyState?.visibility = View.VISIBLE
                binding.recyclerViewPhotos?.visibility = View.GONE
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

            // Error
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
    // Photo click handlers
    // ──────────────────────────────────────────

    private fun onPhotoClicked(photo: RecoveredFileEntity) {
        if (viewModel.uiState.value.selectedPhotoIds.isNotEmpty()) {
            viewModel.selectPhoto(photo.id)
        } else {
            openPreview(photo)
        }
    }

    private fun onPhotoLongClicked(photo: RecoveredFileEntity): Boolean {
        viewModel.selectPhoto(photo.id)
        return true
    }

    private fun openPreview(photo: RecoveredFileEntity) {
        val intent = Intent(requireContext(), PreviewActivity::class.java).apply {
            putExtra(PreviewActivity.EXTRA_FILE_PATH, photo.filePath)
            putExtra(PreviewActivity.EXTRA_FILE_NAME, photo.fileName)
            putExtra(PreviewActivity.EXTRA_FILE_SIZE, photo.fileSize)
            putExtra(PreviewActivity.EXTRA_MIME_TYPE, photo.mimeType)
            putExtra(PreviewActivity.EXTRA_FILE_ID, photo.id)
        }
        startActivity(intent)
    }

    // ──────────────────────────────────────────
    // Filter bottom sheet
    // ──────────────────────────────────────────

    private fun showFilterBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val filterBinding = BottomSheetPhotoFilterBinding.inflate(layoutInflater)
        dialog.setContentView(filterBinding.root)

        // Date range
        filterBinding.btnDateStart.setOnClickListener {
            showDatePicker { dateStart ->
                viewModel.filterPhotos(
                    viewModel.uiState.value.currentFilter.copy(dateStart = dateStart)
                )
            }
        }

        filterBinding.btnDateEnd.setOnClickListener {
            showDatePicker { dateEnd ->
                viewModel.filterPhotos(
                    viewModel.uiState.value.currentFilter.copy(dateEnd = dateEnd)
                )
            }
        }

        // Size filter
        filterBinding.sliderSizeRange.addOnChangeListener { _, value, _ ->
            viewModel.filterPhotos(
                viewModel.uiState.value.currentFilter.copy(minSize = value.toLong() * 1024)
            )
        }

        // MIME type
        val mimeTypes = listOf("image/jpeg", "image/png", "image/gif", "image/webp")
        mimeTypes.forEach { mime ->
            val chip = Chip(requireContext()).apply {
                text = mime.substringAfter("/")
                isCheckable = true
                setOnClickListener {
                    viewModel.filterPhotos(
                        viewModel.uiState.value.currentFilter.copy(mimeType = if (isChecked) mime else null)
                    )
                }
            }
            filterBinding.chipGroupMimeTypes.addView(chip)
        }

        // Apply / Clear
        filterBinding.btnApply.setOnClickListener { dialog.dismiss() }
        filterBinding.btnClear.setOnClickListener {
            viewModel.filterPhotos(PhotoFilter())
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDatePicker(onDateSelected: (Long) -> Unit) {
        val datePicker = com.google.android.material.datepicker.MaterialDatePicker.Builder
            .datePicker()
            .setTitleText(R.string.select_date)
            .build()
        datePicker.addOnPositiveButtonClickListener { selection ->
            onDateSelected(selection)
        }
        datePicker.show(childFragmentManager, "date_picker")
    }

    // ──────────────────────────────────────────
    // Sort dialog
    // ──────────────────────────────────────────

    private fun showSortDialog() {
        val sortOptions = PhotoSortBy.values().map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sort_by)
            .setItems(sortOptions) { _, which ->
                viewModel.sortPhotos(PhotoSortBy.values()[which])
            }
            .show()
    }

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private inner class PhotoAdapter(
        private val onClick: (RecoveredFileEntity) -> Unit,
        private val onLongClick: (RecoveredFileEntity) -> Boolean,
        private val onSelect: (Long) -> Unit
    ) : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

        private var items: List<RecoveredFileEntity> = emptyList()

        fun submitList(newItems: List<RecoveredFileEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val binding = ItemPhotoGridBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return PhotoViewHolder(binding)
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class PhotoViewHolder(
            private val binding: ItemPhotoGridBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(photo: RecoveredFileEntity) {
                val isSelected = photo.id in viewModel.uiState.value.selectedPhotoIds

                // Thumbnail
                val thumbPath = photo.thumbnailPath ?: photo.filePath
                Glide.with(binding.root.context)
                    .load(File(thumbPath))
                    .placeholder(R.drawable.ic_photo_placeholder)
                    .error(R.drawable.ic_broken_image)
                    .centerCrop()
                    .into(binding.ivPhotoThumbnail)

                // Selection overlay
                binding.checkboxOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.checkboxOverlay.isChecked = isSelected
                binding.viewSelectionDim.visibility = if (isSelected) View.VISIBLE else View.GONE

                // File name
                binding.tvFileName.text = photo.fileName

                // Click handlers
                binding.root.setOnClickListener { onClick(photo) }
                binding.root.setOnLongClickListener { onLongClick(photo) }
                binding.checkboxOverlay.setOnClickListener { onSelect(photo.id) }
            }
        }
    }
}
