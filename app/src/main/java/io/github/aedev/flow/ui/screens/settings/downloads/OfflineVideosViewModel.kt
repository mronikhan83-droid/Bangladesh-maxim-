package io.github.aedev.flow.ui.screens.settings.downloads

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.offline.OfflineFillResult
import io.github.aedev.flow.data.offline.OfflineShortsRepository
import io.github.aedev.flow.data.video.downloader.work.DownloadController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val DEFAULT_OFFLINE_TARGET = 50
val OfflineTargets = listOf(20, 50, 100)

/** A one-line result of the last action, shown in the sheet until it is dismissed or replaced. */
sealed interface OfflineNotice {
    data class Started(
        val count: Int,
    ) : OfflineNotice

    data object NothingNeeded : OfflineNotice

    data object NoCandidates : OfflineNotice

    data class Cleared(
        val count: Int,
    ) : OfflineNotice
}

@Immutable
data class OfflineVideosUiState(
    val count: Int = 0,
    val bytes: Long = 0L,
    val thumbnails: List<String> = emptyList(),
    val queued: Int = 0,
    val selected: Int = DEFAULT_OFFLINE_TARGET,
    val busy: Boolean = false,
    val notice: OfflineNotice? = null,
)

@HiltViewModel
class OfflineVideosViewModel
    @Inject
    constructor(
        private val offline: OfflineShortsRepository,
        private val preferences: PlayerPreferences,
        private val downloadController: DownloadController,
    ) : ViewModel() {
        private val _state = MutableStateFlow(OfflineVideosUiState())
        val state: StateFlow<OfflineVideosUiState> = _state.asStateFlow()

        val wifiOnly: StateFlow<Boolean> =
            preferences.downloadOverWifiOnly.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        init {
            viewModelScope.launch {
                // Every download that starts or finishes changes the queue, so this also keeps the count live.
                offline.queueSize().collect { size ->
                    _state.update { it.copy(queued = size) }
                    refreshStats()
                }
            }
        }

        fun select(count: Int) = _state.update { it.copy(selected = count) }

        fun setWifiOnly(value: Boolean) {
            viewModelScope.launch {
                preferences.setDownloadOverWifiOnly(value)
                downloadController.applyNetworkPolicy()
            }
        }

        fun download() {
            if (_state.value.busy) return
            _state.update { it.copy(busy = true, notice = null) }
            viewModelScope.launch {
                val fill = offline.fill(_state.value.selected)
                val notice =
                    when (fill.result) {
                        OfflineFillResult.QUEUED -> OfflineNotice.Started(fill.queued)
                        OfflineFillResult.NOTHING_NEEDED -> OfflineNotice.NothingNeeded
                        OfflineFillResult.NO_CANDIDATES -> OfflineNotice.NoCandidates
                    }
                _state.update { it.copy(busy = false, notice = notice) }
                refreshStats()
            }
        }

        fun clear() {
            viewModelScope.launch {
                val removed = offline.clear()
                _state.update { it.copy(notice = OfflineNotice.Cleared(removed)) }
                refreshStats()
            }
        }

        fun dismissNotice() = _state.update { it.copy(notice = null) }

        private suspend fun refreshStats() {
            val stats = offline.stats()
            _state.update { it.copy(count = stats.count, bytes = stats.bytes, thumbnails = stats.thumbnails) }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
