package et.alpha.mezgeb.ui.ledger

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.EmptyState
import et.alpha.mezgeb.designsystem.component.MezgebScaffold

/** Session 1 placeholder. The paged, filterable ledger is built in Session 5. */
@Composable
fun LedgerScreen(modifier: Modifier = Modifier) {
    MezgebScaffold(
        title = stringResource(R.string.destination_ledger),
        modifier = modifier,
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            EmptyState(
                title = stringResource(R.string.placeholder_ledger_title),
                body = stringResource(R.string.placeholder_ledger_body),
            )
        }
    }
}

@Preview(name = "Ledger", showBackground = true, heightDp = 640)
@Composable
private fun LedgerScreenPreview() {
    MezgebTheme { LedgerScreen() }
}
