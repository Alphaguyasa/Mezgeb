# Daily log

One entry per run, newest first. Each entry ends with the REPORT table from CLAUDE.md
§13 and a line saying what the next run will do.

---

## 2026-10-27: Twelfth daily run (scheduled)

**Start.** CI on `main` was green (`3e2cd57`). Model check: the models overview still
lists Opus 5.5 (`claude-opus-5-5`) as the newest Opus. This run used it, so no switch
was needed. Fable 5.1 was ignored, as Alpha decided.

**Environment.** Same as before: JDK 21, no Android SDK. No Gradle build ran
locally. CI judged everything below. A subagent read the failed job's log through
the GitHub tools (the log storage host is still refused by the proxy).

**R27 (backup schema version), PR #46, merged (`451baa7`).**
- `BackupRepository.SCHEMA_VERSION` is now 6, the same as `MezgebDatabase`.
- New tests: the backup version equals the version of the database Room builds from
  the `@Database` annotation, so the two cannot drift apart unnoticed again; a new
  backup reports the schema that wrote it; a file written at schema 5, with
  `possibleDuplicateOfId` stripped from every transaction, restores exactly.
- Backups written between R5 and today say "5" but hold v6 rows. They restore,
  because the one v6 column is nullable with a default. Green on the first CI run.

**R24 (large text on the remaining screens), PR #47, merged (`c576084`).**
- `LargeTextTest` (font scale 1.5 and 2.0) now covers the ledger, the customer list,
  Reports, Settings, onboarding, the review queue, the transaction detail and its
  editor. It also covers seven dialogs: delete transaction, archive customer, remove
  PIN, the closed-period snapshot, add customer, set PIN and restore passphrase.
- **Real bug, found by CI:** in Settings, "Phone language" and "English" filled the
  language row at a large font, and the Amharic chip was laid out **0dp wide**. It
  was invisible, and it is the way back for someone who reads only Amharic. The row is
  now a FlowRow, so the chips wrap.
- **Real bug, found while reading the code:** the transaction detail had never been
  under the accessibility checks. Its "Reconciled" switch and "Always use this
  category" checkbox each had their own click handler, so each was a second control
  with no name ("switch, off"), the same defect R17 fixed in Settings. The rows are
  now the toggles. New `TransactionDetailAccessibilityTest`.
- Changed before CI could catch them, from the R20/R26 measurements: the customer
  list's outstanding total is a flow of amount and "ETB" (as `MoneyText` since R26),
  and the detail's balance row gives the amount the width it needs.
- The signed-amount check now needs two decimals, so a `+251…` phone number on the
  detail is not taken for an amount. Every amount the app shows has two decimals.
- **CI:** 2 runs. The first failed on the Amharic chip (4 tests) and on the phone
  number (1 test), in both flavors. Fix attempt 1 went green.

**Found.** The "Original message" row on the transaction detail does not say whether
it is open. Added as R28.

**Tags.** I created `v2026-10-27-1` (#46) and `v2026-10-27-2` (#47) locally. Pushing
them failed again ("remote end hung up", then HTTP 403) (A6).

**Dependabot.** #12, #14, #15, #22 and #40 are unchanged and still wait on A7. CI now
warns that `actions/checkout@v4`, `setup-java@v4`, `upload-artifact@v4` and
`gradle/actions/setup-gradle@v4` target the deprecated Node.js 20. #12, #14, #15 and
#22 move all four.

**Roadmap.** R27 and R24 are done; R28 is new. 6 items are open, 3 unblocked (R25,
R28, R22). No replan was needed.

### Report

| Field | Content |
|---|---|
| Done | R27: backup files say schema 6, guarded by a test (#46). R24: large-text check on every remaining screen and dialog; the Amharic language chip was invisible at a large font, and two nameless toggles on the transaction detail are fixed (#47) |
| Merged | #46 yes (`451baa7`), #47 yes (`c576084`). Tags `v2026-10-27-1`/`-2` created but **could not be pushed** (A6) |
| Failed or blocked | #47 needed 1 of its 2 fix attempts (real bugs found by the new tests). Tag push (A6). No local Gradle |
| Roadmap | 6 open, 3 unblocked (R25, R28, R22). New: R28 (original message row state). No replan needed |
| Model | Opus 5.5 (`claude-opus-5-5`), the newest Opus. No action for Alpha |
| Needs approval | A1 DB encryption, A2 samples, A3 protect `main`, A6 tag push, A7 Dependabot authorship (#12, #14, #15, #22, #40) |
| Next | R25 (large text in Amharic), then R28 (original message row state) |

---

## 2026-10-26: Eleventh daily run (scheduled, second run that day)

**Start.** CI on `main` was green (`c962faa`). Model check: the models overview still
lists Opus 5.5 (`claude-opus-5-5`) as the newest Opus. This run used it, so no switch
was needed. Fable 5.1 was ignored, as Alpha decided.

**Environment.** Same as before: JDK 21, no Android SDK. No Gradle build ran
locally. CI judged everything below.

**R23 (last backup and a reminder), PR #43, merged (`1ba07b2`).**
- Settings → Backup now says "Last backup: Mon, 12 Oct 2026" or "Last backup: never".
- Today shows a card ("Save a backup") when the ledger has a transaction and there
  has been no backup for 30 days. If there was never a backup, the 30 days count from
  the first run, so a new install is not asked on day one. "Back up now" opens
  Settings; "Not now" hides the card for 30 days, then it comes back.
- The time is stored only when the whole file was written, and never in demo mode
  (a demo backup holds the demo shop, and would hide the reminder for the real one).
- Both times are in DataStore, not Room: no schema change, and a restore cannot bring
  back an old date. No new permission, no flavor change.
- Tests: the reminder rule at every boundary, the Today and Settings ViewModels, and
  the card's buttons in the accessibility check. New strings in English and Amharic;
  a native speaker should check the Amharic. Green on the first CI run.

**R26 (six-figure headline at a large font), PR #44, merged (`a116a3b`).**
- `MoneyText` with a currency now puts the amount and "ETB" in a flow. When they do
  not fit on one line, only "ETB" moves down; the digits stay together. When they fit,
  the line looks the same as before. This also covers the transaction detail, the
  rule builder and the day subtotals.
- `LargeTextTest` now checks Today with 100,000.00 and 1,234,567.89 at font scale 1.5
  and 2.0: no amount wraps and "ETB" is never split. Green on the first CI run. I
  merged `main` (with #43) into the branch first, so CI tested the two together.

**Found.** `BackupRepository.SCHEMA_VERSION` is 5 while the database is at 6. Restores
work today, but the version in new backup files is wrong. Added as R27 (P1).

**Tags.** I created `v2026-10-26-3` (#43) and `v2026-10-26-4` (#44) locally. Pushing
them failed again with HTTP 403 (A6).

**Dependabot.** #12, #14, #15, #22 and #40 are unchanged and still wait on A7.

**Roadmap.** R23 and R26 are done; R27 is new. 7 items are open, 4 unblocked (R27,
R24, R25, R22). No replan was needed.

### Report

| Field | Content |
|---|---|
| Done | R23: last backup date in Settings, and a 30-day backup reminder on Today (#43). R26: a big total no longer breaks at a large font (#44) |
| Merged | #43 yes (`1ba07b2`), #44 yes (`a116a3b`). Tags `v2026-10-26-3`/`-4` created but **could not be pushed** (A6, HTTP 403) |
| Failed or blocked | Nothing failed in CI. Tag push (A6). No local Gradle |
| Roadmap | 7 open, 4 unblocked (R27, R24, R25, R22). New: R27 (backup schema version). No replan needed |
| Model | Opus 5.5 (`claude-opus-5-5`), the newest Opus. No action for Alpha |
| Needs approval | A1 DB encryption, A2 samples, A3 protect `main`, A6 tag push, A7 Dependabot authorship (#12, #14, #15, #22, #40) |
| Next | R27 (backup schema version and a guard test), then R24 (large-text check on the remaining screens) |

---

## 2026-10-26: Tenth daily run (scheduled)

**Start.** CI on `main` was green (`575645e`). There was no run on 2026-10-25. Model
check: the models overview still lists Opus 5.5 (`claude-opus-5-5`) as the newest
Opus. This run used it, so no switch was needed. Fable 5.1 was ignored, as Alpha
decided.

**Environment.** Same as before: JDK 21, no Android SDK, and Google Maven blocked.
No Gradle build ran locally. CI judged everything below. Job logs were readable
through the GitHub tools.

**R18 part 2 (dialogs with text fields), PR #37, merged (`fdd35e4`).**
- `AccessibilityChecks.openDialog` composes the screen with the dialog closed and
  lets it settle. It then opens the dialog on a paused clock, runs 10 frames, and
  reads the dialog window's merged semantics straight from its compose root
  (`ShadowDialog` → `ViewRootForTest.semanticsOwner`). It never waits for idle, so the
  re-measuring floating label can no longer hang the run.
- Checked: add customer, edit customer, set PIN (field and acknowledgement row),
  backup passphrase, restore passphrase. Each test first finds its dialog's title and
  names its fields by their labels.
- No defect found. The probe test is gone. Green on the first CI run, in both flavors.

**R20 (large text), PR #41, merged (`31db17f`).**
- `LargeTextTest` runs Today, cash entry with its keypad, Reconcile and the credit
  statement at font scale 1.5 and 2.0, with native graphics so text is measured with
  a real font. A guard test checks both the scale and the measurement.
- **Real bug, fixed:** Today's "Paid out" and "Net" sat in 136dp half-columns. At 1.5
  the net wrapped, and at 2.0 paid out broke at its comma ("−10,325." over "00"),
  which reads as a different number. They are now a `FlowRow`, and the net moves
  under paid out when the two do not fit.
- Every control stayed at 48dp or more at both scales.
- **CI:** 3 runs (the first push plus both allowed fix attempts). Run 1 found the bug.
  It also showed that `TextLayoutResult.hasVisualOverflow` is set for every amount
  under native graphics, even ones that fit. Fix attempt 1 added the FlowRow, but
  `didOverflowWidth` turned out to be set everywhere too. Fix attempt 2 judges an
  amount by its line count, with soft wrap on and no line cap, and went green.
- Today's headline measured 278dp of 296dp at 2.0, so a six-figure total would wrap.
  That is the new R26.

**Tags.** I created `v2026-10-26-1` (#37) and `v2026-10-26-2` (#41) locally. Pushing
them failed again with HTTP 403 (A6).

**Dependabot.** Dependabot closed #17 and opened #40 in its place (the same group, 26
updates, green). #12, #14, #15, #22 and #40 all wait on A7.

**Replan.** After R18 and R20, only 4 items were open (R10 and R11 blocked, R12
waiting on A7, R22). New items:
- R23 (P1): show the last backup and remind after 30 days without one. Nothing in the
  app records whether a backup was ever made, and the app has no sync.
- R26: six-figure headline totals at a large font.
- R24: the large-text check on the remaining screens.
- R25: the large-text check in Amharic.

### Report

| Field | Content |
|---|---|
| Done | R18 part 2: the 5 dialogs with text fields are checked (#37). R20: large-text check, and Today's figures no longer break at font scale 1.5 or 2.0 (#41) |
| Merged | #37 yes (`fdd35e4`), #41 yes (`31db17f`). Tags `v2026-10-26-1`/`-2` created but **could not be pushed** (A6, HTTP 403) |
| Failed or blocked | #41 took both fix attempts: TextLayoutResult's overflow flags were unreliable under native graphics. Tag push (A6). No local Gradle |
| Roadmap | Replanned: R23 to R26 added. 8 open, 5 unblocked (R23, R26, R24, R25, R22) |
| Model | Opus 5.5 (`claude-opus-5-5`), the newest Opus. No action for Alpha |
| Needs approval | A1 DB encryption, A2 samples, A3 protect `main`, A6 tag push, A7 Dependabot authorship (#12, #14, #15, #22, #40) |
| Next | R23 (last backup and reminder), then R26 (six-figure headline at a large font) |

---

## 2026-10-24: Ninth daily run (scheduled)

**Start.** CI on `main` was green (`3b8e771`). Model check: the models overview still
lists Opus 5.5 (`claude-opus-5-5`) as the newest Opus. This run used it, so no switch
was needed. Fable 5.1 was ignored, as Alpha decided.

**Environment.** Same as before: JDK 21, no Android SDK. No Gradle build ran locally.
CI judged everything below. CI artifacts and full run logs could not be downloaded
(the proxy refuses their storage hosts), so only the last 5,000 lines of a job log
were readable.

**R21 (a note on a period with a gap), PR #38, merged (`564010b`).**
- The close step shows "Note (optional)" while the period has a gap. It is hidden on
  a balanced period.
- On close, the note is trimmed and capped at 200 characters as it is typed. A blank
  note is stored as none. A note is kept only if the period still has a gap, so a note
  typed before the gap closed is never saved out of sight.
- The closed-period snapshot shows "Your note: ...". Its only button now says "Close"
  instead of "Cancel".
- New strings are in English and Amharic. A native speaker should check the Amharic.
- No schema change (the column already existed). Five ViewModel tests and three
  screen tests. Green on the first CI run.

**R18 part 2 (dialogs with text fields), PR #37, open, not merged.**
- Push 1 was the planned probe: one kind of text field per bare dialog, waiting for
  idle. The dialogs that hung kept laying text out until Robolectric's native-object
  registry overflowed. That failed 37 unrelated Compose tests later in the same JVM,
  and the probe's own results were cut from the log.
- Push 2 (fix attempt 1) ran each probe on a paused clock for 60 frames and wrote the
  counts into a deliberate failure. Every other test passed.
- **Answer:** the floating label is the trigger. A `BasicTextField` and an unlabelled
  `OutlinedTextField` settle in a dialog. A labelled one keeps re-measuring (30
  measures after 60 frames), but only in a dialog window, not in the main window.
- I stopped there instead of spending the last attempt on code I cannot compile
  locally. The roadmap names the next approach: read the dialog's semantics straight
  from its compose root, without ever waiting for idle. The PR stays open for it, and
  a comment on it holds the results table.

**Tags.** I created `v2026-10-24-1` (#38) locally. Pushing it failed again (A6).

**Dependabot.** #12, #14, #15, #17 and #22 are unchanged and still wait on A7.

**Roadmap.** R21 is done. 6 items are open, 3 of them unblocked (R18 part 2, R20,
R22). R12 waits on #17. No replan was needed.

### Report

| Field | Content |
|---|---|
| Done | R21: optional note when a period closes with a gap; snapshot button says Close (#38). R18 part 2: probe found the cause (floating label in a dialog) (#37) |
| Merged | #38 yes (`564010b`). Tag `v2026-10-24-1` created but **could not be pushed** (A6). #37 not merged: probe, red on purpose |
| Failed or blocked | #37's first push failed 37 unrelated tests (the hung dialogs exhausted Robolectric's registry); the second push isolated the cause. Dialog checks still not written. Tag push (A6). No local Gradle. CI artifacts not downloadable |
| Roadmap | 6 open (3 unblocked). No replan needed |
| Model | Opus 5.5 (`claude-opus-5-5`), the newest Opus. No action for Alpha |
| Needs approval | A1 DB encryption, A2 samples, A3 protect `main`, A6 tag push, A7 Dependabot authorship (#12, #14, #15, #17, #22) |
| Next | R18 part 2 on PR #37 (read the dialog's semantics without waiting for idle), then R20 (large-text check) |

---

## 2026-10-23: Eighth daily run (scheduled)

**Start.** CI on `main` was green (`d4ba2be`). Model check: the models overview still
lists Opus 5.5 (`claude-opus-5-5`) as the newest Opus. This run used it, so no switch
was needed. Fable 5.1 was ignored, as Alpha decided.

**Environment.** Same as before: JDK 21, no Android SDK. No Gradle build ran locally.
CI judged everything below.

**R18 part 1 (dialog checks), PR #34, merged (`ba42ace`).**
- The first push was a deliberate probe: five tests in one CI cycle. Each one recorded
  the dialog window's state at every step. That push was red, as expected.
- **Answer:** Compose dialogs do work under Robolectric. A bare `AlertDialog` and a
  bare `Dialog` both settled, shown on the first composition or opened later. Only the
  real add-customer dialog hung. Its window was attached and laid out at 328x340, but
  its compose root kept asking for layout for the full 60s. The difference is the two
  `OutlinedTextField`s inside a dialog window.
- Fix attempt 1 replaced the probe with real checks on the three dialogs that have no
  text field: archive customer, remove PIN, and the closed-period snapshot (which also
  reads "Credit repaid" as "less"). All passed in both flavors and found no new bug.
- The dialogs with text fields (add and edit customer, set PIN, passphrases) stay on
  R18 as part 2, and the roadmap names the next probe.

**R19 (spoken chart summary), PR #35, merged (`b2916ba`).**
- The Reports chart is a canvas, so TalkBack said nothing after "Each day". It now
  reads one summary of the same bars, for example "5 days: 3 ahead, 1 behind, 1 even.
  Best day: 880.00 ETB more received than paid out. Worst day: 300.00 ETB more paid
  out than received."
- When no day was ahead (or behind), it names no best (or worst) day.
- New strings are in English and Amharic. A native speaker should check the Amharic.
- Two new tests. Green on the first CI run.

**Small find, logged under R21:** the snapshot dialog's only button says "Cancel",
though nothing can be cancelled there. It should say "Close".

**Tags.** I created `v2026-10-23-1` (#35) and `v2026-10-23-2` (#34) locally. Pushing
them failed again with HTTP 403 (A6).

**Dependabot.** #12, #14, #15, #17 and #22 are unchanged and still wait on A7.

**Roadmap.** R19 and R18 part 1 are done. 7 items are open, 5 of them unblocked
(R18 part 2, R20, R21, R22, and R12, which waits on #17). No replan was needed.

### Report

| Field | Content |
|---|---|
| Done | R18 part 1: dialogs can be tested now; archive, remove-PIN and snapshot dialogs checked (#34). R19: spoken summary for the Reports chart (#35) |
| Merged | #35 yes (`b2916ba`), #34 yes (`ba42ace`). Tags `v2026-10-23-1`/`-2` created but **could not be pushed** (A6, HTTP 403) |
| Failed or blocked | #34's first push was a deliberate red probe; fix attempt 1 went green. Dialogs with text fields still hang (R18 part 2). Tag push (A6). No local Gradle |
| Roadmap | 7 open (5 unblocked). No replan needed |
| Model | Opus 5.5 (`claude-opus-5-5`), the newest Opus. No action for Alpha |
| Needs approval | A1 DB encryption, A2 samples, A3 protect `main`, A6 tag push, A7 Dependabot authorship (#12, #14, #15, #17, #22) |
| Next | R18 part 2 (text-field probe), then R20 (large-text check) |

---

## 2026-10-22: Seventh daily run (scheduled)

**Start.** CI on `main` was green (`ae8cc86`). Model check: the models overview still
lists Opus 5.5 (`claude-opus-5-5`) as the newest Opus. This run used it, so no switch
was needed. Fable 5.1 was ignored, as Alpha decided.

**Environment.** Same as before: JDK 21, no Android SDK, and Google Maven blocked.
No Gradle build ran locally. CI judged everything below.

**R17 part 2 (accessibility checks), PR #31, merged (`1e79bec`).**
- The shared checks now cover Reconcile, Reports, Settings and Onboarding.
- **Real bugs, fixed:**
  - Reconcile read "Credit repaid" as "−50.00 ETB". Each breakdown line is now one
    sentence, and a subtracted figure says "less".
  - Reports read a negative net as a symbol. It now uses Today's sentence.
    `spokenNet` moved to `ui/common` so both screens share it.
  - The Reports totals said "Received today" over a week or a month. They now say
    "Received" and "Paid out".
  - The three Settings switches had no name, so TalkBack said "switch, on". Each row
    is now the toggle. The PIN acknowledgement checkbox had the same bug and got the
    same fix (not tested, because it is in a dialog: R18).
  - Three chip rows were squeezed on a 360dp phone (Reports period, Reconcile
    period, lock timeout). They now scroll like the ledger's filter bar.
- Onboarding passed with no change.
- New strings are in English and Amharic. A native speaker should check the Amharic.
- **CI:** the first run failed on one assertion in my own test. It expected the
  Export button's name as a content description, but the button is named by its
  text, which the control check already covered. Fix attempt 1 removed that line,
  and CI went green. Every other new check passed the first time in both flavors.

**R15 (test compiler warnings), PR #32, merged (`c56bf67`).**
- I took this instead of R18, because R18 cannot be investigated without a local
  Robolectric run (see below).
- The 42 warnings were taken from the CI log on `main`. The fixes: the 13 ViewModel
  tests opt in to `ExperimentalCoroutinesApi`, `MigrationTest`'s `onUpgrade` uses
  Room's parameter names, and `DatabaseSeederTest` shares one `Json`.
- CI's log for the PR shows 0 Kotlin warnings. Green on the first run.

**R18 skipped.** A dialog under Robolectric hangs for 60s before failing. Without a
local run, every guess costs a full CI cycle and counts against the 2-attempt limit.
The roadmap now names the first probe to try.

**Tags.** I created `v2026-10-22-1` (#32) and `v2026-10-22-2` (#31) locally. Pushing
them failed again (A6).

**Dependabot.** #12, #14, #15, #17 and #22 are unchanged and still wait on A7.

**Roadmap: replanned.** R15 and R17 are done, which left 4 open items. I audited and
added four new items. R19: the daily-net chart is silent to TalkBack. R20: no screen
is tested at a large font scale. R21: the `note` column on a reconciliation period
is never written, so a gap cannot be explained. R22: closed periods cannot be
exported. 8 items are open now, 6 of them unblocked (R18, R19, R20, R21, R22, and
R12, which waits on #17).

### Report

| Field | Content |
|---|---|
| Done | R17 part 2 accessibility checks, with five fixes on Reconcile, Reports and Settings (#31). R15 test compiler warnings, 42 to 0 (#32) |
| Merged | #31 yes (`1e79bec`), #32 yes (`c56bf67`). Tags `v2026-10-22-1`/`-2` created but **could not be pushed** (A6) |
| Failed or blocked | #31 needed one fix push (a wrong assertion in my test). R18 skipped: no local Robolectric. Tag push (A6). No local Gradle |
| Roadmap | Replanned: R19 to R22 added. 8 open (6 unblocked) |
| Model | Opus 5.5 (`claude-opus-5-5`), the newest Opus. No action for Alpha |
| Needs approval | A1 DB encryption, A2 samples, A3 protect `main`, A6 tag push, A7 Dependabot authorship (#12, #14, #15, #17, #22) |
| Next | R18 (dialog probe), then R19 (spoken summary for the chart) |

---

## 2026-10-21: Sixth daily run (scheduled)

**Start.** CI on `main` was green (`6d7ca8b`). Model check: the models overview still
lists Opus 5.5 (`claude-opus-5-5`) as the newest Opus. This run used it, so no switch
was needed. Fable 5.1 was ignored, as Alpha decided.

**Environment.** Same as before: JDK 21, no Android SDK, and Google Maven blocked
(403). No Gradle build ran locally. CI judged everything below.

**R14 (possible-duplicates ledger filter), PR #29, merged (`0f75778`).**
- A "Possible duplicates" chip in the ledger filter bar shows only the rows the
  lookalike check flagged.
- The ledger query, its list twin and the day subtotals take one more nullable
  parameter. A header over filtered rows sums only those rows.
- The chip is on or off. Turning it off shows every row again.
- A dismissed flag is cleared, so that row leaves the view. Deleted rows never show.
- Nothing is deleted, merged or matched automatically. No schema change.
- Tests: DAO, day totals, ViewModel, and the chip itself. Green on the first CI run.

**R17 part 1 (accessibility checks), PR #28, merged (`a5b538c`).**
- The three checks from R8 now live in a shared `AccessibilityChecks` helper. New
  tests cover the ledger, the customer list and the credit statement.
- **Real bugs, fixed.** A statement line showed `+500.00` / `−50.00` as plain text,
  so TalkBack read "plus 500" / "minus 50". The running balance and a customer the
  shop owes (`−20.00 ETB`) had the same problem. They are now spoken as words, for
  example "Balance after: you owe them 20.00 ETB". The screen looks the same. New
  strings are in English and Amharic. A native speaker should check the Amharic.
- **Not covered: the add-customer dialog.** With it open, Compose under Robolectric
  never went idle (a 60s timeout in `setContent`, both flavors). Fix attempt 1, a
  hand-driven clock, did not help. Fix attempt 2 removed that one new test, which
  had never passed. Everything else stayed. The PR comment explains this, and R18
  tracks the dialog.
- Green on the second fix push.

**Tags.** I created `v2026-10-21-1` (#29) and `v2026-10-21-2` (#28) locally. Pushing
them failed again, now with an explicit HTTP 403 (A6).

**Dependabot.** #12, #14, #15, #17 and #22 are unchanged and still wait on A7.

**Roadmap.** R14 is done. R17 part 1 is done, and part 2 (reconcile, reports,
settings, onboarding) stays at the top. Added R18 (the dialog check). 6 items are
open, 4 of them unblocked (R17, R18, R12, R15). No replan was needed.

### Report

| Field | Content |
|---|---|
| Done | R14 possible-duplicates filter (#29). R17 part 1 accessibility checks, with TalkBack fixes on the credit statement and customer list (#28) |
| Merged | #29 yes (`0f75778`), #28 yes (`a5b538c`). Tags `v2026-10-21-1`/`-2` created but **could not be pushed** (A6, HTTP 403) |
| Failed or blocked | The add-customer dialog test cannot run under Robolectric yet (now R18). Tag push (A6). No local Gradle |
| Roadmap | 6 open (4 unblocked). No replan needed. Added R18 |
| Model | Opus 5.5 (`claude-opus-5-5`), the newest Opus. No action for Alpha |
| Needs approval | A1 DB encryption, A2 samples, A3 protect `main`, A6 tag push, A7 Dependabot authorship (#12, #14, #15, #17, #22) |
| Next | R17 part 2 (reconcile, reports, settings, onboarding), then R18 (dialog check) |

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
