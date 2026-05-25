package com.ultimaterecovery.pro.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.ultimaterecovery.pro.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom View for displaying circular scan progress with animated ring,
 * percentage text, file count, scan type label, and pulsing glow effect.
 */
class ScanProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ============================================================================
    // Paints
    // ============================================================================
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = STROKE_WIDTH
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = STROKE_WIDTH
        color = ContextCompat.getColor(context, R.color.scan_progress_background)
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = STROKE_WIDTH + GLOW_EXTRA_WIDTH
        alpha = 0
    }

    private val percentagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.scan_progress_text)
        textSize = PERCENTAGE_TEXT_SIZE
        isFakeBoldText = true
    }

    private val fileCountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.scan_progress_file_count)
        textSize = FILE_COUNT_TEXT_SIZE
    }

    private val scanTypePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.scan_progress_type_label)
        textSize = SCAN_TYPE_TEXT_SIZE
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ============================================================================
    // State
    // ============================================================================
    private var currentProgress = 0f
    private var animatedProgress = 0f
    private var fileCount = 0
    private var scanType: String = ""
    private var isScanning = false

    // Glow animation
    private var glowAlpha = 0f
    private var glowPhase = 0f
    private var pulseAnimator: ValueAnimator? = null

    // Progress animator
    private var progressAnimator: ValueAnimator? = null

    // Sweep angle tracking for rotation effect
    private var sweepAngle = 0f

    // Rectangle for drawing arcs
    private val arcRect = RectF()

    // ============================================================================
    // Colors
    // ============================================================================
    private var progressColorStart: Int = ContextCompat.getColor(context, R.color.scan_progress_start)
    private var progressColorEnd: Int = ContextCompat.getColor(context, R.color.scan_progress_end)
    private var glowColor: Int = ContextCompat.getColor(context, R.color.scan_progress_glow)
    private var dotColor: Int = ContextCompat.getColor(context, R.color.scan_progress_dot)

    init {
        // Load custom attributes if provided
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(
                it, R.styleable.ScanProgressView, defStyleAttr, 0
            )
            try {
                progressColorStart = typedArray.getColor(
                    R.styleable.ScanProgressView_progressColorStart, progressColorStart
                )
                progressColorEnd = typedArray.getColor(
                    R.styleable.ScanProgressView_progressColorEnd, progressColorEnd
                )
                glowColor = typedArray.getColor(
                    R.styleable.ScanProgressView_glowColor, glowColor
                )
                dotColor = typedArray.getColor(
                    R.styleable.ScanProgressView_dotColor, dotColor
                )
            } finally {
                typedArray.recycle()
            }
        }

        glowPaint.color = glowColor
        dotPaint.color = dotColor
    }

    // ============================================================================
    // Public API
    // ============================================================================

    /**
     * Set the current scan progress (0 to 100).
     */
    fun setProgress(progress: Float, animate: Boolean = true) {
        val clampedProgress = progress.coerceIn(0f, 100f)

        if (animate) {
            animateProgressChange(currentProgress, clampedProgress)
        } else {
            animatedProgress = clampedProgress
            currentProgress = clampedProgress
        }

        currentProgress = clampedProgress
    }

    /**
     * Set the number of files found so far.
     */
    fun setFileCount(count: Int) {
        fileCount = count
        invalidate()
    }

    /**
     * Set the scan type label (e.g., "Deep Scan", "Quick Scan", "Photo Recovery").
     */
    fun setScanType(type: String) {
        scanType = type
        invalidate()
    }

    /**
     * Start the scanning state (enables pulsing glow).
     */
    fun startScanning() {
        isScanning = true
        startPulseAnimation()
        invalidate()
    }

    /**
     * Stop the scanning state (disables pulsing glow).
     */
    fun stopScanning() {
        isScanning = false
        stopPulseAnimation()
        invalidate()
    }

    /**
     * Reset the view to its initial state.
     */
    fun reset() {
        stopScanning()
        currentProgress = 0f
        animatedProgress = 0f
        fileCount = 0
        scanType = ""
        sweepAngle = 0f
        progressAnimator?.cancel()
        progressAnimator = null
        invalidate()
    }

    /**
     * Set custom colors for the progress ring.
     */
    fun setColors(
        startColor: Int,
        endColor: Int,
        glow: Int? = null,
        dot: Int? = null,
    ) {
        progressColorStart = startColor
        progressColorEnd = endColor
        glow?.let {
            glowColor = it
            glowPaint.color = it
        }
        dot?.let {
            dotColor = it
            dotPaint.color = it
        }
        invalidate()
    }

    // ============================================================================
    // Drawing
    // ============================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (min(width, height) / 2f) - STROKE_WIDTH - GLOW_EXTRA_WIDTH - PADDING

        // Update arc rect
        arcRect.set(
            centerX - radius,
            centerY - radius + TEXT_OFFSET,
            centerX + radius,
            centerY + radius + TEXT_OFFSET
        )

        // Draw background circle
        canvas.drawArc(arcRect, 0f, 360f, false, backgroundPaint)

        // Draw glow effect (when scanning)
        if (isScanning && glowAlpha > 0) {
            glowPaint.alpha = (glowAlpha * 255).toInt()
            val glowSweep = (animatedProgress / 100f) * 360f
            canvas.drawArc(arcRect, START_ANGLE, glowSweep, false, glowPaint)
        }

        // Draw progress arc with gradient
        if (animatedProgress > 0) {
            val sweepAngle = (animatedProgress / 100f) * 360f

            // Create gradient for progress arc
            val gradient = LinearGradient(
                centerX - radius, centerY - radius + TEXT_OFFSET,
                centerX + radius, centerY + radius + TEXT_OFFSET,
                progressColorStart, progressColorEnd,
                Shader.TileMode.CLAMP
            )
            progressPaint.shader = gradient

            canvas.drawArc(arcRect, START_ANGLE, sweepAngle, false, progressPaint)

            // Draw endpoint dot
            val dotAngle = Math.toRadians((START_ANGLE + sweepAngle).toDouble())
            val dotX = centerX + radius * cos(dotAngle).toFloat()
            val dotY = centerY + TEXT_OFFSET + radius * sin(dotAngle).toFloat()
            canvas.drawCircle(dotX, dotY, DOT_RADIUS, dotPaint)
        }

        // Draw percentage text
        val percentageText = "${animatedProgress.toInt()}%"
        canvas.drawText(
            percentageText,
            centerX,
            centerY + TEXT_OFFSET - PERCENTAGE_Y_OFFSET,
            percentagePaint
        )

        // Draw file count below percentage
        if (fileCount > 0) {
            val fileText = "$fileCount files found"
            canvas.drawText(
                fileText,
                centerX,
                centerY + TEXT_OFFSET + FILE_COUNT_Y_OFFSET,
                fileCountPaint
            )
        }

        // Draw scan type label
        if (scanType.isNotEmpty()) {
            canvas.drawText(
                scanType,
                centerX,
                centerY + TEXT_OFFSET + SCAN_TYPE_Y_OFFSET,
                scanTypePaint
            )
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredSize = (DEFAULT_SIZE_DP * resources.displayMetrics.density).toInt()

        val width = resolveSize(desiredSize, widthMeasureSpec)
        val height = resolveSize(desiredSize, heightMeasureSpec)

        val size = min(width, height)
        setMeasuredDimension(size, size)
    }

    // ============================================================================
    // Animations
    // ============================================================================

    private fun animateProgressChange(from: Float, to: Float) {
        progressAnimator?.cancel()

        progressAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = PROGRESS_ANIMATION_DURATION
            interpolator = FastOutSlowInInterpolator()

            addUpdateListener { animation ->
                animatedProgress = animation.animatedValue as Float
                invalidate()
            }
        }

        progressAnimator?.start()
    }

    private fun startPulseAnimation() {
        stopPulseAnimation()

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_DURATION
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { animation ->
                val value = animation.animatedValue as Float

                // Pulse glow alpha
                glowAlpha = MIN_GLOW_ALPHA + (MAX_GLOW_ALPHA - MIN_GLOW_ALPHA) * value

                // Slight scale pulse for the percentage text
                val scale = 1f + 0.02f * sin(value * Math.PI).toFloat()
                percentagePaint.textSize = PERCENTAGE_TEXT_SIZE * scale

                invalidate()
            }
        }

        pulseAnimator?.start()
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        glowAlpha = 0f
        percentagePaint.textSize = PERCENTAGE_TEXT_SIZE
    }

    // ============================================================================
    // Companion Constants
    // ============================================================================

    companion object {
        private const val START_ANGLE = -90f // Start from top (12 o'clock)
        private const val STROKE_WIDTH = 12f
        private const val GLOW_EXTRA_WIDTH = 8f
        private const val PADDING = 16f
        private const val DOT_RADIUS = 6f

        private const val PERCENTAGE_TEXT_SIZE = 56f
        private const val FILE_COUNT_TEXT_SIZE = 16f
        private const val SCAN_TYPE_TEXT_SIZE = 14f

        private const val PERCENTAGE_Y_OFFSET = 10f
        private const val FILE_COUNT_Y_OFFSET = 30f
        private const val SCAN_TYPE_Y_OFFSET = 50f
        private const val TEXT_OFFSET = 10f

        private const val DEFAULT_SIZE_DP = 260

        private const val PROGRESS_ANIMATION_DURATION = 800L
        private const val PULSE_DURATION = 1200L

        private const val MIN_GLOW_ALPHA = 0.15f
        private const val MAX_GLOW_ALPHA = 0.5f
    }
}
