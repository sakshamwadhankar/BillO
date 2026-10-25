package com.billo.ledger.core

import com.billo.ledger.data.ledger.ChartOfAccounts
import com.billo.ledger.data.ledger.LedgerRepository
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * The end of the evaluation chain: true accrual profit vs what the cash
 * box says. Cash-on-hand alone hides three things — credit sales sitting
 * in receivables, the true COGS behind every plate, and the losses the
 * vendor never noticed. This report surfaces all three.
 */
data class AccrualReport(
    val cashOnHandPaise: Long,
    val receivablesPaise: Long,
    /** Distribution of cash profit from operations (cash in - cash out, ex-owner injections). */
    val cashProfitPaise: Dist,
    /** Booked revenue plus the uncertain collection of receivables. */
    val accrualRevenuePaise: Dist,
    /** True COGS from inferred burn rates, scaled to plates actually sold. */
    val cogsPaise: Dist,
    /** Accrual revenue - true COGS - recorded losses. */
    val accrualProfitPaise: Dist,
    /** Spoilage + theft + personal drawings expense balances. */
    val recordedLossPaise: Long,
    val note: String,
)

class AccrualReporter(
    private val ledger: LedgerRepository,
    private val inventory: InventoryLedger,
) {

    suspend fun report(
        finishedItem: String = "Poha plate",
        seed: Int = 11,
    ): AccrualReport {
        // Read every input from the ledger ONCE, by name/parent — never by
        // fragile string prefixes on display names.
        val all = ledger.balances()
        fun balanceOf(name: String): Long = all.firstOrNull { it.account.name == name }?.paise ?: 0

        val cashOnHand = balanceOf(ChartOfAccounts.CASH)
        // Child accounts of Receivables = implicit customer credit outstanding.
        val receivables = all
            .filter { it.account.parentName == ChartOfAccounts.RECEIVABLES }
            .sumOf { it.paise }
        val revenuePaise = balanceOf(ChartOfAccounts.SALES)
        val recordedLoss = balanceOf(ChartOfAccounts.SPOILAGE) +
            balanceOf(ChartOfAccounts.THEFT) +
            balanceOf(ChartOfAccounts.DRAWINGS)
        val capital = balanceOf(ChartOfAccounts.CAPITAL)
        val cashFlowPaise = cashOnHand - capital

        // True COGS: inferred burn-rate cost per plate x plates actually sold.
        val soldPlates = inventory.movesFor(finishedItem)
            .filter { it.deltaQty < 0 }
            .sumOf { -it.deltaQty }
        val cogsPerUnit = CogsEstimator(inventory).estimate(finishedItem, seed = seed)[finishedItem]
            ?.cogsPerUnitPaise ?: Dist.point(0.0)

        // Deterministic Monte-Carlo over the uncertain components: receivable
        // collection 90–110%, COGS from the burn×cost distribution.
        val rng = Random(seed)
        val samples = 2000
        val revenueSamples = ArrayList<Double>(samples)
        val profitSamples = ArrayList<Double>(samples)
        repeat(samples) {
            val collected = receivables.toDouble() * (0.90 + 0.20 * rng.nextDouble())
            val cogs = cogsPerUnit.sample(rng) * soldPlates
            revenueSamples.add(revenuePaise + collected)
            profitSamples.add(revenuePaise + collected - cogs - recordedLoss)
        }

        val cashProfit = Dist.around(
            cashFlowPaise.toDouble(),
            max(abs(cashFlowPaise) * 0.03, 2000.0),
        )

        return AccrualReport(
            cashOnHandPaise = cashOnHand,
            receivablesPaise = receivables,
            cashProfitPaise = cashProfit,
            accrualRevenuePaise = Dist.fromSamples(revenueSamples),
            cogsPaise = Dist.fromSamples(
                List(samples) { 0.0 }.map { cogsPerUnit.sample(rng) * soldPlates },
            ),
            accrualProfitPaise = Dist.fromSamples(profitSamples),
            recordedLossPaise = recordedLoss,
            note = "Accrual profit recognizes credit sales (receivables), true COGS from inferred burn rates, and attributed losses. Cash-on-hand ignores all three.",
        )
    }
}
