package com.ultimaterecovery.pro.ui.recovery

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.databinding.FragmentSmsRecoveryBinding
import com.ultimaterecovery.pro.ui.fragments.sms.SmsRecoveryFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * SMS Recovery Activity - Container for SmsRecoveryFragment
 *
 * Features:
 * - Displays deleted SMS messages that can be recovered
 * - Chat-bubble style message list
 * - Search and filter functionality
 * - Export to TXT/PDF
 */
@AndroidEntryPoint
class SmsRecoveryActivity : AppCompatActivity() {

    private var binding: FragmentSmsRecoveryBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            
            // Inflate the fragment layout directly
            binding = FragmentSmsRecoveryBinding.inflate(layoutInflater)
            setContentView(binding?.root ?: throw IllegalStateException("Failed to inflate layout"))

            // Setup toolbar
            setSupportActionBar(binding?.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setTitle(R.string.sms_recovery)
            binding?.toolbar?.setNavigationOnClickListener { finish() }

            // The fragment's ViewModel and logic will handle the rest
            // This activity just serves as a container

        } catch (e: Exception) {
            // Fallback: Show error and finish
            Toast.makeText(this, "Error loading SMS recovery: ${e.message}", Toast.LENGTH_SHORT).show()
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
        const val EXTRA_FILTER_TYPE = "extra_filter_type"
        const val EXTRA_CONTACT_FILTER = "extra_contact_filter"
    }
}
