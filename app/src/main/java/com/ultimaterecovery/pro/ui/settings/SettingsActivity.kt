package com.ultimaterecovery.pro.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.ui.fragments.settings.SettingsFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_container)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
}
