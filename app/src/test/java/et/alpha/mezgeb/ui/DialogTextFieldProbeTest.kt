package et.alpha.mezgeb.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import et.alpha.mezgeb.designsystem.MezgebTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROBE for ROADMAP R18 part 2, one CI cycle, to be replaced by real checks.
 *
 * The add-customer dialog never goes idle under Robolectric, while the same fields in
 * the main window do. Each test here shows a bare AlertDialog with one kind of text
 * field and only waits for the dialog title, so which ones pass splits the text field
 * itself from the floating-label animation. The probe strings are never shown to a
 * user, so they are not in strings.xml.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class DialogTextFieldProbeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a - basic text field in a dialog`() = probe {
        BasicTextField(value = "", onValueChange = {})
    }

    @Test
    fun `b - outlined text field with no label in a dialog`() = probe {
        OutlinedTextField(value = "", onValueChange = {})
    }

    @Test
    fun `c - outlined text field with a label in a dialog`() = probe {
        OutlinedTextField(value = "", onValueChange = {}, label = { Text("Probe label") })
    }

    @Test
    fun `d - filled outlined text field with a label in a dialog`() = probe {
        OutlinedTextField(value = "ABEBE", onValueChange = {}, label = { Text("Probe label") })
    }

    private fun probe(field: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MezgebTheme {
                Surface {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Probe title") },
                        text = { field() },
                        confirmButton = { TextButton(onClick = {}) { Text("Probe ok") } },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Probe title").assertExists()
    }
}
