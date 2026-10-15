package et.alpha.mezgeb.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.data.local.entity.CategoryEntity
import et.alpha.mezgeb.data.local.entity.CreditEntryEntity
import et.alpha.mezgeb.data.local.entity.CustomerEntity
import et.alpha.mezgeb.data.local.entity.ParserRuleEntity
import et.alpha.mezgeb.data.local.entity.RawMessageEntity
import et.alpha.mezgeb.data.local.entity.TransactionEntity
import et.alpha.mezgeb.domain.model.CaptureSource
import et.alpha.mezgeb.domain.model.CategoryKind
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.ParseState
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base class for DAO tests.
 *
 * Room's in-memory database still needs a `Context`, so these run under Robolectric
 * rather than on a device — they stay fast enough to run on every build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
abstract class DatabaseTest {

    protected lateinit var db: MezgebDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MezgebDatabase::class.java)
            .allowMainThreadQueries()
            // Run queries on the calling thread. Without this, Room resumes suspend
            // functions and emits flows from its own executor, so a ViewModel test can
            // still have a coroutine landing on Dispatchers.Main after the test has
            // reset it — which fails as "Main is used concurrently with setting it",
            // intermittently and nowhere near the code that caused it.
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }
}

// --- Fixture builders -----------------------------------------------------------
// Defaults chosen so a test only has to state the fields it actually cares about.

internal const val DAY: Long = 24 * 60 * 60 * 1000L

/** 2026-07-02T00:00:00Z, a round base so offsets in tests stay readable. */
internal const val T0: Long = 1_782_950_400_000L

internal fun rawMessage(
    id: String,
    sender: String = "telebirr",
    body: String = "you have received ETB 450.00",
    contentHash: String = "hash_$id",
    receivedAt: Long = T0,
    source: CaptureSource = CaptureSource.NOTIFICATION,
    parseState: ParseState = ParseState.PARSED,
    matchedRuleId: String? = null,
) = RawMessageEntity(
    id = id,
    sender = sender,
    body = body,
    contentHash = contentHash,
    receivedAt = receivedAt,
    source = source,
    parseState = parseState,
    matchedRuleId = matchedRuleId,
)

internal fun transaction(
    id: String,
    rawMessageId: String? = null,
    direction: Direction = Direction.IN,
    amountMinor: Long = 45_000,
    channel: Channel = Channel.TELEBIRR,
    counterpartyName: String? = "NAME_01",
    counterpartyPhone: String? = "+251900000001",
    providerTxnId: String? = null,
    balanceAfterMinor: Long? = null,
    occurredAt: Long = T0,
    categoryId: String? = null,
    note: String? = null,
    isReconciled: Boolean = false,
    reconciliationSessionId: String? = null,
    isDeleted: Boolean = false,
    editedAt: Long? = null,
    touchedAt: Long? = null,
    possibleDuplicateOfId: String? = null,
) = TransactionEntity(
    id = id,
    rawMessageId = rawMessageId,
    direction = direction,
    amountMinor = amountMinor,
    currency = "ETB",
    channel = channel,
    counterpartyName = counterpartyName,
    counterpartyPhone = counterpartyPhone,
    providerTxnId = providerTxnId,
    balanceAfterMinor = balanceAfterMinor,
    occurredAt = occurredAt,
    categoryId = categoryId,
    note = note,
    isReconciled = isReconciled,
    reconciliationSessionId = reconciliationSessionId,
    isDeleted = isDeleted,
    editedAt = editedAt,
    touchedAt = touchedAt,
    possibleDuplicateOfId = possibleDuplicateOfId,
)

internal fun customer(
    id: String,
    name: String = "NAME_$id",
    phone: String? = null,
    createdAt: Long = T0,
) = CustomerEntity(id = id, name = name, phone = phone, note = null, createdAt = createdAt)

internal fun creditEntry(
    id: String,
    customerId: String,
    kind: CreditKind,
    amountMinor: Long,
    occurredAt: Long = T0,
    linkedTransactionId: String? = null,
) = CreditEntryEntity(
    id = id,
    customerId = customerId,
    kind = kind,
    amountMinor = amountMinor,
    occurredAt = occurredAt,
    note = null,
    linkedTransactionId = linkedTransactionId,
)

internal fun category(
    id: String,
    name: String = "Category $id",
    kind: CategoryKind = CategoryKind.INCOME,
    isSystem: Boolean = false,
) = CategoryEntity(id = id, name = name, kind = kind, colorHex = "#0B6E4F", isSystem = isSystem)

internal fun parserRule(
    id: String,
    name: String = "Rule $id",
    isUserCreated: Boolean = false,
    isEnabled: Boolean = true,
    priority: Int = 0,
    bodyPattern: String = "^you have received (?<amount>[\\d,.]+)$",
    version: Int = 1,
    confidence: Float = 1.0f,
) = ParserRuleEntity(
    id = id,
    name = name,
    senderPattern = "telebirr",
    channel = Channel.TELEBIRR,
    bodyPattern = bodyPattern,
    fieldMapJson = """{"amount":"AMOUNT"}""",
    directionStrategy = "FIXED_IN",
    directionKeywordsJson = null,
    version = version,
    isUserCreated = isUserCreated,
    isEnabled = isEnabled,
    priority = priority,
    createdAt = T0,
    matchCount = 0,
    confidence = confidence,
)
