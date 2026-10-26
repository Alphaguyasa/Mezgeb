package et.alpha.mezgeb.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import et.alpha.mezgeb.R
import et.alpha.mezgeb.capture.CaptureHealth
import et.alpha.mezgeb.designsystem.component.AmountKeypad
import et.alpha.mezgeb.domain.backup.BackupReminder
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.UnmatchedGroup
import et.alpha.mezgeb.ui.common.TransactionUiModel
import et.alpha.mezgeb.ui.review.ReviewScreen
import et.alpha.mezgeb.ui.review.ReviewUiState
import et.alpha.mezgeb.ui.today.TodayScreen
import et.alpha.mezgeb.ui.today.TodayUiState
import et.alpha.mezgeb.util.MoneyFormatter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What TalkBack and a thumb get from the three screens used most: Today, the cash
 * keypad and the review queue. The rules themselves are in [AccessibilityChecks].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class AccessibilitySemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val checks = AccessibilityChecks(compose)
    private val context = checks.context

    @Test
    fun `today names and sizes every control`() {
        checks.show { BusiestToday() }

        checks.assertControlsAreNamedAndLargeEnough()
        // The FAB is named from its icon: Material clears the label's own semantics.
        checks.assertSpoken(context.getString(R.string.today_record_cash))
        // The backup reminder's two buttons are on screen, so the checks above saw them
        // (ROADMAP R23).
        compose.onNodeWithText(context.getString(R.string.today_backup_reminder_action))
            .assertHasClickAction()
        compose.onNodeWithText(context.getString(R.string.today_backup_reminder_dismiss))
            .assertHasClickAction()
    }

    @Test
    fun `today reads every amount as words`() {
        checks.show { BusiestToday() }

        checks.assertNoSignedAmountIsSpoken()
        // The totals, a row and the net, each as a sentence.
        checks.assertSpoken(checks.money(R.string.direction_in, TOTAL_IN_MINOR))
        checks.assertSpoken(checks.money(R.string.direction_out, TOTAL_OUT_MINOR))
        checks.assertSpoken(checks.money(R.string.direction_in, ROW_IN_MINOR))
        checks.assertSpoken(
            context.getString(
                R.string.today_net_ahead_content_description,
                MoneyFormatter.formatUnsigned(TOTAL_IN_MINOR - TOTAL_OUT_MINOR),
                MoneyFormatter.DEFAULT_CURRENCY,
            ),
        )
    }

    @Test
    fun `a day that is behind says so in words`() {
        checks.show { BusiestToday(totalInMinor = 10_000, totalOutMinor = 25_000) }

        checks.assertNoSignedAmountIsSpoken()
        checks.assertSpoken(
            context.getString(
                R.string.today_net_behind_content_description,
                MoneyFormatter.formatUnsigned(15_000),
                MoneyFormatter.DEFAULT_CURRENCY,
            ),
        )
    }

    @Test
    fun `a day with nothing in or out is not read as plus zero`() {
        checks.show { BusiestToday(totalInMinor = 0, totalOutMinor = 0) }

        checks.assertNoSignedAmountIsSpoken()
        checks.assertSpoken(context.getString(R.string.today_net_even_content_description))
    }

    @Test
    fun `the keypad names and sizes every key, including backspace`() {
        checks.show { AmountKeypad(onDigit = {}, onDoubleZero = {}, onBackspace = {}) }

        checks.assertControlsAreNamedAndLargeEnough()
        // The one icon-only key, by its own name.
        checks.assertSpoken(context.getString(R.string.keypad_backspace))
        assertEquals(12, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `the review queue names and sizes every control`() {
        checks.show {
            ReviewScreen(
                state = ReviewUiState(
                    isLoading = false,
                    groups = listOf(
                        UnmatchedGroup("AWASH", 14, 1_782_983_640_000L),
                        UnmatchedGroup("DASHEN", 2, 1_782_900_000_000L),
                    ),
                ),
                onEvent = {},
                onBack = {},
                onTeachRule = {},
            )
        }

        checks.assertControlsAreNamedAndLargeEnough()
        checks.assertSpoken(context.getString(R.string.cd_navigate_back))
    }

    /**
     * The screen in its busiest state: a capture problem, a backup reminder, a review
     * queue, money in and money out, so every kind of control and amount is on it at
     * once.
     */
    @Composable
    private fun BusiestToday(
        totalInMinor: Long = TOTAL_IN_MINOR,
        totalOutMinor: Long = TOTAL_OUT_MINOR,
    ) {
        TodayScreen(
            state = TodayUiState(
                isLoading = false,
                totalInMinor = totalInMinor,
                totalOutMinor = totalOutMinor,
                captureHealth = CaptureHealth.NO_ACCESS,
                reviewQueueCount = 3,
                recent = listOf(
                    TransactionUiModel(
                        id = "1",
                        direction = Direction.IN,
                        amountMinor = ROW_IN_MINOR,
                        channel = Channel.TELEBIRR,
                        counterparty = "ABEBE",
                        occurredAt = 1_782_983_640_000L,
                        categoryName = "Sales",
                        isEdited = false,
                        isReconciled = false,
                    ),
                    TransactionUiModel(
                        id = "2",
                        direction = Direction.OUT,
                        amountMinor = 32_500,
                        channel = Channel.CASH,
                        counterparty = null,
                        occurredAt = 1_782_990_000_000L,
                        categoryName = null,
                        isEdited = true,
                        isReconciled = false,
                        isPossibleDuplicate = true,
                    ),
                ),
                hasEverCaptured = true,
                backupReminder = BackupReminder.STALE,
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

    private companion object {
        const val TOTAL_IN_MINOR = 245_000L
        const val TOTAL_OUT_MINOR = 32_500L
        const val ROW_IN_MINOR = 45_000L
    }
}
