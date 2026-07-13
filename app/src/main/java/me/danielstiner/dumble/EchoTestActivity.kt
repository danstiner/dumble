package me.danielstiner.dumble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
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
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

class EchoTestActivity : AppCompatActivity() {

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    
    private val isRunning = AtomicBoolean(false)
    private var loopbackThread: Thread? = null

    private lateinit var statusView: TextView
    private lateinit var volumeBar: View
    private lateinit var startButton: Button
    private lateinit var aecToggle: Button
    private lateinit var delayText: TextView
    private lateinit var delaySeekBar: SeekBar

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startTest()
        } else {
            statusView.text = "Permission RECORD_AUDIO required"
        }
    }
    
    private var isAecEnabled = true
    private var loopbackDelayMs = 250

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        statusView = TextView(this).apply {
            text = "Echo Cancellation Test"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(statusView)

        // Volume indicator
        val volumeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                40
            ).apply { setMargins(0, 0, 0, 64) }
            setBackgroundColor(Color.DKGRAY)
        }
        volumeBar = View(this).apply {
            setBackgroundColor(Color.GREEN)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        volumeContainer.addView(volumeBar)
        layout.addView(volumeContainer)

        // Delay control
        delayText = TextView(this).apply {
            text = "Loopback Delay: 250ms"
            setTextColor(Color.WHITE)
        }
        layout.addView(delayText)

        delaySeekBar = SeekBar(this).apply {
            max = 2000 // 2 seconds max delay
            progress = 250
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 64) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    loopbackDelayMs = progress
                    delayText.text = "Loopback Delay: ${progress}ms"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(delaySeekBar)

        startButton = Button(this).apply {
            text = "Start Loopback"
            setOnClickListener {
                if (isRunning.get()) {
                    stopTest()
                } else {
                    if (ActivityCompat.checkSelfPermission(this@EchoTestActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        startTest()
                    }
                }
            }
        }
        layout.addView(startButton)

        aecToggle = Button(this).apply {
            text = "AEC: ON"
            setOnClickListener {
                isAecEnabled = !isAecEnabled
                text = if (isAecEnabled) "AEC: ON" else "AEC: OFF"
                if (isRunning.get()) {
                    stopTest()
                    if (ActivityCompat.checkSelfPermission(this@EchoTestActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startTest()
                    } else {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
        layout.addView(aecToggle)

        setContentView(layout)
    }

    private fun startTest() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        val sampleRate = 16000 
        val channelConfigRec = AudioFormat.CHANNEL_IN_MONO
        val channelConfigPlay = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigRec, audioFormat) * 2

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            // If AEC is ON, use VOICE_COMMUNICATION to request system-level processing.
            // If AEC is OFF, use MIC to get a rawer, unprocessed stream.
            val audioSource = if (isAecEnabled) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            }

            audioRecord = AudioRecord(
                audioSource,
                sampleRate,
                channelConfigRec,
                audioFormat,
                bufferSize
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (isAecEnabled) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfigPlay)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (AcousticEchoCanceler.isAvailable() && isAecEnabled) {
                echoCanceler = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                echoCanceler?.enabled = true
            }

            isRunning.set(true)
            startButton.text = "Stop Loopback"
            
            loopbackThread = Thread {
                val buffer = ShortArray(bufferSize / 2)
                val delayQueue = LinkedList<ShortArray>()
                
                try {
                    audioRecord?.startRecording()
                    audioTrack?.play()

                    while (isRunning.get()) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            val audioData = buffer.copyOf(read)
                            
                            // Calculate volume for indicator
                            var sum = 0.0
                            for (s in audioData) {
                                sum += s * s
                            }
                            val rms = sqrt(sum / read)
                            val volumeLevel = (rms / 32768.0).coerceIn(0.0, 1.0)
                            
                            runOnUiThread {
                                val params = volumeBar.layoutParams as LinearLayout.LayoutParams
                                params.width = (volumeLevel * (volumeBar.parent as View).width).toInt()
                                volumeBar.layoutParams = params
                            }

                            // Handle Delay
                            delayQueue.add(audioData)
                            val samplesInDelay = (sampleRate * loopbackDelayMs) / 1000
                            
                            var currentBufferedSamples = 0
                            for (b in delayQueue) currentBufferedSamples += b.size
                            
                            while (currentBufferedSamples > samplesInDelay && delayQueue.isNotEmpty()) {
                                val outBuffer = delayQueue.removeFirst()
                                audioTrack?.write(outBuffer, 0, outBuffer.size)
                                currentBufferedSamples -= outBuffer.size
                            }
                        }
                    }

                    audioRecord?.stop()
                    audioTrack?.stop()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    audioRecord?.release()
                    audioTrack?.release()
                    echoCanceler?.release()
                    audioRecord = null
                    audioTrack = null
                    echoCanceler = null
                    runOnUiThread {
                        val params = volumeBar.layoutParams as LinearLayout.LayoutParams
                        params.width = 0
                        volumeBar.layoutParams = params
                    }
                    audioManager.mode = AudioManager.MODE_NORMAL
                }
            }
            loopbackThread?.start()
            
        } catch (e: Exception) {
            statusView.text = "Error: ${e.message}"
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun stopTest() {
        isRunning.set(false)
        startButton.text = "Start Loopback"
        loopbackThread?.join(1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTest()
    }
}

