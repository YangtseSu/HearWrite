package org.yangtse.hearwrite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.LibraryCategory
import org.yangtse.hearwrite.data.LibraryList
import org.yangtse.hearwrite.data.LibrarySearchResult
import org.yangtse.hearwrite.domain.WordEntry

/** Search UI state: idle (no query), loading, or the finished result. */
sealed interface LibrarySearchState {
    data object Idle : LibrarySearchState
    data object Loading : LibrarySearchState
    data class Done(val result: LibrarySearchResult) : LibrarySearchState
}

/** Library browse screen: category list + full-library search (label and word hits). */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as HearWriteApplication).libraryRepository

    private val _categories = MutableStateFlow<List<LibraryCategory>?>(null)
    /** null = still loading (asset scan). */
    val categories: StateFlow<List<LibraryCategory>?> = _categories.asStateFlow()

    private val _queryText = MutableStateFlow("")
    val queryText: StateFlow<String> = _queryText.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchState: StateFlow<LibrarySearchState> = _queryText
        .debounce(250)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf<LibrarySearchState>(LibrarySearchState.Idle)
            else flow<LibrarySearchState> {
                emit(LibrarySearchState.Loading)
                emit(LibrarySearchState.Done(repository.search(q)))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySearchState.Idle)

    init {
        viewModelScope.launch { _categories.value = repository.categories() }
    }

    fun onQueryChange(newQuery: String) {
        _queryText.value = newQuery
    }
}

/** One category's lists (label ordering from the domain comparator). */
class LibraryListsViewModel(
    application: Application,
    handle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val app = application as HearWriteApplication
    private val repository = app.libraryRepository
    private val favoritesRepository = app.favoritesRepository
    val category: String = checkNotNull(handle["category"])

    private val _lists = MutableStateFlow<List<LibraryList>?>(null)
    /** null = still loading. */
    val lists: StateFlow<List<LibraryList>?> = _lists.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    /** Favorited entry ids of this screen (stars on list rows). */
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    init {
        viewModelScope.launch { _lists.value = repository.lists(category) }
        viewModelScope.launch {
            favoritesRepository.observeIds().collect { _favoriteIds.value = it }
        }
    }

    /** Toggle the favorite state of a built-in list entry. */
    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            try {
                favoritesRepository.toggle(id)
            } catch (e: Exception) {
                // Best effort; the star follows the Room state on change.
            }
        }
    }
}

/** Parsed entries of one list for the preview screen. */
class LibraryPreviewViewModel(
    application: Application,
    handle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = (application as HearWriteApplication).libraryRepository
    val category: String = checkNotNull(handle["category"])
    val label: String = checkNotNull(handle["label"])

    private val _entries = MutableStateFlow<List<WordEntry>?>(null)
    /** null = still loading. */
    val entries: StateFlow<List<WordEntry>?> = _entries.asStateFlow()

    init {
        viewModelScope.launch {
            _entries.value = repository.entries(LibraryList(category, label))
        }
    }
}
