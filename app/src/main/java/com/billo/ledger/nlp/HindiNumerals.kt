package com.billo.ledger.nlp

/**
 * Hindi/Hinglish numeral parser (ported from the MunimAI Python engine).
 *
 * Converts spoken numeral expressions to integer values:
 *   "dedh lakh" -> 150000    "dhai hazaar" -> 2500
 *   "sawa do sau" -> 225     "paune teen sau" -> 275
 *   "saadhe paanch sau" -> 550   "pachees hazaar" -> 25000
 *   "2 lakh" -> 200000       "Rs 5000" -> 5000
 */
object HindiNumerals {

    private val UNITS: Map<String, Long> = mapOf(
        "zero" to 0L, "sifar" to 0L, "shunya" to 0L,
        "ek" to 1L, "do" to 2L, "teen" to 3L, "char" to 4L, "paanch" to 5L,
        "chhe" to 6L, "cheh" to 6L, "saat" to 7L, "aath" to 8L, "nau" to 9L, "das" to 10L,
        "gyarah" to 11L, "baarah" to 12L, "terah" to 13L, "chaudah" to 14L,
        "pandrah" to 15L, "solah" to 16L, "satrah" to 17L, "atharah" to 18L, "unnis" to 19L,
        "bees" to 20L, "ikkis" to 21L, "bais" to 22L, "teis" to 23L, "chaubis" to 24L,
        "pachees" to 25L, "chhabbis" to 26L, "sattais" to 27L, "atthaais" to 28L, "untees" to 29L,
        "tees" to 30L, "ikattees" to 31L, "battees" to 32L, "taintees" to 33L, "chauntees" to 34L,
        "paintees" to 35L, "chhattees" to 36L, "saintees" to 37L, "adtees" to 38L, "untaalees" to 39L,
        "chaalees" to 40L, "iktaalees" to 41L, "bayaalees" to 42L, "taintaalees" to 43L,
        "chavaalees" to 44L, "paintaalees" to 45L, "chhiyaalees" to 46L,
        "saintaalees" to 47L, "adtaalees" to 48L, "unchaas" to 49L,
        "pachaas" to 50L, "ikyaavan" to 51L, "baavan" to 52L, "tirpan" to 53L, "chauvan" to 54L,
        "pachpan" to 55L, "chhappan" to 56L, "sattaavan" to 57L, "atthaavan" to 58L, "unsath" to 59L,
        "saath" to 60L, "iksath" to 61L, "baasath" to 62L, "tirsath" to 63L, "chausath" to 64L,
        "painsath" to 65L, "chhiyasath" to 66L, "sarsath" to 67L, "adsath" to 68L, "unhattar" to 69L,
        "sattar" to 70L, "ikattar" to 71L, "bahattar" to 72L, "tihattar" to 73L, "chauhattar" to 74L,
        "pachattar" to 75L, "chhi-hattar" to 76L, "chhibhattar" to 76L, "satattar" to 77L,
        "athhattar" to 78L, "unnaasi" to 79L,
        "assi" to 80L, "ikyaasi" to 81L, "bayaasi" to 82L, "tiraasi" to 83L, "chauraasi" to 84L,
        "pachaasi" to 85L, "chhiyaasi" to 86L, "sattaasi" to 87L, "atthaasi" to 88L, "navaasi" to 89L,
        "nabbe" to 90L, "ikyaanve" to 91L, "baanve" to 92L, "tiraanve" to 93L, "chauraanve" to 94L,
        "pachaanve" to 95L, "chhiyaanve" to 96L, "sattaanve" to 97L, "atthaanve" to 98L, "ninyaanve" to 99L,
        "sau" to 100L, "so" to 100L,
    )

    private val MULTIPLIERS: Map<String, Long> = mapOf(
        "sau" to 100L, "so" to 100L,
        "hazaar" to 1_000L, "hazar" to 1_000L, "hajaar" to 1_000L,
        "lakh" to 100_000L, "lac" to 100_000L, "laakh" to 100_000L,
        "crore" to 10_000_000L, "karod" to 10_000_000L, "caror" to 10_000_000L,
    )

    // dedh = 1.5, dhai = 2.5, sawa = +0.25, paune = -0.25, saadhe = +0.5
    private val FRACTIONS: Map<String, Double> = mapOf(
        "dedh" to 1.5,
        "dhai" to 2.5, "dhaai" to 2.5, "adhai" to 2.5,
        "sawa" to 1.25, "savaa" to 1.25,
        "paune" to 0.75, "pone" to 0.75, "pauna" to 0.75,
        "saadhe" to 0.5, "saade" to 0.5, "sade" to 0.5, "sadhe" to 0.5,
    )

    private val SAADHE = setOf("saadhe", "saade", "sade", "sadhe")
    private val SAWA = setOf("sawa", "savaa")
    private val PAUNE = setOf("paune", "pone", "pauna")

    private val CURRENCY = Regex("""(?i)(?:rs\.?|rupaye|rupiya|rupees?|rupaiye|₹)\s*""")

    private fun normalize(text: String): String =
        CURRENCY.replace(text.lowercase().trim(), "").replace(Regex("\\s+"), " ").trim()

    /** Value of a single token if it is numeric on its own ("3", "do", "dedh"). */
    fun tokenValue(token: String): Double? {
        val digits = token.replace(",", "")
        if (digits.isNotEmpty() && digits.all { it.isDigit() }) return digits.toDouble()
        UNITS[token]?.let { return it.toDouble() }
        FRACTIONS[token]?.let { return it }
        MULTIPLIERS[token]?.let { return it.toDouble() }
        return null
    }

    private fun isNumericToken(token: String): Boolean =
        token.replace(",", "").isNotEmpty() && token.replace(",", "").all { it.isDigit() }

    private fun readNumber(tokens: List<String>, start: Int): Pair<Long?, Int> {
        if (start >= tokens.size) return null to 0
        val token = tokens[start]
        if (isNumericToken(token)) return token.replace(",", "").toLong() to 1
        UNITS[token]?.let { return it to 1 }
        return null to 0
    }

    private fun parseTokens(tokens: List<String>): Long? {
        if (tokens.isEmpty()) return null
        var total = 0L
        var i = 0
        val n = tokens.size

        while (i < n) {
            val token = tokens[i]

            if (FRACTIONS.containsKey(token)) {
                val frac = FRACTIONS.getValue(token)
                i += 1
                if (token in SAADHE) {
                    val (base, consumed) = readNumber(tokens, i)
                    if (base != null) {
                        i += consumed
                        if (i < n && MULTIPLIERS.containsKey(tokens[i])) {
                            total += ((base + 0.5) * MULTIPLIERS.getValue(tokens[i])).toLong()
                            i += 1
                        } else {
                            total += (base + 0.5).toLong()
                        }
                    } else if (i < n && MULTIPLIERS.containsKey(tokens[i])) {
                        total += (1.5 * MULTIPLIERS.getValue(tokens[i])).toLong()
                        i += 1
                    }
                    continue
                }
                if (i < n && MULTIPLIERS.containsKey(tokens[i])) {
                    total += (frac * MULTIPLIERS.getValue(tokens[i])).toLong()
                    i += 1
                    continue
                }
                val (base, consumed) = readNumber(tokens, i)
                if (base != null) {
                    i += consumed
                    if (i < n && MULTIPLIERS.containsKey(tokens[i])) {
                        val mult = MULTIPLIERS.getValue(tokens[i])
                        total += when {
                            token in SAWA -> ((base + 0.25) * mult).toLong()
                            token in PAUNE -> ((base - 0.25) * mult).toLong()
                            else -> (frac * base * mult).toLong()
                        }
                        i += 1
                    } else {
                        total += when {
                            token in SAWA -> (base * 1.25).toLong()
                            token in PAUNE -> (base * 0.75).toLong()
                            else -> (frac * base).toLong()
                        }
                    }
                }
                continue
            }

            val (baseVal, consumed) = readNumber(tokens, i)
            if (baseVal != null) {
                i += consumed
                if (i < n && MULTIPLIERS.containsKey(tokens[i])) {
                    total += baseVal * MULTIPLIERS.getValue(tokens[i])
                    i += 1
                } else {
                    total += baseVal
                }
                continue
            }

            if (MULTIPLIERS.containsKey(token)) {
                total += MULTIPLIERS.getValue(token)
                i += 1
                continue
            }

            i += 1
        }
        return if (total > 0) total else null
    }

    /** Parses a whole phrase as one amount, or null. */
    fun parseHindiAmount(raw: String): Long? {
        val text = normalize(raw)
        if (text.isEmpty()) return null
        val cleaned = text.replace(",", "").replace(" ", "")
        if (cleaned.isNotEmpty() && cleaned.all { it.isDigit() }) return cleaned.toLong()
        return parseTokens(text.split(" "))
    }

    /**
     * Finds every numeric span in free speech text, e.g.
     * "3 kilo tamatar liya 180 mein" -> [3, 180].
     * Numeric runs are parsed with the full Hindi numeral grammar so
     * "dedh sau" and "180" both resolve.
     */
    fun extractAmounts(text: String): List<Long> {
        val tokens = normalize(text).split(" ")
        val amounts = mutableListOf<Long>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val numericStart = isNumericToken(token) ||
                UNITS.containsKey(token) || FRACTIONS.containsKey(token)
            if (!numericStart) {
                i += 1
                continue
            }
            // collect the longest numeric run
            var end = i + 1
            while (end < tokens.size) {
                val t = tokens[end]
                if (isNumericToken(t) || UNITS.containsKey(t) || MULTIPLIERS.containsKey(t)) {
                    end += 1
                } else {
                    break
                }
            }
            val value = parseTokens(tokens.subList(i, end))
            if (value != null) amounts.add(value)
            i = end
        }
        return amounts
    }
}
