package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.IndigoBorder
import com.example.ui.theme.IndigoCard
import com.example.ui.theme.IndigoDark
import com.example.ui.theme.IndigoSurface
import com.example.ui.theme.MagentaAccent
import com.example.ui.theme.RedError
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletSecondary
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TejasScreen(viewModel: TejasViewModel) {
    val context = LocalContext.current
    val assistantState by viewModel.assistantState.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val lastAction by viewModel.lastActionMessage.collectAsState()
    val isSpeakerTesting by viewModel.isSpeakerTesting.collectAsState()
    val logs by viewModel.transcriptLogs.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()

    var textInput by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    // Permissions launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordAudioGranted) {
            viewModel.toggleVoiceSession()
        }
    }

    fun checkAndStart() {
        val hasRecordAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasRecordAudio) {
            viewModel.toggleVoiceSession()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE
                )
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = IndigoDark,
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (assistantState) {
                                    AssistantState.LISTENING -> CyanPrimary
                                    AssistantState.SPEAKING -> MagentaAccent
                                    AssistantState.CONNECTING -> AmberWarning
                                    AssistantState.ERROR -> RedError
                                    AssistantState.IDLE -> TextMuted
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TEJAS",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp,
                        color = TextPrimary
                    )
                }
                Text(
                    text = "Real-time Voice AI • Gemini Live Native Audio",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )

                // Language capability chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.Center
                ) {
                    val languages = listOf("Hindi", "English", "Hinglish", "मराठी", "ગુજરાતી", "தமிழ்", "తెలుగు", "বাংলা")
                    languages.forEach { lang ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = IndigoSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, IndigoBorder),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = lang,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Central Glowing Orb
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                GlowingSoundOrb(
                    state = assistantState,
                    audioLevel = audioLevel,
                    onClick = { checkAndStart() }
                )
            }

            // Status Card & Action Feedback
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Status Pill
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = when (assistantState) {
                        AssistantState.LISTENING -> CyanPrimary.copy(alpha = 0.15f)
                        AssistantState.SPEAKING -> MagentaAccent.copy(alpha = 0.15f)
                        AssistantState.CONNECTING -> AmberWarning.copy(alpha = 0.15f)
                        AssistantState.ERROR -> RedError.copy(alpha = 0.15f)
                        AssistantState.IDLE -> IndigoSurface
                    },
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        when (assistantState) {
                            AssistantState.LISTENING -> CyanPrimary.copy(alpha = 0.5f)
                            AssistantState.SPEAKING -> MagentaAccent.copy(alpha = 0.5f)
                            AssistantState.CONNECTING -> AmberWarning.copy(alpha = 0.5f)
                            AssistantState.ERROR -> RedError.copy(alpha = 0.5f)
                            AssistantState.IDLE -> IndigoBorder
                        }
                    ),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        when (assistantState) {
                            AssistantState.CONNECTING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = AmberWarning
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            AssistantState.LISTENING -> {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            AssistantState.SPEAKING -> {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = null,
                                    tint = MagentaAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            AssistantState.ERROR -> {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = RedError,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            AssistantState.IDLE -> {}
                        }
                        Text(
                            text = statusMessage,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = when (assistantState) {
                                AssistantState.LISTENING -> CyanGlow
                                AssistantState.SPEAKING -> MagentaAccent
                                AssistantState.CONNECTING -> AmberWarning
                                AssistantState.ERROR -> RedError
                                AssistantState.IDLE -> TextSecondary
                            },
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Real-time incoming text response banner
                AnimatedVisibility(
                    visible = streamingText.isNotBlank(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = IndigoCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MagentaAccent.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Subtitles,
                                contentDescription = null,
                                tint = MagentaAccent,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = streamingText,
                                fontSize = 13.sp,
                                color = TextPrimary,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }

                // Action banner if action executed
                AnimatedVisibility(
                    visible = lastAction != null,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut()
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = IndigoCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldSuccess,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = lastAction ?: "",
                                fontSize = 12.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Controls row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Speaker Test Diagnostic Button
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = IndigoSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, IndigoBorder),
                        modifier = Modifier
                            .testTag("speaker_test_button")
                            .clickable(enabled = !isSpeakerTesting) {
                                viewModel.testSpeaker()
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Test Speaker",
                                tint = if (isSpeakerTesting) AmberWarning else CyanPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isSpeakerTesting) "Testing..." else "Test Speaker",
                                fontSize = 12.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Main Power/Mic Button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(
                                when (assistantState) {
                                    AssistantState.LISTENING -> Brush.radialGradient(listOf(CyanPrimary, VioletSecondary))
                                    AssistantState.SPEAKING -> Brush.radialGradient(listOf(MagentaAccent, VioletSecondary))
                                    AssistantState.CONNECTING -> Brush.radialGradient(listOf(AmberWarning, IndigoCard))
                                    else -> Brush.radialGradient(listOf(IndigoCard, IndigoSurface))
                                }
                            )
                            .border(
                                width = 2.dp,
                                color = when (assistantState) {
                                    AssistantState.LISTENING -> CyanPrimary
                                    AssistantState.SPEAKING -> MagentaAccent
                                    AssistantState.CONNECTING -> AmberWarning
                                    else -> IndigoBorder
                                },
                                shape = CircleShape
                            )
                            .clickable { checkAndStart() }
                            .testTag("mic_button")
                    ) {
                        Icon(
                            imageVector = when (assistantState) {
                                AssistantState.IDLE, AssistantState.ERROR -> Icons.Default.Mic
                                AssistantState.CONNECTING -> Icons.Default.Mic
                                AssistantState.LISTENING -> Icons.Default.Mic
                                AssistantState.SPEAKING -> Icons.Default.Stop
                            },
                            contentDescription = "Microphone",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Stop/Interrupt Button (or Logs toggle)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (assistantState == AssistantState.SPEAKING) MagentaAccent.copy(alpha = 0.2f) else IndigoSurface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (assistantState == AssistantState.SPEAKING) MagentaAccent else IndigoBorder
                        ),
                        modifier = Modifier
                            .testTag("interrupt_button")
                            .clickable {
                                if (assistantState == AssistantState.SPEAKING) {
                                    viewModel.interrupt()
                                } else {
                                    showLogs = !showLogs
                                }
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = if (assistantState == AssistantState.SPEAKING) Icons.Default.Stop else Icons.Default.GraphicEq,
                                contentDescription = "Interrupt",
                                tint = if (assistantState == AssistantState.SPEAKING) MagentaAccent else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (assistantState == AssistantState.SPEAKING) "Stop/Wait" else (if (showLogs) "Hide Logs" else "Logs"),
                                fontSize = 12.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Text Input Bar for quiet environments
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Ask or command in any language...", fontSize = 12.sp, color = TextMuted) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("text_prompt_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = IndigoBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = IndigoSurface,
                            unfocusedContainerColor = IndigoSurface
                        ),
                        shape = RoundedCornerShape(20.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (textInput.isNotBlank()) {
                                viewModel.sendTextPrompt(textInput)
                                textInput = ""
                            }
                        })
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                viewModel.sendTextPrompt(textInput)
                                textInput = ""
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(IndigoCard)
                            .testTag("send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = CyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Expandable Live Logs
                AnimatedVisibility(visible = showLogs) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = IndigoSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, IndigoBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .padding(top = 8.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            reverseLayout = true
                        ) {
                            items(logs) { log ->
                                Text(
                                    text = log,
                                    fontSize = 11.sp,
                                    color = if (log.startsWith("Error")) RedError else if (log.startsWith("Executed")) EmeraldSuccess else TextSecondary,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GlowingSoundOrb(
    state: AssistantState,
    audioLevel: Float,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing)
        ),
        label = "rotation"
    )

    // Reactive scale based on audio volume
    val dynamicScale = (1.0f + audioLevel * 0.4f) * (if (state != AssistantState.IDLE) pulseScale else 1.0f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(280.dp)
            .clickable { onClick() }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().scale(dynamicScale)) {
            val centerOffset = Offset(size.width / 2, size.height / 2)
            val baseRadius = size.minDimension / 3.4f

            // Outer reactive ripples
            val rippleColor = when (state) {
                AssistantState.LISTENING -> CyanPrimary.copy(alpha = 0.25f + audioLevel * 0.4f)
                AssistantState.SPEAKING -> MagentaAccent.copy(alpha = 0.25f + audioLevel * 0.4f)
                AssistantState.CONNECTING -> AmberWarning.copy(alpha = 0.2f)
                AssistantState.ERROR -> RedError.copy(alpha = 0.2f)
                AssistantState.IDLE -> IndigoBorder.copy(alpha = 0.3f)
            }

            // Outer rings
            drawCircle(
                color = rippleColor,
                radius = baseRadius * 1.35f,
                center = centerOffset,
                style = Stroke(width = 2.dp.toPx())
            )

            drawCircle(
                color = rippleColor.copy(alpha = rippleColor.alpha * 0.6f),
                radius = baseRadius * 1.6f,
                center = centerOffset,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Orbiting particles when active
            if (state == AssistantState.LISTENING || state == AssistantState.SPEAKING) {
                for (i in 0 until 6) {
                    val angle = Math.toRadians((rotationAngle + i * 60).toDouble())
                    val orbitRadius = baseRadius * 1.45f
                    val px = centerOffset.x + (orbitRadius * cos(angle)).toFloat()
                    val py = centerOffset.y + (orbitRadius * sin(angle)).toFloat()
                    drawCircle(
                        color = if (state == AssistantState.LISTENING) CyanGlow else MagentaAccent,
                        radius = 3.dp.toPx(),
                        center = Offset(px, py)
                    )
                }
            }

            // Core Orb with gradient
            val coreBrush = when (state) {
                AssistantState.LISTENING -> Brush.radialGradient(
                    colors = listOf(CyanGlow, CyanPrimary, VioletSecondary),
                    center = centerOffset,
                    radius = baseRadius
                )
                AssistantState.SPEAKING -> Brush.radialGradient(
                    colors = listOf(Color.White, MagentaAccent, VioletSecondary),
                    center = centerOffset,
                    radius = baseRadius
                )
                AssistantState.CONNECTING -> Brush.radialGradient(
                    colors = listOf(Color.White, AmberWarning, IndigoSurface),
                    center = centerOffset,
                    radius = baseRadius
                )
                AssistantState.ERROR -> Brush.radialGradient(
                    colors = listOf(Color.White, RedError, IndigoDark),
                    center = centerOffset,
                    radius = baseRadius
                )
                AssistantState.IDLE -> Brush.radialGradient(
                    colors = listOf(IndigoCard, IndigoSurface, IndigoDark),
                    center = centerOffset,
                    radius = baseRadius
                )
            }

            drawCircle(
                brush = coreBrush,
                radius = baseRadius,
                center = centerOffset
            )
        }

        // Center Icon / Visualizer
        when (state) {
            AssistantState.CONNECTING -> {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(44.dp)
                )
            }
            AssistantState.LISTENING -> {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Listening",
                    tint = Color.Black,
                    modifier = Modifier.size(44.dp)
                )
            }
            AssistantState.SPEAKING -> {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = "Speaking",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
            AssistantState.ERROR -> {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
            AssistantState.IDLE -> {
                Icon(
                    imageVector = Icons.Default.MicOff,
                    contentDescription = "Idle",
                    tint = TextMuted,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
    }
}
