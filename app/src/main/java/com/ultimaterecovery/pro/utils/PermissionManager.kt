package com.ultimaterecovery.pro.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Permission handling utility for Ultimate Recovery Pro.
 *
 * Manages all runtime permission checks and requests required by the app:
 * - **Storage permissions**: READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE,
 *   MANAGE_EXTERNAL_STORAGE (Android 11+), READ_MEDIA_* (Android 13+)
 * - **Notification permission**: POST_NOTIFICATIONS (Android 13+)
 * - **SMS permission**: READ_SMS
 * - **Call log permission**: READ_CALL_LOG
 * - **Contacts permission**: READ_CONTACTS
 *
 * ## Usage
 * ```kotlin
 * val pm = PermissionManager(activity)
 *
 * if (!pm.hasStoragePermission()) {
 *     pm.requestStoragePermission(activity, REQUEST_CODE_STORAGE)
 * }
 *
 * // Android 11+ all-files access
 * if (!pm.hasManageStoragePermission()) {
 *     pm.requestManageStorage(activity)
 * }
 * ```
 *
 * ## Android Version Considerations
 * - **Android 10 (API 29)**: Scoped storage with `requestLegacyExternalStorage`
 * - **Android 11+ (API 30+)**: `MANAGE_EXTERNAL_STORAGE` required for broad access
 * - **Android 13+ (API 33+)**: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`,
 *   `READ_MEDIA_AUDIO` replace `READ_EXTERNAL_STORAGE`; `POST_NOTIFICATIONS` required
 *
 * @see hasStoragePermission
 * @see hasManageStoragePermission
 * @see hasNotificationPermission
 */
class PermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionManager"

        // ──────────────────────────────────────────
        // Request Codes
        // ──────────────────────────────────────────

        /** Request code for basic storage permissions. */
        const val REQUEST_CODE_STORAGE = 1001

        /** Request code for manage external storage (Android 11+). */
        const val REQUEST_CODE_MANAGE_STORAGE = 1002

        /** Request code for notification permission (Android 13+). */
        const val REQUEST_CODE_NOTIFICATION = 1003

        /** Request code for SMS permission. */
        const val REQUEST_CODE_SMS = 1004

        /** Request code for call log permission. */
        const val REQUEST_CODE_CALL_LOG = 1005

        /** Request code for all permissions. */
        const val REQUEST_CODE_ALL = 1006

        // ──────────────────────────────────────────
        // Permission Groups
        // ──────────────────────────────────────────

        /**
         * Storage permissions required for Android 12 and below.
         * On Android 13+, these are replaced by READ_MEDIA_* permissions.
         */
        private val STORAGE_PERMISSIONS_PRE_33 = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        /**
         * Media permissions required for Android 13+.
         * Replace READ_EXTERNAL_STORAGE with granular media types.
         */
        private val MEDIA_PERMISSIONS_33_PLUS = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )

        /** SMS permission. */
        private val SMS_PERMISSIONS = arrayOf(
            Manifest.permission.READ_SMS
        )

        /** Call log permissions. */
        private val CALL_LOG_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CALL_LOG
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Storage Permissions
    // ═══════════════════════════════════════════════════════════════

    /**
     * Checks whether the app has the necessary storage permissions.
     *
     * On Android 13+ (API 33+), checks READ_MEDIA_IMAGES, READ_MEDIA_VIDEO,
     * and READ_MEDIA_AUDIO.
     *
     * On Android 11–12 (API 30–32), checks READ_EXTERNAL_STORAGE.
     *
     * On Android 10 and below, checks both READ and WRITE external storage.
     *
     * Note: For full file access on Android 11+, also check
     * [hasManageStoragePermission].
     *
     * @return `true` if the app has the appropriate storage permissions.
     */
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: Granular media permissions
            MEDIA_PERMISSIONS_33_PLUS.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                        PackageManager.PERMISSION_GRANTED
            }
        } else {
            // Android 12 and below
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Requests storage permissions from the user.
     *
     * Uses the appropriate permission set based on Android version.
     *
     * @param activity    The activity to use for the permission request.
     * @param requestCode The request code for onActivityResult.
     */
    fun requestStoragePermission(activity: Activity, requestCode: Int = REQUEST_CODE_STORAGE) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            MEDIA_PERMISSIONS_33_PLUS
        } else {
            STORAGE_PERMISSIONS_PRE_33
        }

        ActivityCompat.requestPermissions(activity, permissions, requestCode)
    }

    // ═══════════════════════════════════════════════════════════════
    // MANAGE_EXTERNAL_STORAGE (Android 11+)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Checks whether the app has the MANAGE_EXTERNAL_STORAGE permission
     * (required on Android 11+ for broad file access).
     *
     * This permission allows the app to read and write all files in
     * shared storage, not just media files. It is required for
     * comprehensive file recovery operations.
     *
     * @return `true` if the permission is granted.
     */
    fun hasManageStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Not needed on Android 10 and below
            true
        }
    }

    /**
     * Requests the MANAGE_EXTERNAL_STORAGE permission.
     *
     * On Android 11+, this opens the system "All files access" settings
     * page where the user must manually grant the permission.
     *
     * On Android 10 and below, this is a no-op since the permission
     * is not required.
     *
     * @param activity The activity to use for launching the settings intent.
     */
    fun requestManageStorage(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE)
            } catch (e: Exception) {
                Timber.w(e, "Could not open manage storage settings")
                // Fallback to general settings
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Notification Permission (Android 13+)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Checks whether the app has notification permission.
     *
     * Required on Android 13+ (API 33+) for posting notifications.
     * On earlier versions, notifications are always allowed.
     *
     * @return `true` if notifications are permitted.
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Requests notification permission.
     *
     * @param activity    The activity to use for the permission request.
     * @param requestCode The request code for the permission result.
     */
    fun requestNotificationPermission(
        activity: Activity,
        requestCode: Int = REQUEST_CODE_NOTIFICATION
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                requestCode
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SMS Permission
    // ═══════════════════════════════════════════════════════════════

    /**
     * Checks whether the app has SMS read permission.
     *
     * @return `true` if READ_SMS is granted.
     */
    fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Requests SMS read permission.
     *
     * @param activity    The activity to use for the permission request.
     * @param requestCode The request code.
     */
    fun requestSmsPermission(activity: Activity, requestCode: Int = REQUEST_CODE_SMS) {
        ActivityCompat.requestPermissions(activity, SMS_PERMISSIONS, requestCode)
    }

    // ═══════════════════════════════════════════════════════════════
    // Call Log Permission
    // ═══════════════════════════════════════════════════════════════

    /**
     * Checks whether the app has call log read permission.
     *
     * @return `true` if READ_CALL_LOG is granted.
     */
    fun hasCallLogPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Requests call log read permission.
     *
     * @param activity    The activity to use for the permission request.
     * @param requestCode The request code.
     */
    fun requestCallLogPermission(activity: Activity, requestCode: Int = REQUEST_CODE_CALL_LOG) {
        ActivityCompat.requestPermissions(activity, CALL_LOG_PERMISSIONS, requestCode)
    }

    // ═══════════════════════════════════════════════════════════════
    // All Permissions
    // ═══════════════════════════════════════════════════════════════

    /**
     * Requests all required permissions at once.
     *
     * Collects all needed permissions based on the Android version
     * and requests them in a single batch.
     *
     * @param activity    The activity to use for the permission request.
     * @param requestCode The request code.
     */
    fun requestAllPermissions(activity: Activity, requestCode: Int = REQUEST_CODE_ALL) {
        val permissions = mutableListOf<String>()

        // Storage / media permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.addAll(MEDIA_PERMISSIONS_33_PLUS)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.addAll(STORAGE_PERMISSIONS_PRE_33)
        }

        // SMS and Call Log (always request for recovery features)
        permissions.add(Manifest.permission.READ_SMS)
        permissions.add(Manifest.permission.READ_CALL_LOG)
        permissions.add(Manifest.permission.READ_CONTACTS)

        // Filter out already-granted permissions
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, needed, requestCode)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Rationale and Settings
    // ═══════════════════════════════════════════════════════════════

    /**
     * Checks whether the app should show a permission rationale.
     *
     * Returns `true` if the user has previously denied the permission
     * and the system recommends showing an explanation before
     * requesting again.
     *
     * @param permission The permission to check.
     * @return `true` if a rationale should be shown.
     */
    fun shouldShowRationale(activity: Activity, permission: String): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    /**
     * Checks whether the user has permanently denied a permission
     * (i.e., "Don't ask again" was checked).
     *
     * A permission is considered permanently denied if:
     * 1. The permission is not granted.
     * 2. [shouldShowRationale] returns false.
     * 3. The permission has been requested before (tracked via prefs).
     *
     * @param permission The permission to check.
     * @return `true` if the permission is permanently denied.
     */
    fun isPermissionPermanentlyDenied(activity: Activity, permission: String): Boolean {
        val isGranted = ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        if (isGranted) return false

        val shouldShow = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        val hasRequestedBefore = hasRequestedPermissionBefore(permission)

        return !shouldShow && hasRequestedBefore
    }

    /**
     * Opens the app's settings page in the system Settings app.
     *
     * Use this when the user has permanently denied permissions and
     * the only way to grant them is through the system Settings.
     *
     * @param context The context to use for launching the intent.
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Permission Result Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Processes the result of a permission request.
     *
     * Call this from [Activity.onRequestPermissionsResult] to determine
     * whether all requested permissions were granted.
     *
     * @param grantResults The grant results from the system.
     * @return `true` if all permissions were granted.
     */
    fun areAllPermissionsGranted(grantResults: IntArray): Boolean {
        return grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }

    /**
     * Returns a list of denied permissions from a request result.
     *
     * @param permissions  The permissions that were requested.
     * @param grantResults The grant results.
     * @return List of permission strings that were denied.
     */
    fun getDeniedPermissions(permissions: Array<String>, grantResults: IntArray): List<String> {
        return permissions.filterIndexed { index, _ ->
            grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Permission Tracking (for permanent denial detection)
    // ═══════════════════════════════════════════════════════════════

    private val prefs by lazy {
        context.getSharedPreferences("permission_manager_prefs", Context.MODE_PRIVATE)
    }

    /**
     * Records that a permission has been requested.
     */
    fun markPermissionRequested(permission: String) {
        prefs.edit().putBoolean("requested_$permission", true).apply()
    }

    /**
     * Checks whether a permission has been requested before.
     */
    private fun hasRequestedPermissionBefore(permission: String): Boolean {
        return prefs.getBoolean("requested_$permission", false)
    }

    /**
     * Returns a summary of all permission states for UI display.
     *
     * @return Map of permission name to its granted state.
     */
    fun getPermissionStates(): Map<String, Boolean> {
        val states = mutableMapOf<String, Boolean>()

        states["Storage"] = hasStoragePermission()
        states["Manage Storage"] = hasManageStoragePermission()
        states["Notifications"] = hasNotificationPermission()
        states["SMS"] = hasSmsPermission()
        states["Call Log"] = hasCallLogPermission()

        return states
    }
}
