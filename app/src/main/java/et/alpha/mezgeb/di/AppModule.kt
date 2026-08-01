package et.alpha.mezgeb.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.time.Clock
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
