package et.alpha.mezgeb.data.demo

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.mezgeb.data.local.MezgebDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app is showing demo data, and which database file that implies.
 *
 * SharedPreferences rather than DataStore, against the project's usual rule, for one
 * reason: this flag has to be readable **synchronously**, before Hilt constructs the
 * database, and DataStore is asynchronous by design. Reading it with `runBlocking` on
 * the main thread at process start is the alternative, and a blocking disk read on the
 * path to the first frame is worse than an exception to a convention.
 *
 * Nothing sensitive lives here — one boolean and a seeded marker.
 */
@Singleton
class DemoModeStore @Inject constructor(
    @param:ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Read on the main thread during startup; see the class doc. */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    /**
     * Written with `commit`, not `apply`, and the difference is the whole feature.
     *
     * The only caller flips this flag and then immediately restarts the process with
     * `Runtime.exit(0)` — the flag decides which database file Room opens, so a restart
     * is the point rather than a side effect. `apply()` writes to memory now and to
     * disk on a background thread, and Android's usual flush of pending writes on
     * process exit does not run when the process is halted outright. The write was
     * being lost every time: the demo database was built, the flag never landed, the
     * app came back reading `false`, and "Show the demo" did nothing at all.
     *
     * `commit()` blocks on the disk write. That is a few milliseconds on the main
     * thread immediately before the process is killed on purpose, which is the one
     * moment where blocking costs nothing.
     */
    fun setEnabled(enabled: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_ENABLED, enabled) }
    }

    /**
     * The database Room should open. The real ledger's file is never named while demo
     * mode is on, which is what "disabling restores the real database untouched"
     * actually rests on: it is not restored, it is never opened.
     */
    fun databaseName(): String =
        if (isEnabled()) DEMO_DATABASE else MezgebDatabase.NAME

    companion object {
        private const val FILE = "mezgeb_demo"
        private const val KEY_ENABLED = "demo_enabled"

        const val DEMO_DATABASE = "mezgeb-demo.db"
    }
}
