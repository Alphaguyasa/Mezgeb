package et.alpha.mezgeb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.navigation.MezgebNavHost

/**
 * The app's only Activity. Everything else is a Compose destination inside
 * [MezgebNavHost].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MezgebTheme {
                MezgebNavHost()
            }
        }
    }
}
