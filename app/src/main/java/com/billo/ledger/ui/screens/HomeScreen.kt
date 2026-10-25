package com.billo.ledger.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.billo.ledger.BillOApplication
import com.billo.ledger.R
import com.billo.ledger.ui.ActionCard
import com.billo.ledger.ui.BillOCard
import com.billo.ledger.ui.BillOIconBadge
import com.billo.ledger.ui.BillOPill
import com.billo.ledger.ui.CardLime
import com.billo.ledger.ui.CardMint
import com.billo.ledger.ui.CardPeach
import com.billo.ledger.ui.CardSky
import com.billo.ledger.ui.Forest
import com.billo.ledger.ui.Ink
import com.billo.ledger.ui.MutedText
import com.billo.ledger.ui.PeachDeep
import com.billo.ledger.ui.PrimaryHeroButton
import com.billo.ledger.ui.SectionHeader
import com.billo.ledger.ui.StatTile
import com.billo.ledger.ui.TealGreen
import com.billo.ledger.ui.formatRupees
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(nav: NavHostController) {
    val app = LocalContext.current.applicationContext as BillOApplication

    // Pre-resolved strings (usable inside lambdas too)
    val tagline = stringResource(R.string.app_tagline)
    val greeting = stringResource(R.string.greeting)
    val shopName = stringResource(R.string.shop_name)
    val offlineReady = stringResource(R.string.offline_ready)
    val todaysSalesLabel = stringResource(R.string.todays_sales)
    val addWhatHappened = stringResource(R.string.add_what_happened)
    val speakTypePhoto = stringResource(R.string.speak_type_photo)
    val quickActions = stringResource(R.string.quick_actions)
    val seeAll = stringResource(R.string.see_all)
    val qaSold = stringResource(R.string.qa_sold)
    val qaBought = stringResource(R.string.qa_bought)
    val qaPaid = stringResource(R.string.qa_paid)
    val qaMissing = stringResource(R.string.qa_missing)
    val todaysSummary = stringResource(R.string.todays_summary)
    val moneyIn = stringResource(R.string.money_in)
    val moneyOut = stringResource(R.string.money_out)
    val noEntriesToday = stringResource(R.string.no_entries_today)

    var todayStart by remember { mutableStateOf(System.currentTimeMillis()) }
    var todaySalesPaise by remember { mutableLongStateOf(0L) }
    var todayItems by remember { mutableStateOf(0) }
    var todayOutPaise by remember { mutableLongStateOf(0L) }
    var hasEntriesToday by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        todayStart = start
        var sales = 0L
        var out = 0L
        var items = 0
        app.ledger.entries().first()
            .filter { it.entry.wallTimeMillis >= start }
            .forEach { entry ->
                var hasSale = false
                entry.lines.forEach { line ->
                    if (line.accountName == "Sales" && line.creditPaise > 0) {
                        sales += line.creditPaise
                        hasSale = true
                    }
                    if (line.accountName == "Cash" && line.creditPaise > 0) {
                        out += line.creditPaise
                    }
                }
                if (hasSale) items++
            }
        hasEntriesToday = sales > 0 || out > 0 || items > 0
        todaySalesPaise = sales
        todayItems = items
        todayOutPaise = out
    }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BillO", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                    Spacer(Modifier.width(8.dp))
                    Image(
                        painterResource(R.drawable.ic_cat),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Text(tagline, style = MaterialTheme.typography.bodySmall, color = MutedText)
            }
            BillOPill(offlineReady, background = CardMint, contentColor = Forest)
        }

        // ---------------- greeting ----------------
        Column {
            Text(greeting, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Row {
                Text(
                    "\uD83C\uDFEA " + shopName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedText,
                )
            }
        }

        // ---------------- today's sales card ----------------
        BillOCard(background = CardMint, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BillOIconBadge(Icons.Filled.CurrencyRupee, TealGreen, Color.White)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(todaysSalesLabel, style = MaterialTheme.typography.labelMedium, color = MutedText)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            formatRupees(todaySalesPaise),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Ink,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.from_items, todayItems),
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedText,
                        )
                    }
                }
                Icon(
                    Icons.Filled.BarChart,
                    contentDescription = null,
                    tint = MutedText,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        // ---------------- hero button ----------------
        PrimaryHeroButton(
            title = addWhatHappened,
            subtitle = speakTypePhoto,
            leadingIcon = Icons.Filled.Mic,
            onClick = { nav.navigate("capture") },
        )

        // ---------------- quick actions ----------------
        SectionHeader(title = quickActions, trailing = seeAll, onTrailing = { nav.navigate("entries") })

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f)) {
                ActionCard(
                    Icons.Filled.ShoppingCart, TealGreen, Color.White,
                    title = qaSold, background = CardMint,
                    onClick = { nav.navigate("capture?preset=sale") },
                )
            }
            Box(Modifier.weight(1f)) {
                ActionCard(
                    Icons.Filled.Inventory2, Forest, Color.White,
                    title = qaBought, background = CardLime,
                    onClick = { nav.navigate("capture?preset=stock") },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f)) {
                ActionCard(
                    Icons.Filled.People, TealGreen, Color.White,
                    title = qaPaid, background = CardSky,
                    onClick = { nav.navigate("capture?preset=people") },
                )
            }
            Box(Modifier.weight(1f)) {
                ActionCard(
                    Icons.Filled.Warning, PeachDeep, Color.White,
                    title = qaMissing, background = CardPeach,
                    onClick = { nav.navigate("stock") },
                )
            }
        }

        // ---------------- today's summary ----------------
        BillOCard(background = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        todaysSummary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(
                        SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(todayStart)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        background = CardMint,
                        arrowTint = TealGreen,
                        label = moneyIn,
                        value = formatRupees(todaySalesPaise),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        background = CardPeach,
                        arrowTint = PeachDeep,
                        label = moneyOut,
                        value = formatRupees(todayOutPaise),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!hasEntriesToday) {
                    Text(noEntriesToday, style = MaterialTheme.typography.bodySmall, color = MutedText)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
