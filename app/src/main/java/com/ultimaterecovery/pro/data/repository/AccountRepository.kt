package com.ultimaterecovery.pro.data.repository

import com.ultimaterecovery.pro.data.local.dao.AccountDataDao
import com.ultimaterecovery.pro.data.local.entity.AccountDataEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for [AccountDataEntity] records.
 *
 * Provides reactive queries for browsing recovered account data by
 * type, sensitivity, and search, plus a suspend function for marking
 * accounts as recovered.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val accountDataDao: AccountDataDao
) {

    // ──────────────────────────────────────────────
    // Reactive queries
    // ──────────────────────────────────────────────

    /**
     * Emits all recovered accounts matching the given [accountType]
     * (e.g. "com.google", "com.whatsapp").
     */
    fun getByType(accountType: String): Flow<Resource<List<AccountDataEntity>>> =
        accountDataDao.getByAccountType(accountType)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load accounts by type")) }

    /**
     * Emits all accounts flagged as sensitive.
     */
    fun getSensitiveAccounts(): Flow<Resource<List<AccountDataEntity>>> =
        accountDataDao.getSensitiveAccounts()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load sensitive accounts")) }

    /**
     * Full-text search across account name and account type fields.
     */
    fun searchAccounts(query: String): Flow<Resource<List<AccountDataEntity>>> =
        accountDataDao.searchAccounts(query)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to search accounts")) }

    /**
     * Emits all recovered account data entries in descending recovery-date order.
     */
    fun getAll(): Flow<Resource<List<AccountDataEntity>>> =
        accountDataDao.getAll()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load all accounts")) }

    // ──────────────────────────────────────────────
    // Mutations
    // ──────────────────────────────────────────────

    /**
     * Marks the given [accounts] as recovered and persists them to the
     * database.
     *
     * Each entry's `isRecovered` flag is set to `true` and the
     * `recoveryDate` is updated to the current timestamp.
     *
     * @return [Resource] with the list of generated row IDs.
     */
    suspend fun recoverAccount(accounts: List<AccountDataEntity>): Resource<List<Long>> =
        try {
            val recoveredAccounts = accounts.map { account ->
                account.copy(isRecovered = true, recoveryDate = System.currentTimeMillis())
            }
            val ids = accountDataDao.insertAll(recoveredAccounts)
            Resource.success(ids)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to recover accounts")
        }
}
