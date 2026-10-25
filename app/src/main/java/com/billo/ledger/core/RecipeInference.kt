package com.billo.ledger.core

/** Is the vendor's recent portion size drifting away from the month's average? */
data class PortionDrift(
    val finishedItem: String,
    val rawItem: String,
    val recentPerUnit: Double,
    val overallPerUnit: Double,
    /** (recent - overall) / overall * 100. Positive = heavier portions lately. */
    val driftPct: Double,
)

/** Inferred burn rate of one raw ingredient per one finished unit sold. */
data class BurnEstimate(
    val rawItem: String,
    val finishedItem: String,
    val rawUnit: String,
    /** grams per plate (ml for litre-unit raws) — a full distribution, not a point. */
    val gramsPerUnit: Dist,
)

data class BurnReport(
    val estimates: List<BurnEstimate>,
    val portionDrift: List<PortionDrift>,
    val note: String,
)

/**
 * ZERO-SHOT RECIPE INFERENCE — deduce ingredient-per-plate from recorded
 * burn behaviour alone. There is NO Bill of Materials anywhere in the
 * system: the recipe is reverse-engineered by dividing daily raw
 * consumption by daily finished-item sales.
 */
class RecipeInference(private val inventory: InventoryLedger) {

    suspend fun inferBurn(finishedItem: String = "Poha plate"): BurnReport {
        val dayMs = 86_400_000L
        val moves = inventory.database.inventoryDao().allMoves()

        // Daily finished-item sales: negative deltas = plates sold.
        val soldByDay = HashMap<Long, Double>()
        moves.filter { it.itemName == finishedItem && it.deltaQty < 0 }.forEach { move ->
            val day = move.wallTimeMillis.floorDiv(dayMs)
            soldByDay[day] = (soldByDay[day] ?: 0.0) - move.deltaQty
        }

        // Daily raw consumption, strictly only vendor-recorded daily consumption.
        val consumedByDay = HashMap<Pair<Long, String>, Double>()
        moves.filter { it.reason == "daily-consumption" && it.deltaQty < 0 }.forEach { move ->
            val day = move.wallTimeMillis.floorDiv(dayMs)
            val key = day to move.itemName
            consumedByDay[key] = (consumedByDay[key] ?: 0.0) - move.deltaQty
        }
        val rawNames = moves
            .filter { it.reason == "daily-consumption" }
            .map { it.itemName }
            .distinct()
            .sorted()
        val itemsByName = inventory.allItems().associateBy { it.name }

        val estimates = ArrayList<BurnEstimate>(rawNames.size)
        val drifts = ArrayList<PortionDrift>(rawNames.size)

        for (raw in rawNames) {
            val unit = itemsByName[raw]?.unit ?: "kg"
            // kg -> g, litre -> ml: the same x1000 factor either way.
            val validDays = soldByDay.entries
                .filter { it.value >= 5.0 }
                .map { it.key }
                .sorted()
            val samples = ArrayList<Double>(validDays.size)
            for (day in validDays) {
                val consumed = consumedByDay[day to raw] ?: 0.0
                if (consumed > 0.0) {
                    samples.add(consumed * 1000.0 / soldByDay.getValue(day))
                }
            }
            if (samples.isEmpty()) continue
            estimates.add(
                BurnEstimate(
                    rawItem = raw,
                    finishedItem = finishedItem,
                    rawUnit = unit,
                    gramsPerUnit = Dist.fromSamples(samples),
                ),
            )

            val overall = samples.mean()
            val recent = samples.takeLast(7).mean()
            val driftPct = if (overall > 0.0) (recent - overall) / overall * 100.0 else 0.0
            drifts.add(
                PortionDrift(
                    finishedItem = finishedItem,
                    rawItem = raw,
                    recentPerUnit = recent,
                    overallPerUnit = overall,
                    driftPct = driftPct,
                ),
            )
        }

        return BurnReport(
            estimates = estimates,
            portionDrift = drifts,
            note = "Zero-shot: no BOM — burn rates inferred from recorded consumption vs finished-item sales only.",
        )
    }

    private fun List<Double>.mean(): Double =
        if (isEmpty()) 0.0 else sum() / size
}
