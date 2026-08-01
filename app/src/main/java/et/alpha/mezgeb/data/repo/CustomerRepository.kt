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

    suspend fun updateEntry(entry: CreditEntry) = creditEntryDao.update(entry.toEntity())

    suspend fun deleteEntry(id: String) = creditEntryDao.delete(id)

    suspend fun linkRepaymentToTransaction(entryId: String, transactionId: String?) =
        creditEntryDao.setLinkedTransaction(entryId, transactionId)

    suspend fun sumByKind(kind: CreditKind, from: Long, to: Long): Long =
        creditEntryDao.sumByKind(kind, from, to)
}
