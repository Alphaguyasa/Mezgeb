# Roadmap

The backlog is in priority order. Take the top open item that is not blocked. When an
item is finished, move it to **Done** with its PR link. Replan when fewer than 5 items
are open (see CLAUDE.md §5).

Priority: **P0** is correctness, data loss or privacy. **P1** is safety nets and
security. **P2** is quality, UX and debt. **P3** is features.
Effort: **S** is under half a day, **M** is about a day, **L** needs splitting into
several PRs.

Built from the audit on 2026-10-14: the full code read plus the open items in
`NOTES.md` § "What is left".

## Open

### R7 · P2 · M: Permission-flow test (notification access and SMS import)
`CONVENTIONS.md` names two UI tests worth having. The rule-induction test exists, but
the permission-flow test does not. Write it under Robolectric, like
`RuleBuilderScreenTest`, for both flavors.

### R8 · P2 · M: Accessibility semantics tests
Add Compose semantics tests: content descriptions on icon-only buttons, money read as
words rather than digits-plus-symbol, and 48dp touch targets on Today, the keypad and
the review queue. TalkBack quality still needs a person.

### R14 · P2 · S: Ledger filter for possible duplicates
A filter chip in the ledger that shows only flagged rows, so a shop can work through
them in one place. This extends `listFiltered`/`pagingSourceFiltered` with one nullable
parameter.

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

### R15 · P2 · S: Clear the compiler warnings in test sources
CI's log shows about 40 Kotlin warnings, all in `app/src/test`: missing
`@OptIn(ExperimentalCoroutinesApi::class)` in the ViewModel tests, parameter names in
`MigrationTest.kt:309` that differ from Room's `Callback`, and a `Json` instance
created per use in `DatabaseSeederTest.kt:72`. None is a bug. They hide a new warning
the same way the 24 version warnings hid the reminder bug (R6).

## Done

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
