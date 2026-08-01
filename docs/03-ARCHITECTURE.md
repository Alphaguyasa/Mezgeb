# Mezgeb — Architecture

## 1. The capture problem and why the design looks like this

Google Play treats SMS as a restricted permission class. An app that is not the user's default SMS handler is not permitted to declare `READ_SMS` or `RECEIVE_SMS` in its manifest — the policy covers even placeholder or commented declarations, and finance/transaction tracking is not among the approved exception use cases. Apps that ship it anyway get removed. Becoming the default SMS handler to read payment texts is absurd and users won't do it.

So Mezgeb reads the **notification** rather than the message.

When a payment SMS arrives, the default messaging app posts a notification containing the sender and body. `NotificationListenerService` can read that, gated by a user-granted special access (`BIND_NOTIFICATION_LISTENER_SERVICE` + the user toggling the app on in system settings). This is not a restricted Play permission, it requires explicit and revocable user consent, and it yields the same text.

Its one real limitation: it only sees notifications posted *while the listener is enabled*. It cannot reach backwards. A shop with eight months of payment history in its inbox gets nothing.

Hence two product flavors:

| Flavor | Distribution | Capture | Backfill |
|---|---|---|---|
| `play` | Google Play | Notification listener only | None |
| `direct` | Signed APK, sideloaded | Notification listener **+** SMS ContentResolver | Full inbox history |

Sideloading is normal in Ethiopia, so `direct` is the realistic distribution channel and `play` is the compliant shopfront. Both share one parser and one database schema.

**Manifest discipline is non-negotiable.** SMS permissions exist only in `app/src/direct/AndroidManifest.xml`. The main manifest must be clean, including comments. Verify with `./gradlew :app:processPlayReleaseManifest` and read the merged output.

### Deduplication

When both sources are active, the same payment arrives twice. Two-tier strategy:

1. **Strong key** — if the parser extracts a `providerTxnId`, that alone is the unique key. Collision means duplicate, drop.
2. **Fallback key** — SHA-256 of `normalize(sender) + "|" + normalize(body)`, where normalization lowercases, collapses whitespace, and strips punctuation. Stored as a unique index on `RawMessage.contentHash`.

Timestamps are deliberately excluded from the hash, because the notification timestamp and the SMS `date` column will differ by a few hundred milliseconds for the same message. Insert with `OnConflictStrategy.IGNORE` and let the index do the work.

## 2. Modules

```
mezgeb/
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── parser/                    Pure Kotlin JVM library
│   └── src/
│       ├── main/kotlin/       Rule engine, extractors, normalizers, induction
│       └── test/
│           ├── kotlin/        Unit + corpus accuracy tests
│           └── resources/corpus/   Redacted real message samples (JSON)
└── app/
    └── src/
        ├── main/             Shared code + clean manifest
        ├── play/             Play flavor: notification capture only
        ├── direct/           Direct flavor: + SMS manifest, + import worker
        ├── test/             JVM unit tests
        └── androidTest/      Instrumented tests
```

`:parser` having zero Android dependencies is the load-bearing decision. It means the entire parsing layer runs in milliseconds under plain JUnit against hundreds of fixtures, which is what makes "95% accuracy on a real corpus" a claim you can actually stand behind.

## 3. Layers inside `:app`

```
capture/     NotificationListenerService, SmsImportWorker, BootReceiver,
             CaptureCoordinator (single funnel: raw text → dedup → persist → parse)
data/
  local/     Room database, entities, DAOs, migrations, TypeConverters
  repo/      TransactionRepository, CustomerRepository, RuleRepository,
             ReconciliationRepository, SettingsRepository
domain/      Use cases with real logic: ReconcilePeriodUseCase,
             InduceRuleUseCase, ComputeCustomerBalanceUseCase
ui/          Feature packages, each with Route + Screen + ViewModel + UiState
designsystem/ Theme, color, type, spacing, shared components (MoneyText,
             EmptyState, ChannelChip, AmountKeypad)
di/          Hilt modules
util/        MoneyFormatter, DateFormatter, Redactor
```

Rule: **the capture layer never touches the UI, and the UI never touches a DAO.** Everything crosses at the repository boundary.

## 4. Data model

Room entities. Money is `Long` santim throughout. Timestamps are epoch millis UTC.

```kotlin
@Entity(indices = [Index(value = ["contentHash"], unique = true)])
data class RawMessageEntity(
    @PrimaryKey val id: String,          // UUID
    val sender: String,                  // "telebirr", "CBE", short code
    val body: String,                    // verbatim, never logged
    val contentHash: String,
    val receivedAt: Long,
    val source: CaptureSource,           // NOTIFICATION | SMS_IMPORT | MANUAL
    val parseState: ParseState,          // PARSED | UNMATCHED | IGNORED | RESOLVED_MANUALLY
    val matchedRuleId: String?,
)

@Entity(
    foreignKeys = [ForeignKey(RawMessageEntity::class, ["id"], ["rawMessageId"], onDelete = SET_NULL)],
    indices = [Index("occurredAt"), Index("counterpartyPhone"), Index("categoryId")]
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val rawMessageId: String?,           // null for manual cash entries
    val direction: Direction,            // IN | OUT
    val amountMinor: Long,
    val currency: String = "ETB",
    val channel: Channel,                // TELEBIRR | CBE_BIRR | BANK | CASH | OTHER
    val counterpartyName: String?,
    val counterpartyPhone: String?,      // normalized to +251 E.164
    val providerTxnId: String?,
    val balanceAfterMinor: Long?,
    val occurredAt: Long,
    val categoryId: String?,
    val note: String?,
    val isReconciled: Boolean = false,
    val reconciliationSessionId: String?,
    val isDeleted: Boolean = false,      // soft delete only
    val editedAt: Long?,
)

@Entity data class CustomerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String?,
    val note: String?,
    val createdAt: Long,
)

@Entity data class CreditEntryEntity(
    @PrimaryKey val id: String,
    val customerId: String,
    val kind: CreditKind,                // CREDIT_GIVEN | REPAYMENT | ADJUSTMENT
    val amountMinor: Long,               // always positive; kind carries the sign
    val occurredAt: Long,
    val note: String?,
    val linkedTransactionId: String?,    // repayment matched to a captured payment
)

@Entity data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: CategoryKind,              // INCOME | EXPENSE
    val colorHex: String,
    val isSystem: Boolean,
)

@Entity data class CategoryRuleEntity(
    @PrimaryKey val id: String,
    val matchField: MatchField,          // COUNTERPARTY_NAME | COUNTERPARTY_PHONE | RAW_BODY
    val matchType: MatchType,            // CONTAINS | EQUALS | REGEX
    val pattern: String,
    val categoryId: String,
    val priority: Int,
)

@Entity data class ParserRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val senderPattern: String,           // regex against sender
    val channel: Channel,
    val bodyPattern: String,             // regex with named groups
    val fieldMapJson: String,            // group name → semantic field
    val directionStrategy: String,       // FIXED_IN | FIXED_OUT | KEYWORD
    val directionKeywordsJson: String?,
    val version: Int,
    val isUserCreated: Boolean,
    val isEnabled: Boolean,
    val priority: Int,
    val createdAt: Long,
    val matchCount: Long,                // telemetry, local only
)

@Entity data class ReconciliationSessionEntity(
    @PrimaryKey val id: String,
    val periodStart: Long,
    val periodEnd: Long,
    val expectedTotalMinor: Long?,
    val capturedInMinor: Long,           // snapshotted at close
    val capturedOutMinor: Long,
    val cashRecordedMinor: Long,
    val creditGivenMinor: Long,
    val creditRepaidMinor: Long,
    val status: SessionStatus,           // OPEN | CLOSED
    val closedAt: Long?,
    val note: String?,
)
```

Bundled parser rules ship as a JSON asset and are seeded on first run with `isUserCreated = false`. App updates replace bundled rules by `id` while leaving user rules untouched — so a shop's hand-taught rules survive every update.

## 5. Parser engine (`:parser`)

### Pipeline

```
RawMessage
   ↓ SenderNormalizer      strip carrier decoration, uppercase, trim
   ↓ RuleSelector          rules whose senderPattern matches, by priority
   ↓ PatternMatcher        first bodyPattern that matches wins
   ↓ FieldExtractor        named groups → semantic fields via fieldMap
   ↓ Normalizers           amount, phone, date, direction
   ↓ ParseOutcome
```

### Public API

```kotlin
sealed interface ParseOutcome {
    data class Success(
        val fields: ParsedFields,
        val ruleId: String,
        val confidence: Float,
    ) : ParseOutcome

    data class Unmatched(val reason: UnmatchedReason) : ParseOutcome
    data class Ambiguous(val candidates: List<Success>) : ParseOutcome
    data object Ignored : ParseOutcome   // sender not on the financial allowlist
}

class MessageParser(private val rules: List<ParserRule>) {
    fun parse(sender: String, body: String, receivedAt: Long): ParseOutcome
}
```

`Ignored` matters more than it looks. Without a financial-sender allowlist, every promotional SMS lands in the review queue and the queue becomes noise the user learns to ignore. Allowlist first, parse second.

### Normalizers — the fiddly, high-value part

- **Amount.** Handle `1,234.56`, `1234.56`, `1 234.56`, `ETB 1,234.56`, `1,234.56 ETB`, `Birr 500`, and amounts with no decimal part. Parse to `Long` santim by string manipulation, not `Double` arithmetic — `(500.10 * 100).toLong()` is 50009 on some inputs. Split on the decimal separator and pad the fractional part to exactly two digits.
- **Phone.** Normalize `0912345678`, `+251912345678`, `251912345678`, `0712345678` all to E.164. Ethiopian mobile prefixes are `09` and `07`; treat the leading `0` as the national trunk prefix.
- **Direction.** Some rules are fixed by pattern (a "you have received" template is always IN). Others need keyword inspection. Keywords are configured per rule, never hard-coded in the engine, so an Amharic-language template is a data change rather than a code change.
- **Date.** Prefer a timestamp parsed from the message body when present; fall back to `receivedAt`. Assume EAT (UTC+3) for bodies with no timezone. Store UTC.
- **Amharic digits.** Some templates may use Geez numerals. Normalize `፩–፻` to Arabic digits before amount extraction. (This is a chunk of logic your project #3 can absorb later.)

### Rule induction

Input: the message body plus user-tagged character spans.

```kotlin
data class TaggedSpan(val field: SemanticField, val start: Int, val end: Int)

class RuleInducer {
    fun induce(sender: String, body: String, spans: List<TaggedSpan>): InducedRule
    fun evaluate(rule: ParserRule, corpus: List<RawMessage>): InductionReport
}
```

Algorithm:

1. Sort spans by start index; validate no overlaps.
2. Split the body into alternating literal segments and span segments.
3. Escape each literal segment with `Regex.escape`, then replace runs of digits inside literals with `\d+` and runs of whitespace with `\s+` — otherwise a rule learned from a message containing "on 14/03" only ever matches messages from 14 March.
4. Replace each span with a named group whose pattern is inferred from the span's content class: currency-like → `(?<amount>[\d,\s]+(?:\.\d{1,2})?)`, phone-like → `(?<phone>\+?\d[\d\s-]{7,})`, alphanumeric ID → `(?<ref>[A-Za-z0-9]{6,})`, otherwise → `(?<name>.+?)` non-greedy.
5. Anchor with `^...$` and compile. If compilation fails, degrade the most specific span to `.+?` and retry once.
6. Run `evaluate` against every unmatched message from the same sender. Report matched count, total count, and up to three sample extractions so the user can eyeball correctness before saving.

Anything below ~60% hit rate on a sender with 5+ samples should warn the user that the rule looks too specific.

## 6. Background execution

- `MezgebNotificationListener : NotificationListenerService` — `onNotificationPosted` filters by package (the default SMS app plus known banking apps), extracts title/text from `extras`, hands to `CaptureCoordinator`, returns fast. No parsing on the callback thread.
- `CaptureCoordinator` — the single funnel. Dedup → persist RawMessage → enqueue parse → persist Transaction or mark unmatched. Everything that ingests text goes through here, so there is exactly one place where dedup can be wrong.
- `SmsImportWorker` (`direct` only) — one-shot `WorkManager` job querying `Telephony.Sms.Inbox` in pages of 500, filtered by sender allowlist and a `since` cursor stored in DataStore. Reports progress to a foreground notification, because importing 8,000 messages is not instant.
- `BootCompletedReceiver` — re-establishes the listener binding after reboot and surfaces a warning if notification access was revoked.
- Vendor battery optimization on many devices sold in Ethiopia will kill listeners silently. Detect the manufacturer and, on first run, deep-link the user to the relevant battery settings with a plain-language explanation. Ship a "capture health" indicator on Today showing when the last message was captured, so silent death is visible rather than mysterious.

## 7. Reconciliation logic

```
capturedIn      = Σ transactions where direction=IN, in period, not deleted
capturedOut     = Σ transactions where direction=OUT, in period, not deleted
cashRecorded    = Σ transactions where channel=CASH and direction=IN
creditGiven     = Σ credit entries kind=CREDIT_GIVEN in period
creditRepaid    = Σ credit entries kind=REPAYMENT in period

accountedFor    = capturedIn + cashRecorded + creditGiven - creditRepaid
gap             = expectedTotal - accountedFor
```

When `gap != 0`, produce a ranked list of explanations rather than a bare number:

1. Unmatched messages in the review queue that fall inside the period, with their count and, where the amount alone was extractable, a partial total
2. Transactions with no category
3. Credit entries with no linked repayment past the reminder threshold
4. Manual edits made to transactions inside the period since it was opened

Closing a session snapshots every computed figure into the entity. Later edits to underlying transactions do not retroactively change a closed period — that's the whole point of closing one.

## 8. Security

- PIN stored as a salted Argon2 or PBKDF2 hash in `EncryptedSharedPreferences`. Never store the PIN itself.
- Biometric via `androidx.biometric`, PIN as the required fallback.
- `WindowManager.LayoutParams.FLAG_SECURE` on every screen displaying amounts.
- Backup file: JSON serialized, AES-256-GCM, key derived from a user passphrase via PBKDF2 with a high iteration count and a random salt stored in the file header. Restore validates a magic header and schema version before touching the database.
- `android:allowBackup="false"` and no `dataExtractionRules` opt-in — do not let Google auto-backup a shop's ledger off-device.
- The `Redactor` utility exists so debug logging is possible without ever emitting a message body. Use it or log nothing.

## 9. Suq integration seam (v2 — build the seam now, not the feature)

Keep one interface in `domain/`:

```kotlin
interface ExpectedSalesProvider {
    suspend fun expectedTotalMinor(periodStart: Long, periodEnd: Long): Long?
}
```

v1 ships `ManualEntryExpectedSalesProvider`, backed by the number the user types on the reconcile screen. v2 adds an implementation reading from Suq — via a signed local export file, or a content provider, or a shared local endpoint. Because the reconciliation engine only ever depends on this interface, that integration is a new class and a DI binding, not a refactor.
