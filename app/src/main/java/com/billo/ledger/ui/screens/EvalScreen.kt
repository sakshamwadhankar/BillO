package com.billo.ledger.ui.screens

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billo.ledger.BillOApplication
import com.billo.ledger.R
import com.billo.ledger.eval.EvaluationHarness
import kotlinx.coroutines.launch

@Composable
fun EvalScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as BillOApplication
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<EvaluationHarness.EvalReport?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.eval_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.eval_desc),
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        report = EvaluationHarness(app).run()
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) stringResource(R.string.running) else stringResource(R.string.run_eval)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        report?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        r.summary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (r.passedAll) Color(0xFF26A69A) else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    r.checks.forEach { check ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                if (check.passed) "✓" else "✗",
                                color = if (check.passed) Color(0xFF26A69A) else Color(0xFFE57373),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Column {
                                Text(check.name, style = MaterialTheme.typography.bodyMedium)
                                Text(check.detail, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.eval_chain),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
