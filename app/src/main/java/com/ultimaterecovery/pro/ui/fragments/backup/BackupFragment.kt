package com.ultimaterecovery.pro.ui.fragments.backup

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
import com.ultimaterecovery.pro.data.local.entity.BackupEntity
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.BackupType
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.CloudProvider
import com.ultimaterecovery.pro.databinding.FragmentBackupBinding
import com.ultimaterecovery.pro.databinding.ItemBackupBinding
import com.ultimaterecovery.pro.ui.viewmodel.BackupUiState
import com.ultimaterecovery.pro.ui.viewmodel.BackupViewModel
import com.ultimaterecovery.pro.utils.backup.BackupManager.BackupProgress
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Backup fragment with create / restore / cloud tabs.
 *
 * Features:
 * - Three tabs: Create, Restore, Cloud
 * - Create backup with type, encryption, and compression options
 * - Backup list with restore/delete actions
 * - Cloud upload/download (Google Drive, Dropbox)
 * - Real-time progress tracking
 * - Backup history
 */
@AndroidEntryPoint
class BackupFragment : Fragment() {

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: BackupViewModel by viewModels()

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private lateinit var backupAdapter: BackupAdapter

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            _binding = FragmentBackupBinding.inflate(inflater, container, false)
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
        setupTabs()
        setupRecyclerView()
        setupCreateControls()
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
            setTitle(R.string.backup)
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    // ──────────────────────────────────────────
    // Tabs
    // ──────────────────────────────────────────

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_create))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_restore))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_cloud))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { binding.layoutCreate?.visibility = View.VISIBLE; binding.layoutRestore?.visibility = View.GONE; binding.layoutCloud?.visibility = View.GONE }
                    1 -> { binding.layoutCreate?.visibility = View.GONE; binding.layoutRestore?.visibility = View.VISIBLE; binding.layoutCloud?.visibility = View.GONE }
                    2 -> { binding.layoutCreate?.visibility = View.GONE; binding.layoutRestore?.visibility = View.GONE; binding.layoutCloud?.visibility = View.VISIBLE }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // ──────────────────────────────────────────
    // RecyclerView
    // ──────────────────────────────────────────

    private fun setupRecyclerView() {
        backupAdapter = BackupAdapter(
            onRestore = { backup -> onRestoreClicked(backup) },
            onDelete = { backup -> onDeleteClicked(backup) },
            onUpload = { backup -> onUploadClicked(backup) },
            onDownload = { backup -> onDownloadClicked(backup) }
        )
        binding.recyclerViewBackups.apply {
            adapter = backupAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    // ──────────────────────────────────────────
    // Create controls
    // ──────────────────────────────────────────

    private fun setupCreateControls() {
        // Backup type selector
        binding.chipTypeFull.setOnClickListener { viewModel.setBackupType(BackupType.FULL) }
        binding.chipTypePhotos.setOnClickListener { viewModel.setBackupType(BackupType.PHOTOS) }
        binding.chipTypeVideos.setOnClickListener { viewModel.setBackupType(BackupType.VIDEOS) }
        binding.chipTypeDocuments.setOnClickListener { viewModel.setBackupType(BackupType.DOCUMENTS) }
        binding.chipTypeSms.setOnClickListener { viewModel.setBackupType(BackupType.SMS) }
        binding.chipTypeCallLogs.setOnClickListener { viewModel.setBackupType(BackupType.CALL_LOG) }

        // Encryption toggle
        binding.switchEncryption.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setEncryption(isChecked)
            binding.tilPassword.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Create button
        binding.btnCreateBackup.setOnClickListener {
            viewModel.createBackup()
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

    private fun renderState(state: BackupUiState) {
        try {
        val binding = _binding ?: return
            // Loading
            if (state.isLoading) {
                binding.shimmerFrameLayout?.visibility = View.VISIBLE
                binding.shimmerFrameLayout.startShimmer()
            } else {
                binding.shimmerFrameLayout?.visibility = View.GONE
                binding.shimmerFrameLayout.stopShimmer()
            }

            // Backup list
            backupAdapter.submitList(state.backupHistory)

            // Create progress
            state.currentProgress?.let { progress ->
                binding.layoutCreateProgress?.visibility = View.VISIBLE
                val percent = (progress.progress * 100).toInt()
                binding.progressCreateBackup.progress = percent
                binding.tvCreateProgressPercent.text = getString(R.string.progress_percent, percent)
                binding.tvCreateProgressPhase.text = progress.phase.name
            } ?: run {
                binding.layoutCreateProgress?.visibility = View.GONE
            }

            // Creating state
            binding.btnCreateBackup.isEnabled = !state.isCreating
            binding.progressCreating.visibility = if (state.isCreating) View.VISIBLE else View.GONE

            // Restoring state
            if (state.isRestoring) {
                binding.progressRestore?.visibility = View.VISIBLE
            } else {
                binding.progressRestore?.visibility = View.GONE
            }

            // Uploading state
            if (state.isUploading) {
                binding.progressCloud?.visibility = View.VISIBLE
            } else {
                binding.progressCloud?.visibility = View.GONE
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

            // Empty state for backup list
            if (state.backupHistory.isEmpty() && !state.isLoading) {
                binding.layoutEmptyState?.visibility = View.VISIBLE
                binding.recyclerViewBackups?.visibility = View.GONE
            } else {
                binding.layoutEmptyState?.visibility = View.GONE
                binding.recyclerViewBackups?.visibility = View.VISIBLE
            }

        } catch (e: Exception) {
            Timber.e(e, "Error rendering state")
        } catch (_: Throwable) {
            // Prevent crash
        }
    }

    // ──────────────────────────────────────────
    // Backup actions
    // ──────────────────────────────────────────

    private fun onRestoreClicked(backup: BackupEntity) {
        var password: String? = null
        if (backup.isEncrypted) {
            // Show password input dialog for encrypted backups
            val input = android.widget.EditText(requireContext()).apply {
                hint = getString(R.string.enter_password)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.enter_backup_password)
                .setView(input)
                .setPositiveButton(R.string.restore) { _, _ ->
                    password = input.text.toString()
                    viewModel.restoreBackup(backup.id, password)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            viewModel.restoreBackup(backup.id)
        }
    }

    private fun onDeleteClicked(backup: BackupEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_backup_title)
            .setMessage(R.string.delete_backup_message)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteBackup(backup.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onUploadClicked(backup: BackupEntity) {
        showCloudProviderDialog { provider ->
            viewModel.uploadToCloud(backup.id, provider)
        }
    }

    private fun onDownloadClicked(backup: BackupEntity) {
        showCloudProviderDialog { provider ->
            viewModel.downloadFromCloud(backup.id, provider)
        }
    }

    private fun showCloudProviderDialog(onSelected: (CloudProvider) -> Unit) {
        val providers = CloudProvider.values().map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_cloud_provider)
            .setItems(providers) { _, which ->
                onSelected(CloudProvider.values()[which])
            }
            .show()
    }

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private inner class BackupAdapter(
        private val onRestore: (BackupEntity) -> Unit,
        private val onDelete: (BackupEntity) -> Unit,
        private val onUpload: (BackupEntity) -> Unit,
        private val onDownload: (BackupEntity) -> Unit
    ) : RecyclerView.Adapter<BackupAdapter.BackupViewHolder>() {

        private var items: List<BackupEntity> = emptyList()

        fun submitList(newItems: List<BackupEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BackupViewHolder {
            val binding = ItemBackupBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return BackupViewHolder(binding)
        }

        override fun onBindViewHolder(holder: BackupViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class BackupViewHolder(
            private val binding: ItemBackupBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(backup: BackupEntity) {
                // Backup type icon
                binding.ivBackupIcon.setImageResource(iconForType(backup.backupType))

                // Backup name / date
                binding.tvBackupName.text = backup.backupName
                binding.tvBackupDate.text = DateFormat.getDateTimeInstance()
                    .format(Date(backup.backupDate))

                // Type badge
                binding.tvBackupType.text = backup.backupType.name

                // Size
                binding.tvBackupSize.text = formatFileSize(backup.backupSize)

                // Encryption badge
                binding.ivEncrypted.visibility = if (backup.isEncrypted) View.VISIBLE else View.GONE

                // Cloud badge
                binding.ivCloudSynced.visibility = if (backup.cloudProvider != null) View.VISIBLE else View.GONE

                // Status
                binding.tvStatus.text = backup.status.name

                // Action buttons
                binding.btnRestore.setOnClickListener { onRestore(backup) }
                binding.btnDelete.setOnClickListener { onDelete(backup) }
                binding.btnUpload.setOnClickListener { onUpload(backup) }
                binding.btnDownload.setOnClickListener { onDownload(backup) }
            }
        }
    }

    // ──────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────

    private fun iconForType(type: BackupType): Int = when (type) {
        BackupType.FULL       -> R.drawable.ic_backup_full
        BackupType.PHOTOS     -> R.drawable.ic_photo
        BackupType.VIDEOS     -> R.drawable.ic_video
        BackupType.DOCUMENTS  -> R.drawable.ic_document
        BackupType.SMS        -> R.drawable.ic_sms
        BackupType.CALL_LOG   -> R.drawable.ic_call_log
        BackupType.APP_DATA   -> R.drawable.ic_document
        BackupType.CUSTOM     -> R.drawable.ic_backup_full
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
