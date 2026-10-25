package com.billo.ledger.core

import com.billo.ledger.data.db.BillODatabase
import com.billo.ledger.data.db.LexiconEntity
import com.billo.ledger.data.db.LexiconMeaning
import kotlin.math.exp

/**
 * The Lexicon Drop (Phase 2): mid-event learning of unknown words and units.
 *
 * Street vendors speak in endless local variants ("paav", "dedh kilo", a
 * customer's nickname). Instead of failing on unknown tokens, BillO records
 * every unseen word the moment it is heard, counts recurrences, and lets the
 * shopkeeper (or a Bayesian prior) later promote a token to UNIT / ITEM /
 * PERSON. Known vocabulary is never dropped, so the lexicon only grows with
 * genuinely new commerce language.
 */
object Lexicon {

    /** Splits free text into lowercase word tokens (letters + digits runs). */
    fun tokenize(text: String): List<String> =
        Regex("[\\p{L}\\p{Nd}]+").findAll(text).map { it.value.lowercase() }.toList()

    /**
     * Hardcoded base vocabulary (lowercase): Hinglish/English stopwords,
     * business words, every UnitNormalizer alias, and the Hindi numeral
     * vocabulary. Anything outside this set is a candidate Lexicon Drop.
     */
    fun baseVocab(): Set<String> = BASE_VOCAB

    private val BASE_VOCAB: Set<String> = setOf(
        // --- function words / stopwords (English + Hinglish) ---
        "the", "a", "an", "of", "in", "at", "and", "or", "to", "from", "with",
        "for", "on", "is", "are", "was", "were", "it", "this", "that",
        "kar", "ka", "ke", "ki", "ko", "se", "ne", "mein", "me", "hai", "hain",
        "tha", "thi", "mera", "meri", "apna", "apni", "aaj", "kal", "parso",
        "subah", "shaam", "raat", "din", "bhai", "bhaiya", "please",
        "total", "kitna", "kitne", "de", "do", "dena", "lena", "liya", "diya",
        "gaya", "gayi", "hua", "hui", "karo", "karna", "karke",
        "jama", "udhaar", "udhari", "udhar", "credit", "cash", "pay", "paid",
        "mile", "mila", "bika", "sold", "sale", "buy", "kharida", "wholesale",
        "bill", "slip", "bikri", "wapas", "vapas", "repaid", "repay",
        "lautaya", "return", "manga", "mangwa", "bought",
        "rs", "rupees", "rupaye", "rupaya", "rupaiya",
        // --- common items / commodities ---
        "poha", "tamatar", "tomato", "onion", "pyaz", "pyaaz", "rice",
        "chawal", "flour", "atta", "oil", "tel", "aaloo", "aloo", "potato",
        "doodh", "milk", "cheeni", "chini", "sugar", "chai", "tea", "sabun",
        "soap", "daal", "dal", "besan", "namak", "salt", "mirch", "haldi",
        "mithai", "sabzi",
        // --- months ---
        "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept",
        "oct", "nov", "dec", "mahina", "mahine",
        // --- day names ---
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
        "sunday", "somvaar", "somvar", "mangalvaar", "mangalvar", "budhvaar",
        "budhwar", "guruvaar", "guruwar", "shukravaar", "shukrawar",
        "shanivaar", "shaniwar", "ravivaar", "itwaar", "etwaar",
        // --- UnitNormalizer alias table (full coverage) ---
        "kilo", "kg", "kgs", "kilos", "kilogram", "kilograms",
        "gram", "grams", "gms", "gm", "g",
        "litre", "liter", "litres", "liters", "l",
        "plate", "plates", "darjan", "dazen", "dozen",
        "packet", "packets", "pkt",
        "piece", "pieces", "pcs", "pc", "nos",
        "bottle", "bottles", "glass", "glasses", "pav", "pao",
        // --- English number words ---
        "zero", "one", "two", "three", "four", "five", "six", "seven",
        "eight", "nine", "ten", "hundred", "thousand", "million",
        // --- Hindi numerals: units 0..99 ---
        "ek", "teen", "char", "chaar", "paanch", "paach", "panch", "chhe",
        "cheh", "saat", "aath", "nau", "das", "dus",
        "gyarah", "gyara", "baarah", "barah", "terah", "tera", "chaudah",
        "chauda", "pandrah", "pandra", "solah", "sola", "satrah", "satra",
        "atharah", "athara", "unnis", "unni",
        "bees", "ikkis", "ikkai", "bais", "bai", "teis", "tei", "chaubis",
        "chau", "pachees", "pachchees", "chhabbis", "sattais", "atthaais",
        "untees", "tees", "ikattees", "battees", "taintees", "chauntees",
        "paintees", "chhattees", "saintees", "adtees", "untaalees",
        "chaalees", "chaalis", "iktaalees", "bayaalees", "taintaalees",
        "chavaalees", "paintaalees", "chhiyaalees", "saintaalees", "adtaalees",
        "unchaas", "pachaas", "pachas",
        "ikyaavan", "baavan", "tirpan", "chauvan", "pachpan", "chhappan",
        "sattaavan", "atthaavan", "unsath", "saath", "iksath", "baasath",
        "tirsath", "chausath", "painsath", "chhiyasath", "sarsath", "adsath",
        "unhattar", "sattar", "ikattar", "bahattar", "tihattar", "chauhattar",
        "pachattar", "chhibhattar", "satattar", "athhattar", "unnaasi",
        "assi", "asi", "ikyaasi", "bayaasi", "tiraasi", "chauraasi",
        "pachaasi", "chhiyaasi", "sattaasi", "atthaasi", "navaasi", "nabbe",
        "ikyaanve", "baanve", "tiraanve", "chauraanve", "pachaanve",
        "chhiyaanve", "sattaanve", "atthaanve", "ninyaanve",
        "sau", "so", "sifar", "shunya",
        // --- Hindi multipliers ---
        "hazaar", "hazar", "hajaar", "lakh", "lac", "laakh", "crore",
        "karod", "caror",
        // --- Hindi fractional numerals ---
        "dedh", "dhai", "dhaai", "adhai", "sawa", "savaa", "paune", "pone",
        "pauna", "saadhe", "saade", "sade", "sadhe",
    )

    /**
     * Records unknown tokens from [text] into the lexicon (the "drop").
     * A token is droppable when it is not in [baseVocab], not purely
     * numeric, and at least 3 letters long. Newly seen tokens are inserted
     * with count 1 and returned; known tokens just get their count and
     * lastSeen refreshed (meaning and firstSeen preserved).
     */
    suspend fun recordUnknownTokens(
        database: BillODatabase,
        text: String,
        nowWall: Long,
    ): List<String> {
        val dao = database.lexiconDao()
        val dropped = mutableListOf<String>()
        val vocab = baseVocab()
        for (token in tokenize(text).distinct()) {
            if (token.length < 3) continue
            if (token in vocab) continue
            if (token.none { it.isLetter() }) continue // purely numeric
            val existing = dao.byToken(token)
            if (existing == null) {
                dao.upsert(
                    LexiconEntity(
                        token = token,
                        meaning = LexiconMeaning.UNSET,
                        count = 1,
                        firstSeenWall = nowWall,
                        lastSeenWall = nowWall,
                        sampleContext = text.take(80),
                    ),
                )
                dropped.add(token)
            } else {
                dao.upsert(
                    existing.copy(count = existing.count + 1, lastSeenWall = nowWall),
                )
            }
        }
        return dropped
    }

    /**
     * Bayesian prior strength of a token: unseen → 0.0; labelled
     * UNIT/ITEM/PERSON → 0.9 (trusted); UNSET grows with sightings toward
     * at most 0.6 (suspicious but possibly meaningful).
     */
    suspend fun priorWeight(database: BillODatabase, token: String): Double {
        val entity = database.lexiconDao().byToken(token) ?: return 0.0
        return when (entity.meaning) {
            LexiconMeaning.UNSET -> (1.0 - exp(-entity.count / 5.0)) * 0.6
            LexiconMeaning.UNIT,
            LexiconMeaning.ITEM,
            LexiconMeaning.PERSON,
            -> 0.9
        }
    }

    /** Labels a token, preserving its sighting count and first-seen time. */
    suspend fun label(
        database: BillODatabase,
        token: String,
        meaning: LexiconMeaning,
    ) {
        val dao = database.lexiconDao()
        val existing = dao.byToken(token)
        val now = System.currentTimeMillis()
        dao.upsert(
            existing?.copy(meaning = meaning)
                ?: LexiconEntity(
                    token = token,
                    meaning = meaning,
                    count = 1,
                    firstSeenWall = now,
                    lastSeenWall = now,
                    sampleContext = "",
                ),
        )
    }

    /** One-shot snapshot of the whole learned lexicon. */
    suspend fun allOnce(database: BillODatabase): List<LexiconEntity> =
        database.lexiconDao().allOnce()
}
