package et.alpha.mezgeb.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import et.alpha.mezgeb.capture.sms.PlaySmsImporter
import et.alpha.mezgeb.capture.sms.SmsImporter
import javax.inject.Singleton

/**
 * The `play` half of the flavor-specific capture binding. Its `direct` twin lives at
 * the same path under `src/direct` and binds the real importer.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SmsModule {

    @Binds
    @Singleton
    abstract fun bindSmsImporter(importer: PlaySmsImporter): SmsImporter
}
