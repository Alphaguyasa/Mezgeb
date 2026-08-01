package et.alpha.mezgeb

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import et.alpha.mezgeb.data.local.seed.DatabaseSeeder
import et.alpha.mezgeb.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MezgebApplication : Application() {

    @Inject lateinit var seeder: DatabaseSeeder

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Seeding runs on every launch, not just the first: it is idempotent, and an
        // app update may ship corrected bundled parser rules that need applying.
        // Off the main thread — the first call opens the database.
        applicationScope.launch { seeder.seed() }
    }
}
