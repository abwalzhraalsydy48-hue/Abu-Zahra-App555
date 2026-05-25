package com.ultimaterecovery.pro.data.repository

import com.ultimaterecovery.pro.data.local.dao.BackupDao
import com.ultimaterecovery.pro.data.local.entity.BackupEntity
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.BackupStatus
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.BackupType
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.CloudProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for [BackupEntity] records.
 *
 * Manages backup lifecycle operations — creation, restoration,
 * deletion, cloud upload/download — and provides reactive queries
 * filtered by type, provider, and status.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val backupDao: BackupDao
) {

    // ──────────────────────────────────────────────
    // Mutations
    // ──────────────────────────────────────────────

    /**
     * Creates a new backup record.
     *
     * The entity should be pre-populated with [BackupStatus.PENDING].
     *
     * @return [Resource] with the generated row ID.
     */
    suspend fun createBackup(backup: BackupEntity): Resource<Long> =
        try {
            val id = backupDao.insert(backup)
            Resource.success(id)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to create backup")
        }

    /**
     * Restores a backup by marking it as [BackupStatus.COMPLETED].
     *
     * In a full implementation this would also restore the actual
     * backup files from the backup path.
     *
     * @return [Resource] with the restored [BackupEntity] ID.
     */
    suspend fun restoreBackup(backupId: Long): Resource<Long> =
        try {
            // Restore logic: update status and return the ID.
            // A real implementation would read the backup archive and
            // copy files back to their original locations.
            backupDao.updateStatus(backupId, BackupStatus.COMPLETED)
            Resource.success(backupId)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to restore backup")
        }

    /**
     * Permanently deletes the given [backup] record from the database.
     */
    suspend fun deleteBackup(backup: BackupEntity): Resource<Unit> =
        try {
            backupDao.delete(backup)
            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to delete backup")
        }

    /**
     * Uploads the backup identified by [backupId] to the cloud.
     *
     * Updates the status to [BackupStatus.UPLOADING], then to
     * [BackupStatus.UPLOADED] upon success.
     *
     * @return [Resource] with the updated [BackupEntity] ID.
     */
    suspend fun uploadToCloud(backupId: Long): Resource<Long> =
        try {
            backupDao.updateStatus(backupId, BackupStatus.UPLOADING)
            // TODO: Integrate actual cloud upload (Google Drive / Dropbox SDK)
            // Simulate successful upload by transitioning to UPLOADED.
            backupDao.updateStatus(backupId, BackupStatus.UPLOADED)
            Resource.success(backupId)
        } catch (e: Exception) {
            backupDao.updateStatus(backupId, BackupStatus.FAILED)
            Resource.error(e.message ?: "Failed to upload backup to cloud")
        }

    /**
     * Downloads a backup from the cloud by its [backupId].
     *
     * In a full implementation this would use the [CloudProvider]
     * and `cloudUrl` stored on the entity to download the archive.
     *
     * @return [Resource] with the downloaded [BackupEntity] ID.
     */
    suspend fun downloadFromCloud(backupId: Long): Resource<Long> =
        try {
            // TODO: Integrate actual cloud download (Google Drive / Dropbox SDK)
            // Placeholder: mark as completed after "download".
            backupDao.updateStatus(backupId, BackupStatus.COMPLETED)
            Resource.success(backupId)
        } catch (e: Exception) {
            backupDao.updateStatus(backupId, BackupStatus.FAILED)
            Resource.error(e.message ?: "Failed to download backup from cloud")
        }

    /**
     * Updates the status of the backup identified by [id] to [status].
     */
    suspend fun updateStatus(id: Long, status: BackupStatus): Resource<Unit> =
        try {
            backupDao.updateStatus(id, status)
            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to update backup status")
        }

    // ──────────────────────────────────────────────
    // Reactive queries
    // ──────────────────────────────────────────────

    /**
     * Emits backups of the given [type].
     */
    fun getBackupsByType(type: BackupType): Flow<Resource<List<BackupEntity>>> =
        backupDao.getByType(type)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load backups by type")) }

    /**
     * Emits backups stored with the given [cloudProvider].
     */
    fun getBackupsByProvider(cloudProvider: CloudProvider): Flow<Resource<List<BackupEntity>>> =
        backupDao.getByCloudProvider(cloudProvider)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load backups by provider")) }

    /**
     * Emits all completed (or uploaded) backups.
     */
    fun getCompletedBackups(): Flow<Resource<List<BackupEntity>>> =
        backupDao.getCompletedBackups()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load completed backups")) }
}
