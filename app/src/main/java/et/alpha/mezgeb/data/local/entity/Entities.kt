package et.alpha.mezgeb.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import et.alpha.mezgeb.domain.model.CaptureSource
import et.alpha.mezgeb.domain.model.CategoryKind
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.MatchField
import et.alpha.mezgeb.domain.model.MatchType
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.domain.model.SessionStatus

/**
 * Room entities, transcribed from 03-ARCHITECTURE.md section 4.
 *
 * Two invariants hold throughout and are worth restating because everything
 * downstream assumes them:
 *  - money is `Long` minor units (santim), never a floating-point type;
 *  - timestamps are epoch milliseconds UTC.
 */

/**
 * An unparsed captured notification or SMS, stored verbatim.
 *
 * [contentHash] carries a unique index and is the fallback deduplication key:
 * SHA-256 over normalised sender and body, deliberately excluding timestamps,
 * because the same payment seen via notification and via SMS import will carry
 * timestamps a few hundred milliseconds apart.
 *
 * [body] is never logged. See .md hard constraint #5.
 */
@Entity(
    tableName = "raw_messages",
    indices = [
        Index(value = ["contentHash"], unique = true),
        Index(value = ["parseState"]),
        Index(value = ["sender"]),
    ],
)
@Serializable
data class RawMessageEntity(
    @PrimaryKey val id: String,
    val sender: String,
    val body: String,
    val contentHash: String,
    val receivedAt: Long,
    val source: CaptureSource,
    val parseState: ParseState,
    val matchedRuleId: String?,
)

/** A parsed money movement. In or out. Linked to at most one [RawMessageEntity]. */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = RawMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawMessageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["occurredAt"]),
        Index(value = ["counterpartyPhone"]),
        Index(value = ["categoryId"]),
        Index(value = ["rawMessageId"]),
        Index(value = ["providerTxnId"]),
        Index(value = ["reconciliationSessionId"]),
    ],
)
@Serializable
data class TransactionEntity(
    @PrimaryKey val id: String,
    /** Null for manual cash entries, which have no originating message. */
    val rawMessageId: String?,
    val direction: Direction,
    val amountMinor: Long,
    val currency: String = "ETB",
    val channel: Channel,
    val counterpartyName: String?,
    /** Normalised to +251 E.164 by the parser. */
    val counterpartyPhone: String?,
    val providerTxnId: String?,
    val balanceAfterMinor: Long?,
    val occurredAt: Long,
    val categoryId: String?,
    val note: String?,
    val isReconciled: Boolean = false,
    val reconciliationSessionId: String?,
    /** Soft delete only — a shop's ledger never loses rows outright. */
    val isDeleted: Boolean = false,
    /**
     * When a human last changed a value the parser extracted — the amount, direction,
     * channel, counterparty or time. Drives the "edited" marker in the ledger, which
     * means "this no longer says what the message said".
     *
     * Filing a transaction under a category does not qualify, and neither does adding
     * a note: both leave every parsed value exactly as the message had it. Use
     * [touchedAt] for "a human did something to this row".
     */
    val editedAt: Long?,
    /**
     * When a human last did anything to this row: edited a parsed value, filed it
     * under a category, deleted it or restored it.
     *
     * Reconciliation asks this rather than [editedAt], because a period whose figures
     * moved after it was opened moved for any of those reasons, not only the first.
     *
     * Defaulted so a backup written before this column existed still restores.
     */
    val touchedAt: Long? = null,
)

@Entity(tableName = "customers")
@Serializable
data class CustomerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String?,
    val note: String?,
    val createdAt: Long,
    /**
     * Archived customers drop out of the list and the totals but keep every entry.
     *
     * Deleting a customer would cascade their credit history away, and a shop that
     * settles up with someone still needs to be able to answer "what did we do
     * together last year". Archiving is the operation people actually want when they
     * reach for delete.
     */
    val isArchived: Boolean = false,
)

/** Money a customer owes, has repaid, or an adjustment to that balance. */
@Entity(
    tableName = "credit_entries",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["customerId"]),
        Index(value = ["occurredAt"]),
        Index(value = ["linkedTransactionId"]),
    ],
)
@Serializable
data class CreditEntryEntity(
    @PrimaryKey val id: String,
    val customerId: String,
    val kind: CreditKind,
    /** Always positive; [kind] carries the sign. */
    val amountMinor: Long,
    val occurredAt: Long,
    val note: String?,
    /** Set when a repayment has been matched to a captured payment. */
    val linkedTransactionId: String?,
)

@Entity(tableName = "categories")
@Serializable
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: CategoryKind,
    val colorHex: String,
    val isSystem: Boolean,
)

@Entity(
    tableName = "category_rules",
    indices = [Index(value = ["categoryId"]), Index(value = ["priority"])],
)
@Serializable
data class CategoryRuleEntity(
    @PrimaryKey val id: String,
    val matchField: MatchField,
    val matchType: MatchType,
    val pattern: String,
    val categoryId: String,
    val priority: Int,
)

/**
 * A versioned pattern that turns a [RawMessageEntity] into a [TransactionEntity].
 *
 * Bundled rules ship as a JSON asset with `isUserCreated = false` and are replaced by
 * id on app update. Rules a shop taught itself have `isUserCreated = true` and are
 * never touched by an update.
 */
@Entity(
    tableName = "parser_rules",
    indices = [Index(value = ["priority"]), Index(value = ["isEnabled"])],
)
@Serializable
data class ParserRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val senderPattern: String,
    val channel: Channel,
    val bodyPattern: String,
    val fieldMapJson: String,
    val directionStrategy: String,
    val directionKeywordsJson: String?,
    val version: Int,
    val isUserCreated: Boolean,
    val isEnabled: Boolean,
    val priority: Int,
    val createdAt: Long,
    /** Local telemetry only. Never leaves the device. */
    val matchCount: Long,
)

/**
 * A closed period comparing expected against captured totals.
 *
 * Every figure is snapshotted at close. Later edits to the underlying transactions do
 * not retroactively change a closed period — that is the point of closing one.
 */
@Entity(
    tableName = "reconciliation_sessions",
    indices = [Index(value = ["periodStart"]), Index(value = ["status"])],
)
@Serializable
data class ReconciliationSessionEntity(
    @PrimaryKey val id: String,
    val periodStart: Long,
    val periodEnd: Long,
    /**
     * When the period was opened, which is not the same as when it starts.
     *
     * "Manual edits made inside the period since it was opened" is one of the ranked
     * gap explanations, and a shop reconciling yesterday opens the period today —
     * measuring from [periodStart] would count edits made before anyone was looking.
     */
    val openedAt: Long = 0L,
    val expectedTotalMinor: Long?,
    val capturedInMinor: Long,
    val capturedOutMinor: Long,
    val cashRecordedMinor: Long,
    val creditGivenMinor: Long,
    val creditRepaidMinor: Long,
    val status: SessionStatus,
    val closedAt: Long?,
    val note: String?,
)
