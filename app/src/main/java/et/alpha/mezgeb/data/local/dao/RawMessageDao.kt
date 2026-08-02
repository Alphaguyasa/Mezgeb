package et.alpha.mezgeb.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import et.alpha.mezgeb.data.local.entity.RawMessageEntity
import et.alpha.mezgeb.domain.model.ParseState
import kotlinx.coroutines.flow.Flow

/** One row per unmatched sender, for grouping the review queue. */
data class UnmatchedSenderGroup(
    val sender: String,
    val messageCount: Int,
    val latestReceivedAt: Long,
)

@Dao
interface RawMessageDao {

    /**
     * Returns the new rowId, or -1 when the unique `contentHash` index rejected the
     * insert as a duplicate. Callers use that -1 to decide whether to parse: this is
     * where deduplication actually happens, so it must stay `IGNORE`.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicates(message: RawMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringDuplicates(messages: List<RawMessageEntity>): List<Long>

    @Query("SELECT * FROM raw_messages WHERE id = :id")
    suspend fun getById(id: String): RawMessageEntity?

    @Query("SELECT * FROM raw_messages WHERE contentHash = :contentHash LIMIT 1")
    suspend fun getByContentHash(contentHash: String): RawMessageEntity?

    @Query("SELECT * FROM raw_messages WHERE parseState = :state ORDER BY receivedAt DESC")
    fun observeByParseState(state: ParseState): Flow<List<RawMessageEntity>>

    @Query("SELECT * FROM raw_messages WHERE parseState = :state ORDER BY receivedAt DESC")
    suspend fun getByParseState(state: ParseState): List<RawMessageEntity>

    @Query(
        """
        SELECT sender AS sender,
               COUNT(*) AS messageCount,
               MAX(receivedAt) AS latestReceivedAt
        FROM raw_messages
        WHERE parseState = 'UNMATCHED'
        GROUP BY sender
        ORDER BY latestReceivedAt DESC
        """,
    )
    fun observeUnmatchedGroupedBySender(): Flow<List<UnmatchedSenderGroup>>

    @Query("SELECT * FROM raw_messages WHERE parseState = 'UNMATCHED' AND sender = :sender ORDER BY receivedAt DESC")
    suspend fun getUnmatchedBySender(sender: String): List<RawMessageEntity>

    @Query("SELECT COUNT(*) FROM raw_messages WHERE parseState = :state")
    fun observeCountByParseState(state: ParseState): Flow<Int>

    /**
     * Unreadable messages that arrived inside a period.
     *
     * Reconciliation asks how much of a gap these could account for, so it needs the
     * messages themselves rather than a count — the amount, if there is one, is in
     * the body.
     */
    @Query(
        """
        SELECT * FROM raw_messages
        WHERE parseState = 'UNMATCHED' AND receivedAt >= :from AND receivedAt <= :to
        ORDER BY receivedAt DESC
        """,
    )
    suspend fun getUnmatchedBetween(from: Long, to: Long): List<RawMessageEntity>

    @Query("UPDATE raw_messages SET parseState = :state, matchedRuleId = :matchedRuleId WHERE id = :id")
    suspend fun markResolved(id: String, state: ParseState, matchedRuleId: String?)

    /**
     * Every stored message, for the one job that has to look at all of them: finding
     * credentials an earlier build kept. Read in pages so a phone with a backfilled
     * inbox does not load thousands of bodies at once.
     */
    @Query("SELECT * FROM raw_messages ORDER BY receivedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<RawMessageEntity>

    @Query("SELECT COUNT(*) FROM raw_messages")
    suspend fun count(): Int

    @Query("SELECT * FROM raw_messages ORDER BY receivedAt ASC")
    suspend fun getAll(): List<RawMessageEntity>

    @Query("DELETE FROM raw_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM raw_messages")
    suspend fun deleteAll()
}
