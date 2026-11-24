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
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.voice.latency.CheckOutcome
import me.danielstiner.dumble.mumble.voice.latency.LatencyCheck
import me.danielstiner.dumble.mumble.voice.latency.LatencyResults
import me.danielstiner.dumble.mumble.voice.latency.ResultsFile
import java.time.Instant
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
    @Inject constructor(check: LatencyCheck, @ApplicationContext context: Context) : this(
        check::run,
        { outcome ->
            LatencyResults.text(outcome, ResultsFile.deviceFacts(context)).also {
                ResultsFile.write(context, it, Instant.now())
            }
        },
        Dispatchers.Default,
    )

    private val _state = MutableStateFlow<LatencyCheckState>(LatencyCheckState.Idle)
    val state: StateFlow<LatencyCheckState> = _state

    /** [port] is the raw extra, null for the default. Publishes Running before returning, so the first composition already shows the page. */
    fun start(host: String?, port: String?, fingerprint: String?) {
        if (_state.value is LatencyCheckState.Running) return
        val endpoint = when {
            host.isNullOrBlank() -> return refuse("missing extra: host")
            fingerprint.isNullOrBlank() -> return refuse("missing extra: fingerprint")
            else -> runCatching {
                MumbleEndpoint.parse(host, port?.let { requireNotNull(it.toIntOrNull()) { "port is not a number: $it" } })
            }.getOrElse { return refuse("bad extra: ${it.message}") }
        }
        _state.value = LatencyCheckState.Running("starting")
        viewModelScope.launch {
            val outcome = withContext(dispatcher) {
                run(endpoint, fingerprint) { step -> _state.value = LatencyCheckState.Running(step) }
            }
            _state.value = LatencyCheckState.Done(withContext(dispatcher) { report(outcome) })
        }
    }

    fun dismiss() {
        if (_state.value is LatencyCheckState.Done) _state.value = LatencyCheckState.Idle
    }

    private fun refuse(reason: String) {
        _state.value = LatencyCheckState.Done(report(CheckOutcome.Refused(reason)))
    }
}
