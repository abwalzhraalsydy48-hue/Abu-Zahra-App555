
package com.ultimaterecovery.pro.libsustub

import android.util.Log

/**
 * Stub replacement for com.topjohnwu.superuser.Shell.
 *
 * This class provides safe, no-op fallbacks when the real libsu
 * native library cannot be loaded (e.g., on itel, Huawei, or
 * some Android 13 devices where UnsatisfiedLinkError occurs).
 *
 * All methods return safe defaults and never crash:
 * - isRootGranted() → false (no root without real libsu)
 * - exec() → ShellResult with exitCode 1 (failure)
 * - execAsync() → callback with failure result
 */
data class ShellResult(
    val stdout: List<String>,
    val stderr: List<String>,
    val exitCode: Int,
    val success: Boolean = exitCode == 0
)

object Shell {

    private const val TAG = "libsustub.Shell"

    /** Whether the real libsu library is available and initialized. */
    @Volatile
    var isLibsuAvailable: Boolean = false
        private set

    /**
     * Attempts to initialize the real libsu Shell.
     * Should be called once during Application.onCreate().
     * If this fails, all Shell methods return safe defaults.
     */
    fun tryInitRealShell() {
        try {
            val shellClass = Class.forName("com.topjohnwu.superuser.Shell")
            val builderClass = Class.forName("com.topjohnwu.superuser.Shell\$Builder")
            isLibsuAvailable = true
            Log.i(TAG, "Real libsu Shell class found")
        } catch (e: Throwable) {
            isLibsuAvailable = false
            Log.i(TAG, "Real libsu Shell not available, using stub: ${e.message}")
        }
    }

    /**
     * Returns whether root access is granted.
     * Always returns false when using the stub.
     */
    fun isRootGranted(): Boolean {
        if (!isLibsuAvailable) return false
        return try {
            val shellClass = Class.forName("com.topjohnwu.superuser.Shell")
            val method = shellClass.getMethod("isRootGranted")
            method.invoke(null) as? Boolean ?: false
        } catch (e: Throwable) {
            Log.w(TAG, "isRootGranted failed: ${e.message}")
            false
        }
    }

    /**
     * Executes a shell command synchronously.
     * Returns a failure result when using the stub.
     */
    fun exec(command: String): ShellResult {
        if (!isLibsuAvailable) {
            Log.d(TAG, "exec() called but libsu not available, returning failure for: $command")
            return ShellResult(emptyList(), listOf("libsu not available on this device"), 1)
        }
        return try {
            val shellClass = Class.forName("com.topjohnwu.superuser.Shell")
            // Try using Shell.cmd() which is the modern libsu API
            val cmdMethod = shellClass.getMethod("cmd", String::class.java)
            val result = cmdMethod.invoke(null, command)
            if (result is ShellResult) result
            else ShellResult(emptyList(), emptyList(), 0)
        } catch (e: Throwable) {
            Log.w(TAG, "exec() failed: ${e.message}")
            ShellResult(emptyList(), listOf(e.message ?: "Unknown error"), 1)
        }
    }

    /**
     * Executes a shell command asynchronously.
     * Callback receives a failure result when using the stub.
     */
    fun execAsync(command: String, callback: (ShellResult) -> Unit) {
        if (!isLibsuAvailable) {
            Log.d(TAG, "execAsync() called but libsu not available, returning failure for: $command")
            callback(ShellResult(emptyList(), listOf("libsu not available on this device"), 1))
            return
        }
        try {
            val shellClass = Class.forName("com.topjohnwu.superuser.Shell")
            val cmdMethod = shellClass.getMethod("cmd", String::class.java)
            val result = cmdMethod.invoke(null, command)
            if (result is ShellResult) {
                callback(result)
            } else {
                callback(ShellResult(emptyList(), emptyList(), 0))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "execAsync() failed: ${e.message}")
            callback(ShellResult(emptyList(), listOf(e.message ?: "Unknown error"), 1))
        }
    }
}
