package et.alpha.mezgeb.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import et.alpha.mezgeb.R
import et.alpha.mezgeb.data.local.seed.DatabaseSeeder

/**
 * Display names for the built-in categories.
 *
 * The seeder stores "Sales", "Rent" and the rest as English literals, because the row
 * is written once at first launch and the language can change afterwards. So the name
 * in the database is a default, not a translation, and every screen that shows a
 * category asks this object first.
 *
 * Only an unchanged built-in name is replaced. A shop that renamed "Sales" to its own
 * word sees its own word in every language: what the user typed is never second-guessed.
 * Categories the shop created are always shown as stored.
 *
 * The CSV export does not use this. A spreadsheet column should not change with the
 * phone's language, so it keeps the stored names.
 */
object CategoryLabels {

    private val RESOURCES: Map<String, Int> = mapOf(
        "cat_sales" to R.string.category_sales,
        "cat_supplier" to R.string.category_supplier,
        "cat_rent" to R.string.category_rent,
        "cat_utilities" to R.string.category_utilities,
        "cat_salary" to R.string.category_salary,
        "cat_transfer" to R.string.category_transfer,
        "cat_other" to R.string.category_other,
    )

    private val SEEDED_NAMES: Map<String, String> =
        DatabaseSeeder.SYSTEM_CATEGORIES.associate { it.id to it.name }

    /**
     * The string resource to show for this category, or null when the stored name
     * should be shown as it is (a category the shop made, or a built-in one it renamed).
     */
    @StringRes
    fun resourceFor(id: String?, storedName: String?): Int? {
        if (id == null) return null
        val res = RESOURCES[id] ?: return null
        return if (storedName == null || storedName == SEEDED_NAMES[id]) res else null
    }

    /** For code outside Compose, such as the PDF report. */
    fun label(context: Context, id: String?, storedName: String): String =
        resourceFor(id, storedName)?.let { context.getString(it) } ?: storedName
}

/**
 * The name to show for a category, in the app's language when it is an unchanged
 * built-in one. Resolved here rather than with the application context, because the
 * activity applies the chosen language to its own context only, and below API 33 the
 * application context keeps the system language.
 */
@Composable
fun categoryLabel(id: String?, storedName: String): String =
    CategoryLabels.resourceFor(id, storedName)?.let { stringResource(it) } ?: storedName
