package com.example.ai_vision.ui

import android.app.Application
import com.example.ai_vision.ai.AiBackendConfig
import com.example.ai_vision.ai.AiQuestion
import com.example.ai_vision.ai.BackendAiClient
import com.example.ai_vision.ai.DemoAiClient
import com.example.ai_vision.ai.AiClient
import com.example.ai_vision.ai.aiFailureMessage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.ai_vision.device.GlassesRuntime
import com.example.ai_vision.device.GlassesConnectionService
import kotlinx.coroutines.CoroutineScope
import com.example.ai_vision.device.CapturedImage
import com.example.ai_vision.device.FakeGlassSession
import com.example.ai_vision.device.GlassBleProvisioner
import com.example.ai_vision.device.GlassSession
import com.example.ai_vision.device.GlassCommandException
import com.example.ai_vision.device.GlassVoiceEvent
import com.example.ai_vision.device.GlassVoiceSession
import com.example.ai_vision.device.VoicePhase
import com.example.ai_vision.device.VoiceSessionState
import com.example.ai_vision.device.LocalHotspot
import com.example.ai_vision.device.inspectPcm16
import com.example.ai_vision.device.V2GlassTransport
import com.example.ai_vision.media.GalleryPhotoSaver
import com.example.ai_vision.media.MediaSaveException
import com.example.ai_vision.core.AppIntent
import com.example.ai_vision.core.IntentRouter
import com.example.ai_vision.core.LocalAnswerHandler
import com.example.ai_vision.core.CloudPolicy
import com.example.ai_vision.core.SettingsStore
import com.example.ai_vision.core.SavedSettings
import com.example.ai_vision.speech.SpeechRenderer
import com.example.ai_vision.speech.LocalSpeechRenderer
import com.example.ai_vision.speech.PhonePcmPlayer
import com.example.ai_vision.speech.PcmOutput
import com.example.ai_vision.speech.ReplyAudio
import com.example.ai_vision.speech.PHOTO_SAVED_VI
import com.example.ai_vision.speech.VIDEO_SAVED_VI
import com.example.ai_vision.speech.REPEAT_VI
import com.example.ai_vision.speech.speakerTestPcm
import com.example.ai_vision.vision.LocalTextReader
import com.example.ai_vision.vision.TextReader
import com.example.ai_vision.vision.ReadingDocument
import com.example.ai_vision.voice.PhoneSpeechInput
import com.example.ai_vision.voice.LocalSpeechToTextEngine
import com.example.ai_vision.voice.SpeechToTextEngine
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface DeviceState {
    data object Disconnected : DeviceState
    data class Connecting(val step: String) : DeviceState
    data class Connected(val label: String, val demo: Boolean) : DeviceState
    data class Error(val message: String) : DeviceState
}

sealed interface ActionState {
    data object Idle : ActionState
    data class Running(val name: String) : ActionState
    data class Success(val message: String) : ActionState
    data class Error(val message: String) : ActionState
}

class DeviceController(private val application: Application, private val runtimeScope: CoroutineScope,
    private val speaker: SpeechRenderer = LocalSpeechRenderer(application),
    private val demoAudio: PcmOutput = PhonePcmPlayer(),
    private val demoAiClient: AiClient = DemoAiClient(),
    private val textReader: TextReader = LocalTextReader(),
    private val aiClient: AiClient = BackendAiClient(),
    private val localSpeech: SpeechToTextEngine = LocalSpeechToTextEngine(application),
    private val demoSessionFactory: () -> GlassSession = { FakeGlassSession() }) {
    private val settings = SettingsStore(application)
    private val restoredSettings = settings.load()
    var deviceState by mutableStateOf<DeviceState>(DeviceState.Disconnected)
        private set
    var actionState by mutableStateOf<ActionState>(ActionState.Idle)
        private set
    var image by mutableStateOf<CapturedImage?>(null)
        private set
    private var savedPhoto by mutableStateOf<CapturedImage?>(null)
    var savedPhotoUri by mutableStateOf<String?>(null)
        private set
    val canSavePhoto: Boolean get() = image != null && image !== savedPhoto
    var inputText by mutableStateOf("")
    var backendUrl by mutableStateOf(restoredSettings.backendUrl)
    var backendToken by mutableStateOf(restoredSettings.backendToken)
    var languageTag by mutableStateOf(restoredSettings.languageTag)
    val wakeWord = ""
    var wakeStatus by mutableStateOf("Bấm WAKE AI trên app để bắt đầu thu; không dùng wake word")
        private set
    var voicePhase by mutableStateOf(VoicePhase.IDLE)
        private set
    private val voiceSession = VoiceSessionState()
    private val earlyVoiceEvents = ArrayDeque<GlassVoiceEvent>()
    private var actionEpoch = 0L
    var demoMode by mutableStateOf(false)
    var cloudEnabled by mutableStateOf(restoredSettings.cloudEnabled)
    var isRecording by mutableStateOf(false)
        private set
    var answerText by mutableStateOf("")
        private set
    var micStatus by mutableStateOf("")
        private set
    var canRetryTranscription by mutableStateOf(false)
        private set
    var audioStatus by mutableStateOf("")
        private set
    var taskResultText by mutableStateOf("")
        private set
    private var lastReply by mutableStateOf<ReplyAudio?>(null)
    val canReplayAudio: Boolean get() = lastReply != null
    var ocrStatus by mutableStateOf("")
        private set
    var ocrRotationDegrees by mutableStateOf(0)
        private set
    private var readingDocument by mutableStateOf<ReadingDocument?>(null)
    private var readingPage by mutableStateOf(0)
    private var readingPhoto by mutableStateOf<CapturedImage?>(null)
    val canReadNext: Boolean get() = readingDocument?.let { readingPage + 1 < it.pages.size } == true
    val canRotateReading: Boolean get() = readingPhoto != null

    private val hotspot = LocalHotspot(application)
    private var session: GlassSession? = null
    private val phoneSpeech = PhoneSpeechInput(application)
    private var ble: GlassBleProvisioner? = null
    private var connectionJob: Job? = null
    private var deviceEventJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastEndpoint: com.example.ai_vision.device.GlassEndpoint? = null
    private var actionJob: Job? = null
    private var activeSessionId: String? = null
    private var activeDeviceAction = false
    private var micTestOnly = false
    private var pendingUtterance: GlassVoiceEvent.Utterance? = null
    private var serviceEpoch = 0L

    fun connect() {
        if (deviceState is DeviceState.Connecting || deviceState is DeviceState.Connected) return
        closeConnections(stopService = false)
        actionState = ActionState.Idle
        micStatus = ""
        pendingUtterance = null
        canRetryTranscription = false
        image = null
        savedPhoto = null
        savedPhotoUri = null
        lastReply = null
        clearReading()
        if (demoMode) {
            session = demoSessionFactory()
            deviceState = DeviceState.Connected("Demo device", true)
            watchVoiceEvents(session!!)
            return
        }
        serviceEpoch++
        try { GlassesConnectionService.start(application, serviceEpoch) } catch (error: Exception) {
            deviceState = DeviceState.Error(error.message ?: "Cannot start connection service")
            return
        }
        deviceState = DeviceState.Connecting("Starting phone hotspot")
        connectionJob = runtimeScope.launch {
            try {
                val credentials = withTimeout(15_000) { hotspot.start() }
                deviceState = DeviceState.Connecting("Finding glasses over BLE")
                val provisioner = GlassBleProvisioner(application)
                ble = provisioner
                val endpoint = provisioner.provision(credentials)
                lastEndpoint = endpoint
                deviceState = DeviceState.Connecting("Opening TCP connection")
                val realSession = openSession(endpoint)
                session = realSession
                deviceState = DeviceState.Connected("${endpoint.host}:${endpoint.port} (V2)", false)
                watchVoiceEvents(realSession)
                watchLiveness(realSession)
                val voice = realSession as? GlassVoiceSession
                voice?.configureVoice(languageTag, "")
                wakeStatus = "WAKE AI từ app; mic chỉ thu trong phiên được yêu cầu"
            } catch (error: TimeoutCancellationException) {
                val step = (deviceState as? DeviceState.Connecting)?.step ?: "connecting"
                closeConnections()
                deviceState = DeviceState.Error("Timed out while $step")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                closeConnections()
                deviceState = DeviceState.Error(error.message ?: "Connection failed")
            }
        }
    }

    fun disconnect(stopService: Boolean = true) {
        actionEpoch++
        voiceSession.clear()
        voicePhase = VoicePhase.IDLE
        earlyVoiceEvents.clear()
        connectionJob?.cancel()
        connectionJob = null
        actionJob?.cancel()
        actionJob = null
        deviceEventJob?.cancel()
        deviceEventJob = null
        activeSessionId = null
        micTestOnly = false
        pendingUtterance = null
        canRetryTranscription = false
        speaker.stop()
        demoAudio.stop()
        closeConnections(stopService = stopService)
        image = null
        savedPhoto = null
        savedPhotoUri = null
        lastReply = null
        clearReading()
        isRecording = false
        actionState = ActionState.Idle
        deviceState = DeviceState.Disconnected
    }

    fun serviceDestroyed(epoch: Long) {
        if (epoch == serviceEpoch) disconnect(stopService = false)
    }

    fun changeLanguage() {
        if (actionState is ActionState.Running) return
        languageTag = if (languageTag == "vi-VN") "en-US" else "vi-VN"
        val voice = session as? GlassVoiceSession ?: return
        runAction("VOICE_CONFIG") {
            voice.configureVoice(languageTag, "")
            actionState = ActionState.Success("Language: $languageTag")
        }
    }

    fun saveSettings() {
        if (actionState is ActionState.Running) return
        try {
            settings.save(SavedSettings(backendUrl, backendToken, languageTag, cloudEnabled))
            actionState = ActionState.Success("Đã lưu cài đặt; app token được mã hóa trên điện thoại")
        } catch (error: Exception) { actionState = ActionState.Error(error.message ?: "Cannot save settings") }
    }

    fun submitInput() {
        if (actionState is ActionState.Running) return
        val intent = try {
            IntentRouter.route(inputText, wakeWord)
        } catch (error: IllegalArgumentException) {
            actionState = ActionState.Error(error.message ?: "Empty input")
            return
        }
        when (intent) {
            AppIntent.Ping -> ping()
            AppIntent.Status -> getStatus()
            AppIntent.Capture -> capture()
            AppIntent.ReadText -> readText()
            AppIntent.ReadNext -> readNext()
            AppIntent.StartVideo -> startVideo()
            AppIntent.StopVideo -> stopVideo()
            AppIntent.StopSpeaking -> cancelAction()
            AppIntent.Replay -> replayAnswer()
            is AppIntent.LocalQuery, is AppIntent.Calculate, is AppIntent.Clarification -> answerLocal(intent)
            is AppIntent.UnsupportedLocal -> answerLocal(AppIntent.Clarification(intent.feature))
            is AppIntent.Question -> askQuestion(intent)
        }
    }

    fun listenOnPhone() {
        if (actionState is ActionState.Running) return
        actionState = ActionState.Running("PHONE MIC DEMO")
        launchAction {
            try {
                inputText = phoneSpeech.listen(languageTag)
                actionState = ActionState.Idle
                submitInput()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionState = ActionState.Error(error.message ?: "Phone speech failed")
            }
        }
    }

    fun listenOnGlasses() = startGlassesListening(testOnly = false)

    fun testMicrophone() = startGlassesListening(testOnly = true)

    private fun startGlassesListening(testOnly: Boolean) {
        val voiceTransport = session as? GlassVoiceSession ?: run {
            micStatus = "Connect a V2 glasses session first"
            actionState = ActionState.Error(micStatus)
            return
        }
        if (actionState is ActionState.Running || isRecording) return
        micTestOnly = testOnly
        if (!voiceSession.start()) return
        voicePhase = VoicePhase.STARTING
        earlyVoiceEvents.clear()
        activeSessionId = UUID.randomUUID().toString()
        pendingUtterance = null
        canRetryTranscription = false
        micStatus = if (testOnly) "Recording mic diagnostics for 8 seconds..." else
            "Nói trên kính; kết thúc sau khoảng lặng hoặc tối đa 8 giây..."
        activeDeviceAction = true
        val starting = if (testOnly) "MIC TEST" else "GLASSES MIC"
        val waiting = if (testOnly) "WAITING FOR MIC TEST" else "WAITING FOR GLASSES AUDIO"
        actionState = ActionState.Running(starting)
        launchAction {
            try {
                val id = voiceTransport.startListening(languageTag, diagnostic = testOnly)
                this@DeviceController.voiceSession.bind(id)
                voicePhase = VoicePhase.LISTENING
                actionState = ActionState.Running(waiting)
                val early = earlyVoiceEvents.toList()
                earlyVoiceEvents.clear()
                early.forEach(::handleVoiceInput)
                // Recording ends after 8 s, but buffered PCM may still be in
                // flight over the phone hotspot before AUDIO_END arrives.
                delay(if (testOnly) 25_000 else 45_000)
                if (this@DeviceController.voiceSession.waitingForInput) {
                    micTestOnly = false
                    activeDeviceAction = false
                    closeConnections()
                    deviceState = DeviceState.Error("Glasses audio timed out. Connect again.")
                    actionState = ActionState.Error("Glasses audio timed out")
                    micStatus = "No audio received from glasses. Check mic wiring and reconnect."
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                micTestOnly = false
                activeDeviceAction = false
                if (error !is GlassCommandException) disconnectAfterError()
                actionState = ActionState.Error(error.message ?: "Cannot start glasses microphone")
                micStatus = error.message ?: "Cannot start glasses microphone"
            }
        }
    }

    fun cancelAction() {
        if (voicePhase == VoicePhase.CANCELLING) return
        val wasUsingDevice = activeDeviceAction || voiceSession.sessionId != null
        advanceVoice(VoicePhase.CANCELLING)
        val previous = actionJob
        previous?.cancel()
        activeSessionId = null
        micTestOnly = false
        pendingUtterance = null
        canRetryTranscription = false
        activeDeviceAction = false
        speaker.stop()
        demoAudio.stop()
        actionState = ActionState.Running("CANCELLING")
        launchAction {
            try {
                previous?.join()
                if (wasUsingDevice) (session as? GlassVoiceSession)?.cancelActive()
                actionState = ActionState.Idle
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error !is GlassCommandException) disconnectAfterError()
                actionState = ActionState.Error(error.message ?: "Cannot cancel action")
            }
        }
    }

    private fun answerLocal(intent: AppIntent) {
        if (actionState is ActionState.Running) return
        val id = UUID.randomUUID().toString()
        activeSessionId = id
        actionState = ActionState.Running("LOCAL")
        audioStatus = ""
        launchAction {
            try {
                val answer = LocalAnswerHandler.answer(intent, languageTag)
                answerText = answer
                completeWithReply(answer, languageTag, id, "Đã trả lời local")
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (activeSessionId == id) actionState = ActionState.Error(error.message ?: "Local answer failed")
            } finally { if (activeSessionId == id) activeDeviceAction = false }
        }
    }

    private fun askQuestion(intent: AppIntent.Question) {
        if (actionState is ActionState.Running) return
        check(CloudPolicy.reason(intent) != null)
        if (!demoMode && !cloudEnabled) {
            actionState = ActionState.Error("Câu này nằm ngoài nhóm local. Bật Gemini để hỏi AI; lệnh local vẫn hoạt động offline.")
            return
        }
        if (!demoMode && (backendUrl.isBlank() || backendToken.isBlank())) {
            actionState = ActionState.Error("Enter the HTTPS AI backend URL and app token")
            return
        }
        if (intent.needsFreshPhoto && deviceState !is DeviceState.Connected) {
            actionState = ActionState.Error("Connect glasses to ask about the scene")
            return
        }
        val sessionId = UUID.randomUUID().toString()
        activeSessionId = sessionId
        if (voiceSession.sessionId != null) advanceVoice(VoicePhase.WAITING_AI)
        answerText = ""
        taskResultText = ""
        actionState = ActionState.Running("AI")
        audioStatus = ""
        launchAction {
            var freshPhoto: CapturedImage? = null
            try {
                val photo = if (intent.needsFreshPhoto) {
                    activeDeviceAction = true
                    captureFreshPhoto()
                } else null
                freshPhoto = photo
                activeDeviceAction = false
                val client = if (demoMode) demoAiClient else aiClient
                val answer = client.answer(
                    AiBackendConfig(backendUrl, backendToken),
                    AiQuestion(sessionId, intent.text, photo?.jpeg)
                )
                if (activeSessionId != sessionId) return@launchAction
                answerText = answer
                completeWithReply(answer, languageTag, sessionId, "Đã nhận câu trả lời AI")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeSessionId == sessionId) {
                    if (freshPhoto != null) taskResultText = "Ảnh mới đã chụp; chưa phân tích được cảnh bằng AI."
                    actionState = ActionState.Error(aiFailureMessage(error))
                }
            } finally {
                if (activeSessionId == sessionId) activeDeviceAction = false
            }
        }
    }

    private fun watchVoiceEvents(connectedSession: GlassSession) {
        val voiceTransport = connectedSession as? GlassVoiceSession ?: return
        deviceEventJob?.cancel()
        deviceEventJob = runtimeScope.launch {
            voiceTransport.voiceEvents.collect { event ->
                if (session !== connectedSession) return@collect
                when (event) {
                    is GlassVoiceEvent.Utterance, is GlassVoiceEvent.LocalCommand -> handleVoiceInput(event)
                    is GlassVoiceEvent.MediaAvailable -> {
                        isRecording = false
                        if (actionState !is ActionState.Running) actionState = ActionState.Success("Video is ready. Say stop video to save it.")
                    }
                    is GlassVoiceEvent.DeviceError -> {
                        handleVoiceInput(event)
                    }
                    is GlassVoiceEvent.Failure -> {
                        if (deviceState is DeviceState.Connected) {
                            micStatus = event.message
                            canRetryTranscription = pendingUtterance != null
                            actionJob?.cancel()
                            activeSessionId = null
                            micTestOnly = false
                            activeDeviceAction = false
                            actionState = ActionState.Error(event.message)
                            recoverConnection(event.message)
                        }
                    }
                }
            }
        }
    }

    private fun advanceVoice(next: VoicePhase) {
        voiceSession.move(next)
        voicePhase = next
    }

    private fun handleVoiceInput(event: GlassVoiceEvent) {
        if (voicePhase == VoicePhase.STARTING) {
            if (earlyVoiceEvents.size < 4) earlyVoiceEvents.addLast(event)
            return
        }
        when (event) {
            is GlassVoiceEvent.Utterance -> answerGlassesUtterance(event)
            is GlassVoiceEvent.LocalCommand -> completeLocalCommand(event)
            is GlassVoiceEvent.DeviceError -> abortVoiceSession(event)
            else -> Unit
        }
    }

    private fun abortVoiceSession(event: GlassVoiceEvent.DeviceError) {
        if (event.sessionId == null || event.sessionId != voiceSession.sessionId) return
        val previous = actionJob
        previous?.cancel()
        activeSessionId = null
        micTestOnly = false
        micStatus = event.code
        speaker.stop()
        demoAudio.stop()
        advanceVoice(VoicePhase.CANCELLING)
        actionState = ActionState.Running("VOICE ERROR CLEANUP")
        launchAction {
            previous?.join()
            actionState = ActionState.Error(event.code)
        }
    }

    /** The device already executed this command. Never dispatch it a second time. */
    private fun completeLocalCommand(event: GlassVoiceEvent.LocalCommand) {
        if (!voiceSession.accepts(event.sessionId)) return
        actionJob?.cancel()
        micTestOnly = false
        advanceVoice(VoicePhase.ROUTING)
        val id = UUID.randomUUID().toString()
        activeSessionId = id
        actionState = ActionState.Running("LOCAL RESULT")
        launchAction {
            try {
                val photo = event.photo
                if (event.command == "REPEAT" && event.result == "REPLAY_ON_ANDROID") {
                    val cached = lastReply
                    if (cached == null) completeWithReply("Chưa có phản hồi để phát lại.", languageTag, id, "Chưa có phản hồi")
                    else {
                        taskResultText = cached.resultText
                        playReply(cached, id)
                        if (activeSessionId == id) actionState = ActionState.Success("Đã phát lại phản hồi gần nhất")
                    }
                } else if (photo != null && event.result == "SUCCESS") {
                    val captured = (session as? GlassVoiceSession ?: error("Glasses disconnected")).downloadPhoto(photo)
                    image = captured
                    saveCapturedPhoto(captured)
                    completeWithReply(if (languageTag == "vi-VN") PHOTO_SAVED_VI else "Photo saved.", languageTag, id, "Ảnh đã lưu trên Android")
                } else {
                    val result = "${event.command}: ${event.result}"
                    completeWithReply(event.confirmation ?: result, languageTag, id, result)
                    if (event.result != "SUCCESS" && activeSessionId == id) actionState = ActionState.Error(result)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (activeSessionId == id) {
                    actionState = ActionState.Error(error.message ?: "Cannot finish local command")
                    if (error is java.io.IOException && error !is com.example.ai_vision.media.MediaSaveException && error !is GlassCommandException)
                        recoverConnection("Local media connection lost")
                }
            }
        }
    }

    private fun answerGlassesUtterance(event: GlassVoiceEvent.Utterance) {
        if (!voiceSession.accepts(event.sessionId)) return
        if (micTestOnly) {
            actionJob?.cancel()
            micTestOnly = false
            activeDeviceAction = false
            val stats = inspectPcm16(event.pcm)
            micStatus = "Mic test: ${stats.samples / 16000.0}s, peak=${stats.peak}, RMS=${stats.rms}, near-clipped=${stats.nearClipped}"
            actionState = ActionState.Success(micStatus)
            return
        }
        actionJob?.cancel()
        activeDeviceAction = false
        pendingUtterance = event
        transcribeUtterance(event)
    }

    fun retryTranscription() {
        if (actionState is ActionState.Running) return
        val event = pendingUtterance ?: return
        transcribeUtterance(event)
    }

    private fun transcribeUtterance(event: GlassVoiceEvent.Utterance) {
        val sessionId = event.sessionId
        actionJob?.cancel()
        activeSessionId = sessionId
        advanceVoice(VoicePhase.TRANSCRIBING)
        activeDeviceAction = false
        canRetryTranscription = false
        answerText = ""
        actionState = ActionState.Running("GLASSES STT")
        micStatus = "Recording received; transcribing speech..."
        launchAction {
            try {
                check(inspectPcm16(event.pcm).rms >= 100) { "Âm thanh quá nhỏ hoặc không có giọng nói. Hãy nói lại gần mic." }
                val transcript = localSpeech.transcribe(event.pcm, event.languageTag)
                if (activeSessionId != sessionId) return@launchAction
                inputText = transcript
                pendingUtterance = null
                canRetryTranscription = false
                micStatus = "Recognized locally: $transcript"
                advanceVoice(VoicePhase.ROUTING)
                actionState = ActionState.Idle
                submitInput()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeSessionId == sessionId) {
                    canRetryTranscription = true
                    micStatus = (error.message ?: "Glasses speech recognition failed") +
                        ". Recording is kept in memory; tap Retry transcription."
                    val recognitionError = micStatus
                    val reply = ReplyAudio(if (event.languageTag == "vi-VN") REPEAT_VI else "Please say that again.", event.languageTag,
                        resultText = "Chưa nhận dạng được câu nói")
                    lastReply = reply
                    taskResultText = "Chưa nhận dạng được câu nói"
                    actionState = ActionState.Running("REPEAT PROMPT")
                    try { playReply(reply, sessionId); audioStatus = "Đã phát yêu cầu nói lại" }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (audioError: Exception) { audioStatus = "Chưa phát được tiếng: ${audioError.message ?: "Audio failed"}" }
                    if (activeSessionId == sessionId) actionState = ActionState.Error(recognitionError)
                }
            } finally {
                if (activeSessionId == sessionId) activeDeviceAction = false
            }
        }
    }

    private suspend fun openSession(endpoint: com.example.ai_vision.device.GlassEndpoint): GlassSession {
        val transport = V2GlassTransport()
        try {
            transport.connect(endpoint)
            currentCoroutineContext().ensureActive()
            return transport
        } catch (error: Exception) { transport.disconnect(); throw error }
    }

    private fun watchLiveness(connected: GlassSession) {
        heartbeatJob?.cancel()
        heartbeatJob = runtimeScope.launch {
            while (session === connected) {
                delay(20_000)
                if (actionState is ActionState.Running) continue
                try { connected.ping() }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (session === connected) recoverConnection(error.message ?: "Connection lost"); return@launch }
            }
        }
    }

    private fun recoverConnection(message: String) {
        if (deviceState is DeviceState.Connecting) return
        actionEpoch++
        voiceSession.clear()
        voicePhase = VoicePhase.IDLE
        earlyVoiceEvents.clear()
        val endpoint = lastEndpoint ?: run { closeConnections(); deviceState = DeviceState.Error(message); return }
        actionJob?.cancel()
        activeSessionId = null
        activeDeviceAction = false
        micTestOnly = false
        isRecording = false
        speaker.stop()
        demoAudio.stop()
        heartbeatJob?.cancel()
        session?.disconnect()
        session = null
        // Retain foreground service/hotspot. Retry only TCP, never replay actions.
        deviceState = DeviceState.Connecting("Reconnecting TCP")
        connectionJob = runtimeScope.launch {
            for (attempt in 1..3) {
                delay(1000L shl (attempt - 1))
                var fresh: GlassSession? = null
                try {
                    deviceState = DeviceState.Connecting("Reconnecting TCP ($attempt/3)")
                    fresh = openSession(endpoint)
                    val voice = fresh as? GlassVoiceSession
                    voice?.configureVoice(languageTag, "")
                    session = fresh
                    deviceState = DeviceState.Connected("${endpoint.host}:${endpoint.port} (V2)", false)
                    watchVoiceEvents(fresh)
                    watchLiveness(fresh)
                    actionState = ActionState.Success("Đã nối lại. Lệnh trước không được gửi lại; nói dừng quay để lấy video chờ.")
                    return@launch
                } catch (error: CancellationException) { fresh?.disconnect(); throw error }
                catch (error: Exception) { fresh?.disconnect() }
            }
            closeConnections()
            deviceState = DeviceState.Error("Không nối lại được TCP. Bấm Connect để tạo phiên BLE/hotspot mới.")
        }
    }

    private suspend fun captureFreshPhoto(): CapturedImage = try {
        (session ?: error("Glasses are not connected")).capture().also { image = it }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // A failed media transfer can leave unread bytes on the TCP stream.
        if (error !is GlassCommandException) disconnectAfterError()
        throw error
    }

    fun ping() = runAction("PING") {
        actionState = ActionState.Success((session ?: error("Glasses are not connected")).ping())
    }

    fun getStatus() = runAction("GET_STATUS") {
        val status = (session ?: error("Glasses are not connected")).getStatus()
        val details = buildList {
            status.rssi?.let { add("RSSI: $it dBm") }
            status.cameraReady?.let { add("camera: ${if (it) "ready" else "off"}") }
            status.micReady?.let { add("mic I2S: ${if (it) "initialized" else "off"}") }
            status.speakerReady?.let { add("speaker I2S: ${if (it) "initialized" else "off"}") }
        }
        actionState = ActionState.Success("Wi-Fi: ${if (status.wifiConnected) "connected" else "off"}${if (details.isEmpty()) "" else ", ${details.joinToString()}"}")
    }

    fun capture() = runAction("CAPTURE") {
        val photo = (session ?: error("Glasses are not connected")).capture()
        image = photo
        saveCapturedPhoto(photo)
        activeDeviceAction = false
        val id = activeSessionId ?: return@runAction
        completeWithReply(if (languageTag == "vi-VN") PHOTO_SAVED_VI else "Photo saved.", languageTag, id, photoSavedMessage())
    }

    fun testSpeaker() = runAction("SPEAKER TEST") {
        playPcm(speakerTestPcm())
        actionState = ActionState.Success("Played 1-second speaker test tone")
    }

    /** Local OCR is intentionally separate from runAction: model errors are not TCP errors. */
    fun readText() = runReading("OCR CAPTURE") { id ->
        clearReading()
        answerText = ""
        lastReply = null
        taskResultText = ""
        audioStatus = ""
        ocrStatus = "Đang chụp ảnh mới để đọc chữ offline"
        activeDeviceAction = true
        val photo = captureFreshPhoto()
        activeDeviceAction = false
        readingPhoto = photo
        recognizeReading(photo, id)
    }

    fun rotateReading() = runReading("OCR ROTATE") { id ->
        val photo = readingPhoto ?: return@runReading
        ocrRotationDegrees = (ocrRotationDegrees + 90) % 360
        readingDocument = null
        lastReply = null
        answerText = ""
        taskResultText = ""
        audioStatus = ""
        recognizeReading(photo, id)
    }

    fun readNext() = runReading("OCR NEXT") { id ->
        val document = readingDocument
        if (document == null || !canReadNext) {
            actionState = ActionState.Error("Không còn đoạn để đọc. Hãy đọc chữ từ ảnh mới.")
            return@runReading
        }
        readingPage++
        speakReadingPage(document, id)
    }

    private suspend fun recognizeReading(photo: CapturedImage, id: String) {
        actionState = ActionState.Running("OCR")
        ocrStatus = "Đang nhận dạng offline, góc $ocrRotationDegrees°. Không gửi ảnh lên Gemini."
        val raw = try { withTimeout(30_000) { textReader.read(photo.jpeg, ocrRotationDegrees) } }
        catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("OCR quá thời gian. Hãy thử lại với chữ lớn và ảnh rõ.")
        }
        currentCoroutineContext().ensureActive()
        if (activeSessionId != id) return
        val document = ReadingDocument.fromText(raw)
        readingDocument = document
        readingPage = 0
        answerText = document.text
        if (document.pages.isEmpty()) {
            ocrStatus = "Không tìm thấy chữ. Đưa kính gần chữ, giữ yên và thử xoay ảnh."
            completeWithReply(if (languageTag == "vi-VN") "Không tìm thấy chữ rõ trong ảnh. Hãy đưa kính gần chữ và thử lại."
                else "No clear text found. Move closer and try again.", languageTag, id, "OCR hoàn tất, không tìm thấy chữ")
        } else speakReadingPage(document, id)
    }

    private suspend fun speakReadingPage(document: ReadingDocument, id: String) {
        ocrStatus = "Đoạn ${readingPage + 1}/${document.pages.size}. Văn bản đầy đủ ở dưới; Phát lại không chụp hoặc gọi AI."
        completeWithReply(document.pages[readingPage], languageTag, id,
            "Đã nhận dạng chữ offline, đoạn ${readingPage + 1}/${document.pages.size}")
    }

    private fun runReading(name: String, block: suspend (String) -> Unit) {
        if (actionState is ActionState.Running) return
        if (deviceState !is DeviceState.Connected) {
            actionState = ActionState.Error("Kết nối kính hoặc bật Demo để đọc chữ.")
            return
        }
        if (isRecording) {
            actionState = ActionState.Error("Dừng quay video trước khi đọc chữ và phát loa.")
            return
        }
        val id = UUID.randomUUID().toString()
        activeSessionId = id
        activeDeviceAction = false
        actionState = ActionState.Running(name)
        launchAction {
            try { block(id) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (activeSessionId == id) {
                    ocrStatus = "Chưa đọc được chữ: ${error.message ?: "OCR failed"}"
                    actionState = ActionState.Error(ocrStatus)
                }
            } finally { if (activeSessionId == id) activeDeviceAction = false }
        }
    }

    private fun clearReading() {
        readingDocument = null
        readingPage = 0
        readingPhoto = null
        ocrRotationDegrees = 0
        ocrStatus = ""
    }

    fun testVietnameseVoice() = runAction("VIETNAMESE VOICE TEST") {
        val id = activeSessionId ?: return@runAction
        completeWithReply("Xin chào, đây là giọng tiếng Việt chạy trực tiếp trong ứng dụng.", "vi-VN", id,
            "Đã chuẩn bị câu thử tiếng Việt")
    }

    fun replayAnswer() {
        if (actionState is ActionState.Running || isRecording) return
        val reply = lastReply ?: run { actionState = ActionState.Error("Chưa có phản hồi để phát lại"); return }
        val id = UUID.randomUUID().toString()
        activeSessionId = id
        taskResultText = reply.resultText
        audioStatus = "Đang phát lại phản hồi gần nhất"
        actionState = ActionState.Running("REPLAY")
        launchAction {
            try {
                playReply(reply, id)
                if (activeSessionId == id) { audioStatus = "Đã phát lại"; actionState = ActionState.Success(taskResultText) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (activeSessionId == id) {
                    audioStatus = "Chưa phát được tiếng: ${error.message ?: "Audio failed"}"
                    actionState = ActionState.Success(taskResultText)
                }
            } finally { if (activeSessionId == id) activeDeviceAction = false }
        }
    }

    fun savePhoto() {
        val photo = image ?: return
        if (actionState is ActionState.Running || photo === savedPhoto) return
        actionState = ActionState.Running("SAVE")
        launchAction {
            try {
                saveCapturedPhoto(photo)
                actionState = ActionState.Success(photoSavedMessage())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionState = ActionState.Error(error.message ?: "Cannot save photo")
            }
        }
    }

    fun reportPermissionDenied() {
        deviceState = DeviceState.Error("Bluetooth and nearby Wi-Fi permissions are required")
    }

    fun reportMicrophonePermissionDenied() {
        actionState = ActionState.Error("Microphone permission is required for phone-mic demo")
    }

    fun stopSpeaking() {
        cancelAction()
    }

    /**
     * Answers always play on the glasses speaker. Demo mode has no glasses, so
     * it keeps the phone speaker; anything else without V2 AUDIO_OUT is an error.
     */
    private suspend fun completeWithReply(text: String, language: String, id: String, success: String) {
        if (voiceSession.sessionId != null) advanceVoice(VoicePhase.SYNTHESIZING)
        taskResultText = success
        val reply = ReplyAudio(text, language, resultText = success)
        lastReply = reply
        audioStatus = "Đang chuẩn bị/phát tiếng"
        actionState = ActionState.Running("SPEAKING")
        try {
            playReply(reply, id)
            if (activeSessionId == id) audioStatus = "Đã phát tiếng"
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            if (activeSessionId == id) audioStatus = "Chưa phát được tiếng: ${error.message ?: "Audio failed"}. Có thể bấm Phát lại."
        }
        if (activeSessionId == id) actionState = ActionState.Success(success)
    }

    private suspend fun playReply(reply: ReplyAudio, sessionId: String) {
        val pcm = reply.pcm ?: speaker.renderPcm16k(reply.text, reply.languageTag)
        currentCoroutineContext().ensureActive()
        if (activeSessionId != sessionId) return
        lastReply = reply.copy(pcm = pcm)
        playPcm(pcm)
    }

    private suspend fun playPcm(pcm: ByteArray) {
        if (voiceSession.sessionId != null) advanceVoice(VoicePhase.PLAYING)
        if (demoMode) { demoAudio.play(pcm); return }
        val voice = session as? GlassVoiceSession
            ?: throw IllegalStateException("Connect V2 glasses with speaker support to hear answers")
        if (!voice.supportsAudioOut()) throw IllegalStateException("Glasses speaker is unavailable")
        activeDeviceAction = true
        try { voice.playAudio(pcm) }
        catch (error: CancellationException) { throw error }
        catch (error: java.io.IOException) {
            if (error !is GlassCommandException) recoverConnection("Audio connection lost")
            throw error
        } finally { activeDeviceAction = false }
    }

    private suspend fun recordingSession(): GlassVoiceSession {
        val voice = session as? GlassVoiceSession
            ?: throw IllegalStateException("Connect V2 glasses to record video")
        if (!voice.supportsVideo()) throw IllegalStateException("Glasses video recording is unavailable")
        return voice
    }

    private suspend fun beginRecording(): String {
        val recordingId = recordingSession().startVideo()
        isRecording = true
        return recordingId
    }

    private suspend fun finishRecording(): ByteArray {
        val voice = recordingSession()
        val video = voice.stopVideo()
        isRecording = false
        val bytes = voice.downloadMedia(video.mediaId, video.size)
        try { GalleryPhotoSaver.saveVideo(application, bytes) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { throw MediaSaveException("Video nhận được nhưng chưa lưu: ${error.message}", error) }
        voice.acknowledgeMedia(video.mediaId)
        return bytes
    }

    fun startVideo() = runAction("START_VIDEO") {
        beginRecording()
        actionState = ActionState.Success("Recording video")
    }

    fun stopVideo() = runAction("STOP_VIDEO") {
        val bytes = finishRecording()
        activeDeviceAction = false
        val id = activeSessionId ?: return@runAction
        completeWithReply(if (languageTag == "vi-VN") VIDEO_SAVED_VI else "Video saved.", languageTag, id,
            "Video đã lưu vào Gallery (${bytes.size} bytes)")
    }

    fun toggleRecording() {
        if (actionState is ActionState.Running) return
        if (isRecording) stopVideo() else startVideo()
    }

    private fun runAction(name: String, block: suspend () -> Unit) {
        if (deviceState !is DeviceState.Connected || actionState is ActionState.Running) return
        if (isRecording && name !in setOf("STOP_VIDEO", "PING", "GET_STATUS")) {
            actionState = ActionState.Error("Dừng quay video trước khi dùng mic/camera/loa")
            return
        }
        val id = UUID.randomUUID().toString()
        activeSessionId = id
        actionState = ActionState.Running(name)
        activeDeviceAction = true
        launchAction {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeSessionId == id) {
                    actionState = ActionState.Error(error.message ?: "$name failed")
                    if (error !is GlassCommandException && error !is MediaSaveException && error is java.io.IOException) disconnectAfterError()
                }
            } finally {
                if (activeSessionId == id) activeDeviceAction = false
            }
        }
    }

    private fun photoSavedMessage(): String = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
        "Ảnh đã lưu vào Gallery" else "Ảnh đã lưu trong thư mục của ứng dụng"

    private suspend fun saveCapturedPhoto(photo: CapturedImage) {
        try {
            // Finish the one atomic save and record its URI even if Cancel arrives during disk I/O.
            // Otherwise a successfully published Gallery entry could be saved twice on Retry.
            withContext(NonCancellable) {
                val uri = GalleryPhotoSaver.save(application, photo.jpeg)
                if (image === photo) { savedPhoto = photo; savedPhotoUri = uri.toString() }
            }
            currentCoroutineContext().ensureActive()
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { throw MediaSaveException("Ảnh đã nhận — chưa lưu được: ${error.message}", error) }
    }

    /** One owner; STT handing off to another action supersedes its cleanup. */
    private fun launchAction(block: suspend CoroutineScope.() -> Unit) {
        val epoch = ++actionEpoch
        val job = runtimeScope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (epoch == actionEpoch) actionState = ActionState.Error("Thao tác quá thời gian")
            } finally {
                if (epoch == actionEpoch && actionState !is ActionState.Running) {
                    val terminal = actionState
                    if (voiceSession.sessionId != null) {
                        advanceVoice(VoicePhase.CLEANING_UP)
                        actionState = ActionState.Running("FINISHING VOICE SESSION")
                        withContext(NonCancellable) {
                            try { (session as? GlassVoiceSession)?.stopListening() }
                            catch (error: Exception) { if (error !is GlassCommandException) recoverConnection("Voice cleanup failed") }
                        }
                    }
                    if (epoch == actionEpoch) {
                        voiceSession.clear()
                        voicePhase = VoicePhase.IDLE
                        earlyVoiceEvents.clear()
                        activeDeviceAction = false
                        if (deviceState !is DeviceState.Connecting) actionState = terminal
                    }
                }
            }
        }
        actionJob = job
        job.start()
    }

    private fun disconnectAfterError() {
        recoverConnection("Connection lost")
    }

    private fun closeConnections(stopService: Boolean = true) {
        micTestOnly = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        lastEndpoint = null
        deviceEventJob?.cancel()
        deviceEventJob = null
        session?.disconnect()
        session = null
        ble?.close()
        ble = null
        hotspot.close()
        if (stopService) {
            serviceEpoch++
            GlassesConnectionService.stop(application)
        }
    }

}

// Screen ViewModel owns no connection resources. Runtime survives its onCleared().
class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    val controller = GlassesRuntime.controller(application)
}
