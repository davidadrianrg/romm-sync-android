package es.davidrg.rommsync.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.davidrg.rommsync.data.local.ServerConfig
import es.davidrg.rommsync.data.repository.RomRepository
import es.davidrg.rommsync.domain.model.DownloadStatus
import es.davidrg.rommsync.domain.model.Rom
import es.davidrg.rommsync.domain.model.RomWithStatus
import es.davidrg.rommsync.download.DownloadManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val romRepository: RomRepository,
    private val downloadManager: DownloadManager? = null,
) : ViewModel() {

    private val _selectedPlatformId = MutableStateFlow<Int?>(null)
    val selectedPlatformId = _selectedPlatformId.asStateFlow()

    private val _roms = MutableStateFlow<List<Rom>>(emptyList())
    val roms = _roms.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** One-shot events for UI feedback (snackbars). */
    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    val downloadedIds: StateFlow<Set<Int>> = romRepository.getDownloadedRomIds()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet(),
        )

    /**
     * Live set of ROM IDs currently downloading (ENQUEUED or RUNNING).
     * Driven by WorkManager observeDownloads() — auto-updates when downloads
     * complete or fail, so the card spinner resolves to a checkmark.
     */
    private val activeDownloads: StateFlow<Set<Int>> =
        (downloadManager?.observeDownloads() ?: kotlinx.coroutines.flow.flowOf(emptyList<es.davidrg.rommsync.domain.model.DownloadTask>()))
            .map { tasks ->
                tasks.filter { it.isRunning || (!it.isCompleted && !it.isFailed) }
                    .map { it.romId }
                    .toSet()
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptySet(),
            )

    val romsWithStatus: StateFlow<List<RomWithStatus>> = combine(
        _roms, downloadedIds, activeDownloads,
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

    /** Called by UI when user taps download. Emits snackbar feedback. */
    fun onDownloadEnqueued(romName: String) {
        viewModelScope.launch {
            _events.emit(LibraryEvent.DownloadStarted(romName))
        }
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
