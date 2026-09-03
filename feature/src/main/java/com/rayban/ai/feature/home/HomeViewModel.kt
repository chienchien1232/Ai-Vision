package com.rayban.ai.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rayban.ai.core.common.ui.state.UiState
import com.rayban.ai.domain.usecase.ObserveDeviceStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeDeviceStatus: ObserveDeviceStatusUseCase,
) : ViewModel() {
    val uiState: StateFlow<UiState<HomeUiState>> = observeDeviceStatus()
        .map<com.rayban.ai.domain.model.DeviceConnectionState, UiState<HomeUiState>> { connectionState ->
            UiState.Content(HomeUiState(connectionState))
        }
        .onStart {
            emit(UiState.Loading)
        }
        .catch {
            emit(UiState.Error("Unable to read device status."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UiState.Loading,
        )
}
