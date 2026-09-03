package com.rayban.ai.core.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rayban.ai.core.common.ui.component.GlassCard
import com.rayban.ai.core.voice.AssistantPhase
import com.rayban.ai.core.voice.VoiceAssistantViewModel
import com.rayban.ai.core.voice.VoiceError
import com.rayban.ai.domain.model.ConversationTurn

@Composable
fun CameraScreen(
    onBack: () -> Unit,
    previewViewModel: PreviewViewModel = hiltViewModel(),
    voiceViewModel: VoiceAssistantViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var hasMicPermission by remember { mutableStateOf(context.hasMicrophonePermission()) }
    val defaultQuestion = stringResource(R.string.camera_question_default)
    var question by rememberSaveable { mutableStateOf(defaultQuestion) }
    val voiceState by voiceViewModel.uiState.collectAsStateWithLifecycle()
    val previewController = previewViewModel.controller

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            voiceViewModel.onMicClick()
        }
    }

    DisposableEffect(lifecycleOwner, hasPermission) {
        onDispose {
            if (hasPermission) {
                previewController.unbind()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.camera_back))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.camera_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.camera_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (hasPermission) {
            item {
                CameraPreview(
                    controller = previewController,
                    lifecycleOwner = lifecycleOwner,
                )
            }
            item {
                AssistantInputCard(
                    question = question,
                    onQuestionChange = { question = it },
                    busy = voiceState.phase != AssistantPhase.Idle,
                    onAnalyze = { voiceViewModel.askText(question) },
                    onMicClick = {
                        if (hasMicPermission) {
                            voiceViewModel.onMicClick()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onNewConversation = { voiceViewModel.onNewConversation() },
                    onChangeScene = { voiceViewModel.onChangeScene() },
                    onQuickCommand = { voiceViewModel.onQuickCommand(it) },
                )
            }
            when (voiceState.phase) {
                AssistantPhase.Listening -> {
                    item { StatusText(stringResource(R.string.camera_voice_listening)) }
                }
                AssistantPhase.Processing -> {
                    item { StatusText(stringResource(R.string.camera_voice_processing)) }
                }
                AssistantPhase.Speaking -> {
                    item { StatusText(stringResource(R.string.camera_voice_speaking)) }
                }
                AssistantPhase.Recording -> {
                    item { StatusText(stringResource(R.string.camera_status_recording)) }
                }
                AssistantPhase.Idle -> Unit
            }
            voiceState.mediaMessage?.let { media ->
                item {
                    MediaMessageCard(media)
                }
            }
            voiceState.error?.let { error ->
                item {
                    VoiceErrorCard(
                        error = error,
                        message = voiceState.errorMessage,
                        onRetry = { voiceViewModel.retry() },
                    )
                }
            }
            items(
                items = voiceState.turns,
                key = { "${it.sceneId}-${it.timestampMillis}-${it.question}" },
            ) { turn ->
                ConversationTurnCard(turn)
            }
        } else {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = stringResource(R.string.camera_permission_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.camera_permission_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text(stringResource(R.string.camera_allow_access))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantInputCard(
    question: String,
    onQuestionChange: (String) -> Unit,
    busy: Boolean,
    onAnalyze: () -> Unit,
    onMicClick: () -> Unit,
    onNewConversation: () -> Unit,
    onChangeScene: () -> Unit,
    onQuickCommand: (String) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            OutlinedTextField(
                value = question,
                onValueChange = onQuestionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.camera_question_label)) },
                singleLine = true,
                enabled = !busy,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onAnalyze,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(analyzeButtonText(busy)))
                }
                Button(onClick = onMicClick) {
                    Text(stringResource(R.string.camera_mic))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val cmdPhoto = stringResource(R.string.camera_cmd_photo)
            val cmdVideo = stringResource(R.string.camera_cmd_video)
            val cmdRead = stringResource(R.string.camera_cmd_read)
            val cmdSummary = stringResource(R.string.camera_cmd_summary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { onQuickCommand(cmdPhoto) }) {
                    Text(cmdPhoto, maxLines = 1)
                }
                TextButton(onClick = { onQuickCommand(cmdVideo) }) {
                    Text(cmdVideo, maxLines = 1)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { onQuickCommand(cmdRead) }) {
                    Text(cmdRead, maxLines = 1)
                }
                TextButton(onClick = { onQuickCommand(cmdSummary) }) {
                    Text(cmdSummary, maxLines = 1)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onNewConversation) {
                    Text(stringResource(R.string.camera_new_conversation))
                }
                TextButton(onClick = onChangeScene) {
                    Text(stringResource(R.string.camera_change_scene))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.camera_analyze_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MediaMessageCard(media: com.rayban.ai.core.voice.MediaMessage) {
    val text = when (media.type) {
        com.rayban.ai.domain.model.MediaType.Photo -> stringResource(R.string.camera_media_photo_saved)
        com.rayban.ai.domain.model.MediaType.Video -> stringResource(R.string.camera_media_video_saved)
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun analyzeButtonText(busy: Boolean): Int = when {
    busy -> R.string.camera_analyzing
    else -> R.string.camera_analyze
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConversationTurnCard(turn: ConversationTurn) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.camera_voice_question_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = turn.question,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.camera_ai_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = turn.answer,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

@Composable
private fun VoiceErrorCard(
    error: VoiceError,
    message: String?,
    onRetry: () -> Unit,
) {
    val text = when (error) {
        VoiceError.MicPermissionDenied -> stringResource(R.string.camera_voice_error_mic_permission)
        VoiceError.RecognizerUnavailable -> stringResource(R.string.camera_voice_error_recognizer)
        VoiceError.NoMatch -> stringResource(R.string.camera_voice_error_no_match)
        VoiceError.SpeechNetwork -> stringResource(R.string.camera_voice_error_speech_network)
        VoiceError.SpeechAudio -> stringResource(R.string.camera_voice_error_speech_audio)
        VoiceError.Network -> stringResource(R.string.camera_vision_error_network)
        VoiceError.Timeout -> stringResource(R.string.camera_vision_error_timeout)
        VoiceError.InvalidImage -> stringResource(R.string.camera_vision_error_invalid_image)
        VoiceError.ProviderUnavailable -> stringResource(R.string.camera_vision_error_provider)
        VoiceError.RateLimited -> stringResource(R.string.camera_vision_error_rate_limited)
        VoiceError.Cancelled -> stringResource(R.string.camera_vision_error_cancelled)
        VoiceError.MediaFailed -> stringResource(R.string.camera_voice_error_media)
        VoiceError.NoPreviousAnswer -> stringResource(R.string.camera_voice_error_no_previous)
        VoiceError.AmbiguousCommand -> stringResource(R.string.camera_voice_error_ambiguous)
        VoiceError.UnsupportedAction -> stringResource(R.string.camera_voice_error_unsupported)
        VoiceError.Busy -> stringResource(R.string.camera_voice_error_busy)
        VoiceError.Unknown -> message ?: stringResource(R.string.camera_voice_error_unknown)
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.camera_voice_retry))
            }
        }
    }
}

@Composable
private fun CameraPreview(
    controller: CameraPreviewController,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp)
            .clip(MaterialTheme.shapes.large),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PreviewView(viewContext).also { previewView ->
                    controller.bind(previewView, lifecycleOwner)
                }
            },
        )
    }
}

private fun Context.hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
    this,
    Manifest.permission.CAMERA,
) == PackageManager.PERMISSION_GRANTED

private fun Context.hasMicrophonePermission(): Boolean = ContextCompat.checkSelfPermission(
    this,
    Manifest.permission.RECORD_AUDIO,
) == PackageManager.PERMISSION_GRANTED