package com.billo.ledger.core

import kotlin.math.max
import kotlin.random.Random

/** Monte-Carlo COGS for one finished item, plus each raw's mean contribution. */
data class CogsItem(
    val finishedItem: String,
    /** Distribution of cost per finished unit, paise. */
    val cogsPerUnitPaise: Dist,
    /** Raw item -> mean paise contribution per finished unit. */
    val components: Map<String, Double>,
)

/**
 * Phase 4 COGS engine: propagates the inferred burn-rate distribution
 * (which varies day to day) through purchase-cost uncertainty into a full
 * distribution of cost-of-goods per plate. No recipe table exists — the
 * burn rates come straight from [RecipeInference].
 */
class CogsEstimator(private val inventory: InventoryLedger) {

    suspend fun estimate(
        finishedItem: String = "Poha plate",
        samples: Int = 500,
        seed: Int = 7,
    ): Map<String, CogsItem> {
        val burn = RecipeInference(inventory).inferBurn(finishedItem)
        if (burn.estimates.isEmpty()) return emptyMap()

        val itemsByName = inventory.allItems().associateBy { it.name }
        val rng = Random(seed)

        // Cost per kg (or per litre -> per 1000 ml) with ±15% price uncertainty.
        val costPerThousand = burn.estimates.associate { est ->
            val unitCost = itemsByName[est.rawItem]?.unitCostPaise ?: 0L
            est.rawItem to Dist.around(
                unitCost.toDouble(),
                max(unitCost * 0.15, 500.0),
            )
        }

        val totals = ArrayList<Double>(samples)
        val componentSums = HashMap<String, Double>()

        for (s in 0 until samples) {
            var cogsSample = 0.0
            for (est in burn.estimates) {
                val grams = est.gramsPerUnit.sample(rng)
                val paisePerKg = costPerThousand.getValue(est.rawItem).sample(rng)
                val contribution = grams / 1000.0 * paisePerKg
                cogsSample += contribution
                componentSums[est.rawItem] = (componentSums[est.rawItem] ?: 0.0) + contribution
            }
            totals.add(cogsSample)
        }

        val components = componentSums.mapValues { it.value / samples }
        return mapOf(
            finishedItem to CogsItem(
                finishedItem = finishedItem,
                cogsPerUnitPaise = Dist.fromSamples(totals),
                components = components,
            ),
        )
    }
}
