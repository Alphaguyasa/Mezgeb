package et.alpha.mezgeb.ui.rule

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.domain.model.CaptureSource
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.domain.model.RawMessage
import et.alpha.mezgeb.parser.model.SemanticField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one interaction in this app worth testing through the UI.
 *
 * Everything else can be judged from a ViewModel test, but "select some text, tap
 * what it is" only exists as a gesture: the selection lives in the text field, the
 * chips only appear once something is selected, and the offsets the chip reports have
 * to be the offsets the user actually highlighted. A bug anywhere in that chain
 * teaches a rule about the wrong characters, and no unit test would see it.
 *
 * Run under Robolectric rather than on a device so it runs on every build. The
 * behaviour it covers is Compose's own selection plumbing, which Robolectric drives
 * faithfully; nothing here depends on real touch dispatch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuleBuilderScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<RuleBuilderEvent>()

    @Test
    fun `field chips appear only once something is selected`() {
        showScreen()

        // Nothing selected: the chips would be meaningless, so they are not offered.
        compose.onNodeWithTag(tagChipFor(SemanticField.AMOUNT)).assertDoesNotExist()

        selectAmount()

        compose.onNodeWithTag(tagChipFor(SemanticField.AMOUNT)).assertIsDisplayed()
    }

    @Test
    fun `tapping a chip tags the exact characters the user highlighted`() {
        showScreen()
        selectAmount()

        compose.onNodeWithTag(tagChipFor(SemanticField.AMOUNT)).performClick()

        val tagged = events.filterIsInstance<RuleBuilderEvent.TagSpan>().single()
        assertEquals(SemanticField.AMOUNT, tagged.field)
        // The range must be inclusive of the last character: an off-by-one here reads
        // "450.0" as the amount and silently loses a santim.
        assertEquals("450.00", BODY.substring(tagged.range.first, tagged.range.last + 1))
    }

    @Test
    fun `a tagged field can be untagged`() {
        showScreen(
            state = state().copy(
                spans = mapOf(SemanticField.AMOUNT to amountRange()),
                preview = null,
            ),
        )

        compose.onNodeWithText("Amount").performClick()

        assertTrue(
            events.filterIsInstance<RuleBuilderEvent.ClearSpan>().any {
                it.field == SemanticField.AMOUNT
            },
        )
    }

    @Test
    fun `the screen says an amount is required until one is tagged`() {
        showScreen()

        compose.onNodeWithText("Select the amount to continue.").assertIsDisplayed()
    }

    private fun showScreen(state: RuleBuilderUiState = state()) {
        compose.setContent {
            MezgebTheme {
                RuleBuilderScreen(
                    state = state,
                    onEvent = { events += it },
                    onBack = {},
                )
            }
        }
    }

    private fun selectAmount() {
        val range = amountRange()
        compose.onNodeWithTag(TAG_MESSAGE_BODY)
            .performTextInputSelection(TextRange(range.first, range.last + 1))
    }

    private fun amountRange(): IntRange {
        val start = BODY.indexOf(AMOUNT_TEXT)
        return IntRange(start, start + AMOUNT_TEXT.length - 1)
    }

    private fun state() = RuleBuilderUiState(
        isLoading = false,
        sender = "AWASH",
        messages = listOf(
            RawMessage(
                id = "m1",
                sender = "AWASH",
                body = BODY,
                contentHash = "h",
                receivedAt = 1_782_983_640_000L,
                source = CaptureSource.NOTIFICATION,
                parseState = ParseState.UNMATCHED,
                matchedRuleId = null,
            ),
        ),
    )

    private companion object {
        const val AMOUNT_TEXT = "450.00"
        const val BODY =
            "Dear customer, ETB 450.00 has been credited to your account from ABEBE."
    }
}
