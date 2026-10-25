package com.billo.ledger.core

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Discrete probability distribution over real values (paise as Double).
 * The backbone of Phase 2: every uncertain fact in BillO is one of these,
 * never a bare point. Deterministic — no RNG needed for construction.
 */
data class Dist(val points: List<DistPoint>) {

    data class DistPoint(val value: Double, val weight: Double)

    init {
        require(points.isNotEmpty()) { "Dist needs at least one point" }
        require(points.all { it.weight >= 0.0 && it.value.isFinite() }) { "invalid dist points" }
    }

    fun normalized(): Dist {
        val total = points.sumOf { it.weight }
        if (total <= 0.0) return this
        return copy(points = points.map { DistPoint(it.value, it.weight / total) })
    }

    fun mean(): Double = normalized().points.sumOf { it.value * it.weight }

    fun variance(): Double {
        val n = normalized()
        val m = n.mean()
        return n.points.sumOf { it.weight * (it.value - m) * (it.value - m) }
    }

    fun stddev(): Double = sqrt(variance())

    /** Weighted quantile, q in [0,1]. */
    fun quantile(q: Double): Double {
        val sorted = normalized().points.sortedBy { it.value }
        val target = q.coerceIn(0.0, 1.0)
        var cum = 0.0
        for (p in sorted) {
            cum += p.weight
            if (cum >= target) return p.value
        }
        return sorted.last().value
    }

    fun ci90(): Pair<Double, Double> = quantile(0.05) to quantile(0.95)

    fun scale(k: Double): Dist = Dist(points.map { DistPoint(it.value * k, it.weight) })

    fun shift(c: Double): Dist = Dist(points.map { DistPoint(it.value + c, it.weight) })

    fun negate(): Dist = scale(-1.0)

    /**
     * Sum of two independent distributions (cartesian convolution).
     * Thins oversized results through a deterministic 101-point quantile grid.
     */
    operator fun plus(other: Dist): Dist {
        val a = normalized().points
        val b = normalized().points
        if (a.size * b.size > 900) {
            val thinA = thinned(a.size.coerceAtMost(30))
            val thinB = other.thinned(b.size.coerceAtMost(30))
            return thinA.plus(thinB)
        }
        val out = ArrayList<DistPoint>(a.size * b.size)
        for (p in a) for (q in b) out.add(DistPoint(p.value + q.value, p.weight * q.weight))
        return Dist(out)
    }

    fun thinned(n: Int): Dist {
        if (points.size <= n) return this
        return Dist((1 until n).map { i ->
            val q = quantile(i.toDouble() / n)
            DistPoint(q, 1.0 / n)
        })
    }

    /** Mixture of two distributions with weight w on this one. */
    fun mix(other: Dist, w: Double): Dist =
        Dist(normalized().points.map { DistPoint(it.value, it.weight * w) } +
            other.normalized().points.map { DistPoint(it.value, it.weight * (1 - w)) })

    companion object {
        fun point(v: Double) = Dist(listOf(DistPoint(v, 1.0)))

        fun uniform(min: Double, max: Double, n: Int = 21): Dist =
            Dist((0 until n).map { i ->
                val x = min + (max - min) * i / (n - 1.0).coerceAtLeast(1.0)
                DistPoint(x, 1.0 / n)
            })

        /**
         * Symmetric ±spread around a center — the default "noisy measurement" shape:
         * triangular-ish core with light tails, quantified from evidence quality.
         */
        fun around(center: Double, spread: Double, n: Int = 21): Dist =
            Dist((0 until n).map { i ->
                val z = (i / (n - 1.0)) * 2 - 1 // -1..1
                val x = center + spread * z
                val w = 1.0 + 2.0 * (1.0 - kotlin.math.abs(z)) // triangle density
                DistPoint(x, w)
            }).normalized()

        fun fromSamples(samples: List<Double>): Dist =
            if (samples.isEmpty()) point(0.0) else Dist(samples.map { DistPoint(it, 1.0 / samples.size) })

        /** Beta distribution via log-gamma pdf on a deterministic grid. */
        fun beta(alpha: Double, betaParam: Double, n: Int = 101): Dist {
            val lnb = lnGamma(alpha + betaParam) - lnGamma(alpha) - lnGamma(betaParam)
            val pts = (1..n).map { i ->
                val x = i / (n + 1.0)
                val logPdf = (alpha - 1) * ln(x) + (betaParam - 1) * ln(1 - x) - lnb
                DistPoint(x, kotlin.math.exp(logPdf))
            }
            return Dist(pts).normalized()
        }
    }

    fun sample(rng: kotlin.random.Random): Double {
        val n = normalized()
        var r = rng.nextDouble()
        for (p in n.points) {
            r -= p.weight
            if (r <= 0) return p.value
        }
        return n.points.last().value
    }
}

private val LANCZOS = doubleArrayOf(
    76.18009172947146, -86.50532032941677, 24.01409824083091,
    -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5,
)

fun lnGamma(xIn: Double): Double {
    var x = xIn
    var y = x
    var tmp = x + 5.5
    tmp -= (x + 0.5) * ln(tmp)
    var ser = 1.000000000190015
    for (j in LANCZOS.indices) {
        y += 1.0
        ser += LANCZOS[j] / y
    }
    return -tmp + ln(2.5066282746310005 * ser / x)
}

/** Serializable summary of a Dist for Room storage and UI display. */
data class DistSummary(
    val p5: Double,
    val p50: Double,
    val p95: Double,
    val mean: Double,
    val sd: Double,
    val conf: Double,
)

object DistCodec {
    fun encode(dist: Dist, conf: Double): String {
        val (p5, p95) = dist.ci90()
        return """{"p5":${p5},"p50":${dist.quantile(0.5)},"p95":$p95,"mean":${dist.mean()},"sd":${dist.stddev()},"conf":$conf}"""
    }

    fun decode(json: String?): DistSummary? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = org.json.JSONObject(json)
            DistSummary(
                p5 = o.getDouble("p5"),
                p50 = o.getDouble("p50"),
                p95 = o.getDouble("p95"),
                mean = o.getDouble("mean"),
                sd = o.getDouble("sd"),
                conf = o.getDouble("conf"),
            )
        }.getOrNull()
    }
}
