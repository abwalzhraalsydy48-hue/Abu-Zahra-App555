package com.ultimaterecovery.pro.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.databinding.ItemRecoveredFileBinding
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ──────────────────────────────────────────────
// DiffUtil Callback
// ──────────────────────────────────────────────

/**
 * DiffUtil callback for efficient RecyclerView updates.
 *
 * يستخدم لتحديث القائمة بكفاءة
 */
class RecoveredFileDiffCallback(
    private val oldList: List<RecoveredFileEntity>,
    private val newList: List<RecoveredFileEntity>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean {
        return oldList[oldPosition].id == newList[newPosition].id
    }

    override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean {
        return oldList[oldPosition] == newList[newPosition]
    }
}

// ──────────────────────────────────────────────
// Adapter
// ──────────────────────────────────────────────

/**
 * RecyclerView adapter for displaying recovered files.
 *
 * محول RecyclerView لعرض الملفات المستردة
 * يدعم:
 * - التحديد المتعدد مع خانة الاختيار
 * - عرض الصور المصغرة
 * - تحديثات DiffUtil الفعالة
 */
class RecoveredFilesAdapter(
    private val onItemClick: (RecoveredFileEntity) -> Unit,
    private val onItemLongClick: (RecoveredFileEntity) -> Boolean = { false },
    private val onSelectionChange: (Long) -> Unit
) : RecyclerView.Adapter<RecoveredFilesAdapter.RecoveredFileViewHolder>() {

    private var items: List<RecoveredFileEntity> = emptyList()
    private var selectedIds: Set<Long> = emptySet()
    private val dateFormat: DateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    // ──────────────────────────────────────────
    // تحديث البيانات
    // ──────────────────────────────────────────

    /**
     * Submits a new list with DiffUtil for efficient updates.
     *
     * يقدم قائمة جديدة مع DiffUtil للتحديثات الفعالة
     */
    fun submitList(newList: List<RecoveredFileEntity>) {
        val diffCallback = RecoveredFileDiffCallback(items, newList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newList
        diffResult.dispatchUpdatesTo(this)
    }

    /**
     * Updates the set of selected file IDs.
     *
     * يحدث مجموعة معرفات الملفات المحددة
     */
    fun updateSelectedIds(ids: Set<Long>) {
        val oldSelectedIds = selectedIds
        selectedIds = ids

        // تحديث العناصر المتأثرة فقط
        items.forEachIndexed { index, file ->
            val wasSelected = file.id in oldSelectedIds
            val isSelected = file.id in ids
            if (wasSelected != isSelected) {
                notifyItemChanged(index)
            }
        }
    }

    // ──────────────────────────────────────────
    // RecyclerView methods
    // ──────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecoveredFileViewHolder {
        val binding = ItemRecoveredFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RecoveredFileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecoveredFileViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    // ──────────────────────────────────────────
    // ViewHolder
    // ──────────────────────────────────────────

    inner class RecoveredFileViewHolder(
        private val binding: ItemRecoveredFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: RecoveredFileEntity) {
            val isSelected = file.id in selectedIds

            // تعيين اسم الملف
            binding.textFileName.text = file.fileName

            // تعيين حجم الملف
            binding.textFileSize.text = formatFileSize(file.fileSize)

            // تعيين تاريخ الملف
            binding.textFileDate.text = dateFormat.format(Date(file.originalDate))

            // تعيين أيقونة أو صورة مصغرة
            loadThumbnail(file)

            // حالة التحديد
            binding.checkbox.isChecked = isSelected
            binding.root.isSelected = isSelected

            // تعيين لون الخلفية عند التحديد
            if (isSelected) {
                binding.root.strokeWidth = 2
                binding.root.strokeColor = binding.root.context.getColor(R.color.primary)
            } else {
                binding.root.strokeWidth = 0
            }

            // معالجات النقر
            binding.root.setOnClickListener {
                if (selectedIds.isNotEmpty()) {
                    onSelectionChange(file.id)
                } else {
                    onItemClick(file)
                }
            }

            binding.root.setOnLongClickListener {
                if (selectedIds.isEmpty()) {
                    onSelectionChange(file.id)
                }
                onItemLongClick(file)
            }

            binding.checkbox.setOnClickListener {
                onSelectionChange(file.id)
            }
        }

        /**
         * Loads thumbnail for the file based on its category.
         *
         * يحمل الصورة المصغرة للملف بناءً على فئته
         */
        private fun loadThumbnail(file: RecoveredFileEntity) {
            when (file.category) {
                FileCategory.PHOTO -> {
                    // تحميل الصورة المصغرة للصور
                    val thumbPath = file.thumbnailPath ?: file.filePath
                    Glide.with(binding.imageFileType.context)
                        .load(File(thumbPath))
                        .placeholder(R.drawable.ic_photo_placeholder)
                        .error(R.drawable.ic_broken_image)
                        .centerCrop()
                        .into(binding.imageFileType)

                    // إظهار الصورة المصغرة وإخفاء الأيقونة
                    binding.imageFileType.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                }
                FileCategory.VIDEO -> {
                    // تحميل الصورة المصغرة للفيديو
                    val thumbPath = file.thumbnailPath
                    if (thumbPath != null) {
                        Glide.with(binding.imageFileType.context)
                            .load(File(thumbPath))
                            .placeholder(R.drawable.ic_video_placeholder)
                            .error(R.drawable.ic_video)
                            .centerCrop()
                            .into(binding.imageFileType)
                    } else {
                        binding.imageFileType.setImageResource(R.drawable.ic_video)
                        binding.imageFileType.scaleType = android.widget.ImageView.ScaleType.CENTER
                    }
                    binding.imageFileType.setColorFilter(
                        binding.imageFileType.context.getColor(R.color.category_video)
                    )
                }
                FileCategory.DOCUMENT -> {
                    setCategoryIcon(R.drawable.ic_document, R.color.category_document)
                }
                FileCategory.AUDIO -> {
                    setCategoryIcon(R.drawable.ic_audio, R.color.category_audio)
                }
                FileCategory.ARCHIVE -> {
                    setCategoryIcon(R.drawable.ic_archive, R.color.category_archive)
                }
                FileCategory.APK -> {
                    setCategoryIcon(R.drawable.ic_apk, R.color.category_apk)
                }
                FileCategory.OTHER -> {
                    setCategoryIcon(R.drawable.ic_file, R.color.on_surface_variant)
                }
            }
        }

        /**
         * Sets the category icon with appropriate color.
         *
         * يعين أيقونة الفئة مع اللون المناسب
         */
        private fun setCategoryIcon(iconRes: Int, colorRes: Int) {
            binding.imageFileType.setImageResource(iconRes)
            binding.imageFileType.scaleType = android.widget.ImageView.ScaleType.CENTER
            binding.imageFileType.setColorFilter(
                binding.imageFileType.context.getColor(colorRes)
            )
        }
    }

    // ──────────────────────────────────────────
    // Helper methods
    // ──────────────────────────────────────────

    /**
     * Formats file size to human readable format.
     *
     * يحول حجم الملف إلى صيغة قابلة للقراءة
     */
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

// ──────────────────────────────────────────────
// Category Tab Adapter
// ──────────────────────────────────────────────

/**
 * Data class for category tab information.
 *
 * فئة بيانات لمعلومات تبويب الفئة
 */
data class CategoryTab(
    val category: FileCategory?,
    val title: String,
    val iconRes: Int,
    val count: Int = 0
)

/**
 * Gets the icon resource for a file category.
 *
 * يعيد مورد الأيقونة لفئة الملف
 */
fun getCategoryIcon(category: FileCategory?): Int = when (category) {
    FileCategory.PHOTO -> R.drawable.ic_photo
    FileCategory.VIDEO -> R.drawable.ic_video
    FileCategory.DOCUMENT -> R.drawable.ic_document
    FileCategory.AUDIO -> R.drawable.ic_audio
    FileCategory.ARCHIVE -> R.drawable.ic_archive
    FileCategory.APK -> R.drawable.ic_apk
    FileCategory.OTHER -> R.drawable.ic_file
    null -> R.drawable.ic_file
}

/**
 * Gets the display name for a file category.
 *
 * يعيد اسم العرض لفئة الملف
 */
fun getCategoryDisplayName(category: FileCategory?): String = when (category) {
    FileCategory.PHOTO -> "Photos"
    FileCategory.VIDEO -> "Videos"
    FileCategory.DOCUMENT -> "Documents"
    FileCategory.AUDIO -> "Audio"
    FileCategory.ARCHIVE -> "Archives"
    FileCategory.APK -> "APKs"
    FileCategory.OTHER -> "Other"
    null -> "All"
}
