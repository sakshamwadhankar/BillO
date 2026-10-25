package com.billo.ledger.nlp

/**
 * On-device hint extraction for raw evidence. Runs the moment evidence
 * lands (voice/SMS/OCR) so the fusion engine gets structured hooks.
 */
object EvidenceHints {

    private fun rupees(paise: Long): String {
        val whole = paise / 100
        val frac = (paise % 100).toInt()
        return "₹$whole" + if (frac != 0) ".${frac.toString().padStart(2, '0')}" else ""
    }

    fun summarize(text: String): String? {
        val parts = mutableListOf<String>()
        val amounts = HindiNumerals.extractAmounts(text)
        // Money-like amounts: any value >= 10 could be rupees; small numbers may be quantities.
        amounts.filter { it >= 10 }.take(3).forEach { parts.add("amt≈${rupees(it * 100)}") }
        val quantities = UnitNormalizer.extract(text)
        quantities.take(3).forEach { parts.add("${trimNum(it.value)} ${it.unit}") }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
