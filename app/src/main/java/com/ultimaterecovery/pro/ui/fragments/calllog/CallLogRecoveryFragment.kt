package com.ultimaterecovery.pro.ui.fragments.calllog

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.data.local.entity.CallLogEntity
import com.ultimaterecovery.pro.data.local.entity.CallLogEntity.CallType
import com.ultimaterecovery.pro.databinding.FragmentCallLogRecoveryBinding
import com.ultimaterecovery.pro.databinding.ItemCallLogBinding
import com.ultimaterecovery.pro.ui.viewmodel.CallLogRecoveryUiState
import com.ultimaterecovery.pro.ui.viewmodel.CallLogRecoveryViewModel
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Call log recovery fragment.
 *
 * Features:
 * - Call type icons (incoming / outgoing / missed / rejected)
 * - Duration formatting
 * - Contact name and number display
 * - Search with real-time filtering
 * - Filter by call type
 * - Multi-select for batch recovery
 * - Export to TXT / PDF
 * - Empty state with illustration
 */
@AndroidEntryPoint
class CallLogRecoveryFragment : Fragment() {

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: FragmentCallLogRecoveryBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: CallLogRecoveryViewModel by viewModels()

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private lateinit var callLogAdapter: CallLogAdapter

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            _binding = FragmentCallLogRecoveryBinding.inflate(inflater, container, false)
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
        setupFilterChips()
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
            setTitle(R.string.call_log_recovery)
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
        callLogAdapter = CallLogAdapter(
            onClick = { log -> onCallLogClicked(log) },
            onLongClick = { log -> onCallLogLongClicked(log) },
            onSelect = { id -> viewModel.selectLog(id) }
        )
        binding.recyclerViewCallLogs.apply {
            adapter = callLogAdapter
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
    // Filter chips
    // ──────────────────────────────────────────

    private fun setupFilterChips() {
        binding.chipIncoming.setOnClickListener {
            viewModel.filterCallLogs(viewModel.uiState.value.currentFilter.copy(callType = CallType.INCOMING))
        }
        binding.chipOutgoing.setOnClickListener {
            viewModel.filterCallLogs(viewModel.uiState.value.currentFilter.copy(callType = CallType.OUTGOING))
        }
        binding.chipMissed.setOnClickListener {
            viewModel.filterCallLogs(viewModel.uiState.value.currentFilter.copy(callType = CallType.MISSED))
        }
        binding.chipRejected.setOnClickListener {
            viewModel.filterCallLogs(viewModel.uiState.value.currentFilter.copy(callType = CallType.REJECTED))
        }
        binding.chipAll.setOnClickListener {
            viewModel.filterCallLogs(viewModel.uiState.value.currentFilter.copy(callType = null))
        }
    }

    // ──────────────────────────────────────────
    // Controls
    // ──────────────────────────────────────────

    private fun setupControls() {
        binding.fabRecover.setOnClickListener { viewModel.recoverSelected() }
        binding.fabExport.setOnClickListener { showExportDialog() }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnDeselectAll.setOnClickListener { viewModel.deselectAll() }
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

    private fun renderState(state: CallLogRecoveryUiState) {
        try {
        val binding = _binding ?: return
            if (state.isLoading) {
                binding.shimmerFrameLayout?.visibility = View.VISIBLE
                binding.shimmerFrameLayout.startShimmer()
                binding.recyclerViewCallLogs?.visibility = View.GONE
            } else {
                binding.shimmerFrameLayout?.visibility = View.GONE
                binding.shimmerFrameLayout.stopShimmer()
                binding.recyclerViewCallLogs?.visibility = View.VISIBLE
            }

            callLogAdapter.submitList(state.filteredCallLogs)

            binding.tvCallLogCount.text = getString(
                R.string.call_log_count,
                state.filteredCallLogs.size,
                state.totalCount
            )

            val selectedCount = state.selectedLogIds.size
            if (selectedCount > 0) {
                binding.layoutSelectionBar?.visibility = View.VISIBLE
                binding.tvSelectedCount.text = getString(R.string.selected_count, selectedCount)
                binding.fabRecover?.visibility = View.VISIBLE
            } else {
                binding.layoutSelectionBar?.visibility = View.GONE
                binding.fabRecover?.visibility = View.GONE
            }

            if (state.filteredCallLogs.isEmpty() && !state.isLoading) {
                binding.layoutEmptyState?.visibility = View.VISIBLE
                binding.recyclerViewCallLogs?.visibility = View.GONE
            } else {
                binding.layoutEmptyState?.visibility = View.GONE
            }

            if (state.isExporting) {
                binding.progressExport?.visibility = View.VISIBLE
            } else {
                binding.progressExport?.visibility = View.GONE
            }

            state.exportPath?.let { path ->
                Toast.makeText(requireContext(), getString(R.string.exported_to, path), Toast.LENGTH_SHORT).show()
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

    private fun onCallLogClicked(log: CallLogEntity) {
        if (viewModel.uiState.value.selectedLogIds.isNotEmpty()) {
            viewModel.selectLog(log.id)
        }
    }

    private fun onCallLogLongClicked(log: CallLogEntity): Boolean {
        viewModel.selectLog(log.id)
        return true
    }

    // ──────────────────────────────────────────
    // Export dialog
    // ──────────────────────────────────────────

    private fun showExportDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_call_logs)
            .setItems(arrayOf("TXT", "PDF")) { _, which ->
                when (which) {
                    0 -> viewModel.exportToTxt()
                    1 -> viewModel.exportToPdf()
                }
            }
            .show()
    }

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private inner class CallLogAdapter(
        private val onClick: (CallLogEntity) -> Unit,
        private val onLongClick: (CallLogEntity) -> Boolean,
        private val onSelect: (Long) -> Unit
    ) : RecyclerView.Adapter<CallLogAdapter.CallLogViewHolder>() {

        private var items: List<CallLogEntity> = emptyList()

        fun submitList(newItems: List<CallLogEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallLogViewHolder {
            val binding = ItemCallLogBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return CallLogViewHolder(binding)
        }

        override fun onBindViewHolder(holder: CallLogViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class CallLogViewHolder(
            private val binding: ItemCallLogBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(log: CallLogEntity) {
                val isSelected = log.id in viewModel.uiState.value.selectedLogIds

                // Call type icon with color
                when (log.callType) {
                    CallType.INCOMING -> {
                        binding.ivCallType.setImageResource(R.drawable.ic_call_incoming)
                        binding.ivCallType.setColorFilter(
                            androidx.core.content.ContextCompat.getColor(binding.root.context, R.color.md_theme_light_primary)
                        )
                    }
                    CallType.OUTGOING -> {
                        binding.ivCallType.setImageResource(R.drawable.ic_call_outgoing)
                        binding.ivCallType.setColorFilter(
                            androidx.core.content.ContextCompat.getColor(binding.root.context, R.color.secondary)
                        )
                    }
                    CallType.MISSED -> {
                        binding.ivCallType.setImageResource(R.drawable.ic_call_missed)
                        binding.ivCallType.setColorFilter(
                            androidx.core.content.ContextCompat.getColor(binding.root.context, android.R.color.holo_red_dark)
                        )
                    }
                    CallType.REJECTED -> {
                        binding.ivCallType.setImageResource(R.drawable.ic_call_rejected)
                        binding.ivCallType.setColorFilter(
                            androidx.core.content.ContextCompat.getColor(binding.root.context, android.R.color.holo_orange_dark)
                        )
                    }
                    else -> ""
                }

                // Contact / Number
                binding.tvContactName.text = log.contactName ?: log.number
                if (log.contactName != null) {
                    binding.tvNumber.text = log.number
                    binding.tvNumber?.visibility = View.VISIBLE
                } else {
                    binding.tvNumber?.visibility = View.GONE
                }

                // Date
                binding.tvDate.text = DateFormat.getDateTimeInstance()
                    .format(Date(log.date))

                // Duration
                binding.tvDuration.text = formatDuration(log.duration)

                // Checkbox
                binding.checkbox.isChecked = isSelected
                binding.checkbox.setOnClickListener { onSelect(log.id) }

                // Selected background
                binding.root.isActivated = isSelected

                // Click handlers
                binding.root.setOnClickListener { onClick(log) }
                binding.root.setOnLongClickListener { onLongClick(log) }
            }
        }
    }

    // ──────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val hrs = TimeUnit.SECONDS.toHours(seconds)
        val mins = TimeUnit.SECONDS.toMinutes(seconds) % 60
        val secs = seconds % 60
        return when {
            hrs > 0 -> "${hrs}h ${mins}m ${secs}s"
            mins > 0 -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    }

}

