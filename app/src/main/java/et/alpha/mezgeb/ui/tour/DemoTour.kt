package et.alpha.mezgeb.ui.tour

import androidx.annotation.StringRes
import et.alpha.mezgeb.R
import et.alpha.mezgeb.navigation.MezgebDestination

/**
 * The guided tour shown over demo mode: five stops, about two minutes, one screen
 * each, in the order a shop owner meets the app's promise. Each stop names the screen
 * it explains, so the tour moves the app there itself and the person giving the demo
 * only has to read and tap "Next".
 *
 * Only shown in demo mode. A tour over a real ledger would point at numbers that are
 * not the ones its text describes.
 */
enum class DemoTourStep(
    val destination: MezgebDestination,
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
) {
    TODAY(MezgebDestination.Today, R.string.tour_today_title, R.string.tour_today_body),
    LEDGER(MezgebDestination.Ledger, R.string.tour_ledger_title, R.string.tour_ledger_body),
    CUSTOMERS(MezgebDestination.Customers, R.string.tour_customers_title, R.string.tour_customers_body),
    RECONCILE(MezgebDestination.Reconcile, R.string.tour_reconcile_title, R.string.tour_reconcile_body),
    REPORTS(MezgebDestination.Reports, R.string.tour_reports_title, R.string.tour_reports_body),
}

/**
 * Where the tour is. [stepIndex] null means the tour is closed. Kept as a plain index
 * so it survives a configuration change through `rememberSaveable` without a saver.
 */
data class DemoTourState(val stepIndex: Int? = 0) {

    val step: DemoTourStep? get() = stepIndex?.let { DemoTourStep.entries.getOrNull(it) }
    val isOpen: Boolean get() = step != null
    val isFirst: Boolean get() = stepIndex == 0
    val isLast: Boolean get() = stepIndex == DemoTourStep.entries.lastIndex

    /** "Next" on the last stop closes the tour: there is nothing after it to show. */
    fun next(): DemoTourState = when {
        stepIndex == null -> this
        isLast -> DemoTourState(stepIndex = null)
        else -> DemoTourState(stepIndex + 1)
    }

    /** "Back" on the first stop stays there rather than closing by surprise. */
    fun back(): DemoTourState = when {
        stepIndex == null || isFirst -> this
        else -> DemoTourState(stepIndex - 1)
    }

    fun close(): DemoTourState = DemoTourState(stepIndex = null)

    fun restart(): DemoTourState = DemoTourState(stepIndex = 0)

    companion object {
        val stepCount: Int get() = DemoTourStep.entries.size
    }
}
