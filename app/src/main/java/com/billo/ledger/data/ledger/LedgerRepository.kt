package com.billo.ledger.data.ledger

import android.os.SystemClock
import androidx.room.withTransaction
import com.billo.ledger.core.Clock
import com.billo.ledger.core.Dist
import com.billo.ledger.core.DistCodec
import com.billo.ledger.core.OpsCodec
import com.billo.ledger.data.db.AccountEntity
import com.billo.ledger.data.db.AccountType
import com.billo.ledger.data.db.BillODatabase
import com.billo.ledger.data.db.EntryKind
import com.billo.ledger.data.db.EntryWithLines
import com.billo.ledger.data.db.JournalEntryEntity
import com.billo.ledger.data.db.JournalLineEntity
import com.billo.ledger.data.db.LedgerOpEntity
import com.billo.ledger.data.db.OpType
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** One leg of a double entry. Exactly one of debit/credit must be positive. */
data class Posting(
    val accountId: Long,
    val accountName: String,
    val debitPaise: Long = 0,
    val creditPaise: Long = 0,
)

/** A leg carrying uncertainty (Phase 2): point amount for balancing + a Dist for reporting. */
data class UncertainLeg(
    val accountName: String,
    val accountType: AccountType? = null,
    val parentName: String? = null,
    val debitPaise: Long = 0,
    val creditPaise: Long = 0,
    val dist: Dist? = null,
    val qty: Double? = null,
    val unit: String? = null,
)

data class Balance(val account: AccountEntity, val paise: Long)

object ChartOfAccounts {
    const val CASH = "Cash"
    const val BANK = "Bank"
    const val INVENTORY = "Inventory"
    const val RECEIVABLES = "Receivables"
    const val PAYABLES = "Payables"
    const val CAPITAL = "Owner Capital"
    const val SALES = "Sales"
    const val PURCHASES = "Purchases"
    const val SPOILAGE = "Spoilage Loss"
    const val THEFT = "Theft Loss"
    const val DRAWINGS = "Personal Drawings"

    val BASE_ACCOUNTS: List<Pair<String, AccountType>> = listOf(
        CASH to AccountType.ASSET,
        BANK to AccountType.ASSET,
        INVENTORY to AccountType.ASSET,
        RECEIVABLES to AccountType.ASSET,
        PAYABLES to AccountType.LIABILITY,
        CAPITAL to AccountType.EQUITY,
        SALES to AccountType.INCOME,
        PURCHASES to AccountType.EXPENSE,
        SPOILAGE to AccountType.EXPENSE,
        THEFT to AccountType.EXPENSE,
        DRAWINGS to AccountType.EXPENSE,
    )

    fun customerAccount(customer: String) = "Receivables · $customer"
}

/**
 * The double-entry + CRDT core.
 *
 * Balance invariant: every post() is atomic and refused unless
 * sum(debits) == sum(credits) > 0 with pure debit/credit legs.
 *
 * CRDT: every local mutation also appends an immutable LedgerOp to the
 * op-log; remote devices merge op-logs by union (opId primary key) and
 * re-apply unseen ops idempotently by deterministic uuids.
 */
class LedgerRepository(
    private val database: BillODatabase,
    val nodeId: String,
    private val clock: Clock,
) {
    private val dao get() = database.ledgerDao()

    suspend fun ensureSeeded() {
        if (dao.accountCount() == 0) {
            ChartOfAccounts.BASE_ACCOUNTS.forEach { (name, type) ->
                ensureAccount(name, type)
            }
        }
    }

    suspend fun account(name: String): AccountEntity? = dao.accountByName(name)

    /** Creates the account if missing (emitting a NEW_ACCOUNT op) and returns it. */
    suspend fun ensureAccount(name: String, type: AccountType, parentName: String? = null): AccountEntity {
        dao.accountByName(name)?.let { return it }
        val entity = AccountEntity(
            name = name,
            type = type,
            parentName = parentName,
            uuid = OpsCodec.deterministicAccountUuid(name),
        )
        return database.withTransaction {
            val existing = dao.accountByName(name)
            if (existing != null) {
                existing
            } else {
                entity.copy(id = dao.insertAccount(entity)).also {
                    val h = clock.tick()
                    database.opLogDao().insertOp(
                        LedgerOpEntity(
                            opId = it.uuid,
                            hlcWall = h.wallMillis,
                            hlcCounter = h.counter,
                            nodeId = nodeId,
                            type = OpType.NEW_ACCOUNT,
                            payloadJson = OpsCodec.encodeAccount(
                                OpsCodec.AccountPayload(it.uuid, name, type, parentName),
                            ),
                            appliedAtWall = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    suspend fun post(
        description: String,
        lines: List<Posting>,
        sourceEvidenceId: Long? = null,
    ): Long = postEntry(
        PostRequest(
            description = description,
            lines = lines.map {
                UncertainLeg(
                    accountName = it.accountName,
                    debitPaise = it.debitPaise,
                    creditPaise = it.creditPaise,
                )
            }.map { leg ->
                val account = account(leg.accountName)
                leg.copy(accountType = account?.type, parentName = account?.parentName)
            },
            sourceEvidenceId = sourceEvidenceId,
        ),
    )

    suspend fun postEntry(request: PostRequest): Long {
        require(request.lines.size >= 2) { "A double entry needs at least two legs" }
        val debits = request.lines.sumOf { it.debitPaise }
        val credits = request.lines.sumOf { it.creditPaise }
        require(debits > 0 && debits == credits) {
            "Unbalanced entry refused: Dr $debits paise != Cr $credits paise"
        }
        request.lines.forEach {
            require((it.debitPaise == 0L) xor (it.creditPaise == 0L)) {
                "Leg ${it.accountName} must be exactly one of debit/credit"
            }
        }
        val hlc = clock.tick()
        val entry = JournalEntryEntity(
            description = request.description,
            wallTimeMillis = System.currentTimeMillis(),
            monoTimeMillis = SystemClock.elapsedRealtime(),
            sourceEvidenceId = request.sourceEvidenceId,
            uuid = request.uuid ?: UUID.randomUUID().toString(),
            kind = request.kind,
            confidence = request.confidence.coerceIn(0.0, 1.0),
            nodeId = nodeId,
            hlcWall = hlc.wallMillis,
            hlcCounter = hlc.counter,
        )
        val resolvedLines = request.lines.map { leg ->
            val account = ensureAccount(leg.accountName, leg.accountType ?: AccountType.ASSET, leg.parentName)
            JournalLineEntity(
                entryId = 0,
                accountId = account.id,
                accountName = account.name,
                debitPaise = leg.debitPaise,
                creditPaise = leg.creditPaise,
                distJson = leg.dist?.let { DistCodec.encode(it, request.confidence) },
                qty = leg.qty,
                unit = leg.unit,
            )
        }
        val entryId = database.withTransaction {
            val id = dao.insertEntry(entry)
            dao.insertLines(resolvedLines.map { it.copy(entryId = id) })
            id
        }
        emitEntryOp(entry.copy(id = entryId), resolvedLines)
        return entryId
    }

    private suspend fun emitEntryOp(entry: JournalEntryEntity, lines: List<JournalLineEntity>) {
        val payload = OpsCodec.EntryPayload(
            uuid = entry.uuid,
            description = entry.description,
            wallTimeMillis = entry.wallTimeMillis,
            kind = entry.kind,
            confidence = entry.confidence,
            sourceEvidenceId = entry.sourceEvidenceId,
            lines = lines.map {
                OpsCodec.LinePayload(
                    account = it.accountName,
                    type = dao.accountByName(it.accountName)?.type ?: AccountType.ASSET,
                    parentName = dao.accountByName(it.accountName)?.parentName,
                    debitPaise = it.debitPaise,
                    creditPaise = it.creditPaise,
                    distJson = it.distJson,
                    qty = it.qty,
                    unit = it.unit,
                )
            },
        )
        database.opLogDao().insertOp(
            LedgerOpEntity(
                opId = entry.uuid,
                hlcWall = entry.hlcWall,
                hlcCounter = entry.hlcCounter,
                nodeId = entry.nodeId,
                type = OpType.POST_ENTRY,
                payloadJson = OpsCodec.encodeEntry(payload),
                appliedAtWall = System.currentTimeMillis(),
            ),
        )
    }

    // ------------------------------------------------------------------
    // CRDT application: idempotent re-apply of remote ops
    // ------------------------------------------------------------------

    suspend fun entryExists(uuid: String): Boolean = dao.entryCountByUuid(uuid) > 0

    /** Applies a remote account op. Idempotent by deterministic uuid/name. */
    suspend fun applyAccountOp(payload: OpsCodec.AccountPayload) {
        ensureAccount(payload.name, payload.type, payload.parentName)
    }

    /** Applies a remote entry op. Idempotent by uuid — insert-if-absent. */
    suspend fun applyEntryOp(payload: OpsCodec.EntryPayload): Boolean {
        if (dao.entryCountByUuid(payload.uuid) > 0) return false
        val entry = JournalEntryEntity(
            description = payload.description,
            wallTimeMillis = payload.wallTimeMillis,
            monoTimeMillis = 0,
            sourceEvidenceId = payload.sourceEvidenceId,
            uuid = payload.uuid,
            kind = payload.kind,
            confidence = payload.confidence,
            nodeId = "remote",
            hlcWall = 0,
            hlcCounter = 0,
        )
        val lines = payload.lines.map { l ->
            val account = ensureAccount(l.account, l.type, l.parentName)
            JournalLineEntity(
                entryId = 0,
                accountId = account.id,
                accountName = account.name,
                debitPaise = l.debitPaise,
                creditPaise = l.creditPaise,
                distJson = l.distJson,
                qty = l.qty,
                unit = l.unit,
            )
        }
        database.withTransaction {
            if (dao.entryCountByUuid(payload.uuid) == 0) {
                val id = dao.insertEntry(entry)
                dao.insertLines(lines.map { it.copy(entryId = id) })
            }
        }
        return true
    }

    fun entries(): Flow<List<EntryWithLines>> = dao.entriesWithLines()

    fun accounts(): Flow<List<AccountEntity>> = dao.accounts()

    /** Signed balance per account; positive = normal side (Dr for asset/expense, Cr otherwise). */
    suspend fun balances(): List<Balance> {
        val accounts = dao.allAccounts()
        val lines = dao.allLines()
        val byAccount = lines.groupBy { it.accountId }
        return accounts.map { account ->
            val legs = byAccount[account.id].orEmpty()
            val debits = legs.sumOf { it.debitPaise }
            val credits = legs.sumOf { it.creditPaise }
            val signed = if (account.type == AccountType.ASSET || account.type == AccountType.EXPENSE) {
                debits - credits
            } else {
                credits - debits
            }
            Balance(account, signed)
        }
    }

    suspend fun accountBalancePaise(name: String): Long =
        balances().firstOrNull { it.account.name == name }?.paise ?: 0

    /**
     * The double-entry invariant: Assets + Expenses = Liabilities + Equity + Income
     * (equivalently, Σ(debits) = Σ(credits) across every posted line).
     */
    suspend fun isBalanced(): Boolean {
        val bs = balances()
        val debitNormal = bs
            .filter { it.account.type == AccountType.ASSET || it.account.type == AccountType.EXPENSE }
            .sumOf { it.paise }
        val creditNormal = bs
            .filter { it.account.type != AccountType.ASSET && it.account.type != AccountType.EXPENSE }
            .sumOf { it.paise }
        return debitNormal == creditNormal
    }

    /** The PS demo scenario: opening capital, a wholesale slip, and Sharma ji's credit. */
    suspend fun postDemoScenario() {
        ensureSeeded()
        val cash = requireNotNull(account(ChartOfAccounts.CASH)) { "Cash account missing" }
        val capital = requireNotNull(account(ChartOfAccounts.CAPITAL)) { "Capital account missing" }
        val inventory = requireNotNull(account(ChartOfAccounts.INVENTORY)) { "Inventory account missing" }
        val sales = requireNotNull(account(ChartOfAccounts.SALES)) { "Sales account missing" }
        val sharma = ensureAccount(ChartOfAccounts.customerAccount("Sharma ji"), AccountType.ASSET, ChartOfAccounts.RECEIVABLES)

        post("Opening capital ₹5,000 in cash", listOf(
            Posting(cash.id, cash.name, debitPaise = 500_000),
            Posting(capital.id, capital.name, creditPaise = 500_000),
        ))
        post("Wholesale slip: 3 kg tomato ₹180", listOf(
            Posting(inventory.id, inventory.name, debitPaise = 18_000),
            Posting(cash.id, cash.name, creditPaise = 18_000),
        ))
        post("Sharma ji ka ₹40 jama + 7 plate poha @ ₹20 (credit)", listOf(
            Posting(sharma.id, sharma.name, debitPaise = 180_000),
            Posting(sales.id, sales.name, creditPaise = 180_000),
        ))
    }

    suspend fun clearAll() {
        database.withTransaction {
            dao.clearLines()
            dao.clearEntries()
            dao.clearAccounts()
            database.opLogDao().clear()
            database.syncConflictDao().clear()
            database.lexiconDao().clear()
            database.inventoryDao().clearMoves()
            database.inventoryDao().clearItems()
        }
        ensureSeeded()
    }
}

data class PostRequest(
    val description: String,
    val lines: List<UncertainLeg>,
    val sourceEvidenceId: Long? = null,
    val kind: EntryKind = EntryKind.NORMAL,
    val confidence: Double = 1.0,
    /** Deterministic uuid → idempotent posting (CRDT re-apply safety). */
    val uuid: String? = null,
)
