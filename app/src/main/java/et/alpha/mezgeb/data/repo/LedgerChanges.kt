package et.alpha.mezgeb.data.repo

import et.alpha.mezgeb.data.local.MezgebDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A signal that something a reconciliation reads has changed.
 *
 * Reconciliation runs several one-shot queries rather than one observable one, so no
 * single DAO flow covers it. Room's invalidation tracker does: it fires whenever any of
 * the named tables is written, whatever the write was. A payment captured while the
 * Reconcile screen is open has to move the gap, and this is what tells the screen to
 * recompute.
 */
@Singleton
class LedgerChanges @Inject constructor(
    private val db: MezgebDatabase,
) {

    /** Emits once straight away, then once per write to a table reconciliation reads. */
    fun observe(): Flow<Unit> =
        db.invalidationTracker.createFlow(*RECONCILED_TABLES, emitInitialState = true)
            .map { }

    private companion object {
        // Everything ReconcilePeriodUseCase queries. The sessions table is left out: the
        // view model already observes the open session directly.
        val RECONCILED_TABLES =
            arrayOf("transactions", "raw_messages", "credit_entries", "customers")
    }
}
