
package com.ultimaterecovery.pro.libsustub

import android.util.Log
import java.io.File

/**
 * Stub replacement for a root-accessible File wrapper.
 *
 * Provides safe fallbacks when the real libsu SuFile cannot be
 * loaded. All root-specific methods delegate to regular File
 * operations, which may fail silently if root access is needed.
 *
 * On devices where libsu is available, root operations would
 * normally go through the su binary. This stub ensures the app
 * doesn't crash even when libsu's native libraries are missing.
 */
class SuFile(path: String) : File(path) {

    companion object {
        private const val TAG = "libsustub.SuFile"
    }

    /**
     * Checks if the file exists using root access.
     * Falls back to regular exists() when libsu is unavailable.
     */
    fun existsAsRoot(): Boolean {
        return try {
            exists()
        } catch (e: Throwable) {
            Log.w(TAG, "existsAsRoot() failed for $path: ${e.message}")
            false
        }
    }

    /**
     * Checks if the file is readable using root access.
     * Falls back to regular canRead() when libsu is unavailable.
     */
    fun canReadAsRoot(): Boolean {
        return try {
            canRead()
        } catch (e: Throwable) {
            Log.w(TAG, "canReadAsRoot() failed for $path: ${e.message}")
            false
        }
    }

    /**
     * Lists files in this directory using root access.
     * Falls back to regular list() when libsu is unavailable.
     */
    fun listAsRoot(): Array<String>? {
        return try {
            list()
        } catch (e: Throwable) {
            Log.w(TAG, "listAsRoot() failed for $path: ${e.message}")
            null
        }
    }

    /**
     * Deletes the file using root access.
     * Returns false when libsu is unavailable.
     */
    fun deleteAsRoot(): Boolean {
        return try {
            delete()
        } catch (e: Throwable) {
            Log.w(TAG, "deleteAsRoot() failed for $path: ${e.message}")
            false
        }
    }
}
