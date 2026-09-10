package com.team.smartglasses.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.team.smartglasses.core.Command
import com.team.smartglasses.core.CommandRouter
import com.team.smartglasses.core.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** ViewModel man hinh chinh. Nguoi 3. */
class HomeViewModel(
    private val router: CommandRouter = CommandRouter()
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun onUserInput(text: String) {
        viewModelScope.launch {
            when (val cmd = router.route(text)) {
                is Command.Ask -> _state.value = _state.value.copy(lastAnswer = "Hỏi: ${cmd.question}")
                Command.DescribeScene -> _state.value = _state.value.copy(lastAnswer = "Đang mô tả...")
                Command.TakePhoto -> _state.value = _state.value.copy(lastAnswer = "Đã chụp ảnh")
                else -> Unit
            }
        }
    }
}
