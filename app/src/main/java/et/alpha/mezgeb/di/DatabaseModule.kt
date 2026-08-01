package et.alpha.mezgeb.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import et.alpha.mezgeb.data.local.MezgebDatabase
import et.alpha.mezgeb.data.local.dao.CategoryDao
import et.alpha.mezgeb.data.local.dao.CategoryRuleDao
import et.alpha.mezgeb.data.local.dao.CreditEntryDao
import et.alpha.mezgeb.data.local.dao.CustomerDao
import et.alpha.mezgeb.data.local.dao.ParserRuleDao
import et.alpha.mezgeb.data.local.dao.RawMessageDao
import et.alpha.mezgeb.data.local.dao.ReconciliationSessionDao
import et.alpha.mezgeb.data.local.dao.TransactionDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * No `fallbackToDestructiveMigration` anywhere in this builder, deliberately. A
     * missing migration should fail loudly in development rather than quietly wipe a
     * shop's ledger in the field.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MezgebDatabase =
        Room.databaseBuilder(context, MezgebDatabase::class.java, MezgebDatabase.NAME)
            .build()

    @Provides fun provideRawMessageDao(db: MezgebDatabase): RawMessageDao = db.rawMessageDao()

    @Provides fun provideTransactionDao(db: MezgebDatabase): TransactionDao = db.transactionDao()

    @Provides fun provideCustomerDao(db: MezgebDatabase): CustomerDao = db.customerDao()

    @Provides fun provideCreditEntryDao(db: MezgebDatabase): CreditEntryDao = db.creditEntryDao()

    @Provides fun provideCategoryDao(db: MezgebDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideCategoryRuleDao(db: MezgebDatabase): CategoryRuleDao = db.categoryRuleDao()

    @Provides fun provideParserRuleDao(db: MezgebDatabase): ParserRuleDao = db.parserRuleDao()

    @Provides
    fun provideReconciliationSessionDao(db: MezgebDatabase): ReconciliationSessionDao =
        db.reconciliationSessionDao()
}
