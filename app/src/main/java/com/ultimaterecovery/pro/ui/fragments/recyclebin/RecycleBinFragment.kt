package com.ultimaterecovery.pro.ui.fragments.recyclebin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
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
import com.ultimaterecovery.pro.data.local.entity.RecycleBinItemEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.databinding.FragmentRecycleBinBinding
import com.ultimaterecovery.pro.databinding.ItemRecycleBinGridBinding
import com.ultimaterecovery.pro.ui.viewmodel.RecycleBinUiState
import com.ultimaterecovery.pro.ui.viewmodel.RecycleBinViewModel
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Recycle bin fragment.
 *
 * Features:
 * - Grid of recycled items with thumbnails
 * - Restore / permanent delete actions
 * - Multi-select with batch operations
 * - Auto-clean settings (configurable days and storage limit)
 * - Search and filter by category
 * - Storage usage indicator
 * - Empty state with illustration
 */
@AndroidEntryPoint
class RecycleBinFragment : Fragment() {

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: FragmentRecycleBinBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: RecycleBinViewModel by viewModels()

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private lateinit var recycleBinAdapter: RecycleBinAdapter

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            _binding = FragmentRecycleBinBinding.inflate(inflater, container, false)
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
        setupSearch()
        setupControls()
        setupSettings()
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
            setTitle(R.string.recycle_bin)
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
        recycleBinAdapter = RecycleBinAdapter(
            onClick = { item -> onItemClicked(item) },
            onLongClick = { item -> onItemLongClicked(item) },
            onSelect = { id -> viewModel.selectItem(id) },
            onRestore = { item -> viewModel.restoreItem(item.id) },
            onDelete = { item -> confirmDelete(item) }
        )
        binding.recyclerViewItems.apply {
            adapter = recycleBinAdapter
            layoutManager = GridLayoutManager(requireContext(), 3)
            setHasFixedSize(true)
        }
    }

    // ──────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.search(newText.orEmpty())
                return true
            }
        })
    }

    // ──────────────────────────────────────────
    // Controls
    // ──────────────────────────────────────────

    private fun setupControls() {
        binding.fabRestore.setOnClickListener { viewModel.restoreSelected() }
        binding.fabDelete.setOnClickListener { confirmDeleteSelected() }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnDeselectAll.setOnClickListener { viewModel.deselectAll() }
        binding.btnCleanExpired.setOnClickListener { viewModel.cleanExpired() }
    }

    // ──────────────────────────────────────────
    // Settings
    // ──────────────────────────────────────────

    private fun setupSettings() {
        binding.sliderAutoDeleteDays.addOnChangeListener { _, value, _ ->
            viewModel.setAutoDeleteDays(value.toInt())
        }

        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) viewModel.startMonitoring() else viewModel.stopMonitoring()
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

    private fun renderState(state: RecycleBinUiState) {
        try {
        val binding = _binding ?: return
            if (state.isLoading) {
                binding.shimmerFrameLayout?.visibility = View.VISIBLE
                binding.shimmerFrameLayout.startShimmer()
                binding.recyclerViewItems?.visibility = View.GONE
            } else {
                binding.shimmerFrameLayout?.visibility = View.GONE
                binding.shimmerFrameLayout.stopShimmer()
                binding.recyclerViewItems?.visibility = View.VISIBLE
            }

            recycleBinAdapter.submitList(state.filteredItems)

            // Storage usage
            binding.tvStorageUsed.text = formatFileSize(state.totalStorageUsed)
            binding.tvItemCount.text = getString(R.string.item_count, state.totalItemCount)

            // Selection
            val selectedCount = state.selectedIds.size
            if (selectedCount > 0) {
                binding.layoutSelectionBar?.visibility = View.VISIBLE
                binding.tvSelectedCount.text = getString(R.string.selected_count, selectedCount)
                binding.fabRestore?.visibility = View.VISIBLE
                binding.fabDelete?.visibility = View.VISIBLE
            } else {
                binding.layoutSelectionBar?.visibility = View.GONE
                binding.fabRestore?.visibility = View.GONE
                binding.fabDelete?.visibility = View.GONE
            }

            // Empty state
            if (state.filteredItems.isEmpty() && !state.isLoading) {
                binding.layoutEmptyState?.visibility = View.VISIBLE
                binding.recyclerViewItems?.visibility = View.GONE
            } else {
                binding.layoutEmptyState?.visibility = View.GONE
            }

            // Operation progress
            binding.progressOperation.visibility =
                if (state.isRestoring || state.isDeleting || state.isCleaning) View.VISIBLE else View.GONE

            // Success message
            state.successMessage?.let { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                viewModel.clearSuccessMessage()
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
    // Click handlers
    // ──────────────────────────────────────────

    private fun onItemClicked(item: RecycleBinItemEntity) {
        if (viewModel.uiState.value.selectedIds.isNotEmpty()) {
            viewModel.selectItem(item.id)
        }
    }

    private fun onItemLongClicked(item: RecycleBinItemEntity): Boolean {
        viewModel.selectItem(item.id)
        return true
    }

    private fun confirmDelete(item: RecycleBinItemEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_permanently_title)
            .setMessage(getString(R.string.delete_permanently_message, item.fileName))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteItem(item.id)
            }
            .setNeutralButton(R.string.secure_delete) { _, _ ->
                viewModel.deleteItem(item.id, secureWipe = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteSelected() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_selected_title)
            .setMessage(R.string.delete_selected_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteSelected()
            }
            .setNeutralButton(R.string.secure_delete) { _, _ ->
                viewModel.deleteSelected(secureWipe = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private inner class RecycleBinAdapter(
        private val onClick: (RecycleBinItemEntity) -> Unit,
        private val onLongClick: (RecycleBinItemEntity) -> Boolean,
        private val onSelect: (Long) -> Unit,
        private val onRestore: (RecycleBinItemEntity) -> Unit,
        private val onDelete: (RecycleBinItemEntity) -> Unit
    ) : RecyclerView.Adapter<RecycleBinAdapter.RecycleBinViewHolder>() {

        private var items: List<RecycleBinItemEntity> = emptyList()

        fun submitList(newItems: List<RecycleBinItemEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecycleBinViewHolder {
            val binding = ItemRecycleBinGridBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return RecycleBinViewHolder(binding)
        }

        override fun onBindViewHolder(holder: RecycleBinViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class RecycleBinViewHolder(
            private val binding: ItemRecycleBinGridBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(item: RecycleBinItemEntity) {
                val isSelected = item.id in viewModel.uiState.value.selectedIds

                // Thumbnail
                Glide.with(binding.root.context)
                    .load(File(item.originalPath))
                    .placeholder(R.drawable.ic_file)
                    .error(iconForCategory(item.category))
                    .centerCrop()
                    .into(binding.ivThumbnail)

                // File name
                binding.tvFileName.text = item.fileName

                // Deleted date
                binding.tvDeletedDate.text = DateFormat.getDateTimeInstance()
                    .format(Date(item.deletedDate))

                // Expiry indicator
                val now = System.currentTimeMillis()
                if (item.expiryDate <= now) {
                    binding.tvExpiry.text = getString(R.string.expired)
                    binding.tvExpiry.setTextColor(
                        androidx.core.content.ContextCompat.getColor(binding.root.context, android.R.color.holo_red_dark)
                    )
                } else {
                    val daysLeft = ((item.expiryDate - now) / (1000 * 60 * 60 * 24)).toInt()
                    binding.tvExpiry.text = getString(R.string.days_left, daysLeft)
                }

                // Selection overlay
                binding.checkboxOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.checkboxOverlay.isChecked = isSelected
                binding.viewSelectionDim.visibility = if (isSelected) View.VISIBLE else View.GONE

                // Action buttons - handled via fragment-level FABs
                // btnRestore/btnDelete are in fragment layout, not item layout

                // Click handlers
                binding.root.setOnClickListener { onClick(item) }
                binding.root.setOnLongClickListener { onLongClick(item) }
                binding.checkboxOverlay.setOnClickListener { onSelect(item.id) }
            }
        }
    }

    // ──────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────

    private fun iconForCategory(category: FileCategory): Int = when (category) {
        FileCategory.PHOTO    -> R.drawable.ic_photo
        FileCategory.VIDEO    -> R.drawable.ic_video
        FileCategory.DOCUMENT -> R.drawable.ic_document
        FileCategory.AUDIO    -> R.drawable.ic_audio
        FileCategory.ARCHIVE  -> R.drawable.ic_archive
        FileCategory.APK      -> R.drawable.ic_apk
        FileCategory.OTHER    -> R.drawable.ic_file
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
