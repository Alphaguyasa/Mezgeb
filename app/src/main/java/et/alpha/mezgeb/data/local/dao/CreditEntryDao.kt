package et.alpha.mezgeb.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import et.alpha.mezgeb.data.local.entity.CreditEntryEntity
import et.alpha.mezgeb.domain.model.CreditKind
import kotlinx.coroutines.flow.Flow

/** A statement line: the entry plus the balance after it was applied. */
data class CreditEntryWithRunningBalance(
    val id: String,
    val customerId: String,
    val kind: CreditKind,
    val amountMinor: Long,
    val occurredAt: Long,
    val note: String?,
    val linkedTransactionId: String?,
    val runningBalanceMinor: Long,
)

@Dao
interface CreditEntryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: CreditEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<CreditEntryEntity>)

    @Update
    suspend fun update(entry: CreditEntryEntity)

    @Query("DELETE FROM credit_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM credit_entries WHERE id = :id")
    suspend fun getById(id: String): CreditEntryEntity?

    @Query("SELECT * FROM credit_entries WHERE customerId = :customerId ORDER BY occurredAt ASC, id ASC")
    fun observeForCustomer(customerId: String): Flow<List<CreditEntryEntity>>

    /**
     * The running statement for one customer, computed in SQL.
     *
     * This uses a correlated subquery rather than `SUM(...) OVER (...)` deliberately:
     * window functions need SQLite 3.28, which only arrives with API 30, and minSdk
     * here is 26. The subquery is O(n²) over one customer's entries — a credit book
     * that would make that hurt is far larger than any shop this app targets.
     *
     * Ordering ties are broken by `id` in both the outer query and the subquery, so
     * two entries sharing a timestamp still produce a stable, monotonic statement.
     */
    @Query(
        """
        SELECT e.id AS id,
               e.customerId AS customerId,
               e.kind AS kind,
               e.amountMinor AS amountMinor,
               e.occurredAt AS occurredAt,
               e.note AS note,
               e.linkedTransactionId AS linkedTransactionId,
               (
                   SELECT COALESCE(SUM(
                       CASE e2.kind
                           WHEN 'REPAYMENT' THEN -e2.amountMinor
                           ELSE e2.amountMinor
                       END
                   ), 0)
                   FROM credit_entries e2
                   WHERE e2.customerId = e.customerId
                     AND (
                           e2.occurredAt < e.occurredAt
                           OR (e2.occurredAt = e.occurredAt AND e2.id <= e.id)
                         )
               ) AS runningBalanceMinor
        FROM credit_entries e
        WHERE e.customerId = :customerId
        ORDER BY e.occurredAt ASC, e.id ASC
        """,
    )
    fun observeStatement(customerId: String): Flow<List<CreditEntryWithRunningBalance>>

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM credit_entries
        WHERE kind = :kind AND occurredAt >= :from AND occurredAt <= :to
        """,
    )
    suspend fun sumByKind(kind: CreditKind, from: Long, to: Long): Long

    @Query("SELECT * FROM credit_entries WHERE linkedTransactionId = :transactionId LIMIT 1")
    suspend fun getByLinkedTransaction(transactionId: String): CreditEntryEntity?

    @Query("UPDATE credit_entries SET linkedTransactionId = :transactionId WHERE id = :id")
    suspend fun setLinkedTransaction(id: String, transactionId: String?)

    @Query("DELETE FROM credit_entries")
    suspend fun deleteAll()
}
