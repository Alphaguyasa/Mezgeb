package et.alpha.mezgeb.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import et.alpha.mezgeb.data.local.entity.CustomerEntity
import kotlinx.coroutines.flow.Flow

/**
 * A customer plus their outstanding balance, computed in SQL.
 *
 * [oldestUnpaidAt] is the timestamp of the earliest credit entry, used to age the
 * debt on the customers screen. It is null when the customer has never been given
 * credit.
 */
data class CustomerWithBalance(
    val id: String,
    val name: String,
    val phone: String?,
    val note: String?,
    val createdAt: Long,
    val balanceMinor: Long,
    val oldestUnpaidAt: Long?,
)

@Dao
interface CustomerDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(customer: CustomerEntity)

    @Update
    suspend fun update(customer: CustomerEntity)

    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getById(id: String): CustomerEntity?

    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun observeAll(): Flow<List<CustomerEntity>>

    /**
     * Balance per customer, summed in SQL rather than in Kotlin so the customers
     * screen never has to load every credit entry to render a list.
     *
     * Sign convention follows the entity contract: `amountMinor` is always positive
     * and `kind` carries the sign. CREDIT_GIVEN and ADJUSTMENT increase what the
     * customer owes; REPAYMENT decreases it. A negative result is legal and means the
     * shop owes the customer.
     */
    @Query(
        """
        SELECT c.id AS id,
               c.name AS name,
               c.phone AS phone,
               c.note AS note,
               c.createdAt AS createdAt,
               COALESCE(SUM(
                   CASE e.kind
                       WHEN 'REPAYMENT' THEN -e.amountMinor
                       ELSE e.amountMinor
                   END
               ), 0) AS balanceMinor,
               MIN(CASE WHEN e.kind = 'CREDIT_GIVEN' THEN e.occurredAt END) AS oldestUnpaidAt
        FROM customers c
        LEFT JOIN credit_entries e ON e.customerId = c.id
        GROUP BY c.id
        ORDER BY balanceMinor DESC, c.name ASC
        """,
    )
    fun observeAllWithBalance(): Flow<List<CustomerWithBalance>>

    @Query(
        """
        SELECT c.id AS id,
               c.name AS name,
               c.phone AS phone,
               c.note AS note,
               c.createdAt AS createdAt,
               COALESCE(SUM(
                   CASE e.kind
                       WHEN 'REPAYMENT' THEN -e.amountMinor
                       ELSE e.amountMinor
                   END
               ), 0) AS balanceMinor,
               MIN(CASE WHEN e.kind = 'CREDIT_GIVEN' THEN e.occurredAt END) AS oldestUnpaidAt
        FROM customers c
        LEFT JOIN credit_entries e ON e.customerId = c.id
        WHERE c.id = :customerId
        GROUP BY c.id
        """,
    )
    fun observeWithBalance(customerId: String): Flow<CustomerWithBalance?>

    @Query(
        """
        SELECT c.id AS id,
               c.name AS name,
               c.phone AS phone,
               c.note AS note,
               c.createdAt AS createdAt,
               COALESCE(SUM(
                   CASE e.kind
                       WHEN 'REPAYMENT' THEN -e.amountMinor
                       ELSE e.amountMinor
                   END
               ), 0) AS balanceMinor,
               MIN(CASE WHEN e.kind = 'CREDIT_GIVEN' THEN e.occurredAt END) AS oldestUnpaidAt
        FROM customers c
        LEFT JOIN credit_entries e ON e.customerId = c.id
        WHERE c.name LIKE '%' || :query || '%' OR c.phone LIKE '%' || :query || '%'
        GROUP BY c.id
        ORDER BY balanceMinor DESC, c.name ASC
        """,
    )
    fun searchWithBalance(query: String): Flow<List<CustomerWithBalance>>

    /** Total outstanding across all customers, and how many owe anything. */
    @Query(
        """
        SELECT COALESCE(SUM(balanceMinor), 0) FROM (
            SELECT COALESCE(SUM(
                       CASE e.kind
                           WHEN 'REPAYMENT' THEN -e.amountMinor
                           ELSE e.amountMinor
                       END
                   ), 0) AS balanceMinor
            FROM customers c
            LEFT JOIN credit_entries e ON e.customerId = c.id
            GROUP BY c.id
        )
        """,
    )
    fun observeTotalOutstandingMinor(): Flow<Long>

    @Query("DELETE FROM customers")
    suspend fun deleteAll()
}
