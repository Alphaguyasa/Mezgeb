package et.alpha.mezgeb.domain.reconcile

import et.alpha.mezgeb.data.repo.ReconciliationRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the shop believes it took in a period, from wherever that belief comes from.
 *
 * The seam for v2, built now rather than later (03-ARCHITECTURE.md section 9). In v1
 * the number is typed by a person; a Suq-backed implementation would read it from a
 * point-of-sale system. Nothing above this interface may depend on the concrete
 * class, so adding that implementation is a new binding and nothing else.
 */
interface ExpectedSalesProvider {

    /** Null when nothing has been stated for the period — not zero. */
    suspend fun expectedTotalMinor(periodStart: Long, periodEnd: Long): Long?
}

/**
 * v1: the number the shop owner typed into the open period.
 *
 * It lives on the reconciliation session because it belongs to that period — a
 * figure recalled at the end of a Tuesday is about that Tuesday, and storing it
 * anywhere else would make it ambiguous which day it described.
 */
@Singleton
class ManualEntryExpectedSalesProvider @Inject constructor(
    private val sessions: ReconciliationRepository,
) : ExpectedSalesProvider {

    override suspend fun expectedTotalMinor(periodStart: Long, periodEnd: Long): Long? =
        sessions.getOpenSession()
            ?.takeIf { it.periodStart == periodStart && it.periodEnd == periodEnd }
            ?.expectedTotalMinor
}
