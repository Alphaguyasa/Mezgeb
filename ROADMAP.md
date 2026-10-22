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
R19 to R22 were added.

## Open

### R18 · P2 · S: Accessibility check on the add-customer dialog
The dialog test was taken out of PR #28. With the dialog open, Compose under
Robolectric never went idle, and `setContent` timed out after 60s in both flavors.
Driving the clock by hand (`mainClock.autoAdvance = false`) did not help, so the
cause is not an animation. Find out why the dialog window never settles (try
`isDialog()` roots, the `ShadowDialog` route, or a test of the dialog's content
alone), then add the check back. The same applies to the other `AlertDialog`s
(archive customer, edit customer).
Note (2026-10-22): the PIN acknowledgement checkbox (PR #31) and the reconcile
snapshot dialog also wait on this. No Compose dialog has ever been tested in this
repo, so nothing here proves dialogs work under Robolectric at all. A cloud run has no
local Robolectric, so each guess costs a CI cycle. Start with one probe: a test that
opens a bare `AlertDialog` with one `Text`. If that settles, the cause is in the
dialog's content (the text fields); if not, it is the dialog window.

### R19 · P2 · S: The daily-net chart says nothing to TalkBack
Found in R17 part 2. `DailyNetChart` is a `Canvas` with no semantics, so a TalkBack
user hears the "Each day" heading and then nothing. Give it a spoken summary, for
example "7 days: 5 ahead, 2 behind, best day 880.00 ETB ahead", built from the same
bars, with the numbers as words like `spokenNet`. English and Amharic. Add the check
to `ReconcileReportsAccessibilityTest`.

### R20 · P2 · M: Large-text check (font scale 1.5 and 2.0)
Many shop owners set a large system font. No test runs any screen at a large font
scale. Run Today, the keypad, Reconcile and the credit statement under
`@Config(fontScale = 1.5f)` and `2.0f`. Check that controls stay
at 48dp or more and that the headline totals are not cut off (no `maxLines = 1`
ellipsis on an amount). R17 part 2 already found chip rows that did not fit at the
normal scale.

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
#17 once A7 is answered.

### R21 · P3 · S: A note when closing a period with a gap
`reconciliation_sessions.note` exists in the schema, but no screen writes it
(`ReconcileViewModel` always stores `null`). When a period closes with a gap, the
owner has nowhere to write why ("gave 200 change from my pocket"). Add an optional
note field to the close step, and show it in the closed-period snapshot. No schema
change. ViewModel and UI tests.

### R22 · P3 · M: Export closed reconciliation periods
The CSV export covers the ledger and the credit book, but not the closed periods,
which are what an accountant or a partner asks for. Add "Periods (CSV)" to the
Reports export menu, using the snapshotted columns as stored (never recomputed), plus
the note from R21. Goes through the same system file picker, so no permission is
needed. Tests for the CSV writer, including commas and quotes in a note.

## Done

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
