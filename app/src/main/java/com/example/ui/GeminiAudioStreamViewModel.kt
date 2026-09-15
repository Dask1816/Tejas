package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.actions.DeviceActionBridge
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
import com.example.gemini.GeminiLiveClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Message entry representing transcribed user input, incoming streaming AI response,
 * or executed tool action.
 */
data class TranscriptEntry(
    val id: String = UUID.randomUUID().toString(),
    val sender: SenderType,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

enum class SenderType {
    USER,
    ASSISTANT,
    ACTION,
    SYSTEM
}

enum class StreamConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class VoiceAssistantState {
    IDLE,
    CONNECTING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

/**
 * Immutable UI State representing real-time audio input streaming to Gemini
 * and incoming real-time text & audio response.
 */
data class GeminiAudioStreamUiState(
    val connectionState: StreamConnectionState = StreamConnectionState.DISCONNECTED,
    val voiceState: VoiceAssistantState = VoiceAssistantState.IDLE,
    val micAudioLevel: Float = 0f,
    val speakerAudioLevel: Float = 0f,
    val currentStreamingResponse: String = "",
    val transcripts: List<TranscriptEntry> = emptyList(),
    val statusDescription: String = "Tap microphone to stream audio to Gemini",
    val lastExecutedAction: String? = null,
    val errorMessage: String? = null,
    val isAudioInputStreaming: Boolean = false,
    val isAudioOutputPlaying: Boolean = false,
    val isSpeakerTesting: Boolean = false
)

/**
 * ViewModel that handles streaming audio input to the Gemini AI SDK for real-time processing
 * and manages the incoming text/audio response.
 *
 * Architecture:
 * 1. Audio Input Pipeline: AudioRecorder captures 16kHz 16-bit Mono PCM audio in real-time,
 *    computes live RMS amplitude for visualizer feedback, and streams chunks to GeminiLiveClient.
 * 2. Gemini Real-time AI Stream: GeminiLiveClient handles bi-directional WebSocket communication
 *    using model `gemini-2.5-flash-native-audio-preview-12-2025` with AUDIO response modalities.
 * 3. Incoming Text Response Management: Accumulates incoming streaming text tokens from Gemini turns,
 *    exposing them reactively via StateFlow for live subtitles and conversation logs.
 * 4. Incoming Audio Response Management: Decodes incoming native PCM audio chunks (24kHz / 16kHz)
 *    into AudioPlayer's jitter buffer, playing low-latency speech through device speaker.
 * 5. Interruption Handling: Instantly cancels playback and flushes audio buffers when user speaks
 *    or triggers interrupt, immediately resuming audio input listening.
 */
open class GeminiAudioStreamViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "GeminiAudioStreamVM"
    }

    private val _uiState = MutableStateFlow(GeminiAudioStreamUiState())
    val uiState: StateFlow<GeminiAudioStreamUiState> = _uiState.asStateFlow()

    // Granular StateFlows for specific UI observers
    private val _streamingResponseText = MutableStateFlow("")
    val streamingResponseText: StateFlow<String> = _streamingResponseText.asStateFlow()

    private val _inputAudioLevel = MutableStateFlow(0f)
    val inputAudioLevel: StateFlow<Float> = _inputAudioLevel.asStateFlow()

    private val _outputAudioLevel = MutableStateFlow(0f)
    val outputAudioLevel: StateFlow<Float> = _outputAudioLevel.asStateFlow()

    private val actionBridge = DeviceActionBridge(application.applicationContext)

    protected val audioPlayer: AudioPlayer
    protected val audioRecorder: AudioRecorder
    protected val geminiClient: GeminiLiveClient

    init {
        // 1. Audio Output Player for incoming Gemini audio
        audioPlayer = AudioPlayer(
            scope = viewModelScope,
            onPlaybackStarted = {
                _uiState.update {
                    it.copy(
                        voiceState = VoiceAssistantState.SPEAKING,
                        isAudioOutputPlaying = true,
                        statusDescription = "Gemini is speaking..."
                    )
                }
            },
            onPlaybackFinished = {
                _uiState.update { current ->
                    if (current.voiceState == VoiceAssistantState.SPEAKING) {
                        current.copy(
                            voiceState = VoiceAssistantState.LISTENING,
                            isAudioOutputPlaying = false,
                            speakerAudioLevel = 0f,
                            statusDescription = "Listening... Speak naturally"
                        )
                    } else {
                        current.copy(isAudioOutputPlaying = false, speakerAudioLevel = 0f)
                    }
                }
                _outputAudioLevel.value = 0f
            },
            onAmplitudeChanged = { level ->
                _outputAudioLevel.value = level
                _uiState.update { it.copy(speakerAudioLevel = level) }
            }
        )

        // 2. Audio Input Recorder for streaming microphone audio to Gemini
        audioRecorder = AudioRecorder(
            scope = viewModelScope,
            onAudioChunk = { pcmChunk ->
                // Stream audio chunk directly to Gemini AI in real-time
                sendAudioChunkToGemini(pcmChunk)
            },
            onAmplitudeChanged = { level ->
                _inputAudioLevel.value = level
                _uiState.update { it.copy(micAudioLevel = level) }
            },
            onError = { error ->
                Log.e(TAG, "AudioRecorder error: $error")
                _uiState.update {
                    it.copy(
                        voiceState = VoiceAssistantState.ERROR,
                        errorMessage = error,
                        statusDescription = "Microphone error: $error"
                    )
                }
                appendTranscript(SenderType.SYSTEM, "Microphone error: $error")
            }
        )

        // 3. Gemini Live Real-time Client
        geminiClient = GeminiLiveClient(
            scope = viewModelScope,
            apiKey = BuildConfig.GEMINI_API_KEY,
            audioPlayer = audioPlayer,
            actionBridge = actionBridge,
            onStateChanged = { connState ->
                when (connState) {
                    GeminiLiveClient.ConnectionState.CONNECTING -> {
                        _uiState.update {
                            it.copy(
                                connectionState = StreamConnectionState.CONNECTING,
                                voiceState = VoiceAssistantState.CONNECTING,
                                statusDescription = "Connecting to Gemini Live..."
                            )
                        }
                    }
                    GeminiLiveClient.ConnectionState.CONNECTED -> {
                        _uiState.update {
                            it.copy(
                                connectionState = StreamConnectionState.CONNECTED,
                                voiceState = VoiceAssistantState.LISTENING,
                                isAudioInputStreaming = true,
                                statusDescription = "Streaming audio to Gemini. Speak naturally..."
                            )
                        }
                        audioRecorder.startRecording()
                        appendTranscript(SenderType.SYSTEM, "Connected to Gemini Live voice engine")
                    }
                    GeminiLiveClient.ConnectionState.DISCONNECTED -> {
                        audioRecorder.stopRecording()
                        _uiState.update {
                            it.copy(
                                connectionState = StreamConnectionState.DISCONNECTED,
                                voiceState = VoiceAssistantState.IDLE,
                                isAudioInputStreaming = false,
                                micAudioLevel = 0f,
                                speakerAudioLevel = 0f,
                                statusDescription = "Session ended. Tap to speak again."
                            )
                        }
                    }
                    GeminiLiveClient.ConnectionState.ERROR -> {
                        audioRecorder.stopRecording()
                        _uiState.update {
                            it.copy(
                                connectionState = StreamConnectionState.ERROR,
                                voiceState = VoiceAssistantState.ERROR,
                                isAudioInputStreaming = false
                            )
                        }
                    }
                }
            },
            onActionExecuted = { actionName, details ->
                _uiState.update {
                    it.copy(lastExecutedAction = "$actionName: $details")
                }
                appendTranscript(SenderType.ACTION, "Executed $actionName: $details")
            },
            onError = { error ->
                Log.e(TAG, "Gemini client error: $error")
                _uiState.update {
                    it.copy(
                        voiceState = VoiceAssistantState.ERROR,
                        errorMessage = error,
                        statusDescription = error
                    )
                }
                appendTranscript(SenderType.SYSTEM, "Error: $error")
            },
            onTextReceived = { textChunk ->
                // Accumulate incoming streaming text response in real time
                handleIncomingStreamingText(textChunk)
            },
            onTurnComplete = {
                // Gemini turn completed
                finalizeStreamingTurn()
            },
            onInterrupted = {
                // Interruption signaled by server
                handleServerInterruption()
            }
        )
    }

    /**
     * Streams real-time audio chunk to Gemini AI.
     */
    fun sendAudioChunkToGemini(pcmChunk: ByteArray) {
        if (geminiClient.isSessionConnected()) {
            geminiClient.sendAudioChunk(pcmChunk)
        }
    }

    /**
     * Start streaming audio input to Gemini AI.
     */
    fun startStreamingAudio() {
        _uiState.update {
            it.copy(
                errorMessage = null,
                lastExecutedAction = null,
                statusDescription = "Connecting to Gemini Live..."
            )
        }
        geminiClient.connect()
    }

    /**
     * Stop streaming audio input and end the active Gemini session.
     */
    fun stopStreamingAudio() {
        audioRecorder.stopRecording()
        audioPlayer.stopAndClear()
        geminiClient.disconnect()
        finalizeStreamingTurn()
        _uiState.update {
            it.copy(
                connectionState = StreamConnectionState.DISCONNECTED,
                voiceState = VoiceAssistantState.IDLE,
                isAudioInputStreaming = false,
                isAudioOutputPlaying = false,
                micAudioLevel = 0f,
                speakerAudioLevel = 0f,
                statusDescription = "Session ended. Tap to speak again."
            )
        }
        _inputAudioLevel.value = 0f
        _outputAudioLevel.value = 0f
    }

    /**
     * Toggle audio input streaming session.
     */
    fun toggleStreaming() {
        val currentState = _uiState.value.voiceState
        if (currentState == VoiceAssistantState.IDLE || currentState == VoiceAssistantState.ERROR) {
            startStreamingAudio()
        } else {
            stopStreamingAudio()
        }
    }

    /**
     * Handle incoming streaming text token/chunk from Gemini.
     */
    private fun handleIncomingStreamingText(textChunk: String) {
        _streamingResponseText.update { it + textChunk }
        _uiState.update { current ->
            current.copy(
                currentStreamingResponse = _streamingResponseText.value,
                voiceState = if (current.isAudioOutputPlaying) VoiceAssistantState.SPEAKING else VoiceAssistantState.PROCESSING
            )
        }
    }

    /**
     * Complete a model turn: commit accumulated streaming text to transcripts list.
     */
    private fun finalizeStreamingTurn() {
        val accumulatedText = _streamingResponseText.value.trim()
        if (accumulatedText.isNotBlank()) {
            appendTranscript(SenderType.ASSISTANT, accumulatedText)
            _streamingResponseText.value = ""
            _uiState.update { it.copy(currentStreamingResponse = "") }
        }
    }

    /**
     * Interruption handling: stops incoming audio playback immediately and
     * resumes listening mode for prompt user interaction.
     */
    fun interruptAssistant() {
        Log.d(TAG, "User interrupted assistant audio playback")
        audioPlayer.stopAndClear()
        finalizeStreamingTurn()
        _uiState.update {
            it.copy(
                voiceState = VoiceAssistantState.LISTENING,
                isAudioOutputPlaying = false,
                speakerAudioLevel = 0f,
                statusDescription = "Listening... Go ahead"
            )
        }
        _outputAudioLevel.value = 0f
        appendTranscript(SenderType.SYSTEM, "Interrupted playback")
    }

    private fun handleServerInterruption() {
        Log.d(TAG, "Server signaled speech interruption")
        audioPlayer.stopAndClear()
        finalizeStreamingTurn()
        _uiState.update {
            it.copy(
                voiceState = VoiceAssistantState.LISTENING,
                isAudioOutputPlaying = false,
                speakerAudioLevel = 0f,
                statusDescription = "Listening... Go ahead"
            )
        }
        _outputAudioLevel.value = 0f
    }

    /**
     * Process direct text prompt with real-time audio and text streaming response from Gemini.
     */
    fun sendTextPrompt(prompt: String) {
        if (prompt.isBlank()) return
        appendTranscript(SenderType.USER, prompt)
        _uiState.update {
            it.copy(
                voiceState = VoiceAssistantState.CONNECTING,
                statusDescription = "Gemini is processing..."
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val responseText = geminiClient.sendTextMessageWithAudioResponse(prompt)
            if (_streamingResponseText.value.isBlank()) {
                appendTranscript(SenderType.ASSISTANT, responseText)
            } else {
                finalizeStreamingTurn()
            }
        }
    }

    /**
     * Diagnostic test playing 440 Hz tone through native AudioTrack
     * to confirm audio hardware integrity.
     */
    fun testSpeaker() {
        _uiState.update {
            it.copy(
                isSpeakerTesting = true,
                statusDescription = "Testing speaker output (440Hz tone)..."
            )
        }
        audioPlayer.playSpeakerDiagnosticTone {
            _uiState.update {
                it.copy(
                    isSpeakerTesting = false,
                    statusDescription = "Speaker diagnostic test completed successfully!"
                )
            }
            appendTranscript(SenderType.SYSTEM, "Speaker diagnostic completed")
        }
    }

    /**
     * Appends a message to the transcript log.
     */
    protected fun appendTranscript(sender: SenderType, text: String) {
        val entry = TranscriptEntry(sender = sender, text = text)
        _uiState.update { current ->
            val updated = current.transcripts.toMutableList()
            updated.add(0, entry)
            if (updated.size > 50) {
                updated.removeAt(updated.size - 1)
            }
            current.copy(transcripts = updated)
        }
    }

    fun clearTranscripts() {
        _uiState.update { it.copy(transcripts = emptyList()) }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
        audioPlayer.release()
        geminiClient.disconnect()
    }
}
