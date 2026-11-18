package et.alpha.mezgeb.ui.today

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.domain.reconcile.MissedMoney
import et.alpha.mezgeb.util.MoneyFormatter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The weekly card says what may be missing, keeps double counts apart, and opens Reconcile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class MissedMoneyCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the card leads with the missing total and lists a double count on its own`() {
        var opened = 0
        val missed = MissedMoney(
            balanceJumpCount = 1,
            balanceJumpMinor = 30_000,
            possibleDuplicateCount = 1,
            possibleDuplicatesMinor = 45_000,
        )
        compose.setContent {
            MezgebTheme { MissedMoneyCard(missed = missed, onCheck = { opened++ }) }
        }

        compose.onNodeWithText(
            context.getString(R.string.today_missed_headline, MoneyFormatter.format(30_000)),
        ).assertExists()
        compose.onNodeWithText(
            context.resources.getQuantityString(
                R.plurals.today_missed_duplicates,
                1,
                MoneyFormatter.format(45_000),
                1,
            ),
        ).assertExists()

        compose.onNodeWithText(context.getString(R.string.today_missed_action)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `with only a double count there is no missing headline`() {
        compose.setContent {
            MezgebTheme {
                MissedMoneyCard(
                    missed = MissedMoney(possibleDuplicateCount = 2, possibleDuplicatesMinor = 90_000),
                    onCheck = {},
                )
            }
        }

        compose.onNodeWithText(
            context.getString(R.string.today_missed_headline, MoneyFormatter.format(0)),
        ).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.today_missed_title)).assertExists()
    }
}
