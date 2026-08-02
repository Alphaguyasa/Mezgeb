package et.alpha.mezgeb

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.navigation.MezgebDestination
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import javax.inject.Inject

/**
 * Decides which screen the app opens on.
 *
 * @param isResolved false only for the moment before the stored flag has been read.
 *   The activity renders nothing until it is true, which is briefer than the frame
 *   it would otherwise spend showing Today and then replacing it with onboarding.
 */
data class MainUiState(
    val isResolved: Boolean = false,
    val startDestination: MezgebDestination = MezgebDestination.Today,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    settings: SettingsRepository,
) : ViewModel() {

    /**
     * `take(1)` on purpose. This is a start destination, and a navigation graph
     * cannot have its start changed underneath it — finishing onboarding navigates,
     * it does not re-resolve. Without the take, completing onboarding would rebuild
     * the graph and throw the back stack away.
     */
    val state: StateFlow<MainUiState> = settings.observeOnboardingCompleted()
        .take(1)
        .map { completed ->
            MainUiState(
                isResolved = true,
                startDestination = if (completed) {
                    MezgebDestination.Today
                } else {
                    MezgebDestination.Onboarding
                },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MainUiState(),
        )
}
