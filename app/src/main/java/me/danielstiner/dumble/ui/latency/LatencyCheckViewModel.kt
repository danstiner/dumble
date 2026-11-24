package me.danielstiner.dumble.ui.latency

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.danielstiner.dumble.mumble.connection.MumbleConnection
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.voice.latency.CheckOutcome
import me.danielstiner.dumble.mumble.voice.latency.LatencyResults
import me.danielstiner.dumble.mumble.voice.latency.NativeProbeCodec
import javax.inject.Inject

sealed interface LatencyCheckState {
    data object Idle : LatencyCheckState
    data class Running(val step: String) : LatencyCheckState
    data class Done(val text: String) : LatencyCheckState
}

@HiltViewModel
class LatencyCheckViewModel internal constructor(
    private val run: suspend (MumbleEndpoint, String, (String) -> Unit) -> CheckOutcome,
    /** Formats and stores an outcome; returns the text to show. */
    private val report: (CheckOutcome) -> String,
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(connection: MumbleConnection, @ApplicationContext context: Context) : this(
        LatencyCheck(connection, connection, NativeProbeCodec, callVolume = { ResultsFile.callVolume(context) })::run,
        { outcome -> LatencyResults.text(outcome, ResultsFile.deviceFacts()).also { ResultsFile.write(context, it) } },
        Dispatchers.Default,
    )

    private val _state = MutableStateFlow<LatencyCheckState>(LatencyCheckState.Idle)
    val state: StateFlow<LatencyCheckState> = _state

    /** A null [port] is the default. */
    fun start(host: String?, port: Int?, fingerprint: String?) {
        _state.value = LatencyCheckState.Running("starting")
        viewModelScope.launch {
            _state.value = LatencyCheckState.Done(withContext(dispatcher) { report(check(host, port, fingerprint)) })
        }
    }

    private suspend fun check(host: String?, port: Int?, fingerprint: String?): CheckOutcome {
        if (host.isNullOrBlank()) return CheckOutcome.Refused("missing extra: host")
        if (fingerprint.isNullOrBlank()) return CheckOutcome.Refused("missing extra: fingerprint")
        val endpoint = runCatching { MumbleEndpoint.parse(host, port) }
            .getOrElse { return CheckOutcome.Refused("bad extra: ${it.message}") }
        return run(endpoint, fingerprint) { step -> _state.value = LatencyCheckState.Running(step) }
    }
}
