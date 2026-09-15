package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ui.GeminiAudioStreamViewModel
import com.example.ui.StreamConnectionState
import com.example.ui.VoiceAssistantState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiAudioStreamViewModelTest {

    private lateinit var application: Application
    private lateinit var viewModel: GeminiAudioStreamViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        viewModel = GeminiAudioStreamViewModel(application)
    }

    @Test
    fun testInitialUiState() {
        val state = viewModel.uiState.value
        assertEquals(StreamConnectionState.DISCONNECTED, state.connectionState)
        assertEquals(VoiceAssistantState.IDLE, state.voiceState)
        assertEquals(0f, state.micAudioLevel, 0.001f)
        assertEquals(0f, state.speakerAudioLevel, 0.001f)
        assertEquals("", state.currentStreamingResponse)
        assertTrue(state.transcripts.isEmpty())
        assertNotNull(state.statusDescription)
    }

    @Test
    fun testAudioChunkStreamingSafeCall() {
        // Feed mock 16kHz PCM audio chunk
        val samplePcmChunk = ByteArray(640) { 0 }
        viewModel.sendAudioChunkToGemini(samplePcmChunk)
        // Verify state remains stable and does not throw
        assertNotNull(viewModel.uiState.value)
    }

    @Test
    fun testInterruptAssistant() {
        viewModel.interruptAssistant()
        val state = viewModel.uiState.value
        assertEquals(VoiceAssistantState.LISTENING, state.voiceState)
        assertEquals(0f, state.speakerAudioLevel, 0.001f)
    }

    @Test
    fun testClearTranscripts() {
        viewModel.clearTranscripts()
        assertTrue(viewModel.uiState.value.transcripts.isEmpty())
    }
}
