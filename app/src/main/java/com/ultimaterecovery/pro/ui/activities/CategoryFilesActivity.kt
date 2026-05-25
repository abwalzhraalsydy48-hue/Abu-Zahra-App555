package com.ultimaterecovery.pro.ui.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.databinding.ActivityCategoryFilesBinding
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory

/**
 * Activity for displaying files in a specific category.
 */
class CategoryFilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoryFilesBinding
    private var category: FileCategory = FileCategory.PHOTO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val categoryName = intent.getStringExtra("category") ?: "PHOTO"
        val title = intent.getStringExtra("title") ?: getString(R.string.files)
        
        try {
            category = FileCategory.valueOf(categoryName)
        } catch (e: Exception) {
            category = FileCategory.PHOTO
        }

        setupToolbar(title)
        setupButtons()
        loadFiles()
    }

    private fun setupToolbar(title: String) {
        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupButtons() {
        binding.btnSelectAll.setOnClickListener {
            // Select all files
        }
        binding.btnRecoverSelected.setOnClickListener {
            // Recover selected files
        }
        binding.btnExportSelected.setOnClickListener {
            // Export selected files
        }
    }

    private fun loadFiles() {
        // Load files from database for this category
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        // Set adapter with files
        
        // Show empty state if no files
        binding.emptyState.visibility = android.view.View.VISIBLE
        binding.recyclerView.visibility = android.view.View.GONE
    }
}
