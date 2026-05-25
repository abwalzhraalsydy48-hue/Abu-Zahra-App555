package com.ultimaterecovery.pro.data.repository

import com.ultimaterecovery.pro.data.local.dao.ScanSessionDao
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanStatus
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for [ScanSessionEntity] records.
 *
 * Manages the full lifecycle of a scan session — creation, progress
 * updates, pausing, resuming, completion, and cancellation — while
 * wrapping every result in a [Resource] for consistent error handling.
 */
@Singleton
class ScanSessionRepository @Inject constructor(
    private val scanSessionDao: ScanSessionDao
) {

    // ──────────────────────────────────────────────
    // Mutations
    // ──────────────────────────────────────────────

    /**
     * Creates a new scan session and returns its generated row ID.
     *
     * The session is initialised with [ScanStatus.RUNNING] and zero progress.
     */
    suspend fun createSession(session: ScanSessionEntity): Resource<Long> =
        try {
            val id = scanSessionDao.insert(session)
            Resource.success(id)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to create scan session")
        }

    /**
     * Updates the real-time progress of the session identified by [id].
     *
     * @param progress       Fractional progress from 0.0 to 1.0.
     * @param sectorsScanned Number of storage sectors scanned so far.
     * @param totalFilesFound Cumulative count of files discovered.
     * @param totalSizeFound  Cumulative size of files discovered (bytes).
     */
    suspend fun updateProgress(
        id: Long,
        progress: Float,
        sectorsScanned: Long,
        totalFilesFound: Int,
        totalSizeFound: Long
    ): Resource<Unit> =
        try {
            scanSessionDao.updateProgress(id, progress, sectorsScanned, totalFilesFound, totalSizeFound)
            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to update scan progress")
        }

    /**
     * Marks the session [id] as [ScanStatus.COMPLETED] and sets the
     * [endTime] timestamp. Progress is forced to 1.0.
     */
    suspend fun completeSession(id: Long, endTime: Long = System.currentTimeMillis()): Resource<Unit> =
        try {
            scanSessionDao.completeSession(id, endTime)
            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to complete scan session")
        }

    /**
     * Marks the session [id] as [ScanStatus.CANCELLED] and records the
     * [endTime] timestamp.
     */
    suspend fun cancelSession(id: Long, endTime: Long = System.currentTimeMillis()): Resource<Unit> =
        try {
            scanSessionDao.cancelSession(id, endTime)
            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to cancel scan session")
        }

    /**
     * Pauses the session identified by [id] by updating its status
     * to [ScanStatus.PAUSED] via a full entity update.
     *
     * The caller should first obtain the current entity (e.g. via
     * [getSessionById]) and pass the modified copy here.
     */
    suspend fun pauseSession(session: ScanSessionEntity): Resource<Unit> {
        return try {
            if (session.status != ScanStatus.RUNNING) {
                Resource.error("Only running sessions can be paused", code = 400)
            } else {
                scanSessionDao.update(session.copy(status = ScanStatus.PAUSED))
                Resource.success(Unit)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to pause scan session")
        }
    }

    /**
     * Resumes a paused session by setting its status back to
     * [ScanStatus.RUNNING] via a full entity update.
     */
    suspend fun resumeSession(session: ScanSessionEntity): Resource<Unit> {
        return try {
            if (session.status != ScanStatus.PAUSED) {
                Resource.error("Only paused sessions can be resumed", code = 400)
            } else {
                scanSessionDao.update(session.copy(status = ScanStatus.RUNNING))
                Resource.success(Unit)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to resume scan session")
        }
    }

    // ──────────────────────────────────────────────
    // Reactive queries
    // ──────────────────────────────────────────────

    /**
     * Emits the currently active session (RUNNING or PAUSED), or `null`
     * when no active session exists.
     */
    fun getActiveSession(): Flow<Resource<ScanSessionEntity?>> =
        scanSessionDao.getActiveSession()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load active session")) }

    /**
     * Emits all completed sessions in descending end-time order.
     */
    fun getCompletedSessions(): Flow<Resource<List<ScanSessionEntity>>> =
        scanSessionDao.getCompletedSessions()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load completed sessions")) }

    /**
     * Emits the session with the given [id], or `null` when not found.
     */
    fun getSessionById(id: Long): Flow<Resource<ScanSessionEntity?>> =
        scanSessionDao.getById(id)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load session by ID")) }

    /**
     * Emits all sessions regardless of status, in descending start-time order.
     */
    fun getAllSessions(): Flow<Resource<List<ScanSessionEntity>>> =
        scanSessionDao.getAll()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load all sessions")) }
}
