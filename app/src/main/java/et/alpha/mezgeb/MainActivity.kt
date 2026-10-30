package et.alpha.mezgeb

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import et.alpha.mezgeb.data.demo.DemoModeStore
import et.alpha.mezgeb.data.settings.LanguagePreference
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.LocalDemoMode
import et.alpha.mezgeb.domain.lock.AppLockState
import et.alpha.mezgeb.navigation.MezgebNavHost
import et.alpha.mezgeb.ui.lock.LockRoute
import javax.inject.Inject

/**
 * The app's only Activity. Everything else is a Compose destination inside
 * [MezgebNavHost].
 *
 * A [FragmentActivity] rather than a bare `ComponentActivity`, because
 * `BiometricPrompt` hosts itself in a fragment and will not attach to anything else.
 * The single-activity design is otherwise untouched.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var lockState: AppLockState

    @Inject lateinit var demoMode: DemoModeStore

    /**
     * Applies the language override before any resource is resolved.
     *
     * [LanguagePreference] is constructed here rather than injected because this runs
     * before `super.onCreate`, which is where Hilt performs field injection. It holds a
     * Context and a SharedPreferences file and nothing else, so a second instance is
     * not a second source of truth.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguagePreference(newBase).wrap(newBase))
    }

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
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        enableEdgeToEdge()
        setContent {
            MezgebTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val isLocked by lockState.isLocked.collectAsStateWithLifecycle()

                // Read once, here. The flag cannot change while the process lives —
                // switching demo mode restarts the app precisely because the database
                // singleton is chosen from it at startup.
                CompositionLocalProvider(LocalDemoMode provides demoMode.isEnabled()) {
                    // The lock is drawn *instead of* the graph, not on top of it. A
                    // ledger composed behind a lock screen is one that a transition, a
                    // resize or a recents thumbnail can still show.
                    when {
                        isLocked -> LockRoute()

                        // Nothing is drawn until the start destination is known — one
                        // DataStore read. Showing Today first and swapping it for
                        // onboarding would be a visible flicker on exactly the launch
                        // that matters most.
                        state.isResolved ->
                            MezgebNavHost(startDestination = state.startDestination)
                    }
                }
            }
        }
    }

    /**
     * The timeout is measured from here, not from a Compose lifecycle observer: the
     * question is whether the *app* left the foreground, and a composable that has been
     * disposed cannot answer it. A stop caused by rotation or a language change is not
     * the app leaving the foreground, so it is passed on as such.
     */
    override fun onStop() {
        super.onStop()
        lockState.onBackgrounded(changingConfigurations = isChangingConfigurations)
    }

    override fun onStart() {
        super.onStart()
        lockState.onForegrounded()
    }
}
