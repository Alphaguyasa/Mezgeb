package et.alpha.mezgeb.capture

import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.domain.model.CaptureSource
import et.alpha.mezgeb.domain.model.CategoryRule
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.MatchField
import et.alpha.mezgeb.domain.model.MatchType
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.domain.model.RawMessage
import et.alpha.mezgeb.parser.MessageParser
import et.alpha.mezgeb.parser.model.Channel
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.parser.rule.DirectionStrategy
import et.alpha.mezgeb.parser.rule.ParserRule
import et.alpha.mezgeb.util.IdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The dedup tests are the point of this file.
 *
 * A shop running the sideloaded build has both capture paths live at once, so every
 * payment arrives twice — once as a notification, once again the moment an import
 * runs. Getting this wrong doubles a day's takings, and doubling is worse than
 * missing: a missing payment is noticed, a doubled one is believed.
 */
class CaptureCoordinatorTest : DatabaseTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var settingsHarness: SettingsHarness
    private lateinit var settings: SettingsRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var rules: RuleRepository
    private lateinit var coordinator: CaptureCoordinator

    @Before
    fun createCoordinator() {
        settingsHarness = SettingsHarness(tempFolder.root)
        settings = settingsHarness.repository
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
        rules = RuleRepository(db.categoryDao(), db.categoryRuleDao(), db.parserRuleDao())
        coordinator = CaptureCoordinator(
            transactions = transactions,
            rules = rules,
            parserProvider = FakeParserProvider(MessageParser(TEST_RULES)),
            settings = settings,
            ids = SequentialIds(),
            clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
        )
    }

    @After
    fun stopDataStore() {
        settingsHarness.close()
    }

    // --- The happy path -------------------------------------------------------------

    @Test
    fun `a parsed payment becomes a transaction linked to its message`() = runTest {
        val outcome = coordinator.capture(notification(TELEBIRR_NOTIFICATION))

        val recorded = outcome as CaptureOutcome.Recorded
        val transaction = transactions.getTransaction(recorded.transactionId)
        assertNotNull(transaction)
        assertEquals(45_000L, transaction?.amountMinor)
        assertEquals(Direction.IN, transaction?.direction)
        assertEquals("ABEBE", transaction?.counterpartyName)
        assertEquals("CJ4T5X9AB1", transaction?.providerTxnId)
        assertEquals(recorded.rawMessageId, transaction?.rawMessageId)

        val raw = transactions.getRawMessage(recorded.rawMessageId)
        assertEquals(ParseState.PARSED, raw?.parseState)
        assertEquals("telebirr_received", raw?.matchedRuleId)
        // Stored normalised, so the review queue groups one sender as one sender.
        assertEquals("127", raw?.sender)
    }

    @Test
    fun `a matching category rule is applied as the transaction is written`() = runTest {
        rules.insertCategoryRule(
            CategoryRule(
                id = "cr1",
                matchField = MatchField.COUNTERPARTY_NAME,
                matchType = MatchType.CONTAINS,
                pattern = "abebe",
                categoryId = "cat_sales",
                priority = 10,
            ),
        )

        val recorded = coordinator.capture(notification(TELEBIRR_NOTIFICATION))
            as CaptureOutcome.Recorded

        assertEquals("cat_sales", transactions.getTransaction(recorded.transactionId)?.categoryId)
    }

    // --- Tier one: the content hash --------------------------------------------------

    @Test
    fun `the same message from both sources is captured once`() = runTest {
        val first = coordinator.capture(notification(TELEBIRR_NOTIFICATION))
        val second = coordinator.capture(
            imported(TELEBIRR_NOTIFICATION, receivedAt = NOW + 380),
        )

        assertTrue(first is CaptureOutcome.Recorded)
        assertEquals(CaptureOutcome.Duplicate(DedupTier.CONTENT_HASH), second)
        assertEquals(1, allTransactions().size)
    }

    // --- Tier two: the provider transaction id ---------------------------------------

    @Test
    fun `the same payment worded differently is caught by its transaction id`() = runTest {
        // The messaging app posts a trimmed notification; the inbox holds the full SMS.
        // Different text, so the content hash cannot catch this one.
        val first = coordinator.capture(notification(TELEBIRR_NOTIFICATION))
        val second = coordinator.capture(imported(TELEBIRR_FULL_SMS, receivedAt = NOW + 380))

        assertTrue(first is CaptureOutcome.Recorded)
        assertEquals(CaptureOutcome.Duplicate(DedupTier.PROVIDER_TXN_ID), second)
        assertEquals(1, allTransactions().size)

        // Both raw messages are kept. The second is evidence that the two sources
        // disagree about the wording, and deleting it would let the next import raise
        // it all over again.
        assertEquals(1, transactions.getByParseState(ParseState.DUPLICATE).size)
    }

    @Test
    fun `the same reference from two different providers is two payments`() = runTest {
        // References are unique per provider, not across providers. A match across
        // channels would drop the bank payment as a "duplicate" of the telebirr one.
        val telebirr = coordinator.capture(notification(TELEBIRR_NOTIFICATION))
        val bank = coordinator.capture(
            notification(
                "Your account has been credited with ETB 300.00. Ref CJ4T5X9AB1.",
                sender = "CBE",
                receivedAt = NOW + 60_000,
            ),
        )

        assertTrue(telebirr is CaptureOutcome.Recorded)
        assertTrue(bank is CaptureOutcome.Recorded)
        assertEquals(2, allTransactions().size)
    }

    @Test
    fun `a deleted payment is not brought back by its second copy`() = runTest {
        val first = coordinator.capture(notification(TELEBIRR_NOTIFICATION))
        transactions.softDelete((first as CaptureOutcome.Recorded).transactionId, NOW + 1_000)

        // The inbox copy is worded differently, so only the reference can catch it.
        val second = coordinator.capture(imported(TELEBIRR_FULL_SMS, receivedAt = NOW + 2_000))

        assertEquals(CaptureOutcome.Duplicate(DedupTier.PROVIDER_TXN_ID), second)
        assertTrue(allTransactions().isEmpty())
    }

    @Test
    fun `different payments that happen to share an amount are both recorded`() = runTest {
        val first = coordinator.capture(notification(TELEBIRR_NOTIFICATION))
        val second = coordinator.capture(
            notification(
                "You have received ETB 450.00 from KEBEDE. Your transaction number is " +
                    "BX9911QQ42.",
                receivedAt = NOW + 60_000,
            ),
        )

        assertTrue(first is CaptureOutcome.Recorded)
        assertTrue(second is CaptureOutcome.Recorded)
        assertEquals(2, allTransactions().size)
    }

    @Test
    fun `messages with no transaction id at all still dedup on content`() = runTest {
        // CBE's credit alert carries no reference the parser can extract, so the
        // content hash is the only key there is.
        val first = coordinator.capture(notification(CBE_CREDIT, sender = "CBE"))
        val again = coordinator.capture(
            imported(CBE_CREDIT, sender = "CBE", receivedAt = NOW + 200),
        )
        val different = coordinator.capture(
            notification(CBE_CREDIT_LATER, sender = "CBE", receivedAt = NOW + DAY),
        )

        assertTrue(first is CaptureOutcome.Recorded)
        assertEquals(CaptureOutcome.Duplicate(DedupTier.CONTENT_HASH), again)
        assertTrue(different is CaptureOutcome.Recorded)
        assertEquals(2, allTransactions().size)
    }

    // --- Tier three: the lookalike flag ----------------------------------------------

    @Test
    fun `the same payment worded differently with no reference is recorded and flagged`() =
        runTest {
            // The notification is trimmed and the inbox SMS is not, so the hash differs,
            // and neither carries a reference. Before the lookalike check this was two
            // silent transactions for one payment.
            val first = coordinator.capture(notification(CBE_CREDIT_SHORT, sender = "CBE"))
                as CaptureOutcome.Recorded
            val second = coordinator.capture(
                imported(CBE_CREDIT, sender = "CBE", receivedAt = NOW + 20_000),
            ) as CaptureOutcome.Recorded

            // Recorded, not dropped: only the shop owner can say it is the same payment.
            assertEquals(2, allTransactions().size)
            assertEquals(
                first.transactionId,
                transactions.getTransaction(second.transactionId)?.possibleDuplicateOfId,
            )
            // The row that came first is the original and is left alone.
            assertNull(transactions.getTransaction(first.transactionId)?.possibleDuplicateOfId)
        }

    @Test
    fun `a reference on only one side does not stop the flag`() = runTest {
        val withRef = coordinator.capture(
            notification(
                "Your account has been credited with ETB 450.00. Ref FT26A1B2C3.",
                sender = "CBE",
            ),
        ) as CaptureOutcome.Recorded
        val without = coordinator.capture(
            imported(CBE_CREDIT, sender = "CBE", receivedAt = NOW + 5_000),
        ) as CaptureOutcome.Recorded

        assertEquals(
            withRef.transactionId,
            transactions.getTransaction(without.transactionId)?.possibleDuplicateOfId,
        )
    }

    @Test
    fun `two payments with different references are never flagged`() = runTest {
        // Had the references matched, tier two would have caught them. They did not,
        // so these are two payments, however alike the rest of them looks.
        coordinator.capture(notification(TELEBIRR_NOTIFICATION))
        val second = coordinator.capture(
            notification(
                "You have received ETB 450.00 from ABEBE. Your transaction number is " +
                    "BX9911QQ42.",
                receivedAt = NOW + 10_000,
            ),
        ) as CaptureOutcome.Recorded

        assertNull(transactions.getTransaction(second.transactionId)?.possibleDuplicateOfId)
    }

    @Test
    fun `the same amount more than a minute apart is not flagged`() = runTest {
        coordinator.capture(notification(CBE_CREDIT_SHORT, sender = "CBE"))
        val later = coordinator.capture(
            imported(CBE_CREDIT, sender = "CBE", receivedAt = NOW + 60_001),
        ) as CaptureOutcome.Recorded

        assertNull(transactions.getTransaction(later.transactionId)?.possibleDuplicateOfId)
    }

    @Test
    fun `a different amount at the same moment is not flagged`() = runTest {
        coordinator.capture(notification(CBE_CREDIT_SHORT, sender = "CBE"))
        val other = coordinator.capture(
            imported(
                "Your account has been credited with ETB 460.00 on 01/07/2026 at 09:14.",
                sender = "CBE",
                receivedAt = NOW + 1_000,
            ),
        ) as CaptureOutcome.Recorded

        assertNull(transactions.getTransaction(other.transactionId)?.possibleDuplicateOfId)
    }

    // --- The messages that do not become transactions --------------------------------

    @Test
    fun `an unreadable message from a financial sender waits in the review queue`() = runTest {
        val outcome = coordinator.capture(
            notification("Dear customer, beware of fraud. Never share your PIN.", sender = "CBE"),
        )

        val queued = outcome as CaptureOutcome.Queued
        assertEquals(QueueReason.NO_RULE_MATCHED, queued.reason)
        assertEquals(ParseState.UNMATCHED, transactions.getRawMessage(queued.rawMessageId)?.parseState)
        assertTrue(allTransactions().isEmpty())
    }

    @Test
    fun `a message from a person is never written down at all`() = runTest {
        // The onboarding screen promises "messages from people are ignored", and a
        // shop owner reads ignored as not kept. Storing one and labelling it IGNORED
        // kept the text — which a single plain SMS on a real phone demonstrated.
        val outcome = coordinator.capture(
            notification("see you at the shop tomorrow", sender = "+251911000009"),
        )

        assertEquals(CaptureOutcome.DeclinedAsNotFinancial, outcome)
        assertTrue(transactions.getByParseState(ParseState.IGNORED).isEmpty())
        assertTrue(transactions.getByParseState(ParseState.UNMATCHED).isEmpty())
        assertEquals(0, transactions.observeUnmatchedCount().first())
    }

    @Test
    fun `an advert from a promotional shortcode is not kept either`() = runTest {
        val outcome = coordinator.capture(
            notification("Get 5GB for 99 birr this week only", sender = "PROMO-4455"),
        )

        assertEquals(CaptureOutcome.DeclinedAsNotFinancial, outcome)
    }

    @Test
    fun `noise from a financial sender is kept, because that distinction matters`() = runTest {
        // CBE sends fraud warnings from the same shortcode as payments. Those are
        // stored and marked IGNORED: "we could not read this" and "this was never
        // ours to keep" are different answers, and the review queue depends on the
        // difference.
        val outcome = coordinator.capture(
            notification("Dear customer, beware of fraud.", sender = "CBE"),
        )

        val queued = outcome as CaptureOutcome.Queued
        assertEquals(
            ParseState.UNMATCHED,
            transactions.getRawMessage(queued.rawMessageId)?.parseState,
        )
    }

    // --- Re-parsing the review queue -------------------------------------------------

    @Test
    fun `re-parsing picks up messages a newly taught rule can now read`() = runTest {
        // Queued while no rule matched it — the state the review screen shows.
        transactions.insertRawMessage(
            RawMessage(
                id = "queued",
                sender = "CBE",
                body = CBE_CREDIT,
                contentHash = "hash_queued",
                receivedAt = NOW,
                source = CaptureSource.NOTIFICATION,
                parseState = ParseState.UNMATCHED,
                matchedRuleId = null,
            ),
        )

        val summary = coordinator.reparseUnmatched()

        assertEquals(1, summary.scanned)
        assertEquals(1, summary.recorded)
        assertEquals(1, allTransactions().size)
        assertEquals(ParseState.PARSED, transactions.getRawMessage("queued")?.parseState)
    }

    @Test
    fun `re-parsing does not record a payment that is already in the ledger`() = runTest {
        coordinator.capture(notification(TELEBIRR_NOTIFICATION))
        // The same payment, worded differently, still sitting in the queue.
        transactions.insertRawMessage(
            RawMessage(
                id = "queued",
                sender = "127",
                body = TELEBIRR_FULL_SMS,
                contentHash = "hash_queued",
                receivedAt = NOW,
                source = CaptureSource.SMS_IMPORT,
                parseState = ParseState.UNMATCHED,
                matchedRuleId = null,
            ),
        )

        val summary = coordinator.reparseUnmatched()

        assertEquals(1, summary.duplicates)
        assertEquals(0, summary.recorded)
        assertEquals(1, allTransactions().size)
    }

    @Test
    fun `a message no rule can still read stays exactly where it was`() = runTest {
        val queued = coordinator.capture(
            notification("Dear customer, beware of fraud.", sender = "CBE"),
        ) as CaptureOutcome.Queued

        val summary = coordinator.reparseUnmatched()

        assertEquals(0, summary.changed)
        assertEquals(
            ParseState.UNMATCHED,
            transactions.getRawMessage(queued.rawMessageId)?.parseState,
        )
    }

    // --- Credentials ------------------------------------------------------------------

    @Test
    fun `a message that states a PIN is refused before anything is written`() = runTest {
        // Found on a real device: a bank reset a mobile-banking PIN and sent it in
        // cleartext from a sender the allowlist admits. Nothing about it is stored.
        val outcome = coordinator.capture(
            notification(
                "Your Hibir Mobile TPIN has been reset successfully. New TPIN is 0000",
                sender = "HIBRETBANK",
            ),
        )

        assertEquals(CaptureOutcome.DeclinedAsCredential, outcome)
        assertTrue(transactions.getByParseState(ParseState.UNMATCHED).isEmpty())
        assertTrue(transactions.getByParseState(ParseState.IGNORED).isEmpty())
        assertTrue(allTransactions().isEmpty())
    }

    @Test
    fun `a payment message carrying a security footer is still recorded`() = runTest {
        // The expensive mistake in the other direction: refusing this would cost the
        // shop a payment, silently.
        val outcome = coordinator.capture(
            notification("$CBE_CREDIT Never share your PIN.", sender = "CBE"),
        )

        assertTrue(outcome is CaptureOutcome.Recorded)
        assertEquals(1, allTransactions().size)
    }

    // --- Capture health --------------------------------------------------------------

    @Test
    fun `a notification refreshes the capture health stamp`() = runTest {
        coordinator.capture(notification(TELEBIRR_NOTIFICATION))

        assertEquals(NOW, settings.observeLastCaptureAt().first())
    }

    @Test
    fun `an import does not refresh the capture health stamp`() = runTest {
        // Otherwise a backfill would make a dead listener look alive, which is exactly
        // the failure the stamp exists to expose.
        coordinator.capture(imported(TELEBIRR_NOTIFICATION))

        assertNull(settings.observeLastCaptureAt().first())
    }

    // --- Fixtures --------------------------------------------------------------------

    private suspend fun allTransactions() = transactions.listTransactions(TransactionFilter())

    private fun notification(
        body: String,
        sender: String = "127",
        receivedAt: Long = NOW,
    ) = CapturedMessage(sender, body, receivedAt, CaptureSource.NOTIFICATION)

    private fun imported(
        body: String,
        sender: String = "127",
        receivedAt: Long = NOW,
    ) = CapturedMessage(sender, body, receivedAt, CaptureSource.SMS_IMPORT)

    private class FakeParserProvider(private val parser: MessageParser) : ParserProvider {
        override suspend fun current(): MessageParser = parser
    }

    /** Predictable ids, so a failing assertion names a row instead of a UUID. */
    private class SequentialIds : IdGenerator {
        private var next = 0
        override fun newId(): String = "id_${++next}"
    }

    private companion object {
        const val NOW = T0
        const val DAY = 24 * 60 * 60 * 1000L

        const val TELEBIRR_NOTIFICATION =
            "You have received ETB 450.00 from ABEBE. Your transaction number is CJ4T5X9AB1."

        const val TELEBIRR_FULL_SMS =
            "Dear customer, you have received ETB 450.00 from ABEBE on 01/07/2026. " +
                "Your transaction number is CJ4T5X9AB1. Your balance is ETB 1,200.00."

        const val CBE_CREDIT =
            "Your account has been credited with ETB 450.00 on 01/07/2026 at 09:14."

        /** How the messaging app's notification can render [CBE_CREDIT]: trimmed. */
        const val CBE_CREDIT_SHORT = "Your account has been credited with ETB 450.00 by transfer."

        const val CBE_CREDIT_LATER =
            "Your account has been credited with ETB 450.00 on 02/07/2026 at 16:02."

        val TEST_RULES = listOf(
            ParserRule(
                id = "telebirr_received",
                name = "telebirr received",
                senderPattern = "127",
                channel = Channel.TELEBIRR,
                bodyPattern = "received ETB (?<amount>[\\d,.]+) from (?<name>[A-Za-z]+)" +
                    ".*?transaction number is (?<txn>[A-Z0-9]+)",
                fieldMap = mapOf(
                    "amount" to SemanticField.AMOUNT,
                    "name" to SemanticField.COUNTERPARTY_NAME,
                    "txn" to SemanticField.PROVIDER_TXN_ID,
                ),
                directionStrategy = DirectionStrategy.FIXED_IN,
                priority = 10,
            ),
            ParserRule(
                id = "cbe_credited",
                name = "CBE credited",
                senderPattern = "CBE",
                channel = Channel.BANK,
                bodyPattern = "credited with ETB (?<amount>[\\d,.]+)",
                fieldMap = mapOf("amount" to SemanticField.AMOUNT),
                directionStrategy = DirectionStrategy.FIXED_IN,
                priority = 5,
            ),
            // A bank template that carries a reference, so the same reference string
            // can be seen from two providers.
            ParserRule(
                id = "cbe_credited_ref",
                name = "CBE credited with reference",
                senderPattern = "CBE",
                channel = Channel.BANK,
                bodyPattern = "credited with ETB (?<amount>[\\d,.]+)\\. Ref (?<txn>[A-Z0-9]+)",
                fieldMap = mapOf(
                    "amount" to SemanticField.AMOUNT,
                    "txn" to SemanticField.PROVIDER_TXN_ID,
                ),
                directionStrategy = DirectionStrategy.FIXED_IN,
                priority = 6,
            ),
        )
    }
}
