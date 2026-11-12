# Roadmap

The backlog is in priority order. Take the top open item that is not blocked. When an
item is finished, move it to **Done** with its PR link. Replan when fewer than 5 items
are open (see the engineering handbook §5).

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
2026-10-29, and R33 and R22 on the second 2026-10-29 run. R34 and R35 were done on 2026-10-30. R36 and R37 were done on 2026-10-31, and the
list then fell to 3, so the same run replanned: R38 to R44 come from a fresh code audit,
and R38 and R39 were checked by hand against the code. R38 and R39 part 1 were done on
2026-11-01. R40 and R41 were done on 2026-11-02. R42 and R43 were done on 2026-11-03. R44 was done on 2026-11-04, and
the list then fell to 4 (all blocked), so the same run replanned: R45 to R53 come from a
fresh code audit. R45 was done the same run. R45, R46 and R47 were checked by hand against the code; the others
carry the audit's file and line evidence and are re-checked when picked up. R46 and R47 were done on
2026-11-05. R48 and R49 were done on 2026-11-06. On 2026-11-12 the "Make Mezgeb sellable" milestone was added at the top, and R53 and R51 were done.

## M-next: Make Mezgeb sellable

The next milestone. Its items come before the rest of the open list.

- [ ] Decide what capture does during a demo (decision log A9), then finish R39 part 2 with tests
- [x] R53: ledger and customer search treat `%` and `_` literally, with DAO tests (2026-11-12)
- [x] R51: the privacy purge runs once per app version and after a restore (2026-11-12)
- [ ] R50: built-in category names shown in the app's language
- [ ] R52: "Not a payment" dismisses a message in the review queue
- [ ] Decide who holds the release keystore, then add a signed release workflow
- [ ] A weekly "money you may have missed" card built from the reconcile results
- [ ] A guided demo tour over demo mode, Amharic first
- [ ] A one-page sales sheet (problem, promise, price), for approval

### Later

Encryption at rest (R10, decision log A1) · corpus growth as real samples arrive (R11) ·
a monthly statement export per customer · a nudge to send the encrypted backup to
Telegram Saved Messages · rule packs exported and imported as a file · the SMS parser
as a library other apps can embed.

## Open

### R39 · P0 · M: Demo mode loses real payments captured during a demo (part 2 open)
**Part 1 done 2026-11-01 (PR #68): a restore is refused in demo mode. Part 2 is
blocked: see the decision log A9.**
Demo mode swaps the whole database (`di/DatabaseModule.kt:42`), but the notification
listener (`capture/notification/MezgebNotificationListener.kt:89`) and the `direct` SMS
import keep capturing, with no demo check anywhere in `capture/`. Real payments that
arrive during a demo go into the demo file, and `DemoModeController.disable()` (line
83) deletes it. A restore is also allowed in demo mode (`SettingsViewModel.kt:281`)
and is deleted the same way. Part 1 (no approval needed): refuse a restore while demo
mode is on, with a message. Part 2 waits on **the decision log A9** (what capture
does during a demo). Tests: restore in demo mode is refused and nothing is written;
part 2 per the decision.

### R50 · P2 · S: Built-in category names show in English in the Amharic UI
`DatabaseSeeder.SYSTEM_CATEGORIES` stores "Sales", "Rent" and the rest as literals, and
every screen and the report show `category.name` as stored. Fix: map the system ids
(`cat_*`) to string resources at display time. Names the shop typed stay. No migration.
Tests: the resolver in the `am` locale.

### R52 · P3 · S: A message in the review queue can never be dismissed
`ReviewEvent` has only `Reprocess`. Promotions from an allowed sender stay forever and
reconcile counts their sniffed amounts as possible explanations
(`ReconcilePeriodUseCase.kt:183-191`). Fix: "Not a payment" marks an `UNMATCHED` row
`IGNORED`. Strings in both languages. Tests: the event, reconcile no longer counts it,
a parsed row is refused.

### R10 · P1 · L: Encrypt the database at rest
**Blocked: see the decision log A1.** This needs a new native dependency
(SQLCipher), a one-time migration of the plaintext database, and a decision on
deleting the plaintext file afterwards.

### R11 · P2 · M: Corpus coverage (Amharic templates, a 4th sender)
**Blocked: see the decision log A2.** This needs real anonymized samples from a
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

- 2026-11-12 · R53: Ledger and customer search treat `%`, `_` and `\\` literally. Both
  searches use `instr(lower(column), lower(query)) > 0` instead of `LIKE`, which keeps
  the same ASCII case folding. DAO tests for each character and for case.
- 2026-11-12 · R51: The privacy purge no longer scans every stored message on every
  launch. `PrivacyPurgeGate` keeps the installed version code per database file and
  scans only when it changes, so the demo file and the real ledger are tracked apart. A
  restore purges the restored rows inside its own transaction. Tests for the gate and
  for a restore of a backup holding a PIN and a text from a person.

- 2026-11-06 · R48: A background failure no longer crashes the app. The application
  scope carries a `CoroutineExceptionHandler` from the new `BackgroundFailureLog`, which
  stores only a count and a time (the exception is not logged or stored). Today shows a
  dismissible "Something could not be saved" card that says to free up space and check
  the provider messages, in English and Amharic. The startup purge, seed and first-run
  stamp each run on their own. The transaction detail writes (save, category,
  reconciled, duplicate dismiss, delete) show "Could not save. Try again." A refused
  save keeps the editor open. PR #82. (Tag `v2026-11-06-2` not pushed: A6.)
- 2026-11-06 · R49: Tapping the reconcile reminder opens Reconcile. It carries an
  immutable `PendingIntent` to `MainActivity` with the `OPEN_RECONCILE` action and no
  extras. The request waits through the PIN lock and is dropped during onboarding.
  No permission change. PR #83. (Tag `v2026-11-06-1` not pushed: A6.)

- 2026-11-05 · R47: A cash entry can no longer be saved twice. `canSave` now also
  needs `!isSaved`, so a second tap between the save and the screen closing records
  nothing. The failed-write test now fails the insert with a taken id instead of a
  second save. PR #80.

- 2026-11-05 · R46: Backups keep deleted transactions. The new
  `TransactionDao.getAllIncludingDeleted()` feeds the backup, so after a restore the
  second copy of a deleted payment is still `Duplicate(PROVIDER_TXN_ID)`, and the
  possible-duplicate flags still point at real rows. No schema change. PR #79.

- 2026-11-04 · R45: The inbox import (`direct`) no longer stops after its first
  page when one row has no address or no body. `InboxSource.page()` returns an
  `InboxPage` with the kept rows and the cursor rows read, and the worker pages and
  stops on rows read. The test fake now pages like the provider; new tests over 1,200
  rows and an all-blank first page. PR #77.

- 2026-11-04 · R44: A "Share statement" button on the customer screen sends the
  statement as plain text through the share sheet (`ACTION_SEND` in a chooser): dated
  lines, signed amounts, the balance after each line and the balance now. No
  permission, no network, no phone number needed. Notes stay out. English and
  Amharic. `StatementText` plus 11 tests, 2 ViewModel tests, 2 accessibility
  checks. PR #76.

- 2026-11-03 · R43: `CreditEntryViewModelTest` (11 tests) covers the credit form's
  own rules: the keypad cap at 10,000,000.00 birr, double zero and backspace, a zero
  amount and a missing customer refused, the note trimmed and the picked day kept,
  `SAVE_FAILED` with the form kept for a retry, payments re-ranked on every amount
  change, the 7-day window and money going out, and a second pick replacing the
  first. Test-only. PR #74. (Tag `v2026-11-03-2` not pushed: A6.)
- 2026-11-03 · R42: A statement line opens the credit form for that entry. A
  correction changes the amount, the day and the note through a targeted `UPDATE`,
  so the kind and the repayment link are never written over. Remove asks first. An
  entry removed elsewhere is not re-created, and another customer's entry cannot be
  opened. Closed periods keep their snapshotted figures (tested). Also fixed: a saved
  entry could be saved again in the moment before the screen closed. PR #73. (Tag
  `v2026-11-03-1` not pushed: A6.)

- 2026-11-02 · R41: The amount normalizer declines instead of guessing. Separators
  are dropped only when the whole part is grouped in threes ("1,5" and "12,34" are
  refused, not read as 15 and 1,234 birr). The santim arithmetic is exact, so a
  17-digit amount is refused instead of wrapping negative. A narrow no-break space
  (U+202F) is a separator (it used to cut "1 234.56" to 1 birr). The parser refuses a
  zero amount; a zero balance is still read. Corpus accuracy unchanged. PR #71. (Tag
  `v2026-11-02-2` not pushed: A6.)
- 2026-11-02 · R40: Backup, restore and export run on the injected `@IoDispatcher`,
  not on Main. A backup or export that fails after its file was opened deletes that
  file; a file that could not be opened is left alone (a picker may have offered an
  existing file to replace). PR #70. (Tag `v2026-11-02-1` not pushed: A6.)

- 2026-11-01 · R39 part 1: A restore is refused while demo mode is on. The button is
  disabled with a line saying why, a chosen file does not open the passphrase dialog,
  and `restore()` checks `DemoModeController.isEnabled()` live before opening the file.
  Part 2 (capture during a demo) stays open on A9. PR #68. (Tag `v2026-11-01-2` not
  pushed: A6.)
- 2026-11-01 · R38: The backup passphrase waits in `SettingsViewModel` (memory only,
  used once, then zeroed) instead of a Compose `remember`, so a rotation no longer
  writes a backup with `""`. A file that comes back with no passphrase is refused and
  deleted. `BackupCrypto.write` refuses a passphrase under 8 characters. PR #67. (Tag
  `v2026-11-01-1` not pushed: A6.)

- 2026-10-31 · R37: Reconcile names breaks in the telebirr and CBE Birr balance chain
  ("balance went up by X between 10:02 and 14:30 with nothing recorded"), with the exact
  difference in santim. Shown, never booked. A drop of up to max(10 birr, 2%) per
  outgoing transfer counts as a fee; a rise never does. Bank rows are not chained, and a
  possible duplicate restarts the chain. PR #65. (Tag `v2026-10-31-2` not pushed: A6.)
- 2026-10-31 · R36: Backups are format 2: the whole header is GCM associated data, so
  an edited schema or format byte fails as `WRONG_PASSPHRASE_OR_CORRUPT`. Format 1
  files still restore; a format 2 file relabelled as format 1 fails. PR #64. (Tag
  `v2026-10-31-1` not pushed: A6.)

- 2026-10-30 · R35: Today rolls over at local midnight with the screen still open.
  The day's bounds come from the new `LocalDays` (`ClockLocalDays`), which re-emits
  at the next local midnight on the injected clock; a refresh still restarts it.
  PR #62. (Tag `v2026-10-30-2` not pushed: A6.)
- 2026-10-30 · R34: "Lock immediately" no longer asks for the PIN on rotation or a
  language change. `MainActivity.onStop()` passes `isChangingConfigurations`, and a
  configuration-change stop is not counted as time away. Tested in `AppLockStateTest`
  (no Hilt activity-test setup exists for a `recreate()` test). PR #61. (Tag
  `v2026-10-30-1` not pushed: A6.)
- 2026-10-29 (second run) · R22: Reports → Export → "Closed periods (CSV)" writes
  every closed period, oldest first, with the figures as snapshotted (never
  recomputed). `accounted_for` and `gap` are derived from the stored figures through
  `PeriodReconciliation`. No expected total leaves `expected` and `gap` empty. The
  note is quoted (commas, quotes, line breaks) and neutralised. The open period is
  never exported. English and Amharic. PR #59. (Tag `v2026-10-29-4` not pushed: A6.)
- 2026-10-29 (second run) · R33: CSV export no longer runs formulas. Every
  free-text cell (name, phone, reference, category, note) that starts with `=`, `+`,
  `-`, `@`, tab or CR gets a leading `'`. Amounts and dates are unchanged, so a
  negative gap stays a number. PR #58. (Tag `v2026-10-29-3` not pushed: A6.)
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
  The decision log A6.)

- 2026-10-14 · R2: The parser no longer reads a value from a cut-off message. Before
  the fix, 1,023 truncated variants parsed to wrong values; now none do. Malformed and
  unexpected-format fixtures added. PR #3.
- 2026-10-14 · R1: Provider-reference dedup is scoped to the channel and includes
  deleted rows. PR #2, tag `v2026-10-14-2`.
- 2026-10-14: CI workflow and operating docs. PR #1, tag `v2026-10-14-1`. Also fixed
  two problems that were already there: `gradlew` was not executable, and a
  demo-mode test raced the app's startup purge on Linux.
