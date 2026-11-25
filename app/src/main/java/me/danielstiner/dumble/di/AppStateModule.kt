package me.danielstiner.dumble.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.danielstiner.dumble.session.AppState
import me.danielstiner.dumble.session.AppStateImpl

@Module
@InstallIn(SingletonComponent::class)
abstract class AppStateModule {
    @Binds
    abstract fun bindAppState(impl: AppStateImpl): AppState
}
