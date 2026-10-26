package et.alpha.mezgeb.ui

import androidx.compose.ui.test.junit4.createComposeRule
import et.alpha.mezgeb.R
import et.alpha.mezgeb.domain.credit.CustomerCredit
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.ui.customers.CustomerDetailScreen
import et.alpha.mezgeb.ui.customers.CustomerDetailUiState
import et.alpha.mezgeb.ui.customers.CustomerEditor
import et.alpha.mezgeb.ui.customers.CustomersScreen
import et.alpha.mezgeb.ui.customers.CustomersUiState
import et.alpha.mezgeb.ui.settings.SettingsDialog
import et.alpha.mezgeb.ui.settings.SettingsScreen
import et.alpha.mezgeb.ui.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dialogs with a text field: add and edit a customer, set the PIN, and the backup
 * and restore passphrases. ROADMAP R18 part 2.
 *
 * These cannot be checked the way [DialogAccessibilityTest] checks the others. A
 * labelled text field keeps a dialog window measuring under Robolectric, so the rule's
 * finders, which wait for idle, never return. [AccessibilityChecks.openDialog] reads the
 * dialog's own semantics tree instead, after a fixed number of frames, and the checks
 * run on that. Only the dialog is checked here: the screens behind them have their own
 * tests.
 *
 * Each test also names the fields it expects by their labels, because a text field
 * is read by its label, and a field with none would be "edit box" and nothing more.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class TextFieldDialogAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val checks = AccessibilityChecks(compose)
    private val context = checks.context

    @Test
    fun `the add customer dialog names and sizes every control`() {
        val dialog = checks.openDialog { open ->
            CustomersScreen(
                state = CustomersUiState(isLoading = false, isAddingCustomer = open),
                onEvent = {},
                onOpenCustomer = {},
            )
        }

        checks.assertShows(dialog, context.getString(R.string.customers_add))
        checks.assertControlsAreNamedAndLargeEnough(dialog)
        checks.assertControlNamed(dialog, context.getString(R.string.customers_name))
        checks.assertControlNamed(dialog, context.getString(R.string.customers_phone))
    }

    @Test
    fun `the edit customer dialog names and sizes every control`() {
        val dialog = checks.openDialog { open ->
            CustomerDetailScreen(
                state = CustomerDetailUiState(
                    isLoading = false,
                    credit = CREDIT,
                    editor = if (open) CustomerEditor(name = "ABEBE", phone = "") else null,
                ),
                onEvent = {},
                onBack = {},
                onAddCredit = {},
                onRecordRepayment = {},
                onSendReminder = {},
            )
        }

        checks.assertShows(dialog, context.getString(R.string.credit_edit_customer))
        checks.assertControlsAreNamedAndLargeEnough(dialog)
        checks.assertControlNamed(dialog, context.getString(R.string.customers_name))
        checks.assertControlNamed(dialog, context.getString(R.string.customers_phone))
    }

    @Test
    fun `the set PIN dialog names its field and its acknowledgement`() {
        val dialog = openSettings(SettingsDialog.SetPin)

        checks.assertShows(dialog, context.getString(R.string.settings_lock_set_title))
        checks.assertControlsAreNamedAndLargeEnough(dialog)
        checks.assertControlNamed(dialog, context.getString(R.string.settings_lock_pin_label))
        // The row is the checkbox's control, so the row must say what is agreed to.
        checks.assertControlNamed(dialog, context.getString(R.string.settings_lock_acknowledge))
    }

    @Test
    fun `the backup passphrase dialog names and sizes every control`() {
        val dialog = openSettings(SettingsDialog.BackupPassphrase)

        checks.assertShows(dialog, context.getString(R.string.settings_backup_action))
        checks.assertControlsAreNamedAndLargeEnough(dialog)
        checks.assertControlNamed(dialog, context.getString(R.string.settings_passphrase_label))
    }

    @Test
    fun `the restore passphrase dialog names and sizes every control`() {
        val dialog = openSettings(SettingsDialog.RestorePassphrase(uri = "content://backup"))

        checks.assertShows(dialog, context.getString(R.string.settings_restore_action))
        checks.assertControlsAreNamedAndLargeEnough(dialog)
        checks.assertControlNamed(dialog, context.getString(R.string.settings_passphrase_label))
        checks.assertControlNamed(dialog, context.getString(R.string.settings_restore_confirm))
    }

    private fun openSettings(dialog: SettingsDialog) = checks.openDialog { open ->
        SettingsScreen(
            state = SettingsUiState(dialog = if (open) dialog else null),
            onEvent = {},
            onBack = {},
            onBackupConfirmed = {},
            onRestoreRequested = {},
            onRestoreConfirmed = { _, _ -> },
            onLanguageChosen = {},
        )
    }

    private companion object {
        val CREDIT = CustomerCredit(
            customer = Customer("1", "ABEBE", phone = null, note = null, createdAt = 0L),
            balanceMinor = 45_000,
            oldestUnpaidAt = null,
            daysOutstanding = 41,
            isOverdue = true,
        )
    }
}
