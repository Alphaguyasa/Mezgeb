package et.alpha.mezgeb.ui.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.EmptyState
import et.alpha.mezgeb.designsystem.component.MezgebScaffold

@Composable
fun TodayRoute(
    onOpenReview: () -> Unit,
    onOpenReconcile: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TodayScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onOpenReview = onOpenReview,
        onOpenReconcile = onOpenReconcile,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@Composable
fun TodayScreen(
    state: TodayUiState,
    onEvent: (TodayEvent) -> Unit,
    onOpenReview: () -> Unit,
    onOpenReconcile: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MezgebScaffold(
        title = stringResource(R.string.destination_today),
        modifier = modifier,
        actions = {
            IconButton(onClick = onOpenReview) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = stringResource(R.string.cd_open_review),
                )
            }
            IconButton(onClick = onOpenReconcile) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.cd_open_reconcile),
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.cd_open_settings),
                )
            }
        },
    ) { inner ->
        Box(
            Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            EmptyState(
                title = stringResource(R.string.placeholder_today_title),
                body = stringResource(R.string.placeholder_today_body),
            )
        }
    }
}

@Preview(name = "Today", showBackground = true, heightDp = 640)
@Composable
private fun TodayScreenPreview() {
    MezgebTheme {
        TodayScreen(
            state = TodayUiState(isLoading = false),
            onEvent = {},
            onOpenReview = {},
            onOpenReconcile = {},
            onOpenSettings = {},
        )
    }
}

/** Amharic render check — .md requires Ethiopic to be verified in a preview. */
@Preview(name = "Today (አማርኛ)", showBackground = true, heightDp = 640, locale = "am")
@Composable
private fun TodayScreenAmharicPreview() {
    MezgebTheme {
        TodayScreen(
            state = TodayUiState(isLoading = false),
            onEvent = {},
            onOpenReview = {},
            onOpenReconcile = {},
            onOpenSettings = {},
        )
    }
}
