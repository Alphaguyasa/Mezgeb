package et.alpha.mezgeb.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import et.alpha.mezgeb.ui.customers.CustomersScreen
import et.alpha.mezgeb.ui.ledger.LedgerScreen
import et.alpha.mezgeb.ui.reconcile.ReconcileScreen
import et.alpha.mezgeb.ui.reports.ReportsScreen
import et.alpha.mezgeb.ui.review.ReviewScreen
import et.alpha.mezgeb.ui.settings.SettingsScreen
import et.alpha.mezgeb.ui.today.TodayRoute

/**
 * The app's single navigation graph.
 *
 * The bottom bar shows only on the four top-level destinations; Review, Reconcile and
 * Settings are pushed on top of whatever the user was looking at and carry a back
 * affordance instead.
 */
@Composable
fun MezgebNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentEntry?.destination

    Column(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = MezgebDestination.Today,
            modifier = Modifier.weight(1f),
        ) {
            composable<MezgebDestination.Today> {
                TodayRoute(
                    onOpenReview = { navController.navigate(MezgebDestination.Review) },
                    onOpenReconcile = { navController.navigate(MezgebDestination.Reconcile) },
                    onOpenSettings = { navController.navigate(MezgebDestination.Settings) },
                )
            }
            composable<MezgebDestination.Ledger> { LedgerScreen() }
            composable<MezgebDestination.Customers> { CustomersScreen() }
            composable<MezgebDestination.Reports> { ReportsScreen() }

            composable<MezgebDestination.Review> {
                ReviewScreen(onBack = { navController.navigateUp() })
            }
            composable<MezgebDestination.Reconcile> {
                ReconcileScreen(onBack = { navController.navigateUp() })
            }
            composable<MezgebDestination.Settings> {
                SettingsScreen(onBack = { navController.navigateUp() })
            }
        }

        if (currentDestination.isTopLevel()) {
            MezgebBottomBar(
                currentDestination = currentDestination,
                onSelect = navController::navigateToTopLevel,
            )
        }
    }
}

/**
 * Single-instance tab switching: pop back to the graph's start, keep each tab's own
 * state, and never stack duplicate copies of a tab.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
