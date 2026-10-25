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
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billo.ledger.BillOApplication
import com.billo.ledger.R
import com.billo.ledger.data.ledger.Balance
import com.billo.ledger.ui.ActionCard
import com.billo.ledger.ui.BillOCard
import com.billo.ledger.ui.BillOIconBadge
import com.billo.ledger.ui.CardLime
import com.billo.ledger.ui.CardMint
import com.billo.ledger.ui.Ink
import com.billo.ledger.ui.MutedText
import com.billo.ledger.ui.TealGreen
import com.billo.ledger.ui.formatRupees

@Composable
fun PeopleScreen() {
    val app = LocalContext.current.applicationContext as BillOApplication
    val balances by produceState<List<Balance>>(initialValue = emptyList()) {
        value = app.ledger.balances()
    }
    val receivables = balances.filter { it.account.parentName == "Receivables" && it.paise > 0 }
    val totalOwed = receivables.sumOf { it.paise }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------------- header ----------------
        Column {
            Text(
                stringResource(R.string.people_screen_title),
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

        // ---------------- total card ----------------
        BillOCard(background = CardMint, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BillOIconBadge(Icons.Filled.People, TealGreen, Color.White)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.people_screen_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(
                        formatRupees(totalOwed),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Ink,
                    )
                    Text(
                        stringResource(R.string.from_customers, formatRupees(totalOwed), receivables.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText,
                    )
                }
            }
        }

        // ---------------- debtor list ----------------
        if (receivables.isEmpty()) {
            BillOCard(background = CardLime, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.no_debtors),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            receivables.forEachIndexed { index, balance ->
                val display = balance.account.name
                    .substringAfter("·", balance.account.name)
                    .trim()
                ActionCard(
                    icon = Icons.Filled.Person,
                    iconTint = TealGreen,
                    iconBackground = Color.White,
                    title = display,
                    subtitle = formatRupees(balance.paise),
                    background = if (index % 2 == 0) CardMint else Color.White,
                    onClick = {},
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
