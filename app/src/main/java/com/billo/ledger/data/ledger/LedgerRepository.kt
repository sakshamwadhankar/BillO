package com.billo.ledger.data.ledger

import android.os.SystemClock
import com.billo.ledger.data.db.AccountEntity
import com.billo.ledger.data.db.AccountType
import com.billo.ledger.data.db.EntryWithLines
import com.billo.ledger.data.db.JournalEntryEntity
import com.billo.ledger.data.db.JournalLineEntity
import com.billo.ledger.data.db.LedgerDao
import kotlinx.coroutines.flow.Flow

/** One leg of a double entry. Exactly one of debit/credit must be positive. */
data class Posting(
    val accountId: Long,
    val accountName: String,
    val debitPaise: Long = 0,
    val creditPaise: Long = 0,
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
        SPOILAGE to AccountType.EXPENSE,   // loss attribution targets, Phase 4
        THEFT to AccountType.EXPENSE,
        DRAWINGS to AccountType.EXPENSE,
    )

    fun customerAccount(customer: String) = "Receivables · $customer"
}

/**
 * The double-entry core. Every post() is atomic and refused unless
 * sum(debits) == sum(credits) > 0 and each leg is a pure debit or a
 * pure credit — the "perfect balance" invariant the PS demands, held
 * from the very first entry so sync (Phase 5) can rely on it.
 */
class LedgerRepository(private val dao: LedgerDao) {

    suspend fun ensureSeeded() {
        if (dao.accountCount() == 0) {
            ChartOfAccounts.BASE_ACCOUNTS.forEach { (name, type) ->
                dao.insertAccount(AccountEntity(name = name, type = type))
            }
        }
    }

    suspend fun account(name: String): AccountEntity? = dao.accountByName(name)

    suspend fun ensureAccount(name: String, type: AccountType, parentName: String? = null): AccountEntity =
        dao.accountByName(name) ?: run {
            val entity = AccountEntity(name = name, type = type, parentName = parentName)
            entity.copy(id = dao.insertAccount(entity))
        }

    suspend fun post(
        description: String,
        lines: List<Posting>,
        sourceEvidenceId: Long? = null,
    ): Long {
        require(lines.size >= 2) { "A double entry needs at least two legs" }
        val debits = lines.sumOf { it.debitPaise }
        val credits = lines.sumOf { it.creditPaise }
        require(debits > 0 && debits == credits) {
            "Unbalanced entry refused: Dr $debits paise != Cr $credits paise"
        }
        lines.forEach {
            require((it.debitPaise == 0L) xor (it.creditPaise == 0L)) {
                "Leg ${it.accountName} must be exactly one of debit/credit"
            }
        }
        val entryId = dao.insertEntry(
            JournalEntryEntity(
                description = description,
                wallTimeMillis = System.currentTimeMillis(),     // hostile clock, display only
                monoTimeMillis = SystemClock.elapsedRealtime(),   // monotonic per boot
                sourceEvidenceId = sourceEvidenceId,
            )
        )
        dao.insertLines(
            lines.map {
                JournalLineEntity(
                    entryId = entryId,
                    accountId = it.accountId,
                    accountName = it.accountName,
                    debitPaise = it.debitPaise,
                    creditPaise = it.creditPaise,
                )
            }
        )
        return entryId
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
        // "Bhai 3 kilo tamatar liya 180 mein" — wholesale slip, no BOM
        post("Wholesale slip: 3 kg tomato ₹180", listOf(
            Posting(inventory.id, inventory.name, debitPaise = 18_000),
            Posting(cash.id, cash.name, creditPaise = 18_000),
        ))
        // "Sharma ji ka 40 jama" + "7 plate poha de" — implicit customer credit
        post("Sharma ji ka ₹40 jama + 7 plate poha @ ₹20 (credit)", listOf(
            Posting(sharma.id, sharma.name, debitPaise = 180_000),
            Posting(sales.id, sales.name, creditPaise = 180_000),
        ))
    }

    suspend fun clearAll() {
        dao.clearLines()
        dao.clearEntries()
        dao.clearAccounts()
        ensureSeeded()
    }
}
