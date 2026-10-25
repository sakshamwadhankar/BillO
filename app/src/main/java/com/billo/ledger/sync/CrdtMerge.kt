package com.billo.ledger.sync

import com.billo.ledger.core.Clock
import com.billo.ledger.core.Hlc
import com.billo.ledger.core.OpsCodec
import com.billo.ledger.data.db.BillODatabase
import com.billo.ledger.data.db.EntryKind
import com.billo.ledger.data.db.JournalEntryEntity
import com.billo.ledger.data.db.LedgerOpEntity
import com.billo.ledger.data.db.OpType
import com.billo.ledger.data.db.SyncConflictEntity
import com.billo.ledger.data.ledger.LedgerRepository
import com.billo.ledger.data.ledger.PostRequest
import com.billo.ledger.data.ledger.UncertainLeg
import java.util.UUID
import kotlin.math.abs

/** Summary of one merge round against a remote op-log. */
data class MergeReport(
    val receivedOps: Int,
    val appliedOps: Int,
    val duplicateOps: Int,
    val entriesApplied: Int,
    val accountsApplied: Int,
    val settlementClaimsStored: Int,
    val conflictsResolved: Int,
    val balanced: Boolean,
    val notes: List<String>,
)

/**
 * CRDT merge engine: folds a remote op-log into the local database.
 *
 * Op-logs merge as a commutative union by opId (Room primary key with
 * IGNORE conflict strategy makes re-apply free), and the hybrid logical
 * clock observes every remote HLC so local stamps never regress behind
 * what this device has seen. Settlement contradictions discovered in the
 * merged history are repaired with deterministic, idempotent reversals.
 */
class CrdtMerge(
    private val database: BillODatabase,
    private val ledger: LedgerRepository,
    private val clock: Clock,
) {

    suspend fun merge(remoteOps: List<LedgerOpEntity>): MergeReport {
        var duplicateOps = 0
        var appliedOps = 0
        var entriesApplied = 0
        var accountsApplied = 0
        var settlementClaimsStored = 0

        for (op in remoteOps) {
            if (database.opLogDao().opExists(op.opId) > 0) {
                duplicateOps++
                continue
            }
            database.opLogDao().insertOp(op.copy(appliedAtWall = System.currentTimeMillis()))
            clock.observe(Hlc(op.hlcWall, op.hlcCounter, op.nodeId))
            when (op.type) {
                OpType.NEW_ACCOUNT -> {
                    ledger.applyAccountOp(OpsCodec.decodeAccount(op.payloadJson))
                    accountsApplied++
                    appliedOps++
                }
                OpType.POST_ENTRY -> {
                    if (ledger.applyEntryOp(OpsCodec.decodeEntry(op.payloadJson))) {
                        entriesApplied++
                    }
                    appliedOps++
                }
                OpType.SETTLEMENT_CLAIM -> {
                    settlementClaimsStored++
                    appliedOps++
                }
            }
        }

        val conflictsResolved = resolveSettlementConflicts()
        val balanced = isBalanced()
        val notes = buildList {
            add(
                "Received ${remoteOps.size} ops: $appliedOps applied, $duplicateOps duplicates " +
                    "($entriesApplied entries, $accountsApplied accounts, $settlementClaimsStored claims stored)",
            )
            add("Settlement conflicts resolved with reversals: $conflictsResolved")
            add(if (balanced) "Ledger balanced after merge (sum of signed balances = 0)" else "Ledger NOT balanced after merge — investigate")
        }
        return MergeReport(
            receivedOps = remoteOps.size,
            appliedOps = appliedOps,
            duplicateOps = duplicateOps,
            entriesApplied = entriesApplied,
            accountsApplied = accountsApplied,
            settlementClaimsStored = settlementClaimsStored,
            conflictsResolved = conflictsResolved,
            balanced = balanced,
            notes = notes,
        )
    }

    /**
     * Finds contradictory SETTLE_CUSTOMER entries (same customer account,
     * within 24h of each other, amounts differing by more than 20%) and
     * repairs them by posting a deterministic reversal of the loser.
     *
     * Winner rule (total order, identical on every device): higher
     * confidence, then smaller hlcWall, then smaller hlcCounter, then
     * smaller uuid. The reversal uuid is derived from the loser's uuid so
     * the repair itself is an idempotent CRDT op.
     */
    suspend fun resolveSettlementConflicts(): Int {
        val dao = database.ledgerDao()
        val entries = dao.entriesByKind(EntryKind.SETTLE_CUSTOMER).sortedBy { it.uuid }

        data class Settle(val entry: JournalEntryEntity, val customerAccount: String, val amount: Long)

        val settles = mutableListOf<Settle>()
        for (entry in entries) {
            val lines = dao.linesForEntry(entry.id)
            val customerLine = lines.firstOrNull { it.accountName.startsWith(CUSTOMER_PREFIX) } ?: continue
            settles.add(
                Settle(
                    entry = entry,
                    customerAccount = customerLine.accountName,
                    amount = abs(customerLine.debitPaise - customerLine.creditPaise),
                ),
            )
        }

        var resolved = 0
        val now = System.currentTimeMillis()
        // groupBy preserves encounter order; entries were sorted by uuid, so
        // pair iteration is deterministic (i < j in uuid order).
        for ((customerAccount, group) in settles.groupBy { it.customerAccount }) {
            val list = group.sortedBy { it.entry.uuid }
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    if (abs(a.entry.wallTimeMillis - b.entry.wallTimeMillis) > DAY_MILLIS) continue
                    val maxAmount = maxOf(a.amount, b.amount)
                    if (maxAmount <= 0 || abs(a.amount - b.amount) <= 0.2 * maxAmount) continue

                    val winner = pickWinner(a.entry, b.entry)
                    val loser = if (winner.uuid == a.entry.uuid) b.entry else a.entry
                    val reversalUuid = UUID
                        .nameUUIDFromBytes("billo-reversal:${loser.uuid}".toByteArray())
                        .toString()
                    if (ledger.entryExists(reversalUuid)) continue // already resolved elsewhere

                    val loserLines = dao.linesForEntry(loser.id)
                    val mirrored = loserLines.mapNotNull { line ->
                        if (line.debitPaise == 0L && line.creditPaise == 0L) return@mapNotNull null
                        val account = dao.accountByName(line.accountName)
                        UncertainLeg(
                            accountName = line.accountName,
                            accountType = account?.type,
                            parentName = account?.parentName,
                            debitPaise = line.creditPaise,
                            creditPaise = line.debitPaise,
                            qty = line.qty,
                            unit = line.unit,
                        )
                    }
                    if (mirrored.size < 2) continue // cannot form a balanced double entry

                    ledger.postEntry(
                        PostRequest(
                            description = "CRDT conflict resolution: reversed contradictory settlement ${loser.uuid.take(8)}",
                            lines = mirrored,
                            kind = EntryKind.NORMAL,
                            confidence = (winner.confidence * 0.9).coerceIn(0.0, 1.0),
                            uuid = reversalUuid,
                        ),
                    )
                    database.syncConflictDao().insert(
                        SyncConflictEntity(
                            subject = customerAccount,
                            winnerRef = winner.uuid,
                            loserRef = loser.uuid,
                            note = "amounts ${a.amount} vs ${b.amount} paise; winner by confidence/HLC",
                            resolvedAtWall = now,
                        ),
                    )
                    resolved++
                }
            }
        }
        return resolved
    }

    /** The books must always balance: Assets+Expenses = Liabilities+Equity+Income. */
    suspend fun isBalanced(): Boolean = ledger.isBalanced()

    private fun pickWinner(a: JournalEntryEntity, b: JournalEntryEntity): JournalEntryEntity {
        val totalOrder = compareByDescending<JournalEntryEntity> { it.confidence }
            .thenBy { it.hlcWall }
            .thenBy { it.hlcCounter }
            .thenBy { it.uuid }
        return if (totalOrder.compare(a, b) <= 0) a else b
    }

    private companion object {
        const val CUSTOMER_PREFIX = "Receivables · "
        const val DAY_MILLIS = 24L * 3600L * 1000L
    }
}
