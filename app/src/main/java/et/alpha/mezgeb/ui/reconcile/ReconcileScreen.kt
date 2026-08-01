package et.alpha.mezgeb.ui.reconcile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.BackButton
import et.alpha.mezgeb.designsystem.component.EmptyState
import et.alpha.mezgeb.designsystem.component.MezgebScaffold

/** Session 1 placeholder. The reconciliation engine is built in Session 8. */
@Composable
fun ReconcileScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MezgebScaffold(
        title = stringResource(R.string.destination_reconcile),
        modifier = modifier,
        navigationIcon = { BackButton(onBack) },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            EmptyState(
                title = stringResource(R.string.placeholder_reconcile_title),
                body = stringResource(R.string.placeholder_reconcile_body),
            )
        }
    }
}

@Preview(name = "Reconcile", showBackground = true, heightDp = 640)
@Composable
private fun ReconcileScreenPreview() {
    MezgebTheme { ReconcileScreen(onBack = {}) }
}
