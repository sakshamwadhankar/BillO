package com.billo.ledger.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {

    @Insert
    suspend fun insertAccount(account: AccountEntity): Long

    @Query("SELECT * FROM accounts ORDER BY name")
    fun accounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun allAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE name = :name LIMIT 1")
    suspend fun accountByName(name: String): AccountEntity?

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun accountCount(): Int

    @Insert
    suspend fun insertEntry(entry: JournalEntryEntity): Long

    @Insert
    suspend fun insertLines(lines: List<JournalLineEntity>)

    @Transaction
    @Query("SELECT * FROM journal_entries ORDER BY id DESC")
    fun entriesWithLines(): Flow<List<EntryWithLines>>

    @Query("SELECT * FROM journal_lines")
    suspend fun allLines(): List<JournalLineEntity>

    @Query("DELETE FROM journal_lines")
    suspend fun clearLines()

    @Query("DELETE FROM journal_entries")
    suspend fun clearEntries()

    @Query("DELETE FROM accounts")
    suspend fun clearAccounts()
}

@Dao
interface EvidenceDao {

    @Insert
    suspend fun insert(evidence: EvidenceEntity): Long

    @Query("SELECT * FROM evidence ORDER BY id DESC")
    fun all(): Flow<List<EvidenceEntity>>

    @Query("SELECT COUNT(*) FROM evidence WHERE text = :text AND wallTimeMillis = :wallTimeMillis")
    suspend fun countSame(text: String, wallTimeMillis: Long): Int

    @Query("UPDATE evidence SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: EvidenceStatus)

    @Query("DELETE FROM evidence")
    suspend fun clear()
}
