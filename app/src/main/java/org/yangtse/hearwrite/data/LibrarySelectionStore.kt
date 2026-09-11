package org.yangtse.hearwrite.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 多选词库 state for 抽词听写 (Roadmap #9): which built-in lists the user
 * ticked, across categories (the category/list screens are separate
 * navigation destinations, so the selection cannot live in one of their
 * ViewModels). Process-scoped and in-memory, like [DictationSessionStore] —
 * a selection is composing work, not data.
 *
 * [selectedIds] keeps selection order (ids are `default_<category>_<label>`);
 * leaving multi-select mode clears it, so a hidden stale selection can never
 * resurface in a later visit.
 */
class LibrarySelectionStore {

    private val _active = MutableStateFlow(false)
    /** True while the library screens tick lists instead of opening them. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    /** Enter/leave multi-select mode; leaving drops the selection. */
    fun setActive(value: Boolean) {
        if (!value) _selectedIds.value = emptySet()
        _active.value = value
    }

    /** Tick/untick one list (selection order preserved). */
    fun toggle(id: String) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clear() {
        _selectedIds.value = emptySet()
    }
}
