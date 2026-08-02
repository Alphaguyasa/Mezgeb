package et.alpha.mezgeb.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The single Preferences DataStore. One store, not one per feature: the whole file
 * is a handful of timestamps and flags, and multiple stores would mean multiple
 * files to keep consistent for no benefit.
 *
 * The delegate is a top-level property because that is how DataStore enforces "one
 * instance per file per process" — creating a second one for the same file throws.
 */
private val Context.mezgebSettings: DataStore<Preferences> by preferencesDataStore(
    name = "mezgeb_settings",
)

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.mezgebSettings
}
