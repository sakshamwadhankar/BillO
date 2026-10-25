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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billo.ledger.BillOApplication
import com.billo.ledger.data.db.EvidenceChannel
import com.billo.ledger.data.db.EvidenceEntity
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
fun EvidenceScreen() {
    val app = LocalContext.current.applicationContext as BillOApplication
    val evidence by app.evidence.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Evidence inbox", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Raw multi-channel input — fused into entries in Phase 3",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = { scope.launch { app.evidence.clear() } }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear evidence")
            }
        }
        Spacer(Modifier.height(8.dp))

        if (evidence.isEmpty()) {
            Text(
                "No evidence yet.\nUse the Capture tab: speak Hinglish, snap a slip, " +
                    "or let an SMS chime in — everything lands here first.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(evidence, key = { it.id }) { item ->
                    EvidenceCard(item)
                }
            }
        }
    }
}

@Composable
private fun EvidenceCard(evidence: EvidenceEntity) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = channelColor(evidence.channel).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        evidence.channel.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = channelColor(evidence.channel),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatWallTime(evidence.wallTimeMillis),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(evidence.text, style = MaterialTheme.typography.bodyMedium)
            evidence.parsedHint?.let { hint ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "on-device hints: $hint",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
