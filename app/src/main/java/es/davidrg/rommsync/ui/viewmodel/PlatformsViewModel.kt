package es.davidrg.rommsync.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.davidrg.rommsync.data.repository.RomRepository
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
            runCatching {
                romRepository.configureApi(serverUrl, apiKey)
                romRepository.fetchAndCachePlatforms()
            }.onFailure { e ->
                _error.value = e.message ?: "Error al cargar plataformas"
            }
            _isLoading.value = false
        }
    }

    fun togglePlatformVisibility(platform: Platform) {
        viewModelScope.launch {
            romRepository.updatePlatformVisibility(platform.id, !platform.visible)
        }
    }
}
