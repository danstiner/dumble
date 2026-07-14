package me.danielstiner.dumble

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import me.danielstiner.dumble.mumble.voice.AndroidAudioIn
import me.danielstiner.dumble.mumble.voice.CAPTURE_SAMPLES
import me.danielstiner.dumble.mumble.voice.EnergyVadDetector
import me.danielstiner.dumble.mumble.voice.FRAMES_PER_PACKET
import me.danielstiner.dumble.mumble.voice.FRAME_SAMPLES_10MS
import me.danielstiner.dumble.mumble.voice.RnnoiseSuppressor
import me.danielstiner.dumble.mumble.voice.TransmitGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Local VAD gate tuner. Runs the real transmit chain (mic -> RNNoise -> EnergyVadDetector ->
 * TransmitGate) with NO server, and shows live whether the gate opens for the current input.
 * A slider tunes the absolute open threshold (absOpenDb) so it can be dialed in by ear/eye.
 * No playback (avoids feedback) — this is a visual tuner.
 */
class VadDebugActivity : AppCompatActivity() {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    // Absolute-open-gate slider range, in dB.
    private val minDb = -90f
    private val maxDb = -30f
    @Volatile private var absOpenDb = -55f
    @Volatile private var rnnoiseOn = true

    private val detector = EnergyVadDetector()
    private val gate = TransmitGate()

    private lateinit var readout: TextView
    private lateinit var gateView: TextView
    private lateinit var thresholdText: TextView
    private lateinit var startButton: Button
    private lateinit var rnnoiseButton: Button

    @Volatile private var sent = 0L

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) start() else readout.text = "RECORD_AUDIO permission required" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        layout.addView(TextView(this).apply {
            text = "VAD Gate Tuner"
            textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })

        gateView = TextView(this).apply {
            text = "—"
            textSize = 40f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 24)
            setTextColor(Color.GRAY)
        }
        layout.addView(gateView)

        readout = TextView(this).apply {
            text = "Stopped"
            textSize = 16f; setTextColor(Color.LTGRAY)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 32)
        }
        layout.addView(readout)

        thresholdText = TextView(this).apply {
            text = "Open threshold: %.0f dB".format(absOpenDb)
            setTextColor(Color.WHITE)
        }
        layout.addView(thresholdText)

        layout.addView(SeekBar(this).apply {
            max = 100
            progress = dbToProgress(absOpenDb)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    absOpenDb = progressToDb(p)
                    detector.absOpenDb = absOpenDb
                    thresholdText.text = "Open threshold: %.0f dB".format(absOpenDb)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 48) }
        })

        startButton = Button(this).apply {
            text = "Start"
            setOnClickListener {
                if (running.get()) stop()
                else if (hasMic()) start()
                else requestPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        layout.addView(startButton)

        rnnoiseButton = Button(this).apply {
            text = "RNNoise: ON"
            setOnClickListener {
                rnnoiseOn = !rnnoiseOn
                text = if (rnnoiseOn) "RNNoise: ON" else "RNNoise: OFF"
            }
        }
        layout.addView(rnnoiseButton)

        setContentView(layout)
    }

    private fun hasMic() = ActivityCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        if (running.get()) return
        running.set(true)
        sent = 0
        detector.absOpenDb = absOpenDb
        gate.reset()
        startButton.text = "Stop"
        thread = Thread({ loop() }, "vad-debug").apply { isDaemon = true; start() }
    }

    private fun stop() {
        running.set(false)
        thread?.join(500); thread = null
        startButton.text = "Start"
        runOnUiThread { gateView.text = "—"; gateView.setTextColor(Color.GRAY); readout.text = "Stopped" }
    }

    private fun loop() {
        val recorder = AndroidAudioIn()
        val suppressor = RnnoiseSuppressor()
        val pcm = ShortArray(CAPTURE_SAMPLES)
        val levels = FloatArray(FRAMES_PER_PACKET)
        var tick = 0
        try {
            while (running.get()) {
                if (recorder.read(pcm, CAPTURE_SAMPLES) <= 0) continue
                var sumSq = 0.0
                for (i in 0 until CAPTURE_SAMPLES) { val v = pcm[i].toDouble(); sumSq += v * v }
                val rms = sqrt(sumSq / CAPTURE_SAMPLES)
                val inputDb = if (rms < 1.0) -96.0 else 20.0 * log10(rms / 32768.0)

                for (h in 0 until FRAMES_PER_PACKET) {
                    val off = h * FRAME_SAMPLES_10MS
                    if (rnnoiseOn) suppressor.process(pcm, off, FRAME_SAMPLES_10MS)
                    levels[h] = detector.level(pcm, off, FRAME_SAMPLES_10MS)
                }
                val d = gate.update(levels)
                if (d.send) sent++

                if (tick++ % 3 == 0) {   // ~16 Hz UI
                    val open = d.send
                    val term = d.terminator
                    runOnUiThread {
                        gateView.text = if (open) (if (term) "TERM" else "OPEN") else "closed"
                        gateView.setTextColor(if (open) Color.parseColor("#4CAF50") else Color.GRAY)
                        readout.text = ("in: %+.0f dB   lvl: %.2f / %.2f\n" +
                            "gate: %s   sent: %d").format(
                            inputDb, levels[0], levels[1],
                            if (open) "OPEN" else "closed", sent)
                    }
                }
            }
        } catch (t: Throwable) {
            runOnUiThread { readout.text = "Error: ${t.message}" }
        } finally {
            recorder.close()
            suppressor.close()
        }
    }

    private fun dbToProgress(db: Float): Int =
        (((db - minDb) / (maxDb - minDb)) * 100f).toInt().coerceIn(0, 100)

    private fun progressToDb(p: Int): Float = minDb + (p / 100f) * (maxDb - minDb)

    override fun onDestroy() { super.onDestroy(); stop() }
}
