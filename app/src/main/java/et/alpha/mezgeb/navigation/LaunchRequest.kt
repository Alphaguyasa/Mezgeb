package et.alpha.mezgeb.navigation

import android.content.Context
import android.content.Intent
import et.alpha.mezgeb.MainActivity

/**
 * Intents that open the app on a given screen, and how [MainActivity] reads them back.
 *
 * Only an action string travels, never an id or a value: the activity is exported (it
 * is the launcher), so any app on the phone can send it these intents. The worst such
 * an intent can do is open Reconcile, and the PIN lock is still drawn first, because
 * [MezgebNavHost] is not composed at all while the app is locked.
 */
object LaunchRequest {

    const val ACTION_OPEN_RECONCILE = "et.alpha.mezgeb.action.OPEN_RECONCILE"

    /**
     * Opens Reconcile, from the weekly reminder (ROADMAP R49). CLEAR_TOP with
     * SINGLE_TOP hands the intent to the activity that is already running, through
     * `onNewIntent`, instead of stacking a second copy of the app on top of it.
     */
    fun openReconcile(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_RECONCILE)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

    /** The screen [intent] asks for, or null for an ordinary launch. */
    fun destinationFor(intent: Intent?): MezgebDestination? = when (intent?.action) {
        ACTION_OPEN_RECONCILE -> MezgebDestination.Reconcile
        else -> null
    }
}
