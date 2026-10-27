package et.alpha.mezgeb.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import et.alpha.mezgeb.R
import et.alpha.mezgeb.domain.model.CaptureSource
import et.alpha.mezgeb.domain.model.Category
import et.alpha.mezgeb.domain.model.CategoryKind
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.domain.model.RawMessage
import et.alpha.mezgeb.domain.model.Transaction
import et.alpha.mezgeb.ui.detail.TransactionDetailScreen
import et.alpha.mezgeb.ui.detail.TransactionDetailUiState
import et.alpha.mezgeb.ui.detail.TransactionEditor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One transaction, read and edited. The rules are in [AccessibilityChecks].
 *
 * The detail screen was the last one without these checks (ROADMAP R24). They found
 * the same defect R17 fixed in Settings: the "Reconciled" switch and the "always use
 * this category" checkbox each had their own click handler, so each was a second,
 * nameless control beside its label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class TransactionDetailAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val checks = AccessibilityChecks(compose)
    private val context = checks.context

    @Test
    fun `the detail names and sizes every control`() {
        checks.show { DetailScreen(busiestTransactionDetail()) }

        checks.assertControlsAreNamedAndLargeEnough()
        checks.assertSpoken(context.getString(R.string.detail_edit))
        checks.assertSpoken(context.getString(R.string.cd_navigate_back))
    }

    @Test
    fun `the reconciled switch and the category checkbox are read with their labels`() {
        checks.show { DetailScreen(busiestTransactionDetail()) }

        assertToggleNamed(R.string.detail_reconciled)
        assertToggleNamed(R.string.detail_category_always)
    }

    @Test
    fun `the detail reads its amount as words`() {
        checks.show { DetailScreen(busiestTransactionDetail()) }

        checks.assertNoSignedAmountIsSpoken()
        checks.assertSpoken(checks.money(R.string.direction_in, DETAIL_AMOUNT_MINOR))
    }

    @Test
    fun `the editor names and sizes every control`() {
        checks.show { DetailScreen(busiestTransactionDetail(editing = true)) }

        checks.assertControlsAreNamedAndLargeEnough()
    }

    @Test
    fun `the delete confirmation names and sizes every control`() {
        checks.show { DetailScreen(busiestTransactionDetail().copy(isConfirmingDelete = true)) }

        compose.onNodeWithText(context.getString(R.string.detail_delete_title)).assertExists()
        checks.assertControlsAreNamedAndLargeEnough()
    }

    private fun assertToggleNamed(@StringRes labelRes: Int) {
        val label = context.getString(labelRes)
        val found = compose.onAllNodes(hasText(label) and isToggleable()).fetchSemanticsNodes()
        assertTrue("no toggle is read as \"$label\"", found.isNotEmpty())
    }
}

@Composable
internal fun DetailScreen(state: TransactionDetailUiState) {
    TransactionDetailScreen(state = state, onEvent = {}, onBack = {})
}

internal const val DETAIL_AMOUNT_MINOR = 1_245_000L

/**
 * The detail in its busiest state: edited, flagged as a possible duplicate, with a
 * six-figure balance, a counterparty (so the "always use this category" row shows),
 * a linked credit and the original message open.
 */
internal fun busiestTransactionDetail(editing: Boolean = false): TransactionDetailUiState {
    val at = 1_782_983_640_000L
    val transaction = Transaction(
        id = "t1",
        rawMessageId = "m1",
        direction = Direction.IN,
        amountMinor = DETAIL_AMOUNT_MINOR,
        currency = "ETB",
        channel = Channel.TELEBIRR,
        counterpartyName = "ABEBE",
        // Not a real number.
        counterpartyPhone = "+251900000001",
        providerTxnId = "REF0000001",
        balanceAfterMinor = 12_345_678,
        occurredAt = at,
        categoryId = "cat_sales",
        note = "NOTE",
        isReconciled = false,
        reconciliationSessionId = null,
        isDeleted = false,
        editedAt = at,
        possibleDuplicateOfId = "t0",
    )
    return TransactionDetailUiState(
        isLoading = false,
        transaction = transaction,
        rawMessage = RawMessage(
            id = "m1",
            sender = "127",
            body = "You have received ETB 12,450.00 from ABEBE. Ref REF0000001.",
            contentHash = "h1",
            receivedAt = at,
            source = CaptureSource.NOTIFICATION,
            parseState = ParseState.PARSED,
            matchedRuleId = "r1",
        ),
        categories = listOf(Category("cat_sales", "Sales", CategoryKind.INCOME, "#2E7D32", true)),
        categoryName = "Sales",
        isBodyExpanded = true,
        linkedCustomerName = "CHALTU",
        editor = if (editing) {
            TransactionEditor(
                amountText = "12450.00",
                direction = Direction.IN,
                channel = Channel.TELEBIRR,
                counterpartyName = "ABEBE",
                counterpartyPhone = "+251900000001",
                occurredAt = at,
                note = "NOTE",
            )
        } else {
            null
        },
    )
}
