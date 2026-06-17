package es.davidrg.rommsync.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.davidrg.rommsync.data.local.ServerConfig
import es.davidrg.rommsync.data.repository.RomRepository
import es.davidrg.rommsync.domain.model.DownloadStatus
import es.davidrg.rommsync.domain.model.Rom
import es.davidrg.rommsync.domain.model.RomWithStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /** IDs of ROMs currently in the download queue (enqueued or running). */
    private val _downloadingIds = MutableStateFlow<Set<Int>>(emptySet())
    val downloadingIds = _downloadingIds.asStateFlow()

    /** One-shot events for UI feedback (snackbars). */
    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    val downloadedIds: StateFlow<Set<Int>> = romRepository.getDownloadedRomIds()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet(),
        )

    val romsWithStatus: StateFlow<List<RomWithStatus>> = combine(
        _roms, downloadedIds, _downloadingIds,
    ) { roms, downloaded, downloading ->
        roms.map { rom ->
            val status = when {
                rom.id in downloading -> DownloadStatus.DOWNLOADING
                rom.id in downloaded -> DownloadStatus.DOWNLOADED
                else -> DownloadStatus.NOT_DOWNLOADED
            }
            RomWithStatus(rom = rom, status = status)
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

    /** Called by UI when user taps download. Marks as downloading immediately for instant feedback. */
    fun onDownloadEnqueued(romId: Int, romName: String) {
        _downloadingIds.value = _downloadingIds.value + romId
        viewModelScope.launch {
            _events.emit(LibraryEvent.DownloadStarted(romName))
        }
    }

    /** Called when a download completes or fails to clear the downloading state. */
    fun onDownloadResolved(romId: Int) {
        _downloadingIds.value = _downloadingIds.value - romId
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
                _events.emit(LibraryEvent.Error(e.message ?: "Error al cargar juegos"))
            }
            _isLoading.value = false
        }
    }
}

/** One-shot UI events emitted by the ViewModel. */
sealed class LibraryEvent {
    data class DownloadStarted(val romName: String) : LibraryEvent()
    data class Error(val message: String) : LibraryEvent()
}
