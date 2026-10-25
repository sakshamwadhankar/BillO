package com.billo.ledger.perception.sms

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.billo.ledger.BillOApplication
import com.billo.ledger.data.db.EvidenceChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backfills recent inbox SMS into the evidence store — used when the
 * app was closed while a payment confirmation chimed in. Dedup happens
 * in [EvidenceRepository.insertSmsIfNew].
 */
object SmsInboxReader {

    @SuppressLint("MissingPermission") // caller checks READ_SMS
    suspend fun importRecent(context: Context, limit: Int = 30): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext as BillOApplication
        val projection = arrayOf(Telephony.Sms.Inbox.BODY, Telephony.Sms.Inbox.DATE)
        var imported = 0
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.Inbox.DATE} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext() && imported < limit) {
                    val body = cursor.getString(0) ?: continue
                    if (app.evidence.insertSmsIfNew(body) > 0) imported += 1
                }
            }
        } catch (_: SecurityException) {
            return@withContext -1
        } catch (_: Exception) {
            return@withContext -2
        }
        imported
    }

    fun inboxUri(): Uri = Telephony.Sms.Inbox.CONTENT_URI
}
