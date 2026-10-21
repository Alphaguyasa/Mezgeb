package et.alpha.mezgeb.ui

import androidx.annotation.StringRes
import androidx.compose.ui.test.junit4.createComposeRule
import et.alpha.mezgeb.R
import et.alpha.mezgeb.domain.credit.CustomerCredit
import et.alpha.mezgeb.domain.model.CreditEntry
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.CreditStatementLine
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.ui.customers.CustomerDetailScreen
import et.alpha.mezgeb.ui.customers.CustomerDetailUiState
import et.alpha.mezgeb.ui.customers.CustomersScreen
import et.alpha.mezgeb.ui.customers.CustomersUiState
import et.alpha.mezgeb.util.MoneyFormatter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The customer list, the add-customer dialog and one customer's credit statement.
 * The rules are in [AccessibilityChecks].
 *
 * Both screens show balances that can go below zero (the shop holding a customer's
 * money), which is where a minus sign reaches TalkBack if nothing stops it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class CustomersAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val checks = AccessibilityChecks(compose)
    private val context = checks.context

    @Test
    fun `the customer list names and sizes every control`() {
        showCustomers()

        checks.assertControlsAreNamedAndLargeEnough()
        checks.assertSpoken(context.getString(R.string.customers_add))
        checks.assertSpoken(context.getString(R.string.ledger_search_clear))
    }

    @Test
    fun `a customer the shop owes is not read as minus`() {
        showCustomers()

        checks.assertNoSignedAmountIsSpoken()
        checks.assertSpoken(amount(SHOP_OWES_MINOR))
        checks.assertSpoken(amount(OWES_MINOR))
    }

    @Test
    fun `the add customer dialog names and sizes every control`() {
        showCustomers(isAddingCustomer = true)

        checks.assertControlsAreNamedAndLargeEnough()
    }

    @Test
    fun `the statement names and sizes every control`() {
        showStatement()

        checks.assertControlsAreNamedAndLargeEnough()
        checks.assertSpoken(context.getString(R.string.credit_edit_customer))
        checks.assertSpoken(context.getString(R.string.cd_navigate_back))
    }

    @Test
    fun `the statement reads amounts and balances as words`() {
        showStatement()

        checks.assertNoSignedAmountIsSpoken()
        // Each line's amount, without the sign its kind already explains.
        checks.assertSpoken(amount(50_000))
        checks.assertSpoken(amount(2_000))
        // The balance after each line, owed either way or settled.
        checks.assertSpoken(balance(R.string.credit_statement_balance_owes_content_description, 50_000))
        checks.assertSpoken(
            balance(R.string.credit_statement_balance_shop_owes_content_description, 2_000),
        )
        checks.assertSpoken(
            context.getString(R.string.credit_statement_balance_settled_content_description),
        )
    }

    private fun showCustomers(isAddingCustomer: Boolean = false) {
        checks.show {
            CustomersScreen(
                state = CustomersUiState(
                    isLoading = false,
                    customers = listOf(
                        credit("1", "ABEBE", OWES_MINOR, days = 41, overdue = true),
                        credit("2", "CHALTU", 12_500, days = 6, overdue = false),
                        credit("3", "KEBEDE", -SHOP_OWES_MINOR, days = null, overdue = false),
                    ),
                    totalOutstandingMinor = OWES_MINOR + 12_500,
                    customersInDebt = 2,
                    searchQuery = "e",
                    isAddingCustomer = isAddingCustomer,
                ),
                onEvent = {},
                onOpenCustomer = {},
            )
        }
    }

    private fun showStatement() {
        checks.show {
            CustomerDetailScreen(
                state = CustomerDetailUiState(
                    isLoading = false,
                    credit = credit("1", "ABEBE", OWES_MINOR, days = 41, overdue = true),
                    statement = listOf(
                        line("e1", CreditKind.CREDIT_GIVEN, 50_000, running = 50_000),
                        // Linked to a captured payment, so the line has an unlink button.
                        line("e2", CreditKind.REPAYMENT, 50_000, running = 0, linkedTo = "t1"),
                        line("e3", CreditKind.REPAYMENT, 2_000, running = -2_000),
                        line("e4", CreditKind.CREDIT_GIVEN, 47_000, running = OWES_MINOR),
                    ),
                ),
                onEvent = {},
                onBack = {},
                onAddCredit = {},
                onRecordRepayment = {},
                onSendReminder = {},
            )
        }
    }

    private fun amount(amountMinor: Long): String = context.getString(
        R.string.amount_content_description,
        MoneyFormatter.formatUnsigned(amountMinor),
        MoneyFormatter.DEFAULT_CURRENCY,
    )

    private fun balance(@StringRes resId: Int, amountMinor: Long): String = context.getString(
        resId,
        MoneyFormatter.formatUnsigned(amountMinor),
        MoneyFormatter.DEFAULT_CURRENCY,
    )

    private fun credit(
        id: String,
        name: String,
        balanceMinor: Long,
        days: Int?,
        overdue: Boolean,
    ) = CustomerCredit(
        // A phone, so the statement shows its reminder button too. Not a real number.
        customer = Customer(id, name, phone = "0900000000", note = null, createdAt = 0L),
        balanceMinor = balanceMinor,
        oldestUnpaidAt = null,
        daysOutstanding = days,
        isOverdue = overdue,
    )

    private fun line(
        id: String,
        kind: CreditKind,
        amountMinor: Long,
        running: Long,
        linkedTo: String? = null,
    ) = CreditStatementLine(
        entry = CreditEntry(
            id = id,
            customerId = "1",
            kind = kind,
            amountMinor = amountMinor,
            occurredAt = 1_782_900_000_000L,
            note = null,
            linkedTransactionId = linkedTo,
        ),
        runningBalanceMinor = running,
    )

    private companion object {
        const val OWES_MINOR = 45_000L
        const val SHOP_OWES_MINOR = 2_000L
    }
}
