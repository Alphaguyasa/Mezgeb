package et.alpha.mezgeb.data.repo

import et.alpha.mezgeb.data.local.dao.CreditEntryDao
import et.alpha.mezgeb.data.local.dao.CustomerDao
import et.alpha.mezgeb.data.mapper.toDomain
import et.alpha.mezgeb.data.mapper.toEntity
import et.alpha.mezgeb.domain.model.CreditEntry
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.CreditStatementLine
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.domain.model.CustomerBalance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomerRepository @Inject constructor(
    private val customerDao: CustomerDao,
    private val creditEntryDao: CreditEntryDao,
) {

    fun observeCustomersWithBalance(): Flow<List<CustomerBalance>> =
        customerDao.observeAllWithBalance().map { rows -> rows.map { it.toDomain() } }

    fun searchCustomers(query: String): Flow<List<CustomerBalance>> =
        customerDao.searchWithBalance(query).map { rows -> rows.map { it.toDomain() } }

    fun observeCustomer(id: String): Flow<CustomerBalance?> =
        customerDao.observeWithBalance(id).map { it?.toDomain() }

    fun observeTotalOutstandingMinor(): Flow<Long> = customerDao.observeTotalOutstandingMinor()

    fun observeCustomersInDebtCount(): Flow<Int> = customerDao.observeCustomersInDebtCount()

    /** Off the books, history kept. See [et.alpha.mezgeb.data.local.entity.CustomerEntity]. */
    suspend fun setArchived(id: String, isArchived: Boolean) =
        customerDao.setArchived(id, isArchived)

    suspend fun getCustomer(id: String): Customer? = customerDao.getById(id)?.toDomain()

    suspend fun insertCustomer(customer: Customer) = customerDao.insert(customer.toEntity())

    suspend fun updateCustomer(customer: Customer) = customerDao.update(customer.toEntity())

    suspend fun deleteCustomer(id: String) = customerDao.delete(id)

    // --- Credit entries -----------------------------------------------------------

    /** The customer's statement, each line carrying the balance after it was applied. */
    fun observeStatement(customerId: String): Flow<List<CreditStatementLine>> =
        creditEntryDao.observeStatement(customerId).map { rows -> rows.map { it.toDomain() } }

    fun observeEntries(customerId: String): Flow<List<CreditEntry>> =
        creditEntryDao.observeForCustomer(customerId).map { rows -> rows.map { it.toDomain() } }

    suspend fun insertEntry(entry: CreditEntry) {
        require(entry.amountMinor >= 0) {
            "CreditEntry.amountMinor is always positive; kind carries the sign"
        }
        creditEntryDao.insert(entry.toEntity())
    }

    suspend fun getEntry(id: String): CreditEntry? = creditEntryDao.getById(id)?.toDomain()

    /**
     * Corrects a mistyped entry's amount, day and note. Its kind and its link stay.
     *
     * Closed reconciliation periods are not touched: their credit figures were
     * snapshotted into the session row when they closed, so a correction shows in the
     * statement and in open periods only.
     *
     * @return true when the entry existed and was changed.
     */
    suspend fun correctEntry(id: String, amountMinor: Long, occurredAt: Long, note: String?): Boolean {
        require(amountMinor > 0) {
            "CreditEntry.amountMinor is always positive; kind carries the sign"
        }
        return creditEntryDao.updateDetails(id, amountMinor, occurredAt, note) > 0
    }

    /**
     * Removes an entry that should never have been recorded.
     *
     * A linked repayment takes its link with it, which frees the captured payment to
     * be matched again; the payment itself stays in the ledger.
     *
     * @return true when the entry existed and was removed.
     */
    suspend fun deleteEntry(id: String): Boolean = creditEntryDao.delete(id) > 0

    suspend fun linkRepaymentToTransaction(entryId: String, transactionId: String?) =
        creditEntryDao.setLinkedTransaction(entryId, transactionId)

    /**
     * The repayment a captured payment was used to settle, if any.
     *
     * The link is worth showing from both ends: from the statement it explains where
     * the money came from, and from the transaction it explains where the money went.
     */
    suspend fun repaymentForTransaction(transactionId: String): CreditEntry? =
        creditEntryDao.getByLinkedTransaction(transactionId)?.toDomain()

    suspend fun sumByKind(kind: CreditKind, from: Long, to: Long): Long =
        creditEntryDao.sumByKind(kind, from, to)
}
