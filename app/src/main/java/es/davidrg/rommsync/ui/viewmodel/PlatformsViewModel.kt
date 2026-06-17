package es.davidrg.rommsync.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.davidrg.rommsync.data.repository.RomRepository
import es.davidrg.rommsync.domain.model.ApiResult
import es.davidrg.rommsync.domain.model.ErrorKind
import es.davidrg.rommsync.domain.model.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlatformsViewModel(
    private val romRepository: RomRepository,
) : ViewModel() {

    val platforms: StateFlow<List<Platform>> = romRepository.getCachedPlatforms()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun refreshPlatforms(serverUrl: String, apiKey: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            romRepository.configureApi(serverUrl, apiKey)
            when (val result = romRepository.fetchAndCachePlatforms()) {
                is ApiResult.Success -> {
                    // platforms flow auto-refreshes from Room
                }
                is ApiResult.Error -> {
                    _error.value = result.kind.toUserMessage()
                }
            }
            _isLoading.value = false
        }
    }

    fun togglePlatformVisibility(platform: Platform) {
        viewModelScope.launch {
            romRepository.updatePlatformVisibility(platform.id, !platform.visible)
        }
    }

    fun setAllVisible(visible: Boolean) {
        viewModelScope.launch {
            platforms.value.forEach { platform ->
                if (platform.visible != visible) {
                    romRepository.updatePlatformVisibility(platform.id, visible)
                }
            }
        }
    }
}

/**
 * Maps [ErrorKind] to user-facing Spanish messages.
 */
private fun ErrorKind.toUserMessage(): String = when (this) {
    ErrorKind.NETWORK -> "Sin conexión al servidor"
    ErrorKind.AUTH -> "API Key inválida o sin permisos"
    ErrorKind.NOT_FOUND -> "No encontrado"
    ErrorKind.SERVER -> "Error del servidor (500)"
    ErrorKind.UNKNOWN -> "Error desconocido"
}
