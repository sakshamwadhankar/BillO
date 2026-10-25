package com.billo.ledger.ui.screens

import android.bluetooth.BluetoothManager
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billo.ledger.BillOApplication
import com.billo.ledger.sync.SyncEvent
import com.billo.ledger.core.Hlc
import com.billo.ledger.data.db.LedgerOpEntity
import com.billo.ledger.data.db.OpType
import com.billo.ledger.sync.BluetoothSyncService
import com.billo.ledger.sync.ChaosReport
import com.billo.ledger.sync.ChaosSimulator
import com.billo.ledger.sync.CrdtMerge
import com.billo.ledger.sync.MergeReport
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun SyncScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as BillOApplication
    val scope = rememberCoroutineScope()

    var opCount by remember { mutableStateOf(0) }
    var chaosReport by remember { mutableStateOf<ChaosReport?>(null) }
    var mergeReport by remember { mutableStateOf<MergeReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    val logLines = remember { mutableStateListOf<String>() }

    fun log(line: String) = logLines.add(0, line.take(160)).let { }

    LaunchedEffect(Unit) {
        opCount = app.database.opLogDao().opCount()
        log("This device node: ${app.nodeId.take(8)}…")
    }

    // ---- Bluetooth ----
    var btGranted by remember { mutableStateOf(false) }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { btGranted = it }
    LaunchedEffect(Unit) {
        btGranted = runCatching {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_CONNECT,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }
    val bt = remember {
        BluetoothSyncService(context, app.clock) { event ->
            when (event) {
                is SyncEvent.Status -> log("BT: ${event.message}")
                is SyncEvent.Progress -> log("BT: ${event.message}")
                is SyncEvent.Done -> log("BT: exchanged ${event.sentOps}↑ ${event.receivedOps}↓ ops")
                is SyncEvent.Failed -> log("BT failed: ${event.message}")
            }
        }
    }
    val devices = remember { mutableStateListOf<android.bluetooth.BluetoothDevice>() }
    var selectedDevice by remember { mutableStateOf(0) }
    fun refreshDevices() {
        devices.clear()
        runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            adapter?.bondedDevices?.let { devices.addAll(it.sortedBy { d -> d.name ?: "?" }) }
        }.onFailure { log("BT devices: ${it.message}") }
    }
    LaunchedEffect(btGranted) { if (btGranted) refreshDevices() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sync", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Node ${app.nodeId.take(8)}… · $opCount CRDT ops in the local log. " +
                "Merging is a commutative union of op-logs — order never matters.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        // ---------------- hostile clock chaos test ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Hostile-clock chaos test", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Simulates 3 family devices whose clocks reset to 1970, jump 90 days forward and drift backwards, then merges their op-logs.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(enabled = !busy, onClick = {
                    busy = true
                    try {
                        chaosReport = ChaosSimulator().run()
                        log("Chaos: converged=${chaosReport?.mergedOpsIdentical} balanced=${chaosReport?.allBalanced} winner=${chaosReport?.sameWinnerEverywhere}")
                    } finally { busy = false }
                }) { Text("Run chaos test") }
                chaosReport?.let { r ->
                    Text(
                        "converged=${r.mergedOpsIdentical} · balanced=${r.allBalanced} · " +
                            "same winner everywhere=${r.sameWinnerEverywhere} · ${r.totalOps} ops · " +
                            "${r.anomaliesInjected} clock anomalies survived",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.mergedOpsIdentical && r.allBalanced && r.sameWinnerEverywhere) {
                            Color(0xFF26A69A)
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    r.nodes.forEach { n ->
                        Text("${n.nodeName}: ${n.ops} ops, floor ${n.finalClockFloor}, ${n.anomaliesSurvived} anomalies",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // ---------------- clipboard sync bridge ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Op-log transfer (clipboard bridge)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Bluetooth between real devices; on an emulator, move the op-log between two app instances via the clipboard — both go through the identical CRDT merge path.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val ops = app.database.opLogDao().allOps()
                            val arr = JSONArray()
                            ops.forEach { o ->
                                arr.put(
                                    JSONObject()
                                        .put("opId", o.opId)
                                        .put("hlcWall", o.hlcWall)
                                        .put("hlcCounter", o.hlcCounter)
                                        .put("nodeId", o.nodeId)
                                        .put("type", o.type.name)
                                        .put("payloadJson", o.payloadJson)
                                        .put("appliedAtWall", o.appliedAtWall),
                                )
                            }
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("billo-ops", arr.toString()))
                            log("Exported ${ops.size} ops to clipboard (${arr.toString().length} chars)")
                        }
                    }) { Text("Export ops") }

                    OutlinedButton(onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
                                if (text.isNullOrBlank()) {
                                    log("Clipboard empty")
                                    return@launch
                                }
                                val arr = JSONArray(text)
                                val ops = (0 until arr.length()).map { i ->
                                    val o = arr.getJSONObject(i)
                                    LedgerOpEntity(
                                        opId = o.getString("opId"),
                                        hlcWall = o.getLong("hlcWall"),
                                        hlcCounter = o.getLong("hlcCounter"),
                                        nodeId = o.getString("nodeId"),
                                        type = OpType.valueOf(o.getString("type")),
                                        payloadJson = o.getString("payloadJson"),
                                        appliedAtWall = o.optLong("appliedAtWall"),
                                    )
                                }
                                val report = CrdtMerge(app.database, app.ledger, app.clock).merge(ops)
                                mergeReport = report
                                opCount = app.database.opLogDao().opCount()
                                log("Merged ${report.receivedOps} remote ops: ${report.appliedOps} applied, ${report.duplicateOps} duplicates, ${report.conflictsResolved} conflicts resolved")
                            } catch (e: Exception) {
                                log("Import failed: ${e.message}")
                            } finally { busy = false }
                        }
                    }) { Text("Import & merge") }
                }
                mergeReport?.let { r ->
                    Text(
                        "balanced after merge = ${r.balanced} · entries applied ${r.entriesApplied} · accounts ${r.accountsApplied}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ---------------- Bluetooth ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Bluetooth sync (real devices)", style = MaterialTheme.typography.titleSmall)
                if (!btGranted) {
                    Button(onClick = { btLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT) }) {
                        Text("Grant Bluetooth permission")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { bt.startServer(); log("BT server listening") }) { Text("Start server") }
                        OutlinedButton(onClick = { bt.stopServer(); log("BT server stopped") }) { Text("Stop") }
                    }
                    if (devices.isEmpty()) {
                        Text("No bonded devices. Pair a second device in system settings.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        devices.forEachIndexed { i, d ->
                            val name = runCatching { d.name ?: "device" }.getOrDefault("device")
                            Row(Modifier.fillMaxWidth()) {
                                androidx.compose.material3.FilterChip(
                                    selected = selectedDevice == i,
                                    onClick = { selectedDevice = i },
                                    label = { Text(name) },
                                )
                            }
                        }
                        Button(onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val device = devices[selectedDevice]
                                    val ops = app.database.opLogDao().allOps()
                                    bt.connectAndSync(device, ops) { remote ->
                                        scope.launch {
                                            val report = CrdtMerge(app.database, app.ledger, app.clock).merge(remote)
                                            mergeReport = report
                                            opCount = app.database.opLogDao().opCount()
                                            log("BT merge: ${report.appliedOps} applied, ${report.duplicateOps} duplicates")
                                        }
                                    }
                                } finally { busy = false }
                            }
                        }) { Text("Connect & sync") }
                    }
                }
            }
        }

        // ---------------- log ----------------
        if (logLines.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Log", style = MaterialTheme.typography.titleSmall)
                    logLines.take(8).forEach { line ->
                        Text(line, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
