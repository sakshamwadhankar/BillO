package com.billo.ledger.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billo.ledger.BillOApplication
import com.billo.ledger.R
import com.billo.ledger.core.AccrualReport
import com.billo.ledger.core.AccrualReporter
import com.billo.ledger.core.BurnReport
import com.billo.ledger.core.CogsEstimator
import com.billo.ledger.core.CogsItem
import com.billo.ledger.core.Dist
import com.billo.ledger.core.InventoryLedger
import com.billo.ledger.core.InventorySeeder
import com.billo.ledger.core.LossAttribution
import com.billo.ledger.core.LossReport
import com.billo.ledger.core.RecipeInference
import com.billo.ledger.core.SeedSummary
import com.billo.ledger.ui.BillOCard
import com.billo.ledger.ui.CardLime
import com.billo.ledger.ui.CardMint
import com.billo.ledger.ui.CardPeach
import com.billo.ledger.ui.CardSky
import com.billo.ledger.ui.Forest
import com.billo.ledger.ui.Ink
import com.billo.ledger.ui.MutedText
import com.billo.ledger.ui.PeachDeep
import com.billo.ledger.ui.TealGreen
import com.billo.ledger.ui.formatRupees
import kotlinx.coroutines.launch

@Composable
fun StockScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as BillOApplication
    val scope = rememberCoroutineScope()
    val inventory = remember { InventoryLedger(app.database) }

    var busy by remember { mutableStateOf(false) }
    val defaultStatus = stringResource(R.string.intel_status_default)
    val sBurn = stringResource(R.string.status_burn)
    val sCogs = stringResource(R.string.status_cogs)
    val sLoss = stringResource(R.string.status_loss)
    val sAccrual = stringResource(R.string.status_accrual)
    var status by remember { mutableStateOf<String?>(null) }
    var seedSummary by remember { mutableStateOf<SeedSummary?>(null) }
    var burn by remember { mutableStateOf<BurnReport?>(null) }
    var cogs by remember { mutableStateOf<Map<String, CogsItem>?>(null) }
    var lossReports by remember { mutableStateOf<List<LossReport>?>(null) }
    var accrual by remember { mutableStateOf<AccrualReport?>(null) }
    val countedInputs = remember { mutableStateMapOf<String, String>() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.intel_title),
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Ink,
        )
        Text(
            status ?: defaultStatus,
            style = MaterialTheme.typography.bodySmall,
            color = TealGreen,
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest)

        // ---------------- seed + evaluation chain (lime card) ----------------
        BillOCard(background = CardLime, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.chain_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                app.ledger.clearAll()
                                app.ledger.ensureSeeded()
                                val seed = InventorySeeder.seedDemo(app.ledger, inventory)
                                seedSummary = seed
                                burn = null; cogs = null; lossReports = null; accrual = null
                                countedInputs.clear()
                                inventory.allItems().filter { it.kind == com.billo.ledger.data.db.ItemKind.RAW }.forEach {
                                    countedInputs[it.name] = "%.2f".format(inventory.stockOnHand(it.name))
                                }
                                status = context.getString(
                                    R.string.status_seeded,
                                    seed.days,
                                    seed.soldPlates,
                                    formatRupees(seed.revenuePaise),
                                )
                            } finally {
                                busy = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.seed_month)) }

                OutlinedButton(
                    enabled = !busy && seedSummary != null,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                burn = RecipeInference(inventory).inferBurn()
                                status = sBurn
                            } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.step_burn), color = Forest) }

                OutlinedButton(
                    enabled = !busy && burn != null,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                cogs = CogsEstimator(inventory).estimate()
                                status = sCogs
                            } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.step_cogs), color = Forest) }

                OutlinedButton(
                    enabled = !busy && seedSummary != null,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val counted = countedInputs.mapValues { it.value.toDoubleOrNull() ?: 0.0 }
                                lossReports = LossAttribution(inventory, app.ledger).attributeAndPost(counted)
                                status = sLoss
                            } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.step_loss), color = Forest) }

                OutlinedButton(
                    enabled = !busy && cogs != null,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                accrual = AccrualReporter(app.ledger, inventory).report()
                                status = sAccrual
                            } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.step_accrual), color = Forest) }
            }
        }

        // ---------------- ground truth (sky card) ----------------
        seedSummary?.let { seed ->
            BillOCard(background = CardSky, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.ground_truth),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(
                        "${seed.days} days · ${seed.soldPlates} plates · revenue ${formatRupees(seed.revenuePaise)} · " +
                            "COGS ${formatRupees(seed.cogsPaise)} · losses ${formatRupees(seed.lossValuePaise)} · " +
                            "profit after loss ${formatRupees(seed.profitAfterLossPaise)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText,
                    )
                }
            }
        }

        // ---------------- zero-shot recipe (mint card) ----------------
        burn?.let { b ->
            BillOCard(background = CardMint, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.zero_shot_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    b.estimates.forEach { e ->
                        val (lo, hi) = e.gramsPerUnit.ci90()
                        val displayUnit = if (e.rawUnit == "litre") "ml" else "g"
                        Text(
                            "${e.rawItem}: %.1f %s/plate (90%% CI %.1f–%.1f)".format(
                                e.gramsPerUnit.mean(), displayUnit, lo, hi,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink,
                        )
                        DistBars(e.gramsPerUnit, Modifier.fillMaxWidth().height(28.dp))
                    }
                    b.portionDrift.forEach { d ->
                        Text(
                            stringResource(
                                R.string.portion_drift,
                                d.rawItem,
                                String.format("%.1f", d.overallPerUnit),
                                String.format("%.1f", d.recentPerUnit),
                                String.format("%.1f", d.driftPct),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (kotlin.math.abs(d.driftPct) > 8) Color(0xFFE57373) else MutedText,
                        )
                    }
                    Text(stringResource(R.string.zero_shot_note), style = MaterialTheme.typography.labelSmall, color = MutedText)
                }
            }
        }

        // ---------------- COGS (white card) ----------------
        cogs?.let { c ->
            BillOCard(background = Color.White, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.cogs_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    c.forEach { (item, cogsItem) ->
                        val (lo, hi) = cogsItem.cogsPerUnitPaise.ci90()
                        Text(
                            "$item: ${formatRupees(cogsItem.cogsPerUnitPaise.mean().toLong())}/plate (90% CI ${formatRupees(lo.toLong())}–${formatRupees(hi.toLong())})",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink,
                        )
                        Text(
                            stringResource(
                                R.string.cogs_components,
                                cogsItem.components.entries.joinToString { "${it.key} ${formatRupees(it.value.toLong())}" },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedText,
                        )
                    }
                }
            }
        }

        // ---------------- stock-count inputs (peach card) ----------------
        if (countedInputs.isNotEmpty()) {
            BillOCard(background = CardPeach, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.stockcount_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(
                        stringResource(R.string.stockcount_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText,
                    )
                    countedInputs.forEach { (name, value) ->
                        OutlinedTextField(
                            value = value,
                            onValueChange = { countedInputs[name] = it },
                            label = { Text(stringResource(R.string.counted_label, name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }
        }

        // ---------------- loss attribution (white card) ----------------
        lossReports?.let { reports ->
            BillOCard(background = Color.White, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.loss_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    reports.forEach { r ->
                        Text(
                            r.itemName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                        )
                        Text(
                            stringResource(
                                R.string.loss_line,
                                String.format("%.2f", r.expectedQty),
                                String.format("%.2f", r.countedQty),
                                String.format("%.2f", r.lossQtyDist.mean()),
                                r.verdict,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText,
                        )
                        CauseBar(stringResource(R.string.cause_spoilage), r.causes.spoilage, TealGreen)
                        CauseBar(stringResource(R.string.cause_theft), r.causes.theft, PeachDeep)
                        CauseBar(stringResource(R.string.cause_personal), r.causes.personal, Color(0xFFFFB300))
                        if (r.postedPaise > 0) {
                            Text(
                                stringResource(
                                    R.string.loss_posted,
                                    formatRupees(r.postedPaise),
                                    r.covariates.entries.joinToString { "${it.key}=${it.value}" },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedText,
                            )
                        }
                    }
                }
            }
        }

        // ---------------- accrual profit (mint card) ----------------
        accrual?.let { a ->
            BillOCard(background = CardMint, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.accrual_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    val (lo, hi) = a.accrualProfitPaise.ci90()
                    Text(
                        formatRupees(a.accrualProfitPaise.mean().toLong()),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Ink,
                    )
                    Text(
                        stringResource(R.string.ci_band, formatRupees(lo.toLong()), formatRupees(hi.toLong())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Forest,
                    )
                    DistBars(a.accrualProfitPaise, Modifier.fillMaxWidth().height(48.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.cash_on_hand, formatRupees(a.cashOnHandPaise)), style = MaterialTheme.typography.bodySmall, color = MutedText)
                    Text(stringResource(R.string.receivables_line, formatRupees(a.receivablesPaise)), style = MaterialTheme.typography.bodySmall, color = MutedText)
                    Text(stringResource(R.string.cash_profit_line, formatRupees(a.cashProfitPaise.mean().toLong())), style = MaterialTheme.typography.bodySmall, color = MutedText)
                    Text(stringResource(R.string.accrual_revenue_line, formatRupees(a.accrualRevenuePaise.mean().toLong())), style = MaterialTheme.typography.bodySmall, color = MutedText)
                    Text(stringResource(R.string.true_cogs_line, formatRupees(a.cogsPaise.mean().toLong())), style = MaterialTheme.typography.bodySmall, color = MutedText)
                    Text(stringResource(R.string.attributed_losses_line, formatRupees(a.recordedLossPaise)), style = MaterialTheme.typography.bodySmall, color = MutedText)
                    Text(stringResource(R.string.accrual_note), style = MaterialTheme.typography.labelSmall, color = MutedText)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CauseBar(label: String, fraction: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(70.dp), color = Ink)
        LinearProgressIndicator(
            progress = { fraction.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f),
            color = color,
        )
        Text(
            "${(fraction * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 6.dp),
            color = Ink,
        )
    }
}

@Composable
fun DistBars(dist: Dist, modifier: Modifier = Modifier) {
    val pts = dist.normalized().points
    val minV = pts.minOf { it.value }
    val maxV = pts.maxOf { it.value }
    val maxW = pts.maxOf { it.weight }.coerceAtLeast(1e-9)
    Canvas(modifier) {
        val range = (maxV - minV).coerceAtLeast(1e-9)
        pts.forEach { p ->
            val x = ((p.value - minV) / range).toFloat()
            val h = ((p.weight / maxW).toFloat().coerceIn(0f, 1f)) * size.height
            drawLine(
                color = Color(0xFFFFB300),
                start = Offset(x * size.width, size.height),
                end = Offset(x * size.width, size.height - h),
                strokeWidth = 3f,
            )
        }
    }
}
