package com.ultimaterecovery.pro.ui.fragments.appdata

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.ultimaterecovery.pro.utils.storage.formatFileSize
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.data.local.entity.AppDataEntity
import com.ultimaterecovery.pro.data.local.entity.AppDataEntity.AppDataType
import com.ultimaterecovery.pro.databinding.FragmentAppDataRecoveryBinding
import com.ultimaterecovery.pro.databinding.ItemAppDataBinding
import com.ultimaterecovery.pro.ui.viewmodel.AppDataRecoveryUiState
import com.ultimaterecovery.pro.ui.viewmodel.AppDataRecoveryViewModel
import com.ultimaterecovery.pro.ui.viewmodel.AppDataSortBy
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * App data recovery fragment.
 *
 * Features:
 * - App icons and package names
 * - Data type tabs (Cache, Data, Database, SharedPrefs, External)
 * - Search by app name or package name
 * - System / User app filter
 * - Multi-select for batch recovery
 * - Sort options
 * - Empty state with illustration
 */
@AndroidEntryPoint
class AppDataRecoveryFragment : Fragment() {

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: FragmentAppDataRecoveryBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: AppDataRecoveryViewModel by viewModels()

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private lateinit var appDataAdapter: AppDataAdapter

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            _binding = FragmentAppDataRecoveryBinding.inflate(inflater, container, false)
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
        setupDataTypeTabs()
        setupRecyclerView()
        setupSearch()
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
            setTitle(R.string.app_data_recovery)
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    // ──────────────────────────────────────────
    // Data type tabs
    // ──────────────────────────────────────────

    private fun setupDataTypeTabs() {
        val dataTypes = AppDataType.values()
        dataTypes.forEach { type ->
            binding.tabLayout.addTab(
                binding.tabLayout.newTab().setText(type.name.lowercase().replaceFirstChar { it.uppercase() })
            )
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val index = tab?.position ?: 0
                val dataType = dataTypes.getOrNull(index)
                viewModel.filterAppData(
                    viewModel.uiState.value.currentFilter.copy(dataType = dataType)
                )
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // ──────────────────────────────────────────
    // RecyclerView
    // ──────────────────────────────────────────

    private fun setupRecyclerView() {
        appDataAdapter = AppDataAdapter(
            onClick = { appData -> onAppDataClicked(appData) },
            onLongClick = { appData -> onAppDataLongClicked(appData) },
            onSelect = { id -> viewModel.selectAppData(id) }
        )
        binding.recyclerViewAppData.apply {
            adapter = appDataAdapter
            layoutManager = LinearLayoutManager(requireContext())
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
        binding.fabRecover.setOnClickListener { viewModel.recoverSelected() }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnDeselectAll.setOnClickListener { viewModel.deselectAll() }

        binding.chipSystemApps.setOnClickListener {
            viewModel.filterAppData(
                viewModel.uiState.value.currentFilter.copy(isSystemApp = true)
            )
        }
        binding.chipUserApps.setOnClickListener {
            viewModel.filterAppData(
                viewModel.uiState.value.currentFilter.copy(isSystemApp = false)
            )
        }
        binding.chipAllApps.setOnClickListener {
            viewModel.filterAppData(
                viewModel.uiState.value.currentFilter.copy(isSystemApp = null)
            )
        }
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

    private fun renderState(state: AppDataRecoveryUiState) {
        try {
        val binding = _binding ?: return
            if (state.isLoading) {
                binding.shimmerFrameLayout?.visibility = View.VISIBLE
                binding.shimmerFrameLayout.startShimmer()
                binding.recyclerViewAppData?.visibility = View.GONE
            } else {
                binding.shimmerFrameLayout?.visibility = View.GONE
                binding.shimmerFrameLayout.stopShimmer()
                binding.recyclerViewAppData?.visibility = View.VISIBLE
            }

            appDataAdapter.submitList(state.filteredAppData)

            val selectedCount = state.selectedIds.size
            if (selectedCount > 0) {
                binding.layoutSelectionBar?.visibility = View.VISIBLE
                binding.tvSelectedCount.text = getString(R.string.selected_count, selectedCount)
                binding.fabRecover?.visibility = View.VISIBLE
            } else {
                binding.layoutSelectionBar?.visibility = View.GONE
                binding.fabRecover?.visibility = View.GONE
            }

            if (state.filteredAppData.isEmpty() && !state.isLoading) {
                binding.layoutEmptyState?.visibility = View.VISIBLE
                binding.recyclerViewAppData?.visibility = View.GONE
            } else {
                binding.layoutEmptyState?.visibility = View.GONE
            }

            // Recovery progress
            if (state.isRecovering) {
                binding.progressRecovery?.visibility = View.VISIBLE
            } else {
                binding.progressRecovery?.visibility = View.GONE
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
    // Click handlers
    // ──────────────────────────────────────────

    private fun onAppDataClicked(appData: AppDataEntity) {
        if (viewModel.uiState.value.selectedIds.isNotEmpty()) {
            viewModel.selectAppData(appData.id)
        }
    }

    private fun onAppDataLongClicked(appData: AppDataEntity): Boolean {
        viewModel.selectAppData(appData.id)
        return true
    }

    // ──────────────────────────────────────────
    // Sort dialog
    // ──────────────────────────────────────────

    private fun showSortDialog() {
        val sortOptions = AppDataSortBy.values().map { it.name }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sort_by)
            .setItems(sortOptions) { _, which ->
                viewModel.sortAppData(AppDataSortBy.values()[which])
            }
            .show()
    }

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private inner class AppDataAdapter(
        private val onClick: (AppDataEntity) -> Unit,
        private val onLongClick: (AppDataEntity) -> Boolean,
        private val onSelect: (Long) -> Unit
    ) : RecyclerView.Adapter<AppDataAdapter.AppDataViewHolder>() {

        private var items: List<AppDataEntity> = emptyList()

        fun submitList(newItems: List<AppDataEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppDataViewHolder {
            val binding = ItemAppDataBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return AppDataViewHolder(binding)
        }

        override fun onBindViewHolder(holder: AppDataViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class AppDataViewHolder(
            private val binding: ItemAppDataBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(appData: AppDataEntity) {
                val isSelected = appData.id in viewModel.uiState.value.selectedIds

                // App icon — try to load from package manager
                try {
                    val appInfo = requireContext().packageManager
                        .getApplicationInfo(appData.packageName, 0)
                    binding.ivAppIcon.setImageDrawable(
                        requireContext().packageManager.getApplicationIcon(appInfo)
                    )
                } catch (_: Exception) {
                    binding.ivAppIcon.setImageResource(R.drawable.ic_app_placeholder)
                }

                // App name
                binding.tvAppName.text = appData.appName

                // Package name
                binding.tvPackageName.text = appData.packageName

                // Data type badge
                binding.tvDataType.text = appData.dataType.name

                // File size
                binding.tvDataSize.text = formatFileSize(appData.fileSize)

                // System badge
                if (appData.isSystemApp) {
                    binding.tvSystemBadge?.visibility = View.VISIBLE
                } else {
                    binding.tvSystemBadge?.visibility = View.GONE
                }

                // Recovery date
                binding.tvRecoveryDate.text = DateFormat.getDateTimeInstance()
                    .format(Date(appData.recoveryDate))

                // Checkbox
                binding.checkbox.isChecked = isSelected
                binding.checkbox.setOnClickListener { onSelect(appData.id) }

                // Selected background
                binding.root.isActivated = isSelected

                // Click handlers
                binding.root.setOnClickListener { onClick(appData) }
                binding.root.setOnLongClickListener { onLongClick(appData) }
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
