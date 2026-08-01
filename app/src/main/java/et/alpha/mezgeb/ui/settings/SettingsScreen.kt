package et.alpha.mezgeb.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.BackButton
import et.alpha.mezgeb.designsystem.component.EmptyState
import et.alpha.mezgeb.designsystem.component.MezgebScaffold

/** Session 1 placeholder. Real settings arrive alongside the features they control. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MezgebScaffold(
        title = stringResource(R.string.destination_settings),
        modifier = modifier,
        navigationIcon = { BackButton(onBack) },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            EmptyState(
                title = stringResource(R.string.placeholder_settings_title),
                body = stringResource(R.string.placeholder_settings_body),
                icon = Icons.Filled.Settings,
            )
        }
    }
}

@Preview(name = "Settings", showBackground = true, heightDp = 640)
@Composable
private fun SettingsScreenPreview() {
    MezgebTheme { SettingsScreen(onBack = {}) }
}
