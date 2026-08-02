package et.alpha.mezgeb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.navigation.MezgebNavHost

/**
 * The app's only Activity. Everything else is a Compose destination inside
 * [MezgebNavHost].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MezgebTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                // Nothing is drawn until the start destination is known — one DataStore
                // read. Showing Today first and swapping it for onboarding would be a
                // visible flicker on exactly the launch that matters most.
                if (state.isResolved) {
                    MezgebNavHost(startDestination = state.startDestination)
                }
            }
        }
    }
}
