package et.alpha.mezgeb.ui.ledger

import android.content.Context
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.R
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.designsystem.MezgebTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The possible-duplicates chip: off means every row, on means only flagged rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerFilterBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val label = ApplicationProvider.getApplicationContext<Context>()
        .getString(R.string.ledger_filter_possible_duplicates)

    private val events = mutableListOf<LedgerEvent>()

    @Test
    fun `tapping the chip shows only possible duplicates`() {
        show(TransactionFilter())

        compose.onNodeWithText(label).assertIsNotSelected().performScrollTo().performClick()

        assertEquals(
            listOf(LedgerEvent.FilterChanged(TransactionFilter(isPossibleDuplicate = true))),
            events,
        )
    }

    @Test
    fun `tapping it again shows every row, not only the unflagged ones`() {
        show(TransactionFilter(isPossibleDuplicate = true))

        compose.onNodeWithText(label).assertIsSelected().performScrollTo().performClick()

        assertEquals(listOf(LedgerEvent.FilterChanged(TransactionFilter())), events)
    }

    private fun show(filter: TransactionFilter) {
        compose.setContent {
            MezgebTheme {
                LedgerFilterBar(
                    state = LedgerUiState(isLoading = false, filter = filter),
                    onEvent = { events += it },
                )
            }
        }
    }
}
