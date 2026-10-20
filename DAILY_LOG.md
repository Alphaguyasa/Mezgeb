# Daily log

One entry per run, newest first. Each entry ends with the REPORT table from CLAUDE.md
§13 and a line saying what the next run will do.

---

## 2026-10-20: Fifth daily run (scheduled)

**Start.** CI on `main` was green (`f6fd503`). Model check: the models overview still
lists Opus 5.5 (`claude-opus-5-5`) as the newest Opus. This run used it, so no switch
was needed. Fable 5.1 was ignored, as Alpha decided.

**Environment.** Same as before: JDK 21, no Android SDK, and Google Maven blocked
(403). No Gradle build ran locally. CI judged everything below.

**R8 (accessibility semantics tests), PR #24, merged (`58566c8`).**
- The roadmap said to finish this PR first. It had used its two fix attempts
  yesterday. This run made one more push, and CI went green on it.
- Fix for the FAB failure: the test was right. Material 3's extended FAB hides its
  label from TalkBack and names the button from its icon, and our icon had no
  description. So TalkBack read "Record cash sale" as just "button". **Real bug, now
  fixed.** The Customers screen had the same bug ("Add customer"), fixed in the same
  push.
- Fix for the signed-amount failure: the check now reads the merged tree, which is
  what TalkBack reads.
- Also: the main branch was merged into the PR first, and a lint naming warning in
  the test was fixed.

**R16 (refused permission feedback), PR #26, merged (`0921345`).**
- In the `direct` build, refusing `READ_SMS` now shows "no past payments were added",
  with an "Open app settings" button. After "don't ask again", that button is the
  only way to allow it.
- The message clears after a successful retry, or when the user comes back from
  settings with the permission granted. Coming back does not start the import by
  itself.
- A dialog that was backed out of is not a refusal, so it shows nothing.
- `play` is unchanged: it has no import card and asks for nothing. No permission was
  added.
- New strings are in English and Amharic. A native speaker should check the Amharic.
- Four new route tests and two ViewModel tests. Green on the first run, and again
  after `main` (with #24) was merged in.

**Tags.** I created `v2026-10-20-1` (#24) and `v2026-10-20-2` (#26) locally. Pushing
them failed again (A6).

**Dependabot.** #12, #14, #15, #17 and #22 are unchanged and still wait on A7.

**Roadmap.** R8 and R16 are done. Added R17 (the same accessibility checks on the
other screens), because the Customers bug sat outside the screens R8 tests. 6 items
are open, 4 of them unblocked (R17, R14, R12, R15). No replan was needed.

### Report

| Field | Content |
|---|---|
| Done | R8 accessibility tests, with two TalkBack fixes (#24). R16 refused-permission message (#26) |
| Merged | #24 yes (`58566c8`), #26 yes (`0921345`). Tags `v2026-10-20-1`/`-2` created but **could not be pushed** (A6) |
| Failed or blocked | Tag push (A6). No local Gradle |
| Roadmap | 6 open (4 unblocked). No replan needed. Added R17 |
| Model | Opus 5.5 (`claude-opus-5-5`), the newest Opus. No action for Alpha |
| Needs approval | A1 DB encryption, A2 samples, A3 protect `main`, A6 tag push, A7 Dependabot authorship (#12, #14, #15, #17, #22) |
| Next | R17 (accessibility checks on the other screens), then R14 (possible-duplicates ledger filter) |

---

## 2026-10-19: Fourth daily run (scheduled)

**Start.** CI on `main` was green (`239c451`). Model check: the models overview still
lists Opus 5.5 (`claude-opus-5-5`) as the newest Opus. This run used it. No switch was
needed. Fable 5.1 was ignored, as Alpha decided.

**Environment.** Same as before: JDK 21, no Android SDK, and Google Maven blocked
(403; `maven.google.com` redirects to the blocked host). No Gradle build ran locally.
CI judged everything below. CI artifacts (blob storage) cannot be downloaded from
here either.

**R7 (permission-flow test), PR #23, merged (`9202766`).**
- `OnboardingPermissionFlowTest` drives the real `OnboardingRoute`. A recording
  `ActivityResultRegistry` stands in for the system permission dialog. The test
  checks what is requested, and that granted starts the import. Read refused,
  dismissed, or only the notification permission refused each behave correctly.
  It also checks that pre-33 devices skip `POST_NOTIFICATIONS`, that the access
  button opens settings, and that access granted there shows on resume.
- `testPlay` (new source set): the play importer asks for nothing.
  `testDirect`: the direct importer asks for `READ_SMS` only, and it is declared in
  the manifest.
- Green on the first CI run. Lint still says "No issues found".
- **Found, not fixed:** a refused `READ_SMS` gives the user no feedback. Logged as
  R16.

**R8 (accessibility semantics tests), PR #24, open and red. Not merged.**
- The keypad and review-queue checks pass.
- Real fixes on the branch: Today's net total now has a spoken description (it was
  read as "+2,125.00 ETB"), in English and Amharic. Today's three icon buttons are
  48dp, like `BackButton`.
- Attempt 1 fixed a wrong import. Attempt 2 also made CI print full test failures.
  What is left, both on Today: the signed-amount check reads the unmerged tree, which
  is a mistake in the test itself; and the "record cash sale" button has no readable
  name in the test. Both are explained on the PR. I stopped at the two-attempt limit.

**Tags.** I created `v2026-10-19-1` (#23) locally. Pushing it failed again (A6).

**Dependabot.** #22 (setup-java 4→6) is new and green. #12, #14, #15, #17 and #22
all wait on A7.

**Roadmap.** R7 is done. R8 is in progress. Added R16. 7 items are open, 5 of them
unblocked (R8, R16, R14, R12, R15), so no replan was needed.

### Report

| Field | Content |
|---|---|
| Done | R7 permission-flow test (#23). R8 accessibility tests written (#24), not finished |
| Merged | #23 yes (`9202766`). Tag `v2026-10-19-1` created but **could not be pushed** (A6). #24 **not merged** (red after 2 attempts) |
| Failed or blocked | #24: two Today checks still fail (details on the PR). Tag push (A6). No local Gradle |
| Roadmap | 7 open (5 unblocked). No replan needed. Added R16 |
| Model | Opus 5.5 (`claude-opus-5-5`), the newest Opus. No action for Alpha |
| Needs approval | A1 DB encryption, A2 samples, A3 protect `main`, A6 tag push, A7 Dependabot authorship (#12, #14, #15, #17, #22) |
| Next | Finish #24 (merged-tree check, FAB name), then R16 |

---

## 2026-10-18: Third daily run (scheduled)

**Start.** CI on `main` was green (`eb59502`). There is no log entry for 2026-10-17,
and no commit or PR from that day, so it looks like no run happened then. Model check:
the models overview still lists Opus 5.5 (`claude-opus-5-5`) as the newest Opus. This
run used it. No switch was needed. Fable 5.1 was ignored, as Alpha decided.

**Authorship.** PR #18 ("credit both contributors") was closed without merging on
2026-10-16. `CLAUDE.md` on `main` still says Alpha is the only author, so this run
followed that rule. All commits show Alpha Guyasa, with no Co-Authored-By trailer.

**Environment.** Same as before: JDK 21, no Android SDK, and Google Maven blocked
(403). No Gradle build ran locally. CI judged everything below.

**R9 (coverage in CI), PR #20, merged (`9b45d42`).**
- CI runs `:app:jacocoPlayDebugReport` and uploads it as the `coverage-play-debug`
  artifact (1.4 MB on the first run).
- A guard test checks that CI both runs and uploads it.
- Green on the first CI run.

**R6 (lint triage), PR #19, merged (`bfd8b6b`).**
- Lint went from **42 warnings to "No issues found"**.
- **One warning was a real bug.** The weekly reconcile reminder never appeared on
  Android 8 to 12. `POST_NOTIFICATIONS` exists only from API 33, so below that the
  check always said "denied". The check now runs only on API 33 and up. A new test
  simulates API 32. The August notes had filed this warning as "correctly guarded".
- Lint's newer-version checks (24 warnings) are off, because Dependabot covers them.
- Nine unused placeholder strings were removed (English and Amharic).
- Ten KTX suggestions were applied, with `apply()` semantics kept.
- One redundant label was removed, and one attribute warning was suppressed with a
  reason.
- One compiler warning was fixed.
- CI now prints the lint text report in every log.
- First attempt: `lint { textOutput = file("stdout") }` is deprecated in AGP 9 and
  only wrote a file named `app/stdout`. I replaced it with a CI step that prints the
  generated report. The build was green each time; the first try just did not give
  me the report.

**Tags.** I created `v2026-10-18-1` (#20) and `v2026-10-18-2` (#19) locally. Pushing
them failed again with "remote end hung up" (A6).

**Dependabot.** #12 to #15 and #17 are all green, and all wait on A7.

**Roadmap.** R6 and R9 are done. Added R15 (compiler warnings in test sources). R12
now notes the new KSP versioning in #17. 7 items are open, 5 of them unblocked (R7,
R8, R14, R12, R15), so no replan was needed.

### Report

| Field | Content |
|---|---|
| Done | R6 lint triage plus the reminder fix (#19), R9 coverage report in CI (#20) |
| Merged | #20 yes (`9b45d42`), #19 yes (`bfd8b6b`). Tags `v2026-10-18-1`/`-2` created but **could not be pushed** (A6) |
| Failed or blocked | Tag push (A6). No local Gradle (no Android SDK, Google Maven blocked) |
| Roadmap | 7 open (5 unblocked, R10/R11 blocked). No replan needed. Added R15 |
| Model | Opus 5.5 (`claude-opus-5-5`), the newest Opus. No action for Alpha |
| Needs approval | A1 DB encryption, A2 samples, A3 protect `main`, A6 tag push, A7 Dependabot authorship (#12–#15, #17) |
| Next | R7 (permission-flow test), then R8 (accessibility semantics tests) |

---

## 2026-10-16: Second daily run (scheduled)

**Start.** CI on `main` was green. Model check: the models overview still lists Opus
5.5 (`claude-opus-5-5`) as the newest Opus. This run used it. No switch was needed.
Fable 5.1 was ignored, as Alpha decided.

**Environment.** The setup matched yesterday's: JDK 21, no Android SDK, and Google
Maven blocked, so no Gradle build ran locally. CI judged everything below. No local
result is claimed.

**R4 (P1, Dependabot), PR #10, merged.**
- `.github/dependabot.yml` runs weekly (Monday 04:00 UTC) for `gradle` and
  `github-actions`.
- Minor and patch updates come grouped. Majors come one per PR.
- Kotlin and KSP share a group, because KSP's version must track Kotlin exactly.
- AGP gets its own PR. The Robolectric android-all jar stays pinned by hand.
- A guard test in `ProjectRulesTest` checks the config.
- Green on the first CI run.
- Dependabot squash commits name Dependabot as the author, which collides with the
  authorship rule. I asked Alpha in A7. Until then, the run will review those PRs but
  not merge them.
- Dependabot opened its first four PRs right after the merge, all major GitHub Actions
  bumps (#12 checkout 4→7, #13 setup-java 4→5, #14 gradle/actions 4→6,
  #15 upload-artifact 4→7). They stay open, waiting on A7.

**R13 (P1, reconciliation names possible duplicates), PR #11, merged.**
- Flagged lookalikes still count in `capturedIn`. The ranked gap explanations now
  include "N payments may have been recorded twice" with their total, and tapping it
  opens the ledger.
- Only flagged, non-deleted money in through a captured channel (not cash) is counted.
  Nothing is ever subtracted.
- Added a DAO test and two use-case tests. Strings are in English and Amharic.
- **One slip, caught before merge:** the branch was first cut from a stale local
  `origin/main` (`d9d955a`, from before R5). I merged the current `main` into it (no
  history rewrite) and pushed. CI was green on that head.

**Tags.** I created `v2026-10-16-1` (#10, `8451e53`) and `v2026-10-16-2` (#11,
`b8a038c`) locally. Pushing them failed again with "remote end hung up", the same
problem as A6. I did not look for a workaround.

**Roadmap.** R4 and R13 are done. 8 items are open, 6 of them unblocked (R6, R7, R8,
R9, R14, R12), so no replan was needed.

### Report

| Field | Content |
|---|---|
| Done | R4 Dependabot (#10), R13 reconciliation names possible duplicates (#11) |
| Merged | #10 yes (`8451e53`), #11 yes (`b8a038c`). Tags `v2026-10-16-1`/`-2` created but **could not be pushed** (A6) |
| Failed or blocked | Tag push (A6). No local Gradle (no Android SDK, Google Maven blocked) |
| Roadmap | 8 open (6 unblocked, R10/R11 blocked). No replan needed |
| Model | Opus 5.5 (`claude-opus-5-5`), the newest Opus. No action for Alpha |
| Needs approval | A1 DB encryption, A2 samples, A3 protect `main`, A6 tag push, **A7 Dependabot authorship (new)** |
| Next | Review Dependabot PRs #12–#15 (merge only after A7). Then R6 (triage lint warnings) and R7 (permission-flow test) |

---

## 2026-10-15: First daily run (scheduled)

**Start.** CI on `main` was green. Model check: the models overview lists Opus 5.5
(`claude-opus-5-5`) as the newest Opus, and that is what this run used. No switch
needed. Fable 5.1 is listed above it and was ignored, as Alpha decided.

**Environment.** This cloud run had JDK 21 but no Android SDK. `dl.google.com` (and so
Google Maven) was blocked, so no Gradle build could run locally, not even
`:parser:test`. CI was the judge for everything below. No local result is claimed.

**R5 (P0, fallback dedup key), PR #6, merged.** A payment with no provider reference was
deduplicated only by the content hash, so one payment worded differently by the
notification and the inbox became two transactions. There is now a third tier: same
channel, direction, amount and balance within ±60 s, where at most one side has a
reference. A match is **flagged** in `transactions.possibleDuplicateOfId`, never dropped.
- The ledger shows "possible duplicate". The detail screen explains it and offers
  "Not a duplicate". Strings are in English and Amharic.
- Schema v6 adds one nullable column, with migration 5→6 and a test.
- New tests: a DAO test for each lookalike condition, coordinator tests, a ViewModel
  dismiss test and the migration test.
- Room's `6.json` could not be generated locally, so a new CI step fails when
  `app/schemas` is out of date and prints the diff. Its first run printed the file.
  I committed it byte for byte, after checking that the blob hash `09247e5` matched.
  That was one of the two allowed fix attempts. The second CI run was green.

**R3 (P1, policy guard tests), PR #7, merged.**
- `ProjectRulesTest` checks these rules over the source tree: no Android import in
  `:parser`, no destructive-migration call, no log or print calls in shipped code, no
  Float or Double money, no INTERNET or SMS permissions in the wrong manifests, and a
  committed schema JSON for every DB version.
- `MergedManifestPermissionTest` reads the merged permissions for each flavor: no
  network permission in either, READ_SMS only in `direct`.
- Green on the first CI run.

**Tags.** Created `v2026-10-15-1` (#7, `d79cba9`) and `v2026-10-15-2` (#6, `82f4758`)
locally, but **pushing them failed** ("remote end hung up"), while branch pushes worked.
I did not look for a workaround. See NEEDS_APPROVAL A6.

**Roadmap.** R3 and R5 are done. Added R13 (reconciliation names possible duplicates,
P1) and R14 (ledger filter for flagged rows). 9 items are open, 7 of them unblocked,
so no replan was needed.

### Report

| Field | Content |
|---|---|
| Done | R5 fallback dedup flag (#6), R3 policy guard tests (#7) |
| Merged | #7 yes (`d79cba9`), #6 yes (`82f4758`). Tags `v2026-10-15-1`/`-2` created but **could not be pushed** |
| Failed or blocked | Tag push refused by the environment (A6). No local Gradle (no Android SDK, Google Maven blocked) |
| Roadmap | 9 open (7 unblocked, R10/R11 blocked). No replan needed. Added R13, R14 |
| Model | Opus 5.5 (`claude-opus-5-5`), the newest Opus. No action for Alpha |
| Needs approval | A1 DB encryption, A2 samples, A3 protect `main`, A6 tag push (new, optional) |
| Next | R4 (Dependabot), then R13 (reconciliation shows possible duplicates) |

---

## 2026-10-14: Setup run (interactive, on Alpha's PC)

**Phase 0 (setup).** Read the whole codebase and docs, then wrote CLAUDE.md,
ROADMAP.md, this log and NEEDS_APPROVAL.md. Added `.github/workflows/ci.yml`. Two
problems that were already there kept CI red, and both were fixed:
- `gradlew` had been committed without its executable bit, so Linux could not run it.
- `DemoModeControllerTest` raced `MezgebApplication.onCreate`. The app's startup
  privacy purge queried `mezgeb.db` while the test was deleting that file. It only
  failed on the faster Linux runner. The test now runs under a plain `Application`.

**R1 (dedup).** The provider-reference lookup matched across all channels and skipped
deleted rows. A bank payment sharing a telebirr reference was silently dropped, and a
deleted payment came back when its second copy arrived. The lookup is now scoped to
the channel and includes deleted rows.

**R2 (parser fail-safety).** A new test truncates every train sample at every
position, which gives 52,968 variants. **1,023 of them parsed to wrong values.**
Examples: a cut balance "ETB22.9" read as 22.90, a cut top-up "ETB2" read as 2 birr,
and partial telebirr references that would also have broken dedup. The engine now
treats a captured value that runs into the end of the message as possibly cut off:
- a cut amount sends the message to the review queue;
- a cut optional field is dropped.

The result is 0 violations, with train accuracy still 100.00%. Ten hand-written
fixtures cover garbled, foreign-currency, unknown-template and wrong-sender messages.
A few synthetic app-test messages that ended exactly on the amount were given a
closing word. No real corpus message ends on a captured value.

**Model check.** platform.claude.com/docs/en/models/overview lists Claude Fable 5.1
above Opus 5.5. This setup session ran on Opus 5.5.

**Phase 2 (daily routine) is done.** It was created after Alpha approved it:
"Mezgeb daily lead developer", `0 5 * * *` UTC (08:00 Nairobi), model
`claude-opus-5-5`, repo `Alphaguyasa/Mezgeb`. All account connectors were detached
because the routine needs only the repo. First run: 2026-10-15. Alpha decided on
Opus only, never Fable, and CLAUDE.md §10 now says so.

### Report

| Field | Content |
|---|---|
| Done | Setup and CI (#1), R1 dedup scoping (#2), R2 truncation safety (#3) |
| Merged | #1 → `v2026-10-14-1`, #2 → `v2026-10-14-2`, #3 → `v2026-10-14-3` |
| Failed or blocked | Nothing open. Routine creation was blocked once, then approved by Alpha and created |
| Roadmap | 10 open (R3 to R12), no replan needed. R5 (fallback dedup key, P0) is now top |
| Model | Opus 5.5 (`claude-opus-5-5`). Opus only, by Alpha's decision |
| Needs approval | A1 database encryption, A2 more message samples, A3 protect `main` |
| Next | R5 (fallback dedup key: flag possible duplicates, additive nullable column + migration test), then R3 (policy guard tests) |
