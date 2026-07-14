package me.danielstiner.dumble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Local VAD gate tuner (no server). Runs the real transmit chain
 * (mic -> RNNoise -> EnergyVadDetector -> TransmitGate) and shows live whether the gate opens.
 * A slider tunes the absolute open threshold; a dB-history strip visualizes recent input; a
 * route control cycles Earpiece/Speaker/Bluetooth so audio-route switching (incl. BT) can be
 * tested. No playback (avoids feedback).
 */
class VadDebugActivity : AppCompatActivity() {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private val minDb = -90f
    private val maxDb = -30f
    @Volatile private var absOpenDb = -55f
    private val relMinDb = 3f
    private val relMaxDb = 20f
    @Volatile private var relOpenDb = 9f      // dB above the noise floor to open (relative gate)
    @Volatile private var rnnoiseOn = true

    private val detector = EnergyVadDetector()
    private val gate = TransmitGate()
    private val am by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private lateinit var history: LevelHistoryView
    private lateinit var readout: TextView
    private lateinit var gateView: TextView
    private lateinit var thresholdText: TextView
    private lateinit var relText: TextView
    private lateinit var rnnoiseButton: Button
    private lateinit var routeButton: Button

    @Volatile private var sent = 0L
    private var askedPermission = false

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) start() else readout.text = "RECORD_AUDIO permission required" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 56, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        layout.addView(TextView(this).apply {
            text = "VAD Gate Tuner"
            textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        })

        history = LevelHistoryView(this)
        layout.addView(history, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 480
        ).apply { setMargins(0, 0, 0, 8) })

        layout.addView(TextView(this).apply {
            text = "cyan=rel target · orange=noise floor · yellow=abs gate · tick=raw · green bars=open"
            textSize = 11f; setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, 12)
        })

        gateView = TextView(this).apply {
            text = "—"
            textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 12)
        }
        layout.addView(gateView)

        readout = TextView(this).apply {
            text = "Stopped"
            textSize = 14f; setTextColor(Color.LTGRAY)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 24)
        }
        layout.addView(readout)

        thresholdText = TextView(this).apply {
            text = thresholdLabel()
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
                    thresholdText.text = thresholdLabel()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 24) }
        })

        relText = TextView(this).apply {
            text = relLabel()
            setTextColor(Color.WHITE)
        }
        layout.addView(relText)

        layout.addView(SeekBar(this).apply {
            max = 100
            progress = (((relOpenDb - relMinDb) / (relMaxDb - relMinDb)) * 100f).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    relOpenDb = relMinDb + (p / 100f) * (relMaxDb - relMinDb)
                    detector.marginDb = relOpenDb / gate.openLevel
                    relText.text = relLabel()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 32) }
        })

        rnnoiseButton = Button(this).apply {
            text = "RNNoise: ON"
            setOnClickListener {
                rnnoiseOn = !rnnoiseOn
                text = if (rnnoiseOn) "RNNoise: ON" else "RNNoise: OFF"
                thresholdText.text = thresholdLabel()
            }
        }
        layout.addView(rnnoiseButton)

        routeButton = Button(this).apply {
            text = "Route: default"
            setOnClickListener { cycleRoute(); refreshRoute() }
        }
        layout.addView(routeButton)

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        when {
            hasMic() -> start()
            !askedPermission -> { askedPermission = true; requestPermission.launch(Manifest.permission.RECORD_AUDIO) }
            else -> readout.text = "RECORD_AUDIO permission required"
        }
    }

    override fun onPause() {
        super.onPause()
        stop()
    }

    private fun hasMic() = ActivityCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        if (running.get()) return
        running.set(true)
        sent = 0
        detector.absOpenDb = absOpenDb
        detector.marginDb = relOpenDb / gate.openLevel
        gate.reset()
        am.mode = AudioManager.MODE_IN_COMMUNICATION   // enable comm-device routing (speaker/BT)
        refreshRoute()
        thread = Thread({ loop() }, "vad-debug").apply { isDaemon = true; start() }
    }

    private fun stop() {
        if (!running.getAndSet(false)) return
        thread?.join(500); thread = null
        runCatching { am.clearCommunicationDevice(); am.mode = AudioManager.MODE_NORMAL }
        runOnUiThread { gateView.text = "—"; gateView.setTextColor(Color.GRAY) }
    }

    /** Cycle through the currently-available communication devices (earpiece/speaker/BT/wired). */
    private fun cycleRoute() {
        val devices = am.availableCommunicationDevices
        if (devices.isEmpty()) return
        val currentId = am.communicationDevice?.id
        val idx = devices.indexOfFirst { it.id == currentId }
        am.setCommunicationDevice(devices[(idx + 1) % devices.size])
    }

    private fun refreshRoute() {
        routeButton.text = "Route: " + (am.communicationDevice?.let { deviceName(it.type) } ?: "default")
    }

    private fun deviceName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        else -> "type $type"
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
                val preDb = rmsDbOf(pcm)                        // raw mic

                for (h in 0 until FRAMES_PER_PACKET) {
                    val off = h * FRAME_SAMPLES_10MS
                    if (rnnoiseOn) suppressor.process(pcm, off, FRAME_SAMPLES_10MS)
                    levels[h] = detector.level(pcm, off, FRAME_SAMPLES_10MS)
                }
                val postDb = rmsDbOf(pcm)                       // after RNNoise (what the VAD sees)
                val d = gate.update(levels)
                if (d.send) sent++

                if (tick++ % 3 == 0) {   // ~16 Hz UI
                    val open = d.send
                    val term = d.terminator
                    val preF = preDb.toFloat(); val postF = postDb.toFloat()
                    val floorF = detector.noiseFloorDb
                    val floorDb = floorF.toDouble()
                    val relTargetDb = floorF + gate.openLevel * detector.marginDb  // floor + open margin
                    val snr = postDb - floorDb                     // level relative to the noise floor
                    val postRms = 10.0.pow(postDb / 20.0)          // linear RMS (0..1)
                    runOnUiThread {
                        history.push(preF, postF, open, absOpenDb, floorF, relTargetDb)
                        gateView.text = if (open) (if (term) "TERM" else "OPEN") else "closed"
                        gateView.setTextColor(if (open) OPEN_GREEN else Color.GRAY)
                        readout.text = ("in %+.0f→%+.0f dBFS   %.4f rms\n" +
                            "SNR %+.0f dB (floor %+.0f)   lvl %.2f/%.2f   sent %d").format(
                            preDb, postDb, postRms, snr, floorDb, levels[0], levels[1], sent)
                        refreshRoute()   // updates Route button (reflects BT connect/disconnect)
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

    private fun thresholdLabel() =
        "Open threshold: %.0f dBFS   (detect on %s)".format(absOpenDb, if (rnnoiseOn) "denoised" else "raw")

    private fun relLabel() =
        "Relative threshold: %.0f dB above floor".format(relOpenDb)

    private fun rmsDbOf(pcm: ShortArray): Double {
        var s = 0.0
        for (i in 0 until CAPTURE_SAMPLES) { val v = pcm[i].toDouble(); s += v * v }
        val rms = sqrt(s / CAPTURE_SAMPLES)
        return if (rms < 1.0) -96.0 else 20.0 * log10(rms / 32768.0)
    }

    private fun dbToProgress(db: Float): Int =
        (((db - minDb) / (maxDb - minDb)) * 100f).toInt().coerceIn(0, 100)

    private fun progressToDb(p: Int): Float = minDb + (p / 100f) * (maxDb - minDb)

    override fun onDestroy() { super.onDestroy(); stop() }

    companion object { val OPEN_GREEN = Color.parseColor("#4CAF50") }
}

/** Scrolling strip of recent input dB. Bars are green when the gate was open, gray otherwise;
 *  a yellow line marks the current open threshold. */
class LevelHistoryView(context: Context) : View(context) {
    private val cap = 128
    private val post = FloatArray(cap) { FLOOR }
    private val pre = FloatArray(cap) { FLOOR }
    private val floor = FloatArray(cap) { FLOOR }
    private val relTarget = FloatArray(cap) { FLOOR }
    private val open = BooleanArray(cap)
    private var head = 0
    private var thresholdDb = -55f
    private val bar = Paint()
    private val preTick = Paint().apply { color = Color.parseColor("#BDBDBD") }        // raw (pre-RNNoise)
    private val absLine = Paint().apply { color = Color.parseColor("#FFEB3B"); strokeWidth = 3f }   // abs gate
    private val floorLine = Paint().apply { color = Color.parseColor("#FF9800"); strokeWidth = 3f }  // noise floor
    private val relLine = Paint().apply { color = Color.parseColor("#00E5FF"); strokeWidth = 3f }    // rel target
    private val grid = Paint().apply { color = Color.parseColor("#3A3A3A"); strokeWidth = 1f }
    private val gridLabel = Paint().apply { color = Color.parseColor("#888888"); textSize = 22f }

    fun push(preDb: Float, postDb: Float, isOpen: Boolean, threshold: Float,
             floorDb: Float, relTargetDb: Float) {
        pre[head] = preDb; post[head] = postDb; open[head] = isOpen; thresholdDb = threshold
        floor[head] = floorDb; relTarget[head] = relTargetDb
        head = (head + 1) % cap
        invalidate()
    }

    private fun yOf(db: Float, h: Float) =
        h - ((db.coerceIn(FLOOR, CEIL) - FLOOR) / (CEIL - FLOOR)) * h

    private fun trace(c: Canvas, data: FloatArray, bw: Float, h: Float, paint: Paint) {
        for (i in 0 until cap - 1) {
            val a = (head + i) % cap; val b = (head + i + 1) % cap
            c.drawLine(i * bw + bw / 2f, yOf(data[a], h), (i + 1) * bw + bw / 2f, yOf(data[b], h), paint)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Color.parseColor("#1E1E1E"))
        for (g in intArrayOf(-20, -40, -60, -80)) {   // dBFS gridlines
            val gy = yOf(g.toFloat(), h)
            c.drawLine(0f, gy, w, gy, grid)
            c.drawText("$g", 6f, gy - 4f, gridLabel)
        }
        val bw = w / cap
        for (i in 0 until cap) {
            val idx = (head + i) % cap                     // oldest -> newest
            val x0 = i * bw; val x1 = (i + 1) * bw - 1f
            val postY = yOf(post[idx], h)
            bar.color = if (open[idx]) Color.parseColor("#4CAF50") else Color.parseColor("#5A5A5A")
            c.drawRect(x0, postY, x1, h, bar)
            val preY = yOf(pre[idx], h)
            c.drawRect(x0, preY, x1, preY + 4f, preTick)   // raw pre-RNNoise level
        }
        trace(c, floor, bw, h, floorLine)                  // adaptive noise floor (orange)
        trace(c, relTarget, bw, h, relLine)                // relative open target = floor + margin (cyan)
        val ty = yOf(thresholdDb, h)                       // absolute gate (yellow, horizontal)
        c.drawLine(0f, ty, w, ty, absLine)
    }

    companion object { const val FLOOR = -90f; const val CEIL = 0f }
}
