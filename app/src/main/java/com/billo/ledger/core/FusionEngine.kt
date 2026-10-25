package com.billo.ledger.core

import com.billo.ledger.data.db.AccountType
import com.billo.ledger.data.db.BillODatabase
import com.billo.ledger.data.db.EntryKind
import com.billo.ledger.data.db.EvidenceEntity
import com.billo.ledger.data.db.EvidenceStatus
import com.billo.ledger.data.ledger.ChartOfAccounts
import com.billo.ledger.data.ledger.EvidenceRepository
import com.billo.ledger.data.ledger.LedgerRepository
import com.billo.ledger.data.ledger.PostRequest
import com.billo.ledger.data.ledger.UncertainLeg
import java.util.UUID
import kotlin.math.roundToLong

/** Counters and human-readable notes from one [FusionEngine.run] pass. */
data class FusionReport(
    val scanned: Int,
    val clusters: Int,
    val posted: Int,
    val pendingPartial: Int,
    val conflicts: Int,
    val lexiconDrops: Int,
    val duplicateSkips: Int,
    val notes: List<String>,
)

/**
 * Cross-modal fusion (Phase 3): merges VOICE + SMS + OCR + TALLY evidence
 * into single economic events without double counting.
 *
 * Deterministic end to end — clustering is a greedy sequential pass over
 * monotonic-time-sorted evidence, amounts are resolved by posterior weight
 * and every posting carries a deterministic uuid so re-runs are idempotent.
 */
class FusionEngine(
    private val database: BillODatabase,
    private val ledger: LedgerRepository,
    private val evidenceRepo: EvidenceRepository,
) {

    /** Priority order for cluster kind voting (strongest first). */
    private val KIND_PRIORITY = listOf(
        EventKind.CREDIT_SALE,
        EventKind.REPAYMENT,
        EventKind.PURCHASE,
        EventKind.CASH_SALE,
        EventKind.UNKNOWN,
    )

    private data class Cluster(
        val evidences: MutableList<EvidenceEntity> = mutableListOf(),
        val resolves: MutableList<ResolvedEvent> = mutableListOf(),
        val persons: LinkedHashSet<String> = LinkedHashSet(),
        var lastMono: Long = 0,
    )

    private data class AmountCandidate(
        val evidence: EvidenceEntity,
        val resolve: ResolvedEvent,
        val rupees: Long,
        val paise: Long,
    )

    suspend fun run(nowWall: Long = System.currentTimeMillis()): FusionReport {
        val notes = mutableListOf<String>()
        var lexiconDrops = 0
        var conflicts = 0
        var posted = 0
        var pendingPartial = 0
        var duplicateSkips = 0

        // ---- scan RAW evidence, oldest mono first ----
        val evidence = database.evidenceDao()
            .byStatus(EvidenceStatus.RAW)
            .sortedBy { it.monoTimeMillis }
        val scanned = evidence.size

        // ---- Lexicon Drop: learn unknown words from every evidence ----
        for (e in evidence) {
            lexiconDrops += Lexicon.recordUnknownTokens(database, e.text, e.wallTimeMillis).size
        }
        if (lexiconDrops > 0) notes.add("lexicon drop: $lexiconDrops new token(s) learned")

        // ---- greedy sequential clustering over the sorted list ----
        val clusters = mutableListOf<Cluster>()
        for (e in evidence) {
            val resolved = BayesianResolver.resolve(e.text, e.channel, e.confidence, lexiconPriorsFor(e.text))
            val current = clusters.lastOrNull()
            if (current != null) {
                val delta = e.monoTimeMillis - current.lastMono
                val sharesPerson = resolved.persons.any { it in current.persons }
                val lastResolve = current.resolves.lastOrNull()
                val sameAmountAndKind = lastResolve != null &&
                    resolved.kind != EventKind.UNKNOWN &&
                    resolved.kind == lastResolve.kind &&
                    resolved.moneyPaise.firstOrNull() != null &&
                    resolved.moneyPaise.firstOrNull() == lastResolve.moneyPaise.firstOrNull()
                if (delta <= 120_000L ||
                    (sharesPerson && delta <= 600_000L) ||
                    (sameAmountAndKind && delta <= 600_000L)
                ) {
                    current.evidences.add(e)
                    current.resolves.add(resolved)
                    current.persons.addAll(resolved.persons)
                    current.lastMono = e.monoTimeMillis
                    continue
                }
            }
            clusters.add(
                Cluster(
                    evidences = mutableListOf(e),
                    resolves = mutableListOf(resolved),
                    persons = LinkedHashSet(resolved.persons),
                    lastMono = e.monoTimeMillis,
                ),
            )
        }

        // ---- cluster resolution + posting ----
        for (cluster in clusters) {
            val evidences = cluster.evidences
            val resolves = cluster.resolves

            // kind: majority vote, priority order breaks ties
            val kindCounts = resolves.groupingBy { it.kind }.eachCount()
            val maxCount = kindCounts.values.maxOrNull() ?: 0
            val eventKind = KIND_PRIORITY.first { kindCounts[it] == maxCount }

            val channels = evidences.map { it.channel }.distinct()
            val channelLabel = channels.joinToString("+") { it.name }
            val persons = cluster.persons.toList()

            // confidence: mean evidence confidence, boosted by multi-modal
            // agreement, dented by any unknown reading.
            val meanConf = evidences.map { it.confidence }.average()
            var clusterConf = meanConf *
                (if (channels.size >= 2) 1.15 else 1.0) *
                (if (resolves.any { it.kind == EventKind.UNKNOWN }) 0.95 else 1.0)
            clusterConf = clusterConf.coerceIn(0.0, 1.0)

            // ---- amount: posterior weight resolves contradictions ----
            val candidates = evidences.zip(resolves).mapNotNull { (e, r) ->
                r.moneyPaise.firstOrNull()?.let { AmountCandidate(e, r, it / 100, it) }
            }

            var contradiction: Pair<AmountCandidate, AmountCandidate>? = null
            outer@ for (i in candidates.indices) {
                for (j in i + 1 until candidates.size) {
                    if (!ciOverlaps(candidates[i], candidates[j])) {
                        contradiction = candidates[i] to candidates[j]
                        break@outer
                    }
                }
            }

            val winner = candidates.maxByOrNull { it.evidence.confidence }
            if (winner == null) {
                pendingPartial++
                notes.add("partial: cluster [$channelLabel] has no money amount — left RAW")
                continue
            }
            if (contradiction != null) {
                conflicts++
                clusterConf = (clusterConf * 0.4).coerceIn(0.0, 1.0)
                notes.add(
                    "contradictory amounts ₹${contradiction.first.rupees} vs " +
                        "₹${contradiction.second.rupees} — resolved by posterior weight",
                )
            }

            // ---- partial gate: leave RAW so future evidence can complete ----
            val posterior = winner.resolve.amountPosterior
                ?: Dist.around(
                    winner.paise.toDouble(),
                    maxOf(winner.rupees * 120.0, 5_000.0) * (1.6 - winner.evidence.confidence.coerceIn(0.0, 1.0)),
                )
            val amount = posterior.mean().roundToLong()
            val partialReason = when {
                eventKind == EventKind.UNKNOWN -> "kind unknown"
                candidates.isEmpty() -> "no money amount"
                clusterConf < 0.45 -> "low confidence ${"%.2f".format(clusterConf)}"
                amount <= 0 -> "amount rounds to zero"
                eventKind == EventKind.CREDIT_SALE && persons.isEmpty() ->
                    "credit sale without a person"
                eventKind == EventKind.REPAYMENT && persons.isEmpty() ->
                    "repayment without a person"
                else -> null
            }
            if (partialReason != null) {
                pendingPartial++
                notes.add("partial: cluster [$channelLabel] — $partialReason, left RAW for completion")
                continue
            }

            // ---- idempotent posting ----
            val eventUuid = UUID.nameUUIDFromBytes(
                ("billo-event:" + evidences.map { it.id }.sorted().joinToString(","))
                    .toByteArray(Charsets.UTF_8),
            ).toString()
            if (ledger.entryExists(eventUuid)) {
                duplicateSkips++
                evidences.forEach { evidenceRepo.setStatus(it.id, EvidenceStatus.FUSED) }
                notes.add("duplicate cluster [$channelLabel] skipped, evidence marked FUSED")
                continue
            }

            val description = "Fusion[$channelLabel]: " + evidences.first().text.take(60)
            val lines: List<UncertainLeg> = when (eventKind) {
                EventKind.CREDIT_SALE -> {
                    val customer = ChartOfAccounts.customerAccount(persons.first())
                    listOf(
                        UncertainLeg(
                            accountName = customer,
                            accountType = AccountType.ASSET,
                            parentName = ChartOfAccounts.RECEIVABLES,
                            debitPaise = amount,
                            dist = posterior,
                        ),
                        UncertainLeg(
                            accountName = ChartOfAccounts.SALES,
                            accountType = AccountType.INCOME,
                            creditPaise = amount,
                            dist = posterior,
                        ),
                    )
                }
                EventKind.REPAYMENT -> {
                    val customer = ChartOfAccounts.customerAccount(persons.first())
                    listOf(
                        UncertainLeg(
                            accountName = ChartOfAccounts.CASH,
                            accountType = AccountType.ASSET,
                            debitPaise = amount,
                            dist = posterior,
                        ),
                        UncertainLeg(
                            accountName = customer,
                            accountType = AccountType.ASSET,
                            parentName = ChartOfAccounts.RECEIVABLES,
                            creditPaise = amount,
                            dist = posterior,
                        ),
                    )
                }
                EventKind.PURCHASE -> {
                    val quantity = resolves.flatMap { it.quantities }.firstOrNull()
                    listOf(
                        UncertainLeg(
                            accountName = ChartOfAccounts.INVENTORY,
                            accountType = AccountType.ASSET,
                            debitPaise = amount,
                            dist = posterior,
                            qty = quantity?.value,
                            unit = quantity?.unit,
                        ),
                        UncertainLeg(
                            accountName = ChartOfAccounts.CASH,
                            accountType = AccountType.ASSET,
                            creditPaise = amount,
                            dist = posterior,
                        ),
                    )
                }
                else -> listOf( // CASH_SALE (UNKNOWN never reaches here)
                    UncertainLeg(
                        accountName = ChartOfAccounts.CASH,
                        accountType = AccountType.ASSET,
                        debitPaise = amount,
                        dist = posterior,
                    ),
                    UncertainLeg(
                        accountName = ChartOfAccounts.SALES,
                        accountType = AccountType.INCOME,
                        creditPaise = amount,
                        dist = posterior,
                    ),
                )
            }

            val entryKind = when (eventKind) {
                EventKind.CREDIT_SALE, EventKind.CASH_SALE -> EntryKind.SALE
                EventKind.REPAYMENT -> EntryKind.SETTLE_CUSTOMER
                EventKind.PURCHASE -> EntryKind.PURCHASE
                EventKind.UNKNOWN -> EntryKind.NORMAL
            }

            ledger.postEntry(
                PostRequest(
                    description = description,
                    lines = lines,
                    sourceEvidenceId = evidences.first().id,
                    kind = entryKind,
                    confidence = clusterConf,
                    uuid = eventUuid,
                ),
            )
            evidences.forEach { evidenceRepo.setStatus(it.id, EvidenceStatus.FUSED) }
            posted++
            val who = if (persons.isNotEmpty()) " for ${persons.first()}" else ""
            notes.add(
                "posted ${entryKind.name} ₹${amount / 100}$who from [$channelLabel] " +
                    "cluster of ${evidences.size} (conf ${"%.2f".format(clusterConf)})",
            )
        }

        return FusionReport(
            scanned = scanned,
            clusters = clusters.size,
            posted = posted,
            pendingPartial = pendingPartial,
            conflicts = conflicts,
            lexiconDrops = lexiconDrops,
            duplicateSkips = duplicateSkips,
            notes = notes.take(20),
        )
    }

    /** token -> prior strength for every lexicon-known token in the text. */
    private suspend fun lexiconPriorsFor(text: String): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (token in Lexicon.tokenize(text).distinct()) {
            val weight = Lexicon.priorWeight(database, token)
            if (weight > 0.0) out[token] = weight
        }
        return out
    }

    /** 90% CI of a candidate's amount under the spread rule at its own confidence. */
    private fun ciOverlaps(a: AmountCandidate, b: AmountCandidate): Boolean {
        val ciA = ciOf(a)
        val ciB = ciOf(b)
        return ciA.first <= ciB.second && ciB.first <= ciA.second
    }

    private fun ciOf(c: AmountCandidate): Pair<Double, Double> {
        val spread = maxOf(c.rupees * 120.0, 5_000.0) *
            (1.6 - c.evidence.confidence.coerceIn(0.0, 1.0))
        val center = c.paise.toDouble()
        return (center - spread) to (center + spread)
    }
}
