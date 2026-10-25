package com.billo.ledger.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class AccountType { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE }

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val parentName: String? = null,
    /** CRDT identity — deterministic per name, identical on every device. */
    val uuid: String = "",
)

enum class EntryKind { NORMAL, SALE, PURCHASE, SETTLE_CUSTOMER, LOSS }

/**
 * One journal entry = one economic event = many balanced legs.
 *
 * wallTimeMillis is the device wall clock and is HOSTILE by design
 * (the PS demands survival of 1970 resets / forward jumps). It is stored
 * for display only and never used for ordering; monoTimeMillis
 * (SystemClock.elapsedRealtime) gives a monotonic per-device order.
 * uuid + nodeId + hlc* make the entry an immutable CRDT op payload.
 */
@Entity(tableName = "journal_entries")
data class JournalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val description: String,
    val wallTimeMillis: Long,
    val monoTimeMillis: Long,
    val sourceEvidenceId: Long? = null,
    val uuid: String = "",
    val kind: EntryKind = EntryKind.NORMAL,
    val confidence: Double = 1.0,
    val nodeId: String = "local",
    val hlcWall: Long = 0,
    val hlcCounter: Long = 0,
)

@Entity(tableName = "journal_lines")
data class JournalLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val accountId: Long,
    val accountName: String,
    val debitPaise: Long = 0,
    val creditPaise: Long = 0,
    /** DistSummary JSON of the true amount — Phase 2 uncertainty metadata. */
    val distJson: String? = null,
    val qty: Double? = null,
    val unit: String? = null,
)

data class EntryWithLines(
    @Embedded val entry: JournalEntryEntity,
    @Relation(parentColumn = "id", entityColumn = "entryId")
    val lines: List<JournalLineEntity>,
)

enum class EvidenceChannel { VOICE, OCR, SMS, TALLY, MANUAL }

enum class EvidenceStatus { RAW, FUSED, REJECTED }

/**
 * Raw multi-channel evidence BEFORE fusion. Voice transcriptions, SMS
 * bodies, OCR text from slips and tallies all land here first; the
 * fusion engine reconciles them into journal entries.
 */
@Entity(tableName = "evidence")
data class EvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: EvidenceChannel,
    val text: String,
    val imageUri: String? = null,
    val wallTimeMillis: Long,
    val monoTimeMillis: Long,
    val parsedHint: String? = null,
    val status: EvidenceStatus = EvidenceStatus.RAW,
    /** Calibrated probability that this evidence reflects a real event. */
    val confidence: Double = 1.0,
)

// ---------------------------------------------------------------------------
// Phase 2: Lexicon Drop — mid-event learning of unknown words/units
// ---------------------------------------------------------------------------

enum class LexiconMeaning { UNSET, UNIT, ITEM, PERSON }

@Entity(tableName = "lexicon")
data class LexiconEntity(
    @PrimaryKey val token: String,
    val meaning: LexiconMeaning = LexiconMeaning.UNSET,
    val count: Int = 1,
    val firstSeenWall: Long = 0,
    val lastSeenWall: Long = 0,
    val sampleContext: String = "",
)

// ---------------------------------------------------------------------------
// Phase 4: inventory, burn rates and loss attribution
// ---------------------------------------------------------------------------

enum class ItemKind { RAW, FINISHED }

@Entity(tableName = "inventory_items")
data class InventoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: ItemKind,
    val unit: String = "kg",
    /** Latest observed purchase cost per unit, paise — prior for COGS. */
    val unitCostPaise: Long = 0,
)

@Entity(tableName = "stock_moves")
data class StockMoveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemName: String,
    val deltaQty: Double,
    val reason: String,
    val wallTimeMillis: Long,
    val monoTimeMillis: Long = 0,
)

// ---------------------------------------------------------------------------
// Phase 5: CRDT op-log + contradiction records
// ---------------------------------------------------------------------------

enum class OpType { NEW_ACCOUNT, POST_ENTRY, SETTLEMENT_CLAIM }

@Entity(tableName = "ledger_ops")
data class LedgerOpEntity(
    @PrimaryKey val opId: String,
    val hlcWall: Long,
    val hlcCounter: Long,
    val nodeId: String,
    val type: OpType,
    val payloadJson: String,
    val appliedAtWall: Long = 0,
)

@Entity(tableName = "sync_conflicts")
data class SyncConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val winnerRef: String,
    val loserRef: String,
    val note: String,
    val resolvedAtWall: Long = 0,
)
