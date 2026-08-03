# Mezgeb — build notes

Running log of anything stubbed, deferred, or decided differently from the pack.

---

## Session 1 — Scaffold and design system (2026-08-01)

### Verification results

| Check | Result |
|---|---|
| `:app:assemblePlayDebug` | pass |
| `:app:assembleDirectDebug` | pass |
| `:app:testPlayDebugUnitTest` | pass (2 tests, `TodayViewModelTest`) |
| `:parser:test` | **fails by design** — see "Intentional failure" below |
| SMS tokens in `app/src/main/` | clean (0 occurrences) |
| SMS tokens in merged `playDebug` manifest | clean (0) |
| SMS tokens in merged `directDebug` manifest | clean (0) |
| `INTERNET` in either merged manifest | clean (0) |
| Android references in `parser/src/main/` | clean (0) |
| `:parser` compile classpath | `kotlin-stdlib`, `kotlinx-serialization-json` only |

The only `uses-permission` in either merged manifest is
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature-level permission that
androidx.core defines for itself. It is not a Play-restricted permission.

### Intentional failure

`parser/src/test/.../PlaceholderTest.kt` fails on purpose, as Session 1 step 2
requires, to prove the JUnit 5 test task actually executes. **`./gradlew build` will
therefore fail until Session 2 deletes it.** Use `./gradlew :app:assemblePlayDebug
:app:assembleDirectDebug :app:testPlayDebugUnitTest` for a green run in the meantime.

### Deviations from the pack

1. **compileSdk / targetSdk raised 36 → 37.** The wizard project did not build as
   generated: `androidx.core:core:1.19.0` and `androidx.lifecycle:*:2.11.0` both
   require compiling against API 37 or later. The instruction to keep the wizard's
   SDK versions was not compatible with the dependency versions the wizard itself
   chose. AGP downloaded platform 37 automatically. AGP, Gradle wrapper and Kotlin
   were left untouched as instructed.

2. **`org.jetbrains.kotlin.android` is deliberately NOT applied to `:app`.** AGP 9
   ships built-in Kotlin support and registers the `kotlin` extension itself;
   applying the standalone plugin fails with an extension-name collision. `:parser`
   still applies `kotlin("jvm")` normally.

3. **`android.disallowKotlinSourceSets=false` in `gradle.properties`.** AGP 9's
   built-in Kotlin rejects source directories registered through the
   `kotlin.sourceSets` DSL, and the KSP Gradle plugin still registers its generated
   sources that way. This is the opt-out AGP's own error message prescribes. Revisit
   when KSP supports built-in Kotlin.

4. **JDK 17 is the language level, not the daemon JVM.** The Gradle daemon runs on
   the JDK 21 bundled with Android Studio (`gradle/gradle-daemon-jvm.properties`
   pins `toolchainVersion=21`). Both modules compile to JVM target 17 as .md
   requires. No JDK 17 toolchain download is needed.

5. **Noto Sans Ethiopic is bundled** (`app/src/main/res/font/`, ~1.1 MB, OFL licence
   in `app/licenses/`). Android's font fallback does cover Ethiopic, so this is
   belt-and-braces — but several OEM ROMs common in this market ship trimmed font
   sets, and the architecture doc already flags those vendors as a risk area. A
   ledger rendering as tofu boxes is worse than useless. Counts against the Session
   12 APK budget of 15 MB.

6. **Deleted from the wizard scaffold:** `ui/theme/` (superseded by `designsystem/`),
   the sample `colors.xml` palette, `backup_rules.xml` and `data_extraction_rules.xml`
   (unreferenced once `allowBackup="false"`; removing them means nothing can
   silently re-opt the app into cloud backup), and the two example test classes.

### Stubbed / deferred

- Every one of the seven destinations renders an `EmptyState` placeholder. Real
  screens begin in Session 5.
- `TodayViewModel` exists only to prove the Hilt → `hiltViewModel()` → `StateFlow`
  chain works end to end. It holds no real state.
- `AppModule` provides only a `Clock`. Database, repository and capture bindings
  arrive in Sessions 3 and 4.
- `MoneyFormatter` has no unit tests yet — the amount-formatting edge cases are
  covered as part of Session 2's normalizer work, where the parsing side lives.
- Room, WorkManager, DataStore, Paging, biometric, Robolectric and MockK are in the
  version catalog but not yet on `:app`'s dependency list. They get added by the
  session that first needs them, per .md's "state why when adding a dependency".

### Known low-priority risk

`kotlinx-serialization-json:1.11.0` pulls `kotlin-stdlib` up to 2.3.20 while the
compiler is 2.2.10. A stdlib newer than the compiler is the supported direction (the
reverse is not), and everything compiles and runs, so this is left alone rather than
churned. Worth re-checking if odd metadata errors ever appear.

### Device verification (2026-08-01, Samsung SM-G965U1, Android 10 / API 29)

Both flavors installed side by side, confirming the `.direct` applicationIdSuffix.
Tapped through all seven destinations: bottom-bar selection tracks correctly, Review /
Reconcile / Settings push with a back affordance and no bottom bar, system back
returns to Today. No crashes in logcat.

Amharic rendering is verified as far as it can be without changing the device's system
language (API 29 predates per-app locales):

- the bundled font covers all 358 codepoints of the Ethiopic block U+1200–U+137F, plus
  Latin and Arabic digits, checked with `Font.canDisplayUpTo` against every Amharic
  string the app ships;
- `aapt2 dump` confirms the `am` locale is packaged and `app_name` resolves to መዝገብ;
- screenshots confirm the bundled family is what actually renders.

**Still outstanding:** a live screenshot with the device UI in Amharic, and the Studio
Compose preview (`TodayScreenAmharicPreview`).

---

## Session 3 — Persistence (2026-08-01)

Built out of pack order: Session 2 is blocked on the message corpus, which is
human data-collection work. Session 3 depends on the parser only for seeding bundled
rules, which is stubbed — see below.

### Verification results

| Check | Result |
|---|---|
| `:app:assemblePlayDebug` / `:app:assembleDirectDebug` | pass |
| `:app:testPlayDebugUnitTest` | **68 tests, 0 failures** |
| `app/schemas/…/1.json` exported | yes, all 8 tables, committed |

Test breakdown: TransactionDao 24, CustomerCredit 14, RawMessage 8, ParserRule 6,
Seeder 5, Category 4, ReconciliationSession 3, CategoryRule 2, TodayViewModel 2.

### Decisions worth knowing

1. **`ADJUSTMENT` has no defined sign in the architecture doc.** The entity contract
   says `amountMinor` is always positive and `kind` carries the sign — but with
   CREDIT_GIVEN positive and REPAYMENT negative, ADJUSTMENT is ambiguous by
   construction. Implemented literally per the doc: ADJUSTMENT increases the balance,
   and a downward correction is expressed as a REPAYMENT. **Revisit in Session 7**
   when the credit book UI makes the real requirement obvious.

2. **The running-balance statement uses a correlated subquery, not a window
   function.** `SUM(...) OVER (...)` needs SQLite 3.28, which ships with API 30; minSdk
   is 26. The subquery is O(n²) over a single customer's entries, which is irrelevant
   at the scale of a shop's credit book. Ties on `occurredAt` are broken by `id` in
   both the outer query and the subquery so the statement stays monotonic.

3. **Seeded category names are English strings in the database, not resources.**
   .md wants every user-facing string in `strings.xml`, but these are data rows.
   Session 11 must map the seven system category ids to localised display names rather
   than translating the stored `name`.

4. **Robolectric's `android-all` runtime is resolved by Gradle, not by Robolectric.**
   By default Robolectric downloads a 144 MB jar inside the test JVM on first use, with
   no progress output. It is now declared in the version catalog, staged by a Copy
   task, and Robolectric runs with `robolectric.offline=true` against that directory —
   visible in the dependency graph and with normal Gradle retry behaviour. First run
   still costs the 144 MB download (~30 min here); subsequent runs are ~20 s.

5. **No `fallbackToDestructiveMigration`.** A missing migration must fail loudly in
   development rather than quietly wipe a shop's ledger in the field.

### Stubbed / deferred

- `assets/parser_rules.json` is a valid, empty rules file. Session 2 populates it. The
  seeder's empty-asset and malformed-asset paths are both covered by tests.
- `SettingsRepository` is not built — it is DataStore-backed rather than a DAO wrapper,
  so it belongs with the capture work in Session 4.
- Paging is wired (`pagingSourceFiltered`) but nothing consumes it until Session 5.
- No migration tests yet; there is only one schema version so far.

---

## Session 0 tooling — corpus format, validation, redaction (2026-08-01)

Not a numbered session. Built because Session 2 is blocked on the corpus and this is
the part of that blocker that could be prepared in advance.

- `parser/src/test/.../corpus/CorpusFormat.kt` — the on-disk shape from `docs/04`.
  `channel` and `direction` are plain strings rather than enums so a typo in a
  hand-authored sample surfaces as a readable validation failure instead of a
  deserialization crash.
- `CorpusValidationTest` — privacy guard and labelling discipline. Every test skips
  cleanly while the corpus is empty, so it sits in the repo protecting from now on.
- `tools/redact-samples.ps1` — mechanical redaction with stable tokens, so the same
  real phone always maps to the same fake one and "this customer appears twice"
  survives while identity does not. Names are flagged, never auto-replaced.
- `tools/corpus-example.json` — format example. Deliberately outside test resources so
  it is never loaded as corpus data.
- `CORPUS.md` — template, to be filled during collection.

**The privacy guard was verified by planting a file with six deliberate defects.** All
six were caught (unredacted local phone, unredacted international phone, unredacted
account number, invalid direction, missing amount, duplicate id, malformed timestamp),
each naming the file, sample id and offending value; correctly-redacted samples passed.
The planted file was then removed. A guard that has never been seen to fire is not a
guard.

### Placeholder test removed

`PlaceholderTest` in `:parser` was Session 1's deliverable — a deliberately failing
test proving the test task runs. Real `:parser` tests now exist, so it has served its
purpose and a permanently red build is worse than the signal it gave. `./gradlew build`
is no longer red by design.

### Known environment issue

`./gradlew build` runs release-variant lint, which downloads the lint tool from
`dl.google.com`. That host was timing out during this session, so full `build` fails on
a network error unrelated to the code. Targeted verification works:

```
./gradlew :parser:test :app:assemblePlayDebug :app:assembleDirectDebug :app:testPlayDebugUnitTest
```

Network to Google and Maven Central has been intermittent throughout this session —
worth remembering before diagnosing a slow build as a hang.

---

## Corpus, first pass (2026-08-01)

Extracted from the developer's own device over adb: 760 inbox messages, filtered to
financial and promotional shortcodes before any body text was read. 15 messages from
personal phone numbers were discarded unread. Result: **84 financial samples across 3
senders, 34 noise samples**, 61/23 train/holdout.

Full detail in `CORPUS.md`. The important points:

- **CBE, EBIRR and shortcode 127 are the real transaction senders.** The `telebirr`
  alpha sender turned out to be pure marketing; actual telebirr transactions arrive
  from `127`. Worth knowing before writing the sender allowlist.
- **Three noise samples come from financial senders** — a CBE fraud-awareness notice,
  an EBIRR welcome message, a telebirr service activation. These are the ones that
  matter: a sender allowlist alone would admit them, so the parser has to decline them
  on content.
- **19 messages were dropped, not redacted**, because they carried one-time codes or
  PINs — including bank SMS stating a freshly reset mobile-banking PIN in cleartext.
  A redacted credential is still a credential in git history.
- **59 personal names and 36 phone numbers** were tokenised. Two leaks survived the
  first redaction pass and were caught only because the scan was rewritten to look for
  *any* capitalised phrase outside the template vocabulary rather than a list of names
  already known. A leak scan that depends on knowing what to look for is not a scan.

### Gaps that matter

- **Every transaction sample is in English.** There is Amharic noise and one Amharic
  CBE notice, but no Amharic transaction template — which `docs/04` calls out as
  exactly the case that breaks naive parsers. Session 2 will tune without them.
- No Geez numerals appeared, so `GeezNumeralNormalizer` will be written without fixture
  coverage.
- One person's account, so the data skews to small personal transfers rather than shop
  takings. An accuracy figure against this corpus is a weaker claim than the same
  figure against several real shops.

### Validator change

`CorpusValidationTest` originally required every 10+ digit run to be all zeros, per the
account-number rule. EBIRR's transaction references are bare 12-digit numbers, which is
indistinguishable from an account number by shape. The rule now permits a digit run
when the sample declares it as `providerTxnId`, and requires zeros otherwise — 13
non-reference runs were zeroed in the corpus to satisfy it.

PowerShell 5.1's `Set-Content -Encoding UTF8` writes a BOM, which kotlinx.serialization
rejects. Corpus files are written with `UTF8Encoding($false)`.

---

## Session 2 — Parser engine (2026-08-01)

### Accuracy

| Run | Date | Samples | Exact | Missed | Wrong | False positives | Accuracy |
|---|---|---:|---:|---:|---:|---:|---:|
| Train + noise | 2026-08-01 | 95 | 95 | 0 | 0 | 0 | **100.00%** |
| **Holdout** | **2026-08-01** | **23** | **23** | **0** | **0** | **0** | **100.00%** |

Per-field precision and recall are 100% on both runs across direction, amountMinor,
balanceAfterMinor, counterpartyName, counterpartyPhone, providerTxnId and occurredAt.

### What those numbers are worth — read this before quoting them

**Not much yet, and the README must not imply otherwise.**

1. **The holdout holds out samples, not templates.** Both splits were drawn from the
   same 3 senders and the same ~13 message templates. Every holdout message is a
   near-twin of a training message with different amounts and names. A holdout that
   measured generalisation would contain a sender or a template the rules had never
   seen. This one measures that the rules are not overfitted to specific *values* —
   a much weaker claim.
2. **13 templates, 118 samples, one person's account.** Real coverage is thin.
3. **Zero Amharic transaction templates**, so the hardest case is untested end to end.
4. **100% is the expected result** when the rule author has read every template. It
   would be more informative if it were lower.

The number that will mean something is the one measured after the corpus includes
other people's shops, a fourth sender, and Amharic templates. Until then, quote the
corpus size alongside the figure or the figure is misleading.

### Design decisions

- **Rules are data, in one file.** `parser/src/main/resources/parser_rules.json` is the
  single source of truth. The `syncParserRules` Gradle task contributes it to the app
  as a generated asset via AGP 9's Variant API, so the rules the app ships are provably
  the ones the harness measured. Two committed copies would drift silently.
- **Direction keywords live in rule data**, never in the engine, so an Amharic template
  is a JSON edit rather than a code change and an app update. Covered by a unit test
  using real Amharic keywords even though no corpus sample needs it yet.
- **The engine never guesses.** Equal-priority rules that disagree yield `Ambiguous`; a
  field that will not normalise yields `Unmatched`. Both land in the review queue.
- **`Ignored` vs `Unmatched` matters.** 34 noise samples are correctly declined. Three
  of them come from financial senders — a CBE fraud notice, an EBIRR welcome, a
  telebirr service activation — which is why the allowlist alone was never going to be
  enough.
- **Amount extraction is anchored to the leading phrase**, not to "first number" or
  "largest number". CBE states the transaction amount, then a service charge, then VAT,
  then a *total* that differs, then a balance. Four money figures per message.
- **`AmountNormalizer` is string-only.** `500.5` is 50050 santim, not 50005 — a single
  decimal place is tenths of a birr. Trailing sentence periods (`ETB 2000.0. has
  occurred`) are stripped. More than two decimals truncate rather than round, because
  rounding up invents money.
- **The holdout test is opt-in** (`-Dmezgeb.holdout=true`) and asserts nothing. A test
  that runs on every build is a test people tune against, usually without meaning to,
  by iterating until it goes green.

### Corrections made during the session

- The corpus recorded the telebirr file's sender as `telebirr`, but those messages
  actually arrive from shortcode `127`; the `telebirr` alpha sender carries marketing
  only. All 4 telebirr samples were being ignored until the corpus was corrected.
- The accuracy harness counted noise samples in the denominator but never the
  numerator, reporting 60% when the true figure was 95.8%. Correctly declining a noise
  message is a correct outcome.

### Still stubbed

- `RuleInducer` — Session 6.
- No rule yet for any bank other than CBE, or for CBE Birr, M-Pesa or Amole. The
  allowlist anticipates them; no rules exist.

---

## Session 4 — Capture layer (2026-08-02)

### Verification results

| Check | Result |
|---|---|
| `:app:assemblePlayDebug` / `:app:assembleDirectDebug` | pass |
| `:app:testPlayDebugUnitTest` | **124 tests, 0 failures** |
| `:app:testDirectDebugUnitTest` | **131 tests, 0 failures** (124 shared + 7 direct-only) |
| `:parser:test` | 43 tests, 0 failures, 1 skipped (opt-in holdout) |
| Parser accuracy, train + noise | **100.00%** — unchanged, no parser code touched |
| SMS tokens in `app/src/main/` | clean (0 occurrences) |
| `INTERNET` in either merged manifest | clean (0) |
| Android imports in `parser/src/main/` | clean (0) |

Permissions in the merged `playDebug` manifest: `RECEIVE_BOOT_COMPLETED`,
`FOREGROUND_SERVICE`, `WAKE_LOCK`. The `directDebug` manifest adds `READ_SMS`,
`RECEIVE_SMS`, `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS`.

**Not verified: the thing this session is actually for.** Notification capture cannot
be tested in an emulator, and no real payment has been through the funnel. See
"Outstanding device verification" below — until that is done, this session is code
that compiles and passes tests, not a capture layer known to work.

### Design decisions

1. **Two tiers of deduplication, and a mutex around both.** The unique index on
   `contentHash` makes the first tier race-proof by itself, but the second is a read
   of `providerTxnId` followed by a write — two sources delivering the same payment at
   the same instant could both find nothing and both insert. `CaptureCoordinator`
   serialises every call. Throughput does not matter here: a busy shop sees a few
   messages a minute, and an import is one loop.

2. **`ContentHasher` does not strip all punctuation, contrary to 03-ARCHITECTURE.md
   section 1.** Taken literally, "strips punctuation" makes `ETB 450.00` and
   `ETB 45000` the same string, so two genuinely different payments from one sender
   collide and the second is silently dropped. Punctuation is now stripped *except*
   between two digits, which keeps decimal points and thousands separators while still
   discarding the trailing full stops and brackets that differ between the notification
   and the inbox copy of one message. `ContentHasherTest` has the regression.

3. **`ParseState.DUPLICATE` is new.** When the strong key catches a message the content
   hash did not, the row is kept and marked rather than deleted: it is the evidence
   that the two sources word the same payment differently, and deleting it would let
   the next import raise it again. Enums are stored by name in a `TEXT` column, so this
   is not a schema change and needs no migration.

4. **`lastCaptureAt` is only stamped by the notification listener, never by an import.**
   The stamp exists to expose a listener killed silently by a vendor battery manager.
   A backfill refreshing it would make a dead listener look alive, which is precisely
   the failure it is there to catch.

5. **The sender allowlist runs before the funnel in `SmsImportWorker`, not inside it.**
   A phone's inbox is mostly private correspondence and this app has no business
   storing any of it. A message from a friend is read from the cursor, found not to be
   from a financial sender, and dropped — it never reaches the database.
   `SmsImportWorkerTest` asserts this directly.

6. **`RawMessage.sender` is stored normalised.** The review queue groups by sender, and
   `CBE`, `cbe` and `+251CBE` are one sender with one rule to teach, not three. The
   body is stored verbatim.

7. **`ACCESS_NETWORK_STATE` is removed from the merged manifest.** WorkManager declares
   it for network constraints. Mezgeb has no such job and never will, and an app whose
   selling point is that it cannot reach the internet should not appear to ask about
   the network. `FOREGROUND_SERVICE` and `WAKE_LOCK` also arrive from WorkManager and
   are left alone: both are normal permissions WorkManager genuinely uses.

8. **The parser keeps `SenderAllowlist.DEFAULT` rather than narrowing to senders that
   have rules.** A financial sender nobody has written a rule for yet must reach the
   review queue, which is where the shop teaches one. Narrowing would discard exactly
   the messages the app most needs to be shown.

9. **Vendor battery targets are resolved before use.** MIUI, ColorOS, FuntouchOS, EMUI
   and Transsion's HiOS each ship an autostart list that a notification listener must be
   on to survive, and the component names are undocumented and move between firmware
   versions. `BatteryOptimizationHelper` tries each candidate against the package
   manager and falls back to the stock battery screen, so the "keep Mezgeb running"
   button cannot throw on the one device that needed it most. Deliberately
   `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (a list) rather than
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (one tap), because the latter needs a
   permission Play restricts to app categories this is not one of.

10. **Onboarding is a start destination, resolved once.** `MainViewModel` takes the
    stored flag with `take(1)`: a navigation graph cannot have its start changed
    underneath it, and re-resolving when onboarding completes would rebuild the graph
    and throw the back stack away.

11. **`SmsImporter.requiredPermissions()` returns opaque strings.** It is how the shared
    onboarding screen requests a permission that only one flavor has without any code in
    `src/main` naming it. Constraint #2 is about more than the manifest.

### Flagged — decisions worth revisiting

- **`RECEIVE_SMS` is declared and unused.** The Session 4 brief asks for both
  restricted permissions in the `direct` manifest; only `READ_SMS` has a caller,
  because live capture goes through the notification listener in both flavors. An
  unused restricted permission on a sideloaded APK is worth removing unless a live
  SMS receiver actually ships. Not removed unilaterally — it is what the brief says.
  **Resolved 2026-08-03: removed.** The author overruled the brief. Four sessions of
  evidence backed it — no receiver was ever registered, and device verification
  recorded it `granted=false` for an entire session while the import worked.
- **Rule `confidence` is not persisted.** `ParserRuleEntity` has no column for it, so
  every rule reports 1.0 in the app even where the bundled JSON says otherwise. The
  accuracy harness reads the JSON directly and is unaffected. Needs a schema bump;
  Session 6 is already opening that file.
  **Resolved 2026-08-03: schema 5 adds the column.** Flagged in Session 4, restated in
  Session 6, fixed six sessions later — which is the honest lifetime of a "needs a
  schema bump" note that nothing forces anyone to act on.
  **Resolved 2026-08-03: schema 5 adds the column.** Bundled rules keep the figure the
  asset declares; induced rules keep the inducer's 0.8.
- **The configurable banking-app package list is empty by default.** The default
  messaging app is the real source, and guessing Ethiopian banking app package names
  would fail invisibly — messages simply never arrive, with nothing to show why. Better
  filled in from a real device than from memory.
- **`SmsInboxReader` puts `LIMIT`/`OFFSET` in the sort order.** The SMS provider passes
  it through to SQLite, which is universal in practice but not a documented contract.
  If a device ever returns unpaged results, this is the first place to look.

### Stubbed / deferred

- `CaptureHealthMonitor` computes `HEALTHY` / `NO_ACCESS` / `SILENT_TOO_LONG` and has no
  UI yet — Today is still a placeholder until Session 5, which is where the indicator
  belongs.
- Nothing re-parses the review queue when a rule is added. That is rule induction,
  Session 6.
- `POST_NOTIFICATIONS` is requested alongside the import and nowhere else. Reminders in
  Session 11 will need their own request.
- No UI test for the permission flow yet. .md asks for one; it needs the
  instrumentation runner and a device, and it is worth writing in the same sitting as
  the device verification below rather than blind.

### Device verification — **done 2026-08-02**, see the section at the end of this file

The session's own verify step, none of which an emulator can answer:

1. Install `direct` on a real phone, grant notification access, have someone send a
   small telebirr payment, and confirm it reaches the database within seconds.
2. Run an inbox import afterwards and confirm the same payment is **not** duplicated —
   the tier-two path, which is the one no test can prove against real provider text.
3. Confirm the notification title really is the sender shortcode on the device's
   messaging app, and that the full body arrives in `EXTRA_BIG_TEXT` rather than
   truncated in `EXTRA_TEXT`. This assumption underpins the whole capture path.
4. Fill in the banking-app package list from what actually posts notifications.
5. Reboot and confirm the listener rebinds.

---

## Session 5 — Today, Ledger, detail and manual cash (2026-08-02)

### Verification results

| Check | Result |
|---|---|
| `:app:assemblePlayDebug` / `:app:assembleDirectDebug` | pass |
| `:app:testPlayDebugUnitTest` | **164 tests, 0 failures** |
| `:app:testDirectDebugUnitTest` | **171 tests, 0 failures** |
| `:parser:test` | 43 tests, 0 failures — parser untouched, accuracy unchanged at 100% |

New suites: TodayViewModel 8, LedgerViewModel 8, TransactionDetailViewModel 9,
CashEntryViewModel 9, TransactionDayTotals 5, plus 3 re-parse tests on
CaptureCoordinator.

**Not verified on a device.** Every screen here has been compiled, previewed and
unit-tested, and none of it has been touched by a finger. The Session 4 device list
below still stands and this session adds to it: the filter bar, the keypad and the
day headers are all things a preview flatters.

### The bug the day-header test caught

The first version grouped days in SQL with `strftime(..., 'localtime')`. The test
asserting that SQLite and `MezgebDates` agree on the same instant failed by exactly
three hours: SQLite's `localtime` reads the *process* time zone, which under the test
runner was UTC while the JVM was on +03:00.

Left alone, that is a day header whose subtotal belongs to a different day than the
rows beneath it — a wrong number that looks entirely plausible. The zone offset is now
passed into the query as a parameter, so the two compute the same boundary by
construction rather than by coincidence. It assumes a zone with no daylight saving,
which Ethiopia has none of; a build for anywhere that does needs the offset resolved
per row.

### Design decisions

1. **`FLAG_SECURE` is set on the window, not per screen.** 03-ARCHITECTURE.md asks for
   it on every screen showing amounts, which in a single-activity app where every
   screen shows amounts is the same set. Setting it once cannot be defeated by a
   navigation race, leaves no frame during a transition where a screenshot would
   succeed, and covers the recents thumbnail — which is where a ledger is most likely
   to be read over someone's shoulder.

2. **`LedgerViewModel` exposes two flows, against .md's one-StateFlow rule.**
   `PagingData` is a stream of diffs that must be collected once and cached in the
   ViewModel scope; putting it inside the UI state would make a keystroke in the search
   box look like a new list to Paging. Events still go one way through `onEvent`. This
   is the only screen that does it and the reason is written above the class.

3. **The cash keypad has no decimal key.** Digits accumulate as santim, the way a till
   does: 4, 5, 0 is four birr fifty. A decimal key can be pressed twice or forgotten,
   and either mistake is an amount wrong by a factor of a hundred.

4. **Hand-typed amounts go through the parser's `AmountNormalizer`.** The edit screen
   and the message parser interpret "450.5" with one piece of code, so they cannot
   drift apart.

5. **`editedAt` is set only when a *parsed* field changes.** Adding a note leaves it
   null: the ledger's "edited" marker means the number may no longer be what the
   message said, and a note does not touch the number. See the flag below — this
   collides with what Session 3 decided for categories.

6. **Pull to refresh re-parses the review queue.** There is nothing to fetch in an
   offline app, so refresh means "try again on the things you could not read". A rule
   taught since the last look now applies to the messages already waiting, which is
   what makes teaching a rule feel worth doing. It lives in `CaptureCoordinator`
   alongside capture, because it is the same funnel with a different source of
   messages. Rule *induction* is still Session 6.

7. **"Always use this category" writes a visible `CategoryRule`**, not an invisible
   learned preference — a name matched loosely because providers render names
   inconsistently, a phone matched exactly because it either is the number or is not.
   The user can read and delete it later, which an inferred preference does not allow.

8. **Two empty states everywhere it matters.** The ledger distinguishes "no transaction
   matches these filters — your ledger is not empty, the filters are hiding it" from a
   genuinely empty ledger; Today distinguishes a quiet morning from "capture has never
   worked once". Same layout, completely different meaning.

9. **DAO tests now run Room on the calling thread** (`setQueryExecutor { it.run() }`).
   Room was resuming suspend functions on its own executor, so a ViewModel test could
   land a coroutine on `Dispatchers.Main` after the test had reset it — failing
   intermittently, as "Main is used concurrently with setting it", nowhere near the
   code responsible.

### Flagged — a collision worth resolving in Session 8

`editedAt` is being asked to mean two different things.

- The **ledger badge** wants "a parsed value no longer matches the message", which is
  what this session's edit path writes.
- **Reconciliation** (03-ARCHITECTURE.md section 7, item 4) wants "a human touched this
  row inside the period", which is what Session 3's `setCategory` writes — so
  categorising a transaction currently marks it as edited.

One column cannot honestly carry both. Session 8 owns reconciliation and should split
them, most likely into `editedAt` for parsed-value edits and a separate `touchedAt`.
Not decided unilaterally here, because the reconciliation requirement is real and
guessing at it would break the feature that depends on it.

### Deferred

- **The date filter offers presets, not an arbitrary range** — today, 7 days, 30 days,
  any. A two-ended date picker is more UI than the shop-owner case needs, and Session 9
  needs a real range picker for reports anyway; better built once, there.
- **The day-header grouping is not tested at the list level.** The daily totals and the
  day boundary both are; the `insertSeparators` logic that turns them into headers is
  not, because reading items back out of a `PagingData` needs
  `androidx.paging:paging-testing`, which is not in the version catalog. Worth adding
  when the review queue's list arrives in Session 6.
- **Only the date is editable, not the time of day.** Changing the date preserves the
  original time. Editing a time on a captured payment is a strange thing to want; if it
  turns out to be needed, it is a picker away.
- **No UI tests yet.** .md wants them for permission flows and rule induction —
  the second of those does not exist until Session 6, and both want a device.
  *(Session 6 added the rule-induction one; the permission flow is still open.)*

---

## Session 6 — Review queue and rule induction (2026-08-02)

### Verification results

| Check | Result |
|---|---|
| `:parser:test` | **65 tests, 0 failures** (was 43) |
| `:app:testPlayDebugUnitTest` | **180 tests, 0 failures** |
| `:app:testDirectDebugUnitTest` | **187 tests, 0 failures** |
| `:app:assemblePlayDebug` / `assembleDirectDebug` | pass |
| Corpus accuracy | unchanged — no bundled rule or normalizer was touched |

New: `RuleInducerTest` 20, `CorpusInductionTest` 2, `RuleBuilderViewModelTest` 7,
`RuleBuilderScreenTest` 4 (a real UI test), `ReviewViewModelTest` 5.

### The corpus test earned the whole session

03-ARCHITECTURE.md section 5 specifies anchoring the induced pattern with `^...$`.
Implemented exactly as written, then measured against real messages:

```
removed rule    : cbe_transfer_out_v1
taught from     : cbe_048
other messages  : 21
matched         : 0        ← the documented algorithm
hit rate        : 0.0%
```

CBE ends every message with a receipt URL carrying a random token
(`https://mbreciept.cbe.com.et/i0-<random token>`); EBIRR does the same. With
`$` anchoring, that token has to match exactly, so a rule taught from one message
reads no other message ever. The feature would have shipped looking correct and doing
nothing.

The tail is now truncated to 24 characters and neither end is anchored — enough
closing text to bound the last capture group, and none of the receipt link. Literals
*between* spans are still kept in full, because those are the template's signature.

```
matched         : 21 of 21
hit rate        : 100.0%
```

A second test asserts the induced rule matches **zero** messages from other senders,
so the fix bought coverage rather than looseness.

### Design decisions

1. **The tapped field decides how tightly a span is matched, not the text.** The
   architecture infers a "content class" from the span's characters alone, but a bare
   ten-digit phone number and a large amount are the same characters. Getting that
   wrong produces a rule that reads a phone number as money. The user already said
   which it was by tapping a chip; the text only confirms it, and falls back to
   free-text when it disagrees.

2. **A read-only `BasicTextField`, not a `SelectionContainer`.** Compose gives no way
   to read a selection out of a `SelectionContainer`, and the selection is the entire
   input to this screen. The highlight is a `VisualTransformation` with
   `OffsetMapping.Identity` — load-bearing, because a transformation that changed the
   text length would make every subsequent offset point at the wrong character.

3. **Four taggable fields, not seven.** Amount, Who, Reference, Balance. Direction is
   a separate two-chip question because it is a property of the template rather than a
   piece of its text, and timestamp is not worth the screen space when `receivedAt` is
   already a good answer.

4. **"Resolve manually" lives on the rule builder, not on a list of messages.** The
   session brief puts it per-message in the queue, which would need a second list
   screen to reach individual messages. Instead the builder pages through the sender's
   messages and offers both endings — you tag the spans either way, and the only
   question left is whether to keep the rule. Fewer screens, same two outcomes.

5. **Saving a rule re-parses the backlog inline, then hands the rest to WorkManager.**
   The inline pass is what makes the flow feel like it did something: the user sees
   "3 messages could now be read" before leaving the screen. The worker picks up
   anything an inbox import left behind, where thousands of messages should not block
   a screen.

6. **`resolveManually` goes through `CaptureCoordinator`.** A message resolved by hand
   still has to deduplicate against the same payment arriving from the other source,
   so it uses the same funnel rather than writing a transaction directly.

7. **The backlog worker runs without a foreground notification.** The `play` flavor
   declares no foreground service type, and a job that only reads the local database
   needs no user-visible lifeline. Progress is reported through WorkManager and shown
   on the review screen while someone is watching it.

8. **The empty review queue is drawn as a success.** A tick and "every message was
   read", not a shrug. A user who learns to read "nothing here" as failure stops
   opening the screen, and this queue only works if it gets opened.

### The UI test

`RuleBuilderScreenTest` runs the span-selection interaction under Robolectric rather
than on a device, so it runs on every build. It covers the chain no unit test can see:
chips appear only once something is selected, and the range a chip reports is exactly
the characters that were highlighted — an off-by-one there reads `450.0` as the amount
and silently loses a santim.

### Deferred

- **Not tried by a human.** The session asks for the flow to be timed end to end, with
  a 90-second target and a screen capture. Both need a device and both are still open.
  The interaction is three taps by design; whether it reads that way to someone who has
  not built it is exactly what the timing is meant to find out.
- **Induction learns from one message at a time.** Generalising across two or three
  tagged examples would produce better rules — literals that differ between examples
  could be loosened automatically rather than left to the trailing-context heuristic.
  Worth doing only if the 100% figure above turns out to be optimistic on senders
  outside this corpus.
- ~~**The parser rule `confidence` field is still not persisted**~~ **Done 2026-08-03**,
  schema 5. Induced rules now keep the 0.8 the inducer gave them all the way to the
  engine, instead of arriving there claiming certainty.

---

## Session 7 — Credit book (2026-08-02)

### Verification results

| Check | Result |
|---|---|
| `:app:testPlayDebugUnitTest` | **225 tests, 0 failures** (was 180) |
| `:app:testDirectDebugUnitTest` | **232 tests, 0 failures** |
| `:parser:test` | 65 tests, 0 failures — parser untouched |
| `:app:assemblePlayDebug` / `assembleDirectDebug` | pass |
| Schema | **v1 → v2**, migrated and tested |

New: `CustomerCreditTest` 12, `SuggestRepaymentMatchesTest` 8, `ReminderMessageTest` 6,
`CreditBookViewModelTest` 16, `MigrationTest` 3.

### The first schema migration

Archiving a customer needed a column, so this is the project's first migration —
`ALTER TABLE customers ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0`.

There is no `fallbackToDestructiveMigration` in this project, which is only a safe
decision if the migrations are right: the alternative to a tested migration is not a
crash, it is a shop's ledger quietly emptying on an app update. So `MigrationTest`
builds the v1 database **from the committed `schemas/…/1.json`** — including the
identity hash, so Room recognises it — puts real customers, credit entries,
transactions and raw messages in it, runs the migration, and reads everything back
through the real DAOs. Room validates the migrated schema against what the code
compiles to on open, so a migration that produced anything else fails there.

**The test was checked by breaking the migration.** Emptied to a no-op, all three
tests fail with Room's `IllegalStateException`; restored, all three pass. A migration
test that has never been seen to fail is not a test.

Room's own `MigrationTestHelper` reads schemas from instrumentation assets, which do
not exist in a Robolectric unit test — hence building v1 by hand. The upside is that
this runs on every build rather than only when someone remembers to plug in a phone.

### Design decisions

1. **Archive, not delete.** Deleting a customer cascades their credit history away,
   and the customer you most need records for is the one who has just settled up and
   gone quiet. Archived customers leave the list and the totals; every entry stays.

2. **The outstanding total sums only positive balances.** Netting off a customer the
   shop happens to owe money to would understate the debt the shop is actually
   carrying, which is the one number that header exists to state.

3. **Kotlin and SQL both compute balances, and a test holds them together.** The
   customers list cannot load every entry to render a row, so the sign convention
   lives in `ComputeCustomerBalanceUseCase`, `CustomerDao` and `CreditEntryDao`. Two
   tests run the same entries through both paths — including a negative balance and
   an out-of-order statement — and assert they agree.

4. **A repayment larger than the balance is accepted.** It produces a negative
   balance: the shop is holding the customer's money. Refusing it would be the app
   telling a shop owner that something which just happened in front of her did not
   happen.

5. **Repayment links are suggested, never automatic.** Recent unlinked money-in
   transactions are ranked — amount first, phone second — and each row states its
   reason, so the ranking is checkable rather than magic. A wrongly linked repayment
   makes a debt vanish against an unrelated payment and surfaces weeks later at
   reconciliation with no way to tell which record lied. A payment already claimed by
   another repayment is excluded, so one payment cannot settle two debts.

6. **Phone numbers are compared by their last nine digits.** `+251911234567`,
   `0911234567` and `911234567` are one number written three ways, and which form
   arrives depends on the provider.

7. **Reminders open the messaging app; they never send.** `ACTION_SENDTO` with the
   text prefilled, no `SEND_SMS`, ever. A test asserts the action is not `ACTION_SEND`
   — an app that can message a shop's customers on its own judgement is a different
   and much worse product. The template is stored, not hard-coded, and falls back to
   a translated default so the built-in wording is right in both languages.

8. **Days outstanding are calendar days in local time.** Credit given yesterday
   evening is one day old this morning, which is how the person holding the book
   counts it — not a 24-hour clock.

### Deferred

- **The reminder template and the overdue threshold are stored but not editable yet.**
  Both live in DataStore with defaults and are read by the credit book; the settings
  screen that edits them is Session 11, which owns settings. The session brief puts
  the template's editor in settings, so this is where it stops for now.
- **No inline "create customer" from the credit-sale screen.** The brief allows
  creating one inline; today you add the customer from the list first. One screen
  fewer to build, one tap more to use — worth revisiting after someone has actually
  used it at a counter.
- **`editedAt` still carries two meanings** (flagged in Session 5). Session 8 owns
  reconciliation and should split it.

---

## Session 8 — Reconciliation (2026-08-02)

### Verification results

| Check | Result |
|---|---|
| `:app:testPlayDebugUnitTest` | **246 tests, 0 failures** (was 225) |
| `:app:testDirectDebugUnitTest` | **253 tests, 0 failures** |
| `:parser:test` | **75 tests, 0 failures** (was 65) |
| `:app:assemblePlayDebug` / `assembleDirectDebug` | pass |
| Schema | **v2 → v3**, migrated and tested from v1 through both |
| `play` merged permissions | `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `WAKE_LOCK` — still no `INTERNET`, no `ACCESS_NETWORK_STATE` |

New: `ReconcilePeriodUseCaseTest` 10, `ReconcileViewModelTest` 10, `AmountSnifferTest`
10, plus a fourth migration test covering v1 → current in one run.

### The formula, as documented, tells the shop it is short by exactly its cash

03-ARCHITECTURE.md section 7 writes:

```
capturedIn   = Σ transactions where direction=IN
cashRecorded = Σ transactions where channel=CASH and direction=IN
accountedFor = capturedIn + cashRecorded + creditGiven - creditRepaid
```

A cash sale is a transaction with `direction=IN` and `channel=CASH`, so it is inside
both terms. Implemented literally, a shop that took 200 birr by telebirr and 100 in
cash is told it accounted for 400 and is therefore missing 100 — **the exact amount
of cash it took**. That is the worst answer a reconciliation screen can give: it is
plausible, it is precise, and it sends someone hunting for money that was never
missing.

The glossary settles the intent: "captured" means "arrived as a message", and a cash
entry has no message. So `capturedIn` here excludes `CASH`, and the first test in
`ReconcilePeriodUseCaseTest` is the one that pins it down.

### Design decisions

1. **`ExpectedSalesProvider` is the v2 seam, and nothing names the implementation.**
   `ManualEntryExpectedSalesProvider` reads the figure a person typed into the open
   period; a Suq-backed one is a new binding in `DomainModule` and nothing else.

2. **The session records `openedAt` (schema v3).** "Edits made since the period was
   opened" is one of the ranked explanations, and a shop reconciling yesterday opens
   the period today — measuring from `periodStart` would have counted edits made
   before anyone was looking. The migration defaults existing rows to 0, which
   over-reports rather than hides.

3. **Gap explanations rank by how much they could account for, and the unknowable
   rank last rather than as zero.** An edit has no amount attached; calling it zero
   would claim it explains nothing, which is not what "we don't know" means.

4. **`AmountSniffer` lives in `:parser` but outside the engine.** Ranking unreadable
   messages needs a rough total, and "14 messages, roughly 3,200 birr between them"
   is far more useful than "14 messages". It is anchored to a currency word rather
   than taking the first number, it reports how many messages it actually found an
   amount in, the UI says "roughly", and nothing it produces is ever stored. The
   parser still declines rather than guesses; this is a hint shown as a hint.

5. **Balanced is drawn as an event, not as a zero.** It is the moment the app
   justifies itself. A shop owner who reaches it should feel it, not have to read a
   column of numbers to work out that nothing is wrong.

6. **Every explanation is tappable through to the screen that would fix it** —
   unreadable messages to the review queue, credit to customers, the rest to the
   ledger. A lead that cannot be followed is a complaint.

7. **The reminder is once a day at most, only after a real week, low importance, and
   refusable.** `POST_NOTIFICATIONS` is now declared in the main manifest for it and
   for nothing else; refusing it costs the reminder and nothing more.

### Closed periods are immutable, and it is tested

Two tests, both explicitly required by the brief and both worth the space:

- editing a transaction inside a closed period leaves every closed figure exactly as
  it was;
- the same edit then appears in the **next** period's `EditedSincePeriodOpened`
  explanation, which is the honest place for it.

### Deferred

- **The reminder toggle is stored but has no switch yet.** Default on, read by the
  worker, editable in Session 11 along with the rest of settings.
- **`POST_NOTIFICATIONS` is never requested at runtime.** On API 33+ that means the
  reminder is silently suppressed until something asks. Session 11's settings screen
  is the right place to ask, next to the toggle that controls it.
- **The closed-period snapshot is a dialog, not a destination.** It is read-only and
  it shows the stored columns rather than a recomputation, which is what the brief
  asks for; a full screen would be a better home if it ever grows a chart.
- **`editedAt` still carries two meanings** (Session 5's flag). Reconciliation turned
  out not to force the split: `countEditedSince` wants "a human touched this", which
  is exactly what the column already means. The ledger badge is the loose one. Left
  as it is, now with a reason rather than an intention.

---

## Device verification (2026-08-02, Samsung SM-G965U1, Android 10 / API 29)

Eight sessions of code met a real phone. The Session 4 checklist that had been open
since then is now closed, and it found something.

### The finding: the app was storing live banking credentials

Teaching a rule for HIBRETBANK — a fourth sender that appeared in the real inbox and
that the corpus never contained — put a message on screen that was a **mobile-banking
TPIN reset with the new PIN in cleartext**. Mezgeb had stored it in `raw_messages`,
where it sat in the review queue, rendered in full, inviting the user to select
spans out of it.

Three such messages were on the device: two from HIBRETBANK, one from 127.

The reasoning had already been done once and applied to the wrong place. `CORPUS.md`
records that during collection **19 messages were dropped rather than redacted**
because they carried codes or PINs — "a redacted credential is still a credential in
git history." That judgement was applied to the repository and never to the app. The
capture funnel stored whatever an allowlisted sender sent, so credentials accumulated
in the ledger and would have been carried into the backup and export features that
Sessions 9 and 10 are about to build.

No unit test would have found this. Only real messages from a real bank would.

**The fix:**

- `CredentialDetector` (`:parser`, pure, 7 tests) recognises a message that *states* a
  secret. `CaptureCoordinator` declines those **before the insert** — there is no row
  and no id, because marking and keeping would leave the credential at rest, which is
  the whole thing being avoided.
- `CredentialPurge` runs on every launch and deletes what earlier builds stored. A PIN
  in a ledger does not become harmless because the next version would not have kept it.
  Transactions are left alone: the foreign key is `SET NULL`, so a payment keeps its
  row and loses its link to the text. The money record is what the shop needs; the
  message text is the part that must not be kept.
- The import result now says how many messages were passed over and why. A shop owner
  who counts 273 and sees 270 accounted for deserves the answer.

**Precision is the hard part, and it is tested in both directions.** Detection requires
a credential word *followed by a value*: CBE's payment messages carry "never share your
PIN" footers, and firing on one of those would silently cost a shop a payment — the one
thing this app exists to prevent. `CredentialDetectorTest` pins both sides.

Verified on the device: 273 raw messages → 270 after the purge, 3 removed, **all 263
transactions intact and no orphans**. One row still matches a loose `LIKE '%otp%'`
search: a genuine CBE transfer whose receipt URL happens to contain those letters. The
strict detector correctly left it alone.

### What else the device confirmed

| Check | Result |
|---|---|
| Notification listener binds | live `ServiceRecord` + `ConnectionRecord` |
| App detects access correctly | "Reading payment messages is on" |
| Vendor battery screen resolves | Samsung target found, card shown |
| Only `READ_SMS` is ever requested | `RECEIVE_SMS` remained `granted=false` |
| Import against the real inbox | **273 captured, 263 parsed — 96.3%** |
| Re-import over the same messages | **"0 payments added. 273 were already in your ledger."** |
| `FLAG_SECURE` | `screencap` exits 1 with a 0-byte file; the home screen captures fine |

Thirteen of the fourteen bundled rules fired on real traffic. The review queue held 10
messages across 4 senders, which after the purge is 7 across 3.

**On that 96.3%:** the corpus was extracted from this same phone, so this is not unseen
data. It is 273 messages against a corpus of 118, so most of it is new — but the
honest claim is "the rules generalise across this one account's history", not "the
rules generalise".

### Two smaller things the device surfaced

- **`FLAG_SECURE` blocks screen recording.** Session 6 wants a screen capture of the
  teach-a-rule flow as demo material, and that now requires temporarily removing the
  flag. Worth a deliberate decision rather than a surprise on the day.
- **"1 messages".** The review queue interpolates counts into a plain string. Android
  has `<plurals>` for exactly this, and Amharic pluralises differently again. Not yet
  fixed.

### Still not verified

Notification capture end to end. The listener binds and the app agrees it has access,
but no payment has arrived through it — that needs a real incoming message, which
cannot be fabricated from the shell (`cmd notification post` posts as the shell's own
package, which the source filter correctly refuses). The import path exercises the same
funnel, the same parser and the same dedup, so what remains untested is specifically
`onNotificationPosted` and the `EXTRA_BIG_TEXT` extraction.

### A suggested addition to .md

Hard constraint #5 covers raw bodies never leaving the device and never being logged.
It does not cover *storing* a message that contains a credential. Worth adding, since
the app now depends on it:

> **Messages that state a secret are never stored.** A PIN, a one-time code or a
> password arriving from an allowlisted sender is declined at the funnel, not stored
> and marked. Detection must require a credential word followed by a value, because
> refusing a genuine payment message is the more expensive error.

### The notification path, verified — and a second finding

A plain SMS was sent to the phone and watched. It landed:

```
source        parseState   count
NOTIFICATION  IGNORED      1
```

So `onNotificationPosted` fires, the package filter admits the messaging app, the
title becomes the sender and the text becomes the body, and the funnel and the parser
both run. The last untested piece of Session 4 is now tested.

**And it exposed the second privacy hole.** That message was from a person. It was
stored verbatim in `raw_messages` and merely labelled `IGNORED` — while the onboarding
screen promises, in both languages:

> It reads only messages from banks and payment services. Messages from people are
> ignored.

A shop owner reads *ignored* as *not kept*. It was kept.

The asymmetry had been written down and praised a session earlier: `SmsImportWorker`
applied the sender allowlist **before** the funnel precisely so private correspondence
never reached the database — "a message from a friend is read from the cursor, found
not to be from a financial sender, and dropped". The notification listener did no such
thing. One policy, two paths, one of them wrong, and the wrong one is the path that
runs on every message a phone receives.

**The fix:** the allowlist check moved into `CaptureCoordinator`, before the insert,
next to the credential check. The import worker's own filter was deleted rather than
kept in parallel — one funnel, one place for the policy to be wrong. `PrivacyPurge`
(formerly `CredentialPurge`) now removes both kinds.

The distinction that had to survive: a **financial** sender whose message no rule can
read is still stored, because that is the review queue. "We could not read this" and
"this was never ours to keep" are different answers, and the whole teach-a-rule feature
depends on the first one existing.

Verified on the device: the personal message is gone, and the ledger is untouched —
263 transactions, 0 orphans, 7 still in the review queue.

### What device verification actually cost and bought

Two hours on a phone found two data-protection defects that eight sessions of unit
tests, a 118-sample corpus and a 100% accuracy figure did not. Both were failures of
the same kind: a rule stated correctly in one place and not applied in another.
Neither was a logic error a test could have caught without real messages from a real
bank and a real person.

The 96.3% figure is worth less than the two bugs.

---

## Session 9 — Reports and export (2026-08-02)

### Verification results

| Check | Result |
|---|---|
| `:app:testPlayDebugUnitTest` | **288 tests, 0 failures** (was 258) |
| `:app:testDirectDebugUnitTest` | **295 tests, 0 failures** |
| `:parser:test` | 83 tests, 0 failures — parser untouched |
| `:app:assemblePlayDebug` / `assembleDirectDebug` | pass |
| Reports on the device | 36 transactions over 7 days, in/out/net rendered |
| PDF export on the device | valid `%PDF-1.4`, `/FontName /NotoSansEthiopic` embedded |

New: `CsvExporterTest` 12, `BuildReportUseCaseTest` 9, `ReportsViewModelTest` 7,
`PdfExporterTest` 2.

### A bug that only the phone could show

Exporting a PDF wrote a file called **`mezgeb-summary-2026-08-02.pdf.csv`**. The bytes
were a correct PDF; the name was not, and Android would have handed it to a
spreadsheet app.

`rememberLauncherForActivityResult` captures its contract on **first composition** and
never rebuilds it, so `CreateDocument(viewModel.mimeType(pendingKind))` pinned
`text/csv` — whichever kind happened to be selected first — no matter what the user
chose from the menu afterwards. The document picker then appended the extension for
the type it had been given.

Replaced with a small `ActivityResultContract` that takes the name *and* the type as
its input, so both travel with each launch and nothing is captured at composition.
Re-verified on the device: `mezgeb-summary-2026-08-02.pdf`, 37 KB, valid header.

Every unit test passed before and after. The contract-capture rule is a Compose
lifecycle detail, not a logic error, and it is invisible until a picker is on screen.

### Design decisions

1. **The byte-order mark is not decoration.** Excel on Windows reads a UTF-8 CSV as
   the system code page unless the file starts with a BOM, which turns every Amharic
   name into mojibake. Three ugly bytes decide whether the export is usable by the
   people this app is for.

2. **CSV amounts are ungrouped.** `MoneyFormatter.format` writes `1,200.50`, and a
   comma inside a number in a comma-separated file is a bug waiting for a spreadsheet.
   Added `MoneyFormatter.formatPlain`, which also absorbed a helper that had been
   copy-pasted into two ViewModels.

3. **The chart is 60 lines of `Canvas`, not a dependency.** It handles the three
   things the obvious version gets wrong: negative bars get a baseline where zero is
   rather than at the bottom; days with no activity are drawn as gaps rather than
   skipped, so a quiet week looks quiet; and a month scrolls horizontally instead of
   squeezing to illegibility.

4. **Percentages are of money in, not of everything.** Mixing directions into one
   denominator produces shares that sum past 100% and answer no question.

5. **Money with no category is its own slice**, labelled "—". Dropping it would leave
   the percentages not adding up, and "nobody has said what this was" is a real answer.

6. **Export never picks its own destination.** `ACTION_CREATE_DOCUMENT` hands that to
   the system picker: no storage permission, and the file can land somewhere this app
   cannot read back. For an app whose selling point is that data stays put, the export
   is the one door out and the user holds the handle.

### An admission rather than a green tick

`PdfExporter` has almost no unit tests. `PdfDocument` is a thin wrapper over native
code — `nativeCreateDocument()` returns 0 under Robolectric, so the first `startPage`
throws "document is closed!". There is no shadow, and writing one would test a fake.

Rather than leave two tests that look like coverage and assert nothing, the rendering
is verified on the device and said so in the file. What the device confirmed: a valid
PDF with the bundled Ethiopic font actually embedded, which is the mechanism that
stops Amharic coming out as boxes.

**Still unverified:** whether Ethiopic *glyphs* render correctly on the page. The shop
name is the only Amharic on it and it cannot be set until Session 11 builds the
settings screen, so the exported page currently has no Ethiopic text to look at. That
check moves to Session 11.

### Deferred

- **The shop name is stored but not editable.** `SettingsRepository.observeShopName`
  exists and the PDF header reads it, falling back to "Mezgeb". Session 11 owns the
  screen that sets it.
- **Chart bars carry no labels.** The dates are implied by the period, and axis labels
  at bar width would be unreadable on a phone. Worth revisiting if anyone asks which
  bar is which.

---

## Sessions 10 and 11 — written up late (2026-08-03)

Both sessions shipped without a NOTES.md entry. These two sections were reconstructed
from the commits afterwards, so they record decisions accurately but are not a running
log the way the rest of this file is. The gap itself is the finding: the end-of-session
write-up is the step that gets skipped when a session runs long, and it is the step that
makes every earlier section worth having.

### Session 10 — Security and backup (2026-08-02)

Covered by `8bcdcd3`, whose message carries the reasoning in full. In brief: PBKDF2 in
`EncryptedSharedPreferences`, biometric with the PIN always as fallback, exponential
backoff after five wrong guesses, and the lock drawn *instead of* the navigation graph
rather than over it. Backups are AES-256-GCM with a deliberately plaintext header, so
restore can say why it refused without decrypting first.

**Two iteration counts on purpose** — 210,000 for a backup that runs once, 60,000 for an
unlock that runs forty times a day. A slow lock screen is one people turn off.

**Deferred / device-gated:**

- `AppLockRepository` sits at **0% coverage** and cannot be tested off-device: it stores
  the PIN hash behind a Keystore-backed key, and the Keystore does not exist under
  Robolectric. Verified on the phone instead. This is a real limit, not an oversight,
  but it means the class with the most security weight has the least automated proof.
- `PdfExporter` is at **2.1%** for the same shape of reason — `PdfDocument` is native and
  returns a closed document under Robolectric.

### Session 11 — Demo mode, localization, polish (2026-08-03)

Covered by `75e82dd`. Demo mode is a second database *file*, chosen once at process start
in `DatabaseModule`, seeded before the flag flips so a failed seed leaves the app pointing
at the real ledger. Language is applied in `attachBaseContext` — `LocaleManager` on API
33+, a wrapped configuration below.

**Deviation from .md, deliberate.** `LanguagePreference` and `DemoModeStore` use
SharedPreferences where the stack section specifies DataStore. Both are read in
`attachBaseContext`, before any coroutine scope exists, and a language that arrives one
frame after the first screen is a visible flicker on every launch. Documented in both
class docs.

**What the brief asked for and did not get:**

- **Predictive back was missed** and only added afterwards, in `docs`-session cleanup.
  `android:enableOnBackInvokedCallback` was absent, so Android 13+ ran the legacy back
  path. The gesture still worked, which is exactly why it went unnoticed — the omission
  shows up as an app that feels a version older than the phone, not as a bug.
- **`DemoModeController` is at 0% coverage.** `DemoDataTest` covers the generated data
  thoroughly, because it is pure functions over a seeded `Random`. The controller that
  actually swaps databases is untested — and it is the one switch in the app that
  decides which file a shop's data is read from. The seam is testable; nothing tests it.
- **TalkBack, the slow-device jank pass and the 48dp touch-target audit are device work**
  and remain open.

---

## Session 12 — Release and portfolio (2026-08-03, partial)

R8, signing, README, ARCHITECTURE and the corpus write-up landed in `7f0c13b`. The
holdout ran once more at 100.00% (23/23) and nothing was changed in response.

### Release numbers

Both flavors build to **3.07 MB** against a 15 MB target, down from 16.3 MB unminified.
The merged `play` release manifest carries zero SMS permissions and zero `INTERNET`; the
`direct` one carries the two SMS permissions and still no `INTERNET`. Neither carries
`ACCESS_NETWORK_STATE`, which WorkManager contributes and the main manifest removes.

### The git history scan found one thing

Clean on keys, keystores, `local.properties`, raw corpus and hardcoded secrets. Every
phone number across all commits is a synthetic placeholder.

**But `tools/redact-samples.ps1` has a hole.** Its account-number rule is
`(?<![\d,.])\d{10,}(?![\d,.])`. The trailing `(?![\d,.])` exists to avoid mangling
formatted amounts, and it also exempts any digit run followed by a comma. EBIRR writes
transaction ids as `Id: <twelve digits>,` — comma immediately after. The transaction-reference
rule does not catch them either, because it requires both a letter and a digit.

So **15 distinct 12-digit EBIRR transaction ids passed through neither rule**, in
`corpus/train/ebirr.json` and `corpus/holdout/ebirr.json`. `CorpusValidationTest` then
permits them, because it allows a long digit run when it equals the sample's declared
`providerTxnId` — which these do. The guard meant to catch this is structurally unable to.

Whether those 15 values are real or were faked by hand is not determinable from the
repository. If they are real they are transaction references from the author's own
account in a public portfolio repo.

**Resolved 2026-08-03.** The tool's lookaround now excludes a separator only when it has
a digit on its far side; the validator now requires a declared numeric reference to also
be in redacted form, because "the sample said it was a reference" is a check that cannot
fail; and the fifteen values are replaced with zero-padded sequence numbers of the same
length. Accuracy is unchanged at 95/95 and 23/23 — the rules match digit patterns, not
particular digits. Full write-up in CORPUS.md.

The guard was verified by re-introducing one of the old values and watching the build
fail, then restoring it. A privacy test that has never been seen to fail is a privacy
test nobody has checked.

**Still open: git history.** The values are in every commit that touched those files.
There is no remote and nothing has been pushed, so a rewrite is safe but destructive —
every SHA changes. The repository owner's call.

### Coverage — and the number that was wrong first

**35.2% instruction, 45.1% line, 21.9% branch** on `playDebug`.

The first run reported 6.6%, and publishing that would have been worse than publishing
nothing. Robolectric loads classes through its own instrumenting classloader, which
strips the debug information JaCoCo maps execution back to; JaCoCo drops those classes
rather than guess. 30 of this module's 42 test classes run under Robolectric, so the
report measured the other 12 and looked like a project with no tests instead of one with
a measurement problem. `isIncludeNoLocationClasses = true` fixes it.

Coverage is reported, not enforced. No threshold, no build failure: a gate is a rule
about a proxy, satisfied by a test that executes a line without asserting anything about
it, and the cheapest way past a failing gate is always the least useful test.

The `ui/*` packages sit at 5–25% and that is intentional — .md says not to write UI
tests for layout, and those numbers are Compose screen bodies.

**The three least-tested areas that should be tested:**

1. ~~**Background execution.** `ReprocessBacklogWorker` at **0%**, `ReconcileReminderWorker`
   at **9.9%**.~~ **Done** — both at 100%, 15 tests, see below.
2. ~~**`DemoModeController` at 0%**~~ **Done** — 96.7%, 10 tests, see below.
3. **`PdfExporter` at 2.1%** and **`AppLockRepository` at 0%** — both blocked by native or
   Keystore dependencies that do not exist off-device, both verified on hardware instead.
   Named here so the number is not mistaken for negligence, and so nobody "fixes" it with
   a mock that asserts the mock.

### The workers, tested (2026-08-03)

Both now at 100%. `androidx.work.testing` was already on the classpath and
`SmsImportWorkerTest` already showed the pattern; these two had simply never got one.

**A boundary I got backwards.** The first version of the reminder test asserted that
exactly seven days does *not* fire. It does. The worker returns early on
`elapsed < REMIND_AFTER_DAYS * DAY`, so the window closes the instant the seventh day is
reached rather than a day later. The code was right and the test was wrong; the test now
pins the real behaviour from both sides, at exactly seven days and one millisecond short.
"After a week" is ambiguous by a day, which is invisible in review and obvious to whoever
receives the notification.

What the reprocess tests are really for: a message no rule can read has to **stay** in the
queue. "We still cannot read this" and "this was never ours to keep" are different
answers, and the review queue depends on the first one existing. Also covered — a re-run
over an already-cleared queue not duplicating the ledger, which is a real path because
`APPEND_OR_REPLACE` queues a second pass behind the first.

`WorkManagerBacklogReprocessor` stays at **0%** and should. It is the thin seam whose own
doc says it exists so ViewModels can be tested without WorkManager; testing it would mean
initialising WorkManager per process, which is what the seam was built to avoid.

### Open as of the release-config commit — superseded

*Kept as the record of where things stood mid-session. The current list is
"What is left" at the end of this file; read that one.*

- The release build has **not been run on hardware**. R8's effect on the notification
  listener is unverified, and Session 12 names that as the check to make.
- No signing key, so both release APKs are unsigned.
- The permission-flow UI test .md asks for still does not exist; it needs the
  instrumentation runner and a device.
- ~~Rule `confidence` is still not persisted~~ — **done 2026-08-03**, schema 5.
- ~~`RECEIVE_SMS` is still declared and unused.~~ **Removed 2026-08-03**, on the author's
  call. The `direct` release manifest now merges to `READ_SMS` and nothing else; the
  `play` one still merges to zero SMS permissions and zero `INTERNET`. This is the one
  place the project knowingly departs from a written session brief, and the departure is
  recorded in both the manifest comment and the Session 4 entry above rather than left
  for someone to discover as a discrepancy.

### The demo switch, tested (2026-08-03)

`DemoModeController` 0% → 96.7%, `DemoModeStore` → 100%, across 10 tests.

This one does not use the in-memory `DatabaseTest` helper, and could not: the whole
mechanism under test is *which file gets opened*, and an in-memory database has no file.
So it builds a real `mezgeb.db` on Robolectric's filesystem, seeds it, and asserts what
is still in it afterwards.

**The assertions that matter are the negatives.** A real ledger with two transactions
survives demo mode being switched on and off with both rows intact, and after enabling —
which writes several hundred demo rows — the real database still contains exactly one.
"Disabling restores the real database untouched" is not a restore at all: the real file
is never named while the flag is set, and these tests are what makes that claim checkable
rather than a comment.

**The failure ordering is pinned.** A clock that throws is injected so the seed fails
before it writes anything; the test then asserts the flag is *still false* and
`databaseName()` still points at the real ledger. That ordering is the entire design of
`enable()` — had the flag been set first, a failed seed would leave the app opening a
half-written demo file and calling it the shop's books. It also asserts the failure comes
back as a `Result` rather than an exception, because the caller is a ViewModel that has
to put a message on a settings screen.

Idempotence is covered too: enabling twice deletes and rebuilds, so a demo shown twice is
the same shop both times rather than one with everything in it twice.

**Not covered: the catch branch in `disable()`.** `runCatching { deleteDatabase(...) }`
exists for a device that will not release the file, which is not reachable under
Robolectric without faking the Context. The class doc already says a stale demo file is a
few hundred kilobytes and not a problem, and the flag is cleared before the delete is
attempted, so the branch has nothing to get wrong. Left at 72% on that lambda deliberately.

### Third instance of the same mistake (2026-08-03)

A real CBE receipt URL was the worked example in two places: this file, and a doc
comment in `RuleInducer` — shipped source in the parser module, sitting there since the
rule-induction session. The comment explains why the documented `^...$` anchoring cannot
work, and the example it reached for was a genuine receipt link off the author's device.
The point being made needs the *shape* of a random token, never the token. It now reads
`i0-<random token>`.

That is three times in two days, twice by me: a privacy problem gets written up, and the
write-up quotes the data. A leak explained with a real value is a leak documented into
permanence. A sweep of every tracked file across all history for provider receipt hosts
outside the corpus directory found this one and nothing else.

The commit that made this change no longer exists. The history rewrite applied the same
substitution to every commit, so the commit whose only content *was* that substitution
became empty and `--prune-empty` dropped it — correctly, but it takes the reasoning with
it, which is why the reasoning is written down here instead.

### Rule confidence, persisted (2026-08-03)

Schema 5. `ParserRuleEntity` gains a `confidence` column, and it is carried the whole
way: bundled asset → entity → domain model → engine rule.

Before this, `confidence` existed in the rule JSON and in `RuleInducer` and was silently
dropped at the database boundary, so a bundled rule declaring 0.9 and an induced rule
declaring 0.8 both came back reporting 1.0. A confidence figure that only ever rounds up
to certainty is worse than none — it looks like a measurement.

**The migration defaults existing rows to 1.0, which is knowingly wrong for some of
them.** Bundled rules get their real figure back on the next launch, because the seeder
re-asserts it from the asset. An induced rule's 0.8 is not recoverable from a row that
never stored it, so those rows over-state themselves until the rule is taught again. The
alternative — guessing 0.8 for anything marked `isUserCreated` — would be inventing data
during a migration, which is worse than a known-imperfect default that the seeder repairs
where it can.

Four tests: the migration keeps a user-created rule intact and defaults its confidence;
a bundled rule keeps the asset's figure; re-seeding repairs a defaulted one; and the
value reaches the engine rather than being flattened on the way.

`BackupRepository.SCHEMA_VERSION` moves to 5 alongside, per its own doc. The column is
defaulted so a backup written at schema 4 still restores.

---

## What is left (2026-08-03)

The authoritative list. Everything above this line that reads as open has been
superseded by it.

The code is finished. All twelve sessions are built, committed and green: 379 app tests
(386 in direct), 84 parser tests, zero failures, lint clean on both flavors, parser at
100.00% on train and holdout. What remains is verification that needs hardware, one key,
and a corpus that is honestly short of its own targets.

### Blocked on a device

Nothing here is a known defect. Each is a check that has not been made, which is a
different and more uncomfortable thing to write down.

- **The release build has never run on a phone.** R8 is enabled and its effect on the
  notification listener is unverified. Session 12 names this specifically as the check
  to make, and it is the failure that would matter most: the listener disappearing under
  minification does not crash, it just silently stops capturing payments. Everything
  else in this list is smaller than this one item.
- **No permission-flow UI test.** .md names exactly two UI tests worth writing.
  The rule-induction one exists (`RuleBuilderScreenTest`, under Robolectric, runs on
  every build). The permission flow does not, and there is no `androidTest` source set
  at all — it needs the instrumentation runner and a device.
- **Accessibility is unverified.** TalkBack on Today and the keypad, and the 48dp
  touch-target audit, both from the Session 11 brief.
- **Ledger scroll has not been profiled.** The formatter cache and the `remember` fixes
  were found by reading the scroll path, not by measuring it. The number they cost on a
  real low-end device is still unknown, and was never claimed otherwise.
- **The teach-a-rule flow has not been timed by a human**, against the 90-second target,
  and the README's GIF is a placeholder until it is recorded.
- **`PdfExporter` (2.1%) and `AppLockRepository` (0%)** stay effectively untested off
  the device: `PdfDocument` is native and returns a closed document under Robolectric,
  and the Keystore does not exist there at all. Both were verified by hand on the phone.
  Named so the coverage figures are not mistaken for negligence — and so nobody
  "fixes" them with a mock that asserts the mock.

### Blocked on a signing key

- **Both release APKs are unsigned.** `keystore.properties` is absent, and the build
  deliberately registers no signing config rather than falling back to the debug key —
  a debug-signed APK installs, runs and looks exactly like a release, signed with a key
  every Android SDK on earth has the private half of. There is no installable release
  artifact until a real key exists.

### Honest gaps that no amount of building fixes

- **The corpus misses its own targets**: 84 financial samples against 150, 3 senders
  against 4, and **no Amharic transaction templates at all** — `docs/04` calls those
  "the ones that break naive parsers". The 100% accuracy figure should never be quoted
  without the paragraph in CORPUS.md that says what it is worth: a holdout of samples
  rather than templates, from one person's account, across thirteen message shapes.
- **Coverage is 36.1% instruction / 46.1% line**, reported and not gated. The `ui/*`
  packages are the bulk of what is uncovered and that is deliberate — .md says
  not to write UI tests for layout.
- **Three privacy leaks were found in the corpus in a single day**, two of them in
  places an earlier scan of mine had already reported clean. The guards are real now and
  each was verified by making it fail on purpose. But a scan that has been wrong twice
  is worth less than a fresh pair of eyes, and that is the single most valuable thing
  anyone could do to this repository before it goes public.

### Deliberate departures, recorded so they are not rediscovered as bugs

- **`RECEIVE_SMS` removed** against the Session 4 brief, on the author's instruction,
  after four sessions of evidence that nothing used it.
- **SharedPreferences instead of DataStore** for `DemoModeStore` and `LanguagePreference`
  — both are read before any coroutine scope exists.
- **The reconciliation formula excludes `CASH` from `capturedIn`**, where
  `03-ARCHITECTURE.md` §7 does not. Implemented literally, the spec tells a shop it is
  short by exactly the cash it took.
- **`WorkManagerBacklogReprocessor` is left at 0%** on purpose: it is the seam that
  exists so ViewModels can be tested without WorkManager.
