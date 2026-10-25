package com.billo.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.billo.ledger.R
import com.billo.ledger.ui.screens.CaptureScreen
import com.billo.ledger.ui.screens.EntriesScreen
import com.billo.ledger.ui.screens.EvalScreen
import com.billo.ledger.ui.screens.HomeScreen
import com.billo.ledger.ui.screens.PeopleScreen
import com.billo.ledger.ui.screens.StockScreen
import com.billo.ledger.ui.screens.SummaryScreen
import com.billo.ledger.ui.screens.SyncScreen

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", R.string.nav_home, Icons.Filled.Home),
    Tab("entries", R.string.nav_entries, Icons.Filled.ReceiptLong),
    Tab("stock", R.string.nav_stock, Icons.Filled.Inventory2),
    Tab("people", R.string.nav_people, Icons.Filled.People),
    Tab("summary", R.string.nav_summary, Icons.Filled.BarChart),
)

private val BOTTOM_ROUTES = TABS.map { it.route }.toSet()

@Composable
fun BillONav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        containerColor = Cream,
        bottomBar = {
            if (currentRoute in BOTTOM_ROUTES) {
                BillOBottomBar(nav, currentRoute)
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") { HomeScreen(nav) }
            composable("entries") { EntriesScreen() }
            composable("stock") { StockScreen() }
            composable("people") { PeopleScreen() }
            composable("summary") { SummaryScreen(nav) }
            composable(
                route = "capture?preset={preset}",
                arguments = listOf(navArgument("preset") { defaultValue = "" }),
            ) { entry ->
                CaptureScreen(preset = entry.arguments?.getString("preset") ?: "")
            }
            composable("eval") { EvalScreen() }
            composable("sync") { SyncScreen() }
        }
    }
}

@Composable
private fun BillOBottomBar(nav: NavHostController, currentRoute: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TABS.forEach { tab ->
            val selected = currentRoute == tab.route
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        if (currentRoute != tab.route) {
                            nav.navigate(tab.route) {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                    .padding(vertical = 2.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) Forest else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = stringResource(tab.labelRes),
                        tint = if (selected) Color.White else MutedText,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    stringResource(tab.labelRes),
                    fontSize = 11.sp,
                    color = if (selected) Forest else MutedText,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
