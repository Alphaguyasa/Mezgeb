package et.alpha.mezgeb.ui

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import et.alpha.mezgeb.designsystem.MezgebTheme
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * PROBE for ROADMAP R18 part 2, one CI cycle, to be replaced by real checks.
 *
 * The first probe push waited for idle, and the dialogs that never settle laid text
 * out millions of times until Robolectric's native-object registry overflowed, which
 * broke every Compose test after them in the same JVM. So this version never waits
 * for idle once a dialog is up. It pauses the test clock, opens the dialog, runs a
 * fixed number of frames, and reports how often the field was measured and drawn and
 * whether its window still asks for layout. Every probe fails on purpose so that its
 * numbers reach the CI log. The probe strings are never shown to a user, so they are
 * not in strings.xml.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class DialogTextFieldProbeTest {

    @get:Rule
    val compose = createComposeRule()

    private var measures = 0
    private var draws = 0
    private var fieldView: View? = null

    private val counted: Modifier
        get() = Modifier
            .layout { measurable, constraints ->
                measures++
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .drawBehind { draws++ }

    @Test
    fun `a - basic text field in a dialog`() = probe("a", inDialog = true) {
        BasicTextField(value = "", onValueChange = {}, modifier = counted)
    }

    @Test
    fun `b - outlined text field with no label in a dialog`() = probe("b", inDialog = true) {
        OutlinedTextField(value = "", onValueChange = {}, modifier = counted)
    }

    @Test
    fun `c - outlined text field with a label in a dialog`() = probe("c", inDialog = true) {
        OutlinedTextField(
            value = "",
            onValueChange = {},
            label = { Text("Probe label") },
            modifier = counted,
        )
    }

    @Test
    fun `d - plain text in a dialog, the control`() = probe("d", inDialog = true) {
        Text("Probe text", modifier = counted)
    }

    @Test
    fun `e - labelled outlined text field in the main window, the control`() =
        probe("e", inDialog = false) {
            OutlinedTextField(
                value = "",
                onValueChange = {},
                label = { Text("Probe label") },
                modifier = counted,
            )
        }

    private fun probe(name: String, inDialog: Boolean, field: @Composable () -> Unit) {
        val shown = mutableStateOf(false)
        compose.setContent {
            MezgebTheme {
                Surface {
                    if (shown.value) {
                        val body = @Composable {
                            fieldView = LocalView.current
                            field()
                        }
                        if (inDialog) {
                            AlertDialog(
                                onDismissRequest = {},
                                title = { Text("Probe title") },
                                text = { Column { body() } },
                                confirmButton = { TextButton(onClick = {}) { Text("Probe ok") } },
                            )
                        } else {
                            Column { body() }
                        }
                    }
                }
            }
        }
        // Nothing is shown yet, so this settles. From here the clock only moves by hand.
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { shown.value = true }

        val afterFrames = mutableListOf<String>()
        repeat(FRAMES) { frame ->
            compose.mainClock.advanceTimeByFrame()
            // The dialog is its own window, laid out by the view system's frames, which
            // follow Robolectric's clock rather than the Compose test clock.
            ShadowLooper.idleMainLooper(FRAME_MILLIS, TimeUnit.MILLISECONDS)
            if (frame == 4 || frame == FRAMES - 1) {
                afterFrames += "frame ${frame + 1}: measured $measures, drawn $draws, " +
                    "layoutRequested=${fieldView?.isLayoutRequested}, " +
                    "windowFocus=${fieldView?.hasWindowFocus()}"
            }
        }
        fail("PROBE $name: ${afterFrames.joinToString(" | ")}")
    }

    private companion object {
        const val FRAMES = 60
        const val FRAME_MILLIS = 16L
    }
}
