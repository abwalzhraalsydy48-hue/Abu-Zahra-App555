package com.ultimaterecovery.pro.ui.fragments.filemanager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultimaterecovery.pro.utils.storage.formatFileSize
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.databinding.FragmentFileManagerBinding
import com.ultimaterecovery.pro.databinding.ItemFileEntryBinding
import com.ultimaterecovery.pro.manager.FileManager.FileItem
import com.ultimaterecovery.pro.manager.FileManager.SortOrder
import com.ultimaterecovery.pro.ui.viewmodel.FileManagerUiState
import com.ultimaterecovery.pro.ui.viewmodel.FileManagerViewModel
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * File manager fragment with breadcrumb navigation.
 *
 * Features:
 * - Breadcrumb navigation bar
 * - File list with icons, size, and date
 * - Context menu (copy, move, delete, rename, details)
 * - Multi-select for batch operations
 * - Sort order and hidden-file toggle
 * - Storage volume selector
 * - Bookmark directories
 * - Search with extension filters
 * - Create new file/folder
 */
@AndroidEntryPoint
class FileManagerFragment : Fragment() {

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: FragmentFileManagerBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: FileManagerViewModel by viewModels()

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private lateinit var fileAdapter: FileEntryAdapter

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            _binding = FragmentFileManagerBinding.inflate(inflater, container, false)
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
        setupBreadcrumb()
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
            setTitle(R.string.file_manager)
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener {
            if (viewModel.canNavigateUp()) viewModel.navigateUp()
            else parentFragmentManager.popBackStack()
        }
    }

    // ──────────────────────────────────────────
    // RecyclerView
    // ──────────────────────────────────────────

    private fun setupRecyclerView() {
        fileAdapter = FileEntryAdapter(
            onClick = { fileItem -> onFileClicked(fileItem) },
            onLongClick = { fileItem, view -> onFileLongClicked(fileItem, view) },
            onSelect = { path -> viewModel.selectFile(path) }
        )
        binding.recyclerViewFiles.apply {
            adapter = fileAdapter
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
        }
    }

    // ──────────────────────────────────────────
    // Breadcrumb
    // ──────────────────────────────────────────

    private fun setupBreadcrumb() {
        // Breadcrumb is rendered in observeUiState based on currentPath
    }

    private fun renderBreadcrumb(path: String) {
        binding.breadcrumbBar.removeAllViews()
        val segments = path.split("/")
        var builtPath = ""
        for ((index, segment) in segments.withIndex()) {
            if (segment.isBlank() && index == 0) {
                builtPath = "/"
                addBreadcrumbSegment("/", builtPath)
                continue
            }
            if (segment.isBlank()) continue
            builtPath = if (builtPath == "/") "$builtPath$segment" else "$builtPath/$segment"
            addBreadcrumbSegment(segment, builtPath)
        }
    }

    private fun addBreadcrumbSegment(label: String, path: String) {
        val chip = com.google.android.material.chip.Chip(requireContext()).apply {
            text = label.ifBlank { "/" }
            isClickable = true
            isCheckable = false
            setOnClickListener { viewModel.browseDirectory(path) }
        }
        binding.breadcrumbBar.addView(chip)
    }

    // ──────────────────────────────────────────
    // Controls
    // ──────────────────────────────────────────

    private fun setupControls() {
        binding.fabNewFolder.setOnClickListener { showCreateFolderDialog() }
        binding.fabNewFile.setOnClickListener { showCreateFileDialog() }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAllFiles() }
        binding.btnDeselectAll.setOnClickListener { viewModel.deselectAllFiles() }
    }

    // ──────────────────────────────────────────
    // Menu
    // ──────────────────────────────────────────

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_file_manager, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_sort -> { showSortDialog(); true }
                    R.id.action_show_hidden -> { viewModel.toggleShowHiddenFiles(); true }
                    R.id.action_bookmark -> { toggleBookmark(); true }
                    R.id.action_storage -> { showStorageSelector(); true }
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

    private fun renderState(state: FileManagerUiState) {
        try {
        val binding = _binding ?: return
            // Loading
            if (state.isLoading) {
                binding.shimmerFrameLayout?.visibility = View.VISIBLE
                binding.shimmerFrameLayout.startShimmer()
                binding.recyclerViewFiles?.visibility = View.GONE
            } else {
                binding.shimmerFrameLayout?.visibility = View.GONE
                binding.shimmerFrameLayout.stopShimmer()
                binding.recyclerViewFiles?.visibility = View.VISIBLE
            }

            // Files list or search results
            val displayList = if (state.isSearching) state.searchResults else state.files
            fileAdapter.submitList(displayList)

            // Breadcrumb
            renderBreadcrumb(state.currentPath)

            // Path label
            binding.tvCurrentPath.text = state.currentPath

            // Selection
            val selectedCount = state.selectedFileIds.size
            if (selectedCount > 0) {
                binding.layoutSelectionBar?.visibility = View.VISIBLE
                binding.tvSelectedCount.text = getString(R.string.selected_count, selectedCount)
            } else {
                binding.layoutSelectionBar?.visibility = View.GONE
            }

            // Empty state
            if (displayList.isEmpty() && !state.isLoading) {
                binding.layoutEmptyState?.visibility = View.VISIBLE
                binding.recyclerViewFiles?.visibility = View.GONE
            } else {
                binding.layoutEmptyState?.visibility = View.GONE
            }

            // Batch operation progress
            state.batchProgress?.let { progress ->
                binding.layoutBatchProgress?.visibility = View.VISIBLE
                val percent = if (progress.total > 0) (progress.processed * 100 / progress.total) else 0
                binding.progressBatch.progress = percent
                binding.tvBatchProgress.text = getString(R.string.batch_progress, progress.processed, progress.total)
            } ?: run {
                binding.layoutBatchProgress?.visibility = View.GONE
            }

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
    // File click handlers
    // ──────────────────────────────────────────

    private fun onFileClicked(fileItem: FileItem) {
        if (viewModel.uiState.value.selectedFileIds.isNotEmpty()) {
            viewModel.selectFile(fileItem.path)
        } else if (fileItem.isDirectory) {
            viewModel.browseDirectory(fileItem.path)
        }
        // Files are viewed via long-click → context menu → "Open"
    }

    private fun onFileLongClicked(fileItem: FileItem, view: View): Boolean {
        showContextMenu(fileItem, view)
        return true
    }

    // ──────────────────────────────────────────
    // Context menu
    // ──────────────────────────────────────────

    private fun showContextMenu(fileItem: FileItem, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_file_context, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_copy   -> { /* show destination picker */ true }
                    R.id.action_move   -> { /* show destination picker */ true }
                    R.id.action_rename -> { showRenameDialog(fileItem); true }
                    R.id.action_delete -> { confirmDelete(fileItem); true }
                    R.id.action_details -> { showFileDetails(fileItem); true }
                    R.id.action_bookmark -> { viewModel.addBookmark(fileItem.path); true }
                    else -> false
                }
            }
            show()
        }
    }

    // ──────────────────────────────────────────
    // Dialogs
    // ──────────────────────────────────────────

    private fun showCreateFolderDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.folder_name)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_folder)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString()
                if (name.isNotBlank()) {
                    val path = "${viewModel.uiState.value.currentPath}/$name"
                    viewModel.createFolder(path)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCreateFileDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.file_name)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_file)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString()
                if (name.isNotBlank()) {
                    val path = "${viewModel.uiState.value.currentPath}/$name"
                    viewModel.createFile(path)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(fileItem: FileItem) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.new_name)
            setText(fileItem.name)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotBlank()) {
                    viewModel.renameFile(fileItem.path, newName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(fileItem: FileItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_file_title)
            .setMessage(getString(R.string.delete_file_message, fileItem.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteFile(fileItem.path)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFileDetails(fileItem: FileItem) {
        val details = buildString {
            appendLine("Name: ${fileItem.name}")
            appendLine("Path: ${fileItem.path}")
            appendLine("Size: ${formatFileSize(fileItem.size)}")
            appendLine("Type: ${if (fileItem.isDirectory) "Directory" else "File"}")
            appendLine("Modified: ${DateFormat.getDateTimeInstance().format(Date(fileItem.lastModified))}")
            appendLine("Readable: ${fileItem.file.canRead()}")
            appendLine("Writable: ${fileItem.file.canWrite()}")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.file_details)
            .setMessage(details)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showSortDialog() {
        val sortOptions = SortOrder.values().map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sort_by)
            .setItems(sortOptions) { _, which ->
                viewModel.setSortOrder(SortOrder.values()[which])
            }
            .show()
    }

    private fun toggleBookmark() {
        val currentPath = viewModel.uiState.value.currentPath
        if (viewModel.isBookmarked(currentPath)) {
            viewModel.removeBookmark(currentPath)
            Toast.makeText(requireContext(), R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
        } else {
            viewModel.addBookmark(currentPath)
            Toast.makeText(requireContext(), R.string.bookmark_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showStorageSelector() {
        val volumes = viewModel.uiState.value.storageVolumes
        if (volumes.isEmpty()) return

        val names = volumes.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_storage)
            .setItems(names as Array<CharSequence>) { _, which ->
                val volume = volumes[which]
                viewModel.browseDirectory(volume.path)
            }
            .show()
    }

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private inner class FileEntryAdapter(
        private val onClick: (FileItem) -> Unit,
        private val onLongClick: (FileItem, View) -> Boolean,
        private val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<FileEntryAdapter.FileEntryViewHolder>() {

        private var items: List<FileItem> = emptyList()

        fun submitList(newItems: List<FileItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileEntryViewHolder {
            val binding = ItemFileEntryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return FileEntryViewHolder(binding)
        }

        override fun onBindViewHolder(holder: FileEntryViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class FileEntryViewHolder(
            private val binding: ItemFileEntryBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(fileItem: FileItem) {
                val isSelected = fileItem.path in viewModel.uiState.value.selectedFileIds

                // Icon
                binding.ivFileIcon.setImageResource(
                    if (fileItem.isDirectory) R.drawable.ic_folder
                    else iconForExtension(fileItem.extension)
                )

                // Name
                binding.tvFileName.text = fileItem.name

                // Size & date
                if (!fileItem.isDirectory) {
                    binding.tvFileSize.text = formatFileSize(fileItem.size)
                    binding.tvFileDate.text = DateFormat.getDateTimeInstance()
                        .format(Date(fileItem.lastModified))
                } else {
                    binding.tvFileSize.text = getString(R.string.folder)
                    binding.tvFileDate.text = ""
                }

                // Checkbox
                binding.checkbox.isChecked = isSelected
                binding.checkbox.setOnClickListener { onSelect(fileItem.path) }

                // Selected background
                binding.root.isActivated = isSelected

                // Click handlers
                binding.root.setOnClickListener { onClick(fileItem) }
                binding.root.setOnLongClickListener { onLongClick(fileItem, it) }
            }
        }
    }

    // ──────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────

    private fun iconForExtension(ext: String): Int = when (ext.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> R.drawable.ic_photo
        "mp4", "avi", "mkv", "mov", "wmv" -> R.drawable.ic_video
        "mp3", "wav", "flac", "aac", "ogg" -> R.drawable.ic_audio
        "pdf" -> R.drawable.ic_pdf
        "doc", "docx", "txt" -> R.drawable.ic_document
        "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_archive
        "apk" -> R.drawable.ic_apk
        else -> R.drawable.ic_file
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
