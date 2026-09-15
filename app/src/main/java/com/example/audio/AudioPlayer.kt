package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-latency PCM audio player using Android's native [AudioTrack].
 * Supports sequential queue playback, amplitude monitoring, immediate interruption,
 * and speaker diagnostic testing.
 */
class AudioPlayer(
    private val scope: CoroutineScope,
    private val onPlaybackStarted: () -> Unit = {},
    private val onPlaybackFinished: () -> Unit = {},
    private val onAmplitudeChanged: (Float) -> Unit = {}
) {
    companion object {
        private const val TAG = "TejasAudioPlayer"
        const val DEFAULT_SAMPLE_RATE = 24000 // Standard Gemini Live output
        const val FALLBACK_SAMPLE_RATE = 16000
    }

    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = DEFAULT_SAMPLE_RATE
    private var playbackChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var playbackJob: Job? = null
    private val isPlaying = AtomicBoolean(false)

    init {
        initAudioTrack(currentSampleRate)
        startPlaybackConsumer()
    }

    @Synchronized
    fun initAudioTrack(sampleRate: Int) {
        if (audioTrack != null && currentSampleRate == sampleRate) {
            return
        }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing previous AudioTrack", e)
        }

        currentSampleRate = sampleRate
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBufferSize * 4).coerceAtLeast(8192)

        Log.d(TAG, "Initializing AudioTrack: sampleRate=$sampleRate, bufferSize=$bufferSize")

        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }

        try {
            audioTrack?.play()
            Log.d(TAG, "AudioTrack started playing successfully (state=${audioTrack?.state})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack", e)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startPlaybackConsumer() {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val pcmData = playbackChannel.receive()
                    if (!isPlaying.getAndSet(true)) {
                        scope.launch(Dispatchers.Main) { onPlaybackStarted() }
                    }

                    // Write PCM data to AudioTrack
                    var bytesWritten = 0
                    val total = pcmData.size
                    val track = audioTrack ?: continue

                    // Calculate amplitude for visualizer
                    val rms = calculateRms(pcmData)
                    scope.launch(Dispatchers.Main) { onAmplitudeChanged(rms) }

                    while (bytesWritten < total && isActive) {
                        val written = track.write(pcmData, bytesWritten, total - bytesWritten)
                        if (written > 0) {
                            bytesWritten += written
                        } else {
                            Log.w(TAG, "AudioTrack write returned $written")
                            break
                        }
                    }

                    // Check if channel is empty to trigger finished state
                    if (playbackChannel.isEmpty) {
                        isPlaying.set(false)
                        scope.launch(Dispatchers.Main) {
                            onAmplitudeChanged(0f)
                            onPlaybackFinished()
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error during audio playback loop", e)
                    }
                }
            }
        }
    }

    /**
     * Enqueue a PCM 16-bit audio chunk for sequential playback.
     */
    fun enqueueAudio(pcmData: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        if (sampleRate != currentSampleRate) {
            initAudioTrack(sampleRate)
        }
        playbackChannel.trySend(pcmData)
    }

    /**
     * Immediately interrupts and stops playback, flushing all queued chunks.
     */
    fun stopAndClear() {
        Log.d(TAG, "stopAndClear called - interrupting audio")
        playbackJob?.cancel()
        // Drain current channel
        while (playbackChannel.tryReceive().isSuccess) {
            // drained
        }
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack", e)
        }
        isPlaying.set(false)
        startPlaybackConsumer()
        scope.launch(Dispatchers.Main) {
            onAmplitudeChanged(0f)
            onPlaybackFinished()
        }
    }

    /**
     * Diagnostic Speaker Test: Generates a pure 440 Hz tone for 1.0 second.
     */
    fun playSpeakerDiagnosticTone(onComplete: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Playing speaker diagnostic test (440Hz tone)")
            initAudioTrack(16000)
            val sampleRate = 16000
            val durationSec = 1.0
            val numSamples = (sampleRate * durationSec).toInt()
            val pcmData = ByteArray(numSamples * 2)

            for (i in 0 until numSamples) {
                val angle = 2.0 * PI * 440.0 * i / sampleRate
                val sampleVal = (sin(angle) * 26000).toInt().coerceIn(-32768, 32767)
                pcmData[i * 2] = (sampleVal and 0xFF).toByte()
                pcmData[i * 2 + 1] = ((sampleVal shr 8) and 0xFF).toByte()
            }

            audioTrack?.write(pcmData, 0, pcmData.size)
            Log.d(TAG, "Speaker diagnostic tone played successfully")
            scope.launch(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    private fun calculateRms(pcmData: ByteArray): Float {
        var sumSquares = 0.0
        val sampleCount = pcmData.size / 2
        if (sampleCount == 0) return 0f

        for (i in 0 until sampleCount) {
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += (sample * sample)
        }
        val mean = sumSquares / sampleCount
        val rms = sqrt(mean)
        // Normalize roughly between 0.0 and 1.0
        return (rms / 16384.0).toFloat().coerceIn(0f, 1f)
    }

    fun release() {
        playbackJob?.cancel()
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack", e)
        }
    }
}
