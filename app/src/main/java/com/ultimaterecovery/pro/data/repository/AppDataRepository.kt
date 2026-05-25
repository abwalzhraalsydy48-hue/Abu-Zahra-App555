package com.ultimaterecovery.pro.data.repository

import com.ultimaterecovery.pro.data.local.dao.AppDataDao
import com.ultimaterecovery.pro.data.local.entity.AppDataEntity
import com.ultimaterecovery.pro.data.local.entity.AppDataEntity.AppDataType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for [AppDataEntity] records.
 *
 * Provides reactive queries for browsing recovered application data
 * by package name, data type, system/user classification, and search,
 * plus a suspend function for marking data as recovered.
 */
@Singleton
class AppDataRepository @Inject constructor(
    private val appDataDao: AppDataDao
) {

    // ──────────────────────────────────────────────
    // Reactive queries
    // ──────────────────────────────────────────────

    /**
     * Emits all recovered data entries belonging to the given [packageName].
     */
    fun getByPackageName(packageName: String): Flow<Resource<List<AppDataEntity>>> =
        appDataDao.getByPackageName(packageName)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load app data by package")) }

    /**
     * Emits all recovered data entries of the given [dataType]
     * (CACHE, DATABASE, SHARED_PREFS, …).
     */
    fun getByDataType(dataType: AppDataType): Flow<Resource<List<AppDataEntity>>> =
        appDataDao.getByDataType(dataType)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load app data by type")) }

    /**
     * Full-text search across app name and package name fields.
     */
    fun searchApps(query: String): Flow<Resource<List<AppDataEntity>>> =
        appDataDao.searchApps(query)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to search apps")) }

    /**
     * Emits all system app data entries, sorted alphabetically by app name.
     */
    fun getSystemApps(): Flow<Resource<List<AppDataEntity>>> =
        appDataDao.getSystemApps()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load system apps")) }

    /**
     * Emits all user (non-system) app data entries, sorted alphabetically.
     */
    fun getUserApps(): Flow<Resource<List<AppDataEntity>>> =
        appDataDao.getNonSystemApps()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load user apps")) }

    /**
     * Emits all recovered app data entries in descending recovery-date order.
     */
    fun getAll(): Flow<Resource<List<AppDataEntity>>> =
        appDataDao.getAll()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load all app data")) }

    // ──────────────────────────────────────────────
    // Mutations
    // ──────────────────────────────────────────────

    /**
     * Marks the given [appData] entries as recovered and persists them
     * to the database.
     *
     * Each entry's `isRecovered` flag is set to `true` and the
     * `recoveryDate` is updated to the current timestamp.
     *
     * @return [Resource] with the list of generated row IDs.
     */
    suspend fun recoverAppData(appDataList: List<AppDataEntity>): Resource<List<Long>> =
        try {
            val recoveredList = appDataList.map { appData ->
                appData.copy(isRecovered = true, recoveryDate = System.currentTimeMillis())
            }
            val ids = appDataDao.insertAll(recoveredList)
            Resource.success(ids)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to recover app data")
        }
}
