package com.billo.ledger.core

/**
 * Hybrid Logical Clock (Kulkarni et al.): wall-millis + counter + node id.
 *
 * Hostile-clock survival rules (the PS demands surviving 1970 resets and
 * forward jumps without reordering history):
 *  - the wall component NEVER goes backwards, so a reset to 1970 is ignored
 *    and the counter keeps ordering within the same wall millisecond;
 *  - a forward jump is absorbed (the future value becomes the new floor);
 *  - total order: (wall, counter, nodeId) — deterministic across devices.
 */
data class Hlc(val wallMillis: Long, val counter: Long, val nodeId: String) : Comparable<Hlc> {

    override fun compareTo(other: Hlc): Int {
        wallMillis.compareTo(other.wallMillis).let { if (it != 0) return it }
        counter.compareTo(other.counter).let { if (it != 0) return it }
        return nodeId.compareTo(other.nodeId)
    }

    override fun toString(): String = "$wallMillis.$counter@$nodeId"

    companion object {
        val EPOCH = Hlc(0L, 0L, "?")
    }
}

/** Per-device clock state, thread-safe. */
class Clock(private val nodeId: String) {
    private var lastWall: Long = 0
    private var lastCounter: Long = 0

    @Synchronized
    fun tick(nowWall: Long = System.currentTimeMillis()): Hlc {
        val (w, c) = if (nowWall > lastWall) {
            nowWall to 0L
        } else {
            lastWall to lastCounter + 1
        }
        lastWall = w
        lastCounter = c
        return Hlc(w, c, nodeId)
    }

    @Synchronized
    fun observe(remote: Hlc, nowWall: Long = System.currentTimeMillis()): Hlc {
        val maxWall = maxOf(lastWall, remote.wallMillis, if (nowWall > lastWall) nowWall else Long.MIN_VALUE)
        val (w, c) = when {
            maxWall > lastWall && maxWall > remote.wallMillis -> maxWall to 0L
            lastWall == remote.wallMillis -> lastWall to maxOf(lastCounter, remote.counter) + 1
            else -> maxWall to if (lastWall > remote.wallMillis) lastCounter + 1 else remote.counter + 1
        }
        lastWall = w
        lastCounter = c
        return Hlc(w, c, nodeId)
    }

    @Synchronized
    fun current(): Hlc = Hlc(lastWall, lastCounter, nodeId)
}
