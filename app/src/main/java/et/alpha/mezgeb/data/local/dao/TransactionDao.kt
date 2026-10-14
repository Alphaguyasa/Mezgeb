package et.alpha.mezgeb.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import et.alpha.mezgeb.data.local.entity.TransactionEntity
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import kotlinx.coroutines.flow.Flow

data class CategoryTotal(val categoryId: String?, val totalMinor: Long, val transactionCount: Int)

data class ChannelTotal(val channel: Channel, val totalMinor: Long, val transactionCount: Int)

data class DayTotal(val dayEpochMillis: Long, val inMinor: Long, val outMinor: Long)

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeById(id: String): Flow<TransactionEntity?>

    /**
     * The dedup lookup for a provider's own transaction reference.
     *
     * Scoped to [channel] because a reference is only unique within the provider that
     * issued it: a telebirr reference and a CBE reference can be the same string, and
     * matching across channels would drop the second, real payment as a duplicate.
     *
     * Deliberately includes soft-deleted rows. A payment the shop deleted is still a
     * payment that was seen; if the other capture path delivers it again, it must stay
     * deleted rather than come back as a new row.
     */
    @Query(
        "SELECT * FROM transactions WHERE channel = :channel AND providerTxnId = :providerTxnId LIMIT 1",
    )
    suspend fun getByProviderTxnId(channel: Channel, providerTxnId: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE rawMessageId = :rawMessageId")
    suspend fun getByRawMessageId(rawMessageId: String): TransactionEntity?

    /**
     * The ledger's single filtered query. Every filter is nullable and a null means
     * "don't constrain on this", which keeps one query where six overloads would
     * otherwise accumulate.
     *
     * [query] searches counterparty name, phone, provider reference and note.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE isDeleted = 0
          AND (:from IS NULL OR occurredAt >= :from)
          AND (:to IS NULL OR occurredAt <= :to)
          AND (:channel IS NULL OR channel = :channel)
          AND (:direction IS NULL OR direction = :direction)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:isReconciled IS NULL OR isReconciled = :isReconciled)
          AND (
                :query IS NULL
                OR counterpartyName LIKE '%' || :query || '%'
                OR counterpartyPhone LIKE '%' || :query || '%'
                OR providerTxnId LIKE '%' || :query || '%'
                OR note LIKE '%' || :query || '%'
              )
        ORDER BY occurredAt DESC, id DESC
        """,
    )
    fun pagingSourceFiltered(
        from: Long?,
        to: Long?,
        channel: Channel?,
        direction: Direction?,
        categoryId: String?,
        isReconciled: Boolean?,
        query: String?,
    ): PagingSource<Int, TransactionEntity>

    /** Same predicate as [pagingSourceFiltered], as a list. Used by tests and export. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE isDeleted = 0
          AND (:from IS NULL OR occurredAt >= :from)
          AND (:to IS NULL OR occurredAt <= :to)
          AND (:channel IS NULL OR channel = :channel)
          AND (:direction IS NULL OR direction = :direction)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:isReconciled IS NULL OR isReconciled = :isReconciled)
          AND (
                :query IS NULL
                OR counterpartyName LIKE '%' || :query || '%'
                OR counterpartyPhone LIKE '%' || :query || '%'
                OR providerTxnId LIKE '%' || :query || '%'
                OR note LIKE '%' || :query || '%'
              )
        ORDER BY occurredAt DESC, id DESC
        """,
    )
    suspend fun listFiltered(
        from: Long?,
        to: Long?,
        channel: Channel?,
        direction: Direction?,
        categoryId: String?,
        isReconciled: Boolean?,
        query: String?,
    ): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE isDeleted = 0 ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    /**
     * Money-in transactions since [since] that no credit repayment claims yet.
     *
     * The candidate list for "which payment was this repayment?". Already-linked
     * transactions are excluded so the same payment cannot settle two debts.
     */
    @Query(
        """
        SELECT t.* FROM transactions t
        WHERE t.isDeleted = 0
          AND t.direction = 'IN'
          AND t.occurredAt >= :since
          AND NOT EXISTS (
              SELECT 1 FROM credit_entries e WHERE e.linkedTransactionId = t.id
          )
        ORDER BY t.occurredAt DESC
        """,
    )
    suspend fun unlinkedIncomingSince(since: Long): List<TransactionEntity>

    /**
     * Per-day in and out totals for the same predicate as [pagingSourceFiltered], so
     * the ledger can show a subtotal on each day header.
     *
     * Days are cut in **local** time, not UTC. A shop's day ends when the shop closes;
     * a payment at 9pm in Addis belongs to that day and not to the next one. Timestamps
     * are still stored as epoch millis UTC — this is a presentation boundary.
     *
     * [offsetMillis] is the zone's offset from UTC, passed in rather than left to
     * SQLite's `localtime` modifier. That modifier reads the *process* time zone, which
     * is not necessarily the JVM's — they disagreed the first time this was tested, and
     * a day header whose subtotal belongs to a different day than the rows beneath it is
     * precisely the sort of quietly wrong number this app exists to prevent. Passing the
     * offset makes SQLite and [et.alpha.mezgeb.util.MezgebDates] compute the same
     * boundary by construction.
     *
     * This assumes a zone with no daylight saving, which Ethiopia has none of. A build
     * for anywhere that does would need the offset resolved per row rather than once.
     *
     * [dayEpochMillis] is local midnight as a UTC timestamp, so the caller gets an
     * instant back rather than a string it would have to re-parse.
     */
    @Query(
        """
        SELECT ((occurredAt + :offsetMillis) / 86400000) * 86400000 - :offsetMillis
                    AS dayEpochMillis,
               COALESCE(SUM(CASE WHEN direction = 'IN' THEN amountMinor ELSE 0 END), 0) AS inMinor,
               COALESCE(SUM(CASE WHEN direction = 'OUT' THEN amountMinor ELSE 0 END), 0) AS outMinor
        FROM transactions
        WHERE isDeleted = 0
          AND (:from IS NULL OR occurredAt >= :from)
          AND (:to IS NULL OR occurredAt <= :to)
          AND (:channel IS NULL OR channel = :channel)
          AND (:direction IS NULL OR direction = :direction)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:isReconciled IS NULL OR isReconciled = :isReconciled)
          AND (
                :query IS NULL
                OR counterpartyName LIKE '%' || :query || '%'
                OR counterpartyPhone LIKE '%' || :query || '%'
                OR providerTxnId LIKE '%' || :query || '%'
                OR note LIKE '%' || :query || '%'
              )
        GROUP BY dayEpochMillis
        ORDER BY dayEpochMillis DESC
        """,
    )
    fun observeDailyTotals(
        offsetMillis: Long,
        from: Long?,
        to: Long?,
        channel: Channel?,
        direction: Direction?,
        categoryId: String?,
        isReconciled: Boolean?,
        query: String?,
    ): Flow<List<DayTotal>>

    // --- Aggregates -------------------------------------------------------------
    // COALESCE so an empty period returns 0 rather than null; callers should never
    // have to reason about "no rows" separately from "zero birr".

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM transactions
        WHERE isDeleted = 0 AND direction = :direction
          AND occurredAt >= :from AND occurredAt <= :to
        """,
    )
    suspend fun sumByDirection(direction: Direction, from: Long, to: Long): Long

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM transactions
        WHERE isDeleted = 0 AND direction = :direction
          AND occurredAt >= :from AND occurredAt <= :to
        """,
    )
    fun observeSumByDirection(direction: Direction, from: Long, to: Long): Flow<Long>

    /**
     * Money in that arrived through a message, excluding the given channel.
     *
     * Reconciliation needs captured money and hand-entered cash counted separately —
     * see [et.alpha.mezgeb.domain.reconcile.ReconcilePeriodUseCase], which explains
     * why adding the two together otherwise counts cash twice.
     */
    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM transactions
        WHERE isDeleted = 0 AND direction = :direction AND channel != :excludingChannel
          AND occurredAt >= :from AND occurredAt <= :to
        """,
    )
    suspend fun sumByDirectionExcludingChannel(
        direction: Direction,
        excludingChannel: Channel,
        from: Long,
        to: Long,
    ): Long

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM transactions
        WHERE isDeleted = 0 AND categoryId IS NULL
          AND occurredAt >= :from AND occurredAt <= :to
        """,
    )
    suspend fun sumUncategorised(from: Long, to: Long): Long

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE isDeleted = 0 AND occurredAt >= :from AND occurredAt <= :to
        """,
    )
    suspend fun countInPeriod(from: Long, to: Long): Int

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM transactions
        WHERE isDeleted = 0 AND channel = :channel AND direction = :direction
          AND occurredAt >= :from AND occurredAt <= :to
        """,
    )
    suspend fun sumByChannelAndDirection(
        channel: Channel,
        direction: Direction,
        from: Long,
        to: Long,
    ): Long

    @Query(
        """
        SELECT categoryId AS categoryId,
               COALESCE(SUM(amountMinor), 0) AS totalMinor,
               COUNT(*) AS transactionCount
        FROM transactions
        WHERE isDeleted = 0 AND direction = :direction
          AND occurredAt >= :from AND occurredAt <= :to
        GROUP BY categoryId
        ORDER BY totalMinor DESC
        """,
    )
    suspend fun totalsByCategory(direction: Direction, from: Long, to: Long): List<CategoryTotal>

    @Query(
        """
        SELECT channel AS channel,
               COALESCE(SUM(amountMinor), 0) AS totalMinor,
               COUNT(*) AS transactionCount
        FROM transactions
        WHERE isDeleted = 0 AND direction = :direction
          AND occurredAt >= :from AND occurredAt <= :to
        GROUP BY channel
        ORDER BY totalMinor DESC
        """,
    )
    suspend fun totalsByChannel(direction: Direction, from: Long, to: Long): List<ChannelTotal>

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE isDeleted = 0 AND categoryId IS NULL
          AND occurredAt >= :from AND occurredAt <= :to
        """,
    )
    suspend fun countUncategorised(from: Long, to: Long): Int

    /**
     * Rows a human touched after the period was opened, for whatever reason.
     *
     * `touchedAt`, not `editedAt`: a period's figures move when a transaction is filed
     * under a category or restored from deleted, not only when a parsed value is
     * changed. Asking the narrower column would under-report the reason a period does
     * not balance, which is the one direction a reconciliation explanation must not err.
     */
    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE isDeleted = 0 AND touchedAt IS NOT NULL AND touchedAt >= :since
          AND occurredAt >= :from AND occurredAt <= :to
        """,
    )
    suspend fun countTouchedSince(since: Long, from: Long, to: Long): Int

    // --- Mutations --------------------------------------------------------------

    /**
     * Soft delete. Rows are never removed; the ledger keeps its history.
     *
     * Touched, not edited: nothing the parser extracted has changed, so a row that
     * comes back through [restore] must not wear the ledger's edited marker.
     */
    @Query("UPDATE transactions SET isDeleted = 1, touchedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE transactions SET isDeleted = 0, touchedAt = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    /**
     * Filing, not editing. The category is the shop's own annotation; every value the
     * message carried survives it untouched, so this sets [touchedAt] alone.
     */
    @Query("UPDATE transactions SET categoryId = :categoryId, touchedAt = :now WHERE id = :id")
    suspend fun setCategory(id: String, categoryId: String?, now: Long)

    @Query("UPDATE transactions SET isReconciled = :isReconciled, reconciliationSessionId = :sessionId WHERE id = :id")
    suspend fun setReconciled(id: String, isReconciled: Boolean, sessionId: String?)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}
