package com.airplay.streamer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airplay.streamer.R
import com.airplay.streamer.discovery.AirPlayDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val devices: List<AirPlayDevice> = emptyList(),
    val selectedDevice: AirPlayDevice? = null,
    val isStreaming: Boolean = false,
    val statusMessage: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val repository = com.airplay.streamer.discovery.DiscoveryRepository.getInstance(application)

    private val mediaInfoTracker = com.airplay.streamer.service.MediaInfoTracker(application)
    val mediaInfo = mediaInfoTracker.mediaInfo

    // Manual devices survive the discovery refresh that would otherwise
    // overwrite `_uiState.value.devices` on the next repository emission.
    private val manualDevices = mutableMapOf<String, AirPlayDevice>()

    init {
        repository.startDiscovery()
        mediaInfoTracker.start()

        viewModelScope.launch {
            repository.devices.collect { devices ->
                refreshDevices(devices)
            }
        }
    }

    private fun refreshDevices(discovered: List<AirPlayDevice>) {
        val app = getApplication<Application>()
        // Merge manually-added devices into the discovered list. Manual devices
        // are keyed by host:port and survive discovery refreshes.
        val merged = discovered + manualDevices.values.filter { manual ->
            discovered.none { it.host == manual.host && it.port == manual.port }
        }

        val message = if (merged.isEmpty()) {
            app.getString(R.string.searching_speakers)
        } else {
            app.resources.getQuantityString(R.plurals.found_speakers, merged.size, merged.size)
        }

        _uiState.value = _uiState.value.copy(
            devices = merged,
            statusMessage = message
        )
    }

    fun selectDevice(device: AirPlayDevice) {
        val current = _uiState.value.selectedDevice
        if (current?.host == device.host && current.port == device.port) {
            // Deselect
            _uiState.value = _uiState.value.copy(selectedDevice = null)
        } else {
            _uiState.value = _uiState.value.copy(selectedDevice = device)
        }
    }

    fun addManualDevice(device: AirPlayDevice) {
        manualDevices["${device.host}:${device.port}"] = device
        // Immediately reflect the manual device in the UI state so the caller
        // can select it right away (the repository may not emit again soon).
        refreshDevices(repository.devices.value)
    }

    fun refreshDiscovery() {
        repository.refresh()
    }

    fun setStreamingState(isStreaming: Boolean) {
        _uiState.value = _uiState.value.copy(isStreaming = isStreaming)
    }

    fun togglePlayback() {
        mediaInfoTracker.togglePlayback()
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopDiscovery()
        mediaInfoTracker.stop()
    }
}
