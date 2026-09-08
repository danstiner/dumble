package me.danielstiner.dumble

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.net.ClientIdentityStore
import javax.inject.Inject

@HiltAndroidApp
class DumbleApp : Application() {

    @Inject lateinit var identityStore: ClientIdentityStore

    override fun onCreate() {
        super.onCreate()
        // A fresh install generates its identity now rather than inside the first connect; a
        // connect that comes sooner waits on the store's lock for this same generation.
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { identityStore.load() }
                .onFailure { Log.w(TAG, "client identity not loaded; the next connect will say why", it) }
        }
    }

    private companion object {
        const val TAG = "DumbleApp"
    }
}
