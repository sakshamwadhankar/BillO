package com.billo.ledger.core

import com.billo.ledger.data.db.AccountType
import com.billo.ledger.data.db.EntryKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Wire + storage format for CRDT ops. Every mutation on any device is
 * one immutable op; merging device op-logs is a commutative union by
 * opId, so convergence is guaranteed regardless of merge order.
 */
object OpsCodec {

    data class AccountPayload(val uuid: String, val name: String, val type: AccountType, val parentName: String?)

    data class LinePayload(
        val account: String,
        val type: AccountType,
        val parentName: String?,
        val debitPaise: Long,
        val creditPaise: Long,
        val distJson: String?,
        val qty: Double?,
        val unit: String?,
    )

    data class EntryPayload(
        val uuid: String,
        val description: String,
        val wallTimeMillis: Long,
        val kind: EntryKind,
        val confidence: Double,
        val sourceEvidenceId: Long?,
        val lines: List<LinePayload>,
    )

    data class ClaimPayload(
        val claimId: String,
        val customer: String,
        val amountPaise: Long,
        val repayment: Boolean,
        val confidence: Double,
        val note: String,
    )

    fun encodeAccount(p: AccountPayload): String = JSONObject()
        .put("uuid", p.uuid)
        .put("name", p.name)
        .put("type", p.type.name)
        .put("parent", p.parentName)
        .toString()

    fun decodeAccount(json: String): AccountPayload {
        val o = JSONObject(json)
        return AccountPayload(
            uuid = o.getString("uuid"),
            name = o.getString("name"),
            type = AccountType.valueOf(o.getString("type")),
            parentName = o.optString("parent").takeIf { it.isNotEmpty() },
        )
    }

    fun encodeEntry(p: EntryPayload): String {
        val arr = JSONArray()
        p.lines.forEach { l ->
            val lo = JSONObject()
                .put("account", l.account)
                .put("type", l.type.name)
                .put("parent", l.parentName ?: "")
                .put("debit", l.debitPaise)
                .put("credit", l.creditPaise)
                .put("dist", l.distJson ?: "")
                .put("unit", l.unit ?: "")
            lo.put("qty", if (l.qty != null) l.qty else JSONObject.NULL)
            arr.put(lo)
        }
        return JSONObject()
            .put("uuid", p.uuid)
            .put("description", p.description)
            .put("wall", p.wallTimeMillis)
            .put("kind", p.kind.name)
            .put("conf", p.confidence)
            .put("evidenceId", p.sourceEvidenceId ?: -1L)
            .put("lines", arr)
            .toString()
    }

    fun decodeEntry(json: String): EntryPayload {
        val o = JSONObject(json)
        val lines = mutableListOf<LinePayload>()
        val arr = o.getJSONArray("lines")
        for (i in 0 until arr.length()) {
            val l = arr.getJSONObject(i)
            lines.add(
                LinePayload(
                    account = l.getString("account"),
                    type = AccountType.valueOf(l.getString("type")),
                    parentName = l.optString("parent").takeIf { it.isNotEmpty() },
                    debitPaise = l.getLong("debit"),
                    creditPaise = l.getLong("credit"),
                    distJson = l.optString("dist").takeIf { it.isNotEmpty() },
                    qty = if (l.isNull("qty")) null else l.getDouble("qty"),
                    unit = l.optString("unit").takeIf { it.isNotEmpty() },
                ),
            )
        }
        val evidenceId = o.getLong("evidenceId")
        return EntryPayload(
            uuid = o.getString("uuid"),
            description = o.getString("description"),
            wallTimeMillis = o.getLong("wall"),
            kind = EntryKind.valueOf(o.getString("kind")),
            confidence = o.getDouble("conf"),
            sourceEvidenceId = if (evidenceId > 0) evidenceId else null,
            lines = lines,
        )
    }

    fun encodeClaim(p: ClaimPayload): String = JSONObject()
        .put("claimId", p.claimId)
        .put("customer", p.customer)
        .put("amount", p.amountPaise)
        .put("repayment", p.repayment)
        .put("conf", p.confidence)
        .put("note", p.note)
        .toString()

    fun decodeClaim(json: String): ClaimPayload {
        val o = JSONObject(json)
        return ClaimPayload(
            claimId = o.getString("claimId"),
            customer = o.getString("customer"),
            amountPaise = o.getLong("amount"),
            repayment = o.getBoolean("repayment"),
            confidence = o.getDouble("conf"),
            note = o.optString("note"),
        )
    }

    fun deterministicAccountUuid(name: String): String =
        UUID.nameUUIDFromBytes("billo-account:$name".toByteArray()).toString()
}
