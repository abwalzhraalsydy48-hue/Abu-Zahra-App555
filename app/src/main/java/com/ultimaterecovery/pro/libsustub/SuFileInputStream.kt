
package com.ultimaterecovery.pro.libsustub

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Stub replacement for a root-accessible FileInputStream wrapper.
 *
 * Provides safe fallbacks when the real libsu SuFileInputStream
 * cannot be loaded. Falls back to regular FileInputStream which
 * may throw FileNotFoundException if root access is needed.
 *
 * On devices where libsu is available, this would open the file
 * through the su binary for reading protected files. This stub
 * ensures the app doesn't crash when libsu's native libraries
 * are missing.
 */
class SuFileInputStream(file: SuFile) : FileInputStream(file) {

    companion object {
        private const val TAG = "libsustub.SuFileInputStream"

        /**
         * Safely creates a SuFileInputStream.
         * Returns null if the file cannot be opened (e.g., requires root).
         */
        fun safeCreate(file: SuFile): SuFileInputStream? {
            return try {
                SuFileInputStream(file)
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "File not found (may require root): ${file.path}")
                null
            } catch (e: IOException) {
                Log.w(TAG, "IO error opening file: ${file.path}: ${e.message}")
                null
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to create SuFileInputStream for ${file.path}: ${e.message}")
                null
            }
        }
    }
}
