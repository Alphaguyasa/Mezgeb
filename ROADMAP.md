# Roadmap

The backlog is in priority order. Take the top open item that is not blocked. When an
item is finished, move it to **Done** with its PR link. Replan when fewer than 5 items
are open (see CLAUDE.md §5).

Priority: **P0** is correctness, data loss or privacy. **P1** is safety nets and
security. **P2** is quality, UX and debt. **P3** is features.
Effort: **S** is under half a day, **M** is about a day, **L** needs splitting into
several PRs.

Built from the audit on 2026-10-14: the full code read plus the open items in
`NOTES.md` § "What is left". Replanned on 2026-10-22, when the open list fell below 5:
R19 to R22 were added. Replanned again on 2026-10-26: R23 to R26 were added. R27 was found on the second
2026-10-26 run. R28 was found on 2026-10-27. Replanned on 2026-10-28, when the open
list fell to 4: R29 to R37 come from a fresh code audit. R29 and R30 were checked by
hand against the code; the others carry the audit's file and line evidence and are
re-checked when picked up. R29 and R30 were done on the second 2026-10-28 run. R31 and R32 were done on
2026-10-29.

## Open

### R33 · P1 · S: CSV export allows formula injection
`CsvExporter.escape()` only quotes. A cell that starts with `=`, `+`, `-` or `@` (a
name or note from a message, or typed by the owner; a `+251…` phone) is run as a
formula by Excel or Sheets. Fix: prefix such cells with `'`, before or with R22.
Tests: `=HYPERLINK(…)` and `+2519…` come back prefixed; plain text is unchanged.

### R22 · P3 · M: Export closed reconciliation periods
The CSV export covers the ledger and the credit book, but not the closed periods,
which are what an accountant or a partner asks for. Add "Periods (CSV)" to the
Reports export menu, using the snapshotted columns as stored (never recomputed), plus
the note from R21 (done: `reconciliation_sessions.note`, at most 200 characters, and
it may hold commas, quotes and line breaks). Goes through the same system file
picker, so no permission is needed. Tests for the CSV writer, including commas,
quotes and a line break in a note. Do after R33.

### R34 · P2 · S: "Lock immediately" locks on rotation and on a language change
With the 0-second timeout, `MainActivity.onStop()`/`onStart()` do not check
`isChangingConfigurations`, so turning the phone or switching the language (which
recreates the activity) asks for the PIN again. Test: with a 0 timeout, a Robolectric
`recreate()` leaves the app unlocked.

### R35 · P2 · S: Today does not roll over at midnight
`TodayViewModel` fixes the day's bounds when it refreshes, and it refreshes only on
resume. A shop that keeps the app open past midnight sees yesterday's totals. Fix:
also refresh at the next local midnight. Test: advance the injected clock past
midnight; a transaction after midnight counts, yesterday's do not.

### R36 · P2 · S: The backup header is not authenticated
`BackupCrypto` never calls `updateAAD`, so the format and schema version in the
header can be changed without breaking decryption. Fix: authenticate the header as
AAD in a format version 2, and keep reading version 1. Test: a v2 file with one
schema byte flipped fails as `WRONG_PASSPHRASE_OR_CORRUPT`; a v1 file still restores.

### R37 · P3 · M: Check the balance chain for missed messages
`balanceAfterMinor` is stored but only shown. Per channel, the previous balance plus
or minus the amount should equal the next balance; a break means a payment was never
captured (listener killed, notification lost). Show it as a reconcile explanation
("CBE Birr balance moved by X between 10:02 and 14:30 with nothing recorded"), never
corrected automatically, and allow for fees. Tests: a continuous chain gives nothing;
a chain with a gap gives one explanation with the exact difference in santim.

### R10 · P1 · L: Encrypt the database at rest
**Blocked: see NEEDS_APPROVAL.md A1.** This needs a new native dependency
(SQLCipher), a one-time migration of the plaintext database, and a decision on
deleting the plaintext file afterwards.

### R11 · P2 · M: Corpus coverage (Amharic templates, a 4th sender)
**Blocked: see NEEDS_APPROVAL.md A2.** This needs real anonymized samples from a
phone, which a cloud run cannot collect.

### R12 · P2 · S: Revisit `android.disallowKotlinSourceSets=false`
This is an AGP 9 opt-out kept because KSP registered its sources the old way. Check
whether the current KSP supports built-in Kotlin, and remove the flag if it does.
Note (2026-10-18): Dependabot PR #17 moves KSP to `2.3.12`, which is no longer
versioned as `<kotlin>-<ksp>`. The comment in `libs.versions.toml` and the
`kotlin-and-ksp` group reasoning assume the old scheme. Do this item together with
#17 once A7 is answered. (2026-10-26: #17 is superseded by #40, the same group with
26 updates, Gradle 9.8.2 and Compose BOM 2026.09.00. CI is green on it.)

## Done

- 2026-10-29 · R32: the PIN lockout and the auto-lock are timed on uptime
  (`SystemClock.elapsedRealtime`, new `DeviceUptime`), not the wall clock. A lockout
  stores the boot it started in. After a restart the full wait starts again. A lockout
  read as over is forgotten. A lockout stored by an older version as a wall-clock end
  is converted on first read (capped at 15 minutes). Arithmetic in the pure
  `LockoutTimer`. PR #56. (Tag `v2026-10-29-2` not pushed: A6.)
- 2026-10-29 · R31: Reconcile opens one period at a time. A double tap on "Start
  checking" is ignored, and the button is disabled while busy.
  `ReconciliationSessionDao.insertIfNoneOpen` checks and inserts in one Room
  transaction. Open, save expected and close catch a failed write, show "Could not
  save. Try again.", and keep what was typed. PR #55. (Tag `v2026-10-29-1` not
  pushed: A6.)
- 2026-10-28 · R30: one raw message becomes at most one Transaction.
  `resolveManually` refuses a message that is no longer UNMATCHED, `record()` refuses
  a message that already has a Transaction (and takes it out of the review queue),
  and the insert plus the "resolved" mark are one Room transaction. New
  `DedupTier.SAME_MESSAGE`. The unique index is proposed as A8. PR #53.
- 2026-10-28 · R29: closing a period computes the figures again from the database,
  and the Reconcile screen recomputes when the ledger changes (`LedgerChanges`, Room's
  invalidation tracker), not on every keystroke. PR #52.
- 2026-10-28 · R25: `LargeTextTest` runs in Amharic at font scale 2.0, and every
  screen test now checks that no visible text breaks inside a word. That check found
  16 squeezed labels per flavor, in English too. Label-and-value rows are now flows
  (reconcile figures, transaction detail rows, Reports totals and breakdowns, the
  ledger day header, review queue rows, the transaction row's second line). The
  credit statement's two buttons are stacked full width. PR #49. (Tag
  `v2026-10-28-2` not pushed: A6.)
- 2026-10-28 · R28: The "Message from …" row on the transaction detail is a button
  that says whether the message is shown or hidden, and what a double tap does, in
  English and Amharic. PR #50. (Tag `v2026-10-28-1` not pushed: A6.)
- 2026-10-27 · R27: Backup files carry schema 6 (`BackupRepository.SCHEMA_VERSION`
  was still 5). A test compares it with the version of the database Room builds,
  and a file written at schema 5 (no `possibleDuplicateOfId`) still restores
  exactly. PR #46. (Tag `v2026-10-27-1` not pushed: A6.)
- 2026-10-27 · R24: `LargeTextTest` covers every remaining screen (ledger,
  customers, Reports, Settings, onboarding, review queue, transaction detail and
  editor) and seven dialogs. Real fixes: the Amharic language chip in Settings was
  0dp wide at font scale 1.5 and 2.0 (the row is now a FlowRow). The transaction
  detail's "Reconciled" switch and "always use this category" checkbox were
  nameless second controls (the rows are now the toggles). The customer list's
  outstanding total and the detail's balance no longer wrap at a large font. PR #47.
  (Tag `v2026-10-27-2` not pushed: A6.)

- 2026-10-26 (second run) · R23: Settings shows "Last backup: <date>" or "never".
  Today shows a card when the ledger has transactions and there has been no backup
  for 30 days (counted from the first run if there was never one). "Back up now"
  opens Settings. "Not now" hides it for 30 days. Stamps are in DataStore, so no
  schema change. A failed backup, or one taken in demo mode, is not stamped. PR #43.
  (Tag `v2026-10-26-3` not pushed: A6.)
- 2026-10-26 (second run) · R26: `MoneyText` with a currency lays out the amount and
  "ETB" as a flow, so at a large font only "ETB" moves to the next line. Today's
  headline stays on one line at 100,000.00 and 1,234,567.89, at font scale 1.5 and
  2.0. PR #44. (Tag `v2026-10-26-4` not pushed: A6.)

- 2026-10-26 · R20: Large-text check (font scale 1.5 and 2.0, native graphics) on
  Today, cash entry, Reconcile and the credit statement. Real fix: Today's "Paid out"
  and "Net" sat in 136dp half-columns and broke at a large font ("−10,325." over
  "00"); they are now a FlowRow. Every control stayed at 48dp or more. PR #41. (Tag
  `v2026-10-26-2` not pushed: A6.)
- 2026-10-26 · R18 part 2: The add and edit customer, set PIN and both passphrase
  dialogs get the shared checks. `AccessibilityChecks.openDialog` reads the dialog's
  semantics straight from its compose root without waiting for idle. No defect found.
  PR #37. (Tag `v2026-10-26-1` not pushed: A6.)

- 2026-10-24 · R21: A period that closes with a gap can carry the owner's note
  (optional, trimmed, up to 200 characters). The closed-period snapshot shows it, and
  its button now says "Close" instead of "Cancel". English and Amharic. No schema
  change. PR #38. (Tag `v2026-10-24-1` not pushed: A6.)

- 2026-10-23 · R18 part 1: Compose dialogs can be tested under Robolectric. A
  one-cycle probe showed that only dialogs with text fields hang. The archive,
  remove-PIN and closed-period snapshot dialogs now get the shared checks. PR #34.
  (Tag `v2026-10-23-2` not pushed: A6.)
- 2026-10-23 · R19: The daily-net chart is read by TalkBack as one summary ("5 days:
  3 ahead, 1 behind, 1 even." then the best and the worst day as sentences), in
  English and Amharic. PR #35. (Tag `v2026-10-23-1` not pushed: A6.)

- 2026-10-22 · R17 part 2: Reconcile, Reports, Settings and Onboarding are covered.
  Real fixes: reconcile's "Credit repaid" was read as "−50.00 ETB" (each breakdown
  line is now a sentence that says "less"). The reports net was read as a symbol
  (it now uses Today's sentence), and its totals said "today" over a week. The
  settings switches had no name ("switch, on"); each row is now the toggle. Same
  for the PIN checkbox. Three chip rows were squeezed on a 360dp phone and now
  scroll. PR #31. (Tag `v2026-10-22-2` not pushed: A6.)
- 2026-10-22 · R15: The 42 Kotlin warnings in `app/src/test` are gone (13 opt-ins,
  Room `Callback` parameter names, one shared `Json`). PR #32. (Tag `v2026-10-22-1`
  not pushed: A6.)

- 2026-10-21 · R17 part 1: Shared `AccessibilityChecks` helper. The ledger, the
  customer list and the credit statement are covered. Real fixes: a statement line
  read its amount as "+500.00" and its balance as "−20.00 ETB". Both are now spoken
  as words ("Balance after: you owe them 20.00 ETB"), and so is a negative balance
  on the customer list. PR #28. (Tag `v2026-10-21-2` not pushed: A6.)
- 2026-10-21 · R14: A "Possible duplicates" chip in the ledger shows only flagged
  rows. The day subtotals follow the filter. A dismissed row leaves the view. PR #29.
  (Tag `v2026-10-21-1` not pushed: A6.)

- 2026-10-20 · R16: A refused `READ_SMS` in the `direct` build now says that no past
  payments were added, and offers a button to the app's page in system settings.
  That button is the only way forward after "don't ask again". The message clears on
  a successful retry, or on return from settings with the permission held. A
  dismissed dialog shows nothing. `play` is unchanged. PR #26. (Tag `v2026-10-20-2`
  not pushed: A6.)
- 2026-10-20 · R8: Accessibility semantics tests on Today, the keypad and the review
  queue. Three real fixes: Today's net total is spoken as a sentence. Both extended
  FABs (Today and Customers) now have a spoken name; they had none, because Material
  takes the name from the icon. Today's icon buttons are 48dp. PR #24. (Tag
  `v2026-10-20-1` not pushed: A6.)

- 2026-10-19 · R7: Permission-flow test. The real `OnboardingRoute` runs under
  Robolectric with a recording `ActivityResultRegistry` in place of the system dialog,
  in both flavors. `testPlay` and `testDirect` check each real importer's permissions.
  PR #23. (Tag `v2026-10-19-1` not pushed: A6.)

- 2026-10-18 · R6: Lint went from 42 warnings to none. One was a real bug: the weekly
  reconcile reminder never appeared on Android 8 to 12, because `POST_NOTIFICATIONS`
  is always "denied" below API 33. Fixed and tested. Version-check lint rules are off
  (Dependabot owns them). CI prints the lint report in every log. PR #19. (Tag
  `v2026-10-18-2` not pushed: A6.)
- 2026-10-18 · R9: CI writes the JaCoCo report and uploads it as the
  `coverage-play-debug` artifact on every build. Reported, not gated. PR #20. (Tag
  `v2026-10-18-1` not pushed: A6.)

- 2026-10-16 · R13: Reconciliation names flagged possible duplicates among the ranked
  gap explanations ("N payments may have been recorded twice" with their total). Only
  money in through a captured channel; deleted and dismissed flags are not named.
  Nothing is subtracted. PR #11. (Tag `v2026-10-16-2` not pushed: A6.)
- 2026-10-16 · R4: Dependabot for Gradle and GitHub Actions, weekly, minor and patch
  grouped, Kotlin and KSP in one group, AGP alone, with a guard test. PR #10. (Tag
  `v2026-10-16-1` not pushed: A6.)
- 2026-10-15 · R5: Lookalike payments with no provider reference are flagged as
  possible duplicates (schema v6, additive column), shown in the ledger, and
  dismissable. They are never dropped. Also added a CI step that fails when the
  exported Room schemas are not committed. PR #6. (Tag `v2026-10-15-2` was not
  pushed: A6.)
- 2026-10-15 · R3: Project rules as guard tests, including the merged-manifest
  permissions per flavor. PR #7. (The tag `v2026-10-15-1` could not be pushed: see
  NEEDS_APPROVAL A6.)

- 2026-10-14 · R2: The parser no longer reads a value from a cut-off message. Before
  the fix, 1,023 truncated variants parsed to wrong values; now none do. Malformed and
  unexpected-format fixtures added. PR #3.
- 2026-10-14 · R1: Provider-reference dedup is scoped to the channel and includes
  deleted rows. PR #2, tag `v2026-10-14-2`.
- 2026-10-14: CI workflow and operating docs. PR #1, tag `v2026-10-14-1`. Also fixed
  two problems that were already there: `gradlew` was not executable, and a
  demo-mode test raced the app's startup purge on Linux.
