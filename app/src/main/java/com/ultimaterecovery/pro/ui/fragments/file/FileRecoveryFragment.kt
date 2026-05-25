package com.ultimaterecovery.pro.ui.fragments.file

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.ultimaterecovery.pro.utils.storage.formatFileSize
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.databinding.FragmentFileRecoveryBinding
import com.ultimaterecovery.pro.databinding.ItemFileListBinding
import com.ultimaterecovery.pro.ui.activities.PreviewActivity
import com.ultimaterecovery.pro.ui.viewmodel.FileRecoveryUiState
import com.ultimaterecovery.pro.ui.viewmodel.FileRecoveryViewModel
import com.ultimaterecovery.pro.ui.viewmodel.FileSortBy
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * File recovery fragment with list layout.
 *
 * Features:
 * - List layout with file type icons and size info
 * - Category tabs: Documents, Audio, Archives, APKs, Other
 * - Multi-select with checkboxes
 * - Sort and filter options
 * - Empty state with illustration
 * - SwipeRefreshLayout
 */
@AndroidEntryPoint
class FileRecoveryFragment : Fragment() {

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: FragmentFileRecoveryBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: FileRecoveryViewModel by viewModels()

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private lateinit var fileAdapter: FileAdapter

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            _binding = FragmentFileRecoveryBinding.inflate(inflater, container, false)
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
        setupCategoryTabs()
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
            setTitle(R.string.file_recovery)
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    // ──────────────────────────────────────────
    // Category tabs
    // ──────────────────────────────────────────

    private fun setupCategoryTabs() {
        val categories = listOf(
            FileCategory.DOCUMENT to R.string.tab_documents,
            FileCategory.AUDIO to R.string.tab_audio,
            FileCategory.ARCHIVE to R.string.tab_archives,
            FileCategory.APK to R.string.tab_apks,
            FileCategory.OTHER to R.string.tab_other
        )

        categories.forEach { (_, titleRes) ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(titleRes))
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val index = tab?.position ?: 0
                val category = categories.getOrNull(index)?.first ?: FileCategory.DOCUMENT
                viewModel.setCategory(category)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // ──────────────────────────────────────────
    // RecyclerView
    // ──────────────────────────────────────────

    private fun setupRecyclerView() {
        fileAdapter = FileAdapter(
            onClick = { file -> onFileClicked(file) },
            onLongClick = { file -> onFileLongClicked(file) },
            onSelect = { id -> viewModel.selectFile(id) }
        )
        binding.recyclerViewFiles.apply {
            adapter = fileAdapter
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
        }
    }

    // ──────────────────────────────────────────
    // Swipe refresh
    // ──────────────────────────────────────────

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadFiles()
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

    private fun renderState(state: FileRecoveryUiState) {
        try {
        val binding = _binding ?: return
            binding.swipeRefresh.isRefreshing = false

            if (state.isLoading) {
                binding.shimmerFrameLayout?.visibility = View.VISIBLE
                binding.shimmerFrameLayout.startShimmer()
                binding.recyclerViewFiles?.visibility = View.GONE
            } else {
                binding.shimmerFrameLayout?.visibility = View.GONE
                binding.shimmerFrameLayout.stopShimmer()
                binding.recyclerViewFiles?.visibility = View.VISIBLE
            }

            fileAdapter.submitList(state.filteredFiles)

            val selectedCount = state.selectedFileIds.size
            if (selectedCount > 0) {
                binding.layoutSelectionBar?.visibility = View.VISIBLE
                binding.tvSelectedCount.text = getString(R.string.selected_count, selectedCount)
                binding.fabRecover?.visibility = View.VISIBLE
            } else {
                binding.layoutSelectionBar?.visibility = View.GONE
                binding.fabRecover?.visibility = View.GONE
            }

            if (state.filteredFiles.isEmpty() && !state.isLoading) {
                binding.layoutEmptyState?.visibility = View.VISIBLE
                binding.recyclerViewFiles?.visibility = View.GONE
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
    // File click handlers
    // ──────────────────────────────────────────

    private fun onFileClicked(file: RecoveredFileEntity) {
        if (viewModel.uiState.value.selectedFileIds.isNotEmpty()) {
            viewModel.selectFile(file.id)
        } else {
            openPreview(file)
        }
    }

    private fun onFileLongClicked(file: RecoveredFileEntity): Boolean {
        viewModel.selectFile(file.id)
        return true
    }

    private fun openPreview(file: RecoveredFileEntity) {
        val intent = Intent(requireContext(), PreviewActivity::class.java).apply {
            putExtra(PreviewActivity.EXTRA_FILE_PATH, file.filePath)
            putExtra(PreviewActivity.EXTRA_FILE_NAME, file.fileName)
            putExtra(PreviewActivity.EXTRA_FILE_SIZE, file.fileSize)
            putExtra(PreviewActivity.EXTRA_MIME_TYPE, file.mimeType)
            putExtra(PreviewActivity.EXTRA_FILE_ID, file.id)
        }
        startActivity(intent)
    }

    // ──────────────────────────────────────────
    // Sort dialog
    // ──────────────────────────────────────────

    private fun showSortDialog() {
        val sortOptions = FileSortBy.values().map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sort_by)
            .setItems(sortOptions) { _, which ->
                viewModel.sortFiles(FileSortBy.values()[which])
            }
            .show()
    }

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private inner class FileAdapter(
        private val onClick: (RecoveredFileEntity) -> Unit,
        private val onLongClick: (RecoveredFileEntity) -> Boolean,
        private val onSelect: (Long) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

        private var items: List<RecoveredFileEntity> = emptyList()

        fun submitList(newItems: List<RecoveredFileEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
            val binding = ItemFileListBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return FileViewHolder(binding)
        }

        override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class FileViewHolder(
            private val binding: ItemFileListBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(file: RecoveredFileEntity) {
                val isSelected = file.id in viewModel.uiState.value.selectedFileIds

                // File type icon
                binding.ivFileIcon.setImageResource(iconForExtension(file.fileExtension))

                // File name
                binding.tvFileName.text = file.fileName

                // File size
                binding.tvFileSize.text = formatFileSize(file.fileSize)

                // Date
                binding.tvFileDate.text = DateFormat.getDateTimeInstance()
                    .format(Date(file.originalDate))

                // Extension badge
                binding.tvFileExtension.text = file.fileExtension.uppercase()

                // Checkbox
                binding.checkbox.isChecked = isSelected
                binding.checkbox.setOnClickListener { onSelect(file.id) }

                // Background highlight for selected items
                binding.root.isActivated = isSelected

                // Click handlers
                binding.root.setOnClickListener { onClick(file) }
                binding.root.setOnLongClickListener { onLongClick(file) }
            }
        }
    }

    // ──────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────

    private fun iconForExtension(ext: String): Int = when (ext.lowercase()) {
        "pdf"   -> R.drawable.ic_pdf
        "doc", "docx" -> R.drawable.ic_document
        "xls", "xlsx" -> R.drawable.ic_document
        "ppt", "pptx" -> R.drawable.ic_document
        "txt"   -> R.drawable.ic_document
        "mp3", "wav", "flac", "aac" -> R.drawable.ic_audio
        "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_archive
        "apk"   -> R.drawable.ic_apk
        else    -> R.drawable.ic_file
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
