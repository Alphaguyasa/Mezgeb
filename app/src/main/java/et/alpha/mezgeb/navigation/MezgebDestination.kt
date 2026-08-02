package et.alpha.mezgeb.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import et.alpha.mezgeb.R
import kotlinx.serialization.Serializable

/**
 * Every destination in the app, as type-safe Navigation Compose routes.
 *
 * These are `@Serializable` objects rather than route strings, so a typo becomes a
 * compile error and arguments (added from Session 5) are checked by the compiler.
 */
sealed interface MezgebDestination {

    /**
     * Shown instead of Today until the shop has been told what the app reads. Not
     * reachable from the bottom bar: it is a start destination, not a tab.
     */
    @Serializable
    data object Onboarding : MezgebDestination

    @Serializable
    data object Today : MezgebDestination

    @Serializable
    data object Ledger : MezgebDestination

    @Serializable
    data object Review : MezgebDestination

    @Serializable
    data object Customers : MezgebDestination

    @Serializable
    data object Reconcile : MezgebDestination

    @Serializable
    data object Reports : MezgebDestination

    @Serializable
    data object Settings : MezgebDestination
}

/**
 * The four destinations on the bottom bar. Review, Reconcile and Settings are
 * deliberately not here — they are reached from Today and the top bar, because a
 * five- or seven-item bottom bar stops being tappable one-handed.
 */
enum class TopLevelDestination(
    val route: MezgebDestination,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    TODAY(MezgebDestination.Today, R.string.destination_today, Icons.Filled.Home),
    LEDGER(MezgebDestination.Ledger, R.string.destination_ledger, Icons.AutoMirrored.Filled.List),
    CUSTOMERS(MezgebDestination.Customers, R.string.destination_customers, Icons.Filled.Person),
    REPORTS(MezgebDestination.Reports, R.string.destination_reports, Icons.Filled.DateRange),
}
