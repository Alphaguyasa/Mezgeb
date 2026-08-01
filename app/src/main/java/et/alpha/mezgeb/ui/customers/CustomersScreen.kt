package et.alpha.mezgeb.ui.customers

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

/** Session 1 placeholder. The credit book is built in Session 7. */
@Composable
fun CustomersScreen(modifier: Modifier = Modifier) {
    MezgebScaffold(
        title = stringResource(R.string.destination_customers),
        modifier = modifier,
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            EmptyState(
                title = stringResource(R.string.placeholder_customers_title),
                body = stringResource(R.string.placeholder_customers_body),
            )
        }
    }
}

@Preview(name = "Customers", showBackground = true, heightDp = 640)
@Composable
private fun CustomersScreenPreview() {
    MezgebTheme { CustomersScreen() }
}
