package com.ultimaterecovery.pro.ui.activities

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.databinding.ActivityLockBinding
import java.util.concurrent.Executor
import timber.log.Timber

/**
 * App lock screen activity.
 *
 * Features:
 * - PIN input with 4-6 digit pad
 * - Password input option
 * - Biometric prompt (fingerprint / face)
 * - Fingerprint icon animation
 * - Wrong attempt counter with exponential delay
 * - Beautiful lock screen design with gradient and Lottie
 */
class LockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SECURITY_LEVEL = "extra_security_level"
        const val RESULT_AUTHENTICATED = 100
        const val RESULT_NOT_AUTHENTICATED = 101

        private const val MAX_WRONG_ATTEMPTS = 5
        private const val BASE_DELAY_MS = 1000L
    }

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: ActivityLockBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // State
    // ──────────────────────────────────────────

    private var currentPin = StringBuilder()
    private var wrongAttempts = 0
    private var isLocked = false
    private var lockUntilTime = 0L

    // ──────────────────────────────────────────
    // Biometric
    // ──────────────────────────────────────────

    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    private lateinit var executor: Executor

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            _binding = ActivityLockBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupNumpad()
            setupBiometric()
            animateFingerprintIcon()

            // Determine which auth method to show based on security level
            val securityLevel = intent.getStringExtra(EXTRA_SECURITY_LEVEL) ?: "PIN"
            when (securityLevel) {
                "BIOMETRIC" -> {
                    binding.layoutPinInput?.visibility = View.GONE
                    showBiometricPrompt()
                }
                "PIN_AND_BIOMETRIC" -> {
                    binding.layoutPinInput?.visibility = View.VISIBLE
                    binding.btnFingerprint?.visibility = View.VISIBLE
                    showBiometricPrompt()
                }
                else -> {
                    binding.layoutPinInput?.visibility = View.VISIBLE
                    binding.btnFingerprint?.visibility = View.GONE
                }
            }

            // Biometric fallback button
            binding.btnFingerprint.setOnClickListener { showBiometricPrompt() }

        } catch (e: Exception) {
            Timber.e(e, "Error in onCreate")
        } catch (_: Throwable) {
            // Prevent crash
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    // ──────────────────────────────────────────
    // Numpad
    // ──────────────────────────────────────────

    private fun setupNumpad() {
        val numpadButtons = listOf(
            binding.btnKey0, binding.btnKey1, binding.btnKey2,
            binding.btnKey3, binding.btnKey4, binding.btnKey5,
            binding.btnKey6, binding.btnKey7, binding.btnKey8,
            binding.btnKey9
        )

        numpadButtons.forEach { button ->
            button.setOnClickListener { onDigitPressed(button.text.toString()) }
        }

        binding.btnDelete.setOnClickListener { onBackspacePressed() }
        binding.btnDone.setOnClickListener { onDonePressed() }
    }

    private fun onDigitPressed(digit: String) {
        if (isLocked) return
        if (currentPin.length >= 6) return // Max 6-digit PIN

        currentPin.append(digit)
        updatePinDots()

        // Auto-submit when 4+ digits are entered
        if (currentPin.length >= 4) {
            // Haptic feedback
            binding.root.performHapticFeedback(
                android.view.HapticFeedbackConstants.CONFIRM
            )
        }
    }

    private fun onBackspacePressed() {
        if (isLocked) return
        if (currentPin.isNotEmpty()) {
            currentPin.deleteCharAt(currentPin.length - 1)
            updatePinDots()
        }
    }

    private fun onDonePressed() {
        if (isLocked) return
        if (currentPin.length < 4) {
            shakePinDots()
            return
        }
        validatePin(currentPin.toString())
    }

    // ──────────────────────────────────────────
    // PIN validation
    // ──────────────────────────────────────────

    private fun validatePin(enteredPin: String) {
        val savedPin = getSharedPreferences("app_settings", MODE_PRIVATE)
            .getString("app_pin", null)

        if (savedPin == null) {
            // First-time setup — save the PIN
            getSharedPreferences("app_settings", MODE_PRIVATE)
                .edit()
                .putString("app_pin", enteredPin)
                .apply()
            onAuthenticated()
            return
        }

        if (enteredPin == savedPin) {
            wrongAttempts = 0
            onAuthenticated()
        } else {
            onWrongAttempt()
        }
    }

    private fun onWrongAttempt() {
        wrongAttempts++
        shakePinDots()

        binding.textWrongAttempt.text = getString(
            R.string.wrong_attempts,
            wrongAttempts,
            MAX_WRONG_ATTEMPTS
        )
        binding.textWrongAttempt?.visibility = View.VISIBLE

        if (wrongAttempts >= MAX_WRONG_ATTEMPTS) {
            lockOut()
        } else {
            // Exponential delay
            val delayMs = BASE_DELAY_MS * (1L shl (wrongAttempts - 1))
            isLocked = true
            binding.root.postDelayed({ isLocked = false }, delayMs)
        }

        currentPin.clear()
        updatePinDots()
    }

    private fun lockOut() {
        val lockDurationMs = 30_000L // 30 seconds
        isLocked = true
        lockUntilTime = System.currentTimeMillis() + lockDurationMs

        binding.tvLockMessage?.visibility = View.VISIBLE
        binding.tvLockMessage.text = getString(R.string.lockout_message)

        binding.root.postDelayed({
            isLocked = false
            binding.tvLockMessage?.visibility = View.GONE
            wrongAttempts = 0
            binding.textWrongAttempt?.visibility = View.GONE
        }, lockDurationMs)
    }

    private fun onAuthenticated() {
        binding.lottieSuccess?.visibility = View.VISIBLE
        binding.lottieSuccess.playAnimation()

        binding.root.postDelayed({
            setResult(RESULT_AUTHENTICATED)
            finish()
        }, 800)
    }

    // ──────────────────────────────────────────
    // PIN dots
    // ──────────────────────────────────────────

    private fun updatePinDots() {
        val dots = listOf(
            binding.pinDot1, binding.pinDot2, binding.pinDot3,
            binding.pinDot4, binding.pinDot5, binding.pinDot6
        )

        dots.forEachIndexed { index, dot ->
            val isFilled = index < currentPin.length
            dot.isSelected = isFilled
            dot.alpha = if (isFilled) 1f else 0.3f
        }
    }

    private fun shakePinDots() {
        val animator = ObjectAnimator.ofFloat(
            binding.pinDotsContainer,
            View.TRANSLATION_X,
            0f, 25f, -25f, 25f, -25f, 15f, -15f, 0f
        )
        animator.duration = 500
        animator.interpolator = OvershootInterpolator()
        animator.start()
    }

    // ──────────────────────────────────────────
    // Biometric
    // ──────────────────────────────────────────

    private fun setupBiometric() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onAuthenticated()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(
                        this@LockActivity,
                        R.string.biometric_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        // Show PIN fallback
                        binding.layoutPinInput?.visibility = View.VISIBLE
                    }
                }
            }
        )

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(getString(R.string.use_pin))
            .setConfirmationRequired(false)
            .build()
    }

    private fun showBiometricPrompt() {
        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            // Biometric not available — fall back to PIN
            binding.layoutPinInput?.visibility = View.VISIBLE
            binding.btnFingerprint?.visibility = View.GONE
        }
    }

    // ──────────────────────────────────────────
    // Animations
    // ──────────────────────────────────────────

    private fun animateFingerprintIcon() {
        val scaleX = ObjectAnimator.ofFloat(binding.imageFingerprint, View.SCALE_X, 1f, 1.1f, 1f)
        val scaleY = ObjectAnimator.ofFloat(binding.imageFingerprint, View.SCALE_Y, 1f, 1.1f, 1f)
        val alpha = ObjectAnimator.ofFloat(binding.imageFingerprint, View.ALPHA, 0.7f, 1f, 0.7f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 2000
            interpolator = OvershootInterpolator(1.5f)
            startDelay = 300
        }.start()
    }
}
