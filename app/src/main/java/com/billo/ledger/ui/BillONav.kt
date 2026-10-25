package com.billo.ledger.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.billo.ledger.R
import com.billo.ledger.ui.screens.CaptureScreen
import com.billo.ledger.ui.screens.EvalScreen
import com.billo.ledger.ui.screens.EvidenceScreen
import com.billo.ledger.ui.screens.IntelligenceScreen
import com.billo.ledger.ui.screens.LedgerScreen
import com.billo.ledger.ui.screens.SyncScreen

private val TABS = listOf(
    Triple("evidence", R.string.nav_evidence, Icons.Filled.ReceiptLong),
    Triple("ledger", R.string.nav_ledger, Icons.Filled.MenuBook),
    Triple("intel", R.string.nav_intel, Icons.Filled.Insights),
    Triple("sync", R.string.nav_sync, Icons.Filled.Sync),
    Triple("eval", R.string.nav_eval, Icons.Filled.FactCheck),
)

@Composable
fun BillONav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { (route, labelRes, icon) ->
                    val label = stringResource(labelRes)
                    NavigationBarItem(
                        selected = currentRoute == route,
                        onClick = {
                            if (currentRoute != route) {
                                nav.navigate(route) { launchSingleTop = true }
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "evidence",
            modifier = Modifier.padding(padding),
        ) {
            composable("evidence") { EvidenceScreen(onCapture = { nav.navigate("capture") { launchSingleTop = true } }) }
            composable("ledger") { LedgerScreen() }
            composable("intel") { IntelligenceScreen() }
            composable("sync") { SyncScreen() }
            composable("eval") { EvalScreen() }
            composable("capture") { CaptureScreen() }
        }
    }
}
