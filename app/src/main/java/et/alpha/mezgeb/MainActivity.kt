package et.alpha.mezgeb

import android.os.Bundle
import android.view.WindowManager
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

        // FLAG_SECURE for the whole window rather than per screen.
        //
        // 03-ARCHITECTURE.md section 8 asks for it on every screen showing amounts,
        // which in a single-activity app where every screen shows amounts is the same
        // set. Setting it once cannot be defeated by a navigation race, and there is no
        // moment during a transition where a screenshot would succeed. It also blocks
        // the recents-screen thumbnail, which is where a ledger is most likely to be
        // read over someone's shoulder.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

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
