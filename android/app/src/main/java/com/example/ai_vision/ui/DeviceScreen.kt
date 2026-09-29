package com.example.ai_vision.ui

import android.Manifest
import android.graphics.Bitmap
import com.example.ai_vision.media.decodePhotoPreview
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DeviceScreen(owner: DeviceViewModel = viewModel()) {
    val viewModel = owner.controller
    val context = LocalContext.current
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (permissions.all { result[it] == true || ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            viewModel.connect()
        } else {
            viewModel.reportPermissionDenied()
        }
    }
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.listenOnPhone() else viewModel.reportMicrophonePermissionDenied()
    }
    val device = viewModel.deviceState
    val action = viewModel.actionState
    val photo = viewModel.image
    val bitmap by produceState<Bitmap?>(null, photo) {
        value = null
        value = photo?.let { captured -> withContext(Dispatchers.Default) {
            try { decodePhotoPreview(captured.jpeg) }
            catch (_: RuntimeException) { null }
            catch (_: OutOfMemoryError) { null }
        } }
    }
    val connected = device is DeviceState.Connected

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ai-Vision Glasses", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Demo without glasses")
            Switch(
                checked = viewModel.demoMode,
                onCheckedChange = { viewModel.demoMode = it },
                enabled = device !is DeviceState.Connected && device !is DeviceState.Connecting
            )
        }
        if (!viewModel.demoMode) Text("Connect: phone hotspot → BLE provisioning → TCP V2 port 5000. No manual IP.")
        Text(when (device) {
            DeviceState.Disconnected -> "Disconnected"
            is DeviceState.Connecting -> device.step
            is DeviceState.Connected -> "Connected to ${device.label}${if (device.demo) " (synthetic data)" else ""}"
            is DeviceState.Error -> device.message
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = device !is DeviceState.Connecting && !connected,
                onClick = {
                    if (viewModel.demoMode || permissions.all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                        viewModel.connect()
                    } else {
                        permissionLauncher.launch(permissions)
                    }
                }
            ) { Text("Connect") }
            Button(
                enabled = device is DeviceState.Connecting || connected,
                onClick = viewModel::disconnect
            ) { Text("Disconnect") }
        }
        Text(when (action) {
            ActionState.Idle -> "No command sent"
            is ActionState.Running -> "Running ${action.name}..."
            is ActionState.Success -> action.message
            is ActionState.Error -> action.message
        })
        if (viewModel.taskResultText.isNotBlank()) Text(viewModel.taskResultText)
        if (viewModel.audioStatus.isNotBlank()) Text(viewModel.audioStatus)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = connected && action !is ActionState.Running, onClick = viewModel::ping) { Text("PING") }
            Button(enabled = connected && action !is ActionState.Running, onClick = viewModel::getStatus) { Text("Status") }
            Button(enabled = connected && !viewModel.isRecording && action !is ActionState.Running, onClick = viewModel::capture) { Text("Capture") }
        }
        if (bitmap != null) {
            Spacer(Modifier.height(8.dp))
            Image(bitmap!!.asImageBitmap(), contentDescription = if (viewModel.demoMode) "Synthetic demo photo" else "Photo from glasses", modifier = Modifier.fillMaxWidth())
        } else if (photo != null) {
            Text("Preview đang tải hoặc không hiển thị được; dữ liệu ảnh vẫn được giữ trên Android.")
        }
        if (photo != null) {
            Button(enabled = viewModel.canSavePhoto && action !is ActionState.Running, onClick = viewModel::savePhoto) {
                Text(if (viewModel.canSavePhoto) "Lưu ảnh trên Android" else "Ảnh đã lưu")
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Assistant", style = MaterialTheme.typography.titleLarge)
        Text("Text questions play answers on the glasses speaker (V2 AUDIO_OUT required). Demo mode still uses the phone speaker.")
        OutlinedTextField(
            value = viewModel.inputText,
            onValueChange = { viewModel.inputText = it },
            label = { Text("Command or question") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = action !is ActionState.Running, onClick = viewModel::submitInput) { Text("Send") }
            Button(
                enabled = action !is ActionState.Running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        viewModel.listenOnPhone()
                    } else {
                        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            ) { Text("Phone mic demo") }
        }
        Button(enabled = viewModel.canReplayAudio && action !is ActionState.Running, onClick = viewModel::replayAnswer) {
            Text("Phát lại phản hồi gần nhất")
        }
        if (connected) {
            Button(enabled = !viewModel.isRecording && action !is ActionState.Running, onClick = viewModel::testSpeaker) { Text("Test loa bằng beep") }
            Button(enabled = !viewModel.isRecording && action !is ActionState.Running, onClick = viewModel::testVietnameseVoice) { Text("Test giọng Việt offline") }
        }
        Text("Tiếng Việt phát bằng model offline trong app, không cần voice hệ thống. Tiếng Anh vẫn cần voice offline đã cài.")
        Text("Đọc chữ offline", style = MaterialTheme.typography.titleLarge)
        Button(enabled = connected && !viewModel.isRecording && action !is ActionState.Running, onClick = viewModel::readText) {
            Text("Chụp và đọc chữ")
        }
        Button(enabled = connected && viewModel.canReadNext && !viewModel.isRecording && action !is ActionState.Running, onClick = viewModel::readNext) {
            Text("Đọc tiếp văn bản")
        }
        Button(enabled = connected && viewModel.canRotateReading && !viewModel.isRecording && action !is ActionState.Running, onClick = viewModel::rotateReading) {
            Text("Xoay ảnh và đọc lại (${viewModel.ocrRotationDegrees}°)")
        }
        Text("Nói ‘đọc chữ trước mặt tôi’ để dùng OCR local. Nói ‘trước mặt tôi có gì’ để hỏi Gemini. Camera hiện QVGA: đưa kính gần chữ và giữ yên.")
        if (viewModel.demoMode) Text("OCR Demo chỉ đọc chữ trên ảnh giả do app tạo, không phải ảnh kính thật.")
        if (viewModel.ocrStatus.isNotBlank()) Text(viewModel.ocrStatus)
        if (!viewModel.demoMode && connected) {
            Button(enabled = !viewModel.isRecording && action !is ActionState.Running, onClick = viewModel::testMicrophone) {
                Text("Mic diagnostics (8s, no STT)")
            }
            Button(enabled = !viewModel.isRecording && action !is ActionState.Running, onClick = viewModel::listenOnGlasses) {
                Text("WAKE AI — nói trên mic kính")
            }
            Button(enabled = action !is ActionState.Running, onClick = viewModel::toggleRecording) {
                Text(if (viewModel.isRecording) "Stop video" else "Record video")
            }
        }
        if (viewModel.micStatus.isNotBlank()) Text(viewModel.micStatus)
        if (viewModel.canRetryTranscription) {
            Button(enabled = action !is ActionState.Running, onClick = viewModel::retryTranscription) {
                Text("Retry transcription")
            }
        }
        Button(enabled = action is ActionState.Running, onClick = viewModel::cancelAction) { Text("Cancel action") }
        Button(enabled = action !is ActionState.Running, onClick = viewModel::changeLanguage) { Text("Language: ${viewModel.languageTag}") }
        Text(viewModel.wakeStatus)
        Text("Voice: ${viewModel.voicePhase}. Hiện STT offline trên Android; Local AI phân loại lệnh trên chip chưa tích hợp. Không nghe chen lúc loa phát.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cho phép Gemini với câu ngoài nhóm local")
            Switch(checked = viewModel.cloudEnabled, onCheckedChange = { viewModel.cloudEnabled = it }, enabled = action !is ActionState.Running)
        }
        OutlinedTextField(
            value = viewModel.backendUrl,
            onValueChange = { viewModel.backendUrl = it },
            label = { Text("AI backend HTTPS URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = viewModel.backendToken,
            onValueChange = { viewModel.backendToken = it },
            label = { Text("App token") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(enabled = action !is ActionState.Running, onClick = viewModel::saveSettings) { Text("Lưu cài đặt") }
        if (viewModel.answerText.isNotEmpty()) {
            Text(viewModel.answerText, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
