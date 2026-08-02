package et.alpha.mezgeb.domain.credit

import et.alpha.mezgeb.domain.model.CreditEntry
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.domain.model.CustomerBalance
import et.alpha.mezgeb.util.MezgebDates
import java.time.ZoneId
import javax.inject.Inject

/** A customer's credit position, aged. */
data class CustomerCredit(
    val customer: Customer,
    val balanceMinor: Long,
    val oldestUnpaidAt: Long?,
    /** Whole days since the oldest unsettled credit. Null when nothing is owed. */
    val daysOutstanding: Int?,
    val isOverdue: Boolean,
) {
    val owesMoney: Boolean get() = balanceMinor > 0

    /** Negative balance: the shop is holding the customer's money. */
    val shopOwesCustomer: Boolean get() = balanceMinor < 0
}

/**
 * The balance rules, in one place.
 *
 * The sign convention is the entity contract: `amountMinor` is always positive and
 * `kind` carries the sign. Repayment reduces what is owed; credit and adjustment
 * increase it.
 *
 * A repayment larger than the balance is legal and produces a negative balance — the
 * shop is holding the customer's money. Refusing it would be the app telling a shop
 * owner that something which just happened in front of her did not happen.
 *
 * The same arithmetic exists in SQL, in `CustomerDao` and `CreditEntryDao`, because a
 * list of customers must not have to load every entry to render a row. That is a
 * duplication with teeth, so [CustomerCreditTest] asserts the two agree.
 */
class ComputeCustomerBalanceUseCase @Inject constructor(
    private val zone: ZoneId,
) {

    /** The balance implied by a set of entries, in any order. */
    fun balanceOf(entries: List<CreditEntry>): Long = entries.sumOf { entry ->
        when (entry.kind) {
            CreditKind.REPAYMENT -> -entry.amountMinor
            CreditKind.CREDIT_GIVEN, CreditKind.ADJUSTMENT -> entry.amountMinor
        }
    }

    /**
     * A running statement from unordered entries, oldest first.
     *
     * Order is by date, ties broken by id — the same rule the SQL uses, so a statement
     * rendered from either source reads identically.
     */
    fun runningBalances(entries: List<CreditEntry>): List<Pair<CreditEntry, Long>> {
        var running = 0L
        return entries
            .sortedWith(compareBy({ it.occurredAt }, { it.id }))
            .map { entry ->
                running += when (entry.kind) {
                    CreditKind.REPAYMENT -> -entry.amountMinor
                    CreditKind.CREDIT_GIVEN, CreditKind.ADJUSTMENT -> entry.amountMinor
                }
                entry to running
            }
    }

    /**
     * Ages a balance against the shop's overdue threshold.
     *
     * Days are counted as calendar days in local time, not as 24-hour blocks: credit
     * given yesterday evening is one day old this morning, which is how the person
     * holding the book counts it.
     */
    fun summarise(
        balance: CustomerBalance,
        now: Long,
        overdueThresholdDays: Int = DEFAULT_OVERDUE_DAYS,
    ): CustomerCredit {
        val oldest = balance.oldestUnpaidAt.takeIf { balance.balanceMinor > 0 }
        val days = oldest?.let { daysBetween(it, now) }
        return CustomerCredit(
            customer = balance.customer,
            balanceMinor = balance.balanceMinor,
            oldestUnpaidAt = oldest,
            daysOutstanding = days,
            isOverdue = days != null && days >= overdueThresholdDays,
        )
    }

    private fun daysBetween(from: Long, to: Long): Int {
        val start = MezgebDates.localDate(from, zone)
        val end = MezgebDates.localDate(to, zone)
        return java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt().coerceAtLeast(0)
    }

    companion object {
        /** Configurable in settings; a month is what most shops already use. */
        const val DEFAULT_OVERDUE_DAYS = 30
    }
}
