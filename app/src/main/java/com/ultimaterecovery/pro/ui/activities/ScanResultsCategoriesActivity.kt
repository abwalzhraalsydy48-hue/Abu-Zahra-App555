package com.ultimaterecovery.pro.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.databinding.ActivityScanResultsCategoriesBinding
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import java.text.DecimalFormat

/**
 * Activity for displaying scan results by category.
 */
class ScanResultsCategoriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanResultsCategoriesBinding
    private var photosCount = 0
    private var videosCount = 0
    private var smsCount = 0
    private var callLogsCount = 0
    private var documentsCount = 0
    private var audioCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanResultsCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupCategoryCards()
        setupActionButtons()
        loadResults()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupCategoryCards() {
        binding.cardPhotos.setOnClickListener {
            openCategoryDetails(FileCategory.PHOTO, getString(R.string.scan_type_photos))
        }
        binding.cardVideos.setOnClickListener {
            openCategoryDetails(FileCategory.VIDEO, getString(R.string.scan_type_videos))
        }
        binding.cardSms.setOnClickListener {
            openCategoryDetails(FileCategory.OTHER, getString(R.string.scan_type_sms))
        }
        binding.cardCallLogs.setOnClickListener {
            openCategoryDetails(FileCategory.OTHER, getString(R.string.scan_type_call_log))
        }
        binding.cardDocuments.setOnClickListener {
            openCategoryDetails(FileCategory.DOCUMENT, getString(R.string.scan_type_documents))
        }
        binding.cardAudio.setOnClickListener {
            openCategoryDetails(FileCategory.AUDIO, getString(R.string.scan_type_audio))
        }
    }

    private fun setupActionButtons() {
        binding.btnRecoverAll.setOnClickListener {
            // Recover all files
            finish()
        }
        binding.btnExportAll.setOnClickListener {
            // Export all files
        }
        binding.btnNewScan.setOnClickListener {
            val intent = Intent(this, ScanActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun loadResults() {
        // Load from intent or database
        photosCount = intent.getIntExtra("photos_count", 0)
        videosCount = intent.getIntExtra("videos_count", 0)
        smsCount = intent.getIntExtra("sms_count", 0)
        callLogsCount = intent.getIntExtra("call_logs_count", 0)
        documentsCount = intent.getIntExtra("documents_count", 0)
        audioCount = intent.getIntExtra("audio_count", 0)

        updateUI()
    }

    private fun updateUI() {
        val total = photosCount + videosCount + smsCount + callLogsCount + documentsCount + audioCount
        binding.tvTotalFound.text = total.toString()
        
        binding.tvPhotosCount.text = "$photosCount " + getString(R.string.files)
        binding.tvVideosCount.text = "$videosCount " + getString(R.string.files)
        binding.tvSmsCount.text = "$smsCount " + getString(R.string.files)
        binding.tvCallLogsCount.text = "$callLogsCount " + getString(R.string.files)
        binding.tvDocumentsCount.text = "$documentsCount " + getString(R.string.files)
        binding.tvAudioCount.text = "$audioCount " + getString(R.string.files)

        // Hide empty categories
        binding.cardPhotos.visibility = if (photosCount > 0) View.VISIBLE else View.GONE
        binding.cardVideos.visibility = if (videosCount > 0) View.VISIBLE else View.GONE
        binding.cardSms.visibility = if (smsCount > 0) View.VISIBLE else View.GONE
        binding.cardCallLogs.visibility = if (callLogsCount > 0) View.VISIBLE else View.GONE
        binding.cardDocuments.visibility = if (documentsCount > 0) View.VISIBLE else View.GONE
        binding.cardAudio.visibility = if (audioCount > 0) View.VISIBLE else View.GONE
    }

    private fun openCategoryDetails(category: FileCategory, title: String) {
        val intent = Intent(this, CategoryFilesActivity::class.java).apply {
            putExtra("category", category.name)
            putExtra("title", title)
        }
        startActivity(intent)
    }
}
