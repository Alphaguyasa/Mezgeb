# Mezgeb — Build Sessions

Twelve sessions. Each is one focused build session. Start a fresh session each time — long sessions drift.

**Before every session:** confirm `.md` is in the repo root and current.
**After every session:** run the verification block, commit, and note anything stubbed in `NOTES.md`.

---

## Session 1 — Scaffold and design system

**Goal:** an app that builds, installs, and navigates between empty screens, with both flavors configured correctly.

### Prompt

```
Set up the Mezgeb Android project from scratch. Read .md first and follow
every hard constraint in it.

Create:

1. Gradle setup
   - Root settings.gradle.kts with modules :app and :parser
   - gradle/libs.versions.toml version catalog. Every dependency version lives
     here. Include: Kotlin 2.x, AGP, Compose BOM, Material 3, Navigation Compose,
     Room + KSP, Hilt, WorkManager, DataStore, kotlinx-serialization, androidx
     biometric, JUnit5, Turbine, MockK, Robolectric.
   - JDK 17, minSdk 26, targetSdk current stable, compileSdk current stable

2. :parser module — pure Kotlin JVM library
   - kotlin("jvm") plugin only. No Android plugin, no Android dependencies.
   - Empty package structure: rule/, extract/, normalize/, induce/
   - JUnit5 test source set wired up
   - Add a failing placeholder test so I can confirm the test task runs

3. :app module with product flavors
   - flavorDimension "distribution", flavors "play" and "direct"
   - src/main/AndroidManifest.xml must contain NO SMS permissions and no
     commented-out SMS permissions. This is a Play policy requirement, not a
     style preference.
   - src/direct/AndroidManifest.xml exists but is empty for now
   - applicationIdSuffix ".direct" on the direct flavor so both can be installed
     side by side during development
   - android:allowBackup="false"
   - Do NOT add android.permission.INTERNET

4. Design system in app/src/main/.../designsystem/
   - Material 3 theme, light and dark
   - A deliberate, non-default palette. This is a financial app for a market
     context: readable in bright daylight, high contrast, money-in and money-out
     colors that are distinguishable for the most common forms of color blindness
     (do not rely on red/green alone — pair with sign, icon, and position)
   - Typography scale with a font that renders Ethiopic script correctly.
     Verify Amharic text renders in a preview before you finish.
   - Spacing tokens
   - Components: MoneyText, ChannelChip, EmptyState, SectionHeader, MezgebScaffold

5. Navigation shell
   - Single MainActivity, Hilt @AndroidEntryPoint, @HiltAndroidApp Application
   - Type-safe Navigation Compose routes for: Today, Ledger, Review, Customers,
     Reconcile, Reports, Settings
   - Bottom navigation with Today, Ledger, Customers, Reports; Review, Reconcile
     and Settings reachable from Today and a top bar
   - Every destination is a placeholder Composable showing an EmptyState

6. Hilt: Application class, an empty AppModule, and verify injection works

Do not build any features. This session ends when the app installs and I can tap
through every tab.
```

### Verify

```bash
./gradlew :app:assemblePlayDebug :app:assembleDirectDebug
./gradlew :app:processPlayDebugManifest
# read app/build/intermediates/merged_manifests/playDebug/AndroidManifest.xml
# and confirm zero occurrences of SMS
grep -ri "READ_SMS\|RECEIVE_SMS" app/src/main/ && echo "FAIL" || echo "clean"
```

Install both flavors. Tap every tab. Check Amharic renders in a Compose preview.

**Commit:** `chore: project scaffold, flavors, design system, navigation shell`

---

## Session 2 — Parser engine

**Goal:** the corpus parses at ≥95% on train, measured. This is the most important session in the project.

**Prerequisite:** corpus files exist at `parser/src/test/resources/corpus/`. Do not start otherwise.

### Prompt

```
Build the :parser module. Read 03-ARCHITECTURE.md section 5 for the full design.
This module is pure Kotlin/JVM — zero Android imports, enforced.

Start by reading every JSON file under parser/src/test/resources/corpus/ and
summarizing back to me: how many samples per sender, what field patterns you
observe, and any formatting variations that will be awkward. Do this BEFORE
writing code, and wait for me to confirm your reading is right.

Then build:

1. Domain types
   - SemanticField enum: AMOUNT, DIRECTION, COUNTERPARTY_NAME, COUNTERPARTY_PHONE,
     PROVIDER_TXN_ID, BALANCE_AFTER, TIMESTAMP
   - ParsedFields, ParserRule, ParseOutcome (Success/Unmatched/Ambiguous/Ignored),
     UnmatchedReason
   - Money as Long minor units throughout

2. Normalizers, each independently unit tested
   - AmountNormalizer: string-based parsing to Long santim. Handle thousands
     separators (comma, space, none), optional decimals, currency prefix or
     suffix in any of "ETB", "Birr", "ብር". NEVER use Double arithmetic —
     split on the decimal separator and pad the fraction to two digits.
     Test with 500, 500.5, 500.50, 1,234.56, 1 234.56, ETB 1,234.56, 10,450.75.
   - PhoneNormalizer: 09XXXXXXXX, 07XXXXXXXX, +251..., 251... all to E.164
   - GeezNumeralNormalizer: Ethiopic numerals to Arabic digits, applied before
     amount extraction
   - DateNormalizer: parse in-body timestamps, assume UTC+3 when no zone,
     fall back to receivedAt, store epoch millis UTC
   - DirectionResolver: FIXED_IN / FIXED_OUT / KEYWORD strategies, keywords
     supplied by rule data not hard-coded

3. SenderAllowlist — financial senders only. Everything else returns Ignored.
   Configurable, seeded from the corpus.

4. RuleEngine
   - MessageParser(rules).parse(sender, body, receivedAt): ParseOutcome
   - Sender match → priority-ordered body pattern match → named group extraction
     → fieldMap → normalizers → ParsedFields
   - Ambiguous when two rules of equal priority both match with different results

5. Rule definitions as data, serialized with kotlinx-serialization, loaded from
   a JSON asset. Write the bundled rules for every sender in the corpus.

6. The accuracy harness — this is the deliverable I care most about
   - CorpusAccuracyTest loads corpus/train/, parses every sample, compares to
     expected field by field
   - Report per-field precision and recall, per-sender accuracy, overall accuracy
   - FALSE POSITIVES ARE FATAL: any noise sample that produces a Success, or any
     sample where an extracted value differs from expected, fails the build.
     A miss (Unmatched) is acceptable; a wrong answer is not.
   - Print a readable summary table to stdout on every run
   - Assert overall ≥95% on train

Iterate on the rules until the harness passes. Show me the summary table.
Do NOT run the holdout set yet.
```

### Verify

```bash
./gradlew :parser:test --info
# read the accuracy table
grep -r "android" parser/src/main/ && echo "FAIL: android dep in parser" || echo "clean"
```

**Then, separately, once and only once:**

```
Run the accuracy harness against corpus/holdout/ and report the number.
Do not tune anything in response to this result — just report it.
Record it in NOTES.md with today's date.
```

**Commit:** `feat(parser): rule engine, normalizers, corpus accuracy harness`

---

## Session 3 — Persistence

**Goal:** the full Room schema with tested DAOs and repositories.

### Prompt

```
Build the persistence layer in :app. Use the entity definitions in
03-ARCHITECTURE.md section 4 exactly as written — same names, same types,
same indices.

1. Room database "mezgeb.db", version 1, exportSchema = true with schemas
   committed to the repo
   - All entities from the architecture doc
   - TypeConverters for the enums and for nothing else
   - Unique index on RawMessageEntity.contentHash

2. DAOs
   - RawMessageDao: insert with OnConflictStrategy.IGNORE, query by parseState,
     query unmatched grouped by sender, mark resolved
   - TransactionDao: paged query with filters (date range, channel, direction,
     categoryId, reconciled, search text), aggregate sums by period and by
     category, soft delete
   - CustomerDao + CreditEntryDao: including a balance query that computes
     running balance per customer in SQL, not in Kotlin
   - CategoryDao, CategoryRuleDao, ParserRuleDao, ReconciliationSessionDao
   - Reads return Flow, writes are suspend

3. Repositories in data/repo/ wrapping the DAOs. Repositories expose domain
   models, not entities — write the mappers.

4. Database seeding on first run
   - Default categories (Sales, Supplier payment, Rent, Utilities, Salary,
     Transfer, Other) with isSystem = true
   - Bundled parser rules from the JSON asset with isUserCreated = false
   - Seeding is idempotent — running it twice changes nothing

5. Hilt module providing the database, DAOs, and repositories

6. Tests: in-memory Room test for every DAO. Cover the filter query with each
   filter individually and in combination, and cover the customer balance query
   with credit given, repayment, and adjustment entries mixed.

Money stays Long santim everywhere. Timestamps are epoch millis UTC.
```

### Verify

```bash
./gradlew :app:testPlayDebugUnitTest
ls app/schemas/   # confirm schema JSON is exported
```

**Commit:** `feat(data): room schema, daos, repositories, seeding`

---

## Session 4 — Capture layer

**Goal:** real payments land in the database automatically.

### Prompt

```
Build the capture layer. Read 03-ARCHITECTURE.md sections 1 and 6.

1. CaptureCoordinator in capture/ — the single funnel every ingested message
   passes through, regardless of source:
     normalize sender → compute contentHash → insert RawMessage (IGNORE on
     conflict, so duplicates die here) → if newly inserted, parse via
     :parser → on Success insert Transaction and apply category rules →
     on Unmatched leave in queue → on Ignored mark IGNORED
   Two-tier dedup: providerTxnId when available is the strong key, contentHash
   is the fallback. Timestamps are deliberately NOT part of the hash.

2. MezgebNotificationListener : NotificationListenerService (in src/main, both
   flavors)
   - Manifest entry with BIND_NOTIFICATION_LISTENER_SERVICE
   - Filter by source package: the default SMS app plus a configurable list of
     known banking app packages
   - Extract title and text from Notification.extras, handling BigTextStyle
     where the full body lives in EXTRA_BIG_TEXT rather than EXTRA_TEXT
   - Hand off to CaptureCoordinator on a background dispatcher and return
     immediately. No parsing on the callback thread.
   - Handle the service being rebound after being killed

3. Permission flow
   - Helper to check whether notification access is granted
   - Intent to open the system notification-access settings
   - Onboarding screen: plain-language explanation of what the app reads and
     what it does not, then the grant action. Written for a shop owner, not a
     developer. Available in English and Amharic.

4. direct flavor only — SMS import
   - src/direct/AndroidManifest.xml declares READ_SMS and RECEIVE_SMS. These
     must NOT appear anywhere in src/main.
   - SmsImportWorker: WorkManager one-shot, queries Telephony.Sms.Inbox in pages
     of 500, filters by the sender allowlist, respects a "since" cursor in
     DataStore, feeds each message through CaptureCoordinator
   - Foreground notification with progress
   - Use a flavor-specific interface (SmsImporter) with a no-op implementation
     in the play flavor, so shared code compiles against both

5. BootCompletedReceiver — verify listener binding after reboot, surface a
   warning state if access was revoked

6. Capture health
   - Store lastCaptureAt in DataStore, updated by CaptureCoordinator
   - Expose a CaptureHealth state: HEALTHY, NO_ACCESS, SILENT_TOO_LONG
   - On first run, detect manufacturer (Xiaomi, Oppo, Vivo, Huawei, Tecno,
     Infinix, Samsung) and offer a deep link to that vendor's battery
     optimization settings with an explanation of why it matters

7. Tests: CaptureCoordinator dedup tests covering same message from both
   sources, same providerTxnId with different body text, and genuinely
   different messages that happen to share an amount.
```

### Verify

Install the `direct` flavor on a real phone. Grant notification access. Have someone send you a small telebirr payment — or send yourself one. Confirm it appears in the database within seconds. Then run an SMS import and confirm the same payment is **not** duplicated.

```bash
grep -r "READ_SMS\|RECEIVE_SMS" app/src/main/ && echo "FAIL" || echo "clean"
```

**Commit:** `feat(capture): notification listener, sms import, dedup coordinator`

---

## Session 5 — Today and Ledger

**Goal:** the owner can see her money.

### Prompt

```
Build the Today and Ledger screens. MVVM with a single StateFlow<UiState> and a
single onEvent(Event) per screen, per .md. Every screen gets a stateless
XxxScreen(state, onEvent) plus an XxxRoute(viewModel) wrapper.

TODAY
- Header: today's total IN, total OUT, and net, large and readable at arm's
  length in daylight
- Capture health banner when state is NO_ACCESS or SILENT_TOO_LONG, with the
  fix action inline
- Review queue badge with count, tappable
- Last 10 transactions with channel chip, counterparty, amount, time
- Quick action: "Record cash sale" opening the amount keypad
- Empty state that explains what will appear here and why nothing has yet

LEDGER
- Paged transaction list (Paging 3), grouped by day with day subtotals
- Sticky filter bar: date range, channel, direction, category, reconciled
- Search across counterparty name, phone, reference, and note
- Filtered-empty state distinct from truly-empty state
- Pull to refresh triggers a re-parse of anything in UNMATCHED that a newly
  saved rule might now match

TRANSACTION DETAIL
- Parsed fields shown as a labelled table
- The original message body shown verbatim in a collapsed section — this is the
  trust feature, the user must always be able to see what the app read
- Category picker, with "always categorize like this" creating a CategoryRule
- Note field, reconciled toggle, soft delete with confirmation
- Edit any parsed field manually; edits set editedAt and are visibly marked

MANUAL CASH ENTRY
- Full-screen amount keypad, large targets, works one-handed
- Amount, direction, category, optional counterparty, optional note, date
- Creates a Transaction with channel = CASH and rawMessageId = null

Apply FLAG_SECURE to all three screens. All strings in strings.xml with
values-am translations. ViewModel tests with Turbine covering initial, loading,
empty, populated, and error for each.
```

### Verify

Install, capture or seed some transactions, exercise every filter, confirm search works, confirm a cash entry appears with a distinct channel chip.

**Commit:** `feat(ui): today, ledger, transaction detail, manual entry`

---

## Session 6 — Review queue and rule induction

**Goal:** the feature that makes this project memorable. Give it a full session.

### Prompt

```
Build the review queue and the rule induction flow. Read 03-ARCHITECTURE.md
section 5, "Rule induction", for the algorithm.

1. RuleInducer in :parser (pure JVM, fully unit tested)
   - induce(sender, body, spans): InducedRule
   - Sort and validate spans, reject overlaps
   - Segment body into literals and spans
   - Escape literals, then generalize: digit runs inside literals become \d+,
     whitespace runs become \s+. Without this, a rule learned from a message
     containing a date only matches that date.
   - Each span becomes a named group with a pattern inferred from its content
     class: currency-like, phone-like, alphanumeric-id-like, or non-greedy .+?
   - Anchor and compile; on compile failure degrade the most specific span to
     .+? and retry once
   - evaluate(rule, corpus): InductionReport with matched count, total count,
     hit rate, up to 3 sample extractions, and a warning flag when hit rate is
     under 60% with 5+ samples
   - Test induction against the corpus: take a known rule, remove it, induce a
     replacement from tagged spans on one sample, assert the induced rule
     matches the rest of that sender's samples

2. REVIEW QUEUE SCREEN
   - Unmatched RawMessages grouped by sender, count per group, newest first
   - Per group: "Teach a rule" primary action
   - Per message: "Resolve manually" secondary action
   - Empty state is a success state — say so, don't show a sad face

3. RULE BUILDER SCREEN — the interaction has to be obvious without instruction
   - Message body rendered as selectable text
   - User selects a span, then taps a field chip: Amount, Who, Reference,
     Direction. Selected spans get a colored highlight matching the chip.
   - Amount is required; everything else optional
   - Live preview card showing what the transaction would look like
   - "Test rule" runs evaluate() against the sender's other unmatched messages
     and shows: "Matches 14 of 16 messages" plus the three sample extractions
   - Warn on low hit rate; warn on conflict with an existing enabled rule
   - Save creates a ParserRuleEntity with isUserCreated = true, then reprocesses
     the entire UNMATCHED backlog against the new rule and reports how many
     were resolved

4. MANUAL RESOLUTION
   - Same span-tagging UI, but creates a single Transaction and marks the
     RawMessage RESOLVED_MANUALLY without saving a rule

5. Backlog reprocessing runs in WorkManager with progress, since it may cover
   thousands of messages

6. Instrumented test for the span-selection interaction. This is the one UI
   flow worth an instrumented test.
```

### Verify

Delete one bundled rule, restart, confirm those messages land in the queue, teach a replacement rule through the UI, confirm the backlog resolves. Time yourself doing it — if it takes more than 90 seconds, the interaction needs work.

**Record a screen capture of this flow.** It's the single best 20 seconds of demo material the project will produce.

**Commit:** `feat(review): rule induction engine and teach-a-rule flow`

---

## Session 7 — Credit book

### Prompt

```
Build the customer credit book.

CUSTOMERS SCREEN
- List sorted by outstanding balance descending
- Each row: name, balance, days since oldest unpaid entry
- Overdue highlighting past a configurable threshold (default 30 days)
- Totals header: total outstanding, number of customers with a balance
- Search by name or phone
- Add customer

CUSTOMER DETAIL
- Running statement: every CreditEntry chronologically with a running balance
  column
- Balance headline, prominent
- Actions: Add credit sale, Record repayment, Edit, Archive
- Repayment flow offers to link to a captured Transaction: show IN transactions
  from the last 7 days that are not yet linked, matching on amount first and
  counterparty phone second, best matches at the top
- "Send reminder" builds a message and opens the default SMS app via
  Intent(ACTION_SENDTO, "smsto:..." ) with EXTRA_TEXT prefilled. It must NOT
  send. It must NOT use SEND_SMS. The user presses send themselves.
  Template is editable in settings, available in Amharic.

ADD CREDIT SALE
- Customer (searchable, or create inline), amount via the keypad, date, note

DOMAIN
- ComputeCustomerBalanceUseCase, with the running balance computed in SQL
- Link and unlink repayment to transaction, with the link visible from the
  transaction detail screen too

Tests: balance computation across credit/repayment/adjustment sequences,
including out-of-order dates and a repayment exceeding the balance (which is
legal — it becomes a negative balance, meaning the shop owes the customer).
```

**Commit:** `feat(credit): customer credit book, statements, repayment linking`

---

## Session 8 — Reconciliation

### Prompt

```
Build the reconciliation engine and screen. Formulas are in 03-ARCHITECTURE.md
section 7 — implement them exactly.

1. ExpectedSalesProvider interface in domain/, with
   ManualEntryExpectedSalesProvider as the only v1 implementation. Bind via
   Hilt. Do not let anything else depend on the concrete class — a Suq-backed
   implementation gets added in v2 and must require no changes here.

2. ReconcilePeriodUseCase
   - Compute capturedIn, capturedOut, cashRecorded, creditGiven, creditRepaid,
     accountedFor, gap
   - Produce a ranked List<GapExplanation>:
       UnmatchedMessages(count, partialTotalMinor?)
       UncategorizedTransactions(count, totalMinor)
       OutstandingCredit(customerCount, totalMinor)
       EditedSincePeriodOpened(count)
     Rank by how much of the gap each could account for.

3. RECONCILE SCREEN
   - No open period: period picker (Today, This week, Custom) and Open
   - Open period: expected total input, the breakdown, the gap in large type,
     the ranked explanations each tappable through to the relevant screen
   - Balanced state gets a clear, satisfying confirmation — this is the moment
     the app delivers its value, treat it as such
   - Close period snapshots every computed figure into the entity
   - History list of closed periods, each opening a read-only snapshot view

4. Closed periods are immutable. Editing a transaction inside a closed period
   is allowed but does not change the closed figures; it appears in the next
   period's EditedSincePeriodOpened explanation. Test this explicitly.

5. WorkManager reminder: if no period has been closed in 7 days, post a local
   notification. Toggleable in settings, default on.
```

**Commit:** `feat(reconcile): period reconciliation engine and close flow`

---

## Session 9 — Reports and export

### Prompt

```
Build reports and export.

REPORTS SCREEN
- Period selector: day, week, month, custom
- Summary: total in, out, net, transaction count
- Breakdown by channel and by category, each with amount and percentage
- Bar chart of daily net across the period. Write it in Compose Canvas — do not
  add a charting dependency for one chart. Handle negative bars, empty days,
  and periods longer than the screen (scroll horizontally).
- Top 5 customers by outstanding credit
- Insufficient-data state when the period has fewer than 3 transactions

EXPORT
- CSV: ledger, credit book, or both. UTF-8 with BOM so Excel opens Amharic
  correctly. Columns: date, direction, amount, currency, channel, counterparty,
  phone, reference, category, note, reconciled.
- PDF: one-page period summary using android.graphics.pdf.PdfDocument. Header
  with shop name from settings, the summary figures, the category breakdown,
  a footer with the generation timestamp. Must render Ethiopic script correctly
  — test with an Amharic shop name.
- Both write via ACTION_CREATE_DOCUMENT so the user picks the location. No
  storage permissions.
- Share sheet offered after export

Tests: CSV escaping for values containing commas, quotes, and newlines.
Aggregation correctness against a known fixture dataset.
```

**Commit:** `feat(reports): summaries, chart, csv and pdf export`

---

## Session 10 — Security and backup

### Prompt

```
Build the security layer. Read 03-ARCHITECTURE.md section 8.

1. App lock
   - Optional PIN, 4 or 6 digits, stored as a PBKDF2 hash with a random salt in
     EncryptedSharedPreferences. Never store the PIN.
   - Biometric via androidx.biometric with PIN as required fallback
   - Lock on cold start and on resume after a configurable timeout (default 60s)
   - Rate limit: exponential backoff after 5 failed attempts
   - Recovery: none. If the PIN is lost the data is lost. Say this explicitly
     during setup, in plain language, and require the user to acknowledge it.

2. FLAG_SECURE on every screen displaying amounts. Verify by attempting a
   screenshot and by checking the recents thumbnail.

3. Encrypted backup
   - Serialize the whole database to JSON
   - AES-256-GCM, key from a user passphrase via PBKDF2 with a high iteration
     count and a random salt
   - File header: magic bytes, format version, schema version, salt, IV
   - Write via ACTION_CREATE_DOCUMENT
   - Restore validates header and schema version before touching anything,
     and requires explicit confirmation that existing data will be replaced
   - Round-trip test: seed → backup → wipe → restore → assert deep equality

4. Redactor utility, and audit every existing log statement in the codebase to
   ensure no message body, phone number, or account number is ever logged.
   Show me the audit result.

5. Confirm android:allowBackup="false" and that no dataExtractionRules opt the
   app into cloud backup.
```

**Commit:** `feat(security): app lock, flag_secure, encrypted backup and restore`

---

## Session 11 — Demo mode, localization, polish

### Prompt

```
Final functional session.

1. DEMO MODE
   - Settings toggle. On enable: swap to a separate demo database, seeded with
     ~200 transactions across 90 days with realistic amount distributions and
     day-of-week patterns, 12 customers with varied credit balances including
     two overdue, 6 messages sitting in the review queue in two different
     unmatched formats, and one closed reconciliation period plus one open one.
   - Persistent, unobtrusive "Demo data" banner while active
   - Disabling restores the real database untouched
   - This exists so anyone can install the APK and see a working app in ten
     seconds. Make the seeded data look like a real shop, not like test data.

2. LOCALIZATION
   - Complete values-am/strings.xml. Every string. No exceptions.
   - Language override in settings, defaulting to system locale
   - ETB currency formatting
   - Audit every Composable for hardcoded strings, including previews. Report
     what you found.

3. POLISH
   - Every screen has empty, loading, and error states. List them and show me
     the ones that are missing.
   - Adaptive launcher icon and a proper app name in both languages
   - Consistent back behavior and predictive back support
   - Accessibility: content descriptions everywhere, 48dp minimum touch targets,
     verify with TalkBack on the Today and keypad screens
   - Run over a slow device profile and fix any jank on the ledger scroll

4. Full test pass. Report total coverage and name the three least-tested areas.
```

**Commit:** `feat: demo mode, amharic localization, accessibility polish`

---

## Session 12 — Release and portfolio

### Prompt

```
Prepare for release and portfolio presentation.

1. RELEASE BUILD
   - Signing config reading from a gitignored keystore.properties
   - R8 enabled with rules verified against Room, Hilt, and kotlinx-serialization
   - Verify the play release manifest contains no SMS permission and no INTERNET
     permission. Print the merged manifest permission list so I can see it.
   - Build both flavors, report APK sizes, target under 15 MB
   - Test the release build on a real device, including the notification
     listener under R8 (a common breakage point)

2. HOLDOUT RUN
   - Run the parser accuracy harness against corpus/holdout/ one final time
   - Report per-sender and overall numbers
   - Do not tune in response

3. README.md, written for a developer or client evaluating me in 60 seconds
   - One-sentence description, then a GIF placeholder of the teach-a-rule flow
   - "The Play Store constraint" section, under 300 words, explaining why SMS
     permissions are unavailable to finance apps and how the dual-flavor
     notification capture works. This is the section that demonstrates judgment.
   - "The adaptive parser" section with the holdout accuracy number and corpus
     size
   - Architecture diagram (mermaid), stack, module layout
   - Privacy: no network permission, everything local, how the corpus was
     collected and redacted
   - Build and install instructions for both flavors

4. ARCHITECTURE.md in the repo — condensed version of the design doc

5. CORPUS.md — sample counts, anonymized sources, redaction method, train/
   holdout split, accuracy results

6. Verify .gitignore excludes keystore.properties, *.jks, and any raw corpus
   directory. Run a scan of the full git history for anything resembling a
   phone number, account number, or key, and report findings.
```

**Do the history scan seriously.** A leaked secret in a portfolio repo undoes the entire impression the repo was built to create.

**Commit:** `chore: release config, readme, corpus documentation`

---

## Sequencing notes

**Session 2 is the project.** If the parser is weak, nothing downstream matters. Budget two sittings for it if needed, and don't move on until the harness passes honestly.

**Session 6 is the demo.** It's what people will remember. Don't rush it into the same sitting as Session 5.

**Sessions 3, 7, 9 are mechanical.** They'll go fast.

**Session 4 needs a real phone and a real payment.** Plan for that — you can't verify capture in an emulator.

If you have to cut scope, cut Session 9's PDF export and Session 11's demo-mode polish. Never cut Session 2's accuracy harness or Session 12's holdout run — those two are what separate this from a project that merely looks finished.
