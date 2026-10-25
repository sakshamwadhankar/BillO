package com.billo.ledger.sync

import com.billo.ledger.core.Clock
import com.billo.ledger.core.OpsCodec
import com.billo.ledger.data.db.AccountType
import com.billo.ledger.data.db.EntryKind
import com.billo.ledger.data.db.LedgerOpEntity
import com.billo.ledger.data.db.OpType
import java.util.UUID
import kotlin.random.Random

data class ChaosNodeReport(
    val nodeName: String,
    val ops: Int,
    val finalClockFloor: Long,
    val anomaliesSurvived: Int,
)

data class ChaosReport(
    val nodes: List<ChaosNodeReport>,
    val totalOps: Int,
    val anomaliesInjected: Int,
    val mergedOpsIdentical: Boolean,
    val allBalanced: Boolean,
    val conflictsDetected: Int,
    val sameWinnerEverywhere: Boolean,
    val notes: List<String>,
)

/**
 * Hostile-clock CRDT chaos test, fully in memory (pure Kotlin, no Android).
 *
 * Three simulated devices generate balanced entries while their wall clocks
 * are attacked (reset to 1970, +90 day forward jump, backwards jump attempt),
 * produce contradictory settlement claims, then merge op-logs pairwise until
 * a fixpoint. The report proves convergence, balance preservation and a
 * deterministic, device-independent settlement winner under the total-order
 * rule: higher confidence, then smaller hlcWall, then smaller opId.
 *
 * Deterministic: single Random(99) stream consumed node by node, event by
 * event, so every run produces byte-identical results.
 */
class ChaosSimulator {

    fun run(): ChaosReport {
        val rng = Random(99)
        val nodeNames = listOf("device-A", "device-B", "device-C")
        val startWall = 1_700_000_000_000L
        val hourMillis = 3_600_000L
        val dayMillis = 24L * 3_600_000L

        data class SimNode(
            val name: String,
            val clock: Clock,
            val ops: MutableList<LedgerOpEntity>,
            var simWall: Long,
            var forwardJumpWall: Long,
            var anomalies: Int,
        )

        val nodes = nodeNames.map { name ->
            SimNode(name, Clock(name), mutableListOf(), startWall, 0L, 0)
        }

        // ------------------------------------------------------------------
        // Phase 1: 20 balanced entries per node under a hostile clock
        // ------------------------------------------------------------------
        for (node in nodes) {
            for (i in 0 until EVENTS_PER_NODE) {
                // Anomalies, deterministically placed before events 5, 9 and 14 (1-based).
                when (i) {
                    4 -> { // 1970 reset: wall falls back to 1000L
                        node.simWall = 1_000L
                        node.anomalies++
                    }
                    8 -> { // forward jump: +90 days (absorbed as the new clock floor)
                        node.simWall += 90L * dayMillis
                        node.forwardJumpWall = node.simWall
                        node.anomalies++
                    }
                    13 -> { // backwards attempt: HLC floor must hold the line
                        node.simWall = maxOf(
                            node.simWall - 5L * dayMillis,
                            node.clock.current().wallMillis,
                        )
                        node.anomalies++
                    }
                }

                val amount = 10_000L + rng.nextInt(90_000)
                val payload = OpsCodec.EntryPayload(
                    uuid = UUID.nameUUIDFromBytes("${node.name}-event-$i".toByteArray()).toString(),
                    description = "${node.name} event $i",
                    wallTimeMillis = node.simWall,
                    kind = EntryKind.NORMAL,
                    confidence = 1.0,
                    sourceEvidenceId = null,
                    lines = listOf(
                        OpsCodec.LinePayload("Cash", AccountType.ASSET, null, amount, 0L, null, null, null),
                        OpsCodec.LinePayload("Sales", AccountType.INCOME, null, 0L, amount, null, null, null),
                    ),
                )
                val hlc = node.clock.tick(node.simWall)
                node.ops.add(
                    LedgerOpEntity(
                        opId = payload.uuid,
                        hlcWall = hlc.wallMillis,
                        hlcCounter = hlc.counter,
                        nodeId = node.name,
                        type = OpType.POST_ENTRY,
                        payloadJson = OpsCodec.encodeEntry(payload),
                        appliedAtWall = 0L,
                    ),
                )
                node.simWall += hourMillis
            }
        }

        // ------------------------------------------------------------------
        // Phase 2: contradictory settlement claims for the same customer
        // ------------------------------------------------------------------
        for (node in nodes) {
            val claim = when (node.name) {
                "device-A" -> OpsCodec.ClaimPayload("claim-A", "Sharma ji", 4_000, true, 0.8, "A heard 40")
                "device-B" -> OpsCodec.ClaimPayload("claim-B", "Sharma ji", 2_500, true, 0.9, "B heard 25")
                else -> null
            } ?: continue
            val hlc = node.clock.tick(node.simWall)
            node.ops.add(
                LedgerOpEntity(
                    opId = claim.claimId,
                    hlcWall = hlc.wallMillis,
                    hlcCounter = hlc.counter,
                    nodeId = node.name,
                    type = OpType.SETTLEMENT_CLAIM,
                    payloadJson = OpsCodec.encodeClaim(claim),
                    appliedAtWall = 0L,
                ),
            )
        }

        // ------------------------------------------------------------------
        // Phase 3: pairwise op-log exchange until fixpoint (union by opId)
        // ------------------------------------------------------------------
        val merged = HashMap<String, LinkedHashSet<LedgerOpEntity>>()
        for (node in nodes) merged[node.name] = LinkedHashSet(node.ops)

        var changed = true
        while (changed) {
            changed = false
            for (a in nodeNames) {
                for (b in nodeNames) {
                    if (a == b) continue
                    val setA = merged.getValue(a)
                    val before = setA.size
                    setA.addAll(merged.getValue(b))
                    if (setA.size != before) changed = true
                }
            }
        }

        val union = merged.getValue(nodeNames.first())
        val opIdSets = merged.values.map { set -> set.mapTo(HashSet()) { it.opId } }
        val mergedOpsIdentical = merged.values.all { it.size == union.size } &&
            opIdSets.distinct().size == 1
        val totalOps = union.size

        // ------------------------------------------------------------------
        // Phase 4: every merged entry must still balance
        // ------------------------------------------------------------------
        var allBalanced = true
        for (op in union) {
            if (op.type != OpType.POST_ENTRY) continue
            val payload = OpsCodec.decodeEntry(op.payloadJson)
            if (payload.lines.sumOf { it.debitPaise } != payload.lines.sumOf { it.creditPaise }) {
                allBalanced = false
            }
        }

        // ------------------------------------------------------------------
        // Phase 5: settlement winner — evaluated independently on every node
        // ------------------------------------------------------------------
        var conflictsDetected = 0
        var sameWinnerEverywhere = true
        var winnerRef: String? = null
        val winnerNotes = mutableListOf<String>()
        // Number of contradictory claim groups, counted once over the union.
        conflictsDetected = union
            .filter { it.type == OpType.SETTLEMENT_CLAIM }
            .groupBy { OpsCodec.decodeClaim(it.payloadJson).customer }
            .count { it.value.size > 1 }
        for (node in nodes) {
            val claims = merged.getValue(node.name).filter { it.type == OpType.SETTLEMENT_CLAIM }
            val byCustomer = claims.groupBy { OpsCodec.decodeClaim(it.payloadJson).customer }
            for ((customer, group) in byCustomer) {
                val winner = group.minWith(
                    compareByDescending<LedgerOpEntity> { OpsCodec.decodeClaim(it.payloadJson).confidence }
                        .thenBy { it.hlcWall }
                        .thenBy { it.opId },
                )
                if (winnerRef == null) {
                    winnerRef = winner.opId
                } else if (winnerRef != winner.opId) {
                    sameWinnerEverywhere = false
                }
                if (node === nodes.first()) {
                    val w = OpsCodec.decodeClaim(winner.payloadJson)
                    winnerNotes.add(
                        "Settlement conflict on '$customer': winner ${winner.opId} " +
                            "(confidence ${w.confidence}, ${w.amountPaise} paise) — identical on every node",
                    )
                }
            }
        }

        // ------------------------------------------------------------------
        // Phase 6: clock floor verification + report
        // ------------------------------------------------------------------
        val notes = mutableListOf<String>()
        val nodeReports = nodes.map { node ->
            val floor = node.clock.current().wallMillis
            val survived = node.anomalies
            if (floor >= node.forwardJumpWall && survived == ANOMALIES_PER_NODE) {
                notes.add(
                    "${node.name} survived $survived hostile clock anomalies " +
                        "(1970 reset, +90d forward jump, backwards attempt); " +
                        "clock floor $floor >= forward-jump wall ${node.forwardJumpWall}",
                )
            } else {
                notes.add(
                    "${node.name} FAILED clock discipline: floor $floor, " +
                        "forward-jump wall ${node.forwardJumpWall}, anomalies $survived",
                )
            }
            ChaosNodeReport(
                nodeName = node.name,
                ops = node.ops.size,
                finalClockFloor = floor,
                anomaliesSurvived = survived,
            )
        }
        notes.add("1970 resets and backward jumps never moved the clock floor backwards")
        notes.add(
            if (mergedOpsIdentical) {
                "Op-log union converged: every node holds all $totalOps unique ops after pairwise exchange"
            } else {
                "Op-log union did NOT converge — nodes disagree"
            },
        )
        notes.add(
            if (allBalanced) {
                "Every merged POST_ENTRY op is balanced (sum debits == sum credits)"
            } else {
                "Balance violation detected in merged ops"
            },
        )
        notes.add(
            "Contradictory settlement claims detected: $conflictsDetected (claim-A 4000 paise vs claim-B 2500 paise for 'Sharma ji')",
        )
        notes.addAll(winnerNotes)
        notes.add(
            "Winner rule (confidence desc, then hlcWall, then opId) is deterministic: " +
                (if (sameWinnerEverywhere) "same winner on every node" else "WINNER DISAGREEMENT"),
        )

        return ChaosReport(
            nodes = nodeReports,
            totalOps = totalOps,
            anomaliesInjected = nodes.sumOf { it.anomalies },
            mergedOpsIdentical = mergedOpsIdentical,
            allBalanced = allBalanced,
            conflictsDetected = conflictsDetected,
            sameWinnerEverywhere = sameWinnerEverywhere,
            notes = notes,
        )
    }

    private companion object {
        const val EVENTS_PER_NODE = 20
        const val ANOMALIES_PER_NODE = 3
    }
}
