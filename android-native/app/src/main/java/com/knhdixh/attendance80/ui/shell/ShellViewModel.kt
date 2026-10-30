package com.knhdixh.attendance80.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knhdixh.attendance80.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

data class ShellState(
    val data: com.knhdixh.attendance80.net.AppData? = null,
    val selected: List<Long> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val session: String? = null,
    val lastSynced: Long? = null,
)

class ShellViewModel(private val container: AppContainer) : ViewModel() {
    val state = combine(container.dataRepository.sessionSnapshot, container.calendarScope.selected,
        container.dataRepository.loading, container.dataRepository.error) { owned, selected, loading, error ->
        ShellState(owned.second?.let { container.calendarScope.apply(it, selected) }, selected, loading, error, owned.first, owned.third)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellState())

    val clock get() = container.clock

    fun refresh() { viewModelScope.launch { container.dataRepository.refresh() } }
    fun toggleScope(value: String) {
        val calendars = container.dataRepository.snapshot.value?.calendars ?: return
        viewModelScope.launch { container.calendarScope.toggle(value, calendars) }
    }
    fun signOut() { viewModelScope.launch { container.authRepository.logout() } }
}
