package com.billo.ledger

import android.app.Application
import com.billo.ledger.data.db.BillODatabase
import com.billo.ledger.data.ledger.EvidenceRepository
import com.billo.ledger.data.ledger.LedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BillOApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val ledger: LedgerRepository by lazy {
        LedgerRepository(database.ledgerDao())
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
