package et.alpha.mezgeb.domain.model

/**
 * Domain models.
 *
 * Repositories hand these out; nothing above `data/` sees a Room entity. They are
 * near-identical to the entities today, which is fine — the point is that when an
 * entity grows a storage-shaped field (a denormalised column, a migration shim),
 * the UI does not have to learn about it.
 */

/** An unparsed captured notification or SMS, stored verbatim. */
data class RawMessage(
    val id: String,
    val sender: String,
    /** Never logged. See .md hard constraint #5. */
    val body: String,
    val contentHash: String,
    val receivedAt: Long,
    val source: CaptureSource,
    val parseState: ParseState,
    val matchedRuleId: String?,
)

/** A parsed money movement. */
data class Transaction(
    val id: String,
    val rawMessageId: String?,
    val direction: Direction,
    val amountMinor: Long,
    val currency: String,
    val channel: Channel,
    val counterpartyName: String?,
    val counterpartyPhone: String?,
    val providerTxnId: String?,
    val balanceAfterMinor: Long?,
    val occurredAt: Long,
    val categoryId: String?,
    val note: String?,
    val isReconciled: Boolean,
    val reconciliationSessionId: String?,
    val isDeleted: Boolean,
    val editedAt: Long?,
) {
    /** True when the user has hand-edited a parsed field. Surfaced in the UI. */
    val isEdited: Boolean get() = editedAt != null
}

data class Customer(
    val id: String,
    val name: String,
    val phone: String?,
    val note: String?,
    val createdAt: Long,
    /** Off the books, but their history is kept. */
    val isArchived: Boolean = false,
)

/** A customer plus their outstanding balance. Negative means the shop owes them. */
data class CustomerBalance(
    val customer: Customer,
    val balanceMinor: Long,
    /** When credit was first given and not yet cleared. Null when they owe nothing. */
    val oldestUnpaidAt: Long?,
) {
    val owesMoney: Boolean get() = balanceMinor > 0
}

data class CreditEntry(
    val id: String,
    val customerId: String,
    val kind: CreditKind,
    /** Always positive; [kind] carries the sign. */
    val amountMinor: Long,
    val occurredAt: Long,
    val note: String?,
    val linkedTransactionId: String?,
)

/** One line of a customer's statement: the entry plus the balance after it. */
data class CreditStatementLine(
    val entry: CreditEntry,
    val runningBalanceMinor: Long,
)

data class Category(
    val id: String,
    val name: String,
    val kind: CategoryKind,
    val colorHex: String,
    val isSystem: Boolean,
)

data class CategoryRule(
    val id: String,
    val matchField: MatchField,
    val matchType: MatchType,
    val pattern: String,
    val categoryId: String,
    val priority: Int,
)

/** A versioned pattern that turns a [RawMessage] into a [Transaction]. */
data class ParserRule(
    val id: String,
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
    val matchCount: Long,
)

data class ReconciliationSession(
    val id: String,
    val periodStart: Long,
    val periodEnd: Long,
    /** When the period was opened, which is not when it starts. */
    val openedAt: Long,
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

/** A group of unmatched messages from one sender, for the review queue. */
data class UnmatchedGroup(
    val sender: String,
    val messageCount: Int,
    val latestReceivedAt: Long,
)
