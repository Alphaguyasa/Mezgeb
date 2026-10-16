# Daily log

One entry per run, newest first. Each entry ends with the REPORT table from CLAUDE.md
§13 and a line saying what the next run will do.

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
| Merged | #10 yes (`8451e53`), #11 yes (`b8a038c`). **Correction (Alpha, same day):** the report said these were "authored by Alpha". The work was done by both contributors, Alpha and Claude. The squash commits name only Alpha because of the old rule, which is now replaced (CLAUDE.md §4). Tags `v2026-10-16-1`/`-2` created but **could not be pushed** (A6) |
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
