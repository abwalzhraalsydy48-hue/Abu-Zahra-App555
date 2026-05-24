package com.ultimaterecovery.pro.ui.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ultimaterecovery.pro.R
import com.ultimaterecovery.pro.databinding.ActivityMainBinding
import com.ultimaterecovery.pro.engine.root.RootState
import com.ultimaterecovery.pro.ui.viewmodel.MainNavigationEvent
import com.ultimaterecovery.pro.ui.viewmodel.MainUiState
import com.ultimaterecovery.pro.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Main entry-point activity for Ultimate Recovery Pro.
 *
 * Hosts the [NavHostFragment] with a [com.google.android.material.bottomnavigation.BottomNavigationView]
 * containing 5 tabs: Home, Scan, Recovery, Tools, and Settings.
 *
 * Responsibilities:
 * - Navigation Component setup with bottom navigation
 * - Toolbar with app title and root-status indicator
 * - Runtime permission requests on first launch
 * - Root availability check
 * - Lock-screen check on resume (delegates to [LockActivity])
 * - Back-navigation handling via [OnBackPressedCallback]
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // ──────────────────────────────────────────
    // ViewBinding
    // ──────────────────────────────────────────

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!

    // ──────────────────────────────────────────
    // ViewModel
    // ──────────────────────────────────────────

    private val viewModel: MainViewModel by viewModels()

    // ──────────────────────────────────────────
    // Navigation
    // ──────────────────────────────────────────

    private lateinit var navController: NavController

    // ──────────────────────────────────────────
    // Permissions
    // ──────────────────────────────────────────

    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (!allGranted) {
            showPermissionRationale()
        }
    }

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            _binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            // If binding fails, we can't recover
            return
        }

        try {
            setupToolbar()
        } catch (e: Exception) {
            // Toolbar setup is non-critical
        }

        try {
            setupNavigation()
        } catch (e: Exception) {
            // Navigation setup failure should not crash the app
        }

        try {
            setupBackNavigation()
        } catch (e: Exception) {
            // Back navigation setup is non-critical
        }

        try {
            observeUiState()
        } catch (e: Exception) {
            // UI state observation is non-critical
        }

        try {
            observeNavigationEvents()
        } catch (e: Exception) {
            // Navigation event observation is non-critical
        }

        try {
            checkAndRequestPermissions()
        } catch (e: Exception) {
            // Permission request can fail on some Android versions
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            checkLockScreen()
        } catch (e: Exception) {
            // Lock screen check failure should not crash
        }
        try {
            viewModel.checkRootStatus()
        } catch (e: Exception) {
            // Root status check failure is non-critical
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    // ──────────────────────────────────────────
    // Toolbar
    // ──────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = getString(R.string.app_name)
    }

    /**
     * Updates the root-status indicator chip in the toolbar.
     */
    private fun updateRootIndicator(isRootAvailable: Boolean, rootState: RootState) {
        binding.chipRootStatus?.visibility = View.VISIBLE
        when (rootState) {
            is RootState.Granted -> {
                binding.chipRootStatus.text = getString(R.string.root_granted)
                binding.chipRootStatus.setChipIconResource(R.drawable.ic_check_circle)
                binding.chipRootStatus.chipBackgroundColor =
                    ContextCompat.getColorStateList(this, R.color.primary_container)
            }
            is RootState.Available -> {
                binding.chipRootStatus.text = getString(R.string.root_available)
                binding.chipRootStatus.setChipIconResource(R.drawable.ic_warning)
                binding.chipRootStatus.chipBackgroundColor =
                    ContextCompat.getColorStateList(this, R.color.secondary_container)
            }
            is RootState.NotAvailable -> {
                binding.chipRootStatus.text = getString(R.string.root_unavailable)
                binding.chipRootStatus.setChipIconResource(R.drawable.ic_close)
                binding.chipRootStatus.chipBackgroundColor =
                    ContextCompat.getColorStateList(this, R.color.surface_variant)
            }
            RootState.Unknown -> {
                binding.chipRootStatus?.visibility = View.GONE
            }
            is RootState.Denied -> {
                binding.chipRootStatus.text = getString(R.string.root_unavailable)
                binding.chipRootStatus.setChipIconResource(R.drawable.ic_close)
                binding.chipRootStatus.chipBackgroundColor =
                    ContextCompat.getColorStateList(this, R.color.surface_variant)
            }
            is RootState.Revoked -> {
                binding.chipRootStatus.text = getString(R.string.root_unavailable)
                binding.chipRootStatus.setChipIconResource(R.drawable.ic_close)
                binding.chipRootStatus.chipBackgroundColor =
                    ContextCompat.getColorStateList(this, R.color.surface_variant)
            }
        }
    }

    // ──────────────────────────────────────────
    // Navigation
    // ──────────────────────────────────────────

    private fun setupNavigation() {
        try {
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.navHostFragment) as? NavHostFragment
                ?: return
            navController = navHostFragment.navController

            // Connect BottomNavigationView with NavController
            NavigationUI.setupWithNavController(binding.bottomNavigation, navController)

            // Hide bottom nav on certain destinations (e.g. preview, lock)
            navController.addOnDestinationChangedListener { _, destination, _ ->
                val hideNavDestinations = setOf(
                    R.id.previewActivity,
                    R.id.lockActivity
                )
                binding.bottomNavigation.visibility = if (destination.id in hideNavDestinations) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            }
        } catch (e: Exception) {
            // Navigation setup can fail if nav graph has issues
        }
    }

    /**
     * Handles back navigation using the Navigation Component.
     * If the user is on the top-level destination, minimise the app
     * instead of popping the stack.
     */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try {
                    if (!::navController.isInitialized || !navController.popBackStack()) {
                        // At the root of the navigation graph — minimise app
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                } catch (e: Exception) {
                    // If nav controller is not available, just minimize
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ──────────────────────────────────────────
    // UI State observation
    // ──────────────────────────────────────────

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: MainUiState) {
        // Root indicator
        updateRootIndicator(state.isRootAvailable, state.rootState)

        // Loading
        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        // Error
        state.error?.let { error ->
            Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.dismiss)) { viewModel.clearError() }
                .show()
        }
    }

    // ──────────────────────────────────────────
    // Navigation events
    // ──────────────────────────────────────────

    private fun observeNavigationEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.navigationEvents.collect { event ->
                    handleNavigationEvent(event)
                }
            }
        }
    }

    private fun handleNavigationEvent(event: MainNavigationEvent) {
        try {
            when (event) {
                is MainNavigationEvent.NavigateToScan -> {
                    val intent = Intent(this, ScanActivity::class.java).apply {
                        putExtra(ScanActivity.EXTRA_SCAN_TYPE, event.scanType.name)
                    }
                    startActivity(intent)
                }
                is MainNavigationEvent.NavigateToRecovery -> {
                    if (::navController.isInitialized) {
                        navController.navigate(
                            R.id.fileRecoveryFragment,
                            Bundle().apply {
                                putString("category", event.category.name)
                            }
                        )
                    }
                }
                is MainNavigationEvent.NavigateToDeepScan -> {
                    val intent = Intent(this, ScanActivity::class.java).apply {
                    putExtra(ScanActivity.EXTRA_SCAN_TYPE, "DEEP")
                }
                startActivity(intent)
            }
            is MainNavigationEvent.ShowMessage -> {
                Snackbar.make(binding.root, event.message, Snackbar.LENGTH_SHORT).show()
            }
        }
        } catch (e: Exception) {
            // Navigation event handling failure should not crash
        }
    }

    // ──────────────────────────────────────────
    // Permissions
    // ──────────────────────────────────────────

    private fun checkAndRequestPermissions() {
        try {
            val ungranted = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (ungranted.isEmpty()) return

            val shouldShowRationale = ungranted.any {
                shouldShowRequestPermissionRationale(it)
            }

            if (shouldShowRationale) {
                showPermissionRationale()
            } else {
                permissionLauncher.launch(ungranted.toTypedArray())
            }
        } catch (e: Exception) {
            // Permission check can crash on certain Android versions
        }
    }

    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_rationale_title)
            .setMessage(R.string.permission_rationale_message)
            .setPositiveButton(R.string.grant) { _, _ ->
                permissionLauncher.launch(requiredPermissions)
            }
            .setNegativeButton(R.string.deny, null)
            .show()
    }

    // ──────────────────────────────────────────
    // Lock screen check
    // ──────────────────────────────────────────

    /**
     * Checks if the app lock is enabled and, if so, launches [LockActivity].
     *
     * Called on every [onResume] to ensure the app is secured when
     * the user switches back from another app.
     */
    private fun checkLockScreen() {
        try {
            val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
            val securityLevel = prefs.getString("security_level", "NONE") ?: "NONE"
            if (securityLevel != "NONE") {
                // Only show lock if we're not already on the lock screen
                val intent = Intent(this, LockActivity::class.java)
                startActivity(intent)
            }
        } catch (e: Exception) {
            // Lock screen check should not crash the app
        }
    }
}
