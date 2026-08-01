package et.alpha.mezgeb.ui.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.BackButton
import et.alpha.mezgeb.designsystem.component.EmptyState
import et.alpha.mezgeb.designsystem.component.MezgebScaffold

/**
 * Session 1 placeholder. The queue and the teach-a-rule flow are built in Session 6.
 *
 * Note the icon: an empty review queue means every captured message parsed cleanly,
 * so this empty state is a success state and is drawn as one.
 */
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MezgebScaffold(
        title = stringResource(R.string.destination_review),
        modifier = modifier,
        navigationIcon = { BackButton(onBack) },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            EmptyState(
                title = stringResource(R.string.placeholder_review_title),
                body = stringResource(R.string.placeholder_review_body),
                icon = Icons.Filled.CheckCircle,
            )
        }
    }
}

@Preview(name = "Review", showBackground = true, heightDp = 640)
@Composable
private fun ReviewScreenPreview() {
    MezgebTheme { ReviewScreen(onBack = {}) }
}
