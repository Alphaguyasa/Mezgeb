package et.alpha.mezgeb.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.window.Dialog
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.ui.customers.CustomersScreen
import et.alpha.mezgeb.ui.customers.CustomersUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * ROADMAP R18: a probe, not coverage. No Compose dialog has ever settled under
 * Robolectric in this repo (the add-customer check timed out in `setContent` after
 * 60s), and a cloud run has no local Robolectric, so each guess costs a CI cycle. These
 * tests split the question in one cycle: is it every dialog window, or only one shown
 * on the first composition, or only one with text fields, and does advancing the main
 * looper's clock let the dialog's window lay out?
 *
 * A probe that hangs fails with the steps it reached and the dialog window's state at
 * each one, so the CI log says where it stopped. Probes that pass become the pattern
 * for the real dialog checks; this file goes once those exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class DialogProbeTest {

    @get:Rule
    val compose = createComposeRule()

    private var open by mutableStateOf(false)
    private val steps = mutableListOf<String>()

    @Test
    fun `probe 1 a bare AlertDialog on the first composition settles`() = probe {
        open = true
        setHost { BareAlertDialog() }
    }

    @Test
    fun `probe 2 a bare AlertDialog opened after the host settles`() = probe {
        setHost { BareAlertDialog() }
        record("host settled")
        open = true
        compose.waitForIdle()
    }

    @Test
    fun `probe 3 a bare AlertDialog opened with the looper clock advanced`() = probe {
        setHost { BareAlertDialog() }
        record("host settled")
        openAndAdvanceLooper()
    }

    @Test
    fun `probe 4 a bare ui Dialog on the first composition settles`() = probe {
        open = true
        setHost {
            Dialog(onDismissRequest = {}) {
                Surface { Text(stringResource(R.string.customers_add)) }
            }
        }
    }

    @Test
    fun `probe 5 the add customer dialog with the looper clock advanced`() = probe {
        var adding by mutableStateOf(false)
        compose.setContent {
            MezgebTheme {
                Surface {
                    CustomersScreen(
                        state = CustomersUiState(isLoading = false, isAddingCustomer = adding),
                        onEvent = {},
                        onOpenCustomer = {},
                    )
                }
            }
        }
        record("host settled")
        compose.mainClock.autoAdvance = false
        adding = true
        compose.mainClock.advanceTimeByFrame()
        record("one frame after opening")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LOOPER_ADVANCE_MS))
        record("looper advanced")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        record("idle")
        AccessibilityChecks(compose).assertControlsAreNamedAndLargeEnough()
    }

    /** Opens the dialog one frame at a time, so the state is visible between steps. */
    private fun openAndAdvanceLooper() {
        compose.mainClock.autoAdvance = false
        open = true
        compose.mainClock.advanceTimeByFrame()
        record("one frame after opening")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LOOPER_ADVANCE_MS))
        record("looper advanced")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun setHost(dialog: @Composable () -> Unit) {
        compose.setContent {
            MezgebTheme {
                Surface {
                    Text(stringResource(R.string.customers_add))
                    if (open) dialog()
                }
            }
        }
    }

    @Composable
    private fun BareAlertDialog() {
        AlertDialog(
            onDismissRequest = {},
            text = { Text(stringResource(R.string.customers_add)) },
            confirmButton = {
                TextButton(onClick = {}) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    private fun probe(body: () -> Unit) {
        try {
            body()
            record("idle")
        } catch (failure: Throwable) {
            record("failed: ${failure.javaClass.simpleName}")
            throw AssertionError("dialog probe stopped:\n" + steps.joinToString("\n"), failure)
        }
    }

    /** The main looper and the dialog window as they are now, one line per step. */
    private fun record(step: String) {
        val looper = shadowOf(Looper.getMainLooper())
        val dialog = ShadowDialog.getLatestDialog()
        val decor: View? = dialog?.window?.decorView
        val composeView = decor?.let(::findComposeView)
        steps += "$step: looperIdle=${looper.isIdle} nextTask=${looper.nextScheduledTaskTime} " +
            "dialog=${dialog?.javaClass?.simpleName} showing=${dialog?.isShowing} " +
            "decor[attached=${decor?.isAttachedToWindow} layoutRequested=${decor?.isLayoutRequested} " +
            "size=${decor?.width}x${decor?.height} focus=${decor?.hasWindowFocus()}] " +
            "compose[${composeView?.javaClass?.simpleName} " +
            "layoutRequested=${composeView?.isLayoutRequested} " +
            "size=${composeView?.width}x${composeView?.height}]"
    }

    private fun findComposeView(view: View): View? {
        if (view.javaClass.simpleName == "AndroidComposeView") return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            findComposeView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private companion object {
        const val LOOPER_ADVANCE_MS = 100L
    }
}
