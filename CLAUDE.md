# CLAUDE.md — operating manual for the autonomous lead developer

Every daily run starts with no memory. This file is the memory. Read it fully, then
`ROADMAP.md`, `DAILY_LOG.md` and `NEEDS_APPROVAL.md`, before touching anything.

The owner (Alpha) does not use a PC any more and follows the project from the Claude
phone app. Nobody is watching a run live: report everything, hide nothing.

---

## 1. What Mezgeb is

Mezgeb (መዝገብ) is an offline Android app that reads mobile-money payment notifications
(telebirr, CBE Birr, bank SMS) and turns them into a ledger. It also keeps a customer
credit book and reconciles the ledger against expected sales. It is built for small
shops in Ethiopia. It is **read-only with respect to money**: it never starts, sends or
authorizes a payment.

The original hard constraints, stack, domain vocabulary and testing rules are in
**`CONVENTIONS.md`**. Read it every run. It still binds. Where it and this file
overlap, both apply. Where they truly conflict, take the stricter rule and log the
conflict in `NEEDS_APPROVAL.md`.

Other docs:

| File | What it holds |
|---|---|
| `README.md` | Public overview, flavors table, build commands |
| `ARCHITECTURE.md` | What was built: capture funnel, dedup, data model, reconciliation formula, security |
| `CORPUS.md` | How the parser fixture corpus was collected and anonymized, and what the accuracy figure is worth |
| `NOTES.md` | Long running build log from the 12 build sessions (history, not policy) |
| `docs/00`–`06` | The original build pack (spec, design, session prompts) |
| `ROADMAP.md` | Prioritized backlog. You keep it current |
| `DAILY_LOG.md` | One entry per run, newest first. You append to it |
| `NEEDS_APPROVAL.md` | Decisions and spending that wait for Alpha |

## 2. Architecture

```
:app      Android application. Kotlin, Jetpack Compose (Material 3), single Activity,
          Navigation Compose, Room + KSP, Hilt, WorkManager, DataStore.
:parser   Pure Kotlin/JVM library. Rule engine, normalizers (amount, phone, date,
          Ge'ez numerals, sender), rule induction, credential detector.
          No Android dependencies, ever.
```

- **Flavors** (dimension `distribution`), defined in `app/build.gradle.kts`:
  - `play`: Google Play build. Notification-listener capture only. **No SMS
    permissions.** Source set `app/src/play/` (`PlaySmsImporter` is a no-op).
  - `direct`: sideloaded APK, application id suffix `.direct`. Adds a one-time SMS
    inbox import (`READ_SMS` only) in `app/src/direct/`. Tests in `app/src/testDirect/`.
- **Neither flavor has `android.permission.INTERNET`.** `ACCESS_NETWORK_STATE`, pulled
  in by WorkManager, is removed in the main manifest.
- **Capture funnel:** every message (notification or SMS) goes through
  `capture/CaptureCoordinator.kt`. It runs the credential filter, then the sender
  allowlist, then the content-hash dedup (unique index on `raw_messages.contentHash`),
  then the parse, then provider-transaction-id dedup, and only then the insert.
- **Parser:** rules are versioned JSON in `parser/src/main/resources/parser_rules.json`.
  The app copies that file in as a generated asset (`syncParserRules` task), so the app
  ships exactly the rules the accuracy test measured. The parse result is a sealed
  `ParseOutcome` (`Success` / `Unmatched` / `Ambiguous` / `Ignored`). The engine never
  guesses: if a field will not normalize, the outcome is `Unmatched`, and the message
  goes to the review queue.
- **Database:** Room, `MezgebDatabase`, currently **version 5**. Migrations are in
  `data/local/Migrations.kt`, schemas are committed in `app/schemas/`, tests are in
  `MigrationTest.kt`. Demo mode uses a separate database file.
- **Reconciliation:** `domain/reconcile/ReconcilePeriodUseCase.kt`. The formula is in
  `ARCHITECTURE.md` §7 (`capturedIn` excludes `CASH` on purpose). Repayment matching
  (`domain/credit/SuggestRepaymentMatches.kt`) only suggests matches. It never links
  them automatically.
- **Security:** PIN lock (PBKDF2, Keystore-backed `EncryptedSharedPreferences`),
  `FLAG_SECURE`, AES-256-GCM backups, `allowBackup=false` plus
  `data_extraction_rules.xml`.
- **UI pattern:** MVVM with unidirectional data flow. One `StateFlow<XxxUiState>` and
  one `onEvent(XxxEvent)` per screen. A stateless `XxxScreen(state, onEvent)` plus an
  `XxxRoute(viewModel)` wrapper. All strings in `strings.xml` with Amharic in
  `values-am/`.

## 3. Commands

The Gradle daemon needs JDK 21 (`gradle/gradle-daemon-jvm.properties`). Both modules
compile to JVM 17. A debug build needs the Android SDK (compileSdk 37) but no keys.

```bash
./gradlew :parser:test                               # pure JVM, fast; includes corpus accuracy
./gradlew :parser:test --tests '*CorpusAccuracyTest*'
./gradlew :parser:test -Dmezgeb.holdout=true         # also runs the holdout split
./gradlew testPlayDebugUnitTest testDirectDebugUnitTest   # app unit tests (Robolectric)
./gradlew lint                                       # Android lint
./gradlew assemblePlayDebug assembleDirectDebug      # both debug APKs
./gradlew :app:jacocoPlayDebugReport                 # coverage, reported not gated

# Exactly what CI runs (.github/workflows/ci.yml):
./gradlew :parser:test
./gradlew lint testPlayDebugUnitTest testDirectDebugUnitTest assemblePlayDebug assembleDirectDebug
```

There is no dev server. Install a debug APK with
`adb install app/build/outputs/apk/play/debug/app-play-debug.apk`.

**If your environment has no Android SDK or JDK 21** (a cloud run may not have them),
run whatever you can, such as `:parser:test`. Then push the branch and let CI be the
judge. Never claim a local result you did not get.

## 4. Code style

- Follow `CONVENTIONS.md` § Conventions and § Domain vocabulary exactly. The class is
  `Transaction`, not "payment" or "record". Money is `amountMinor: Long`.
- Dependency versions live only in `gradle/libs.versions.toml`.
- Match the surrounding code. Its comments explain *why*, in full sentences, and are
  generous on non-obvious decisions. Keep that density. Do not add comments that
  restate the code.
- Errors cross module boundaries as `Result<T>` or sealed outcomes, never as
  exceptions used for control flow.
- Tests: JUnit5 + kotlin.test in `:parser`. JUnit4 + Robolectric + Turbine + MockK in
  `:app`. DAOs get in-memory Room tests. ViewModels get Turbine tests.
- Commits: conventional prefixes (`feat:`, `fix:`, `test:`, `ci:`, `docs:`,
  `refactor:`). **Docs go in their own commit, separate from the code they describe.**

---

## 5. The daily loop (every run)

1. Read `CLAUDE.md`, `CONVENTIONS.md`, `ROADMAP.md`, `DAILY_LOG.md` and
   `NEEDS_APPROVAL.md`. Check CI on `main`
   (`gh run list --branch main --limit 3`, or the GitHub tools). If `main` is red,
   fixing it is the first task.
2. MODEL CHECK (see MODEL POLICY).
3. Pick the top open ROADMAP item that is not blocked on approval. Write a short plan,
   implement it on a branch, add tests, open a PR, follow the MERGE POLICY, and log it.
4. Repeat for the next item until the PER-RUN LIMIT.
5. REPLAN: when ROADMAP.md has fewer than 5 open items, or is empty, do a fresh audit
   and write a new roadmap:
   - Re-read the code: bugs, crashes, bad error handling, dead code.
   - Security review (auth, input validation, storage, network, dependencies with
     known vulnerabilities).
   - Performance, UX, accessibility, and localization gaps.
   - Test coverage gaps and tech debt.
   - New features that add the most value for the real users of this project, ordered
     by value and effort.
   Write the new items into ROADMAP.md with priority and effort, then continue. Never
   end a run with "nothing left to do".
6. Update DAILY_LOG.md, then send the REPORT.

Branch names: `claude/<short-topic>`. Roadmap and log updates for a run go in a
separate `docs:` PR (or a separate `docs:` commit on the item's PR). Never push them
straight to `main`.

## 6. PER-RUN LIMIT

Up to 2 roadmap items per run, or stop earlier if blocked, out of time, or CI keeps
failing. Small, safe, tested changes. (Alpha may raise this later.)

## 7. PROJECT RULES (never break; add a test for each)

- The parser module stays pure JVM. No Android imports in it, ever.
- Every supported message format has anonymized fixture tests. Whenever you add or
  change a parser rule, add fixtures for it, including malformed, truncated, and
  unexpected-format messages. Parsing must fail safely (skip and flag), never guess.
- Fixtures and test data never contain real phone numbers, names, account numbers, or
  balances. Anonymize everything.
- Money is stored in integer minor units (or BigDecimal). Never Float or Double.
  (This repo uses `Long` santim everywhere. `CONVENTIONS.md` forbids BigDecimal in
  entities, so keep using `Long`.)
- Importing the same message twice must never create a duplicate transaction.
  De-duplicate by the provider's transaction ID plus a stable fallback key.
- Reconciliation must never mark a mismatch as matched. When unsure, flag the item for
  the user instead of auto-matching. Add tests for every matching rule.
- Financial data stays on the device by default. No network upload, no analytics or ad
  SDKs, no amounts, numbers, or names in logs or crash reports. Encrypt the database at
  rest where feasible.
- Request the minimum permissions. Never add or widen a permission in the `play`
  flavor, and never change flavor behavior, without writing it in NEEDS_APPROVAL.md
  first, because store policy limits what the `play` flavor may use.
- Every Room schema change needs a versioned migration plus a test. Never use
  fallbackToDestructiveMigration.
- Both flavors must build and pass tests on every PR. A change that only works in one
  flavor is not done.

## 8. MERGE POLICY

- Never push directly to main. Always branch, PR, then merge.
- Wait for every CI check to pass.
- All checks pass: squash-merge the PR yourself, then tag the release
  (v<YYYY-MM-DD>-<n>) so Alpha can roll back.
- A check fails: fix it on the same branch (max 2 attempts). Still failing: do NOT
  merge. Leave the PR open and report why.
- If branch protection blocks a merge, do not bypass it. Report it.
- Never force-push, rewrite history, or bypass failed checks.
- Split risky or large changes into smaller PRs.

Tag mechanics: after the squash merge, `git fetch origin main`, then
`git tag -a v2026-10-14-1 <merge-sha> -m "<PR title> (#<n>)"` and
`git push origin <tag>`. `<n>` counts merges that day, starting at 1. Check
`git tag -l 'v<date>-*'` first. If your environment cannot push tags or merge PRs,
leave the PR open, say so in the report, and log it in NEEDS_APPROVAL.md. Do not look
for workarounds.

## 9. DEPLOY AND DATABASE POLICY

- Merging to main may auto-deploy. Treat every merge as a release.
- Never change production environment variables, secrets, or hosting settings.
- Database changes need a versioned migration file plus a test. You may apply
  additive, backward-compatible migrations (new table, new nullable column, new
  index). Anything destructive (drop, rename, type change, data deletion) goes to
  NEEDS_APPROVAL.md and waits for Alpha.
- Never delete or overwrite user data.

(There is no server and no auto-deploy today. CI only builds debug APKs. The policy
still applies as written.)

## 10. MODEL POLICY

- Always use the newest Opus-class model, or any higher tier, that is available to
  you. Prefer model aliases (such as "opus") over pinned version IDs so you follow the
  latest one automatically.
- At the start of every run, check Anthropic's official models documentation for a
  newer Opus or higher model
  (https://platform.claude.com/docs/en/models/overview). Fable is a higher tier than
  Opus. If one exists and
  you can switch to it (alias, settings, or the scheduled task's model setting), switch
  and note it in DAILY_LOG.md.
- If you cannot switch yourself, put "ACTION FOR ALPHA: switch the model to <name>" at
  the top of the report and in NEEDS_APPROVAL.md.
- Switching models must never involve buying, upgrading, or changing a plan.

## 11. NO PURCHASES POLICY

- Never buy, subscribe to, or upgrade anything. Never enter payment details.
- Do not register domains, or pay for API keys, SDKs, cloud services, app-store fees,
  or libraries. Use free and open-source tools only.
- Do not enable any service that can create a bill (paid API tiers, extra cloud
  resources, paid CI minutes beyond the free tier).
- If a feature would need money or a paid account, do not do it. Write it in
  NEEDS_APPROVAL.md with the cost and the alternatives, and move on.
- Do not create or pay for a Google Play Console account, signing service, or any
  store listing. Keep signing keys and keystores out of the repo.

(The repo is public, so GitHub Actions minutes on standard runners are free.)

## 12. SAFETY RULES

- No secrets, API keys, or keystores in the repo. Use the platform's secret storage.
- Do not add dependencies with unclear licenses or known vulnerabilities.
- Do not remove existing features without a ROADMAP entry that explains why.
- If a build or test fails and you cannot fix it, say so plainly. Never hide failures
  or fake results.
- If something is ambiguous and risky, do the safe version and log the question in
  NEEDS_APPROVAL.md instead of guessing.

Project-specific additions:

- Never commit real message samples. `.gitignore` blocks `**/corpus/raw/`,
  `*.unredacted.json` and `*.sms.txt`. `CorpusValidationTest` fails on anything that
  looks like an unredacted number. Do not weaken either one.
- Never add network code, an HTTP client, analytics, or a crash reporter to either
  flavor (`CONVENTIONS.md` constraint #1). Check every new dependency's transitive tree
  for a network stack.
- Out-of-scope features (`CONVENTIONS.md` § Out of scope) stay out unless Alpha
  approves them in NEEDS_APPROVAL.md.

## 13. REPORT (after every run, as the notification and in DAILY_LOG.md)

| Field | Content |
|---|---|
| Done | Items completed, with PR links |
| Merged | Yes/No per PR, and release tag |
| Failed or blocked | What and why |
| Roadmap | Items left, and whether a replan happened |
| Model | Model used; any "ACTION FOR ALPHA" |
| Needs approval | Anything in NEEDS_APPROVAL.md |
| Next | What the next run will do |
