package et.alpha.mezgeb.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import et.alpha.mezgeb.domain.reconcile.ExpectedSalesProvider
import et.alpha.mezgeb.domain.reconcile.ManualEntryExpectedSalesProvider
import javax.inject.Singleton

/**
 * Domain seams.
 *
 * [ExpectedSalesProvider] is the one that matters: v2 adds a Suq-backed
 * implementation, and this binding is the only line that should have to change.
 * Nothing above the interface may name the concrete class — see
 * 03-ARCHITECTURE.md section 9.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {

    @Binds
    @Singleton
    abstract fun bindExpectedSalesProvider(
        provider: ManualEntryExpectedSalesProvider,
    ): ExpectedSalesProvider
}
