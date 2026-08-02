package et.alpha.mezgeb.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import et.alpha.mezgeb.capture.ParserProvider
import et.alpha.mezgeb.capture.RuleBackedParserProvider
import et.alpha.mezgeb.capture.notification.AndroidCaptureAccess
import et.alpha.mezgeb.capture.notification.CaptureAccess
import et.alpha.mezgeb.util.IdGenerator
import et.alpha.mezgeb.util.UuidGenerator
import javax.inject.Singleton

/**
 * The seams in the capture layer.
 *
 * Each of these exists as an interface for one reason: the funnel's tests are about
 * what it concluded, and they should not need a system settings provider, a real
 * rules table or a random number generator to find out.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CaptureModule {

    @Binds
    @Singleton
    abstract fun bindParserProvider(provider: RuleBackedParserProvider): ParserProvider

    @Binds
    @Singleton
    abstract fun bindCaptureAccess(access: AndroidCaptureAccess): CaptureAccess

    @Binds
    @Singleton
    abstract fun bindIdGenerator(generator: UuidGenerator): IdGenerator
}
