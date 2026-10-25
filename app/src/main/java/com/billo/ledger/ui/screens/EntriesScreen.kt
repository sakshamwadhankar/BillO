package com.billo.ledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billo.ledger.BillOApplication
import com.billo.ledger.R
import com.billo.ledger.core.FusionEngine
import com.billo.ledger.core.FusionReport
import com.billo.ledger.data.db.EvidenceChannel
import com.billo.ledger.data.db.EvidenceEntity
import com.billo.ledger.ui.BillOCard
import com.billo.ledger.ui.BillOPill
import com.billo.ledger.ui.Forest
import com.billo.ledger.ui.Ink
import com.billo.ledger.ui.LimeAccent
import com.billo.ledger.ui.MutedText
import com.billo.ledger.ui.TealGreen
import com.billo.ledger.ui.formatWallTime
import kotlinx.coroutines.launch

private fun channelColor(channel: EvidenceChannel): Color = when (channel) {
    EvidenceChannel.VOICE -> Color(0xFFFFB300)
    EvidenceChannel.OCR -> Color(0xFF26A69A)
    EvidenceChannel.SMS -> Color(0xFF64B5F6)
    EvidenceChannel.TALLY -> Color(0xFFBA68C8)
    EvidenceChannel.MANUAL -> Color(0xFF90A4AE)
}

@Composable
fun EntriesScreen() {
    val app = LocalContext.current.applicationContext as BillOApplication
    val evidence by app.evidence.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var fusionReport by remember { mutableStateOf<FusionReport?>(null) }
    var fusing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        // ---------------- header ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.evidence_title),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink,
                )
                Text(
                    stringResource(R.string.evidence_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
            }
            IconButton(onClick = { scope.launch { app.evidence.clear() } }) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = stringResource(R.string.clear_evidence),
                    tint = MutedText,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------------- run fusion (lime, full width) ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(LimeAccent)
                .clickable(enabled = !fusing) {
                    scope.launch {
                        fusing = true
                        try {
                            fusionReport = FusionEngine(app.database, app.ledger, app.evidence).run()
                        } finally {
                            fusing = false
                        }
                    }
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (fusing) stringResource(R.string.fusing) else stringResource(R.string.run_fusion),
                color = Forest,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "⚙",
                color = Forest,
                fontWeight = FontWeight.Bold,
            )
        }

        fusionReport?.let { r ->
            Spacer(Modifier.height(12.dp))
            BillOCard(background = Color.White, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(
                            R.string.fusion_stats,
                            r.posted,
                            r.pendingPartial,
                            r.conflicts,
                            r.lexiconDrops,
                            r.duplicateSkips,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = Ink,
                    )
                    r.notes.take(4).forEach { note ->
                        Text(note, style = MaterialTheme.typography.labelSmall, color = MutedText)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (evidence.isEmpty()) {
            BillOCard(background = Color.White, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.evidence_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedText,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(evidence, key = { it.id }) { item ->
                    EvidenceCard(item)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EvidenceCard(evidence: EvidenceEntity) {
    BillOCard(background = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BillOPill(
                    evidence.channel.name,
                    background = channelColor(evidence.channel).copy(alpha = 0.2f),
                    contentColor = channelColor(evidence.channel),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatWallTime(evidence.wallTimeMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.confidence, (evidence.confidence * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = TealGreen,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(evidence.text, style = MaterialTheme.typography.bodyMedium, color = Ink)
            evidence.parsedHint?.let { hint ->
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.hints, hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = Forest,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
