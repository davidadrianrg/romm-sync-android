package es.davidrg.rommsync.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.davidrg.rommsync.data.local.ServerConfig
import es.davidrg.rommsync.data.repository.RomRepository
import es.davidrg.rommsync.domain.model.DownloadStatus
import es.davidrg.rommsync.domain.model.Rom
import es.davidrg.rommsync.domain.model.RomWithStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val romRepository: RomRepository,
) : ViewModel() {

    private val _selectedPlatformId = MutableStateFlow<Int?>(null)
    val selectedPlatformId = _selectedPlatformId.asStateFlow()

    private val _roms = MutableStateFlow<List<Rom>>(emptyList())
    val roms = _roms.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val downloadedIds: StateFlow<Set<Int>> = romRepository.getDownloadedRomIds()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet(),
        )

    val romsWithStatus: StateFlow<List<RomWithStatus>> = combine(_roms, downloadedIds) { roms, ids ->
        roms.map { rom ->
            RomWithStatus(
                rom = rom,
                status = if (rom.id in ids) DownloadStatus.DOWNLOADED else DownloadStatus.NOT_DOWNLOADED,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
    )

    fun selectPlatform(platformId: Int, serverUrl: String, apiKey: String) {
        _selectedPlatformId.value = platformId
        loadRoms(serverUrl, apiKey, reset = true)
    }

    fun loadMore(serverUrl: String, apiKey: String) {
        loadRoms(serverUrl, apiKey, reset = false)
    }

    private fun loadRoms(serverUrl: String, apiKey: String, reset: Boolean) {
        val platformId = _selectedPlatformId.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            runCatching {
                romRepository.configureApi(serverUrl, apiKey)
                val offset = if (reset) 0 else _roms.value.size
                val newRoms = romRepository.fetchRoms(platformId, offset = offset)
                _roms.value = if (reset) newRoms else _roms.value + newRoms
            }.onFailure { e ->
                _error.value = e.message ?: "Error al cargar juegos"
            }
            _isLoading.value = false
        }
    }
}
