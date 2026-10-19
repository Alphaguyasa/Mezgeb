package et.alpha.mezgeb.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.R
import et.alpha.mezgeb.capture.CaptureHealth
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.AmountKeypad
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.UnmatchedGroup
import et.alpha.mezgeb.parser.model.Channel
import et.alpha.mezgeb.ui.common.TransactionUiModel
import et.alpha.mezgeb.ui.review.ReviewScreen
import et.alpha.mezgeb.ui.review.ReviewUiState
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

/**
 * What TalkBack and a thumb get from the three screens used most: Today, the cash
 * keypad and the review queue.
 *
 * Three rules, checked on the semantics tree rather than on pixels:
 * - every control has a name, so an icon-only button is never read as "button";
 * - every control is at least 48dp each way, the Android accessibility floor;
 * - no amount reaches TalkBack as a sign glued to digits. "+2,125.00" is read as a
 *   symbol and a number; "2,125.00 ETB more received than paid out" is a sentence.
 *
 * This is a floor, not a review. Whether the screens make sense spoken aloud still
 * needs a person with TalkBack on. The screen is made tall so the lazy lists compose
 * every row instead of only the ones that fit Robolectric's default size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class AccessibilitySemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `today names and sizes every control`() {
        show { today() }

        assertControlsAreNamedAndLargeEnough()
    }

    @Test
    fun `today reads every amount as words`() {
        show { today() }

        assertNoSignedAmountIsSpoken()
        // The totals, a row and the net, each as a sentence.
        assertSpoken(money(R.string.direction_in, TOTAL_IN_MINOR))
        assertSpoken(money(R.string.direction_out, TOTAL_OUT_MINOR))
        assertSpoken(money(R.string.direction_in, ROW_IN_MINOR))
        assertSpoken(
            context.getString(
                R.string.today_net_ahead_content_description,
                MoneyFormatter.formatUnsigned(TOTAL_IN_MINOR - TOTAL_OUT_MINOR),
                MoneyFormatter.DEFAULT_CURRENCY,
            ),
        )
    }

    @Test
    fun `a day that is behind says so in words`() {
        show { today(totalInMinor = 10_000, totalOutMinor = 25_000) }

        assertNoSignedAmountIsSpoken()
        assertSpoken(
            context.getString(
                R.string.today_net_behind_content_description,
                MoneyFormatter.formatUnsigned(15_000),
                MoneyFormatter.DEFAULT_CURRENCY,
            ),
        )
    }

    @Test
    fun `a day with nothing in or out is not read as plus zero`() {
        show { today(totalInMinor = 0, totalOutMinor = 0) }

        assertNoSignedAmountIsSpoken()
        assertSpoken(context.getString(R.string.today_net_even_content_description))
    }

    @Test
    fun `the keypad names and sizes every key, including backspace`() {
        show { AmountKeypad(onDigit = {}, onDoubleZero = {}, onBackspace = {}) }

        assertControlsAreNamedAndLargeEnough()
        // The one icon-only key, by its own name.
        assertSpoken(context.getString(R.string.keypad_backspace))
        assertEquals(12, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `the review queue names and sizes every control`() {
        show {
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

        assertControlsAreNamedAndLargeEnough()
        assertSpoken(context.getString(R.string.cd_navigate_back))
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            MezgebTheme {
                Surface { content() }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The screen in its busiest state: a capture problem, a review queue, money in
     * and money out, so every kind of control and amount is on it at once.
     */
    @Composable
    private fun today(
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

    private fun assertControlsAreNamedAndLargeEnough() {
        val controls = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        // Guards the test itself: no controls found would pass everything below.
        assertTrue("no controls found; is the screen on the tree at all?", controls.isNotEmpty())

        val unnamed = controls.filter { it.spokenName().isBlank() }
        assertTrue("controls with nothing to read: ${unnamed.describe()}", unnamed.isEmpty())

        val small = controls.filter { it.widthDp() < MIN_TARGET_DP || it.heightDp() < MIN_TARGET_DP }
        assertTrue("controls under ${MIN_TARGET_DP}dp: ${small.describe()}", small.isEmpty())
    }

    /**
     * No text on the unmerged tree may be a sign followed by a digit. The unmerged
     * tree is what matters: a MoneyText clears its children's semantics, so a signed
     * amount still showing up there is one TalkBack will reach.
     */
    private fun assertNoSignedAmountIsSpoken() {
        val signedAmount = Regex("""[+−]\d""")
        val all = compose.onAllNodes(isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes()
        val offenders = all.flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.filter { signedAmount.containsMatchIn(it) }
        assertTrue("signed amounts reach TalkBack as symbols: $offenders", offenders.isEmpty())
    }

    private fun assertSpoken(description: String) {
        val found = compose.onAllNodesWithContentDescription(description, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("nothing is read as \"$description\"", found.isNotEmpty())
    }

    private fun money(@StringRes direction: Int, amountMinor: Long): String = context.getString(
        R.string.money_content_description,
        context.getString(direction),
        MoneyFormatter.formatUnsigned(amountMinor),
        MoneyFormatter.DEFAULT_CURRENCY,
    )

    private fun SemanticsNode.spokenName(): String =
        (
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            ).joinToString(" ")

    /**
     * The larger of the node's own size and its layout's. Material components reserve
     * their 48dp with `minimumInteractiveComponentSize`, which enlarges the layout
     * around a visually smaller control, so the semantics bounds alone would flag a
     * button that is in fact large enough.
     */
    private fun SemanticsNode.widthDp(): Float =
        maxOf(size.width, layoutInfo.width) / context.resources.displayMetrics.density

    private fun SemanticsNode.heightDp(): Float =
        maxOf(size.height, layoutInfo.height) / context.resources.displayMetrics.density

    private fun List<SemanticsNode>.describe(): String = joinToString {
        "\"${it.spokenName()}\" (${it.widthDp()}x${it.heightDp()}dp)"
    }

    private companion object {
        val MIN_TARGET_DP = 48.dp.value

        const val TOTAL_IN_MINOR = 245_000L
        const val TOTAL_OUT_MINOR = 32_500L
        const val ROW_IN_MINOR = 45_000L
    }
}
