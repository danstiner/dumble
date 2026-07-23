package me.danielstiner.dumble.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.danielstiner.dumble.data.PinDataStore
import me.danielstiner.dumble.mumble.net.PinStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MumbleModule {

    /** Renaming this orphans every stored pin, turning known servers back into first contact. */
    const val PIN_STORE_NAME = "mumble_pins"

    @Provides
    @Singleton
    fun providePinDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(PIN_STORE_NAME) }

    @Provides
    @Singleton
    fun providePinStore(dataStore: DataStore<Preferences>): PinStore = PinDataStore(dataStore)
}
