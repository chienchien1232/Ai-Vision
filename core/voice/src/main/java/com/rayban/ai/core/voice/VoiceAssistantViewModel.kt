package com.rayban.ai.core.voice

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rayban.ai.domain.audio.SpeakerEvent
import com.rayban.ai.domain.audio.SpeechError
import com.rayban.ai.domain.audio.SpeechEvent
import com.rayban.ai.domain.audio.SpeechToText
import com.rayban.ai.domain.audio.TextSpeaker
import com.rayban.ai.domain.model.FrameCaptureException
import com.rayban.ai.domain.model.MediaCaptureException
import com.rayban.ai.domain.model.MediaCaptureError
import com.rayban.ai.domain.model.VisionException
import com.rayban.ai.domain.orchestrator.AiOrchestrator
import com.rayban.ai.domain.orchestrator.AssistantCommand
import com.rayban.ai.domain.orchestrator.CommandError
import com.rayban.ai.domain.orchestrator.CommandMatch
import com.rayban.ai.domain.orchestrator.CommandRequest
import com.rayban.ai.domain.orchestrator.CommandSource
import com.rayban.ai.domain.orchestrator.CommandOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class VoiceAssistantViewModel @Inject constructor(
    private val speechToText: SpeechToText,
    private val textSpeaker: TextSpeaker,
    private val orchestrator: AiOrchestrator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    private var commandJob: Job? = null
    private var interactionId: Long = 0L
    private var lastQuestion: String? = null
    private var lastCommand: AssistantCommand? = null

    init {
        viewModelScope.launch {
            speechToText.events.collect { handleSpeechEvent(it) }
        }
        viewModelScope.launch {
            textSpeaker.events.collect { handleSpeakerEvent(it) }
        }
    }

    override fun onCleared() {
        commandJob?.cancel()
        speechToText.cancel()
        textSpeaker.stop()
    }

    fun onMicClick() {
        when (_uiState.value.phase) {
            AssistantPhase.Idle -> startListening()
            AssistantPhase.Listening -> cancelListening()
            AssistantPhase.Processing -> cancelCommand()
            AssistantPhase.Speaking -> stopSpeaking()
            AssistantPhase.Recording -> stopRecordingEarly()
        }
    }

    fun askText(question: String) {
        submit(question.trim(), CommandSource.Typed)
    }

    fun onQuickCommand(utterance: String) {
        submit(utterance, CommandSource.QuickAction)
    }

    fun retry() {
        val currentError = _uiState.value.error
        if (currentError != null && currentError.isSpeechError()) {
            startListening()
            return
        }
        val command = lastCommand
        val question = lastQuestion
        if (command != null && !question.isNullOrBlank()) {
            if (_uiState.value.phase != AssistantPhase.Idle) {
                setError(VoiceError.Busy, null)
                return
            }
            executeCommand(command, question)
        } else {
            startListening()
        }
    }

    fun onNewConversation() {
        cancelActiveWork()
        viewModelScope.launch { orchestrator.newConversation() }
        _uiState.value = VoiceUiState()
        lastQuestion = null
        lastCommand = null
        Log.d(TAG, "[Voice] New conversation started")
    }

    fun onChangeScene() {
        cancelActiveWork()
        viewModelScope.launch { orchestrator.changeScene() }
        _uiState.value = VoiceUiState()
        lastQuestion = null
        lastCommand = null
        Log.d(TAG, "[Voice] Scene changed")
    }

    // ------------------------------------------------------------------
    // Command routing
    // ------------------------------------------------------------------

    private fun submit(utterance: String, source: CommandSource) {
        if (utterance.isEmpty()) {
            setError(VoiceError.NoMatch, null)
            return
        }
        val match = orchestrator.route(
            CommandRequest(
                interactionId = ++interactionId,
                utterance = utterance,
                source = source,
                requestedAtMillis = System.currentTimeMillis(),
            ),
        )
        when (match) {
            is CommandMatch.Rejected -> setError(match.error.toVoiceError(), null)
            is CommandMatch.Matched -> handleCommand(match.command, utterance)
        }
    }

    private fun handleCommand(command: AssistantCommand, utterance: String) {
        when (command) {
            AssistantCommand.Stop -> handleStop()
            AssistantCommand.RepeatLastAnswer -> handleRepeat()
            else -> executeCommand(command, utterance)
        }
    }

    private fun executeCommand(command: AssistantCommand, utterance: String) {
        if (_uiState.value.phase != AssistantPhase.Idle) {
            Log.d(TAG, "[Voice] Request dropped: busy in ${_uiState.value.phase}")
            setError(VoiceError.Busy, null)
            return
        }
        val isVideo = command is AssistantCommand.RecordVideo
        lastCommand = command
        lastQuestion = utterance
        _uiState.value = _uiState.value.copy(
            phase = if (isVideo) AssistantPhase.Recording else AssistantPhase.Processing,
            error = null,
            errorMessage = null,
            recordingStartedAtMillis = if (isVideo) System.currentTimeMillis() else null,
        )
        Log.d(TAG, "[Voice] Executing command: $command utterance: $utterance")
        commandJob?.cancel()
        commandJob = viewModelScope.launch {
            try {
                when (val outcome = orchestrator.execute(command, utterance)) {
                    is CommandOutcome.Speak -> {
                        Log.d(TAG, "[Voice] Answer ready")
                        _uiState.value = _uiState.value.copy(
                            phase = AssistantPhase.Speaking,
                            turns = outcome.turns,
                        )
                        textSpeaker.speak(outcome.text)
                    }
                    is CommandOutcome.PhotoSaved -> {
                        Log.d(TAG, "[Voice] Photo saved: ${outcome.asset.uri}")
                        _uiState.value = _uiState.value.copy(
                            phase = AssistantPhase.Idle,
                            mediaMessage = MediaMessage(outcome.asset.type, outcome.asset.uri),
                        )
                        textSpeaker.speak(PHOTO_SAVED_CONFIRMATION)
                    }
                    is CommandOutcome.VideoSaved -> {
                        Log.d(TAG, "[Voice] Video saved: ${outcome.asset.uri}")
                        _uiState.value = _uiState.value.copy(
                            phase = AssistantPhase.Idle,
                            mediaMessage = MediaMessage(outcome.asset.type, outcome.asset.uri),
                            recordingStartedAtMillis = null,
                        )
                        textSpeaker.speak(VIDEO_SAVED_CONFIRMATION)
                    }
                    CommandOutcome.Stopped, CommandOutcome.NothingRunning, CommandOutcome.NoPreviousAnswer -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaCaptureException) {
                Log.e(TAG, "[Voice] Media capture failed: ${e.error}", e)
                setFailure(VoiceError.MediaFailed, e.message)
            } catch (e: Exception) {
                Log.e(TAG, "[Voice] Command failed: ${e.message}", e)
                setFailure(e.toVoiceError(), e.message)
            }
        }
    }

    private fun handleStop() {
        Log.d(TAG, "[Voice] Stop command received in ${_uiState.value.phase}")
        when (_uiState.value.phase) {
            AssistantPhase.Idle -> Unit
            AssistantPhase.Listening -> {
                speechToText.cancel()
                _uiState.value = _uiState.value.copy(phase = AssistantPhase.Idle)
            }
            AssistantPhase.Processing -> cancelCommand()
            AssistantPhase.Speaking -> stopSpeaking()
            AssistantPhase.Recording -> stopRecordingEarly()
        }
    }

    private fun handleRepeat() {
        if (_uiState.value.phase != AssistantPhase.Idle) {
            setError(VoiceError.Busy, null)
            return
        }
        viewModelScope.launch {
            val last = orchestrator.lastAnswer()
            if (last.isNullOrBlank()) {
                Log.d(TAG, "[Voice] Repeat requested with no previous answer")
                setError(VoiceError.NoPreviousAnswer, null)
            } else {
                Log.d(TAG, "[Voice] Repeating last answer")
                _uiState.value = _uiState.value.copy(phase = AssistantPhase.Speaking)
                textSpeaker.speak(last)
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase actions
    // ------------------------------------------------------------------

    private fun startListening() {
        _uiState.value = _uiState.value.copy(
            phase = AssistantPhase.Listening,
            error = null,
            errorMessage = null,
        )
        Log.d(TAG, "[Voice] Listening started")
        speechToText.startListening()
    }

    private fun cancelListening() {
        speechToText.cancel()
        _uiState.value = _uiState.value.copy(phase = AssistantPhase.Idle)
        Log.d(TAG, "[Voice] Listening cancelled")
    }

    private fun cancelCommand() {
        commandJob?.cancel()
        commandJob = null
        _uiState.value = _uiState.value.copy(
            phase = AssistantPhase.Idle,
            recordingStartedAtMillis = null,
        )
        Log.d(TAG, "[Voice] Command cancelled")
    }

    private fun stopSpeaking() {
        textSpeaker.stop()
        _uiState.value = _uiState.value.copy(phase = AssistantPhase.Idle)
        Log.d(TAG, "[Voice] Speaking stopped")
    }

    private fun stopRecordingEarly() {
        Log.d(TAG, "[Voice] Stopping recording early")
        orchestrator.stopVideoEarly()
    }

    private fun cancelActiveWork() {
        commandJob?.cancel()
        commandJob = null
        speechToText.cancel()
        textSpeaker.stop()
    }

    // ------------------------------------------------------------------
    // Event handling
    // ------------------------------------------------------------------

    private fun handleSpeechEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Ready, is SpeechEvent.Listening -> {
                if (_uiState.value.phase == AssistantPhase.Idle) {
                    _uiState.value = _uiState.value.copy(phase = AssistantPhase.Listening)
                }
            }
            is SpeechEvent.PartialResult -> Unit
            is SpeechEvent.FinalResult -> {
                if (_uiState.value.phase == AssistantPhase.Listening) {
                    if (event.text.isBlank()) {
                        Log.w(TAG, "[Voice] Empty final result")
                        _uiState.value = _uiState.value.copy(phase = AssistantPhase.Idle)
                        setError(VoiceError.NoMatch, null)
                    } else {
                        _uiState.value = _uiState.value.copy(phase = AssistantPhase.Idle)
                        submit(event.text.trim(), CommandSource.Voice)
                    }
                } else {
                    Log.w(TAG, "[Voice] Ignoring late final result")
                }
            }
            is SpeechEvent.Error -> {
                if (_uiState.value.phase == AssistantPhase.Listening) {
                    Log.w(TAG, "[Voice] Speech error: ${event.error}")
                    setError(event.error.toVoiceError(), null)
                }
            }
            is SpeechEvent.Cancelled -> {
                if (_uiState.value.phase == AssistantPhase.Listening) {
                    _uiState.value = _uiState.value.copy(phase = AssistantPhase.Idle)
                }
            }
        }
    }

    private fun handleSpeakerEvent(event: SpeakerEvent) {
        when (event) {
            is SpeakerEvent.Ready -> Unit
            is SpeakerEvent.Started -> Unit
            is SpeakerEvent.Finished -> {
                if (_uiState.value.phase == AssistantPhase.Speaking) {
                    _uiState.value = _uiState.value.copy(phase = AssistantPhase.Idle)
                }
            }
            is SpeakerEvent.Error -> {
                if (_uiState.value.phase == AssistantPhase.Speaking) {
                    Log.w(TAG, "[Voice] TTS error, keeping text answer")
                    _uiState.value = _uiState.value.copy(phase = AssistantPhase.Idle)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // State helpers
    // ------------------------------------------------------------------

    private fun setFailure(error: VoiceError, message: String?) {
        _uiState.value = VoiceUiState(
            phase = AssistantPhase.Idle,
            turns = _uiState.value.turns,
            error = error,
            errorMessage = message,
            mediaMessage = _uiState.value.mediaMessage,
        )
    }

    private fun setError(error: VoiceError, message: String?) {
        _uiState.value = _uiState.value.copy(error = error, errorMessage = message)
    }

    private fun CommandError.toVoiceError(): VoiceError = when (this) {
        CommandError.EmptyInput -> VoiceError.NoMatch
        is CommandError.Ambiguous -> VoiceError.AmbiguousCommand
        CommandError.UnsupportedAction -> VoiceError.UnsupportedAction
        CommandError.Unknown -> VoiceError.Unknown
    }

    private fun SpeechError.toVoiceError(): VoiceError = when (this) {
        SpeechError.PermissionDenied -> VoiceError.MicPermissionDenied
        SpeechError.NotAvailable, SpeechError.Busy -> VoiceError.RecognizerUnavailable
        SpeechError.NoMatch -> VoiceError.NoMatch
        SpeechError.Network -> VoiceError.SpeechNetwork
        SpeechError.Audio -> VoiceError.SpeechAudio
        SpeechError.Unknown -> VoiceError.Unknown
    }

    private fun VoiceError.isSpeechError(): Boolean = when (this) {
        VoiceError.MicPermissionDenied,
        VoiceError.RecognizerUnavailable,
        VoiceError.NoMatch,
        VoiceError.SpeechNetwork,
        VoiceError.SpeechAudio,
        -> true
        else -> false
    }

    private fun Throwable.toVoiceError(): VoiceError = when (this) {
        is VisionException -> when (error) {
            com.rayban.ai.domain.model.VisionError.NetworkError -> VoiceError.Network
            com.rayban.ai.domain.model.VisionError.Timeout -> VoiceError.Timeout
            com.rayban.ai.domain.model.VisionError.InvalidImage -> VoiceError.InvalidImage
            com.rayban.ai.domain.model.VisionError.ProviderUnavailable -> VoiceError.ProviderUnavailable
            com.rayban.ai.domain.model.VisionError.RateLimited -> VoiceError.RateLimited
            com.rayban.ai.domain.model.VisionError.Cancelled -> VoiceError.Cancelled
            com.rayban.ai.domain.model.VisionError.Unknown -> VoiceError.Unknown
        }
        is FrameCaptureException -> VoiceError.InvalidImage
        is MediaCaptureException -> when (error) {
            MediaCaptureError.AudioUnavailable -> VoiceError.MicPermissionDenied
            else -> VoiceError.MediaFailed
        }
        else -> VoiceError.Unknown
    }

    companion object {
        private const val TAG = "VoiceAssistantVM"
        const val PHOTO_SAVED_CONFIRMATION = "Photo saved."
        const val VIDEO_SAVED_CONFIRMATION = "Video saved."
    }
}