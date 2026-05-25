package com.ultimaterecovery.pro.engine.root

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root Access Manager - Manages root access state and operations.
 *
 * Provides a unified API for checking root availability, requesting
 * root access, executing root commands, and monitoring root state.
 * Falls back gracefully when root is unavailable.
 */
@Singleton
class RootManager @Inject constructor(
    private val context: Context
) {

    companion object {
        private val SU_BINARY_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/vendor/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
            "/su/bin/su", "/magisk/.core/bin/su", "/debugfs/su"
        )

        private val SUPERUSER_PACKAGES = listOf(
            "com.noshufou.android.su", "eu.chainfire.supersu",
            "com.koushikdutta.superuser", "com.thirdparty.superuser",
            "com.yellowes.su", "io.github.vvb2060.magisk"
        )

        private const val TAG = "RootManager"
    }

    private val _rootState = MutableStateFlow<RootState>(RootState.Unknown)
    val rootState: StateFlow<RootState> = _rootState.asStateFlow()

    val isRootGranted: Boolean
        get() = _rootState.value is RootState.Granted

    private val _rootInfo = MutableStateFlow<RootInfo?>(null)
    val rootInfo: StateFlow<RootInfo?> = _rootInfo.asStateFlow()

    private var shell: Any? = null

    // ──────────────────────────────────────────────
    // Root Availability Check
    // ──────────────────────────────────────────────

    suspend fun isRootAvailable(): RootCheckResult = withContext(Dispatchers.IO) {
        val checks = mutableMapOf<String, Boolean>()
        var anyPass = false

        val suBinaryFound = checkSuBinary()
        checks["su_binary"] = suBinaryFound
        if (suBinaryFound) anyPass = true

        val superuserAppFound = checkSuperuserApp()
        checks["superuser_app"] = superuserAppFound
        if (superuserAppFound) anyPass = true

        val rootType = when {
            anyPass -> RootType.UNKNOWN
            else -> RootType.NONE
        }

        val result = RootCheckResult(
            isRooted = anyPass,
            checks = checks,
            rootType = rootType,
            suPath = findSuPath()
        )

        if (anyPass) {
            _rootInfo.value = RootInfo(rootType = rootType, suPath = result.suPath)
            if (_rootState.value !is RootState.Granted) {
                _rootState.value = RootState.Available(rootType)
            }
        } else {
            _rootState.value = RootState.NotAvailable
        }
        result
    }

    suspend fun requestRootAccess(): Boolean = withContext(Dispatchers.IO) {
        // Root access requires libsu which is not available in this build
        _rootState.value = RootState.Denied
        false
    }

    // ──────────────────────────────────────────────
    // Command Execution
    // ──────────────────────────────────────────────

    suspend fun executeCommand(cmd: String): Boolean = withContext(Dispatchers.IO) {
        if (!isRootGranted && _rootState.value !is RootState.Available) {
            return@withContext false
        }
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    suspend fun executeCommandWithOutput(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            CommandResult(stdout = stdout, stderr = stderr, exitCode = exitCode, success = exitCode == 0)
        } catch (e: Exception) {
            CommandResult(stdout = "", stderr = e.message ?: "Unknown error", exitCode = -1, success = false)
        }
    }

    fun executeCommandStreaming(cmd: String): Flow<String> = flow {
        try {
            val result = executeCommandWithOutput(cmd)
            for (line in result.stdout.lines()) {
                if (line.isNotEmpty()) emit(line)
            }
        } catch (e: Exception) {
            emit("ERROR: ${e.message}")
        }
    }.flowOn(Dispatchers.IO)

    suspend fun executeCommands(commands: List<String>): List<CommandResult> =
        withContext(Dispatchers.IO) {
            commands.map { cmd -> executeCommandWithOutput(cmd) }
        }

    // ──────────────────────────────────────────────
    // Root Access Management
    // ──────────────────────────────────────────────

    fun revokeRoot() {
        _rootState.value = RootState.Revoked
    }

    // ──────────────────────────────────────────────
    // File Operations via Root
    // ──────────────────────────────────────────────

    fun openFileAsRoot(path: String): InputStream? {
        return try {
            val file = File(path)
            if (file.exists()) file.inputStream() else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun listFilesAsRoot(path: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(path)
            if (dir.isDirectory) {
                dir.listFiles()?.map { it.name } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun readFileAsRoot(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val result = executeCommandWithOutput("cat '$path'")
            if (result.success) result.stdout else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun copyFileAsRoot(srcPath: String, destPath: String): Boolean =
        withContext(Dispatchers.IO) {
            executeCommand("cp '$srcPath' '$destPath'")
        }

    suspend fun fileExistsAsRoot(path: String): Boolean = withContext(Dispatchers.IO) {
        executeCommand("test -e '$path' && echo EXISTS").let { false }
            .also { File(path).exists() }
    }

    suspend fun getFilePermissions(path: String): String? = withContext(Dispatchers.IO) {
        val result = executeCommandWithOutput("stat -c '%A' '$path'")
        if (result.success && result.stdout.isNotEmpty()) result.stdout.trim() else null
    }

    // ──────────────────────────────────────────────
    // Private Detection Methods
    // ──────────────────────────────────────────────

    private fun checkSuBinary(): Boolean {
        for (path in SU_BINARY_PATHS) {
            if (File(path).exists()) return true
        }
        return false
    }

    private fun findSuPath(): String? {
        for (path in SU_BINARY_PATHS) {
            if (File(path).exists()) return path
        }
        return null
    }

    private fun checkSuperuserApp(): Boolean {
        val pm = context.packageManager
        for (pkg in SUPERUSER_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        return false
    }

    private fun detectRootType(): RootType {
        return RootType.UNKNOWN
    }

    private fun getMagiskVersion(): String? = null

    private fun getSuperSuVersion(): String? {
        return try {
            val pm = context.packageManager
            val pkgInfo = pm.getPackageInfo("eu.chainfire.supersu", 0)
            pkgInfo.versionName
        } catch (_: Exception) {
            null
        }
    }
}

// ──────────────────────────────────────────────
// Data Classes and Enums
// ──────────────────────────────────────────────

sealed class RootState {
    data object Unknown : RootState()
    data object NotAvailable : RootState()
    data class Available(val rootType: RootType) : RootState()
    data class Granted(val rootType: RootType) : RootState()
    data object Denied : RootState()
    data object Revoked : RootState()
}

enum class RootType {
    NONE,
    MAGISK,
    SUPERSU,
    UNKNOWN
}

data class RootCheckResult(
    val isRooted: Boolean,
    val checks: Map<String, Boolean>,
    val rootType: RootType,
    val suPath: String? = null,
    val magiskVersion: String? = null
) {
    val positiveCheckCount: Int get() = checks.values.count { it }
    val totalCheckCount: Int get() = checks.size
    val confidence: Float get() = if (totalCheckCount > 0) {
        positiveCheckCount.toFloat() / totalCheckCount
    } else 0f
}

data class RootInfo(
    val rootType: RootType,
    val suPath: String?,
    val magiskVersion: String? = null,
    val superSuVersion: String? = null
)

data class CommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val success: Boolean
) {
    val fullOutput: String get() = buildString {
        if (stdout.isNotEmpty()) append(stdout)
        if (stderr.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append(stderr)
        }
    }
    val outputLines: List<String> get() = stdout.lines().filter { it.isNotEmpty() }
}
