package com.ultimaterecovery.pro.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.ultimaterecovery.pro.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom View for displaying storage usage as an animated donut chart
 * with category color breakdown, used/free/total labels, and touch-to-reveal details.
 */
class StorageInfoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ============================================================================
    // Data Classes
    // ============================================================================

    /**
     * Represents a storage category with name, size, and color.
     */
    data class StorageCategory(
        val name: String,
        val sizeBytes: Long,
        val color: Int,
    )

    /**
     * Storage info summary.
     */
    data class StorageInfo(
        val totalBytes: Long,
        val usedBytes: Long,
        val categories: List<StorageCategory>,
    ) {
        val freeBytes: Long get() = totalBytes - usedBytes
        val usedPercentage: Float get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes) * 100f else 0f
    }

    // ============================================================================
    // Paints
    // ============================================================================
    private val donutBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = DONUT_STROKE_WIDTH
        color = ContextCompat.getColor(context, R.color.storage_donut_background)
    }

    private val donutSegmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = DONUT_STROKE_WIDTH
    }

    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.storage_center_text)
        textSize = CENTER_TEXT_SIZE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val centerSubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.storage_center_sub_text)
        textSize = CENTER_SUB_TEXT_SIZE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        color = ContextCompat.getColor(context, R.color.storage_label_text)
        textSize = LABEL_TEXT_SIZE
    }

    private val labelValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.RIGHT
        color = ContextCompat.getColor(context, R.color.storage_label_value)
        textSize = LABEL_VALUE_SIZE
    }

    private val categoryDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val detailTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.storage_detail_text)
        textSize = DETAIL_TEXT_SIZE
    }

    // ============================================================================
    // State
    // ============================================================================
    private var storageInfo: StorageInfo? = null
    private var animatedSegments: List<AnimatedSegment> = emptyList()
    private var animatedUsedPercentage = 0f
    private var showDetails = false
    private var selectedCategory: StorageCategory? = null

    // Animation
    private var entryAnimator: ValueAnimator? = null
    private var segmentAnimators: MutableList<ValueAnimator> = mutableListOf()
    private var animProgress = 0f

    // Donut drawing
    private val donutRect = RectF()

    // Touch handling
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            toggleDetails()
            return true
        }
    })

    init {
        // Load custom attributes
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(
                it, R.styleable.StorageInfoView, defStyleAttr, 0
            )
            try {
                donutBackgroundPaint.color = typedArray.getColor(
                    R.styleable.StorageInfoView_donutBackgroundColor,
                    donutBackgroundPaint.color
                )
                centerTextPaint.color = typedArray.getColor(
                    R.styleable.StorageInfoView_centerTextColor,
                    centerTextPaint.color
                )
                centerSubTextPaint.color = typedArray.getColor(
                    R.styleable.StorageInfoView_centerSubTextColor,
                    centerSubTextPaint.color
                )
            } finally {
                typedArray.recycle()
            }
        }

        isClickable = true
    }

    // ============================================================================
    // Public API
    // ============================================================================

    /**
     * Set the storage info to display.
     */
    fun setStorageInfo(info: StorageInfo, animate: Boolean = true) {
        storageInfo = info
        computeSegments()

        if (animate) {
            startEntryAnimation()
        } else {
            animProgress = 1f
            animatedSegments = computeAnimatedSegments(1f)
            animatedUsedPercentage = info.usedPercentage
            invalidate()
        }
    }

    /**
     * Show detailed category breakdown.
     */
    fun showDetails() {
        showDetails = true
        invalidate()
    }

    /**
     * Hide detailed category breakdown.
     */
    fun hideDetails() {
        showDetails = false
        selectedCategory = null
        invalidate()
    }

    /**
     * Toggle details visibility.
     */
    fun toggleDetails() {
        if (showDetails) hideDetails() else showDetails()
    }

    // ============================================================================
    // Drawing
    // ============================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val info = storageInfo ?: return

        val centerX = width / 2f
        val donutCenterY = DONUT_TOP_PADDING + DONUT_RADIUS
        val radius = DONUT_RADIUS

        // Donut rect
        donutRect.set(
            centerX - radius,
            donutCenterY - radius,
            centerX + radius,
            donutCenterY + radius
        )

        // Draw background donut
        canvas.drawArc(donutRect, 0f, 360f, false, donutBackgroundPaint)

        // Draw segments
        var startAngle = START_ANGLE
        animatedSegments.forEach { segment ->
            val sweepAngle = segment.animatedSweepAngle * animProgress
            if (sweepAngle > 0f) {
                donutSegmentPaint.color = segment.color
                canvas.drawArc(donutRect, startAngle, sweepAngle, false, donutSegmentPaint)
                startAngle += sweepAngle
            }
        }

        // Draw center text
        val usedText = formatSize(info.usedBytes)
        val usedLabel = "Used"
        val percentageText = "${animatedUsedPercentage.toInt()}%"

        canvas.drawText(percentageText, centerX, donutCenterY - CENTER_TEXT_OFFSET, centerTextPaint)
        canvas.drawText(usedLabel, centerX, donutCenterY + CENTER_SUB_TEXT_OFFSET, centerSubTextPaint)

        // Draw used/free/total labels below donut
        val labelsY = donutCenterY + radius + LABELS_TOP_MARGIN

        // Used
        drawLabelWithDot(
            canvas,
            centerX - LABELS_COLUMN_WIDTH,
            labelsY,
            "Used",
            formatSize(info.usedBytes),
            ContextCompat.getColor(context, R.color.storage_used_color)
        )

        // Free
        drawLabelWithDot(
            canvas,
            centerX - LABELS_COLUMN_WIDTH + LABELS_COLUMN_WIDTH,
            labelsY,
            "Free",
            formatSize(info.freeBytes),
            ContextCompat.getColor(context, R.color.storage_free_color)
        )

        // Total
        drawLabelWithDot(
            canvas,
            centerX - LABELS_COLUMN_WIDTH + LABELS_COLUMN_WIDTH * 2,
            labelsY,
            "Total",
            formatSize(info.totalBytes),
            ContextCompat.getColor(context, R.color.storage_total_color)
        )

        // Draw category breakdown (when showing details)
        if (showDetails && info.categories.isNotEmpty()) {
            drawCategoryBreakdown(canvas, centerX, labelsY + CATEGORY_TOP_MARGIN)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (DEFAULT_WIDTH_DP * resources.displayMetrics.density).toInt()
        val desiredHeight = if (showDetails) {
            (DEFAULT_HEIGHT_WITH_DETAILS_DP * resources.displayMetrics.density).toInt()
        } else {
            (DEFAULT_HEIGHT_DP * resources.displayMetrics.density).toInt()
        }

        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)

        setMeasuredDimension(width, height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // ============================================================================
    // Private Drawing Helpers
    // ============================================================================

    private fun drawLabelWithDot(
        canvas: Canvas,
        x: Float,
        y: Float,
        label: String,
        value: String,
        dotColor: Int,
    ) {
        // Dot
        categoryDotPaint.color = dotColor
        canvas.drawCircle(x, y - LABEL_DOT_OFFSET, LABEL_DOT_RADIUS, categoryDotPaint)

        // Label
        canvas.drawText(label, x + LABEL_DOT_SPACING, y, labelPaint)

        // Value
        canvas.drawText(value, x + LABEL_DOT_SPACING + LABEL_VALUE_OFFSET, y, labelValuePaint)
    }

    private fun drawCategoryBreakdown(canvas: Canvas, centerX: Float, startY: Float) {
        val info = storageInfo ?: return
        val totalUsed = info.usedBytes.toFloat()

        info.categories.forEachIndexed { index, category ->
            val y = startY + index * CATEGORY_ITEM_HEIGHT
            val percentage = if (totalUsed > 0) (category.sizeBytes / totalUsed) * 100 else 0

            // Category color dot
            categoryDotPaint.color = category.color
            canvas.drawCircle(
                centerX - CATEGORY_DOT_OFFSET,
                y,
                CATEGORY_DOT_RADIUS,
                categoryDotPaint
            )

            // Category name
            canvas.drawText(
                category.name,
                centerX - CATEGORY_DOT_OFFSET + CATEGORY_DOT_SPACING,
                y + CATEGORY_TEXT_Y_OFFSET,
                labelPaint
            )

            // Category size and percentage
            val detailText = "${formatSize(category.sizeBytes)} (${String.format("%.1f", percentage)}%)"
            canvas.drawText(
                detailText,
                centerX + CATEGORY_DOT_OFFSET + CATEGORY_DOT_SPACING,
                y + CATEGORY_TEXT_Y_OFFSET,
                labelValuePaint
            )
        }
    }

    // ============================================================================
    // Segment Computation
    // ============================================================================

    private data class AnimatedSegment(
        val color: Int,
        val animatedSweepAngle: Float,
    )

    private fun computeSegments() {
        val info = storageInfo ?: return
        animatedSegments = computeAnimatedSegments(1f)
    }

    private fun computeAnimatedSegments(progress: Float): List<AnimatedSegment> {
        val info = storageInfo ?: return emptyList()
        if (info.totalBytes <= 0) return emptyList()

        val totalAngle = 360f

        return info.categories.map { category ->
            val fraction = category.sizeBytes.toFloat() / info.totalBytes.toFloat()
            AnimatedSegment(
                color = category.color,
                animatedSweepAngle = totalAngle * fraction,
            )
        }
    }

    // ============================================================================
    // Animations
    // ============================================================================

    private fun startEntryAnimation() {
        cancelAnimations()

        // Animate the overall progress
        entryAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ENTRY_ANIMATION_DURATION
            interpolator = FastOutSlowInInterpolator()

            addUpdateListener { animation ->
                animProgress = animation.animatedValue as Float
                storageInfo?.let {
                    animatedUsedPercentage = it.usedPercentage * animProgress
                }
                invalidate()
            }
        }

        entryAnimator?.start()
    }

    private fun cancelAnimations() {
        entryAnimator?.cancel()
        entryAnimator = null
        segmentAnimators.forEach { it.cancel() }
        segmentAnimators.clear()
    }

    // ============================================================================
    // Utility
    // ============================================================================

    /**
     * Format bytes to a human-readable string.
     */
    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val unitSize = 1024.0
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(unitSize)).toInt()

        val value = bytes / Math.pow(unitSize, digitGroups.toDouble())
        return String.format("%.1f %s", value, units[digitGroups.coerceAtMost(units.size - 1)])
    }

    // ============================================================================
    // Companion Constants
    // ============================================================================

    companion object {
        private const val START_ANGLE = -90f

        private const val DONUT_STROKE_WIDTH = 24f
        private const val DONUT_RADIUS = 100f
        private const val DONUT_TOP_PADDING = 120f

        private const val CENTER_TEXT_SIZE = 42f
        private const val CENTER_SUB_TEXT_SIZE = 14f
        private const val CENTER_TEXT_OFFSET = 8f
        private const val CENTER_SUB_TEXT_OFFSET = 24f

        private const val LABEL_TEXT_SIZE = 12f
        private const val LABEL_VALUE_SIZE = 12f
        private const val LABELS_TOP_MARGIN = 50f
        private const val LABELS_COLUMN_WIDTH = 120f
        private const val LABEL_DOT_RADIUS = 4f
        private const val LABEL_DOT_OFFSET = 4f
        private const val LABEL_DOT_SPACING = 12f
        private const val LABEL_VALUE_OFFSET = 40f

        private const val CATEGORY_TOP_MARGIN = 40f
        private const val CATEGORY_ITEM_HEIGHT = 36f
        private const val CATEGORY_DOT_RADIUS = 5f
        private const val CATEGORY_DOT_OFFSET = 80f
        private const val CATEGORY_DOT_SPACING = 16f
        private const val CATEGORY_TEXT_Y_OFFSET = 4f

        private const val DETAIL_TEXT_SIZE = 12f

        private const val DEFAULT_WIDTH_DP = 300
        private const val DEFAULT_HEIGHT_DP = 350
        private const val DEFAULT_HEIGHT_WITH_DETAILS_DP = 550

        private const val ENTRY_ANIMATION_DURATION = 1000L
    }
}
