package com.ultimaterecovery.pro.ui.animations

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.annotation.AnimRes
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.ViewPropertyAnimatorCompat
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import android.transition.AutoTransition
import android.transition.ChangeBounds
import android.transition.ChangeClipBounds
import android.transition.ChangeTransform
import android.transition.Fade
import android.transition.Slide
import android.transition.Transition
import android.transition.TransitionManager
import android.transition.TransitionSet
import com.google.android.material.card.MaterialCardView
import kotlin.math.hypot
import kotlin.math.max

/**
 * Animation utility class for Ultimate Recovery Pro.
 * Provides reusable animation helpers for views, RecyclerView items,
 * Lottie animations, shared element transitions, and more.
 */
object AnimationUtils {

    // ============================================================================
    // Constants
    // ============================================================================
    const val DEFAULT_DURATION = 300L
    const val SHORT_DURATION = 150L
    const val LONG_DURATION = 500L
    const val EXTRA_LONG_DURATION = 800L

    // ============================================================================
    // Custom Interpolators
    // ============================================================================
    val FAST_OUT_SLOW_IN: Interpolator = FastOutSlowInInterpolator()
    val LINEAR_OUT_SLOW_IN: Interpolator = LinearOutSlowInInterpolator()
    val FAST_OUT_LINEAR_IN: Interpolator = FastOutLinearInInterpolator()
    val OVERSHOOT: Interpolator = OvershootInterpolator(1.5f)
    val ANTICIPATE_OVERSHOOT: Interpolator = AnticipateOvershootInterpolator(1.2f)
    val DECELERATE: Interpolator = DecelerateInterpolator(1.5f)
    val ACCELERATE_DECELERATE: Interpolator = AccelerateDecelerateInterpolator()

    // ============================================================================
    // View Enter Animations
    // ============================================================================

    /**
     * Fade in a view from fully transparent.
     */
    fun fadeIn(
        view: View,
        duration: Long = DEFAULT_DURATION,
        delay: Long = 0,
        interpolator: Interpolator = FAST_OUT_SLOW_IN,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        view.alpha = 0f
        view?.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(interpolator)
            .withStartAction { onStart?.invoke() }
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    /**
     * Slide in from the bottom with fade.
     */
    fun slideInFromBottom(
        view: View,
        duration: Long = DEFAULT_DURATION,
        delay: Long = 0,
        translationY: Float = view.height.toFloat().coerceAtLeast(200f),
        interpolator: Interpolator = FAST_OUT_SLOW_IN,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        view.alpha = 0f
        view.translationY = translationY
        view?.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(interpolator)
            .withStartAction { onStart?.invoke() }
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    /**
     * Slide in from the left with fade.
     */
    fun slideInFromLeft(
        view: View,
        duration: Long = DEFAULT_DURATION,
        delay: Long = 0,
        interpolator: Interpolator = FAST_OUT_SLOW_IN,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        view.alpha = 0f
        view.translationX = -view.width.toFloat().coerceAtLeast(300f)
        view?.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(interpolator)
            .withStartAction { onStart?.invoke() }
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    /**
     * Slide in from the right with fade.
     */
    fun slideInFromRight(
        view: View,
        duration: Long = DEFAULT_DURATION,
        delay: Long = 0,
        interpolator: Interpolator = FAST_OUT_SLOW_IN,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        view.alpha = 0f
        view.translationX = view.width.toFloat().coerceAtLeast(300f)
        view?.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(interpolator)
            .withStartAction { onStart?.invoke() }
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    /**
     * Scale in from a smaller size with fade.
     */
    fun scaleIn(
        view: View,
        duration: Long = DEFAULT_DURATION,
        delay: Long = 0,
        fromScale: Float = 0.5f,
        interpolator: Interpolator = OVERSHOOT,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        view.alpha = 0f
        view.scaleX = fromScale
        view.scaleY = fromScale
        view?.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(interpolator)
            .withStartAction { onStart?.invoke() }
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    // ============================================================================
    // View Exit Animations
    // ============================================================================

    /**
     * Fade out a view to fully transparent.
     */
    fun fadeOut(
        view: View,
        duration: Long = DEFAULT_DURATION,
        delay: Long = 0,
        interpolator: Interpolator = FAST_OUT_LINEAR_IN,
        toVisibility: Int = View.GONE,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        view.animate()
            .alpha(0f)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(interpolator)
            .withStartAction { onStart?.invoke() }
            .withEndAction {
                view.visibility = toVisibility
                onEnd?.invoke()
            }
            .start()
    }

    /**
     * Slide out to the bottom with fade.
     */
    fun slideOutToBottom(
        view: View,
        duration: Long = DEFAULT_DURATION,
        delay: Long = 0,
        translationY: Float = view.height.toFloat().coerceAtLeast(200f),
        interpolator: Interpolator = FAST_OUT_LINEAR_IN,
        toVisibility: Int = View.GONE,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        view.animate()
            .alpha(0f)
            .translationY(translationY)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(interpolator)
            .withStartAction { onStart?.invoke() }
            .withEndAction {
                view.visibility = toVisibility
                view.translationY = 0f
                onEnd?.invoke()
            }
            .start()
    }

    /**
     * Slide out to the left with fade.
     */
    fun slideOutToLeft(
        view: View,
        duration: Long = DEFAULT_DURATION,
        delay: Long = 0,
        interpolator: Interpolator = FAST_OUT_LINEAR_IN,
        toVisibility: Int = View.GONE,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        view.animate()
            .alpha(0f)
            .translationX(-view.width.toFloat().coerceAtLeast(300f))
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(interpolator)
            .withStartAction { onStart?.invoke() }
            .withEndAction {
                view.visibility = toVisibility
                view.translationX = 0f
                onEnd?.invoke()
            }
            .start()
    }

    /**
     * Slide out to the right with fade.
     */
    fun slideOutToRight(
        view: View,
        duration: Long = DEFAULT_DURATION,
        delay: Long = 0,
        interpolator: Interpolator = FAST_OUT_LINEAR_IN,
        toVisibility: Int = View.GONE,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        view.animate()
            .alpha(0f)
            .translationX(view.width.toFloat().coerceAtLeast(300f))
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(interpolator)
            .withStartAction { onStart?.invoke() }
            .withEndAction {
                view.visibility = toVisibility
                view.translationX = 0f
                onEnd?.invoke()
            }
            .start()
    }

    /**
     * Scale out to a smaller size with fade.
     */
    fun scaleOut(
        view: View,
        duration: Long = DEFAULT_DURATION,
        delay: Long = 0,
        toScale: Float = 0.5f,
        interpolator: Interpolator = FAST_OUT_LINEAR_IN,
        toVisibility: Int = View.GONE,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        view.animate()
            .alpha(0f)
            .scaleX(toScale)
            .scaleY(toScale)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(interpolator)
            .withStartAction { onStart?.invoke() }
            .withEndAction {
                view.visibility = toVisibility
                view.scaleX = 1f
                view.scaleY = 1f
                onEnd?.invoke()
            }
            .start()
    }

    // ============================================================================
    // RecyclerView Item Animations
    // ============================================================================

    /**
     * Animate a RecyclerView item appearing with a staggered delay based on position.
     */
    fun animateItemEnter(
        holder: RecyclerView.ViewHolder,
        position: Int,
        previousPosition: Int = -1,
    ) {
        val itemView = holder.itemView
        itemView.alpha = 0f
        itemView.translationY = 80f

        val delay = if (position > previousPosition) {
            (position - max(0, previousPosition)) * 50L
        } else {
            0L
        }

        itemView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(DEFAULT_DURATION)
            .setStartDelay(delay.coerceAtMost(500))
            .setInterpolator(FAST_OUT_SLOW_IN)
            .start()
    }

    /**
     * Animate a RecyclerView item being removed.
     */
    fun animateItemRemove(
        holder: RecyclerView.ViewHolder,
        onEnd: (() -> Unit)? = null,
    ) {
        val itemView = holder.itemView
        itemView.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .translationX(itemView.width.toFloat())
            .setDuration(DEFAULT_DURATION)
            .setInterpolator(FAST_OUT_LINEAR_IN)
            .withEndAction {
                itemView.scaleX = 1f
                itemView.scaleY = 1f
                itemView.translationX = 0f
                onEnd?.invoke()
            }
            .start()
    }

    /**
     * Animate a RecyclerView item being moved.
     */
    fun animateItemMove(holder: RecyclerView.ViewHolder, fromX: Float, fromY: Float) {
        val itemView = holder.itemView
        val deltaX = fromX - itemView.translationX
        val deltaY = fromY - itemView.translationY

        itemView.translationX = fromX
        itemView.translationY = fromY

        itemView.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(DEFAULT_DURATION)
            .setInterpolator(FAST_OUT_SLOW_IN)
            .start()
    }

    /**
     * Staggered entrance animation for multiple views (e.g., grid items).
     */
    fun staggeredEnter(
        views: List<View>,
        direction: StaggerDirection = StaggerDirection.BOTTOM,
        itemDelay: Long = 50L,
        duration: Long = DEFAULT_DURATION,
    ) {
        views.forEachIndexed { index, view ->
            val delay = index * itemDelay
            when (direction) {
                StaggerDirection.BOTTOM -> slideInFromBottom(view, duration, delay)
                StaggerDirection.LEFT -> slideInFromLeft(view, duration, delay)
                StaggerDirection.RIGHT -> slideInFromRight(view, duration, delay)
                StaggerDirection.SCALE -> scaleIn(view, duration, delay)
                StaggerDirection.FADE -> fadeIn(view, duration, delay)
            }
        }
    }

    enum class StaggerDirection {
        BOTTOM, LEFT, RIGHT, SCALE, FADE
    }

    // ============================================================================
    // Shimmer Effect Helper
    // ============================================================================

    /**
     * Create and start a shimmer animation on a view.
     * Typically used on placeholder/skeleton views.
     */
    fun startShimmer(
        view: View,
        duration: Long = 1500L,
        repeatCount: Int = ValueAnimator.INFINITE,
    ): ValueAnimator {
        val shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            this.repeatCount = repeatCount
            this.repeatMode = ValueAnimator.RESTART
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                val translation = view.width * value
                view.translationX = translation - view.width
            }
        }
        shimmerAnimator.start()
        return shimmerAnimator
    }

    /**
     * Create a shimmer alpha pulse effect.
     */
    fun startShimmerPulse(
        view: View,
        duration: Long = 1000L,
        minAlpha: Float = 0.3f,
        maxAlpha: Float = 0.7f,
    ): ValueAnimator {
        val animator = ValueAnimator.ofFloat(minAlpha, maxAlpha).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animation ->
                view.alpha = animation.animatedValue as Float
            }
        }
        animator.start()
        return animator
    }

    /**
     * Apply a shimmer translation effect across a group of views.
     */
    fun applyShimmerToGroup(
        views: List<View>,
        duration: Long = 1500L,
    ): List<ValueAnimator> {
        return views.mapIndexed { index, view ->
            ValueAnimator.ofFloat(-1f, 1f).apply {
                this.duration = duration
                startDelay = (index * 100L).coerceAtMost(300)
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    val translation = view.width * 1.5f * value
                    view.translationX = translation
                }
                start()
            }
        }
    }

    // ============================================================================
    // Lottie Animation Helper
    // ============================================================================

    /**
     * Play a Lottie animation with optional callbacks.
     */
    fun playLottieAnimation(
        lottieView: LottieAnimationView,
        rawRes: Int? = null,
        url: String? = null,
        loop: Boolean = false,
        speed: Float = 1f,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        rawRes?.let { lottieView.setAnimation(it) }
        url?.let { lottieView.setAnimationFromUrl(it) }

        lottieView.repeatCount = if (loop) ValueAnimator.INFINITE else 0
        lottieView.speed = speed

        lottieView.addAnimatorListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                onStart?.invoke()
            }

            override fun onAnimationEnd(animation: Animator) {
                onEnd?.invoke()
            }
        })

        lottieView.playAnimation()
    }

    /**
     * Play a Lottie animation with a progress range.
     */
    fun playLottieRange(
        lottieView: LottieAnimationView,
        rawRes: Int,
        startProgress: Float = 0f,
        endProgress: Float = 1f,
        speed: Float = 1f,
    ) {
        lottieView.setAnimation(rawRes)
        lottieView.setMinAndMaxProgress(startProgress, endProgress)
        lottieView.speed = speed
        lottieView.playAnimation()
    }

    /**
     * Load a Lottie composition asynchronously.
     */
    fun loadLottieComposition(
        context: Context,
        rawRes: Int,
        onLoaded: (LottieComposition) -> Unit,
    ) {
        LottieCompositionFactory.fromRawRes(context, rawRes)
            .addListener { composition ->
                composition?.let { onLoaded(it) }
            }
    }

    /**
     * Animate a Lottie view based on a progress value (0f to 1f).
     * Useful for syncing Lottie with scroll or custom progress.
     */
    fun setLottieProgress(lottieView: LottieAnimationView, progress: Float) {
        lottieView.progress = progress.coerceIn(0f, 1f)
    }

    // ============================================================================
    // Shared Element Transition Helpers
    // ============================================================================

    /**
     * Set up a shared element transition for a Fragment.
     */
    fun setupSharedElementTransition(
        fragment: androidx.fragment.app.Fragment,
        transitionDuration: Long = DEFAULT_DURATION,
    ) {
        val transitionSet = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            duration = transitionDuration
            interpolator = FAST_OUT_SLOW_IN
            addTransition(ChangeBounds())
            addTransition(ChangeTransform())
            addTransition(ChangeClipBounds())
            addTransition(Fade(Fade.IN))
        }
        fragment.sharedElementEnterTransition = transitionSet
        fragment.sharedElementReturnTransition = transitionSet
    }

    /**
     * Set up a shared element transition for an Activity.
     */
    fun setupActivitySharedElementTransition(
        activity: androidx.appcompat.app.AppCompatActivity,
        transitionDuration: Long = DEFAULT_DURATION,
    ) {
        val transitionSet = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            duration = transitionDuration
            interpolator = FAST_OUT_SLOW_IN
            addTransition(ChangeBounds())
            addTransition(ChangeTransform())
            addTransition(ChangeClipBounds())
            addTransition(Fade(Fade.IN))
        }
        activity.window.sharedElementEnterTransition = transitionSet as Transition
        activity.window.sharedElementReturnTransition = transitionSet as Transition
    }

    /**
     * Create a combined transition set for enter transition.
     */
    fun createEnterTransition(
        duration: Long = DEFAULT_DURATION,
        slideEdge: Int = android.view.Gravity.BOTTOM,
    ): TransitionSet {
        return TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            this.duration = duration
            interpolator = FAST_OUT_SLOW_IN
            addTransition(Slide(slideEdge))
            addTransition(Fade())
        }
    }

    /**
     * Create a combined transition set for exit transition.
     */
    fun createExitTransition(
        duration: Long = DEFAULT_DURATION,
        slideEdge: Int = android.view.Gravity.TOP,
    ): TransitionSet {
        return TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            this.duration = duration
            interpolator = FAST_OUT_LINEAR_IN
            addTransition(Slide(slideEdge))
            addTransition(Fade())
        }
    }

    /**
     * Begin a delayed transition on a ViewGroup using TransitionManager.
     */
    fun beginDelayedTransition(
        parent: ViewGroup,
        duration: Long = DEFAULT_DURATION,
    ) {
        val transition = AutoTransition().apply {
            this.duration = duration
            interpolator = FAST_OUT_SLOW_IN
        }
        TransitionManager.beginDelayedTransition(parent, transition)
    }

    // ============================================================================
    // Circular Reveal Animation
    // ============================================================================

    /**
     * Perform a circular reveal animation on a view.
     * Falls back to fade on devices below API 21.
     */
    fun circularReveal(
        view: View,
        centerX: Int = view.width / 2,
        centerY: Int = view.height / 2,
        startRadius: Float = 0f,
        endRadius: Float = hypot(
            view.width.toDouble(),
            view.height.toDouble()
        ).toFloat(),
        duration: Long = DEFAULT_DURATION,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view?.visibility = View.VISIBLE
            val reveal = android.view.ViewAnimationUtils.createCircularReveal(
                view, centerX, centerY, startRadius, endRadius
            )
            reveal.duration = duration
            reveal.interpolator = FAST_OUT_SLOW_IN
            reveal.doOnEnd { onEnd?.invoke() }
            reveal.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    onStart?.invoke()
                }
            })
            reveal.start()
        } else {
            // Fallback for pre-Lollipop
            fadeIn(view, duration, onStart = onStart, onEnd = onEnd)
        }
    }

    /**
     * Perform a circular conceal (reverse reveal) animation on a view.
     */
    fun circularConceal(
        view: View,
        centerX: Int = view.width / 2,
        centerY: Int = view.height / 2,
        startRadius: Float = hypot(
            view.width.toDouble(),
            view.height.toDouble()
        ).toFloat(),
        endRadius: Float = 0f,
        duration: Long = DEFAULT_DURATION,
        toVisibility: Int = View.INVISIBLE,
        onEnd: (() -> Unit)? = null,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val reveal = android.view.ViewAnimationUtils.createCircularReveal(
                view, centerX, centerY, startRadius, endRadius
            )
            reveal.duration = duration
            reveal.interpolator = FAST_OUT_LINEAR_IN
            reveal.doOnEnd {
                view.visibility = toVisibility
                onEnd?.invoke()
            }
            reveal.start()
        } else {
            fadeOut(view, duration, toVisibility = toVisibility, onEnd = onEnd)
        }
    }

    /**
     * Circular reveal from the center of a touch event.
     */
    fun circularRevealFromTouch(
        view: View,
        touchX: Int,
        touchY: Int,
        parent: ViewGroup,
        duration: Long = DEFAULT_DURATION,
    ) {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val centerX = touchX - location[0]
        val centerY = touchY - location[1]

        circularReveal(view, centerX, centerY, duration = duration)
    }

    // ============================================================================
    // Spring Animation Helpers
    // ============================================================================

    /**
     * Create a spring-like bounce animation on a view.
     * Uses ObjectAnimator to simulate spring physics.
     */
    fun springBounce(
        view: View,
        bounceHeight: Float = 20f,
        duration: Long = 600L,
        onEnd: (() -> Unit)? = null,
    ) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = OVERSHOOT

            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val translation = bounceHeight * Math.sin(fraction * Math.PI * 2).toFloat()
                view.translationY = -translation
            }

            doOnEnd {
                view.translationY = 0f
                onEnd?.invoke()
            }
        }
        animator.start()
    }

    /**
     * Create a spring scale animation (bouncy scale up and back).
     */
    fun springScale(
        view: View,
        targetScale: Float = 1.1f,
        duration: Long = 400L,
        onEnd: (() -> Unit)? = null,
    ) {
        val scaleUpX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, targetScale)
        val scaleUpY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, targetScale)
        val scaleDownX = ObjectAnimator.ofFloat(view, View.SCALE_X, targetScale, 1f)
        val scaleDownY = ObjectAnimator.ofFloat(view, View.SCALE_Y, targetScale, 1f)

        scaleUpX.interpolator = OVERSHOOT
        scaleUpY.interpolator = OVERSHOOT
        scaleDownX.interpolator = DECELERATE
        scaleDownY.interpolator = DECELERATE

        val animatorSet = AnimatorSet()
        animatorSet.play(scaleUpX).with(scaleUpY)
        animatorSet.play(scaleDownX).with(scaleDownY).after(scaleUpX)
        animatorSet.duration = duration / 2

        animatorSet.doOnEnd {
            view.scaleX = 1f
            view.scaleY = 1f
            onEnd?.invoke()
        }

        animatorSet.start()
    }

    /**
     * Create a spring rotation animation.
     */
    fun springRotate(
        view: View,
        angle: Float = 5f,
        duration: Long = 400L,
        onEnd: (() -> Unit)? = null,
    ) {
        val rotateRight = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, angle)
        val rotateLeft = ObjectAnimator.ofFloat(view, View.ROTATION, angle, -angle * 0.5f)
        val rotateCenter = ObjectAnimator.ofFloat(view, View.ROTATION, -angle * 0.5f, 0f)

        rotateRight.interpolator = OVERSHOOT
        rotateLeft.interpolator = DECELERATE
        rotateCenter.interpolator = DECELERATE

        val animatorSet = AnimatorSet()
        animatorSet.play(rotateLeft).after(rotateRight)
        animatorSet.play(rotateCenter).after(rotateLeft)
        animatorSet.duration = duration / 3

        animatorSet.doOnEnd {
            view.rotation = 0f
            onEnd?.invoke()
        }

        animatorSet.start()
    }

    /**
     * Create a spring translation animation (for drag release or card swipe effect).
     */
    fun springTranslate(
        view: View,
        fromX: Float = 0f,
        toX: Float = 0f,
        fromY: Float = 0f,
        toY: Float = 0f,
        duration: Long = 500L,
        onEnd: (() -> Unit)? = null,
    ) {
        val translateX = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, fromX, toX)
        val translateY = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, fromY, toY)

        translateX.interpolator = OVERSHOOT
        translateY.interpolator = OVERSHOOT

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(translateX, translateY)
        animatorSet.duration = duration

        animatorSet.doOnEnd {
            view.translationX = 0f
            view.translationY = 0f
            onEnd?.invoke()
        }

        animatorSet.start()
    }

    /**
     * Create a pulsing animation that continuously scales a view up and down.
     */
    fun pulseAnimation(
        view: View,
        minScale: Float = 0.95f,
        maxScale: Float = 1.05f,
        duration: Long = 800L,
    ): ValueAnimator {
        val animator = ValueAnimator.ofFloat(minScale, maxScale).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animation ->
                val scale = animation.animatedValue as Float
                view.scaleX = scale
                view.scaleY = scale
            }
        }
        animator.start()
        return animator
    }

    // ============================================================================
    // Color Animation
    // ============================================================================

    /**
     * Animate a color change on a view's background.
     */
    fun animateColorChange(
        view: View,
        fromColor: Int,
        toColor: Int,
        duration: Long = DEFAULT_DURATION,
        onEnd: (() -> Unit)? = null,
    ) {
        val animator = ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
            this.duration = duration
            addUpdateListener { animation ->
                view.setBackgroundColor(animation.animatedValue as Int)
            }
            doOnEnd { onEnd?.invoke() }
        }
        animator.start()
    }

    // ============================================================================
    // Utility Functions
    // ============================================================================

    /**
     * Animate multiple views sequentially.
     */
    fun animateSequentially(
        vararg animations: Pair<View, () -> Unit>,
        delayBetween: Long = 100L,
    ) {
        animations.forEachIndexed { index, (view, animationFn) ->
            view.postDelayed({ animationFn() }, index * delayBetween)
        }
    }

    /**
     * Cancel all animations on a view.
     */
    fun cancelAnimations(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
    }

    /**
     * Check if a view is currently being animated.
     */
    fun isAnimating(view: View): Boolean {
        return view.animate() != null
    }
}
