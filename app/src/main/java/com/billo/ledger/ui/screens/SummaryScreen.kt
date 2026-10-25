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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.billo.ledger.BillOApplication
import com.billo.ledger.R
import com.billo.ledger.data.db.EntryKind
import com.billo.ledger.data.ledger.Balance
import com.billo.ledger.data.ledger.ChartOfAccounts
import com.billo.ledger.ui.ActionCard
import com.billo.ledger.ui.BillOCard
import com.billo.ledger.ui.BillOIconBadge
import com.billo.ledger.ui.BillOPill
import com.billo.ledger.ui.CardLime
import com.billo.ledger.ui.CardMint
import com.billo.ledger.ui.CardSky
import com.billo.ledger.ui.Forest
import com.billo.ledger.ui.Ink
import com.billo.ledger.ui.MutedText
import com.billo.ledger.ui.SectionHeader
import com.billo.ledger.ui.TealGreen
import com.billo.ledger.ui.formatRupees
import kotlinx.coroutines.launch

@Composable
fun SummaryScreen(nav: NavHostController) {
    val app = LocalContext.current.applicationContext as BillOApplication
    val scope = rememberCoroutineScope()
    val entries by app.ledger.entries().collectAsState(initial = emptyList())
    var balances by remember { mutableStateOf<List<Balance>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(entries) {
        balances = app.ledger.balances()
    }

    val cashOnHand = balances.firstOrNull { it.account.name == ChartOfAccounts.CASH }?.paise ?: 0L
    val salesBalance = balances.firstOrNull { it.account.name == ChartOfAccounts.SALES }?.paise ?: 0L
    val expenseBalance = balances
        .filter {
            it.account.name == ChartOfAccounts.PURCHASES ||
                it.account.name == ChartOfAccounts.SPOILAGE ||
                it.account.name == ChartOfAccounts.THEFT ||
                it.account.name == ChartOfAccounts.DRAWINGS
        }
        .sumOf { it.paise }
    val saleCount = entries.count { it.entry.kind == EntryKind.SALE }
    val expenseEntryCount = entries.count { it.entry.kind == EntryKind.PURCHASE || it.entry.kind == EntryKind.LOSS }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------------- header ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.summary_title),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink,
                )
                Text(
                    stringResource(R.string.summary_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
            }
            BillOPill(
                stringResource(R.string.saved_offline),
                background = CardMint,
                contentColor = Forest,
            )
        }

        // ---------------- sales / expenses cards ----------------
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BillOCard(background = CardMint, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.sales_in),
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedText,
                    )
                    Text(
                        formatRupees(salesBalance),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Ink,
                    )
                    Text(
                        stringResource(R.string.items_count, saleCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText,
                    )
                }
            }
            BillOCard(background = CardLime, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.expenses_out),
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedText,
                    )
                    Text(
                        formatRupees(expenseBalance),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Ink,
                    )
                    Text(
                        stringResource(R.string.entries_count, expenseEntryCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText,
                    )
                }
            }
        }

        // ---------------- about ₹X left ----------------
        BillOCard(background = Color.White, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BillOIconBadge(Icons.Filled.BarChart, Forest, CardMint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.about_left, formatRupees(cashOnHand)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(
                        stringResource(R.string.estimate_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText,
                    )
                }
            }
        }

        // ---------------- other things ----------------
        SectionHeader(stringResource(R.string.other_things))

        // ---------------- recent activity ----------------
        BillOCard(background = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.recent_activity),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                if (entries.isEmpty()) {
                    Text(
                        stringResource(R.string.ledger_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText,
                    )
                } else {
                    entries.take(5).forEach { item ->
                        val amount = item.lines.maxOf { line -> maxOf(line.debitPaise, line.creditPaise) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.entry.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatRupees(amount),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TealGreen,
                            )
                        }
                    }
                }
            }
        }

        // ---------------- advanced tools ----------------
        BillOCard(background = CardSky, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(stringResource(R.string.advanced))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = Color.White),
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
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.ledger_demo)) }
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
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.ledger_reset), color = Forest) }
                }
                ActionCard(
                    icon = Icons.Filled.Sync,
                    iconTint = Forest,
                    iconBackground = CardMint,
                    title = stringResource(R.string.sync_card),
                    background = Color.White,
                    onClick = { nav.navigate("sync") },
                )
                ActionCard(
                    icon = Icons.Filled.FactCheck,
                    iconTint = Forest,
                    iconBackground = CardLime,
                    title = stringResource(R.string.eval_card),
                    background = Color.White,
                    onClick = { nav.navigate("eval") },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
