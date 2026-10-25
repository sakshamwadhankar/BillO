package com.billo.ledger.data.ledger

import android.os.SystemClock
import com.billo.ledger.data.db.EvidenceChannel
import com.billo.ledger.data.db.EvidenceDao
import com.billo.ledger.data.db.EvidenceEntity
import com.billo.ledger.nlp.EvidenceHints

class EvidenceRepository(private val dao: EvidenceDao) {

    fun all() = dao.all()

    /**
     * Persists raw evidence from any channel. Amount/unit hints are
     * extracted on-device immediately (Hindi numerals + units) so the
     * Phase 3 fusion engine has structured hooks, not just raw text.
     */
    suspend fun insert(
        channel: EvidenceChannel,
        text: String,
        imageUri: String? = null,
    ): Long {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return -1
        val entity = EvidenceEntity(
            channel = channel,
            text = trimmed,
            imageUri = imageUri,
            wallTimeMillis = System.currentTimeMillis(),
            monoTimeMillis = SystemClock.elapsedRealtime(),
            parsedHint = EvidenceHints.summarize(trimmed),
        )
        return dao.insert(entity)
    }

    /** Used by SMS import: avoid re-importing the same message twice. */
    suspend fun insertSmsIfNew(text: String): Long {
        val trimmed = text.trim()
        val wall = System.currentTimeMillis()
        if (dao.countSame(trimmed, wall) > 0) return -1
        return insert(EvidenceChannel.SMS, trimmed)
    }

    suspend fun setStatus(id: Long, status: com.billo.ledger.data.db.EvidenceStatus) =
        dao.setStatus(id, status)

    suspend fun clear() = dao.clear()
}
