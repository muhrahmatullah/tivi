/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.tivi.home.followed

import androidx.lifecycle.viewModelScope
import androidx.paging.PagedList
import app.tivi.TiviMvRxViewModel
import app.tivi.data.entities.RefreshType
import app.tivi.data.entities.SortOption
import app.tivi.data.entities.TiviShow
import app.tivi.data.resultentities.FollowedShowEntryWithShow
import app.tivi.domain.interactors.ChangeShowFollowStatus
import app.tivi.domain.interactors.UpdateFollowedShows
import app.tivi.domain.invoke
import app.tivi.domain.launchObserve
import app.tivi.domain.observers.ObservePagedFollowedShows
import app.tivi.domain.observers.ObserveTraktAuthState
import app.tivi.trakt.TraktAuthState
import app.tivi.util.ObservableLoadingCounter
import app.tivi.util.ShowStateSelector
import app.tivi.util.collectFrom
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FollowedViewModel @AssistedInject constructor(
    @Assisted initialState: FollowedViewState,
    private val updateFollowedShows: UpdateFollowedShows,
    private val observePagedFollowedShows: ObservePagedFollowedShows,
    private val observeTraktAuthState: ObserveTraktAuthState,
    private val changeShowFollowStatus: ChangeShowFollowStatus
) : TiviMvRxViewModel<FollowedViewState>(initialState) {
    private val boundaryCallback = object : PagedList.BoundaryCallback<FollowedShowEntryWithShow>() {
        override fun onZeroItemsLoaded() {
            setState { copy(isEmpty = filter.isNullOrEmpty()) }
        }

        override fun onItemAtEndLoaded(itemAtEnd: FollowedShowEntryWithShow) {
            setState { copy(isEmpty = false) }
        }

        override fun onItemAtFrontLoaded(itemAtFront: FollowedShowEntryWithShow) {
            setState { copy(isEmpty = false) }
        }
    }

    private val loadingState = ObservableLoadingCounter()
    private val showSelection = ShowStateSelector()

    init {
        viewModelScope.launch {
            loadingState.observable
                    .distinctUntilChanged()
                    .debounce(2000)
                    .execute {
                        copy(isLoading = it() ?: false)
                    }
        }

        viewModelScope.launchObserve(observePagedFollowedShows) {
            it.execute {
                copy(followedShows = it())
            }
        }

        viewModelScope.launch {
            showSelection.observeSelectedShowIds().collect {
                setState { copy(selectedShowIds = it) }
            }
        }

        viewModelScope.launch {
            showSelection.observeIsSelectionOpen().collect {
                setState { copy(selectionOpen = it) }
            }
        }

        viewModelScope.launchObserve(observeTraktAuthState) { flow ->
            flow.distinctUntilChanged().collect {
                if (it == TraktAuthState.LOGGED_IN) {
                    refreshFollowed(false)
                }
            }
        }
        observeTraktAuthState()

        // Set the available sorting options
        setState {
            copy(availableSorts = listOf(
                    SortOption.SUPER_SORT,
                    SortOption.LAST_WATCHED,
                    SortOption.ALPHABETICAL,
                    SortOption.DATE_ADDED
            ))
        }

        // Subscribe to state changes, so update the observed data source
        subscribe(::updateDataSource)

        refresh(false)
    }

    private fun updateDataSource(state: FollowedViewState) {
        observePagedFollowedShows(
                ObservePagedFollowedShows.Parameters(
                        sort = state.sort,
                        filter = state.filter,
                        pagingConfig = PAGING_CONFIG,
                        boundaryCallback = boundaryCallback
                )
        )
    }

    fun refresh() = refresh(true)

    private fun refresh(fromUser: Boolean) {
        viewModelScope.launch {
            observeTraktAuthState.observe()
                    .first { it == TraktAuthState.LOGGED_IN }
                    .also { refreshFollowed(fromUser) }
        }
    }

    fun setFilter(filter: String) {
        setState { copy(filter = filter, filterActive = filter.isNotEmpty()) }
    }

    fun setSort(sort: SortOption) = setState {
        require(availableSorts.contains(sort))
        copy(sort = sort)
    }

    fun clearSelection() {
        showSelection.clearSelection()
    }

    fun onItemClick(show: TiviShow): Boolean {
        return showSelection.onItemClick(show)
    }

    fun onItemLongClick(show: TiviShow): Boolean {
        return showSelection.onItemLongClick(show)
    }

    fun unfollowSelectedShows() {
        changeShowFollowStatus(
                ChangeShowFollowStatus.Params(
                        showSelection.getSelectedShowIds(),
                        ChangeShowFollowStatus.Action.UNFOLLOW)
        )
        showSelection.clearSelection()
    }

    private fun refreshFollowed(fromInteraction: Boolean) {
        updateFollowedShows(UpdateFollowedShows.Params(fromInteraction, RefreshType.QUICK)).also {
            viewModelScope.launch {
                loadingState.collectFrom(it)
            }
        }
    }

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: FollowedViewState): FollowedViewModel
    }

    companion object : MvRxViewModelFactory<FollowedViewModel, FollowedViewState> {
        private val PAGING_CONFIG = PagedList.Config.Builder()
                .setPageSize(60)
                .setPrefetchDistance(20)
                .setEnablePlaceholders(false)
                .build()

        override fun create(viewModelContext: ViewModelContext, state: FollowedViewState): FollowedViewModel? {
            val fragment: FollowedFragment = (viewModelContext as FragmentViewModelContext).fragment()
            return fragment.followedViewModelFactory.create(state)
        }
    }
}
