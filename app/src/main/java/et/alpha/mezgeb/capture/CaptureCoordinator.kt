package et.alpha.mezgeb.capture

import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.domain.model.CaptureSource
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.domain.model.RawMessage
import et.alpha.mezgeb.domain.model.Transaction
import et.alpha.mezgeb.parser.model.ParseOutcome
import et.alpha.mezgeb.parser.model.ParsedFields
import et.alpha.mezgeb.parser.model.UnmatchedReason
import et.alpha.mezgeb.parser.normalize.SenderNormalizer
import et.alpha.mezgeb.parser.privacy.CredentialDetector
import et.alpha.mezgeb.parser.rule.SenderAllowlist
import et.alpha.mezgeb.util.IdGenerator
import et.alpha.mezgeb.util.MoneyFormatter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import et.alpha.mezgeb.domain.model.Channel as DomainChannel
import et.alpha.mezgeb.domain.model.Direction as DomainDirection

/**
 * The single funnel every captured message passes through.
 *
 * ```
 * normalise sender ─ content hash ─ insert RawMessage (IGNORE)
 *        │
 *        ├─ index rejected it ────────────────────────► Duplicate (content hash)
 *        └─ new row
 *              │
 *           parse ─┬─ Ignored ──────────────────────► mark IGNORED
 *                  ├─ Unmatched / Ambiguous ────────► leave in the review queue
 *                  └─ Success
 *                        │
 *                     provider txn id already known? ► Duplicate (strong key)
 *                        │
 *                     looks like a row already there? ─► flag it, but still record it
 *                        │
 *                     insert Transaction and mark PARSED (one write), apply category rules
 * ```
 *
 * Notification capture and inbox import both come through here, so there is exactly
 * one implementation of deduplication and exactly one place for it to be wrong.
 *
 * Calls are serialised on a mutex. The unique index on `contentHash` already makes
 * the first tier race-proof, but the second tier is a read followed by a write — two
 * sources delivering the same payment at the same moment could both find no existing
 * transaction and both insert one. Throughput is irrelevant here (a busy shop sees a
 * few messages a minute; an import is one loop), so the simple correct thing wins.
 */
@Singleton
class CaptureCoordinator @Inject constructor(
    private val transactions: TransactionRepository,
    private val rules: RuleRepository,
    private val parserProvider: ParserProvider,
    private val settings: SettingsRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) {

    private val mutex = Mutex()

    /**
     * The same allowlist the parser uses, applied one step earlier so that a message
     * from outside it is never written down at all.
     */
    private val allowlist = SenderAllowlist.DEFAULT

    suspend fun capture(message: CapturedMessage): CaptureOutcome = mutex.withLock {
        // Before anything is written. Banks send PINs and one-time codes over SMS in
        // cleartext, from the same senders as payment messages, so they pass the
        // allowlist and would otherwise sit in the ledger — visible in the review
        // queue and carried into any backup. Found on a real device, not in a test.
        if (CredentialDetector.containsCredential(message.body)) {
            return@withLock CaptureOutcome.DeclinedAsCredential
        }

        // Also before the insert, and for the same reason. The notification listener
        // sees every message the phone's messaging app posts, most of which is private
        // correspondence. Storing it and marking it IGNORED kept the text; the
        // onboarding screen says messages from people are ignored, and a shop owner
        // reads that as "not kept". Found on a device by sending one plain SMS.
        //
        // Senders that *are* financial still get stored when no rule reads them: that
        // is the review queue, and the difference between "we could not read this" and
        // "this was never ours to keep" is the whole distinction.
        if (!allowlist.allows(message.sender)) {
            return@withLock CaptureOutcome.DeclinedAsNotFinancial
        }

        val rawMessageId = ids.newId()
        val stored = transactions.insertRawMessage(
            RawMessage(
                id = rawMessageId,
                // The normalised form is stored, not the raw one: the review queue
                // groups by sender, and `CBE`, `cbe` and `+251CBE` are one sender with
                // one rule to teach, not three.
                sender = SenderNormalizer.normalize(message.sender),
                body = message.body,
                contentHash = ContentHasher.hash(message.sender, message.body),
                receivedAt = message.receivedAt,
                source = message.source,
                // Optimistic states are a trap: if the process dies between here and
                // the parse, an UNMATCHED row shows up in the review queue, which is
                // recoverable. A PARSED row with no transaction is not.
                parseState = ParseState.UNMATCHED,
                matchedRuleId = null,
            ),
        )
        if (!stored) return@withLock CaptureOutcome.Duplicate(DedupTier.CONTENT_HASH)

        // Only the listener proves the listener is alive. An inbox import is a manual,
        // historical operation, and letting it refresh the health stamp would hide
        // exactly the failure this stamp exists to expose: a listener killed by the
        // vendor's battery manager while the app looks fine.
        if (message.source == CaptureSource.NOTIFICATION) {
            settings.recordCapture(clock.millis())
        }

        when (val outcome = parserProvider.current().parse(
            sender = message.sender,
            body = message.body,
            receivedAt = message.receivedAt,
        )) {
            ParseOutcome.Ignored -> {
                transactions.markRawMessageResolved(rawMessageId, ParseState.IGNORED, null)
                CaptureOutcome.Ignored(rawMessageId)
            }

            is ParseOutcome.Unmatched -> CaptureOutcome.Queued(
                rawMessageId = rawMessageId,
                reason = outcome.reason.toQueueReason(),
            )

            // The row is already UNMATCHED, which is where an ambiguous message
            // belongs: two rules disagreed, and the shop owner decides, not the app.
            is ParseOutcome.Ambiguous -> CaptureOutcome.Queued(
                rawMessageId = rawMessageId,
                reason = QueueReason.AMBIGUOUS,
            )

            is ParseOutcome.Success -> record(
                rawMessageId = rawMessageId,
                body = message.body,
                fields = outcome.fields,
                ruleId = outcome.ruleId,
            )
        }
    }

    /**
     * Runs the parser again over everything sitting in the review queue.
     *
     * A rule taught in the review screen, or shipped by an app update, applies to the
     * messages that are already waiting — otherwise teaching a rule fixes the next
     * message and leaves the fifty identical ones the shop was complaining about.
     *
     * Nothing is deleted and nothing is re-hashed: a message that still matches no
     * rule stays exactly where it was.
     */
    suspend fun reparseUnmatched(
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): ReparseSummary = mutex.withLock {
        val queued = transactions.getByParseState(ParseState.UNMATCHED)
        if (queued.isEmpty()) return@withLock ReparseSummary()

        val parser = parserProvider.current()
        var recorded = 0
        var duplicates = 0
        var ignored = 0
        var scanned = 0

        for (message in queued) {
            onProgress(scanned++, queued.size)
            when (
                val outcome = parser.parse(message.sender, message.body, message.receivedAt)
            ) {
                ParseOutcome.Ignored -> {
                    transactions.markRawMessageResolved(message.id, ParseState.IGNORED, null)
                    ignored++
                }

                is ParseOutcome.Success -> {
                    val result = record(
                        rawMessageId = message.id,
                        body = message.body,
                        fields = outcome.fields,
                        ruleId = outcome.ruleId,
                    )
                    if (result is CaptureOutcome.Recorded) recorded++ else duplicates++
                }

                // Still nothing, or still contested. Leave it for the user.
                is ParseOutcome.Unmatched, is ParseOutcome.Ambiguous -> Unit
            }
        }

        ReparseSummary(
            scanned = queued.size,
            recorded = recorded,
            duplicates = duplicates,
            ignored = ignored,
        )
    }

    /**
     * Records a transaction from fields the user tagged by hand, without a rule.
     *
     * The escape hatch for a message that is worth recording but not worth teaching —
     * a one-off template, or one the user cannot see a pattern in. It goes through the
     * same funnel so that a message resolved by hand is still deduplicated against the
     * same payment arriving from the other source.
     */
    suspend fun resolveManually(
        rawMessageId: String,
        fields: ParsedFields,
    ): CaptureOutcome = mutex.withLock {
        val message = transactions.getRawMessage(rawMessageId)
            ?: return@withLock CaptureOutcome.Duplicate(DedupTier.CONTENT_HASH)
        // Only a message still in the review queue can be resolved. One that was parsed,
        // ignored, marked a duplicate or resolved by hand already, is not the user's to
        // record a second time; the screen that offered it was showing an old state.
        if (message.parseState != ParseState.UNMATCHED) {
            return@withLock CaptureOutcome.Duplicate(DedupTier.SAME_MESSAGE)
        }

        record(
            rawMessageId = rawMessageId,
            body = message.body,
            fields = fields,
            ruleId = null,
            resolvedState = ParseState.RESOLVED_MANUALLY,
        )
    }

    private suspend fun record(
        rawMessageId: String,
        body: String,
        fields: ParsedFields,
        ruleId: String?,
        resolvedState: ParseState = ParseState.PARSED,
    ): CaptureOutcome {
        // One message, one transaction. With no provider reference, nothing below
        // would stop a message that already has a transaction from getting another;
        // the lookalike check would only flag it. A message found here still reading
        // UNMATCHED (an older build wrote the two halves separately) is taken out of
        // the review queue, since its payment is already in the ledger.
        if (transactions.getByRawMessageId(rawMessageId) != null) {
            transactions.markRawMessageResolved(rawMessageId, resolvedState, ruleId)
            return CaptureOutcome.Duplicate(DedupTier.SAME_MESSAGE)
        }

        // Tier two. The provider's own transaction id is authoritative: when the
        // notification and the inbox render the same payment differently enough to
        // survive the content hash, this is what catches it. Scoped to the channel,
        // since references are only unique per provider, and matched against deleted
        // rows too, so a payment the shop deleted is not resurrected by its second copy.
        val existing = fields.providerTxnId?.let {
            transactions.getByProviderTxnId(DomainChannel.valueOf(fields.channel.name), it)
        }
        if (existing != null) {
            transactions.markRawMessageResolved(
                id = rawMessageId,
                state = ParseState.DUPLICATE,
                matchedRuleId = ruleId,
            )
            return CaptureOutcome.Duplicate(DedupTier.PROVIDER_TXN_ID)
        }

        // Tier three, for a payment with no reference to compare (or only one side
        // carrying one). The notification and the inbox can word one payment
        // differently, and without a reference that used to become two transactions.
        // A lookalike is flagged, never dropped: two real payments of the same amount
        // inside one minute do happen, and only the shop owner can tell which this is.
        val lookalike = transactions.findLookalike(
            channel = DomainChannel.valueOf(fields.channel.name),
            direction = DomainDirection.valueOf(fields.direction.name),
            amountMinor = fields.amountMinor,
            balanceAfterMinor = fields.balanceAfterMinor,
            occurredAt = fields.occurredAt,
            providerTxnId = fields.providerTxnId,
            windowMillis = LOOKALIKE_WINDOW_MILLIS,
        )

        val transactionId = ids.newId()
        // One write, so the message never reads UNMATCHED while its transaction exists.
        transactions.insertForMessage(
            transaction = fields.toTransaction(transactionId, rawMessageId, body)
                .copy(possibleDuplicateOfId = lookalike?.id),
            rawMessageId = rawMessageId,
            state = resolvedState,
            matchedRuleId = ruleId,
        )
        ruleId?.let { rules.recordParserRuleMatch(it) }
        return CaptureOutcome.Recorded(rawMessageId, transactionId)
    }

    private suspend fun ParsedFields.toTransaction(
        transactionId: String,
        rawMessageId: String,
        body: String,
    ) = Transaction(
        id = transactionId,
        rawMessageId = rawMessageId,
        direction = DomainDirection.valueOf(direction.name),
        amountMinor = amountMinor,
        currency = MoneyFormatter.DEFAULT_CURRENCY,
        channel = DomainChannel.valueOf(channel.name),
        counterpartyName = counterpartyName,
        counterpartyPhone = counterpartyPhone,
        providerTxnId = providerTxnId,
        balanceAfterMinor = balanceAfterMinor,
        occurredAt = occurredAt,
        categoryId = CategoryRuleMatcher.categoryIdFor(
            rules = rules.getCategoryRules(),
            counterpartyName = counterpartyName,
            counterpartyPhone = counterpartyPhone,
            body = body,
        ),
        note = null,
        isReconciled = false,
        reconciliationSessionId = null,
        isDeleted = false,
        // Both null, not "now". No human has been near this row: the parser wrote it
        // from the message, which is neither an edit nor a touch.
        editedAt = null,
        touchedAt = null,
    )
}

/**
 * How far apart two lookalike messages may be and still be flagged as one payment.
 *
 * A minute either side. The two capture paths see the same payment within seconds of
 * each other, and a message that states its own time states it to the minute, so a
 * wider window would only add unrelated payments to the flag.
 */
private const val LOOKALIKE_WINDOW_MILLIS = 60_000L

private fun UnmatchedReason.toQueueReason(): QueueReason = when (this) {
    UnmatchedReason.NO_RULE_FOR_SENDER,
    UnmatchedReason.NO_PATTERN_MATCHED,
    -> QueueReason.NO_RULE_MATCHED

    UnmatchedReason.FIELD_EXTRACTION_FAILED,
    UnmatchedReason.NO_AMOUNT,
    -> QueueReason.EXTRACTION_FAILED
}
