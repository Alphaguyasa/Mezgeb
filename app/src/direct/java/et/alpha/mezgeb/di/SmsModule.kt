package et.alpha.mezgeb.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import et.alpha.mezgeb.capture.sms.DirectSmsImporter
import et.alpha.mezgeb.capture.sms.InboxSource
import et.alpha.mezgeb.capture.sms.SmsImporter
import et.alpha.mezgeb.capture.sms.SmsInboxReader
import javax.inject.Singleton

/**
 * The `direct` half of the flavor-specific capture binding. Its `play` twin lives at
 * the same path under `src/play` and binds a no-op.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SmsModule {

    @Binds
    @Singleton
    abstract fun bindSmsImporter(importer: DirectSmsImporter): SmsImporter

    @Binds
    abstract fun bindInboxSource(reader: SmsInboxReader): InboxSource
}
