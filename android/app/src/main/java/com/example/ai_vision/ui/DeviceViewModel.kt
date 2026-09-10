package com.example.ai_vision.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_vision.device.FakeGlassTransport
import com.example.ai_vision.device.GlassTransport
import com.example.ai_vision.device.protocol.CommandName
import com.example.ai_vision.device.protocol.EventName
import com.example.ai_vision.device.protocol.GlassCommand
import com.example.ai_vision.device.protocol.GlassStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.lang.Compiler.command
import java.util.UUID

class DeviceViewModel(
    private val transport: GlassTransport = FakeGlassTransport()
) : ViewModel() {
    var deviceState by mutableStateOf<DeviceState>(
        DeviceState.Disconnected
    )
        private set

    private var connectionJob: Job? = null
    private var commandJob: Job? = null

    var actionState by mutableStateOf<ActionState>(ActionState.Idle)
        private set

    var glassStatus by mutableStateOf<GlassStatus?>(null)
        private set
    fun connect() {
        if (deviceState == DeviceState.Connecting ||
            deviceState == DeviceState.Connected
        ) {
            return
        }
        actionState = ActionState.Idle
        deviceState = DeviceState.Connecting
        connectionJob = viewModelScope.launch {
            try {
                withTimeout(5_000) {
                    transport.connect()
                }
                deviceState = DeviceState.Connected
            } catch (e: TimeoutCancellationException) {
                transport.disconnect()
                deviceState = DeviceState.Error("Connection timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transport.disconnect()
                deviceState = DeviceState.Error(e.message ?: "Connection failed")
            }
        }
    }
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        commandJob?.cancel()
        commandJob = null
        transport.disconnect()
        deviceState = DeviceState.Disconnected
        actionState = ActionState.Idle
        glassStatus=null
    }

    fun ping() {
        if(deviceState != DeviceState.Connected){
            return
        }
        if(actionState == ActionState.Running){
            return
        }

        actionState = ActionState.Running
        commandJob = viewModelScope.launch {
            try {
                val command = GlassCommand(
                    requestId = UUID.randomUUID().toString(),
                    name = CommandName.PING
                )

                val response = withTimeout(3_000){
                    transport.send(command)
                }

                if(response.requestId == command.requestId &&
                    response.name == EventName.Pong){
                    actionState = ActionState.Success("PONG")
                }else{
                    actionState = ActionState.Error("Invalid response")
                }
            }catch (e: TimeoutCancellationException){
                actionState = ActionState.Error("Ping time out")
            }catch (e: CancellationException){
                throw e
            }catch (e: Exception){
                actionState = ActionState.Error(e.message ?: "PING failed")
            }
        }
    }

    fun getStatus(){
        if(deviceState != DeviceState.Connected){
            return
        }
        if(actionState == ActionState.Running){
            return
        }

        actionState = ActionState.Running
        commandJob = viewModelScope.launch {
            try {
                val command = GlassCommand(
                    requestId = UUID.randomUUID().toString(),
                    name = CommandName.GET_STATUS
                )

                val response = withTimeout(3_000){
                    transport.send(command)
                }

                if(response.requestId == command.requestId &&
                    response.name == EventName.Status &&
                    response.status != null){
                    glassStatus = response.status
                    actionState = ActionState.Success("Get status successful")
                }else{
                    actionState = ActionState.Error("Invalid response")
                }
            }catch (e: TimeoutCancellationException){
                actionState = ActionState.Error("Get status timed out")
            }catch (e: CancellationException){
                throw e
            }catch (e: Exception){
                actionState = ActionState.Error(
                    e.message ?: "Get status failed"
                )
            }
        }
    }
}
