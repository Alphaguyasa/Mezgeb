package et.alpha.mezgeb.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import et.alpha.mezgeb.R
import et.alpha.mezgeb.domain.credit.CustomerCredit
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.domain.model.ReconciliationSession
import et.alpha.mezgeb.domain.model.SessionStatus
import et.alpha.mezgeb.ui.customers.CustomerDetailScreen
import et.alpha.mezgeb.ui.customers.CustomerDetailUiState
import et.alpha.mezgeb.ui.reconcile.ReconcileScreen
import et.alpha.mezgeb.ui.reconcile.ReconcileUiState
import et.alpha.mezgeb.ui.settings.SettingsDialog
import et.alpha.mezgeb.ui.settings.SettingsScreen
import et.alpha.mezgeb.ui.settings.SettingsUiState
import et.alpha.mezgeb.util.MoneyFormatter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dialogs with no text field: archive a customer, remove the PIN, and a closed
 * period's snapshot. The rules are in [AccessibilityChecks], and they see the dialog
 * window as well as the screen behind it.
 *
 * ROADMAP R18. A probe on PR #34 settled the question of whether a Compose dialog can
 * be tested under Robolectric here at all: it can. A bare `AlertDialog` and a bare
 * `Dialog` both went idle, shown on the first composition or opened later. Only the
 * add-customer dialog hung, with its compose root asking for layout on every frame
 * for the full 60s. The difference is its two `OutlinedTextField`s (PR #37 narrowed it
 * to the floating label). The dialogs with text fields are checked in
 * [TextFieldDialogAccessibilityTest], which reads the dialog's tree without waiting
 * for idle.
 *
 * Each test first finds the dialog's own text, because the checks would pass on the
 * screen behind it alone if the dialog never opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class DialogAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val checks = AccessibilityChecks(compose)
    private val context = checks.context

    @Test
    fun `the archive confirmation names and sizes every control`() {
        checks.show {
            CustomerDetailScreen(
                state = CustomerDetailUiState(
                    isLoading = false,
                    credit = credit(),
                    isConfirmingArchive = true,
                ),
                onEvent = {},
                onBack = {},
                onAddCredit = {},
                onRecordRepayment = {},
                onSendReminder = {},
            )
        }

        compose.onNodeWithText(context.getString(R.string.credit_archive_title)).assertExists()
        checks.assertControlsAreNamedAndLargeEnough()
    }

    @Test
    fun `the remove PIN confirmation names and sizes every control`() {
        checks.show {
            SettingsScreen(
                state = SettingsUiState(isLockEnabled = true, dialog = SettingsDialog.RemovePin),
                onEvent = {},
                onBack = {},
                onBackupConfirmed = {},
                onRestoreRequested = {},
                onRestoreConfirmed = { _, _ -> },
                onLanguageChosen = {},
            )
        }

        compose.onNodeWithText(context.getString(R.string.settings_lock_remove_title))
            .assertExists()
        checks.assertControlsAreNamedAndLargeEnough()
    }

    @Test
    fun `a closed period's snapshot reads credit repaid as less, not minus`() {
        checks.show {
            ReconcileScreen(
                state = ReconcileUiState(
                    isLoading = false,
                    closedSessions = listOf(CLOSED),
                    viewingSnapshot = CLOSED,
                ),
                onEvent = {},
                onBack = {},
                onExplanationTapped = {},
            )
        }

        compose.onNodeWithText(context.getString(R.string.reconcile_snapshot_note)).assertExists()
        checks.assertControlsAreNamedAndLargeEnough()
        checks.assertNoSignedAmountIsSpoken()
        checks.assertSpoken(
            context.getString(
                R.string.reconcile_figure_less_content_description,
                context.getString(R.string.reconcile_credit_repaid),
                MoneyFormatter.formatUnsigned(CLOSED.creditRepaidMinor),
                MoneyFormatter.DEFAULT_CURRENCY,
            ),
        )
    }

    private fun credit() = CustomerCredit(
        customer = Customer("1", "ABEBE", phone = null, note = null, createdAt = 0L),
        balanceMinor = 45_000,
        oldestUnpaidAt = null,
        daysOutstanding = 41,
        isOverdue = true,
    )

    private companion object {
        val CLOSED = ReconciliationSession(
            id = "s1",
            periodStart = 1_782_950_400_000L,
            periodEnd = 1_783_036_799_999L,
            openedAt = 1_782_950_400_000L,
            expectedTotalMinor = 245_000,
            capturedInMinor = 200_000,
            capturedOutMinor = 15_000,
            cashRecordedMinor = 45_000,
            creditGivenMinor = 0,
            creditRepaidMinor = 5_000,
            status = SessionStatus.CLOSED,
            closedAt = 1_783_036_799_999L,
            note = null,
        )
    }
}
