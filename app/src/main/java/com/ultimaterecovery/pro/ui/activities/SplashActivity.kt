package com.ultimaterecovery.pro.ui.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ultimaterecovery.pro.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Splash screen activity - تم تحسينها للعمل على جميع الأجهزة بما في ذلك itel P55
 * 
 * تستخدم Android 12 Splash Screen API مع fallback للأجهزة القديمة
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_DURATION_MS = 1500L
    }

    private val splashLatch = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        // تثبيت Splash Screen قبل super.onCreate للأندرويد 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                installSplashScreen()
            } catch (_: Exception) {}
        }

        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_splash)
        } catch (_: Exception) {
            // إذا فشل تحميل الـ layout، ننتقل مباشرة للـ MainActivity
            navigateToMain()
            return
        }

        // تعطيل زر الرجوع
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // لا تفعل شيء - زر الرجوع معطل
            }
        })

        // إعداد ألوان شريط الحالة والتنقل
        try {
            window.statusBarColor = getColor(R.color.primary)
            window.navigationBarColor = getColor(R.color.primary)
            
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
        } catch (_: Exception) {}

        // الانتقال لـ MainActivity بعد فترة
        Thread {
            try {
                splashLatch.await(SPLASH_DURATION_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {}
            
            runOnUiThread {
                navigateToMain()
            }
        }.start()
    }

    private fun navigateToMain() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } catch (_: Exception) {
            // إذا فشل فتح MainActivity، نغلق التطبيق بأمان
            try {
                finishAffinity()
            } catch (_: Exception) {}
        }
        
        try {
            finish()
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        splashLatch.countDown()
    }
}
