package com.lagradost.cloudstream3.desktop.player.vlcj2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlayerUiState {
    data object Idle : PlayerUiState
    data class Initializing(val message: String = "") : PlayerUiState
    data class Playing(
        val positionMs: Long,
        val durationMs: Long,
        val isPaused: Boolean,
        val volume: Int,
    ) : PlayerUiState
    data class Error(val message: String, val recoverable: Boolean = false) : PlayerUiState
    data object Finished : PlayerUiState
    data class Reinitializing(val reason: String) : PlayerUiState
}

class PlayerStateFlow(private val engine: Vlcj2Engine) {
    private val _state = MutableStateFlow<PlayerUiState>(PlayerUiState.Idle)
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    fun launch(scope: CoroutineScope) {
        scope.launch(Dispatchers.Main) {
            while (true) {
                delay(250L)

                val pos = engine.positionMs
                val dur = engine.durationMs
                val playing = engine.isPlaying
                val finished = engine.isFinished
                val vol = engine.volume
                val reinit = engine.isReinitializing

                        val uiState = when {
                            reinit -> PlayerUiState.Reinitializing("Switching mode...")
                            playing && dur > 0L -> PlayerUiState.Playing(
                                positionMs = pos,
                                durationMs = dur,
                                isPaused = false,
                                volume = vol,
                            )
                            !playing && dur > 0L && !finished -> PlayerUiState.Playing(
                                positionMs = pos,
                                durationMs = dur,
                                isPaused = true,
                                volume = vol,
                            )
                            finished -> PlayerUiState.Finished
                            else -> PlayerUiState.Idle
                        }
                _state.value = uiState
            }
        }
    }
}
