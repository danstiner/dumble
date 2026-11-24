package me.danielstiner.dumble.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.danielstiner.dumble.mumble.connection.Connection
import me.danielstiner.dumble.mumble.connection.MumbleConnection
import me.danielstiner.dumble.mumble.voice.latency.CheckHost
import me.danielstiner.dumble.mumble.voice.latency.LatencyCheck
import me.danielstiner.dumble.mumble.voice.latency.NativeProbeCodec
import me.danielstiner.dumble.mumble.voice.latency.ProbeCodec

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionModule {
    @Binds
    abstract fun bindConnection(impl: MumbleConnection): Connection

    // The same singleton as the Connection binding.
    @Binds
    abstract fun bindCheckHost(impl: MumbleConnection): CheckHost

    companion object {
        @Provides
        fun probeCodec(): ProbeCodec = NativeProbeCodec

        @Provides
        fun latencyCheck(connection: Connection, host: CheckHost, probe: ProbeCodec) =
            LatencyCheck(connection, host, probe)
    }
}
