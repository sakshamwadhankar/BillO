package com.billo.ledger.perception.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.billo.ledger.BillOApplication
import com.billo.ledger.data.db.EvidenceChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Passive SMS evidence ingestion ("delayed SMS chimes"): every incoming
 * SMS lands in the evidence store with its receive timestamps. Payment
 * confirmations that arrive minutes after the spoken transaction become
 * fusion input instead of duplicate entries.
 */
class SmsEvidenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val body = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            .joinToString(" ") { it.displayMessageBody.orEmpty() }
            .trim()
        if (body.isEmpty()) return
        val app = context.applicationContext as? BillOApplication ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.evidence.insert(EvidenceChannel.SMS, body)
            } catch (e: Exception) {
                Log.w("SmsEvidence", "failed to store sms evidence", e)
            }
        }
    }
}
