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
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.util.MoneyFormatter
import org.junit.Assert.assertTrue

/**
 * The three accessibility rules every screen test checks, on the semantics tree
 * rather than on pixels:
 * - every control has a name, so an icon-only button is never read as "button";
 * - every control is at least 48dp each way, the Android accessibility floor;
 * - no amount reaches TalkBack as a sign glued to digits. "+2,125.00" is read as a
 *   symbol and a number; "2,125.00 ETB more received than paid out" is a sentence.
 *
 * This is a floor, not a review. Whether a screen makes sense spoken aloud still
 * needs a person with TalkBack on. Tests that use this should run on a tall screen
 * (`@Config(qualifiers = "w360dp-h1600dp")`) so lazy lists compose every row instead
 * of only the ones that fit Robolectric's default size.
 */
class AccessibilityChecks(private val compose: ComposeContentTestRule) {

    val context: Context = ApplicationProvider.getApplicationContext()

    fun show(content: @Composable () -> Unit) {
        compose.setContent {
            MezgebTheme {
                Surface { content() }
            }
        }
        compose.waitForIdle()
    }

    fun assertControlsAreNamedAndLargeEnough() {
        val controls = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        // Guards the test itself: no controls found would pass everything below.
        assertTrue("no controls found; is the screen on the tree at all?", controls.isNotEmpty())

        val unnamed = controls.filter { it.spokenName().isBlank() }
        assertTrue("controls with nothing to read: ${unnamed.describe()}", unnamed.isEmpty())

        val small = controls.filter { it.widthDp() < MIN_TARGET_DP || it.heightDp() < MIN_TARGET_DP }
        assertTrue("controls under ${MIN_TARGET_DP}dp: ${small.describe()}", small.isEmpty())
    }

    /**
     * No text on the merged tree may be a sign followed by a digit. The merged tree is
     * what TalkBack reads. The unmerged tree is the wrong place to look: it still holds
     * the visible "+2,450.00" Text under a MoneyText, whose clearAndSetSemantics drops
     * it from what is spoken.
     */
    fun assertNoSignedAmountIsSpoken() {
        val signedAmount = Regex("""[+−]\d""")
        val all = compose.onAllNodes(isRoot().not()).fetchSemanticsNodes()
        val offenders = all.flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.filter { signedAmount.containsMatchIn(it) }
        assertTrue("signed amounts reach TalkBack as symbols: $offenders", offenders.isEmpty())
    }

    fun assertSpoken(description: String) {
        val found = compose.onAllNodesWithContentDescription(description, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("nothing is read as \"$description\"", found.isNotEmpty())
    }

    /** What a MoneyText says: direction, amount, currency. */
    fun money(@StringRes direction: Int, amountMinor: Long): String = context.getString(
        R.string.money_content_description,
        context.getString(direction),
        MoneyFormatter.formatUnsigned(amountMinor),
        MoneyFormatter.DEFAULT_CURRENCY,
    )

    /**
     * Everything TalkBack can read for the node. A text field's typed value counts: a
     * filled field is read by its value even before its label.
     */
    private fun SemanticsNode.spokenName(): String =
        (
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text)
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

    /** Name, size and every semantics property, so a failure says which node it is. */
    private fun List<SemanticsNode>.describe(): String = joinToString(separator = "\n") {
        "\"${it.spokenName()}\" (${it.widthDp()}x${it.heightDp()}dp) ${it.config}"
    }

    private companion object {
        val MIN_TARGET_DP = 48.dp.value
    }
}
