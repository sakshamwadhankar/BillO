package com.billo.ledger.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.billo.ledger.ui.screens.CaptureScreen
import com.billo.ledger.ui.screens.EvidenceScreen
import com.billo.ledger.ui.screens.LedgerScreen

private val TABS = listOf(
    Triple("evidence", "Evidence", Icons.Filled.ReceiptLong),
    Triple("ledger", "Ledger", Icons.Filled.MenuBook),
    Triple("capture", "Capture", Icons.Filled.Mic),
)

@Composable
fun BillONav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { (route, label, icon) ->
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
            composable("evidence") { EvidenceScreen() }
            composable("ledger") { LedgerScreen() }
            composable("capture") { CaptureScreen() }
        }
    }
}
