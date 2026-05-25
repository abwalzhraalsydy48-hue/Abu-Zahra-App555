package com.ultimaterecovery.pro.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.ultimaterecovery.pro.utils.storage.formatFileSize
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.databinding.ActivityPreviewBinding
import kotlinx.coroutines.launch
import java.io.File
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
        } catch (_: Throwable) {
            // Prevent crash
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
            // In production, delegate to the ViewModel's recovery engine
            Toast.makeText(this, R.string.recovering_file, Toast.LENGTH_SHORT).show()
        }

        binding.fabShare.setOnClickListener {
            shareFile()
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

        when {
            mimeType.startsWith("image/") -> loadImagePreview()
            mimeType.startsWith("video/") -> loadVideoPreview()
            mimeType.startsWith("audio/") -> loadAudioPreview()
            else -> loadGenericPreview()
        }
    }

    /**
     * Loads an image using Glide with zoom/pan support via PhotoView.
     */
    private fun loadImagePreview() {
        binding.photoView?.visibility = View.VISIBLE
        binding.playerView?.visibility = View.GONE
        binding.layoutGenericPreview?.visibility = View.GONE

        Glide.with(this)
            .load(File(filePath))
            .placeholder(R.drawable.ic_photo_placeholder)
            .error(R.drawable.ic_broken_image)
            .into(binding.photoView)
    }

    /**
     * Sets up ExoPlayer for video playback.
     */
    private fun loadVideoPreview() {
        binding.photoView?.visibility = View.GONE
        binding.playerView?.visibility = View.VISIBLE
        binding.layoutGenericPreview?.visibility = View.GONE

        initializePlayer()
        val mediaItem = MediaItem.fromUri(Uri.fromFile(File(filePath)))
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = false
    }

    /**
     * Shows an audio visualization with player controls.
     */
    private fun loadAudioPreview() {
        binding.photoView?.visibility = View.GONE
        binding.playerView?.visibility = View.VISIBLE
        binding.layoutGenericPreview?.visibility = View.GONE

        initializePlayer()
        val mediaItem = MediaItem.fromUri(Uri.fromFile(File(filePath)))
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = false
    }

    /**
     * Shows a generic file type icon with file info.
     */
    private fun loadGenericPreview() {
        binding.photoView?.visibility = View.GONE
        binding.playerView?.visibility = View.GONE
        binding.layoutGenericPreview?.visibility = View.VISIBLE

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
        binding.tvFileName.text = fileName
        binding.tvFileSize.text = formatFileSize(fileSize)
        binding.tvFilePath.text = filePath
        binding.tvFileMimeType.text = mimeType

        val file = File(filePath)
        if (file.exists()) {
            binding.tvFileDate.text = formatDate(file.lastModified())
        }
    }

    private fun toggleFileInfoOverlay() {
        binding.layoutFileInfoOverlay.visibility =
            if (binding.layoutFileInfoOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_file)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.share_error, e.message), Toast.LENGTH_SHORT).show()
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
}
