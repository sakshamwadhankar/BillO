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
)

/**
 * One journal entry = one economic event = many balanced legs.
 *
 * wallTimeMillis is the device wall clock and is HOSTILE by design
 * (the PS demands survival of 1970 resets / forward jumps). It is stored
 * for display only and never used for ordering; monoTimeMillis
 * (SystemClock.elapsedRealtime) gives a monotonic per-device order and
 * is the seed for the logical clocks added in the sync phase.
 */
@Entity(tableName = "journal_entries")
data class JournalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val description: String,
    val wallTimeMillis: Long,
    val monoTimeMillis: Long,
    val sourceEvidenceId: Long? = null,
)

@Entity(tableName = "journal_lines")
data class JournalLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val accountId: Long,
    val accountName: String,
    val debitPaise: Long = 0,
    val creditPaise: Long = 0,
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
 * fusion engine (Phase 3) reconciles them into journal entries.
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
)
