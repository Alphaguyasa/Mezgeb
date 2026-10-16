package et.alpha.mezgeb.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import et.alpha.mezgeb.data.local.dao.CategoryTotal
import et.alpha.mezgeb.data.local.dao.ChannelTotal
import et.alpha.mezgeb.data.local.dao.DayTotal
import et.alpha.mezgeb.data.local.dao.PossibleDuplicateTotals
import et.alpha.mezgeb.data.local.dao.RawMessageDao
import et.alpha.mezgeb.data.local.dao.TransactionDao
import et.alpha.mezgeb.data.mapper.toDomain
import et.alpha.mezgeb.data.mapper.toEntity
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.domain.model.RawMessage
import et.alpha.mezgeb.domain.model.Transaction
import et.alpha.mezgeb.domain.model.UnmatchedGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** The set of filters the ledger screen can apply. All null means "everything". */
data class TransactionFilter(
    val from: Long? = null,
    val to: Long? = null,
    val channel: Channel? = null,
    val direction: Direction? = null,
    val categoryId: String? = null,
    val isReconciled: Boolean? = null,
    val query: String? = null,
) {
    val isActive: Boolean
        get() = from != null || to != null || channel != null || direction != null ||
            categoryId != null || isReconciled != null || !query.isNullOrBlank()
}

/** Totals for one period, as the reconcile and reports screens need them. */
data class PeriodTotals(
    val capturedInMinor: Long,
    val capturedOutMinor: Long,
    val cashRecordedMinor: Long,
) {
    val netMinor: Long get() = capturedInMinor - capturedOutMinor
}

@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val rawMessageDao: RawMessageDao,
) {

    fun pagedTransactions(filter: TransactionFilter): Flow<PagingData<Transaction>> = Pager(
        config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
        pagingSourceFactory = {
            transactionDao.pagingSourceFiltered(
                from = filter.from,
                to = filter.to,
                channel = filter.channel,
                direction = filter.direction,
                categoryId = filter.categoryId,
                isReconciled = filter.isReconciled,
                query = filter.query?.takeIf { it.isNotBlank() },
            )
        },
    ).flow.map { paging -> paging.map { it.toDomain() } }

    /**
     * Per-day subtotals for the same filter the paged list is showing.
     *
     * @param zone the day boundary to cut on. Resolved to a fixed offset here so the
     *   query and [et.alpha.mezgeb.util.MezgebDates] agree by construction.
     */
    fun observeDailyTotals(filter: TransactionFilter, zone: ZoneId): Flow<List<DayTotal>> =
        transactionDao.observeDailyTotals(
            offsetMillis = zone.rules.getOffset(Instant.now()).totalSeconds * 1000L,
            from = filter.from,
            to = filter.to,
            channel = filter.channel,
            direction = filter.direction,
            categoryId = filter.categoryId,
            isReconciled = filter.isReconciled,
            query = filter.query?.takeIf { it.isNotBlank() },
        )

    suspend fun listTransactions(filter: TransactionFilter): List<Transaction> =
        transactionDao.listFiltered(
            from = filter.from,
            to = filter.to,
            channel = filter.channel,
            direction = filter.direction,
            categoryId = filter.categoryId,
            isReconciled = filter.isReconciled,
            query = filter.query?.takeIf { it.isNotBlank() },
        ).map { it.toDomain() }

    fun observeRecent(limit: Int = 10): Flow<List<Transaction>> =
        transactionDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeTransaction(id: String): Flow<Transaction?> =
        transactionDao.observeById(id).map { it?.toDomain() }

    suspend fun getTransaction(id: String): Transaction? =
        transactionDao.getById(id)?.toDomain()

    /** Includes soft-deleted rows; see [TransactionDao.getByProviderTxnId]. */
    suspend fun getByProviderTxnId(channel: Channel, providerTxnId: String): Transaction? =
        transactionDao.getByProviderTxnId(channel, providerTxnId)?.toDomain()

    /** Candidates for "which payment settled this debt?". See the credit book. */
    suspend fun unlinkedIncomingSince(since: Long): List<Transaction> =
        transactionDao.unlinkedIncomingSince(since).map { it.toDomain() }

    suspend fun insert(transaction: Transaction) = transactionDao.insert(transaction.toEntity())

    suspend fun update(transaction: Transaction) = transactionDao.update(transaction.toEntity())

    suspend fun softDelete(id: String, now: Long) = transactionDao.softDelete(id, now)

    suspend fun restore(id: String, now: Long) = transactionDao.restore(id, now)

    suspend fun setCategory(id: String, categoryId: String?, now: Long) =
        transactionDao.setCategory(id, categoryId, now)

    suspend fun setReconciled(id: String, isReconciled: Boolean, sessionId: String?) =
        transactionDao.setReconciled(id, isReconciled, sessionId)

    // --- Aggregates --------------------------------------------------------------

    suspend fun periodTotals(from: Long, to: Long): PeriodTotals = PeriodTotals(
        capturedInMinor = transactionDao.sumByDirection(Direction.IN, from, to),
        capturedOutMinor = transactionDao.sumByDirection(Direction.OUT, from, to),
        cashRecordedMinor = transactionDao.sumByChannelAndDirection(
            channel = Channel.CASH,
            direction = Direction.IN,
            from = from,
            to = to,
        ),
    )

    fun observeSum(direction: Direction, from: Long, to: Long): Flow<Long> =
        transactionDao.observeSumByDirection(direction, from, to)

    suspend fun totalsByCategory(direction: Direction, from: Long, to: Long): List<CategoryTotal> =
        transactionDao.totalsByCategory(direction, from, to)

    suspend fun totalsByChannel(direction: Direction, from: Long, to: Long): List<ChannelTotal> =
        transactionDao.totalsByChannel(direction, from, to)

    suspend fun countUncategorised(from: Long, to: Long): Int =
        transactionDao.countUncategorised(from, to)

    suspend fun sumUncategorised(from: Long, to: Long): Long =
        transactionDao.sumUncategorised(from, to)

    suspend fun countInPeriod(from: Long, to: Long): Int =
        transactionDao.countInPeriod(from, to)

    /** Money in through a message, with hand-entered cash left out. See reconciliation. */
    suspend fun sumCapturedIn(from: Long, to: Long): Long =
        transactionDao.sumByDirectionExcludingChannel(Direction.IN, Channel.CASH, from, to)

    /** Flagged possible duplicates among the money counted by [sumCapturedIn]. */
    suspend fun possibleDuplicateTotals(from: Long, to: Long): PossibleDuplicateTotals =
        transactionDao.possibleDuplicateTotals(Direction.IN, Channel.CASH, from, to)

    suspend fun getUnmatchedBetween(from: Long, to: Long): List<RawMessage> =
        rawMessageDao.getUnmatchedBetween(from, to).map { it.toDomain() }

    /** Rows a human touched since [since] — edited, categorised, deleted or restored. */
    suspend fun countTouchedSince(since: Long, from: Long, to: Long): Int =
        transactionDao.countTouchedSince(since, from, to)

    // --- Raw messages -------------------------------------------------------------
    // Raw messages live here rather than in their own repository because every
    // meaningful operation on one is really an operation on the transaction it did or
    // did not become.

    /** Returns true when the message was new; false when the dedup index rejected it. */
    suspend fun insertRawMessage(message: RawMessage): Boolean =
        rawMessageDao.insertIgnoringDuplicates(message.toEntity()) != -1L

    suspend fun getRawMessage(id: String): RawMessage? =
        rawMessageDao.getById(id)?.toDomain()

    fun observeUnmatchedGroups(): Flow<List<UnmatchedGroup>> =
        rawMessageDao.observeUnmatchedGroupedBySender()
            .map { groups -> groups.map { it.toDomain() } }

    suspend fun getUnmatchedBySender(sender: String): List<RawMessage> =
        rawMessageDao.getUnmatchedBySender(sender).map { it.toDomain() }

    suspend fun getByParseState(state: ParseState): List<RawMessage> =
        rawMessageDao.getByParseState(state).map { it.toDomain() }

    fun observeUnmatchedCount(): Flow<Int> =
        rawMessageDao.observeCountByParseState(ParseState.UNMATCHED)

    suspend fun markRawMessageResolved(id: String, state: ParseState, matchedRuleId: String?) =
        rawMessageDao.markResolved(id, state, matchedRuleId)

    private companion object {
        const val PAGE_SIZE = 40
    }
}
