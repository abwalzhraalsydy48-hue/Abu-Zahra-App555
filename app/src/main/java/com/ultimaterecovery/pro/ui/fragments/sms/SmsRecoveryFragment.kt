package com.ultimaterecovery.pro.ui.fragments.sms

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
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity.SmsType
import com.ultimaterecovery.pro.databinding.FragmentSmsRecoveryBinding
import com.ultimaterecovery.pro.databinding.ItemSmsBubbleBinding
import com.ultimaterecovery.pro.ui.viewmodel.SmsRecoveryUiState
import com.ultimaterecovery.pro.ui.viewmodel.SmsRecoveryViewModel
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * SMS recovery fragment with chat-bubble style list.
 *
 * Features:
 * - Chat-bubble style message list
 * - Search bar with real-time filtering
 * - Filter by type (Inbox, Sent, Draft, Outbox)
 * - Multi-select for batch recovery
 * - Export to TXT / PDF
 * - Empty state with illustration
 */
@AndroidEntryPoint
class SmsRecoveryFragment : Fragment() {

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: FragmentSmsRecoveryBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: SmsRecoveryViewModel by viewModels()

    // ──────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────

    private lateinit var smsAdapter: SmsAdapter

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            _binding = FragmentSmsRecoveryBinding.inflate(inflater, container, false)
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
            setTitle(R.string.sms_recovery)
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
        smsAdapter = SmsAdapter(
            onClick = { message -> onMessageClicked(message) },
            onLongClick = { message -> onMessageLongClicked(message) },
            onSelect = { id -> viewModel.selectMessage(id) }
        )
        binding.recyclerViewSms.apply {
            adapter = smsAdapter
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
        binding.chipInbox.setOnClickListener {
            viewModel.filterMessages(viewModel.uiState.value.currentFilter.copy(type = SmsType.INBOX))
        }
        binding.chipSent.setOnClickListener {
            viewModel.filterMessages(viewModel.uiState.value.currentFilter.copy(type = SmsType.SENT))
        }
        binding.chipDraft.setOnClickListener {
            viewModel.filterMessages(viewModel.uiState.value.currentFilter.copy(type = SmsType.DRAFT))
        }
        binding.chipAll.setOnClickListener {
            viewModel.filterMessages(viewModel.uiState.value.currentFilter.copy(type = null))
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

    private fun renderState(state: SmsRecoveryUiState) {
        try {
        val binding = _binding ?: return
            if (state.isLoading) {
                binding.shimmerFrameLayout?.visibility = View.VISIBLE
                binding.shimmerFrameLayout.startShimmer()
                binding.recyclerViewSms?.visibility = View.GONE
            } else {
                binding.shimmerFrameLayout?.visibility = View.GONE
                binding.shimmerFrameLayout.stopShimmer()
                binding.recyclerViewSms?.visibility = View.VISIBLE
            }

            smsAdapter.submitList(state.filteredMessages)

            // Message count header
            binding.tvMessageCount.text = getString(
                R.string.message_count,
                state.filteredMessages.size,
                state.totalMessageCount
            )

            // Selection
            val selectedCount = state.selectedMessageIds.size
            if (selectedCount > 0) {
                binding.layoutSelectionBar?.visibility = View.VISIBLE
                binding.tvSelectedCount.text = getString(R.string.selected_count, selectedCount)
                binding.fabRecover?.visibility = View.VISIBLE
            } else {
                binding.layoutSelectionBar?.visibility = View.GONE
                binding.fabRecover?.visibility = View.GONE
            }

            // Empty state
            if (state.filteredMessages.isEmpty() && !state.isLoading) {
                binding.layoutEmptyState?.visibility = View.VISIBLE
                binding.recyclerViewSms?.visibility = View.GONE
            } else {
                binding.layoutEmptyState?.visibility = View.GONE
            }

            // Export progress
            if (state.isExporting) {
                binding.progressExport?.visibility = View.VISIBLE
            } else {
                binding.progressExport?.visibility = View.GONE
            }

            // Export result
            state.exportPath?.let { path ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.exported_to, path),
                    Toast.LENGTH_SHORT
                ).show()
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
    // Message click handlers
    // ──────────────────────────────────────────

    private fun onMessageClicked(message: SmsMessageEntity) {
        if (viewModel.uiState.value.selectedMessageIds.isNotEmpty()) {
            viewModel.selectMessage(message.id)
        }
    }

    private fun onMessageLongClicked(message: SmsMessageEntity): Boolean {
        viewModel.selectMessage(message.id)
        return true
    }

    // ──────────────────────────────────────────
    // Export dialog
    // ──────────────────────────────────────────

    private fun showExportDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_messages)
            .setItems(arrayOf("TXT", "PDF")) { _, which ->
                when (which) {
                    0 -> viewModel.exportToTxt()
                    1 -> viewModel.exportToPdf()
                }
            }
            .show()
    }

    // ──────────────────────────────────────────
    // Adapter — Chat-bubble style
    // ──────────────────────────────────────────

    private inner class SmsAdapter(
        private val onClick: (SmsMessageEntity) -> Unit,
        private val onLongClick: (SmsMessageEntity) -> Boolean,
        private val onSelect: (Long) -> Unit
    ) : RecyclerView.Adapter<SmsAdapter.SmsViewHolder>() {

        private var items: List<SmsMessageEntity> = emptyList()

        fun submitList(newItems: List<SmsMessageEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SmsViewHolder {
            val binding = ItemSmsBubbleBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return SmsViewHolder(binding)
        }

        override fun onBindViewHolder(holder: SmsViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class SmsViewHolder(
            private val binding: ItemSmsBubbleBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(message: SmsMessageEntity) {
                val isSelected = message.id in viewModel.uiState.value.selectedMessageIds

                // Contact / Address
                binding.tvAddress.text = message.contactName ?: message.address

                // Message body
                binding.tvBody.text = message.body

                // Date
                binding.tvDate.text = DateFormat.getDateTimeInstance()
                    .format(Date(message.date))

                // Type icon and bubble alignment
                when (message.type) {
                    SmsType.INBOX -> {
                        binding.ivTypeIcon.setImageResource(R.drawable.ic_inbox)
                        // Align left for received messages
                        (binding.bubbleLayout.layoutParams as RecyclerView.LayoutParams).let {
                            it.marginStart = 0
                            it.marginEnd = 80
                        }
                    }
                    SmsType.SENT -> {
                        binding.ivTypeIcon.setImageResource(R.drawable.ic_sent)
                        // Align right for sent messages
                        (binding.bubbleLayout.layoutParams as RecyclerView.LayoutParams).let {
                            it.marginStart = 80
                            it.marginEnd = 0
                        }
                    }
                    SmsType.DRAFT -> {
                        binding.ivTypeIcon.setImageResource(R.drawable.ic_draft)
                    }
                    SmsType.OUTBOX -> {
                        binding.ivTypeIcon.setImageResource(R.drawable.ic_outbox)
                    }
                    SmsType.FAILED -> {
                        binding.ivTypeIcon.setImageResource(R.drawable.ic_sent)
                    }
                    SmsType.QUEUED -> {
                        binding.ivTypeIcon.setImageResource(R.drawable.ic_outbox)
                    }
                }

                // Checkbox
                binding.checkbox.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.checkbox.isChecked = isSelected

                // Selected background
                binding.root.isActivated = isSelected

                // Click handlers
                binding.root.setOnClickListener { onClick(message) }
                binding.root.setOnLongClickListener { onLongClick(message) }
                binding.checkbox.setOnClickListener { onSelect(message.id) }
            }
        }
    }
}
