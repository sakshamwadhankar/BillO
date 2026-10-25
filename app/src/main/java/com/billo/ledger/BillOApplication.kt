package com.billo.ledger

import android.app.Application
import android.content.Context
import com.billo.ledger.core.Clock
import com.billo.ledger.data.db.BillODatabase
import com.billo.ledger.data.ledger.EvidenceRepository
import com.billo.ledger.data.ledger.LedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class BillOApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Stable per-device identity for the CRDT op-log and hybrid logical clock. */
    val nodeId: String by lazy {
        val prefs = getSharedPreferences("billo", Context.MODE_PRIVATE)
        prefs.getString("node_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("node_id", it).apply()
        }
    }

    val clock: Clock by lazy { Clock(nodeId) }

    val ledger: LedgerRepository by lazy {
        LedgerRepository(database, nodeId, clock)
    }

    val evidence: EvidenceRepository by lazy {
        EvidenceRepository(database.evidenceDao())
    }

    val database: BillODatabase by lazy { BillODatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { ledger.ensureSeeded() }
    }
}
