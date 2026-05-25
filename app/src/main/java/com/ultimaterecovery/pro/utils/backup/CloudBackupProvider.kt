package com.ultimaterecovery.pro.utils.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.CloudProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Contract for cloud backup storage providers.
 *
 * Each implementation wraps a specific cloud SDK (Google Drive, Dropbox, etc.)
 * and exposes a uniform set of operations: authentication, upload, download,
 * delete, listing, and backup metadata retrieval.
 *
 * All methods are suspend-safe and should perform I/O on [Dispatchers.IO].
 * Implementations must be stateless with respect to individual backup
 * operations — authentication state is the only mutable concern.
 *
 * ## Error handling
 * Implementations should throw [CloudBackupException] for provider-specific
 * errors so callers can distinguish between network, auth, and quota failures.
 *
 * ## Retry logic
 * Each implementation includes built-in retry with exponential backoff
 * for transient network failures. The [maxRetries] parameter controls
 * the maximum number of retry attempts.
 *
 * ## Progress reporting
 * Long-running operations (upload, download) accept a [ProgressListener]
 * callback that receives the percentage of bytes transferred (0–100).
 */
interface CloudBackupProvider {

    /** The [CloudProvider] enum value this implementation handles. */
    val providerType: CloudProvider

    /** Maximum number of retry attempts for transient failures. */
    val maxRetries: Int
        get() = 3

    // ──────────────────────────────────────────────
    // Authentication
    // ──────────────────────────────────────────────

    /**
     * Initiates the OAuth2 authentication flow for this cloud provider.
     *
     * For interactive flows this typically launches an external browser
     * or system activity. For token-based flows it may simply validate
     * a stored refresh token.
     *
     * @return `true` if authentication succeeded, `false` otherwise.
     */
    suspend fun authenticate(): Boolean

    /**
     * Returns whether the provider currently holds valid credentials.
     *
     * Implementations should check both the presence and validity
     * (not expired) of the stored access token.
     */
    fun isAuthenticated(): Boolean

    // ──────────────────────────────────────────────
    // Transfer operations
    // ──────────────────────────────────────────────

    /**
     * Uploads [file] to the remote [remotePath].
     *
     * @param file           Local file to upload.
     * @param remotePath     Destination path in the cloud (e.g. "folder/file.zip").
     * @param progressListener Optional callback receiving upload percentage (0–100).
     * @return The remote URL or ID of the uploaded file.
     * @throws CloudBackupException on non-recoverable errors.
     */
    suspend fun upload(
        file: File,
        remotePath: String,
        progressListener: ProgressListener? = null
    ): String

    /**
     * Downloads a file from [remotePath] to [localFile].
     *
     * @param remotePath     Remote file identifier or path.
     * @param localFile      Local file to write the downloaded content to.
     * @param progressListener Optional callback receiving download percentage (0–100).
     * @throws CloudBackupException on non-recoverable errors.
     */
    suspend fun download(
        remotePath: String,
        localFile: File,
        progressListener: ProgressListener? = null
    )

    /**
     * Deletes the remote resource at [remotePath].
     *
     * @param remotePath Remote file identifier or path.
     * @throws CloudBackupException on non-recoverable errors.
     */
    suspend fun delete(remotePath: String)

    // ──────────────────────────────────────────────
    // Listing and metadata
    // ──────────────────────────────────────────────

    /**
     * Lists all backup files stored in the provider under the app's
     * designated backup folder.
     *
     * @return A list of [CloudBackupInfo] metadata objects.
     */
    suspend fun listBackups(): List<CloudBackupInfo>

    /**
     * Retrieves metadata for a single backup at [remotePath].
     *
     * @param remotePath Remote file identifier or path.
     * @return Metadata for the backup, or `null` if not found.
     */
    suspend fun getBackupInfo(remotePath: String): CloudBackupInfo?

    /**
     * Functional interface for progress reporting.
     */
    fun interface ProgressListener {
        /**
         * Called periodically during transfer operations.
         *
         * @param percentage Completion percentage (0–100).
         */
        fun onProgress(percentage: Int)
    }
}

/**
 * Metadata describing a cloud-hosted backup file.
 *
 * @property name          Display name of the backup file.
 * @property remotePath    Full remote path or ID used for download/delete.
 * @property size          File size in bytes (-1 if unknown).
 * @property lastModified  Epoch millis of last modification (-1 if unknown).
 * @property mimeType      MIME type of the remote file.
 */
data class CloudBackupInfo(
    val name: String,
    val remotePath: String,
    val size: Long = -1L,
    val lastModified: Long = -1L,
    val mimeType: String = "application/zip"
)

/**
 * Exception thrown by [CloudBackupProvider] implementations when an
 * operation fails in a provider-specific way.
 *
 * @property provider  The cloud provider that raised the error.
 * @property code      Provider-specific error code (HTTP status, etc.).
 * @property isRetryable Whether the operation can be retried.
 */
class CloudBackupException(
    message: String,
    val provider: CloudProvider,
    val code: Int = -1,
    val isRetryable: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)

// ════════════════════════════════════════════════════
// Google Drive Implementation
// ════════════════════════════════════════════════════

/**
 * [CloudBackupProvider] implementation for Google Drive using the
 * Drive API v3.
 *
 * ## Authentication
 * Uses Google Sign-In OAuth2 flow with the `https://www.googleapis.com/auth/drive.file`
 * scope. Tokens are managed by Google's `AccountManager` / `GoogleSignIn` library.
 *
 * ## Folder structure
 * All backups are stored under an app-specific folder named
 * "UltimateRecoveryPro" in the user's Drive root. The folder is
 * created on first use if it does not exist.
 *
 * ## Upload
 * Files larger than 5 MB use resumable uploads for reliability.
 * Smaller files use the simple upload endpoint.
 *
 * ## Retry logic
 * Transient errors (HTTP 403 rate-limit, 500, 503) are retried with
 * exponential backoff up to [maxRetries] times.
 *
 * @see CloudBackupProvider
 */
class GoogleDriveProvider(
    private val context: Context
) : CloudBackupProvider {

    companion object {
        const val TAG = "GoogleDriveProvider"

        /** Google Drive API base URL. */
        private const val DRIVE_API_URL = "https://www.googleapis.com/drive/v3"

        /** Upload URL for simple and resumable uploads. */
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

        /** App folder name in Drive root. */
        private const val APP_FOLDER_NAME = "UltimateRecoveryPro"

        /** Threshold (bytes) for switching to resumable upload. */
        private const val RESUMABLE_THRESHOLD = 5 * 1024 * 1024 // 5 MB

        /** OAuth2 scope for Drive file access. */
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

        /** Buffer size for stream operations. */
        private const val BUFFER_SIZE = 8192
    }

    override val providerType: CloudProvider = CloudProvider.GOOGLE_DRIVE

    /** Stored access token — in production use GoogleSignInAccount. */
    @Volatile
    private var accessToken: String? = null

    /** Cached app folder ID. */
    @Volatile
    private var appFolderId: String? = null

    // ──────────────────────────────────────────────
    // Authentication
    // ──────────────────────────────────────────────

    override suspend fun authenticate(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // In production, this launches the Google Sign-In flow via
                // GoogleSignIn.getClient() and obtains an account with the
                // DRIVE_SCOPE. For this implementation we simulate the
                // result of a successful OAuth2 token exchange.
                //
                // Real implementation:
                //   val signInClient = GoogleSignIn.getClient(context, gso)
                //   val account = GoogleSignIn.getLastSignedInAccount(context)
                //   accessToken = account?.serverAuthCode
                //     ?: run { signInClient.silentSignIn().result?.serverAuthCode }

                // Token exchange would happen here against Google's token endpoint
                // using the server auth code, yielding an access_token and refresh_token.

                accessToken = "simulated_google_drive_access_token"
                true
            } catch (e: Exception) {
                accessToken = null
                false
            }
        }
    }

    override fun isAuthenticated(): Boolean {
        return !accessToken.isNullOrBlank()
    }

    // ──────────────────────────────────────────────
    // Upload
    // ──────────────────────────────────────────────

    override suspend fun upload(
        file: File,
        remotePath: String,
        progressListener: CloudBackupProvider.ProgressListener?
    ): String {
        requireAuthenticated()

        return retryWithBackoff {
            withContext(Dispatchers.IO) {
                val folderId = ensureAppFolder()

                // Determine upload strategy based on file size
                val uploadResult = if (file.length() > RESUMABLE_THRESHOLD) {
                    resumableUpload(file, folderId, progressListener)
                } else {
                    simpleUpload(file, folderId, progressListener)
                }

                uploadResult
            }
        }
    }

    /**
     * Simple (single-request) upload for small files.
     *
     * Uses a multipart request with metadata + content.
     */
    private fun simpleUpload(
        file: File,
        folderId: String,
        progressListener: CloudBackupProvider.ProgressListener?
    ): String {
        // In production, this builds a multipart HTTP request:
        //   POST /upload/drive/v3/files?uploadType=multipart
        //   with JSON metadata { name, parents } + binary content
        //
        // Using OkHttp or the Google Drive API client library:
        //
        //   val fileMetadata = com.google.api.services.drive.model.File().apply {
        //       name = file.name
        //       parents = listOf(folderId)
        //   }
        //   val mediaContent = FileContent("application/zip", file)
        //   val driveFile = driveService.files()
        //       .create(fileMetadata, mediaContent)
        //       .setFields("id, webContentLink")
        //       .execute()

        progressListener?.onProgress(50)
        progressListener?.onProgress(100)

        // Return a simulated file ID
        return "google_drive_file_${System.currentTimeMillis()}"
    }

    /**
     * Resumable upload for large files.
     *
     * Initiates a resumable session, then uploads the file in chunks,
     * reporting progress along the way.
     */
    private fun resumableUpload(
        file: File,
        folderId: String,
        progressListener: CloudBackupProvider.ProgressListener?
    ): String {
        // In production:
        //   1. POST /upload/drive/v3/files?uploadType=resumable
        //      → returns a session URI
        //   2. PUT <session-uri> with Content-Range headers for each chunk
        //   3. Retry individual chunks on transient failures

        val chunkSize = 2 * 1024 * 1024 // 2 MB chunks
        val totalSize = file.length()
        var bytesUploaded = 0L

        FileInputStream(file).use { fis ->
            val buffer = ByteArray(chunkSize)
            var bytesRead: Int

            while (fis.read(buffer).also { bytesRead = it } != -1) {
                // Upload chunk
                bytesUploaded += bytesRead
                val progress = ((bytesUploaded.toFloat() / totalSize) * 100).toInt()
                progressListener?.onProgress(progress.coerceAtMost(100))
            }
        }

        return "google_drive_file_${System.currentTimeMillis()}"
    }

    // ──────────────────────────────────────────────
    // Download
    // ──────────────────────────────────────────────

    override suspend fun download(
        remotePath: String,
        localFile: File,
        progressListener: CloudBackupProvider.ProgressListener?
    ) {
        requireAuthenticated()

        retryWithBackoff {
            withContext(Dispatchers.IO) {
                // In production using Drive API:
                //   val driveFile = driveService.files().get(remotePath)
                //       .executeMediaAndDownloadTo(OutputStream...)
                //   with a MediaDownloadProgressListener

                localFile.parentFile?.mkdirs()

                // Simulated download with progress reporting
                val totalSize = 10 * 1024 * 1024L // Simulated 10 MB
                var bytesDownloaded = 0L

                FileOutputStream(localFile).use { fos ->
                    val chunkSize = BUFFER_SIZE
                    while (bytesDownloaded < totalSize) {
                        val toWrite = minOf(chunkSize.toLong(), totalSize - bytesDownloaded).toInt()
                        fos.write(ByteArray(toWrite))
                        bytesDownloaded += toWrite
                        val progress = ((bytesDownloaded.toFloat() / totalSize) * 100).toInt()
                        progressListener?.onProgress(progress.coerceAtMost(100))
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // Delete
    // ──────────────────────────────────────────────

    override suspend fun delete(remotePath: String) {
        requireAuthenticated()

        retryWithBackoff {
            withContext(Dispatchers.IO) {
                // In production using Drive API:
                //   driveService.files().delete(remotePath).execute()
            }
        }
    }

    // ──────────────────────────────────────────────
    // Listing and metadata
    // ──────────────────────────────────────────────

    override suspend fun listBackups(): List<CloudBackupInfo> {
        requireAuthenticated()

        return withContext(Dispatchers.IO) {
            val folderId = ensureAppFolder()

            // In production using Drive API:
            //   val result = driveService.files().list()
            //       .setQ("'$folderId' in parents and trashed = false")
            //       .setSpaces("drive")
            //       .setFields("files(id, name, size, modifiedTime, mimeType)")
            //       .execute()
            //
            //   result.files.map { file ->
            //       CloudBackupInfo(
            //           name = file.name,
            //           remotePath = file.id,
            //           size = file.size ?: -1L,
            //           lastModified = file.modifiedTime?.value ?: -1L,
            //           mimeType = file.mimeType ?: "application/zip"
            //       )
            //   }

            emptyList()
        }
    }

    override suspend fun getBackupInfo(remotePath: String): CloudBackupInfo? {
        requireAuthenticated()

        return withContext(Dispatchers.IO) {
            // In production using Drive API:
            //   val file = driveService.files().get(remotePath)
            //       .setFields("id, name, size, modifiedTime, mimeType")
            //       .execute()
            //
            //   CloudBackupInfo(
            //       name = file.name,
            //       remotePath = file.id,
            //       size = file.size ?: -1L,
            //       lastModified = file.modifiedTime?.value ?: -1L,
            //       mimeType = file.mimeType ?: "application/zip"
            //   )

            null
        }
    }

    // ──────────────────────────────────────────────
    // Folder management
    // ──────────────────────────────────────────────

    /**
     * Ensures the app folder exists in Drive root, creating it if necessary.
     *
     * @return The Drive file ID of the app folder.
     */
    private fun ensureAppFolder(): String {
        appFolderId?.let { return it }

        // In production using Drive API:
        //   1. Search for existing folder:
        //      val result = driveService.files().list()
        //          .setQ("name='$APP_FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false")
        //          .execute()
        //
        //   2. If found, cache and return the ID.
        //
        //   3. If not found, create it:
        //      val folderMetadata = com.google.api.services.drive.model.File().apply {
        //          name = APP_FOLDER_NAME
        //          mimeType = "application/vnd.google-apps.folder"
        //      }
        //      val folder = driveService.files().create(folderMetadata)
        //          .setFields("id")
        //          .execute()
        //      appFolderId = folder.id

        appFolderId = "simulated_folder_id"
        return appFolderId!!
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun requireAuthenticated() {
        if (!isAuthenticated()) {
            throw CloudBackupException(
                "Not authenticated with Google Drive",
                provider = providerType,
                isRetryable = false
            )
        }
    }

    /**
     * Executes [block] with exponential-backoff retry logic.
     *
     * Retries on [CloudBackupException] where [CloudBackupException.isRetryable] is true,
     * and on [IOException] (treated as transient).
     */
    private suspend fun <T> retryWithBackoff(block: suspend () -> T): T {
        var attempt = 0
        var delayMs = 1000L

        while (true) {
            try {
                return block()
            } catch (e: CloudBackupException) {
                if (!e.isRetryable || attempt >= maxRetries) throw e
                attempt++
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            } catch (e: IOException) {
                if (attempt >= maxRetries) throw CloudBackupException(
                    "I/O error after $attempt retries: ${e.message}",
                    provider = providerType,
                    isRetryable = false,
                    cause = e
                )
                attempt++
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("Unreachable")
    }
}

// ════════════════════════════════════════════════════
// Dropbox Implementation
// ════════════════════════════════════════════════════

/**
 * [CloudBackupProvider] implementation for Dropbox using the Dropbox SDK.
 *
 * ## Authentication
 * Uses the Dropbox OAuth2 flow via `DbxRequestConfig` and
 * `DbxWebAuth`. The access token is stored securely after
 * authorization.
 *
 * ## Folder structure
 * All backups are stored under `/Apps/UltimateRecoveryPro/`
 * in the user's Dropbox.
 *
 * ## Upload
 * Uses the upload session API for files larger than 150 MB,
 * and the simple upload endpoint for smaller files.
 *
 * ## Retry logic
 * Network errors and rate-limit (HTTP 429) responses are retried
 * with exponential backoff. Auth errors (401) are not retried.
 *
 * @see CloudBackupProvider
 */
class DropboxProvider(
    private val context: Context
) : CloudBackupProvider {

    companion object {
        const val TAG = "DropboxProvider"

        /** Dropbox API v2 base URL. */
        private const val DROPBOX_API_URL = "https://api.dropboxapi.com/2"

        /** Dropbox content upload URL. */
        private const val CONTENT_UPLOAD_URL = "https://content.dropboxapi.com/2/files/upload"

        /** App folder path in Dropbox. */
        private const val APP_FOLDER_PATH = "/Apps/UltimateRecoveryPro"

        /** Chunk size for upload sessions (4 MB). */
        private const val UPLOAD_CHUNK_SIZE = 4 * 1024 * 1024

        /** Buffer size for stream operations. */
        private const val BUFFER_SIZE = 8192

        /** Dropbox app key — replace with production value. */
        private const val APP_KEY = "your_dropbox_app_key"

        /** Dropbox app secret — replace with production value. */
        private const val APP_SECRET = "your_dropbox_app_secret"
    }

    override val providerType: CloudProvider = CloudProvider.DROPBOX

    /** Stored OAuth2 access token. */
    @Volatile
    private var accessToken: String? = null

    // ──────────────────────────────────────────────
    // Authentication
    // ──────────────────────────────────────────────

    override suspend fun authenticate(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // In production using the Dropbox SDK:
                //
                //   val config = DbxRequestConfig.newBuilder("UltimateRecoveryPro").build()
                //   val auth = DbxWebAuth(config, DbxAppInfo(APP_KEY, APP_SECRET))
                //
                //   1. Build authorization URL:
                //      val authUrl = auth.authorize(DbxWebAuth.Request.newBuilder()
                //          .withForceReapprove(false)
                //          .build())
                //
                //   2. Open URL in browser / CustomTabsIntent
                //   3. Receive redirect in onNewIntent()
                //   4. Exchange auth code for access token:
                //      val authFinish = auth.finishFromCode(authCode)
                //      accessToken = authFinish.accessToken

                // Simulated successful auth
                accessToken = "simulated_dropbox_access_token"
                true
            } catch (e: Exception) {
                accessToken = null
                false
            }
        }
    }

    override fun isAuthenticated(): Boolean {
        return !accessToken.isNullOrBlank()
    }

    // ──────────────────────────────────────────────
    // Upload
    // ──────────────────────────────────────────────

    override suspend fun upload(
        file: File,
        remotePath: String,
        progressListener: CloudBackupProvider.ProgressListener?
    ): String {
        requireAuthenticated()

        return retryWithBackoff {
            withContext(Dispatchers.IO) {
                val dropboxPath = "$APP_FOLDER_PATH/${file.name}"

                ensureAppFolder()

                val result = if (file.length() > 150 * 1024 * 1024) {
                    // Large file — use upload session
                    uploadSessionStart(file, dropboxPath, progressListener)
                } else {
                    // Small file — simple upload
                    simpleUpload(file, dropboxPath, progressListener)
                }

                result
            }
        }
    }

    /**
     * Simple upload for files under 150 MB.
     *
     * Uses the /files/upload endpoint.
     */
    private fun simpleUpload(
        file: File,
        dropboxPath: String,
        progressListener: CloudBackupProvider.ProgressListener?
    ): String {
        // In production using the Dropbox SDK:
        //
        //   val config = DbxRequestConfig.newBuilder("UltimateRecoveryPro").build()
        //   val client = DbxClientV2(config, accessToken)
        //   val localFile = java.io.File(file.absolutePath)
        //
        //   val metadata = client.files()
        //       .uploadBuilder(dropboxPath)
        //       .withMode(WriteMode.OVERWRITE)
        //       .uploadAndFinish(FileInputStream(localFile))
        //
        //   return metadata.getId()

        progressListener?.onProgress(50)
        progressListener?.onProgress(100)

        return "dropbox_file_${System.currentTimeMillis()}"
    }

    /**
     * Chunked upload session for files over 150 MB.
     *
     * Uses /files/upload_session/start → append → finish.
     */
    private fun uploadSessionStart(
        file: File,
        dropboxPath: String,
        progressListener: CloudBackupProvider.ProgressListener?
    ): String {
        // In production using the Dropbox SDK:
        //
        //   val config = DbxRequestConfig.newBuilder("UltimateRecoveryPro").build()
        //   val client = DbxClientV2(config, accessToken)
        //
        //   var sessionCursor: FileMetadata? = null
        //   FileInputStream(file).use { fis ->
        //       val buffer = ByteArray(UPLOAD_CHUNK_SIZE)
        //       var bytesRead: Int
        //       var offset = 0L
        //       var isFirst = true
        //
        //       while (fis.read(buffer).also { bytesRead = it } != -1) {
        //           val chunkStream = ByteArrayInputStream(buffer, 0, bytesRead)
        //
        //           if (isFirst) {
        //               val result = client.files().uploadSessionStart(chunkStream)
        //               sessionCursor = UploadSessionCursor(result.sessionId, offset + bytesRead)
        //               isFirst = false
        //           } else {
        //               client.files().uploadSessionAppendV2(
        //                   UploadSessionCursor(sessionCursor!!.sessionId, offset),
        //                   chunkStream
        //               )
        //           }
        //
        //           offset += bytesRead
        //           val progress = ((offset.toFloat() / file.length()) * 100).toInt()
        //           progressListener?.onProgress(progress)
        //       }
        //
        //       // Finish the session
        //       val commitInfo = CommitInfo.newBuilder(dropboxPath)
        //           .withMode(WriteMode.OVERWRITE)
        //           .build()
        //       val metadata = client.files().uploadSessionFinish(
        //           UploadSessionCursor(sessionCursor!!.sessionId, offset),
        //           commitInfo,
        //           ByteArrayInputStream(ByteArray(0))
        //       )
        //       return metadata.getId()
        //   }

        FileInputStream(file).use { fis ->
            val buffer = ByteArray(UPLOAD_CHUNK_SIZE)
            var bytesRead: Int
            var offset = 0L
            val totalSize = file.length()

            while (fis.read(buffer).also { bytesRead = it } != -1) {
                offset += bytesRead
                val progress = ((offset.toFloat() / totalSize) * 100).toInt()
                progressListener?.onProgress(progress.coerceAtMost(100))
            }
        }

        return "dropbox_file_${System.currentTimeMillis()}"
    }

    // ──────────────────────────────────────────────
    // Download
    // ──────────────────────────────────────────────

    override suspend fun download(
        remotePath: String,
        localFile: File,
        progressListener: CloudBackupProvider.ProgressListener?
    ) {
        requireAuthenticated()

        retryWithBackoff {
            withContext(Dispatchers.IO) {
                // In production using the Dropbox SDK:
                //
                //   val config = DbxRequestConfig.newBuilder("UltimateRecoveryPro").build()
                //   val client = DbxClientV2(config, accessToken)
                //
                //   val outputStream = FileOutputStream(localFile)
                //   val metadata = client.files().downloadBuilder(remotePath)
                //       .download(outputStream)
                //
                //   outputStream.close()

                localFile.parentFile?.mkdirs()

                // Simulated download with progress
                val totalSize = 10 * 1024 * 1024L
                var bytesDownloaded = 0L

                FileOutputStream(localFile).use { fos ->
                    val chunkSize = BUFFER_SIZE
                    while (bytesDownloaded < totalSize) {
                        val toWrite = minOf(chunkSize.toLong(), totalSize - bytesDownloaded).toInt()
                        fos.write(ByteArray(toWrite))
                        bytesDownloaded += toWrite
                        val progress = ((bytesDownloaded.toFloat() / totalSize) * 100).toInt()
                        progressListener?.onProgress(progress.coerceAtMost(100))
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // Delete
    // ──────────────────────────────────────────────

    override suspend fun delete(remotePath: String) {
        requireAuthenticated()

        retryWithBackoff {
            withContext(Dispatchers.IO) {
                // In production using the Dropbox SDK:
                //
                //   val config = DbxRequestConfig.newBuilder("UltimateRecoveryPro").build()
                //   val client = DbxClientV2(config, accessToken)
                //   client.files().deleteV2(remotePath)
            }
        }
    }

    // ──────────────────────────────────────────────
    // Listing and metadata
    // ──────────────────────────────────────────────

    override suspend fun listBackups(): List<CloudBackupInfo> {
        requireAuthenticated()

        return withContext(Dispatchers.IO) {
            // In production using the Dropbox SDK:
            //
            //   val config = DbxRequestConfig.newBuilder("UltimateRecoveryPro").build()
            //   val client = DbxClientV2(config, accessToken)
            //
            //   val result = client.files().listFolder(APP_FOLDER_PATH)
            //   result.entries.map { entry ->
            //       CloudBackupInfo(
            //           name = entry.name,
            //           remotePath = entry.pathLower ?: entry.name,
            //           size = (entry as? FileMetadata)?.size ?: -1L,
            //           lastModified = (entry as? FileMetadata)?.serverModified?.time ?: -1L,
            //           mimeType = "application/zip"
            //       )
            //   }

            emptyList()
        }
    }

    override suspend fun getBackupInfo(remotePath: String): CloudBackupInfo? {
        requireAuthenticated()

        return withContext(Dispatchers.IO) {
            // In production using the Dropbox SDK:
            //
            //   val config = DbxRequestConfig.newBuilder("UltimateRecoveryPro").build()
            //   val client = DbxClientV2(config, accessToken)
            //
            //   val metadata = client.files().getMetadata(remotePath)
            //   (metadata as? FileMetadata)?.let { file ->
            //       CloudBackupInfo(
            //           name = file.name,
            //           remotePath = file.pathLower ?: file.name,
            //           size = file.size,
            //           lastModified = file.serverModified.time,
            //           mimeType = "application/zip"
            //       )
            //   }

            null
        }
    }

    // ──────────────────────────────────────────────
    // Folder management
    // ──────────────────────────────────────────────

    /**
     * Ensures the app folder exists in Dropbox, creating it if necessary.
     */
    private fun ensureAppFolder() {
        // In production:
        //   try {
        //       client.files().createFolderV2(APP_FOLDER_PATH)
        //   } catch (e: CreateFolderErrorException) {
        //       if (e.errorValue.isPath && e.errorValue.pathValue.isConflict) {
        //           // Folder already exists — this is fine
        //       } else throw e
        //   }
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun requireAuthenticated() {
        if (!isAuthenticated()) {
            throw CloudBackupException(
                "Not authenticated with Dropbox",
                provider = providerType,
                isRetryable = false
            )
        }
    }

    /**
     * Executes [block] with exponential-backoff retry logic.
     */
    private suspend fun <T> retryWithBackoff(block: suspend () -> T): T {
        var attempt = 0
        var delayMs = 1000L

        while (true) {
            try {
                return block()
            } catch (e: CloudBackupException) {
                if (!e.isRetryable || attempt >= maxRetries) throw e
                attempt++
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            } catch (e: IOException) {
                if (attempt >= maxRetries) throw CloudBackupException(
                    "I/O error after $attempt retries: ${e.message}",
                    provider = providerType,
                    isRetryable = false,
                    cause = e
                )
                attempt++
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("Unreachable")
    }
}
