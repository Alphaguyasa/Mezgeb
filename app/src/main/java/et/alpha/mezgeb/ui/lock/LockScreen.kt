package et.alpha.mezgeb.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.AmountKeypad
import kotlin.math.roundToInt

@Composable
fun LockRoute(
    modifier: Modifier = Modifier,
    viewModel: LockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Resolved in composition rather than inside the effect. `context.getString` is a
    // read Compose cannot observe, so a language change would leave the prompt in the
    // previous language until the process restarted.
    val promptTitle = stringResource(R.string.lock_biometric_title)
    val promptNegative = stringResource(R.string.lock_use_pin)

    // Offered the moment the screen appears when it is available, because a shop
    // owner unlocking forty times a day should not have to tap a prompt first.
    LaunchedEffect(state.biometricAvailable) {
        val activity = context as? FragmentActivity ?: return@LaunchedEffect
        if (!state.biometricAvailable || state.isLockedOut) return@LaunchedEffect
        if (BiometricManager.from(context).canAuthenticate(BIOMETRIC_TYPES) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            return@LaunchedEffect
        }

        BiometricPrompt(
            activity,
            androidx.core.content.ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.onEvent(LockEvent.BiometricSucceeded)
                }
            },
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(promptTitle)
                // The PIN is always the fallback: a fingerprint that stops being
                // recognised must not be a way of losing a ledger.
                .setNegativeButtonText(promptNegative)
                .build(),
        )
    }

    LockScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/**
 * The screen between someone and a shop's money.
 *
 * Deliberately offers nothing else: no "forgot PIN", no way to see the ledger behind
 * it, nothing but digits. Every escape hatch on a lock screen is a hatch for whoever
 * picked the phone up.
 */
@Composable
fun LockScreen(
    state: LockUiState,
    onEvent: (LockEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.xl))

            PinDots(length = state.entered.length)
            Spacer(Modifier.height(spacing.lg))

            when {
                state.isLockedOut -> Text(
                    text = pluralStringResource(
                        R.plurals.lock_locked_out,
                        (state.lockedOutForMillis / 1000.0).roundToInt(),
                        (state.lockedOutForMillis / 1000.0).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )

                state.showWrong -> Text(
                    text = if (state.attemptsBeforeLockout > 0) {
                        pluralStringResource(
                            R.plurals.lock_wrong_with_tries,
                            state.attemptsBeforeLockout,
                            state.attemptsBeforeLockout,
                        )
                    } else {
                        stringResource(R.string.lock_wrong)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Spacer(Modifier.height(spacing.lg))
            }

            Spacer(Modifier.height(spacing.lg))
            AmountKeypad(
                onDigit = { onEvent(LockEvent.DigitPressed(it)) },
                // A PIN keypad has no "00" key; reusing the component means it has one,
                // so it is wired to a single zero rather than left to insert two.
                onDoubleZero = { onEvent(LockEvent.DigitPressed(0)) },
                onBackspace = { onEvent(LockEvent.BackspacePressed) },
            )

            Spacer(Modifier.height(spacing.lg))
            Button(
                onClick = { onEvent(LockEvent.Submit) },
                enabled = state.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.touchTarget),
            ) {
                Text(stringResource(R.string.lock_unlock))
            }
        }
    }
}

@Composable
private fun PinDots(length: Int) {
    val spacing = MezgebTheme.spacing
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
        repeat(length.coerceAtLeast(1)) {
            Box(
                Modifier
                    .size(spacing.md)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Fingerprint or face; device credential is deliberately not offered as a fallback. */
private const val BIOMETRIC_TYPES = BiometricManager.Authenticators.BIOMETRIC_WEAK

@Preview(name = "Lock", showBackground = true, heightDp = 800)
@Composable
private fun LockScreenPreview() {
    MezgebTheme {
        LockScreen(state = LockUiState(entered = "12"), onEvent = {})
    }
}

@Preview(name = "Lock (locked out, አማርኛ)", showBackground = true, heightDp = 800, locale = "am")
@Composable
private fun LockScreenLockedOutPreview() {
    MezgebTheme {
        LockScreen(state = LockUiState(lockedOutForMillis = 30_000), onEvent = {})
    }
}
