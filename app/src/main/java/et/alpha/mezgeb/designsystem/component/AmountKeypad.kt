package et.alpha.mezgeb.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme

/**
 * The amount keypad for hand-entered cash.
 *
 * Digits accumulate as santim, the way a shop's till does: 4, 5, 0 gives 4.50 birr
 * and no decimal point is ever typed. There is no decimal key on purpose — a key
 * that can be pressed twice, or forgotten, is a key that produces an amount off by
 * a factor of a hundred.
 *
 * Keys are 64dp tall rather than the 48dp floor: this is used one-handed, at a
 * counter, often in a hurry.
 */
@Composable
fun AmountKeypad(
    onDigit: (Int) -> Unit,
    onDoubleZero: () -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        listOf(1..3, 4..6, 7..9).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                row.forEach { digit ->
                    KeypadKey(
                        label = digit.toString(),
                        onClick = { onDigit(digit) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            KeypadKey(label = "00", onClick = onDoubleZero, modifier = Modifier.weight(1f))
            KeypadKey(label = "0", onClick = { onDigit(0) }, modifier = Modifier.weight(1f))
            KeypadKey(
                label = null,
                onClick = onBackspace,
                modifier = Modifier.weight(1f),
                contentDescription = stringResource(R.string.keypad_backspace),
            )
        }
    }
}

@Composable
private fun KeypadKey(
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(KEY_HEIGHT),
        shape = MaterialTheme.shapes.medium,
    ) {
        if (label != null) {
            Text(text = label, style = MaterialTheme.typography.headlineSmall)
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = contentDescription,
            )
        }
    }
}

private val KEY_HEIGHT = 64.dp

@Preview(name = "AmountKeypad", showBackground = true, heightDp = 360)
@Composable
private fun AmountKeypadPreview() {
    MezgebTheme {
        Surface {
            AmountKeypad(
                onDigit = {},
                onDoubleZero = {},
                onBackspace = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
