package et.alpha.mezgeb.ui.tour

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.navigation.MezgebDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The demo tour's order, its buttons, and its Amharic text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "am-w360dp-h1600dp")
class DemoTourTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the tour opens on Today and walks every stop in order`() {
        var state = DemoTourState()
        val visited = mutableListOf<MezgebDestination>()
        while (state.isOpen) {
            visited += state.step!!.destination
            state = state.next()
        }

        assertEquals(
            listOf(
                MezgebDestination.Today,
                MezgebDestination.Ledger,
                MezgebDestination.Customers,
                MezgebDestination.Reconcile,
                MezgebDestination.Reports,
            ),
            visited,
        )
        // "Next" on the last stop closed it, and a closed tour stays closed.
        assertNull(state.stepIndex)
        assertFalse(state.next().isOpen)
    }

    @Test
    fun `back stops at the first stop instead of closing`() {
        val first = DemoTourState()
        assertTrue(first.isFirst)
        assertEquals(first, first.back())
        assertEquals(DemoTourState(0), DemoTourState(1).back())
    }

    @Test
    fun `close ends the tour from any stop, and restart begins again`() {
        val closed = DemoTourState(2).close()
        assertFalse(closed.isOpen)
        assertEquals(DemoTourStep.TODAY, closed.restart().step)
    }

    @Test
    fun `an out-of-range index is a closed tour, not a crash`() {
        assertFalse(DemoTourState(DemoTourStep.entries.size).isOpen)
    }

    @Test
    fun `the card shows the stop in Amharic and its buttons work`() {
        val taps = mutableListOf<String>()
        compose.setContent {
            MezgebTheme {
                DemoTourCard(
                    step = DemoTourStep.CUSTOMERS,
                    isFirst = false,
                    isLast = false,
                    onBack = { taps += "back" },
                    onNext = { taps += "next" },
                    onClose = { taps += "close" },
                )
            }
        }

        compose.onNodeWithText("የዱቤ መዝገብ").assertExists()
        compose.onNodeWithText(context.getString(R.string.tour_progress, 3, 5)).assertExists()
        compose.onNodeWithText(context.getString(R.string.tour_next)).performClick()
        compose.onNodeWithText(context.getString(R.string.tour_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.tour_close)).performClick()

        assertEquals(listOf("next", "back", "close"), taps)
    }

    @Test
    fun `the last stop says finish, and the first has no back button`() {
        compose.setContent {
            MezgebTheme {
                // One stop that is both first and last, to see both edges on one card.
                DemoTourCard(
                    step = DemoTourStep.REPORTS,
                    isFirst = true,
                    isLast = true,
                    onBack = {},
                    onNext = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.tour_finish)).assertExists()
        compose.onNodeWithText(context.getString(R.string.tour_back)).assertDoesNotExist()
    }
}
