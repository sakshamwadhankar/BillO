package com.billo.ledger.core

import com.billo.ledger.data.db.EvidenceChannel
import com.billo.ledger.nlp.HindiNumerals
import com.billo.ledger.nlp.UnitNormalizer

/** Economic event class produced by [BayesianResolver.resolve]. */
enum class EventKind { CREDIT_SALE, CASH_SALE, PURCHASE, REPAYMENT, UNKNOWN }

/**
 * One evidence item fully parsed: event class, people involved, money and
 * quantity mentions, a posterior over the true amount (paise) and the
 * calibrated confidence of the whole reading.
 */
data class ResolvedEvent(
    val kind: EventKind,
    val persons: List<String>,
    val moneyPaise: List<Long>,
    val quantities: List<UnitNormalizer.Quantity>,
    val amountPosterior: Dist?,
    val confidence: Double,
    val notes: List<String>,
)

/**
 * Deterministic Bayesian-style resolver for a single piece of evidence.
 * No RNG: uncertainty is always expressed as a [Dist] built with
 * deterministic grids (Dist.around), never sampled.
 */
object BayesianResolver {

    /** Per-channel reliability prior. */
    fun channelWeight(channel: EvidenceChannel): Double = when (channel) {
        EvidenceChannel.VOICE -> 0.9
        EvidenceChannel.OCR -> 0.85
        EvidenceChannel.SMS -> 0.95
        EvidenceChannel.TALLY -> 0.7
        EvidenceChannel.MANUAL -> 1.0
    }

    /** Priority order used to break kind-score ties (strongest first). */
    val KIND_PRIORITY: List<EventKind> = listOf(
        EventKind.CREDIT_SALE,
        EventKind.REPAYMENT,
        EventKind.PURCHASE,
        EventKind.CASH_SALE,
        EventKind.UNKNOWN,
    )

    private val CREDIT_KEYWORDS =
        listOf("jama", "udhaar", "udhari", "udhar", "credit", "lena", "dena", "on credit")
    private val REPAYMENT_KEYWORDS =
        listOf("diya", "wapas", "vapas", "repaid", "repay", "paid", "lautaya", "return")
    private val PURCHASE_KEYWORDS =
        listOf("liya", "kharida", "manga", "mangwa", "buy", "bought", "wholesale", "bill", "slip")
    private val CASH_SALE_KEYWORDS =
        listOf("mile", "mila", "cash", "bika", "bikri", "sold")

    private val KEYWORDS: Map<EventKind, List<String>> = linkedMapOf(
        EventKind.CREDIT_SALE to CREDIT_KEYWORDS,
        EventKind.REPAYMENT to REPAYMENT_KEYWORDS,
        EventKind.PURCHASE to PURCHASE_KEYWORDS,
        EventKind.CASH_SALE to CASH_SALE_KEYWORDS,
    )

    private val PERSON_PATTERN = Regex(
        """([A-Za-z][A-Za-z]+(?:\s+[A-Za-z][A-Za-z]+)?)\s+(?:ji|ka|ke|se|ne|ko)\b""",
    )

    /** Words that look like name+suffix matches but are business vocabulary. */
    private val NON_PERSONS = setOf(
        "bhai", "bhaiya", "jama", "udhaar", "udhari", "udhar", "credit",
        "cash", "dena", "lena", "liya", "diya", "wapas", "vapas", "bill",
        "slip", "total", "kilo", "plate", "lautaya", "kharida",
    )

    fun resolve(
        text: String,
        channel: EvidenceChannel,
        evidenceConfidence: Double,
        lexiconPriors: Map<String, Double> = emptyMap(),
    ): ResolvedEvent {
        val notes = mutableListOf<String>()
        val lower = text.lowercase()

        // ---- kind by keyword scores, ties broken by priority order ----
        val scores: Map<EventKind, Int> = KEYWORDS.mapValues { (_, keywords) ->
            keywords.sumOf { hits(lower, it) }
        }
        val bestScore = scores.values.maxOrNull() ?: 0
        val kind = if (bestScore == 0) {
            EventKind.UNKNOWN
        } else {
            // KEYWORDS is insertion-ordered == priority order, so the first
            // entry reaching the max count wins the tie.
            scores.entries.first { it.value == bestScore }.key
        }
        if (kind == EventKind.UNKNOWN) {
            notes.add("kind=UNKNOWN (no keyword match)")
        } else {
            val topKeyword = KEYWORDS.getValue(kind)
                .map { it to hits(lower, it) }
                .maxByOrNull { it.second }
            if (topKeyword != null && topKeyword.second > 0) {
                notes.add("kind=$kind (keyword '${topKeyword.first}' x${topKeyword.second})")
            }
            if (kind == EventKind.REPAYMENT && Regex("\\bdiya\\b").containsMatchIn(lower)) {
                notes.add(
                    "'diya' is ambiguous in Hinglish (gave vs. lamp) — acceptable heuristic, treated as repayment",
                )
            }
        }

        // ---- persons ----
        val persons = LinkedHashSet<String>()
        for (match in PERSON_PATTERN.findAll(lower)) {
            canonicalPerson(match.groupValues[1])?.let { persons.add(it) }
        }
        for (token in tokenize(lower)) {
            if (token != "ji" && token.endsWith("ji") && token.length >= 4) {
                canonicalPerson(token)?.let { persons.add(it) }
            }
        }
        if (persons.isEmpty() && lexiconPriors.isNotEmpty()) {
            val tokensInText = tokenize(lower).toSet()
            for ((token, prior) in lexiconPriors) {
                if (prior >= 0.8 && token in tokensInText &&
                    UnitNormalizer.canonical(token) == null &&
                    HindiNumerals.tokenValue(token) == null
                ) {
                    canonicalPerson(token)?.let {
                        persons.add(it)
                        notes.add("lexicon prior boosted '$token'")
                    }
                }
            }
        }
        persons.forEach { notes.add("person: $it") }

        // ---- money + quantities ----
        val rupeeAmounts = HindiNumerals.extractAmounts(text).filter { it >= 10 }
        val moneyPaise = rupeeAmounts.map { it * 100L }
        val quantities = UnitNormalizer.extract(text)

        // ---- amount posterior (deterministic grid, no RNG) ----
        val amountPosterior = rupeeAmounts.firstOrNull()?.let { a ->
            val spread = maxOf(a * 120.0, 5_000.0) * (1.6 - evidenceConfidence.coerceIn(0.0, 1.0))
            Dist.around(a * 100.0, spread)
        }
        if (amountPosterior != null) {
            val (lo, hi) = amountPosterior.ci90()
            notes.add("amount ₹${rupeeAmounts.first()} (90% CI ₹${lo.toLong() / 100}–₹${hi.toLong() / 100})")
        }

        // ---- confidence ----
        var confidence = channelWeight(channel) * evidenceConfidence.coerceIn(0.0, 1.0)
        if (kind == EventKind.UNKNOWN) confidence -= 0.25
        if (moneyPaise.isEmpty()) confidence -= 0.15
        if ((kind == EventKind.CREDIT_SALE || kind == EventKind.REPAYMENT) && persons.isEmpty()) {
            confidence -= 0.15
        }
        if (kind == EventKind.PURCHASE && quantities.isNotEmpty()) confidence += 0.05
        confidence = confidence.coerceIn(0.0, 1.0)

        return ResolvedEvent(
            kind = kind,
            persons = persons.toList(),
            moneyPaise = moneyPaise,
            quantities = quantities,
            amountPosterior = amountPosterior,
            confidence = confidence,
            notes = notes,
        )
    }

    private fun hits(lower: String, keyword: String): Int =
        Regex("\\b${Regex.escape(keyword)}\\b").findAll(lower).count()

    private fun tokenize(text: String): List<String> =
        Regex("[\\p{L}\\p{Nd}]+").findAll(text).map { it.value.lowercase() }.toList()

    /**
     * Normalizes a person mention: strip trailing " ji", lowercase, drop
     * bare bhai/bhaiya and business vocabulary, capitalize the first letter.
     */
    private fun canonicalPerson(raw: String): String? {
        var name = raw.trim().lowercase()
        if (name.endsWith(" ji")) {
            name = name.removeSuffix(" ji")
        } else if (name.endsWith("ji") && name.length > 3) {
            name = name.removeSuffix("ji")
        }
        name = name.trim()
        if (name.isEmpty() || name in NON_PERSONS) return null
        if (name.none { it.isLetter() }) return null
        return name.replaceFirstChar { it.uppercaseChar() }
    }
}
