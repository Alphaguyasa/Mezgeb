package et.alpha.mezgeb.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.paging.PagingData
import et.alpha.mezgeb.R
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.ui.common.TransactionUiModel
import et.alpha.mezgeb.ui.ledger.DateRange
import et.alpha.mezgeb.ui.ledger.LedgerItem
import et.alpha.mezgeb.ui.ledger.LedgerScreen
import et.alpha.mezgeb.ui.ledger.LedgerUiState
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ledger with every filter control showing (a search typed, a filter on, so the
 * clear buttons appear) and a day of money in and out. The rules are in
 * [AccessibilityChecks].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class LedgerAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val checks = AccessibilityChecks(compose)
    private val context = checks.context

    @Test
    fun `the ledger names and sizes every control`() {
        showBusiestLedger()

        checks.assertControlsAreNamedAndLargeEnough()
        checks.assertSpoken(context.getString(R.string.ledger_search_clear))
    }

    @Test
    fun `the ledger reads every amount as words`() {
        showBusiestLedger()

        checks.assertNoSignedAmountIsSpoken()
        // The day's subtotals and each row.
        checks.assertSpoken(checks.money(R.string.direction_in, DAY_IN_MINOR))
        checks.assertSpoken(checks.money(R.string.direction_out, DAY_OUT_MINOR))
        checks.assertSpoken(checks.money(R.string.direction_in, FIRST_IN_MINOR))
        checks.assertSpoken(checks.money(R.string.direction_in, SECOND_IN_MINOR))
    }

    private fun showBusiestLedger() {
        val rows = listOf(
            LedgerItem.Header(DAY, inMinor = DAY_IN_MINOR, outMinor = DAY_OUT_MINOR),
            LedgerItem.Row(transaction("1", Direction.IN, FIRST_IN_MINOR, "ABEBE")),
            LedgerItem.Row(transaction("2", Direction.IN, SECOND_IN_MINOR, "CHALTU")),
            LedgerItem.Row(transaction("3", Direction.OUT, DAY_OUT_MINOR, counterparty = null)),
        )
        checks.show {
            LedgerScreen(
                state = LedgerUiState(
                    isLoading = false,
                    searchQuery = "abe",
                    dateRange = DateRange.LAST_7_DAYS,
                    filter = TransactionFilter(direction = Direction.IN, query = "abe"),
                ),
                items = flowOf(PagingData.from(rows)),
                onEvent = {},
                onOpenTransaction = {},
            )
        }
        // Paging hands the rows over a frame or two after the first composition.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("ABEBE").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun transaction(
        id: String,
        direction: Direction,
        amountMinor: Long,
        counterparty: String?,
    ) = TransactionUiModel(
        id = id,
        direction = direction,
        amountMinor = amountMinor,
        channel = if (direction == Direction.IN) Channel.TELEBIRR else Channel.CASH,
        counterparty = counterparty,
        occurredAt = DAY + 3_600_000L,
        categoryName = null,
        isEdited = false,
        isReconciled = false,
        isPossibleDuplicate = direction == Direction.OUT,
    )

    private companion object {
        const val DAY = 1_782_950_400_000L
        const val FIRST_IN_MINOR = 30_000L
        const val SECOND_IN_MINOR = 15_000L
        const val DAY_IN_MINOR = FIRST_IN_MINOR + SECOND_IN_MINOR
        const val DAY_OUT_MINOR = 12_500L
    }
}
