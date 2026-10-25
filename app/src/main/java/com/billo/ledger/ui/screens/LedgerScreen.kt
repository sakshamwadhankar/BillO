package com.billo.ledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billo.ledger.BillOApplication
import com.billo.ledger.data.ledger.Balance
import com.billo.ledger.data.ledger.ChartOfAccounts
import com.billo.ledger.data.ledger.Posting
import com.billo.ledger.ui.formatRupees
import com.billo.ledger.ui.formatWallTime
import kotlinx.coroutines.launch

@Composable
fun LedgerScreen() {
    val app = LocalContext.current.applicationContext as BillOApplication
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val entries by app.ledger.entries().collectAsState(initial = emptyList())
    var balances by remember { mutableStateOf<List<Balance>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(entries) {
        balances = app.ledger.balances()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            app.ledger.postDemoScenario()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Post PS demo entries") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            app.ledger.clearAll()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Reset") }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Journal") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Balances") })
        }

        if (tab == 0) {
            if (entries.isEmpty()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "No entries yet. Post the demo scenario to see the " +
                            "double-entry invariant in action.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.entry.id }) { withLines ->
                        JournalCard(withLines)
                    }
                }
            }
        } else {
            BalanceList(balances)
        }
    }
}

@Composable
private fun JournalCard(item: com.billo.ledger.data.db.EntryWithLines) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(item.entry.description, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                formatWallTime(item.entry.wallTimeMillis) +
                    "  ·  ${item.entry.nodeId.take(8)}" +
                    if (item.entry.confidence < 1.0) "  ·  conf ${(item.entry.confidence * 100).toInt()}%" else "",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(4.dp))
            item.lines.forEach { line ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            line.accountName,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        com.billo.ledger.core.DistCodec.decode(line.distJson)?.let { s ->
                            Text(
                                "90%% CI ₹%.0f–₹%.0f".format(s.p5 / 100, s.p95 / 100),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    if (line.debitPaise > 0) {
                        Text("Dr ${formatRupees(line.debitPaise)}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "Cr ${formatRupees(line.creditPaise)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF26A69A),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceList(balances: List<Balance>) {
    val debitNormal = balances
        .filter { it.account.type == com.billo.ledger.data.db.AccountType.ASSET || it.account.type == com.billo.ledger.data.db.AccountType.EXPENSE }
        .sumOf { it.paise }
    val creditNormal = balances
        .filter { it.account.type != com.billo.ledger.data.db.AccountType.ASSET && it.account.type != com.billo.ledger.data.db.AccountType.EXPENSE }
        .sumOf { it.paise }
    val balanced = debitNormal == creditNormal && balances.isNotEmpty()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (balances.isNotEmpty()) {
            Text(
                if (balanced) "Assets+Expenses ₹${debitNormal / 100} = Liab+Equity+Income ₹${creditNormal / 100} — balanced ✓"
                else "UNBALANCED: A+E ₹${debitNormal / 100} ≠ L+Eq+I ₹${creditNormal / 100}",
                style = MaterialTheme.typography.labelMedium,
                color = if (balanced) Color(0xFF26A69A) else Color(0xFFE57373),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(balances, key = { it.account.id }) { balance ->
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(balance.account.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${balance.account.type} · ${balance.account.parentName ?: "top-level"}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        formatRupees(balance.paise),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
