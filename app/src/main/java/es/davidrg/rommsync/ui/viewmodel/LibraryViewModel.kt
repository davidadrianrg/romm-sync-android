package es.davidrg.rommsync.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.davidrg.rommsync.data.local.ServerConfig
import es.davidrg.rommsync.data.repository.RomRepository
import es.davidrg.rommsync.domain.model.ApiResult
import es.davidrg.rommsync.domain.model.DownloadStatus
import es.davidrg.rommsync.domain.model.ErrorKind
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

    /** Género seleccionado actualmente para filtrado server-side (null = sin filtro). */
    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre = _selectedGenre.asStateFlow()

    /** Región seleccionada actualmente para filtrado server-side (null = sin filtro). */
    private val _selectedRegion = MutableStateFlow<String?>(null)
    val selectedRegion = _selectedRegion.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    /** True when actively loading the next page (not the first). */
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()

    /** False when we've fetched all pages for this platform. */
    private val _hasMore = MutableStateFlow(true)
    val hasMore = _hasMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** Current server-side search term (null = no search, empty page browsing). */
    private var currentSearch: String? = null

    companion object {
        private const val PAGE_SIZE = 50
    }

    /**
     * Géneros disponibles extraídos de los ROMs ya cargados.
     * Se actualiza automáticamente cuando cambia [_roms].
     */
    val availableGenres: StateFlow<List<String>> = _roms
        .map { roms -> roms.flatMap { it.genres }.distinct().sorted() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    /**
     * Regiones disponibles extraídas de los ROMs ya cargados.
     * Se actualiza automáticamente cuando cambia [_roms].
     */
    val availableRegions: StateFlow<List<String>> = _roms
        .map { roms -> roms.flatMap { it.regions }.distinct().sorted() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    /** True cuando hay algún filtro avanzado activo (género o región). */
    val hasActiveFilters: StateFlow<Boolean> = combine(_selectedGenre, _selectedRegion) { genre, region ->
        !genre.isNullOrBlank() || !region.isNullOrBlank()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )

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
        currentSearch = null
        _selectedGenre.value = null
        _selectedRegion.value = null
        loadRoms(serverUrl, apiKey, reset = true)
    }

    /**
     * Server-side search: fetches ROMs matching [query] from the ENTIRE platform,
     * not just the already-loaded pages. Replaces the current list.
     * Pass empty string to clear search and reload full list.
     */
    fun searchRoms(query: String, serverUrl: String, apiKey: String) {
        currentSearch = query.ifBlank { null }
        loadRoms(serverUrl, apiKey, reset = true)
    }

    /**
     * Aplica filtros avanzados (género y/o región) y recarga los ROMs desde
     * el servidor con los nuevos parámetros. Pasa null/vacío para quitar un filtro.
     */
    fun applyFilters(serverUrl: String, apiKey: String, genre: String?, region: String?) {
        _selectedGenre.value = genre?.takeIf { it.isNotBlank() }
        _selectedRegion.value = region?.takeIf { it.isNotBlank() }
        loadRoms(serverUrl, apiKey, reset = true)
    }

    /** Limpia todos los filtros avanzados y recarga. */
    fun clearFilters(serverUrl: String, apiKey: String) {
        _selectedGenre.value = null
        _selectedRegion.value = null
        loadRoms(serverUrl, apiKey, reset = true)
    }

    /** Called by infinite scroll when user nears the bottom. */
    fun loadMore(serverUrl: String, apiKey: String) {
        if (_isLoadingMore.value || !_hasMore.value || _isLoading.value) return
        loadRoms(serverUrl, apiKey, reset = false)
    }

    /** Called by UI when user taps download. Emits snackbar feedback. */
    fun onDownloadEnqueued(romName: String) {
        viewModelScope.launch {
            _events.emit(LibraryEvent.DownloadStarted(romName))
        }
    }

    /**
     * Batch download: enqueues every ROM in [roms] that is not already downloading.
     * Used by the "Descargar faltantes" action in the library toolbar.
     *
     * @param roms        ROMs the user wants to download (usually the visible
     *                    NOT_DOWNLOADED ones from the current platform page).
     * @param serverUrl   Base URL of the RomM server.
     */
    fun enqueueBatchDownload(roms: List<Rom>, serverUrl: String) {
        val manager = downloadManager ?: run {
            viewModelScope.launch { _events.emit(LibraryEvent.Error("DownloadManager no disponible")) }
            return
        }
        // Skip ROMs that are already enqueued/running to avoid duplicate work.
        val currentlyDownloading = activeDownloads.value
        val toEnqueue = roms.filter { it.id !in currentlyDownloading }
        if (toEnqueue.isEmpty()) {
            viewModelScope.launch { _events.emit(LibraryEvent.Error("No hay ROMs nuevos para descargar")) }
            return
        }
        viewModelScope.launch {
            toEnqueue.forEach { rom -> manager.enqueueDownload(rom = rom, serverUrl = serverUrl) }
            _events.emit(LibraryEvent.BatchDownloadStarted(toEnqueue.size))
        }
    }

    /**
     * Pull-to-refresh: reloads the first page for the currently selected
     * platform keeping the active search term (if any). Does not change the
     * selected platform.
     */
    fun refresh(serverUrl: String, apiKey: String) {
        // Only reload if a platform is selected; otherwise there's nothing to refresh.
        if (_selectedPlatformId.value == null) return
        loadRoms(serverUrl, apiKey, reset = true)
    }

    private fun loadRoms(serverUrl: String, apiKey: String, reset: Boolean) {
        val platformId = _selectedPlatformId.value ?: return
        viewModelScope.launch {
            if (reset) {
                _isLoading.value = true
                _hasMore.value = true
            } else {
                _isLoadingMore.value = true
            }
            _error.value = null
            romRepository.configureApi(serverUrl, apiKey)
            val offset = if (reset) 0 else _roms.value.size
            when (val result = romRepository.fetchRoms(
                platformId,
                offset = offset,
                search = currentSearch,
                genre = _selectedGenre.value,
                region = _selectedRegion.value,
            )) {
                is ApiResult.Success -> {
                    _roms.value = if (reset) result.data else _roms.value + result.data
                    _hasMore.value = result.data.size >= PAGE_SIZE
                }
                is ApiResult.Error -> {
                    val userMessage = result.kind.toUserMessage()
                    _error.value = userMessage
                    _events.emit(LibraryEvent.Error(userMessage))
                }
            }
            _isLoading.value = false
            _isLoadingMore.value = false
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

/** One-shot UI events emitted by the ViewModel. */
sealed class LibraryEvent {
    data class DownloadStarted(val romName: String) : LibraryEvent()
    data class BatchDownloadStarted(val count: Int) : LibraryEvent()
    data class Error(val message: String) : LibraryEvent()
}
