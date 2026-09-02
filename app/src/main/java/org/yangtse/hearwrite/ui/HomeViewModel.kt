package org.yangtse.hearwrite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.domain.parseWords

/** Debounce for draft persistence; the flush on dispose covers the tail. */
private const val DRAFT_DEBOUNCE_MS = 500L

/**
 * Home: pasted word list with the persisted draft (500 ms debounce + flush on
 * dispose), start options 起始序号 (clamped when the list shrinks) and 随机顺序
 * (session-local Fisher–Yates). Starting prepares slice → shuffle and hands
 * the lines to the dictation session.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = (application as HearWriteApplication).settingsRepository

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _startIndex = MutableStateFlow(0)
    /** Index into the parsed word list where dictation starts (0-based). */
    val startIndex: StateFlow<Int> = _startIndex.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    /** Live parsed count of the draft. */
    val wordCount: StateFlow<Int> = _draft.map { parseWords(it).size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        // Seed the draft from the persisted value, then watch for changes.
        viewModelScope.launch {
            _draft.value = settings.draft.first()
        }
        // Persist debounced; flushDraft() covers the pending tail on dispose.
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            _draft.debounce(DRAFT_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { settings.setDraft(it) }
        }
        // Clamp the start index whenever the list shrinks below it.
        viewModelScope.launch {
            wordCount.collect { count ->
                if (count == 0) {
                    _startIndex.value = 0
                } else if (_startIndex.value >= count) {
                    _startIndex.value = count - 1
                }
            }
        }
    }

    fun onDraftChange(value: String) {
        _draft.value = value
    }

    fun fillSample(text: String) {
        _draft.value = text
        _startIndex.value = 0
        viewModelScope.launch { settings.setDraft(text) }
    }

    fun clearDraft() = onDraftChange("")

    fun adjustStartIndex(delta: Int) {
        val count = wordCount.value
        if (count == 0) return
        _startIndex.value = (_startIndex.value + delta).coerceIn(0, count - 1)
    }

    fun onShuffleChange(on: Boolean) {
        _shuffle.value = on
    }

    /** Flush the pending debounced draft (DisposableEffect.onDispose). */
    fun flushDraft() {
        viewModelScope.launch { settings.setDraft(_draft.value) }
    }

    /**
     * Prepare the dictation list: slice from the clamped 起始序号, then apply
     * 随机顺序 (Fisher–Yates via shuffled). Returns null when there is nothing
     * to dictate; the session-local options are not persisted.
     */
    fun prepareLines(): List<String>? {
        val all = parseWords(_draft.value)
        if (all.isEmpty()) return null
        val clamped = _startIndex.value.coerceIn(0, all.size - 1)
        var lines = all.subList(clamped, all.size)
        if (_shuffle.value) lines = lines.shuffled()
        return lines
    }
}
