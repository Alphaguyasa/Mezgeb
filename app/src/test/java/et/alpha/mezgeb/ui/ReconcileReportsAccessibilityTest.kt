package et.alpha.mezgeb.ui

import androidx.annotation.StringRes
import androidx.compose.ui.test.junit4.createComposeRule
import et.alpha.mezgeb.R
import et.alpha.mezgeb.domain.model.ReconciliationSession
import et.alpha.mezgeb.domain.model.SessionStatus
import et.alpha.mezgeb.domain.reconcile.GapExplanation
import et.alpha.mezgeb.domain.reconcile.PeriodReconciliation
import et.alpha.mezgeb.domain.report.BreakdownSlice
import et.alpha.mezgeb.domain.report.DailyNet
import et.alpha.mezgeb.domain.report.Report
import et.alpha.mezgeb.domain.report.ReportPeriod
import et.alpha.mezgeb.ui.reconcile.ReconcileScreen
import et.alpha.mezgeb.ui.reconcile.ReconcileUiState
import et.alpha.mezgeb.ui.reports.ReportsScreen
import et.alpha.mezgeb.ui.reports.ReportsUiState
import et.alpha.mezgeb.util.MoneyFormatter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reconcile and Reports, the two screens of totals. The rules are in
 * [AccessibilityChecks].
 *
 * Both show figures that are subtracted or can go below zero: credit repaid is taken
 * off what is accounted for, and a period's net is negative when more went out than
 * came in. Those are exactly where a minus sign used to reach TalkBack as a symbol.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class ReconcileReportsAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val checks = AccessibilityChecks(compose)
    private val context = checks.context

    @Test
    fun `the period picker names and sizes every control`() {
        showReconcile(ReconcileUiState(isLoading = false, closedSessions = listOf(closed())))

        checks.assertControlsAreNamedAndLargeEnough()
        checks.assertSpoken(context.getString(R.string.cd_navigate_back))
    }

    @Test
    fun `an open period with a gap names and sizes every control`() {
        showOpenPeriod()

        checks.assertControlsAreNamedAndLargeEnough()
    }

    @Test
    fun `credit repaid is read as taken off, not as minus`() {
        showOpenPeriod()

        checks.assertNoSignedAmountIsSpoken()
        checks.assertSpoken(figure(R.string.reconcile_captured_in, CAPTURED_MINOR))
        checks.assertSpoken(
            figure(
                R.string.reconcile_credit_repaid,
                REPAID_MINOR,
                spoken = R.string.reconcile_figure_less_content_description,
            ),
        )
        checks.assertSpoken(
            figure(R.string.reconcile_accounted_for, CAPTURED_MINOR + CASH_MINOR - REPAID_MINOR),
        )
    }

    @Test
    fun `the reports screen names and sizes every control`() {
        showReports(netNegative = true)

        // The export button is named by its own text, which the check above covers.
        checks.assertControlsAreNamedAndLargeEnough()
    }

    @Test
    fun `a period that lost money is read as words`() {
        showReports(netNegative = true)

        checks.assertNoSignedAmountIsSpoken()
        checks.assertSpoken(
            context.getString(
                R.string.today_net_behind_content_description,
                MoneyFormatter.formatUnsigned(OUT_MINOR - IN_MINOR),
                MoneyFormatter.DEFAULT_CURRENCY,
            ),
        )
    }

    @Test
    fun `a period that made money is read as words`() {
        showReports(netNegative = false)

        checks.assertSpoken(
            context.getString(
                R.string.today_net_ahead_content_description,
                MoneyFormatter.formatUnsigned(IN_MINOR - SMALL_OUT_MINOR),
                MoneyFormatter.DEFAULT_CURRENCY,
            ),
        )
    }

    private fun showReconcile(state: ReconcileUiState) {
        checks.show {
            ReconcileScreen(state = state, onEvent = {}, onBack = {}, onExplanationTapped = {})
        }
    }

    private fun showOpenPeriod() {
        showReconcile(
            ReconcileUiState(
                isLoading = false,
                openSession = closed().copy(
                    id = "open",
                    status = SessionStatus.OPEN,
                    closedAt = null,
                ),
                reconciliation = PeriodReconciliation(
                    periodStart = START,
                    periodEnd = END,
                    expectedTotalMinor = 300_000,
                    capturedInMinor = CAPTURED_MINOR,
                    capturedOutMinor = 15_000,
                    cashRecordedMinor = CASH_MINOR,
                    creditGivenMinor = 0,
                    creditRepaidMinor = REPAID_MINOR,
                    explanations = listOf(
                        GapExplanation.UnmatchedMessages(4, 32_000, amountsFound = 3),
                        GapExplanation.PossibleDuplicates(1, 9_000),
                        GapExplanation.EditedSincePeriodOpened(1),
                    ),
                ),
                expectedInput = "3000.00",
                closedSessions = listOf(closed()),
            ),
        )
    }

    private fun showReports(netNegative: Boolean) {
        val outMinor = if (netNegative) OUT_MINOR else SMALL_OUT_MINOR
        checks.show {
            ReportsScreen(
                state = ReportsUiState(
                    isLoading = false,
                    period = ReportPeriod.WEEK,
                    report = Report(
                        periodStart = START,
                        periodEnd = END,
                        totalInMinor = IN_MINOR,
                        totalOutMinor = outMinor,
                        transactionCount = 9,
                        byChannel = listOf(BreakdownSlice("TELEBIRR", IN_MINOR, 1f, 6)),
                        byCategory = listOf(BreakdownSlice("Sales", IN_MINOR, 1f, 6)),
                        dailyNet = listOf(DailyNet(START, IN_MINOR - outMinor)),
                        topDebtors = emptyList(),
                    ),
                ),
                onEvent = {},
                onExport = {},
            )
        }
    }

    private fun figure(
        @StringRes label: Int,
        amountMinor: Long,
        @StringRes spoken: Int = R.string.reconcile_figure_content_description,
    ): String = context.getString(
        spoken,
        context.getString(label),
        MoneyFormatter.formatUnsigned(amountMinor),
        MoneyFormatter.DEFAULT_CURRENCY,
    )

    private fun closed() = ReconciliationSession(
        id = "s1",
        periodStart = START,
        periodEnd = END,
        openedAt = START,
        expectedTotalMinor = 245_000,
        capturedInMinor = 200_000,
        capturedOutMinor = 15_000,
        cashRecordedMinor = 45_000,
        creditGivenMinor = 0,
        creditRepaidMinor = 5_000,
        status = SessionStatus.CLOSED,
        closedAt = END,
        note = null,
    )

    private companion object {
        const val START = 1_782_950_400_000L
        const val END = 1_783_036_799_999L
        const val CAPTURED_MINOR = 200_000L
        const val CASH_MINOR = 45_000L
        const val REPAID_MINOR = 5_000L
        const val IN_MINOR = 245_000L
        const val OUT_MINOR = 327_000L
        const val SMALL_OUT_MINOR = 82_000L
    }
}
