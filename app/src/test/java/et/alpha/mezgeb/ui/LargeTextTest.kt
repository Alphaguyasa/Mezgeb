package et.alpha.mezgeb.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import et.alpha.mezgeb.capture.CaptureHealth
import et.alpha.mezgeb.domain.credit.CustomerCredit
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.CreditEntry
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.CreditStatementLine
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.ReconciliationSession
import et.alpha.mezgeb.domain.model.SessionStatus
import et.alpha.mezgeb.domain.reconcile.GapExplanation
import et.alpha.mezgeb.domain.reconcile.PeriodReconciliation
import et.alpha.mezgeb.ui.cash.CashEntryScreen
import et.alpha.mezgeb.ui.cash.CashEntryUiState
import et.alpha.mezgeb.ui.common.TransactionUiModel
import et.alpha.mezgeb.ui.customers.CustomerDetailScreen
import et.alpha.mezgeb.ui.customers.CustomerDetailUiState
import et.alpha.mezgeb.ui.reconcile.ReconcileScreen
import et.alpha.mezgeb.ui.reconcile.ReconcileUiState
import et.alpha.mezgeb.ui.today.TodayScreen
import et.alpha.mezgeb.ui.today.TodayUiState
import et.alpha.mezgeb.util.MoneyFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screens with the biggest numbers, at the large system font many shop owners
 * use. ROADMAP R20. Each subclass below sets one font scale.
 *
 * Two things are checked. Every control is still named and at least 48dp each way
 * (the shared [AccessibilityChecks]). And no amount wraps onto a second line or runs
 * past its box: "2,450.0" over "0 ETB" is a wrong number at a glance, which is worse
 * than a smaller one.
 *
 * The amounts are read from the unmerged tree. Their wrappers replace what TalkBack
 * hears, but the visible Text and its layout are still there underneath. Native
 * graphics are on so that the layout is measured with a real font. Robolectric's
 * legacy graphics stub out text measurement, and then nothing would ever wrap. The
 * first test checks both the scale and the measurement, so a broken setup fails
 * loudly instead of passing everything.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
abstract class LargeTextTest(private val fontScale: Float) {

    @get:Rule
    val compose = createComposeRule()

    private val checks = AccessibilityChecks(compose)

    @Test
    fun `the test really runs at its font scale, with real text measurement`() {
        var composedScale = 0f
        checks.show {
            composedScale = LocalDensity.current.fontScale
            Today()
        }

        assertEquals(fontScale, composedScale, 0.01f)
        // A real font makes "+12,450.00" far wider than a pixel per character.
        val headline = amountLayouts().first()
        val density = checks.context.resources.displayMetrics.density
        assertTrue(
            "text is not measured with a real font: ${headline.describe()}",
            headline.size.width / density > headline.layoutInput.text.length * MIN_GLYPH_DP,
        )
    }

    @Test
    fun `today keeps its controls large and its totals whole`() {
        checks.show { Today() }

        checks.assertControlsAreNamedAndLargeEnough()
        assertNoAmountWrapsOrRunsOver()
    }

    /**
     * At 2.0 the five-figure headline measured 278dp of 296dp (PR #41), so these two
     * would have broken inside the number. Now the currency moves under the amount
     * instead (ROADMAP R26).
     */
    @Test
    fun `today keeps a six-figure headline whole`() {
        checks.show { Today(totalInMinor = 10_000_000) }

        assertNoAmountWrapsOrRunsOver()
        assertCurrencyIsWhole()
    }

    @Test
    fun `today keeps a seven-figure headline whole`() {
        checks.show { Today(totalInMinor = 123_456_789) }

        assertNoAmountWrapsOrRunsOver()
        assertCurrencyIsWhole()
    }

    @Test
    fun `the cash keypad keeps its keys large and its amount whole`() {
        checks.show {
            CashEntryScreen(
                state = CashEntryUiState(amountMinor = 1_245_000, occurredAt = AT),
                onEvent = {},
                onBack = {},
            )
        }

        checks.assertControlsAreNamedAndLargeEnough()
        assertNoAmountWrapsOrRunsOver()
    }

    @Test
    fun `reconcile keeps its controls large and its figures whole`() {
        checks.show {
            ReconcileScreen(
                state = ReconcileUiState(
                    isLoading = false,
                    openSession = SESSION,
                    reconciliation = PeriodReconciliation(
                        periodStart = AT,
                        periodEnd = AT + DAY_MS - 1,
                        expectedTotalMinor = 1_300_000,
                        capturedInMinor = 1_000_000,
                        capturedOutMinor = 15_000,
                        cashRecordedMinor = 245_000,
                        creditGivenMinor = 0,
                        creditRepaidMinor = 5_000,
                        explanations = listOf(
                            GapExplanation.UnmatchedMessages(4, 32_000, amountsFound = 3),
                        ),
                    ),
                    expectedInput = "13000.00",
                ),
                onEvent = {},
                onBack = {},
                onExplanationTapped = {},
            )
        }

        checks.assertControlsAreNamedAndLargeEnough()
        assertNoAmountWrapsOrRunsOver()
    }

    @Test
    fun `the credit statement keeps its controls large and its balances whole`() {
        checks.show {
            CustomerDetailScreen(
                state = CustomerDetailUiState(
                    isLoading = false,
                    credit = CustomerCredit(
                        // Not a real number; there so the reminder button shows.
                        customer = Customer("1", "ABEBE", "0900000000", null, createdAt = 0L),
                        balanceMinor = 1_245_000,
                        oldestUnpaidAt = null,
                        daysOutstanding = 41,
                        isOverdue = true,
                    ),
                    statement = listOf(
                        line("e1", CreditKind.CREDIT_GIVEN, 1_250_000, running = 1_250_000),
                        line("e2", CreditKind.REPAYMENT, 5_000, running = 1_245_000),
                    ),
                ),
                onEvent = {},
                onBack = {},
                onAddCredit = {},
                onRecordRepayment = {},
                onSendReminder = {},
            )
        }

        checks.assertControlsAreNamedAndLargeEnough()
        assertNoAmountWrapsOrRunsOver()
    }

    /** Today in its busiest state, with five-figure totals unless told otherwise. */
    @Composable
    private fun Today(totalInMinor: Long = 1_245_000) {
        TodayScreen(
            state = TodayUiState(
                isLoading = false,
                totalInMinor = totalInMinor,
                totalOutMinor = 1_032_500,
                captureHealth = CaptureHealth.NO_ACCESS,
                reviewQueueCount = 3,
                recent = listOf(
                    TransactionUiModel(
                        id = "1",
                        direction = Direction.IN,
                        amountMinor = 1_245_000,
                        channel = Channel.TELEBIRR,
                        counterparty = "ABEBE",
                        occurredAt = AT,
                        categoryName = "Sales",
                        isEdited = true,
                        isReconciled = false,
                        isPossibleDuplicate = true,
                    ),
                ),
                hasEverCaptured = true,
            ),
            onEvent = {},
            onOpenReview = {},
            onOpenReconcile = {},
            onOpenSettings = {},
            onOpenTransaction = {},
            onRecordCashSale = {},
            onFixCapture = {},
        )
    }

    private fun assertNoAmountWrapsOrRunsOver() {
        val amounts = amountLayouts()
        // Guards the test itself: no amount found would pass everything below.
        assertTrue("no amount found on the screen", amounts.isNotEmpty())
        // Not TextLayoutResult's overflow flags: under native graphics both of them
        // were set for every amount, even one a third the width of its box (PR #41's
        // first two runs). Instead the amount must wrap freely and have no line cap.
        // Then one that does not fit can only show as a second line, which is what
        // a cut-off amount looked like on the first run ("−10,325." over "00").
        val broken = amounts.filter {
            it.lineCount > 1 || !it.layoutInput.softWrap || it.layoutInput.maxLines != Int.MAX_VALUE
        }
        assertTrue(
            "at font scale $fontScale these amounts wrap or run over:\n" +
                broken.joinToString(separator = "\n") { it.describe() },
            broken.isEmpty(),
        )
    }

    /** "ETB" may move to a line of its own, but never split across two. */
    private fun assertCurrencyIsWhole() {
        val currencies = compose
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                useUnmergedTree = true,
            )
            .fetchSemanticsNodes()
            .mapNotNull { it.textLayout() }
            .filter { it.layoutInput.text.text.trim() == MoneyFormatter.DEFAULT_CURRENCY }
        assertTrue("no currency found on the screen", currencies.isNotEmpty())
        val broken = currencies.filter { it.lineCount > 1 }
        assertTrue(
            "at font scale $fontScale the currency breaks:\n" +
                broken.joinToString(separator = "\n") { it.describe() },
            broken.isEmpty(),
        )
    }

    /** The layout of every visible Text that is an amount and nothing else. */
    private fun amountLayouts(): List<TextLayoutResult> = compose
        .onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        )
        .fetchSemanticsNodes()
        .mapNotNull { it.textLayout() }
        .filter { AMOUNT.matches(it.layoutInput.text.text.trim()) }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()
    }

    private fun TextLayoutResult.describe(): String {
        val density = checks.context.resources.displayMetrics.density
        return "\"${layoutInput.text}\": ${lineCount} lines, " +
            "${size.width / density}dp wide in ${layoutInput.constraints.maxWidth / density}dp, " +
            "softWrap=${layoutInput.softWrap}, maxLines=${layoutInput.maxLines}"
    }

    private fun line(id: String, kind: CreditKind, amountMinor: Long, running: Long) =
        CreditStatementLine(
            entry = CreditEntry(
                id = id,
                customerId = "1",
                kind = kind,
                amountMinor = amountMinor,
                occurredAt = AT,
                note = null,
                linkedTransactionId = null,
            ),
            runningBalanceMinor = running,
        )

    private companion object {
        const val AT = 1_782_950_400_000L
        const val DAY_MS = 86_400_000L

        /** Far below the width of any real glyph at these sizes. */
        const val MIN_GLYPH_DP = 3f

        /** "2,450.00", "+2,450.00", "−32,500.00 ETB": an amount on its own. */
        val AMOUNT = Regex("""[+−-]?\d{1,3}(,\d{3})*\.\d\d( [A-Z]{3})?""")

        val SESSION = ReconciliationSession(
            id = "open",
            periodStart = AT,
            periodEnd = AT + DAY_MS - 1,
            openedAt = AT,
            expectedTotalMinor = 1_300_000,
            capturedInMinor = 1_000_000,
            capturedOutMinor = 15_000,
            cashRecordedMinor = 245_000,
            creditGivenMinor = 0,
            creditRepaidMinor = 5_000,
            status = SessionStatus.OPEN,
            closedAt = null,
            note = null,
        )
    }
}

/** Android's "Large" font setting on most phones. */
@Config(fontScale = 1.5f)
class LargeTextAt150Test : LargeTextTest(fontScale = 1.5f)

/** The largest font Android offers since version 14. */
@Config(fontScale = 2.0f)
class LargeTextAt200Test : LargeTextTest(fontScale = 2.0f)
