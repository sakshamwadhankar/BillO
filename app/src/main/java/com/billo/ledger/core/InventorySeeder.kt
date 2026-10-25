package com.billo.ledger.core

import com.billo.ledger.data.db.ItemKind
import com.billo.ledger.data.ledger.ChartOfAccounts
import com.billo.ledger.data.ledger.LedgerRepository
import com.billo.ledger.data.ledger.Posting
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToLong
import kotlin.random.Random

/** Ground-truth losses that happened PHYSICALLY but were never recorded anywhere. */
data class GroundTruthLoss(
    val spoilageKg: Map<String, Double>,
    val theftKg: Map<String, Double>,
    val personalKg: Map<String, Double>,
)

data class SeedSummary(
    val days: Int,
    val soldPlates: Int,
    val revenuePaise: Long,
    val cogsPaise: Long,
    /** Raw item -> true grams (or ml) consumed per finished plate. */
    val trueBurnGramsPerPlate: Map<String, Double>,
    val loss: GroundTruthLoss,
    /** Total value of all losses at purchase cost. */
    val lossValuePaise: Long,
    /** revenue - cogs. */
    val profitBeforeLossPaise: Long,
    /** revenue - cogs - lossValue. */
    val profitAfterLossPaise: Long,
)

/**
 * The evaluation harness's ground-truth generator: a physically-real month
 * of street-vendor data, fully deterministic (fixed seeds per day).
 *
 * Key property: [stock moves][com.billo.ledger.data.db.StockMoveEntity]
 * record ONLY what a vendor would naturally write down — purchases and
 * per-day raw consumption at the TRUE burn rates. Losses (spoilage /
 * theft / personal use) happen physically but are never recorded; they
 * surface later only via a stock count and get attributed in
 * [LossAttribution]. Sales revenue and purchases are posted to the ledger.
 */
object InventorySeeder {

    const val PRICE_PER_PLATE_PAISE = 2_000L // ₹20
    private const val DAY_MS = 24L * 3600 * 1000

    // True recipe (zero-shot target): Rice 120 g, Onion 40 g, Oil 8 ml per plate.
    private val TRUE_BURN_GRAMS = linkedMapOf(
        "Rice" to 120.0,
        "Onion" to 40.0,
        "Oil" to 8.0,
    )
    private val UNITS = mapOf("Rice" to "kg", "Onion" to "kg", "Oil" to "litre")
    private val BASE_COST_PAISE = mapOf("Rice" to 4_000L, "Onion" to 2_500L, "Oil" to 15_000L)
    private val BUY_QTY = mapOf("Rice" to 10.0, "Onion" to 8.0, "Oil" to 3.0)
    private val LOW_STOCK = mapOf("Rice" to 4.0, "Onion" to 4.0, "Oil" to 1.0)

    suspend fun seedDemo(
        ledger: LedgerRepository,
        inventory: InventoryLedger,
        startWall: Long = System.currentTimeMillis() - 30L * 24 * 3600 * 1000,
    ): SeedSummary {
        // Fresh demo: wipe prior inventory demo data.
        inventory.database.inventoryDao().clearMoves()
        inventory.database.inventoryDao().clearItems()

        // Base accounts must exist with correct types before any posting.
        ledger.ensureSeeded()

        // Register items.
        inventory.addItem("Rice", ItemKind.RAW, "kg", BASE_COST_PAISE.getValue("Rice"))
        inventory.addItem("Onion", ItemKind.RAW, "kg", BASE_COST_PAISE.getValue("Onion"))
        inventory.addItem("Oil", ItemKind.RAW, "litre", BASE_COST_PAISE.getValue("Oil"))
        inventory.addItem("Poha plate", ItemKind.FINISHED, "plate", 0L)

        var soldPlates = 0
        var revenuePaise = 0L
        val consumedTotals = linkedMapOf("Rice" to 0.0, "Onion" to 0.0, "Oil" to 0.0)
        val stock = linkedMapOf("Rice" to 0.0, "Onion" to 0.0, "Oil" to 0.0)
        val unitCost = BASE_COST_PAISE.toMutableMap()
        val spoilage = linkedMapOf("Rice" to 0.0, "Onion" to 0.0, "Oil" to 0.0)
        val theft = linkedMapOf("Rice" to 0.0, "Onion" to 0.0, "Oil" to 0.0)
        val personal = linkedMapOf("Rice" to 0.0, "Onion" to 0.0, "Oil" to 0.0)

        for (d in 0 until 30) {
            val dayWall = startWall + d * DAY_MS
            val date = Instant.ofEpochMilli(dayWall).atZone(ZoneOffset.UTC)
            val dow = date.dayOfWeek
            val weekendBonus = if (dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) 8 else 0
            val plates = (20 + weekendBonus + (Random(42 + d).nextInt(7) - 3)).coerceAtLeast(12)

            // --- Sales: revenue posted to ledger, plates leave stock. ---
            val dayRevenue = plates * PRICE_PER_PLATE_PAISE
            ledger.post(
                "Day ${d + 1} sales: $plates plates",
                listOf(
                    Posting(0, ChartOfAccounts.CASH, debitPaise = dayRevenue),
                    Posting(0, ChartOfAccounts.SALES, creditPaise = dayRevenue),
                ),
            )
            inventory.addMove("Poha plate", -plates.toDouble(), "sale", dayWall)
            soldPlates += plates
            revenuePaise += dayRevenue

            // --- Raw consumption, recorded at true burn + small noise (±5%). ---
            val noise = 1.0 + (Random(1000 + d).nextDouble() - 0.5) * 0.10
            val riceKg = plates * TRUE_BURN_GRAMS.getValue("Rice") / 1000.0 * noise
            val onionKg = plates * TRUE_BURN_GRAMS.getValue("Onion") / 1000.0 * noise
            val oilL = plates * TRUE_BURN_GRAMS.getValue("Oil") / 1000.0 * noise
            inventory.addMove("Rice", -riceKg, "daily-consumption", dayWall)
            inventory.addMove("Onion", -onionKg, "daily-consumption", dayWall)
            inventory.addMove("Oil", -oilL, "daily-consumption", dayWall)
            consumedTotals["Rice"] = consumedTotals.getValue("Rice") + riceKg
            consumedTotals["Onion"] = consumedTotals.getValue("Onion") + onionKg
            consumedTotals["Oil"] = consumedTotals.getValue("Oil") + oilL
            stock["Rice"] = stock.getValue("Rice") - riceKg
            stock["Onion"] = stock.getValue("Onion") - onionKg
            stock["Oil"] = stock.getValue("Oil") - oilL

            // --- Purchases: vendor buys when running low (stock = purchases - consumption). ---
            for (raw in listOf("Rice", "Onion", "Oil")) {
                if (stock.getValue(raw) < LOW_STOCK.getValue(raw)) {
                    val qty = BUY_QTY.getValue(raw)
                    val cost = unitCost.getValue(raw)
                    val amount = (qty * cost).roundToLong()
                    inventory.addMove(raw, qty, "purchase", dayWall)
                    ledger.post(
                        "Purchase: $qty ${UNITS.getValue(raw)} of $raw",
                        listOf(
                            Posting(0, ChartOfAccounts.INVENTORY, debitPaise = amount),
                            Posting(0, ChartOfAccounts.CASH, creditPaise = amount),
                        ),
                    )
                    stock[raw] = stock.getValue(raw) + qty
                    // Market price drift: ±3%.
                    val newCost = (cost * (1.0 + (Random(2000 + d).nextDouble() - 0.5) * 0.06)).roundToLong()
                    if (newCost > 0) {
                        unitCost[raw] = newCost
                        inventory.database.inventoryDao().updateUnitCost(raw, newCost)
                    }
                }
            }

            // --- UNRECORDED physical losses (never addMove, never post). ---
            if (d % 5 == 4) {
                spoilage["Rice"] = spoilage.getValue("Rice") + 0.5
                spoilage["Onion"] = spoilage.getValue("Onion") + 0.3
            }
            if (d == 12) {
                theft["Rice"] = theft.getValue("Rice") + 2.0
            }
            if (d % 7 == 6) {
                personal["Onion"] = personal.getValue("Onion") + 0.4
            }
        }

        // --- Ground truth aggregates (at base purchase cost). ---
        var cogsPaise = 0L
        for (raw in listOf("Rice", "Onion", "Oil")) {
            cogsPaise += (consumedTotals.getValue(raw) * BASE_COST_PAISE.getValue(raw)).roundToLong()
        }
        var lossValuePaise = 0L
        for (raw in listOf("Rice", "Onion", "Oil")) {
            val lostKg = spoilage.getValue(raw) + theft.getValue(raw) + personal.getValue(raw)
            lossValuePaise += (lostKg * BASE_COST_PAISE.getValue(raw)).roundToLong()
        }

        return SeedSummary(
            days = 30,
            soldPlates = soldPlates,
            revenuePaise = revenuePaise,
            cogsPaise = cogsPaise,
            trueBurnGramsPerPlate = TRUE_BURN_GRAMS.toMap(),
            loss = GroundTruthLoss(
                spoilageKg = spoilage.toMap(),
                theftKg = theft.toMap(),
                personalKg = personal.toMap(),
            ),
            lossValuePaise = lossValuePaise,
            profitBeforeLossPaise = revenuePaise - cogsPaise,
            profitAfterLossPaise = revenuePaise - cogsPaise - lossValuePaise,
        )
    }
}
