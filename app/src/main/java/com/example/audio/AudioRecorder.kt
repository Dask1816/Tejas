package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Real-time audio recorder capturing 16kHz, 16-bit PCM mono audio
 * directly compatible with Gemini Live API.
 */
class AudioRecorder(
    private val scope: CoroutineScope,
    private val onAudioChunk: (ByteArray) -> Unit,
    private val onAmplitudeChanged: (Float) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "TejasAudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording.get()) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            val err = "AudioRecord min buffer size error: $minBufferSize"
            Log.e(TAG, err)
            onError(err)
            return
        }

        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)
        Log.d(TAG, "Initializing AudioRecord: sampleRate=$SAMPLE_RATE, bufferSize=$bufferSize")

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                // Fallback to default MIC
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                val err = "Failed to initialize AudioRecord (state=${audioRecord?.state})"
                Log.e(TAG, err)
                onError(err)
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)
            Log.d(TAG, "AudioRecord started recording successfully")
        } catch (e: Exception) {
            val err = "Exception initializing AudioRecord: ${e.message}"
            Log.e(TAG, err, e)
            onError(err)
            return
        }

        recordingJob = scope.launch(Dispatchers.IO) {
            val chunkBuffer = ByteArray(2048) // 1024 samples @ 16kHz = ~64ms chunks
            while (isActive && isRecording.get()) {
                val read = audioRecord?.read(chunkBuffer, 0, chunkBuffer.size) ?: -1
                if (read > 0) {
                    val pcmData = chunkBuffer.copyOf(read)
                    onAudioChunk(pcmData)

                    // Amplitude calculation
                    val rms = calculateRms(pcmData)
                    scope.launch(Dispatchers.Main) {
                        onAmplitudeChanged(rms)
                    }
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord read error code: $read")
                }
            }
        }
    }

    fun stopRecording() {
        if (!isRecording.getAndSet(false)) return
        Log.d(TAG, "Stopping AudioRecord")
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        scope.launch(Dispatchers.Main) {
            onAmplitudeChanged(0f)
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
        return (rms / 12000.0).toFloat().coerceIn(0f, 1f)
    }

    fun isRecordingActive(): Boolean = isRecording.get()
}
