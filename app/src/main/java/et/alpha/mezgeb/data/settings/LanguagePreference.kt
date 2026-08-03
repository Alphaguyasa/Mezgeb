package et.alpha.mezgeb.data.settings

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What the app should be written in. [SYSTEM] means "whatever the phone is set to". */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    AMHARIC("am"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/**
 * The language override, and the machinery to apply it.
 *
 * SharedPreferences rather than DataStore for the same reason as
 * [et.alpha.mezgeb.data.demo.DemoModeStore]: this is read in `attachBaseContext`,
 * before anything has a coroutine scope to read it from, and a language that arrives
 * one frame after the first screen is a visible flicker on every launch.
 *
 * Two mechanisms, split by version rather than by preference:
 *  - API 33 and up hand the tag to [LocaleManager], so the choice also appears in the
 *    system's own per-app language screen and survives the app being killed. A user who
 *    changes it in Settings and a user who changes it here end up in the same place.
 *  - Below 33 there is no such API, so the Activity's base context is wrapped with an
 *    overridden configuration. AppCompat's `setApplicationLocales` would do this too,
 *    but pulling AppCompat into a Compose-only app to get it would also pull in its
 *    theming, which this app does not use and would then have to work around.
 */
@Singleton
class LanguagePreference @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun current(): AppLanguage = AppLanguage.fromTag(prefs.getString(KEY_TAG, null))

    fun set(language: AppLanguage) {
        prefs.edit { putString(KEY_TAG, language.tag) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        }
    }

    /**
     * Wraps [base] so that resources resolve in the chosen language.
     *
     * A no-op when the choice is SYSTEM, and a no-op on API 33 and up, where the
     * platform has already applied it and re-applying would fight it.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = current().tag ?: return base

        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(configuration)
    }

    private companion object {
        const val FILE = "mezgeb_language"
        const val KEY_TAG = "language_tag"
    }
}
