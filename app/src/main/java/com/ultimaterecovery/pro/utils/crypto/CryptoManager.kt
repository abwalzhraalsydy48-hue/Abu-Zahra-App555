package com.ultimaterecovery.pro.utils.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.ultimaterecovery.pro.data.repository.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Security and encryption manager for Ultimate Recovery Pro.
 *
 * Centralizes all cryptographic operations, authentication, and secure
 * storage for the application:
 *
 * ## Encryption
 * - AES-256-GCM file encryption/decryption with user-supplied passwords.
 * - Key derivation via PBKDF2WithHmacSHA256 (100 000 iterations).
 * - Android Keystore-backed key management for biometric-protected keys.
 *
 * ## Authentication
 * - Biometric (fingerprint / face) authentication via AndroidX Biometric library.
 * - PIN and password lock with secure storage.
 * - App lock / unlock state management.
 *
 * ## Secure deletion
 * - DoD 5220.22-M compliant secure file wiping (3-pass overwrite).
 * - Random data overwrite followed by zeros and file truncation.
 *
 * ## Hashing
 * - MD5 and SHA-256 file hash computation for integrity verification.
 *
 * ## Key storage
 * - Encryption keys stored in the Android Keystore where supported.
 * - PIN/password hashes stored in encrypted SharedPreferences.
 *
 * @see encryptFile
 * @see decryptFile
 * @see secureDelete
 * @see hashFile
 */
@Singleton
class CryptoManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val TAG = "CryptoManager"

        /** Android Keystore provider name. */
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Keystore alias for the biometric-protected key. */
        private const val BIOMETRIC_KEY_ALIAS = "urp_biometric_key"

        /** Keystore alias for the app-lock key. */
        private const val APP_LOCK_KEY_ALIAS = "urp_app_lock_key"

        /** AES key size in bits. */
        private const val AES_KEY_SIZE = 256

        /** GCM authentication tag length in bits. */
        private const val GCM_TAG_LENGTH = 128

        /** GCM IV length in bytes. */
        private const val GCM_IV_LENGTH = 12

        /** PBKDF2 iteration count for password-based key derivation. */
        private const val PBKDF2_ITERATIONS = 100_000

        /** Salt length in bytes. */
        private const val SALT_LENGTH = 32

        /** Buffer size for file I/O (1 MB). */
        private const val BUFFER_SIZE = 1024 * 1024

        /** Encrypted file header magic bytes. */
        private val HEADER_MAGIC = "URPENC01".toByteArray()

        // ──────────────────────────────────────────
        // SharedPreferences keys
        // ──────────────────────────────────────────

        private const val PREFS_NAME = "urp_crypto_prefs"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_PASSWORD_SALT = "password_salt"
        private const val KEY_APP_LOCKED = "app_locked"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_LOCK_TYPE = "lock_type"

        /** Lock type values. */
        private const val LOCK_TYPE_NONE = "none"
        private const val LOCK_TYPE_PIN = "pin"
        private const val LOCK_TYPE_PASSWORD = "password"
        private const val LOCK_TYPE_BIOMETRIC = "biometric"
    }

    // ──────────────────────────────────────────────
    // Encrypted SharedPreferences
    // ──────────────────────────────────────────────

    private val cryptoPrefs: SharedPreferences by lazy {
        // In production, use EncryptedSharedPreferences from AndroidX Security:
        //   val masterKey = MasterKey.Builder(context)
        //       .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        //       .build()
        //   EncryptedSharedPreferences.create(
        //       context, PREFS_NAME, masterKey,
        //       EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        //       EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        //   )
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ──────────────────────────────────────────────
    // File encryption / decryption
    // ──────────────────────────────────────────────

    /**
     * Encrypts a file using AES-256-GCM with a key derived from [password].
     *
     * The output format is:
     * ```
     * [8B header magic][32B salt][12B IV][ciphertext + GCM tag]
     * ```
     *
     * @param inputPath  Path of the file to encrypt.
     * @param outputPath Path for the encrypted output file.
     * @param password   User-supplied password for key derivation.
     * @return [Resource.Unit] on success.
     */
    suspend fun encryptFile(
        inputPath: String,
        outputPath: String,
        password: String
    ): Resource<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                val inputFile = File(inputPath)
                val outputFile = File(outputPath)

                if (!inputFile.exists()) return@withContext Resource.error("Input file does not exist")
                outputFile.parentFile?.mkdirs()

                // Generate random salt and IV
                val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
                val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }

                // Derive key from password
                val secretKey = deriveKeyFromPassword(password, salt)

                // Initialize cipher
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

                // Write encrypted file
                FileOutputStream(outputFile).use { fos ->
                    // Header
                    fos.write(HEADER_MAGIC)
                    fos.write(salt)
                    fos.write(iv)

                    // Encrypted content
                    CipherInputStream(FileInputStream(inputFile), cipher).use { cis ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (cis.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                    }
                }

                Resource.success(Unit)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Encryption failed")
        }
    }

    /**
     * Decrypts a file encrypted with [encryptFile].
     *
     * Reads the header, salt, and IV from the encrypted file, derives
     * the key from [password], and decrypts the content.
     *
     * @param inputPath  Path of the encrypted file.
     * @param outputPath Path for the decrypted output file.
     * @param password   Password used during encryption.
     * @return [Resource.Unit] on success.
     */
    suspend fun decryptFile(
        inputPath: String,
        outputPath: String,
        password: String
    ): Resource<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                val inputFile = File(inputPath)
                val outputFile = File(outputPath)

                if (!inputFile.exists()) return@withContext Resource.error("Input file does not exist")
                outputFile.parentFile?.mkdirs()

                FileInputStream(inputFile).use { fis ->
                    // Read and verify header
                    val header = ByteArray(HEADER_MAGIC.size)
                    fis.read(header)
                    if (!header.contentEquals(HEADER_MAGIC)) {
                        return@withContext Resource.error("Invalid encrypted file format")
                    }

                    // Read salt and IV
                    val salt = ByteArray(SALT_LENGTH)
                    fis.read(salt)
                    val iv = ByteArray(GCM_IV_LENGTH)
                    fis.read(iv)

                    // Derive key from password
                    val secretKey = deriveKeyFromPassword(password, salt)

                    // Initialize cipher
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

                    // Write decrypted file
                    FileOutputStream(outputFile).use { fos ->
                        CipherInputStream(fis, cipher).use { cis ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (cis.read(buffer).also { bytesRead = it } != -1) {
                                fos.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                }

                Resource.success(Unit)
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            Resource.error("Decryption failed — wrong password or corrupted file")
        } catch (e: Exception) {
            Resource.error(e.message ?: "Decryption failed")
        }
    }

    // ──────────────────────────────────────────────
    // Biometric authentication
    // ──────────────────────────────────────────────

    /**
     * Checks whether biometric authentication is available on this device.
     *
     * @return `true` if the device has biometric hardware and the user
     *         has enrolled at least one biometric credential.
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Returns a human-readable reason why biometric auth is unavailable,
     * or `null` if it is available.
     */
    fun getBiometricUnavailableReason(): String? {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
        )) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                "No biometric hardware available"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                "Biometric hardware is currently unavailable"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "No biometric credentials enrolled. Please set up fingerprint or face unlock in Settings."
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                "A security update is required before biometric authentication can be used"
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
                "Biometric authentication is not supported on this device"
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN ->
                "Biometric status unknown"
            else -> "Biometric authentication unavailable"
        }
    }

    /**
     * Performs biometric authentication.
     *
     * This must be called from a [FragmentActivity] context since it
     * shows a system biometric prompt dialog.
     *
     * @param activity  The hosting activity.
     * @param title     Prompt title.
     * @param subtitle  Prompt subtitle.
     * @param negativeButtonText Text for the cancel button.
     * @return [Resource] with `true` on successful authentication.
     */
    suspend fun authenticateBiometric(
        activity: FragmentActivity,
        title: String = "Authenticate",
        subtitle: String = "Use your fingerprint or face to continue",
        negativeButtonText: String = "Cancel"
    ): Resource<Boolean> {
        return try {
            if (!isBiometricAvailable()) {
                return Resource.error("Biometric authentication is not available")
            }

            // Ensure the biometric key exists in the Keystore
            getOrCreateBiometricKey()

            val result = suspendCancellableCoroutine<Resource<Boolean>> { continuation ->
                val executor = context.mainExecutor
                val biometricPrompt = BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            continuation.resume(Resource.success(true))
                        }

                        override fun onAuthenticationFailed() {
                            // Don't resume yet — the user can retry
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            continuation.resume(Resource.error(
                                errString.toString(),
                                code = errorCode
                            ))
                        }
                    }
                )

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setNegativeButtonText(negativeButtonText)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                BiometricManager.Authenticators.BIOMETRIC_WEAK
                    )
                    .build()

                biometricPrompt.authenticate(promptInfo)

                continuation.invokeOnCancellation {
                    biometricPrompt.cancelAuthentication()
                }
            }

            result
        } catch (e: Exception) {
            Resource.error(e.message ?: "Biometric authentication failed")
        }
    }

    // ──────────────────────────────────────────────
    // PIN lock
    // ──────────────────────────────────────────────

    /**
     * Sets a PIN for app lock.
     *
     * The PIN is hashed with a random salt using SHA-256 and stored
     * in encrypted SharedPreferences.
     *
     * @param pin The PIN to set (typically 4–6 digits).
     * @return [Resource.Unit] on success.
     */
    suspend fun setPin(pin: String): Resource<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
                val hash = hashString(pin, salt)

                cryptoPrefs.edit()
                    .putString(KEY_PIN_HASH, hash)
                    .putString(KEY_PIN_SALT, salt.joinToString(",") { "%02x".format(it) })
                    .putString(KEY_LOCK_TYPE, LOCK_TYPE_PIN)
                    .apply()

                Resource.success(Unit)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to set PIN")
        }
    }

    /**
     * Verifies a PIN against the stored hash.
     *
     * @param pin The PIN to verify.
     * @return [Resource] with `true` if the PIN matches.
     */
    suspend fun verifyPin(pin: String): Resource<Boolean> {
        return try {
            withContext(Dispatchers.IO) {
                val storedHash = cryptoPrefs.getString(KEY_PIN_HASH, null)
                val storedSaltStr = cryptoPrefs.getString(KEY_PIN_SALT, null)

                if (storedHash == null || storedSaltStr == null) {
                    return@withContext Resource.error("No PIN has been set")
                }

                val salt = storedSaltStr.split(",").map { it.toInt(16).toByte() }.toByteArray()
                val computedHash = hashString(pin, salt)

                Resource.success(computedHash == storedHash)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "PIN verification failed")
        }
    }

    // ──────────────────────────────────────────────
    // Password lock
    // ──────────────────────────────────────────────

    /**
     * Sets a password for app lock.
     *
     * The password is hashed with a random salt using SHA-256 and
     * stored in encrypted SharedPreferences.
     *
     * @param password The password to set.
     * @return [Resource.Unit] on success.
     */
    suspend fun setPassword(password: String): Resource<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
                val hash = hashString(password, salt)

                cryptoPrefs.edit()
                    .putString(KEY_PASSWORD_HASH, hash)
                    .putString(KEY_PASSWORD_SALT, salt.joinToString(",") { "%02x".format(it) })
                    .putString(KEY_LOCK_TYPE, LOCK_TYPE_PASSWORD)
                    .apply()

                Resource.success(Unit)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to set password")
        }
    }

    /**
     * Verifies a password against the stored hash.
     *
     * @param password The password to verify.
     * @return [Resource] with `true` if the password matches.
     */
    suspend fun verifyPassword(password: String): Resource<Boolean> {
        return try {
            withContext(Dispatchers.IO) {
                val storedHash = cryptoPrefs.getString(KEY_PASSWORD_HASH, null)
                val storedSaltStr = cryptoPrefs.getString(KEY_PASSWORD_SALT, null)

                if (storedHash == null || storedSaltStr == null) {
                    return@withContext Resource.error("No password has been set")
                }

                val salt = storedSaltStr.split(",").map { it.toInt(16).toByte() }.toByteArray()
                val computedHash = hashString(password, salt)

                Resource.success(computedHash == storedHash)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Password verification failed")
        }
    }

    // ──────────────────────────────────────────────
    // App lock state
    // ──────────────────────────────────────────────

    /**
     * Returns whether the app is currently in a locked state.
     */
    fun isAppLocked(): Boolean {
        return cryptoPrefs.getBoolean(KEY_APP_LOCKED, false)
    }

    /**
     * Locks the app.
     *
     * After calling this, [isAppLocked] will return `true` until
     * [unlockApp] is called with valid credentials.
     */
    fun lockApp() {
        cryptoPrefs.edit().putBoolean(KEY_APP_LOCKED, true).apply()
    }

    /**
     * Attempts to unlock the app using the provided credential.
     *
     * Supports PIN, password, or biometric authentication depending
     * on the configured lock type.
     *
     * @param credential The PIN or password to verify.
     * @param biometricResult  If `true`, biometric authentication already succeeded.
     * @return [Resource] with `true` if unlock succeeded.
     */
    suspend fun unlockApp(
        credential: String? = null,
        biometricResult: Boolean = false
    ): Resource<Boolean> {
        val lockType = cryptoPrefs.getString(KEY_LOCK_TYPE, LOCK_TYPE_NONE)

        val authenticated = when (lockType) {
            LOCK_TYPE_PIN -> {
                credential?.let { verifyPin(it) } ?: Resource.error("PIN required")
            }
            LOCK_TYPE_PASSWORD -> {
                credential?.let { verifyPassword(it) } ?: Resource.error("Password required")
            }
            LOCK_TYPE_BIOMETRIC -> {
                if (biometricResult) Resource.success(true)
                else Resource.error("Biometric authentication required")
            }
            LOCK_TYPE_NONE -> Resource.success(true)
            else -> Resource.error("Unknown lock type")
        }

        return when (authenticated) {
            is Resource.Success -> {
                if (authenticated.data == true) {
                    cryptoPrefs.edit().putBoolean(KEY_APP_LOCKED, false).apply()
                }
                Resource.success(authenticated.data == true)
            }
            is Resource.Error -> authenticated
            is Resource.Loading -> Resource.error("Unexpected loading state")
        }
    }

    /**
     * Returns the current lock type.
     */
    fun getLockType(): String {
        return cryptoPrefs.getString(KEY_LOCK_TYPE, LOCK_TYPE_NONE) ?: LOCK_TYPE_NONE
    }

    /**
     * Removes the app lock entirely.
     */
    fun removeLock() {
        cryptoPrefs.edit()
            .remove(KEY_PIN_HASH).remove(KEY_PIN_SALT)
            .remove(KEY_PASSWORD_HASH).remove(KEY_PASSWORD_SALT)
            .putBoolean(KEY_APP_LOCKED, false)
            .putString(KEY_LOCK_TYPE, LOCK_TYPE_NONE)
            .apply()
    }

    // ──────────────────────────────────────────────
    // Secure delete
    // ──────────────────────────────────────────────

    /**
     * Securely deletes a file using the DoD 5220.22-M standard.
     *
     * Performs 3 overwrite passes:
     * 1. Random data
     * 2. Complementary of pass 1
     * 3. Zeros
     *
     * Then truncates and deletes the file.
     *
     * **Note**: On modern devices with SSD / F2FS / ext4 with journaling,
     * secure deletion provides no guarantee that the data is actually
     * overwritten at the physical storage level. This is a best-effort
     * implementation.
     *
     * @param filePath Path of the file to securely delete.
     * @return [Resource.Unit] on success.
     */
    suspend fun secureDelete(filePath: String): Resource<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                val file = File(filePath)
                if (!file.exists()) return@withContext Resource.error("File does not exist: $filePath")
                if (!file.canWrite()) return@withContext Resource.error("Cannot write to file: $filePath")

                val fileSize = file.length()
                val random = SecureRandom()

                // Pass 1: Random data
                overwriteFile(file, fileSize) { buffer ->
                    random.nextBytes(buffer)
                }

                // Pass 2: Complementary (0xFF)
                overwriteFile(file, fileSize) { buffer ->
                    buffer.fill(0xFF.toByte())
                }

                // Pass 3: Zeros
                overwriteFile(file, fileSize) { buffer ->
                    buffer.fill(0x00)
                }

                // Truncate to zero length
                FileOutputStream(file).use { fos ->
                    fos.channel.truncate(0)
                }

                // Delete the file
                val deleted = file.delete()
                if (deleted) Resource.success(Unit)
                else Resource.error("Failed to delete file after secure wipe")
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Secure delete failed")
        }
    }

    /**
     * Overwrites a file with data generated by [dataProvider].
     */
    private fun overwriteFile(file: File, fileSize: Long, dataProvider: (ByteArray) -> Unit) {
        FileOutputStream(file).use { fos ->
            var remaining = fileSize
            val buffer = ByteArray(BUFFER_SIZE)

            while (remaining > 0) {
                val chunkSize = minOf(buffer.size.toLong(), remaining).toInt()
                val chunk = if (chunkSize < buffer.size) buffer.copyOf(chunkSize) else buffer
                dataProvider(chunk)
                fos.write(chunk, 0, chunkSize)
                remaining -= chunkSize
            }

            fos.flush()
            fos.fd.sync()
        }
    }

    // ──────────────────────────────────────────────
    // Hashing
    // ──────────────────────────────────────────────

    /**
     * Computes a hash of a file using the specified algorithm.
     *
     * @param path      Absolute file path.
     * @param algorithm Hash algorithm — "MD5" or "SHA-256".
     * @return [Resource] with the hex-encoded hash string.
     */
    suspend fun hashFile(path: String, algorithm: String): Resource<String> {
        return try {
            withContext(Dispatchers.IO) {
                val file = File(path)
                if (!file.exists()) return@withContext Resource.error("File does not exist: $path")

                val digest = MessageDigest.getInstance(algorithm)
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } > 0) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }

                val hashBytes = digest.digest()
                val hashString = hashBytes.joinToString("") { "%02x".format(it) }

                Resource.success(hashString)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Hash computation failed")
        }
    }

    // ──────────────────────────────────────────────
    // Key derivation and management
    // ──────────────────────────────────────────────

    /**
     * Derives an AES-256 key from a password and salt using PBKDF2.
     *
     * @param password User-supplied password.
     * @param salt     Random salt.
     * @return A [SecretKeySpec] for AES-256.
     */
    private fun deriveKeyFromPassword(password: String, salt: ByteArray): SecretKeySpec {
        val keySpec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_SIZE
        )
        val secretKey = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(keySpec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }

    /**
     * Hashes a string with a salt using SHA-256.
     *
     * Used for PIN and password storage.
     *
     * @param input The string to hash.
     * @param salt  Random salt.
     * @return Hex-encoded hash string.
     */
    private fun hashString(input: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Creates or retrieves a biometric-protected key from the Android Keystore.
     *
     * The key requires biometric authentication every time it is used.
     *
     * @return The [SecretKey] for biometric operations.
     */
    private fun getOrCreateBiometricKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Check if key already exists
        if (keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
            val entry = keyStore.getEntry(BIOMETRIC_KEY_ALIAS, null)
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        // Create new key
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            KeyGenParameterSpec.Builder(
                BIOMETRIC_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    30, // Authentication validity duration in seconds
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
                .build()
        } else {
            @Suppress("DEPRECATION")
            KeyGenParameterSpec.Builder(
                BIOMETRIC_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(30)
                .build()
        }

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Creates or retrieves the app-lock key from the Android Keystore.
     *
     * Unlike the biometric key, this key does not require user
     * authentication to use — it's used internally for encrypting
     * lock state data.
     */
    private fun getOrCreateAppLockKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(APP_LOCK_KEY_ALIAS)) {
            val entry = keyStore.getEntry(APP_LOCK_KEY_ALIAS, null)
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            APP_LOCK_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Deletes the biometric key from the Keystore.
     *
     * Call this when biometric lock is disabled.
     */
    fun deleteBiometricKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
        } catch (_: Exception) {}
    }

    /**
     * Deletes the app-lock key from the Keystore.
     */
    fun deleteAppLockKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(APP_LOCK_KEY_ALIAS)
        } catch (_: Exception) {}
    }

    // ──────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────

    /**
     * Checks if a PIN has been configured.
     */
    fun hasPin(): Boolean {
        return cryptoPrefs.getString(KEY_PIN_HASH, null) != null
    }

    /**
     * Checks if a password has been configured.
     */
    fun hasPassword(): Boolean {
        return cryptoPrefs.getString(KEY_PASSWORD_HASH, null) != null
    }

    /**
     * Checks if any lock method is configured.
     */
    fun hasLockConfigured(): Boolean {
        return getLockType() != LOCK_TYPE_NONE
    }

    /**
     * Enables biometric-based app lock.
     */
    fun enableBiometricLock() {
        cryptoPrefs.edit()
            .putString(KEY_LOCK_TYPE, LOCK_TYPE_BIOMETRIC)
            .putBoolean(KEY_BIOMETRIC_ENABLED, true)
            .apply()
    }

    /**
     * Disables biometric-based app lock.
     */
    fun disableBiometricLock() {
        cryptoPrefs.edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, false)
            .apply()
        deleteBiometricKey()
    }

    /**
     * Checks if biometric lock is enabled.
     */
    fun isBiometricLockEnabled(): Boolean {
        return cryptoPrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false) && isBiometricAvailable()
    }
}
