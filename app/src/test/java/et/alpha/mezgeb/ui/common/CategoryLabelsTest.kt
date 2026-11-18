package et.alpha.mezgeb.ui.common

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.data.local.seed.DatabaseSeeder
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.domain.model.Category
import et.alpha.mezgeb.domain.model.CategoryKind
import et.alpha.mezgeb.ui.ledger.LedgerFilterBar
import et.alpha.mezgeb.ui.ledger.LedgerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Built-in category names follow the app's language; names the shop typed do not.
 * Run in the Amharic configuration, which is where the English seed names used to leak.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "am")
class CategoryLabelsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an unchanged built-in name is shown in Amharic`() {
        assertEquals("ሽያጭ", CategoryLabels.label(context, "cat_sales", "Sales"))
        assertEquals("ኪራይ", CategoryLabels.label(context, "cat_rent", "Rent"))
    }

    @Test
    fun `a built-in category the shop renamed keeps the shop's word`() {
        assertEquals("Bread", CategoryLabels.label(context, "cat_sales", "Bread"))
    }

    @Test
    fun `a category the shop created is shown as stored`() {
        assertEquals("Transport", CategoryLabels.label(context, "user-1234", "Transport"))
        assertEquals("—", CategoryLabels.label(context, null, "—"))
    }

    @Test
    fun `every seeded category has a translation`() {
        DatabaseSeeder.SYSTEM_CATEGORIES.forEach { category ->
            assertNotNull(category.id, CategoryLabels.resourceFor(category.id, category.name))
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun `in English the label is exactly the seeded name`() {
        DatabaseSeeder.SYSTEM_CATEGORIES.forEach { category ->
            assertEquals(category.name, CategoryLabels.label(context, category.id, category.name))
        }
    }

    @Test
    fun `the ledger's category filter lists built-in categories in Amharic`() {
        val categories = listOf(
            Category("cat_sales", "Sales", CategoryKind.INCOME, "#0B6E4F", isSystem = true),
        )
        compose.setContent {
            MezgebTheme {
                LedgerFilterBar(
                    state = LedgerUiState(
                        isLoading = false,
                        categories = categories,
                        filter = TransactionFilter(categoryId = "cat_sales"),
                    ),
                    onEvent = {},
                )
            }
        }

        compose.onNodeWithText("ሽያጭ").assertExists()
    }
}
