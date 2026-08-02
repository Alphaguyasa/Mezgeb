package et.alpha.mezgeb

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import et.alpha.mezgeb.capture.CredentialPurge
import et.alpha.mezgeb.data.local.seed.DatabaseSeeder
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.di.ApplicationScope
import et.alpha.mezgeb.domain.reconcile.ReconcileReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

@HiltAndroidApp
class MezgebApplication : Application(), Configuration.Provider {

    @Inject lateinit var seeder: DatabaseSeeder

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var clock: Clock

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var reconcileReminders: ReconcileReminderScheduler

    @Inject lateinit var credentialPurge: CredentialPurge

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    /**
     * WorkManager is configured here rather than by App Startup — see the manifest,
     * which removes its default initializer — so that workers can take constructor
     * dependencies from Hilt. The import worker needs the capture funnel, and building
     * that by hand inside a worker would be a second, divergent wiring of the app.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Seeding runs on every launch, not just the first: it is idempotent, and an
        // app update may ship corrected bundled parser rules that need applying.
        // Off the main thread — the first call opens the database.
        applicationScope.launch {
            // Before anything else touches the database: an earlier build stored
            // messages that state PINs and one-time codes, and they should not survive
            // an update by a minute longer than they have to.
            credentialPurge.run()

            seeder.seed()
            // Capture health measures silence from here until the first message
            // arrives, so this stamp has to exist before capture can be judged.
            settings.markFirstRun(clock.millis())
        }

        // Idempotent, and cheap: WorkManager keeps the existing schedule.
        reconcileReminders.schedule()
    }
}
