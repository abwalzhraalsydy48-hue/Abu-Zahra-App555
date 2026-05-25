package com.ultimaterecovery.pro.ui.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import com.ultimaterecovery.pro.R

/**
 * Splash screen activity shown on app launch.
 *
 * Displays the app branding for a short duration, then
 * launches [MainActivity]. This is the LAUNCHER activity
 * declared in AndroidManifest.xml with the splash theme.
 *
 * The splash theme (@style/Theme.UltimateRecoveryPro.Splash)
 * sets the window background to the primary color, providing
 * an instant brand-colored screen while the app initializes.
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_DURATION_MS = 1500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Make status and navigation bars transparent for a clean look
        window.statusBarColor = getColor(R.color.primary)
        window.navigationBarColor = getColor(R.color.primary)

        // Hide system bars for immersive splash
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }

        // Navigate to MainActivity after splash duration
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    // Clear the splash from the back stack
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback: if MainActivity can't be started, finish splash
                finish()
            }
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, SPLASH_DURATION_MS)
    }

    override fun onPause() {
        super.onPause()
        // Prevent re-entry while transitioning
    }

    override fun onBackPressed() {
        // Disable back press on splash screen
    }
}
