package et.alpha.mezgeb.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy

@Composable
fun MezgebBottomBar(
    currentDestination: NavDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentDestination.isOn(destination),
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        // The label below is always visible, so repeating it here would
                        // make TalkBack announce the item twice.
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(destination.labelRes)) },
                alwaysShowLabel = true,
            )
        }
    }
}

/**
 * True when [this] destination, or any parent of it, is [target]'s route. Walking the
 * hierarchy keeps the tab highlighted on nested screens added in later sessions.
 */
internal fun NavDestination?.isOn(target: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(target.route::class) } == true

/** True when the current destination is one of the four bottom-bar tabs. */
internal fun NavDestination?.isTopLevel(): Boolean =
    TopLevelDestination.entries.any { isOn(it) }
