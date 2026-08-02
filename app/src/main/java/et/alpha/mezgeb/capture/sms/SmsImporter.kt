package et.alpha.mezgeb.capture.sms

import kotlinx.coroutines.flow.Flow

/**
 * Backfill of messages already on the phone.
 *
 * The notification listener can only see what is posted while it is running — it
 * cannot reach backwards, so a shop with months of history in its inbox starts with
 * an empty ledger. Reading that history needs a restricted permission that Google
 * Play does not grant to finance apps, so it exists in the sideloaded `direct`
 * flavor only. See 03-ARCHITECTURE.md section 1.
 *
 * Shared code depends on this interface and never on either implementation, which is
 * also what keeps the restricted permission names out of `src/main` entirely —
 * including out of [requiredPermissions], which returns nothing at all in the `play`
 * flavor. .md hard constraint #2 is about more than the manifest.
 */
interface SmsImporter {

    /** False in `play`. UI that offers an import must check this first. */
    val isSupported: Boolean

    /**
     * Runtime permissions the user must grant before [start] can do anything, in the
     * order they should be requested. Empty when [isSupported] is false.
     */
    fun requiredPermissions(): List<String>

    /** Enqueues the import. Safe to call twice — a run already in flight is kept. */
    fun start()

    fun observeStatus(): Flow<SmsImportStatus>
}

/**
 * Where an import has got to.
 *
 * Counted rather than a bare spinner because the numbers are the reassurance: a shop
 * owner watching "412 imported, 38 already known" understands what happened to the
 * other 38, and "0 imported" tells them immediately that something is wrong.
 */
sealed interface SmsImportStatus {

    /** This build cannot import. The `play` flavor never leaves this state. */
    data object Unavailable : SmsImportStatus

    data object Idle : SmsImportStatus

    data class Running(val imported: Int, val scanned: Int, val total: Int) : SmsImportStatus

    data class Finished(
        val imported: Int,
        val duplicates: Int,
        val queued: Int,
    ) : SmsImportStatus

    /** Usually the permission being refused. The user is told, not left waiting. */
    data object Failed : SmsImportStatus
}
