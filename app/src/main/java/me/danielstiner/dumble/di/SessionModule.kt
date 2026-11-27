package me.danielstiner.dumble.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import me.danielstiner.dumble.mumble.net.AndroidNetworkWatch
import me.danielstiner.dumble.mumble.net.ClientIdentityStore
import me.danielstiner.dumble.mumble.net.MumbleTcpTransport
import me.danielstiner.dumble.mumble.net.MumbleUdpTransport
import me.danielstiner.dumble.mumble.net.PinStore
import me.danielstiner.dumble.mumble.protocol.ConnectionStateMachine
import me.danielstiner.dumble.mumble.voice.AndroidVoiceCall
import me.danielstiner.dumble.mumble.voice.VoiceSender
import me.danielstiner.dumble.mumble.voice.openNativeCapture
import me.danielstiner.dumble.mumble.voice.openNativePlayout
import me.danielstiner.dumble.session.CaptureLifecycle
import me.danielstiner.dumble.session.SessionParts
import me.danielstiner.dumble.time.BootTimeSource
import javax.inject.Singleton
import kotlin.time.TimeSource

@Module
@InstallIn(SingletonComponent::class)
internal object SessionModule {
    @Provides
    @Singleton
    fun sessionParts(
        @ApplicationContext context: Context,
        pinStore: PinStore,
        identityStore: ClientIdentityStore,
    ) = SessionParts(
        pinStore = pinStore,
        newTransport = { MumbleTcpTransport(it, identityStore = identityStore) },
        newUdp = { crypt, listener -> MumbleUdpTransport(crypt, listener, BootTimeSource) },
        newPlayout = { openNativePlayout() },
        newCapture = { openNativeCapture(context) },
        call = AndroidVoiceCall(context),
        captures = CaptureLifecycle(
            VoiceSender::start, TimeSource.Monotonic,
            CaptureLifecycle.STUCK_PUMP_MILLIS, Dispatchers.Default, Dispatchers.IO,
        ),
        bootClock = BootTimeSource,
        pingIntervalMs = ConnectionStateMachine.PING_INTERVAL_MS,
        sleep = { delay(it) },
        networkWatch = AndroidNetworkWatch(context),
    )
}
