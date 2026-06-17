package es.davidrg.rommsync.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.davidrg.rommsync.data.local.ServerConfig
import es.davidrg.rommsync.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConfigViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<ServerConfig> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ServerConfig("", "", "", 2),
        )

    fun setServerUrl(url: String) {
        viewModelScope.launch { settingsRepository.setServerUrl(url) }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch { settingsRepository.setApiKey(key) }
    }

    fun setRomsRootPath(path: String) {
        viewModelScope.launch { settingsRepository.setRomsRootPath(path) }
    }

    fun setMaxConcurrentDownloads(max: Int) {
        viewModelScope.launch { settingsRepository.setMaxConcurrentDownloads(max) }
    }
}
