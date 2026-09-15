package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.actions.DeviceActionBridge
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
import com.example.gemini.GeminiLiveClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AssistantState {
    IDLE,
    CONNECTING,
    LISTENING,
    SPEAKING,
    ERROR
}

class TejasViewModel(application: Application) : AndroidViewModel(application) {

    private val _assistantState = MutableStateFlow(AssistantState.IDLE)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _statusMessage = MutableStateFlow("Tap microphone to start talking to Tejas")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _lastActionMessage = MutableStateFlow<String?>(null)
    val lastActionMessage: StateFlow<String?> = _lastActionMessage.asStateFlow()

    private val _isSpeakerTesting = MutableStateFlow(false)
    val isSpeakerTesting: StateFlow<Boolean> = _isSpeakerTesting.asStateFlow()

    private val _transcriptLogs = MutableStateFlow<List<String>>(emptyList())
    val transcriptLogs: StateFlow<List<String>> = _transcriptLogs.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val actionBridge = DeviceActionBridge(application.applicationContext)

    private val audioPlayer: AudioPlayer
    private val audioRecorder: AudioRecorder
    private val geminiClient: GeminiLiveClient

    init {
        audioPlayer = AudioPlayer(
            scope = viewModelScope,
            onPlaybackStarted = {
                _assistantState.value = AssistantState.SPEAKING
                _statusMessage.value = "Tejas is speaking..."
            },
            onPlaybackFinished = {
                if (_assistantState.value == AssistantState.SPEAKING) {
                    _assistantState.value = AssistantState.LISTENING
                    _statusMessage.value = "Listening to you... Speak naturally"
                }
            },
            onAmplitudeChanged = { level ->
                if (_assistantState.value == AssistantState.SPEAKING) {
                    _audioLevel.value = level
                }
            }
        )

        audioRecorder = AudioRecorder(
            scope = viewModelScope,
            onAudioChunk = { pcmChunk ->
                geminiClient.sendAudioChunk(pcmChunk)
            },
            onAmplitudeChanged = { level ->
                if (_assistantState.value == AssistantState.LISTENING) {
                    _audioLevel.value = level
                }
            },
            onError = { err ->
                _assistantState.value = AssistantState.ERROR
                _statusMessage.value = err
                addLog("Microphone error: $err")
            }
        )

        geminiClient = GeminiLiveClient(
            scope = viewModelScope,
            apiKey = BuildConfig.GEMINI_API_KEY,
            audioPlayer = audioPlayer,
            actionBridge = actionBridge,
            onStateChanged = { connState ->
                when (connState) {
                    GeminiLiveClient.ConnectionState.CONNECTING -> {
                        _assistantState.value = AssistantState.CONNECTING
                        _statusMessage.value = "Connecting to Gemini Live..."
                    }
                    GeminiLiveClient.ConnectionState.CONNECTED -> {
                        _assistantState.value = AssistantState.LISTENING
                        _statusMessage.value = "Listening... Tejas is ready"
                        audioRecorder.startRecording()
                        addLog("Live session connected with Gemini voice engine")
                    }
                    GeminiLiveClient.ConnectionState.DISCONNECTED -> {
                        _assistantState.value = AssistantState.IDLE
                        _statusMessage.value = "Session ended. Tap to speak again."
                        audioRecorder.stopRecording()
                    }
                    GeminiLiveClient.ConnectionState.ERROR -> {
                        _assistantState.value = AssistantState.ERROR
                        audioRecorder.stopRecording()
                    }
                }
            },
            onActionExecuted = { action, detail ->
                _lastActionMessage.value = "$action: $detail"
                addLog("Executed action: $action ($detail)")
            },
            onError = { err ->
                _assistantState.value = AssistantState.ERROR
                _statusMessage.value = err
                addLog("Error: $err")
            },
            onTextReceived = { textChunk ->
                _streamingText.value += textChunk
            },
            onTurnComplete = {
                val fullText = _streamingText.value.trim()
                if (fullText.isNotBlank()) {
                    addLog("Tejas: $fullText")
                    _streamingText.value = ""
                }
            },
            onInterrupted = {
                _streamingText.value = ""
                _assistantState.value = AssistantState.LISTENING
                _statusMessage.value = "Listening... Go ahead"
            }
        )
    }


    fun toggleVoiceSession() {
        when (_assistantState.value) {
            AssistantState.IDLE, AssistantState.ERROR -> {
                startSession()
            }
            AssistantState.CONNECTING, AssistantState.LISTENING, AssistantState.SPEAKING -> {
                stopSession()
            }
        }
    }

    private fun startSession() {
        _lastActionMessage.value = null
        _statusMessage.value = "Starting Tejas assistant..."
        geminiClient.connect()
    }

    fun stopSession() {
        audioRecorder.stopRecording()
        audioPlayer.stopAndClear()
        geminiClient.disconnect()
        _assistantState.value = AssistantState.IDLE
        _statusMessage.value = "Tejas is idle. Tap to start."
        _audioLevel.value = 0f
    }

    /**
     * Immediate interruption: cancels current audio playback and immediately
     * opens the microphone to listen for the user's new request.
     */
    fun interrupt() {
        if (_assistantState.value == AssistantState.SPEAKING) {
            addLog("Interrupted: stopping Tejas voice")
            audioPlayer.stopAndClear()
            _assistantState.value = AssistantState.LISTENING
            _statusMessage.value = "Listening... Go ahead"
        }
    }

    /**
     * Speaker Diagnostic Test: plays a 440 Hz tone through native AudioTrack
     * to verify speaker hardware and audio output path.
     */
    fun testSpeaker() {
        _isSpeakerTesting.value = true
        _statusMessage.value = "Testing speaker output (440Hz tone)..."
        addLog("Running speaker diagnostic test...")
        audioPlayer.playSpeakerDiagnosticTone {
            _isSpeakerTesting.value = false
            _statusMessage.value = "Speaker test completed successfully!"
            addLog("Speaker diagnostic test tone completed")
        }
    }

    /**
     * Send text prompt with real audio response from Gemini
     */
    fun sendTextPrompt(prompt: String) {
        if (prompt.isBlank()) return
        addLog("User: $prompt")
        _assistantState.value = AssistantState.CONNECTING
        _statusMessage.value = "Tejas is thinking..."

        viewModelScope.launch {
            val responseText = geminiClient.sendTextMessageWithAudioResponse(prompt)
            addLog("Tejas: $responseText")
        }
    }

    private fun addLog(message: String) {
        val current = _transcriptLogs.value.toMutableList()
        current.add(0, message)
        if (current.size > 20) {
            current.removeAt(current.size - 1)
        }
        _transcriptLogs.value = current
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
        audioPlayer.release()
        geminiClient.disconnect()
    }
}
