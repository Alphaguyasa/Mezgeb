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
import et.alpha.mezgeb.ui.cash.CashEntryRoute
import et.alpha.mezgeb.ui.customers.CreditEntryRoute
import et.alpha.mezgeb.ui.customers.CustomerDetailRoute
import et.alpha.mezgeb.ui.customers.CustomersRoute
import et.alpha.mezgeb.ui.detail.TransactionDetailRoute
import et.alpha.mezgeb.ui.ledger.LedgerRoute
import et.alpha.mezgeb.ui.onboarding.OnboardingRoute
import et.alpha.mezgeb.ui.reconcile.ReconcileRoute
import et.alpha.mezgeb.ui.reports.ReportsRoute
import et.alpha.mezgeb.ui.review.ReviewRoute
import et.alpha.mezgeb.ui.rule.RuleBuilderRoute
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
    startDestination: MezgebDestination = MezgebDestination.Today,
    navController: NavHostController = rememberNavController(),
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentEntry?.destination

    Column(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.weight(1f),
        ) {
            composable<MezgebDestination.Onboarding> {
                OnboardingRoute(
                    onFinished = {
                        navController.navigate(MezgebDestination.Today) {
                            // Onboarding is done: system back from Today must leave the
                            // app, not walk back into the explanation screen.
                            popUpTo(MezgebDestination.Onboarding) { inclusive = true }
                        }
                    },
                )
            }

            composable<MezgebDestination.Today> {
                TodayRoute(
                    onOpenReview = { navController.navigate(MezgebDestination.Review) },
                    onOpenReconcile = { navController.navigate(MezgebDestination.Reconcile) },
                    onOpenSettings = { navController.navigate(MezgebDestination.Settings) },
                    onOpenTransaction = { navController.navigateToTransaction(it) },
                    onRecordCashSale = { navController.navigate(MezgebDestination.CashEntry) },
                )
            }
            composable<MezgebDestination.Ledger> {
                LedgerRoute(onOpenTransaction = { navController.navigateToTransaction(it) })
            }
            composable<MezgebDestination.Customers> {
                CustomersRoute(
                    onOpenCustomer = {
                        navController.navigate(MezgebDestination.CustomerDetail(it))
                    },
                )
            }
            composable<MezgebDestination.CustomerDetail> {
                CustomerDetailRoute(
                    onBack = { navController.navigateUp() },
                    onAddCredit = {
                        navController.navigate(
                            MezgebDestination.CreditEntry(it, isRepayment = false),
                        )
                    },
                    onRecordRepayment = {
                        navController.navigate(
                            MezgebDestination.CreditEntry(it, isRepayment = true),
                        )
                    },
                )
            }
            composable<MezgebDestination.CreditEntry> {
                CreditEntryRoute(onBack = { navController.navigateUp() })
            }
            composable<MezgebDestination.Reports> { ReportsRoute() }

            composable<MezgebDestination.Review> {
                ReviewRoute(
                    onBack = { navController.navigateUp() },
                    onTeachRule = { sender ->
                        navController.navigate(MezgebDestination.RuleBuilder(sender))
                    },
                )
            }
            composable<MezgebDestination.RuleBuilder> {
                RuleBuilderRoute(onBack = { navController.navigateUp() })
            }
            composable<MezgebDestination.Reconcile> {
                ReconcileRoute(
                    onBack = { navController.navigateUp() },
                    onOpenReview = { navController.navigate(MezgebDestination.Review) },
                    onOpenLedger = { navController.navigateToTopLevel(TopLevelDestination.LEDGER) },
                    onOpenCustomers = {
                        navController.navigateToTopLevel(TopLevelDestination.CUSTOMERS)
                    },
                )
            }
            composable<MezgebDestination.Settings> {
                SettingsScreen(onBack = { navController.navigateUp() })
            }

            composable<MezgebDestination.TransactionDetail> {
                TransactionDetailRoute(onBack = { navController.navigateUp() })
            }
            composable<MezgebDestination.CashEntry> {
                CashEntryRoute(onBack = { navController.navigateUp() })
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

/** Both Today and the Ledger open the same detail screen; only the route knows how. */
private fun NavHostController.navigateToTransaction(transactionId: String) {
    navigate(MezgebDestination.TransactionDetail(transactionId))
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
