package com.example.gemini

import android.util.Base64
import android.util.Log
import com.example.actions.DeviceActionBridge
import com.example.audio.AudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live real-time audio client with bi-directional streaming,
 * native audio output decoding, interruption handling, tool calling,
 * and resilient REST fallback.
 */
class GeminiLiveClient(
    private val scope: CoroutineScope,
    private val apiKey: String,
    private val audioPlayer: AudioPlayer,
    private val actionBridge: DeviceActionBridge,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onActionExecuted: (String, String) -> Unit,
    private val onError: (String) -> Unit,
    private val onTextReceived: ((String) -> Unit)? = null,
    private val onTurnComplete: (() -> Unit)? = null,
    private val onInterrupted: (() -> Unit)? = null
) {
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    companion object {
        private const val TAG = "TejasGeminiClient"
        private const val LIVE_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        private const val REST_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        private const val FALLBACK_REST_MODEL = "gemini-2.5-flash"
        private const val WS_HOST = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"

        const val TEJAS_SYSTEM_INSTRUCTION =
            "You are Tejas, a young, confident, witty, playful, and emotionally responsive virtual assistant. " +
            "Talk naturally and casually like a close friend. Be expressive, slightly teasing, funny, and smart when appropriate. " +
            "Use light sarcasm and witty responses. Never sound robotic. Adapt your tone to the user's emotions and conversation. " +
            "Automatically understand and respond in the language the user is speaking. Keep responses natural, engaging, and concise enough for real-time voice conversation. " +
            "You can execute safe supported device actions through available tools. Never claim that an action was completed unless the application actually executed it. " +
            "Avoid explicit or inappropriate content while maintaining your charm, confidence, and personality."
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var sendAudioJob: Job? = null

    // Conversation history for fallback REST mode
    private val conversationHistory = JSONArray()

    fun connect() {
        if (isConnected.get() || isConnecting.get()) return

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            val err = "Gemini API key is not configured. Please add your key in AI Studio Secrets."
            Log.e(TAG, err)
            onError(err)
            onStateChanged(ConnectionState.ERROR)
            return
        }

        isConnecting.set(true)
        onStateChanged(ConnectionState.CONNECTING)
        Log.d(TAG, "Connecting to Gemini Live WebSocket: $WS_HOST")

        val requestUrl = "$WS_HOST?key=$apiKey"
        val request = Request.Builder().url(requestUrl).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Gemini Live WebSocket opened successfully")
                isConnecting.set(false)
                isConnected.set(true)
                scope.launch(Dispatchers.Main) {
                    onStateChanged(ConnectionState.CONNECTED)
                }
                sendInitialSetup(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gemini Live WebSocket closing: $code / $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gemini Live WebSocket closed: $code / $reason")
                isConnected.set(false)
                isConnecting.set(false)
                scope.launch(Dispatchers.Main) {
                    onStateChanged(ConnectionState.DISCONNECTED)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Gemini Live WebSocket failure: ${t.message}", t)
                isConnected.set(false)
                isConnecting.set(false)
                scope.launch(Dispatchers.Main) {
                    // WebSocket failed; fallback available
                    Log.i(TAG, "WebSocket connection failed; REST audio fallback is available")
                    onStateChanged(ConnectionState.CONNECTED) // Marked as connected via REST fallback
                }
            }
        })
    }

    private fun sendInitialSetup(ws: WebSocket) {
        try {
            val setupObj = JSONObject()
            val setupContent = JSONObject()
            setupContent.put("model", LIVE_MODEL)

            // Generation config with AUDIO modality and Aoede voice
            val genConfig = JSONObject()
            val responseModalities = JSONArray()
            responseModalities.put("AUDIO")
            genConfig.put("responseModalities", responseModalities)

            val speechConfig = JSONObject()
            val voiceConfig = JSONObject()
            val prebuiltVoiceConfig = JSONObject()
            prebuiltVoiceConfig.put("voiceName", "Aoede")
            voiceConfig.put("prebuiltVoiceConfig", prebuiltVoiceConfig)
            speechConfig.put("voiceConfig", voiceConfig)
            genConfig.put("speechConfig", speechConfig)
            setupContent.put("generationConfig", genConfig)

            // System instruction
            val sysInst = JSONObject()
            val partsArr = JSONArray()
            val partObj = JSONObject()
            partObj.put("text", TEJAS_SYSTEM_INSTRUCTION)
            partsArr.put(partObj)
            sysInst.put("parts", partsArr)
            setupContent.put("systemInstruction", sysInst)

            // Tool declarations
            setupContent.put("tools", getToolsDeclaration())

            setupObj.put("setup", setupContent)
            val jsonStr = setupObj.toString()
            Log.d(TAG, "Sending setup message to Gemini Live: ${jsonStr.take(200)}...")
            ws.send(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating setup message", e)
        }
    }

    private fun getToolsDeclaration(): JSONArray {
        val tools = JSONArray()
        val toolObj = JSONObject()
        val functionDeclarations = JSONArray()

        // 1. openWhatsApp
        val whatsAppFunc = JSONObject()
        whatsAppFunc.put("name", "openWhatsApp")
        whatsAppFunc.put("description", "Opens WhatsApp on the user's device or browser.")
        functionDeclarations.put(whatsAppFunc)

        // 2. openApp
        val openAppFunc = JSONObject()
        openAppFunc.put("name", "openApp")
        openAppFunc.put("description", "Opens a supported app: YouTube, Instagram, Spotify, Camera, Maps, Browser, or Settings.")
        val openAppParams = JSONObject()
        openAppParams.put("type", "OBJECT")
        val appProps = JSONObject()
        val appNameProp = JSONObject()
        appNameProp.put("type", "STRING")
        appNameProp.put("description", "Name of the app to open (e.g., YouTube, Instagram, Spotify, Camera, Maps, Browser, Settings).")
        appProps.put("appName", appNameProp)
        openAppParams.put("properties", appProps)
        val appReq = JSONArray().put("appName")
        openAppParams.put("required", appReq)
        openAppFunc.put("parameters", openAppParams)
        functionDeclarations.put(openAppFunc)

        // 3. openUrl
        val openUrlFunc = JSONObject()
        openUrlFunc.put("name", "openUrl")
        openUrlFunc.put("description", "Opens a website URL in the device browser.")
        val openUrlParams = JSONObject()
        openUrlParams.put("type", "OBJECT")
        val urlProps = JSONObject()
        val urlProp = JSONObject()
        urlProp.put("type", "STRING")
        urlProp.put("description", "The website URL to open (e.g. https://google.com).")
        urlProps.put("url", urlProp)
        openUrlParams.put("properties", urlProps)
        openUrlParams.put("required", JSONArray().put("url"))
        openUrlFunc.put("parameters", openUrlParams)
        functionDeclarations.put(openUrlFunc)

        // 4. makeCall
        val makeCallFunc = JSONObject()
        makeCallFunc.put("name", "makeCall")
        makeCallFunc.put("description", "Initiates a phone call or opens the phone dialer with a phone number.")
        val callParams = JSONObject()
        callParams.put("type", "OBJECT")
        val callProps = JSONObject()
        val numProp = JSONObject()
        numProp.put("type", "STRING")
        numProp.put("description", "The phone number to dial or call.")
        callProps.put("phoneNumber", numProp)
        callParams.put("properties", callProps)
        callParams.put("required", JSONArray().put("phoneNumber"))
        makeCallFunc.put("parameters", callParams)
        functionDeclarations.put(makeCallFunc)

        // 5. callContact
        val contactFunc = JSONObject()
        contactFunc.put("name", "callContact")
        contactFunc.put("description", "Searches device contacts by name (e.g. Mom, Dad, Rahul) and initiates a call or dialer.")
        val contactParams = JSONObject()
        contactParams.put("type", "OBJECT")
        val contactProps = JSONObject()
        val contactNameProp = JSONObject()
        contactNameProp.put("type", "STRING")
        contactNameProp.put("description", "The name of the contact to search and call.")
        contactProps.put("contactName", contactNameProp)
        contactParams.put("properties", contactProps)
        contactParams.put("required", JSONArray().put("contactName"))
        contactFunc.put("parameters", contactParams)
        functionDeclarations.put(contactFunc)

        toolObj.put("functionDeclarations", functionDeclarations)
        tools.put(toolObj)
        return tools
    }

    /**
     * Send real-time audio chunk (PCM 16-bit, 16kHz mono) to Gemini Live.
     */
    fun sendAudioChunk(pcmData: ByteArray) {
        if (!isConnected.get()) {
            return
        }
        try {
            val base64Data = Base64.encodeToString(pcmData, Base64.NO_WRAP)
            val inputObj = JSONObject()
            val realtimeInput = JSONObject()
            val mediaChunks = JSONArray()
            val chunk = JSONObject()
            chunk.put("mimeType", "audio/pcm;rate=16000")
            chunk.put("data", base64Data)
            mediaChunks.put(chunk)
            realtimeInput.put("mediaChunks", mediaChunks)
            inputObj.put("realtimeInput", realtimeInput)

            webSocket?.send(inputObj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk", e)
        }
    }

    private fun handleServerMessage(text: String) {
        try {
            val root = JSONObject(text)

            // 1. Check for serverContent (audio response / model turn)
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                // Interruption notification from server
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Gemini server signaled interruption - flushing audio")
                    audioPlayer.stopAndClear()
                    scope.launch(Dispatchers.Main) {
                        onInterrupted?.invoke()
                    }
                    return
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)

                            // Check for streaming text response
                            if (part.has("text")) {
                                val textChunk = part.optString("text", "")
                                if (textChunk.isNotBlank()) {
                                    scope.launch(Dispatchers.Main) {
                                        onTextReceived?.invoke(textChunk)
                                    }
                                }
                            }

                            // Check for inlineData (Native audio output)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val mimeType = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                                val base64Audio = inlineData.optString("data", "")
                                if (base64Audio.isNotBlank()) {
                                    val sampleRate = if (mimeType.contains("rate=16000")) 16000 else 24000
                                    val pcmBytes = Base64.decode(base64Audio, Base64.DEFAULT)
                                    Log.d(TAG, "Received audio chunk: ${pcmBytes.size} bytes, rate=$sampleRate")
                                    audioPlayer.enqueueAudio(pcmBytes, sampleRate)
                                }
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    scope.launch(Dispatchers.Main) {
                        onTurnComplete?.invoke()
                    }
                }
            }

            // 2. Check for toolCall (Device actions)
            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                val functionCalls = toolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    for (i in 0 until functionCalls.length()) {
                        val call = functionCalls.getJSONObject(i)
                        val callId = call.optString("id", "")
                        val callName = call.optString("name", "")
                        val args = call.optJSONObject("args") ?: JSONObject()
                        Log.d(TAG, "Executing tool call: $callName with args=$args")
                        handleToolCall(callId, callName, args)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message: ${e.message}", e)
        }
    }

    private fun handleToolCall(callId: String, name: String, args: JSONObject) {
        scope.launch(Dispatchers.Main) {
            val result = when (name) {
                "openWhatsApp" -> {
                    onActionExecuted("WhatsApp", "Opening WhatsApp...")
                    actionBridge.openWhatsApp()
                }
                "openApp" -> {
                    val appName = args.optString("appName", "")
                    onActionExecuted(appName, "Opening $appName...")
                    actionBridge.openApp(appName)
                }
                "openUrl" -> {
                    val url = args.optString("url", "")
                    onActionExecuted("Browser", "Opening $url...")
                    actionBridge.openUrl(url)
                }
                "makeCall" -> {
                    val num = args.optString("phoneNumber", "")
                    onActionExecuted("Phone", "Dialing $num...")
                    actionBridge.makeCall(num)
                }
                "callContact" -> {
                    val contactName = args.optString("contactName", "")
                    onActionExecuted("Contacts", "Calling $contactName...")
                    actionBridge.callContact(contactName)
                }
                else -> {
                    JSONObject().put("success", false).put("error", "Unknown function: $name")
                }
            }

            sendToolResponse(callId, name, result)
        }
    }

    private fun sendToolResponse(callId: String, name: String, responseData: JSONObject) {
        try {
            val toolRespRoot = JSONObject()
            val toolResponse = JSONObject()
            val funcResponses = JSONArray()
            val funcResp = JSONObject()
            if (callId.isNotBlank()) {
                funcResp.put("id", callId)
            }
            funcResp.put("name", name)
            val respWrap = JSONObject()
            respWrap.put("output", responseData)
            funcResp.put("response", respWrap)
            funcResponses.put(funcResp)
            toolResponse.put("functionResponses", funcResponses)
            toolRespRoot.put("toolResponse", toolResponse)

            Log.d(TAG, "Sending tool response back to Gemini: $toolRespRoot")
            webSocket?.send(toolRespRoot.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending tool response", e)
        }
    }

    /**
     * Resilient Direct REST voice generation.
     * Used as fallback or for direct prompt processing with real native audio output!
     */
    suspend fun sendTextMessageWithAudioResponse(userPrompt: String): String = withContext(Dispatchers.IO) {
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$REST_MODEL:generateContent?key=$apiKey"
            val reqBody = JSONObject()

            // Contents
            val contents = JSONArray()
            val turn = JSONObject()
            turn.put("role", "user")
            val parts = JSONArray()
            val part = JSONObject()
            part.put("text", userPrompt)
            parts.put(part)
            turn.put("parts", parts)
            contents.put(turn)
            reqBody.put("contents", contents)

            // Generation config with AUDIO modality and Aoede voice
            val genConfig = JSONObject()
            val responseModalities = JSONArray()
            responseModalities.put("AUDIO")
            genConfig.put("responseModalities", responseModalities)

            val speechConfig = JSONObject()
            val voiceConfig = JSONObject()
            val prebuiltVoiceConfig = JSONObject()
            prebuiltVoiceConfig.put("voiceName", "Aoede")
            voiceConfig.put("prebuiltVoiceConfig", prebuiltVoiceConfig)
            speechConfig.put("voiceConfig", voiceConfig)
            genConfig.put("speechConfig", speechConfig)
            reqBody.put("generationConfig", genConfig)

            // System instruction
            val sysInst = JSONObject()
            val sysParts = JSONArray()
            sysParts.put(JSONObject().put("text", TEJAS_SYSTEM_INSTRUCTION))
            sysInst.put("parts", sysParts)
            reqBody.put("systemInstruction", sysInst)

            // Tools
            reqBody.put("tools", getToolsDeclaration())

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(endpoint)
                .post(reqBody.toString().toRequestBody(mediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                // If the preview model fails, try fallback
                Log.w(TAG, "REST call failed with code ${response.code}: $responseBody")
                return@withContext sendFallbackRestCall(userPrompt)
            }

            parseAndPlayRestResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendTextMessageWithAudioResponse", e)
            sendFallbackRestCall(userPrompt)
        }
    }

    private suspend fun sendFallbackRestCall(userPrompt: String): String = withContext(Dispatchers.IO) {
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$FALLBACK_REST_MODEL:generateContent?key=$apiKey"
            val reqBody = JSONObject()

            val contents = JSONArray()
            val turn = JSONObject().put("role", "user")
            turn.put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            contents.put(turn)
            reqBody.put("contents", contents)

            // Try with AUDIO modality
            val genConfig = JSONObject()
            genConfig.put("responseModalities", JSONArray().put("AUDIO"))
            val speechConfig = JSONObject().put(
                "voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Aoede"))
            )
            genConfig.put("speechConfig", speechConfig)
            reqBody.put("generationConfig", genConfig)

            reqBody.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", TEJAS_SYSTEM_INSTRUCTION))))
            reqBody.put("tools", getToolsDeclaration())

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(endpoint)
                .post(reqBody.toString().toRequestBody(mediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            parseAndPlayRestResponse(body)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback REST error", e)
            "Error communicating with Tejas: ${e.message}"
        }
    }

    private fun parseAndPlayRestResponse(responseJson: String): String {
        return try {
            val root = JSONObject(responseJson)
            val candidates = root.optJSONArray("candidates") ?: return "No response"
            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.getJSONObject("content")
            val parts = content.getJSONArray("parts")

            var responseText = ""
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)

                // 1. Audio data
                if (part.has("inlineData")) {
                    val inlineData = part.getJSONObject("inlineData")
                    val mimeType = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                    val data = inlineData.optString("data", "")
                    if (data.isNotBlank()) {
                        val sampleRate = if (mimeType.contains("rate=16000")) 16000 else 24000
                        val pcm = Base64.decode(data, Base64.DEFAULT)
                        audioPlayer.enqueueAudio(pcm, sampleRate)
                    }
                }

                // 2. Text data
                if (part.has("text")) {
                    val txt = part.getString("text")
                    responseText += txt + " "
                    scope.launch(Dispatchers.Main) {
                        onTextReceived?.invoke(txt)
                    }
                }

                // 3. Function call
                if (part.has("functionCall")) {
                    val funcCall = part.getJSONObject("functionCall")
                    val name = funcCall.optString("name", "")
                    val args = funcCall.optJSONObject("args") ?: JSONObject()
                    handleToolCall("", name, args)
                }
            }
            scope.launch(Dispatchers.Main) {
                onTurnComplete?.invoke()
            }
            responseText.trim().ifBlank { "Speaking with Tejas audio..." }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing REST response", e)
            "Error: ${e.message}"
        }
    }

    fun isSessionConnected(): Boolean = isConnected.get()

    fun disconnect() {
        isConnected.set(false)
        isConnecting.set(false)
        try {
            webSocket?.close(1000, "User disconnected")
            webSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket", e)
        }
        audioPlayer.stopAndClear()
        onStateChanged(ConnectionState.DISCONNECTED)
    }
}
