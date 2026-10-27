package et.alpha.mezgeb.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

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

    /**
     * Opens a dialog and returns what TalkBack can read in it: the merged semantics of
     * the dialog's own window, read straight from its compose root. [content] gets
     * whether the dialog is open. ROADMAP R18.
     *
     * Needed for any dialog with a labelled text field. Under Robolectric such a dialog
     * never settles: the probe on PR #37 counted 30 measures of the field in 60 frames,
     * where the same field in the main window was measured once. Anything that waits for
     * idle, which includes every `onNode` finder, then lays text out until Robolectric's
     * native-object registry overflows, and that fails every Compose test after it in
     * the same JVM. So nothing here waits once the dialog is up. The screen is composed
     * with the dialog closed and allowed to settle, then the dialog opens on a paused
     * clock and runs a fixed number of frames, which is enough for its window to be
     * composed and laid out. The tree is read after that, without touching the rule.
     */
    fun openDialog(content: @Composable (open: Boolean) -> Unit): List<SemanticsNode> {
        val open = mutableStateOf(false)
        show { content(open.value) }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { open.value = true }
        repeat(DIALOG_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            // The dialog is its own window, laid out by the view system's frames, which
            // follow Robolectric's clock rather than the Compose test clock.
            ShadowLooper.idleMainLooper(FRAME_MILLIS, TimeUnit.MILLISECONDS)
        }
        val dialog = checkNotNull(ShadowDialog.getLatestDialog()) { "no dialog was shown" }
        val root = checkNotNull(dialog.window?.decorView?.findComposeRoot()) {
            "the dialog window holds no compose root"
        }
        return root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
    }

    fun assertControlsAreNamedAndLargeEnough() {
        checkControls(compose.onAllNodes(hasClickAction()).fetchSemanticsNodes())
    }

    /** The same rules, on a tree read by [openDialog]. */
    fun assertControlsAreNamedAndLargeEnough(tree: List<SemanticsNode>) {
        checkControls(tree.filter { hasClickAction().matches(it) })
    }

    /**
     * The dialog really is the one under test. The checks would pass on an empty or
     * wrong tree, so each dialog test first finds its own title.
     */
    fun assertShows(tree: List<SemanticsNode>, text: String) {
        val texts = tree.flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }
        assertTrue("\"$text\" is not in the dialog; it shows $texts", texts.any { it == text })
    }

    /**
     * Some control is read with [name] in it. A text field is read by its label, so this
     * is how a test says which field it means: "Name, edit box" rather than "edit box".
     */
    fun assertControlNamed(tree: List<SemanticsNode>, name: String) {
        val controls = tree.filter { hasClickAction().matches(it) }
        assertTrue(
            "no control is read as \"$name\": ${controls.describe()}",
            controls.any { name in it.spokenName() },
        )
    }

    private fun checkControls(controls: List<SemanticsNode>) {
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
        // An amount has two decimals. Without them this also caught "+251900000001",
        // a phone number in E.164, which TalkBack reads correctly as "plus 2 5 1...".
        val signedAmount = Regex("""[+−]\d{1,3}(,\d{3})*\.\d\d""")
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

    private fun View.findComposeRoot(): ViewRootForTest? = when (this) {
        is ViewRootForTest -> this
        is ViewGroup ->
            (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findComposeRoot() }
        else -> null
    }

    private companion object {
        val MIN_TARGET_DP = 48.dp.value

        /** A dialog is composed, shown and laid out well within this; see [openDialog]. */
        const val DIALOG_FRAMES = 10
        const val FRAME_MILLIS = 16L
    }
}
