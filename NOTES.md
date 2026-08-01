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
