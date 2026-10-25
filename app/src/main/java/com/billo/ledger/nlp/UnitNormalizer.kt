package com.billo.ledger.nlp

/**
 * Non-standard, fluctuating commerce units → canonical unit.
 * Phase 1 baseline: exact alias tables. The mid-event Lexicon Drop
 * (Phase 2) will add unknown-unit learning on top of this.
 */
object UnitNormalizer {

    data class Quantity(val value: Double, val unit: String, val rawSpan: String)

    private val ALIASES: Map<String, String> = mapOf(
        "kilo" to "kg", "kg" to "kg", "kgs" to "kg", "kilos" to "kg",
        "kilogram" to "kg", "kilograms" to "kg",
        "gram" to "g", "grams" to "g", "gms" to "g", "gm" to "g", "g" to "g",
        "litre" to "litre", "liter" to "litre", "litres" to "litre", "l" to "litre",
        "plate" to "plate", "plates" to "plate",
        "darjan" to "darjan", "dazen" to "darjan", "dozen" to "darjan",
        "packet" to "packet", "packets" to "packet", "pkt" to "packet",
        "piece" to "piece", "pieces" to "piece", "pcs" to "piece", "pc" to "piece", "nos" to "piece",
        "bottle" to "bottle", "bottles" to "bottle",
        "glass" to "glass", "glasses" to "glass",
        "pav" to "pav", "pao" to "pav",
    )

    fun canonical(token: String): String? = ALIASES[token]

    /** Extracts "<number> <unit>" spans from free text: "3 kilo tamatar" -> 3 kg. */
    fun extract(text: String): List<Quantity> {
        val tokens = text.lowercase().replace(Regex("[,;]"), " ").split(Regex("\\s+"))
        val out = mutableListOf<Quantity>()
        var i = 0
        while (i < tokens.size - 1) {
            val value = HindiNumerals.tokenValue(tokens[i])
            val unit = canonical(tokens[i + 1])
            if (value != null && unit != null) {
                out.add(Quantity(value, unit, "${tokens[i]} ${tokens[i + 1]}"))
                i += 2
            } else {
                i += 1
            }
        }
        return out
    }
}
