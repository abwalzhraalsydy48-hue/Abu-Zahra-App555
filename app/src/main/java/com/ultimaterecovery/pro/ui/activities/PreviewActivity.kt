package com.ultimaterecovery.pro.ui.activities

import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.databinding.ActivityPreviewBinding
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import timber.log.Timber

/**
 * Full-screen media preview activity.
 *
 * Features:
 * - Full-screen image preview with zoom/pan via PhotoView
 * - Video playback with ExoPlayer
 * - File info overlay
 * - Recover button
 * - Share button
 * - Open with external app button
 * - Support for all media types (image, video, audio, document)
 */
class PreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_FILE_SIZE = "extra_file_size"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_FILE_ID = "extra_file_id"
        const val EXTRA_CATEGORY = "extra_category"
    }

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: ActivityPreviewBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ExoPlayer
    // ──────────────────────────────────────────

    private var exoPlayer: ExoPlayer? = null

    // ──────────────────────────────────────────
    // Intent data
    // ──────────────────────────────────────────

    private var filePath: String = ""
    private var fileName: String = ""
    private var fileSize: Long = 0L
    private var mimeType: String = ""
    private var fileId: Long = -1L

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            _binding = ActivityPreviewBinding.inflate(layoutInflater)
            setContentView(binding.root)

            parseIntent()
            setupToolbar()
            setupControls()
            loadPreview()

        } catch (e: Exception) {
            Timber.e(e, "Error in onCreate")
            showErrorAndFinish("Error loading preview: ${e.message}")
        } catch (_: Throwable) {
            // Prevent crash
            showErrorAndFinish("An unexpected error occurred")
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        _binding = null
    }

    // ──────────────────────────────────────────
    // Intent parsing
    // ──────────────────────────────────────────

    private fun parseIntent() {
        filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        fileSize = intent.getLongExtra(EXTRA_FILE_SIZE, 0L)
        mimeType = intent.getStringExtra(EXTRA_MIME_TYPE).orEmpty()
        fileId = intent.getLongExtra(EXTRA_FILE_ID, -1L)
    }

    // ──────────────────────────────────────────
    // Toolbar
    // ──────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = fileName.ifBlank { getString(R.string.preview) }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    // ──────────────────────────────────────────
    // Controls
    // ──────────────────────────────────────────

    private fun setupControls() {
        binding.fabRecover.setOnClickListener {
            exportFile()
        }

        binding.fabShare.setOnClickListener {
            shareFile()
        }

        // Close button
        binding.btnClose.setOnClickListener {
            finish()
        }

        // Toggle file info overlay on tap
        binding.root.setOnClickListener {
            toggleFileInfoOverlay()
        }
    }

    // ──────────────────────────────────────────
    // Preview loading
    // ──────────────────────────────────────────

    private fun loadPreview() {
        populateFileInfo()

        // Check if file exists
        val file = File(filePath)
        if (!file.exists()) {
            showFileNotFound()
            return
        }

        when {
            mimeType.startsWith("image/") -> loadImagePreview()
            mimeType.startsWith("video/") -> loadVideoPreview()
            mimeType.startsWith("audio/") -> loadAudioPreview()
            else -> loadGenericPreview()
        }
    }

    /**
     * Shows file not found state
     */
    private fun showFileNotFound() {
        binding.photoView.visibility = View.GONE
        binding.playerView.visibility = View.GONE
        binding.layoutGenericPreview.visibility = View.VISIBLE
        
        binding.ivFileIcon.setImageResource(R.drawable.ic_broken_image)
        binding.textFileName.text = fileName
        binding.textFileName.visibility = View.VISIBLE
        
        Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_LONG).show()
    }

    /**
     * Loads an image using Glide with zoom/pan support.
     */
    private fun loadImagePreview() {
        binding.photoView.visibility = View.VISIBLE
        binding.playerView.visibility = View.GONE
        binding.layoutGenericPreview.visibility = View.GONE

        try {
            Glide.with(this)
                .load(File(filePath))
                .placeholder(R.drawable.ic_photo_placeholder)
                .error(R.drawable.ic_broken_image)
                .into(binding.photoView)
        } catch (e: Exception) {
            Timber.e(e, "Error loading image")
            showFileNotFound()
        }
    }

    /**
     * Sets up ExoPlayer for video playback.
     */
    private fun loadVideoPreview() {
        binding.photoView.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        binding.layoutGenericPreview.visibility = View.GONE

        try {
            initializePlayer()
            val mediaItem = MediaItem.fromUri(Uri.fromFile(File(filePath)))
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = false
        } catch (e: Exception) {
            Timber.e(e, "Error loading video")
            showFileNotFound()
        }
    }

    /**
     * Shows an audio visualization with player controls.
     */
    private fun loadAudioPreview() {
        binding.photoView.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        binding.layoutGenericPreview.visibility = View.GONE

        try {
            initializePlayer()
            val mediaItem = MediaItem.fromUri(Uri.fromFile(File(filePath)))
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = false
        } catch (e: Exception) {
            Timber.e(e, "Error loading audio")
            showFileNotFound()
        }
    }

    /**
     * Shows a generic file type icon with file info.
     */
    private fun loadGenericPreview() {
        binding.photoView.visibility = View.GONE
        binding.playerView.visibility = View.GONE
        binding.layoutGenericPreview.visibility = View.VISIBLE

        val iconRes = when {
            mimeType.contains("pdf")   -> R.drawable.ic_pdf
            mimeType.contains("zip")   -> R.drawable.ic_archive
            mimeType.contains("apk")   -> R.drawable.ic_apk
            mimeType.contains("text")  -> R.drawable.ic_document
            else                       -> R.drawable.ic_file
        }
        binding.ivFileIcon.setImageResource(iconRes)
    }

    // ──────────────────────────────────────────
    // ExoPlayer
    // ──────────────────────────────────────────

    private fun initializePlayer() {
        if (exoPlayer != null) return
        exoPlayer = ExoPlayer.Builder(this).build().also {
            binding.playerView.player = it
        }
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    // ──────────────────────────────────────────
    // File info overlay
    // ──────────────────────────────────────────

    private fun populateFileInfo() {
        binding.textFileName.text = fileName
        binding.textFileSize.text = formatFileSize(fileSize)
        binding.textRecoveryChance.text = getString(R.string.high_recovery_chance)

        val file = File(filePath)
        if (file.exists()) {
            binding.textFileDate.text = formatDate(file.lastModified())
        } else {
            binding.textFileDate.text = getString(R.string.file_not_available)
        }
    }

    private fun toggleFileInfoOverlay() {
        binding.cardFileInfo.visibility =
            if (binding.cardFileInfo.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // ──────────────────────────────────────────
    // Share
    // ──────────────────────────────────────────

    private fun shareFile() {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show()
                return
            }

            val uri = getFileUri(file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_file)))
        } catch (e: Exception) {
            Timber.e(e, "Error sharing file")
            Toast.makeText(this, getString(R.string.share_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    // ──────────────────────────────────────────
    // Open with external app
    // ──────────────────────────────────────────

    private fun openWithExternalApp() {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show()
                return
            }

            val uri = getFileUri(file)

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            val chooser = Intent.createChooser(viewIntent, getString(R.string.open_with))
            startActivity(chooser)
        } catch (e: Exception) {
            Timber.e(e, "Error opening file")
            Toast.makeText(this, getString(R.string.open_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    // ──────────────────────────────────────────
    // FileProvider URI
    // ──────────────────────────────────────────

    /**
     * Get a content URI for a file using FileProvider
     */
    private fun getFileUri(file: File): Uri {
        return FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
    }

    // ──────────────────────────────────────────
    // Export/Save
    // ──────────────────────────────────────────

    /**
     * Export the file to downloads folder
     */
    private fun exportFile() {
        try {
            val sourceFile = File(filePath)
            if (!sourceFile.exists()) {
                Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show()
                return
            }

            Toast.makeText(this, R.string.exporting_file, Toast.LENGTH_SHORT).show()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - use MediaStore API
                exportViaMediaStore(sourceFile)
            } else {
                // Android 9 and below - direct copy
                exportLegacy(sourceFile)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error exporting file")
            Toast.makeText(this, getString(R.string.export_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Export using MediaStore API (Android 10+)
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportViaMediaStore(sourceFile: File) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "*/*" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, getRelativePathForMimeType())
        }

        val contentUri = when {
            mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }

        try {
            val uri = contentResolver.insert(contentUri, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(sourceFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Toast.makeText(this, getString(R.string.export_success, fileName), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error exporting via MediaStore")
            Toast.makeText(this, getString(R.string.export_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Export for older versions (Android 9 and below)
     */
    private fun exportLegacy(sourceFile: File) {
        val destDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "UltimateRecovery"
        )
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        val destFile = File(destDir, fileName)
        var finalDestFile = destFile
        var counter = 1

        // Avoid overwriting existing files
        while (finalDestFile.exists()) {
            val name = sourceFile.nameWithoutExtension
            val ext = sourceFile.extension
            finalDestFile = File(destDir, "${name}_$counter.$ext")
            counter++
        }

        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(finalDestFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Update MediaStore
            MediaScannerConnection.scanFile(
                this,
                arrayOf(finalDestFile.absolutePath),
                arrayOf(mimeType),
                null
            )

            Toast.makeText(this, getString(R.string.export_success, finalDestFile.name), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Timber.e(e, "Error exporting legacy")
            Toast.makeText(this, getString(R.string.export_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Get relative path based on MIME type
     */
    private fun getRelativePathForMimeType(): String {
        return when {
            mimeType.startsWith("image/") -> "${Environment.DIRECTORY_DCIM}/UltimateRecovery"
            mimeType.startsWith("video/") -> "${Environment.DIRECTORY_MOVIES}/UltimateRecovery"
            mimeType.startsWith("audio/") -> "${Environment.DIRECTORY_MUSIC}/UltimateRecovery"
            else -> "${Environment.DIRECTORY_DOWNLOADS}/UltimateRecovery"
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

    private fun formatDate(timestamp: Long): String {
        return java.text.DateFormat.getDateTimeInstance()
            .format(java.util.Date(timestamp))
    }

    private fun showErrorAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
