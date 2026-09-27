package app.nudge.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nudge.core.domain.usecase.SearchTasksUseCase
import app.nudge.core.domain.usecase.TaskGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Results for [query] (trimmed); [query] is empty before the user types. */
data class SearchResults(val query: String = "", val groups: List<TaskGroup> = emptyList())

/** FR-84, 03 §3.8: full-text search over titles and notes, debounced 200 ms, grouped by list. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    searchTasks: SearchTasksUseCase,
) : ViewModel() {
    val query: StateFlow<String> = savedStateHandle.getStateFlow(KEY_QUERY, "")

    val results: StateFlow<SearchResults> = query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0L else DEBOUNCE_MS }
        .flatMapLatest { q ->
            if (q.isEmpty()) flowOf(SearchResults()) else searchTasks(flowOf(q)).map { SearchResults(q, it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    fun onQueryChange(value: String) {
        savedStateHandle[KEY_QUERY] = value
    }

    private companion object {
        const val KEY_QUERY = "query"
        const val DEBOUNCE_MS = 200L
    }
}
