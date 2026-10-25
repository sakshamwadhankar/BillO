package com.billo.ledger.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    @Query("SELECT COUNT(*) FROM journal_entries WHERE uuid = :uuid")
    suspend fun entryCountByUuid(uuid: String): Int

    @Query("SELECT * FROM journal_entries WHERE kind = :kind")
    suspend fun entriesByKind(kind: EntryKind): List<JournalEntryEntity>

    @Insert
    suspend fun insertLines(lines: List<JournalLineEntity>)

    @Transaction
    @Query("SELECT * FROM journal_entries ORDER BY id DESC")
    fun entriesWithLines(): Flow<List<EntryWithLines>>

    @Query("SELECT * FROM journal_lines")
    suspend fun allLines(): List<JournalLineEntity>

    @Query("SELECT * FROM journal_lines WHERE entryId = :entryId")
    suspend fun linesForEntry(entryId: Long): List<JournalLineEntity>

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

    @Query("SELECT * FROM evidence WHERE status = :status")
    suspend fun byStatus(status: EvidenceStatus): List<EvidenceEntity>

    @Query("SELECT COUNT(*) FROM evidence WHERE text = :text AND wallTimeMillis = :wallTimeMillis")
    suspend fun countSame(text: String, wallTimeMillis: Long): Int

    @Query("UPDATE evidence SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: EvidenceStatus)

    @Query("DELETE FROM evidence")
    suspend fun clear()
}

@Dao
interface LexiconDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LexiconEntity)

    @Query("SELECT * FROM lexicon WHERE token = :token LIMIT 1")
    suspend fun byToken(token: String): LexiconEntity?

    @Query("SELECT * FROM lexicon ORDER BY lastSeenWall DESC")
    fun all(): Flow<List<LexiconEntity>>

    @Query("SELECT * FROM lexicon")
    suspend fun allOnce(): List<LexiconEntity>

    @Query("DELETE FROM lexicon")
    suspend fun clear()
}

@Dao
interface InventoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: InventoryItemEntity): Long

    @Query("SELECT * FROM inventory_items ORDER BY name")
    suspend fun allItems(): List<InventoryItemEntity>

    @Query("SELECT * FROM inventory_items WHERE name = :name LIMIT 1")
    suspend fun itemByName(name: String): InventoryItemEntity?

    @Query("UPDATE inventory_items SET unitCostPaise = :costPaise WHERE name = :name")
    suspend fun updateUnitCost(name: String, costPaise: Long)

    @Insert
    suspend fun insertMove(move: StockMoveEntity): Long

    @Query("SELECT * FROM stock_moves ORDER BY wallTimeMillis")
    suspend fun allMoves(): List<StockMoveEntity>

    @Query("SELECT * FROM stock_moves WHERE itemName = :name ORDER BY wallTimeMillis")
    suspend fun movesFor(name: String): List<StockMoveEntity>

    @Query("DELETE FROM stock_moves")
    suspend fun clearMoves()

    @Query("DELETE FROM inventory_items")
    suspend fun clearItems()
}

@Dao
interface OpLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOp(op: LedgerOpEntity): Long

    @Query("SELECT * FROM ledger_ops ORDER BY hlcWall, hlcCounter, nodeId")
    suspend fun allOps(): List<LedgerOpEntity>

    @Query("SELECT COUNT(*) FROM ledger_ops")
    suspend fun opCount(): Int

    @Query("SELECT COUNT(*) FROM ledger_ops WHERE opId = :opId")
    suspend fun opExists(opId: String): Int

    @Query("DELETE FROM ledger_ops")
    suspend fun clear()
}

@Dao
interface SyncConflictDao {
    @Insert
    suspend fun insert(conflict: SyncConflictEntity): Long

    @Query("SELECT * FROM sync_conflicts ORDER BY id DESC")
    fun all(): Flow<List<SyncConflictEntity>>

    @Query("SELECT COUNT(*) FROM sync_conflicts")
    suspend fun count(): Int

    @Query("DELETE FROM sync_conflicts")
    suspend fun clear()
}
