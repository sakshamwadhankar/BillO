package com.billo.ledger.core

import com.billo.ledger.data.db.AccountType
import com.billo.ledger.data.db.EntryKind
import com.billo.ledger.data.ledger.ChartOfAccounts
import com.billo.ledger.data.ledger.LedgerRepository
import com.billo.ledger.data.ledger.PostRequest
import com.billo.ledger.data.ledger.UncertainLeg
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** Normalized posterior over the three loss causes (sums to 1). */
data class CausePosterior(
    val spoilage: Double,
    val theft: Double,
    val personal: Double,
)

data class LossReport(
    val itemName: String,
    val expectedQty: Double,
    val countedQty: Double,
    /** Distribution of the physical loss quantity in the item's unit. */
    val lossQtyDist: Dist,
    val causes: CausePosterior,
    val covariates: Map<String, String>,
    val verdict: String,
    /** Paise actually posted as expense across the cause accounts. */
    val postedPaise: Long,
)

/**
 * Phase 4 loss attribution. A stock count disagrees with the book
 * quantity (which never recorded any losses); the shortfall gets a
 * probability distribution over spoilage / theft / personal use built
 * from environmental covariates (monsoon season, weekend, festival
 * proximity) and the shortfall's magnitude, then posts one balanced
 * LOSS entry splitting the value across the cause accounts.
 *
 * Idempotent: the physical correction is recorded as a
 * "loss-attributed" stock move, so re-running the same count yields no
 * further loss.
 */
class LossAttribution(
    private val inventory: InventoryLedger,
    private val ledger: LedgerRepository,
) {

    suspend fun attributeAndPost(
        counted: Map<String, Double>,
        nowWall: Long = System.currentTimeMillis(),
    ): List<LossReport> {
        val zone = Instant.ofEpochMilli(nowWall).atZone(ZoneOffset.UTC)
        val season = if (zone.monthValue in 6..9) "monsoon" else "dry"
        val spoilageBase = if (season == "monsoon") 0.55 else 0.35
        val theftBase = if (zone.dayOfWeek == java.time.DayOfWeek.FRIDAY ||
            zone.dayOfWeek == java.time.DayOfWeek.SATURDAY
        ) 0.45 else 0.35
        val dom = zone.dayOfMonth
        val festivalNear = abs(dom - 1) <= 2 || abs(dom - 15) <= 2 || abs(dom - 28) <= 2
        val festival = if (festivalNear) "near" else "none"
        val personalBase = 0.10 + if (festivalNear) 0.15 else 0.0
        val covariates = mapOf(
            "season" to season,
            "dayOfWeek" to zone.dayOfWeek.name,
            "festival" to festival,
        )

        val reports = ArrayList<LossReport>()

        for (name in counted.keys.sorted()) {
            val item = inventory.database.inventoryDao().itemByName(name) ?: continue
            if (item.kind != com.billo.ledger.data.db.ItemKind.RAW) continue
            val expected = inventory.stockOnHand(name)
            if (expected <= 0.0) continue
            val countedQty = counted[name] ?: continue
            val loss = expected - countedQty

            if (loss <= 0.05) {
                // No material shortfall — report for transparency, post nothing.
                reports.add(
                    LossReport(
                        itemName = name,
                        expectedQty = expected,
                        countedQty = countedQty,
                        lossQtyDist = Dist.point(0.0),
                        causes = CausePosterior(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0),
                        covariates = covariates,
                        verdict = "no material loss",
                        postedPaise = 0L,
                    ),
                )
                continue
            }

            // Covariate-based prior, updated by the shortfall's magnitude.
            var spoilage = spoilageBase
            var theft = theftBase
            var personal = personalBase
            val ratio = loss / expected
            if (ratio > 0.15) theft *= 1.5 // big chunk gone overnight smells like theft
            if (ratio < 0.05) spoilage *= 1.3 // tiny daily drip smells like spoilage
            val total = spoilage + theft + personal
            spoilage /= total
            theft /= total
            personal /= total
            val causes = CausePosterior(spoilage, theft, personal)

            val lossQtyDist = Dist.around(loss, max(loss * 0.10, 0.05))
            val lossValuePaise = (loss * item.unitCostPaise).roundToLong()

            var posted = 0L
            if (lossValuePaise > 0) {
                // Debit legs first; the credit is their exact sum to keep the entry balanced.
                val spoilagePaise = (causes.spoilage * lossValuePaise).roundToLong()
                val theftPaise = (causes.theft * lossValuePaise).roundToLong()
                val personalPaise = (causes.personal * lossValuePaise).roundToLong()
                val debitLegs = buildList {
                    if (spoilagePaise > 0) add(
                        UncertainLeg(
                            accountName = ChartOfAccounts.SPOILAGE,
                            accountType = AccountType.EXPENSE,
                            debitPaise = spoilagePaise,
                            dist = Dist.around(spoilagePaise.toDouble(), spoilagePaise * 0.25),
                        ),
                    )
                    if (theftPaise > 0) add(
                        UncertainLeg(
                            accountName = ChartOfAccounts.THEFT,
                            accountType = AccountType.EXPENSE,
                            debitPaise = theftPaise,
                            dist = Dist.around(theftPaise.toDouble(), theftPaise * 0.25),
                        ),
                    )
                    if (personalPaise > 0) add(
                        UncertainLeg(
                            accountName = ChartOfAccounts.DRAWINGS,
                            accountType = AccountType.EXPENSE,
                            debitPaise = personalPaise,
                            dist = Dist.around(personalPaise.toDouble(), personalPaise * 0.25),
                        ),
                    )
                }
                val debitTotal = debitLegs.sumOf { it.debitPaise }
                ledger.postEntry(
                    PostRequest(
                        description = "Loss attribution: %.2f kg %s (spoilage %d%%, theft %d%%, personal %d%%)".format(
                            loss, name,
                            (causes.spoilage * 100).roundToLong(),
                            (causes.theft * 100).roundToLong(),
                            (causes.personal * 100).roundToLong(),
                        ),
                        lines = debitLegs + UncertainLeg(
                            accountName = ChartOfAccounts.INVENTORY,
                            accountType = AccountType.ASSET,
                            creditPaise = debitTotal,
                            dist = Dist.around(debitTotal.toDouble(), debitTotal * 0.25),
                            qty = loss,
                            unit = item.unit,
                        ),
                        kind = EntryKind.LOSS,
                        confidence = 0.85,
                    ),
                )
                posted = debitTotal
            }

            // Physical correction so re-running the same count is a no-op.
            inventory.addMove(name, -loss, "loss-attributed", nowWall)

            val verdict = listOf(
                causes.spoilage to "spoilage",
                causes.theft to "theft",
                causes.personal to "personal",
            ).maxByOrNull { it.first }?.second ?: "personal"

            reports.add(
                LossReport(
                    itemName = name,
                    expectedQty = expected,
                    countedQty = countedQty,
                    lossQtyDist = lossQtyDist,
                    causes = causes,
                    covariates = covariates,
                    verdict = verdict,
                    postedPaise = posted,
                ),
            )
        }

        return reports
    }
}
