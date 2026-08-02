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
- **Rule `confidence` is not persisted.** `ParserRuleEntity` has no column for it, so
  every rule reports 1.0 in the app even where the bundled JSON says otherwise. The
  accuracy harness reads the JSON directly and is unaffected. Needs a schema bump;
  Session 6 is already opening that file.
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

### Outstanding device verification

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
