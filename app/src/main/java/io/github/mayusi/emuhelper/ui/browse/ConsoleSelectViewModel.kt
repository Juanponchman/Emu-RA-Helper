package io.github.mayusi.emuhelper.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.source.CatalogRepository
import io.github.mayusi.emuhelper.data.source.RefreshResult
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConsoleSelectViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    val lastSelectedConsoles: StateFlow<Set<String>> =
        settings.lastSelectedConsoles.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun saveSelectedConsoles(consoles: Set<String>) {
        viewModelScope.launch {
            settings.setLastSelectedConsoles(consoles)
        }
    }

    // ---- Favorite consoles -----------------------------------------------

    val favoriteConsoles: StateFlow<Set<String>> =
        settings.favoriteConsoles.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleFavorite(key: String) {
        viewModelScope.launch {
            settings.toggleFavoriteConsole(key)
        }
    }

    // ---- Remote catalog refresh ------------------------------------------

    /** True while a refresh network call is in progress. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** One-shot event emitted after a manual refresh; null when idle. */
    private val _refreshResult = MutableStateFlow<RefreshResult?>(null)
    val refreshResult: StateFlow<RefreshResult?> = _refreshResult.asStateFlow()

    fun refreshCatalog() {
        viewModelScope.launch {
            _refreshResult.value = null
            _isRefreshing.value = true
            try {
                val result = catalogRepository.refresh()
                _refreshResult.value = result
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Clear the result after the UI has consumed it (prevents re-showing on recomposition). */
    fun clearRefreshResult() {
        _refreshResult.value = null
    }
}
