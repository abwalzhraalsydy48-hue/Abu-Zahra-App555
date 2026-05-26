package com.ultimaterecovery.pro.ui.recovery

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.databinding.FragmentCallLogRecoveryBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * Call Log Recovery Activity - Container for CallLogRecoveryFragment
 *
 * Features:
 * - Displays deleted call logs that can be recovered
 * - Call type icons (incoming/outgoing/missed/rejected)
 * - Search and filter functionality
 * - Export to TXT/PDF
 */
@AndroidEntryPoint
class CallLogRecoveryActivity : AppCompatActivity() {

    private var binding: FragmentCallLogRecoveryBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            
            // Inflate the fragment layout directly
            binding = FragmentCallLogRecoveryBinding.inflate(layoutInflater)
            setContentView(binding?.root ?: throw IllegalStateException("Failed to inflate layout"))

            // Setup toolbar
            setSupportActionBar(binding?.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setTitle(R.string.call_log_recovery)
            binding?.toolbar?.setNavigationOnClickListener { finish() }

            // The fragment's ViewModel and logic will handle the rest
            // This activity just serves as a container

        } catch (e: Exception) {
            // Fallback: Show error and finish
            Toast.makeText(this, "Error loading Call Log recovery: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        } catch (_: Throwable) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding = null
    }

    companion object {
        const val EXTRA_CALL_TYPE = "extra_call_type"
        const val EXTRA_NUMBER_FILTER = "extra_number_filter"
    }
}
