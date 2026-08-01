package et.alpha.mezgeb.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

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
}
