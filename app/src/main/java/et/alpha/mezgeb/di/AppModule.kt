package et.alpha.mezgeb.di

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import et.alpha.mezgeb.domain.lock.DeviceUptime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.ZoneId
import javax.inject.Qualifier
import javax.inject.Singleton

/** The IO dispatcher, injected so tests can substitute a deterministic one. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** A scope that lives as long as the process, for work that must outlive a screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Application-wide bindings that belong to no single feature.
 *
 * Deliberately almost empty in Session 1 — the database, repositories and capture
 * bindings arrive in Sessions 3 and 4 and get their own modules.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Injected rather than reached for statically so that tests can wind the clock.
     * Every timestamp in the app is epoch millis UTC, so the clock is UTC too.
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    /**
     * The device's time zone, injected for the same reason as the clock: a day
     * boundary is a local decision, and a test that has to run in Addis to pass is
     * not a test. See [et.alpha.mezgeb.util.MezgebDates].
     */
    @Provides
    fun provideZoneId(): ZoneId = ZoneId.systemDefault()

    /**
     * Time since boot, for the PIN lockout and the auto-lock: unlike [Clock], the
     * person holding the phone cannot set it. See [DeviceUptime].
     */
    @Provides
    @Singleton
    fun provideDeviceUptime(@ApplicationContext context: Context): DeviceUptime =
        object : DeviceUptime {
            override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

            // Missing only on a ROM that strips it. Zero then reads as "same boot"
            // every time, and a restart is caught only when the stored start is ahead
            // of the new boot's clock, which it is for any restart shortly after.
            override fun bootCount(): Int =
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        }

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * SupervisorJob so that one failed background task — a seed, an import — cannot
     * cancel every other piece of application-scoped work.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Lenient on unknown keys so that a rules asset written by a newer build does not
     * hard-fail an older one.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
}
