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
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billo.ledger.BillOApplication
import com.billo.ledger.core.AccrualReport
import com.billo.ledger.core.BurnReport
import com.billo.ledger.core.CogsItem
import com.billo.ledger.core.Dist
import com.billo.ledger.core.InventoryLedger
import com.billo.ledger.core.InventorySeeder
import com.billo.ledger.core.SeedSummary
import com.billo.ledger.core.LossAttribution
import com.billo.ledger.core.LossReport
import com.billo.ledger.core.RecipeInference
import com.billo.ledger.core.AccrualReporter
import com.billo.ledger.ui.formatRupees
import kotlinx.coroutines.launch

@Composable
fun IntelligenceScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as BillOApplication
    val scope = rememberCoroutineScope()
    val inventory = remember { InventoryLedger(app.database) }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Seed a demo month, then walk the evaluation chain.") }
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Intelligence", style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        // ---------------- seed + evaluation chain ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Raw Material Burn → Inferred Wastage → Reconciled Credit → True Accrual Profit",
                    style = MaterialTheme.typography.titleSmall)
                Button(enabled = !busy, onClick = {
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
                            status = "Seeded ${seed.days} days: ${seed.soldPlates} plates, revenue ₹${seed.revenuePaise / 100}"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Seed 30-day demo month") }

                OutlinedButton(
                    enabled = !busy && seedSummary != null,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                burn = RecipeInference(inventory).inferBurn()
                                status = "Burn rates inferred — zero-shot, no BOM"
                            } finally { busy = false }
                        }
                    },
                ) { Text("1 · Infer burn rates") }

                OutlinedButton(
                    enabled = !busy && burn != null,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                cogs = com.billo.ledger.core.CogsEstimator(inventory).estimate()
                                status = "COGS estimated by Monte-Carlo over burn × cost"
                            } finally { busy = false }
                        }
                    },
                ) { Text("2 · Estimate COGS") }

                OutlinedButton(
                    enabled = !busy && seedSummary != null,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val counted = countedInputs.mapValues { it.value.toDoubleOrNull() ?: 0.0 }
                                lossReports = LossAttribution(inventory, app.ledger).attributeAndPost(counted)
                                status = "Unrecorded loss attributed as spoilage/theft/personal"
                            } finally { busy = false }
                        }
                    },
                ) { Text("3 · Attribute loss (stock count)") }

                OutlinedButton(
                    enabled = !busy && cogs != null,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                accrual = AccrualReporter(app.ledger, inventory).report()
                                status = "True accrual profit computed"
                            } finally { busy = false }
                        }
                    },
                ) { Text("4 · True accrual profit") }
            }
        }

        seedSummary?.let { seed ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Ground truth (hidden from the engine)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${seed.days} days · ${seed.soldPlates} plates · revenue ${formatRupees(seed.revenuePaise)} · " +
                            "COGS ${formatRupees(seed.cogsPaise)} · losses ${formatRupees(seed.lossValuePaise)} · " +
                            "profit after loss ${formatRupees(seed.profitAfterLossPaise)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        burn?.let { b ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Zero-shot recipe (burn rates)", style = MaterialTheme.typography.titleSmall)
                    b.estimates.forEach { e ->
                        val (lo, hi) = e.gramsPerUnit.ci90()
                        val displayUnit = if (e.rawUnit == "litre") "ml" else "g"
                        Text(
                            "${e.rawItem}: %.1f %s/plate (90%% CI %.1f–%.1f)".format(
                                e.gramsPerUnit.mean(), displayUnit, lo, hi,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    b.portionDrift.forEach { d ->
                        Text(
                            "portion drift ${d.rawItem}: %.1f → %.1f (%.1f%%)".format(
                                d.overallPerUnit, d.recentPerUnit, d.driftPct,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (kotlin.math.abs(d.driftPct) > 8) Color(0xFFE57373) else Color.Unspecified,
                        )
                    }
                    Text(b.note, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        cogs?.let { c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("COGS without a BOM", style = MaterialTheme.typography.titleSmall)
                    c.forEach { (item, cogsItem) ->
                        val (lo, hi) = cogsItem.cogsPerUnitPaise.ci90()
                        Text(
                            "$item: ${formatRupees(cogsItem.cogsPerUnitPaise.mean().toLong())}/plate (90% CI ${formatRupees(lo.toLong())}–${formatRupees(hi.toLong())})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "components: " + cogsItem.components.entries.joinToString { "${it.key} ${formatRupees(it.value.toLong())}" },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        if (countedInputs.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Stock count (what you actually see)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Lower any number to record a shortfall — the engine attributes it to spoilage, theft or personal consumption as a probability distribution.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    countedInputs.forEach { (name, value) ->
                        OutlinedTextField(
                            value = value,
                            onValueChange = { countedInputs[name] = it },
                            label = { Text("$name counted") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }
        }

        lossReports?.let { reports ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Loss attribution", style = MaterialTheme.typography.titleSmall)
                    reports.forEach { r ->
                        Text(r.itemName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "expected %.2f, counted %.2f, loss %.2f — %s".format(
                                r.expectedQty, r.countedQty, r.lossQtyDist.mean(), r.verdict,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        CauseBar("spoilage", r.causes.spoilage, Color(0xFF26A69A))
                        CauseBar("theft", r.causes.theft, Color(0xFFE57373))
                        CauseBar("personal", r.causes.personal, Color(0xFFFFB300))
                        if (r.postedPaise > 0) {
                            Text("posted ${formatRupees(r.postedPaise)} · ${r.covariates.entries.joinToString { "${it.key}=${it.value}" }}",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        accrual?.let { a ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("True accrual profit", style = MaterialTheme.typography.titleSmall)
                    val (lo, hi) = a.accrualProfitPaise.ci90()
                    Text(
                        formatRupees(a.accrualProfitPaise.mean().toLong()),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "90% CI ${formatRupees(lo.toLong())} – ${formatRupees(hi.toLong())}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    DistBars(a.accrualProfitPaise, Modifier.fillMaxWidth().height(48.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("cash on hand: ${formatRupees(a.cashOnHandPaise)}", style = MaterialTheme.typography.bodySmall)
                    Text("receivables (implicit credit): ${formatRupees(a.receivablesPaise)}", style = MaterialTheme.typography.bodySmall)
                    Text("cash profit: ${formatRupees(a.cashProfitPaise.mean().toLong())}", style = MaterialTheme.typography.bodySmall)
                    Text("accrual revenue: ${formatRupees(a.accrualRevenuePaise.mean().toLong())}", style = MaterialTheme.typography.bodySmall)
                    Text("true COGS: ${formatRupees(a.cogsPaise.mean().toLong())}", style = MaterialTheme.typography.bodySmall)
                    Text("attributed losses: ${formatRupees(a.recordedLossPaise)}", style = MaterialTheme.typography.bodySmall)
                    Text(a.note, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CauseBar(label: String, fraction: Double, color: Color) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(70.dp))
        LinearProgressIndicator(
            progress = { fraction.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f),
            color = color,
        )
        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp))
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
