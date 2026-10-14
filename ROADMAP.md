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

### R5 · P0 · M: Stable fallback dedup key when there is no provider ID
When a message has no `providerTxnId`, the only dedup is the content hash. The same
payment rendered differently by notification and SMS therefore creates two
transactions. Design: a fallback key over `(channel, direction, amountMinor,
balanceAfterMinor, occurredAt rounded to the minute)`. When it collides, **flag** the
new row as a possible duplicate for the user. Do not drop it, because two real
identical payments in one minute do happen. This needs one additive nullable column
(allowed) plus a migration test. Write the design in the PR first.

### R3 · P1 · S: Project-policy guard tests
Turn the project rules into tests so a later change cannot quietly break them:
- no `android.*` or `androidx.*` import in `parser/src/main`;
- no `fallbackToDestructiveMigration` anywhere;
- no `android.util.Log`, `println` or Timber calls in shipped code;
- no Float or Double field holding money (`*Minor`, `amount*`) in entities or models;
- no `INTERNET` or SMS `<uses-permission>` in the main or play manifests;
- a Room `version` bump without a matching exported schema JSON fails.

### R4 · P1 · S: Dependabot for Gradle and GitHub Actions
Add `.github/dependabot.yml` (weekly, grouped minor and patch updates) so dependencies
with known vulnerabilities surface as PRs. Free on public repos. Dependabot PRs follow
the normal merge policy, and any new transitive network stack is rejected
(`CONVENTIONS.md` #1).

### R6 · P2 · S: Triage the lint warnings
Lint reports 0 errors and about 26 warnings (`NOTES.md`). Read the full report, fix
the real ones, and suppress the rest narrowly with a reason, as was done for
`fullBackupContent`.

### R7 · P2 · M: Permission-flow test (notification access and SMS import)
`CONVENTIONS.md` names two UI tests worth having. The rule-induction test exists, but
the permission-flow test does not. Write it under Robolectric, like
`RuleBuilderScreenTest`, for both flavors.

### R8 · P2 · M: Accessibility semantics tests
Add Compose semantics tests: content descriptions on icon-only buttons, money read as
words rather than digits-plus-symbol, and 48dp touch targets on Today, the keypad and
the review queue. TalkBack quality still needs a person.

### R9 · P2 · S: Coverage report in CI
Run `:app:jacocoPlayDebugReport` in CI and upload the HTML as an artifact, reported
but not gated (see the reasoning in `app/build.gradle.kts`).

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

## Done

- 2026-10-14 · R2: The parser no longer reads a value from a cut-off message. Before
  the fix, 1,023 truncated variants parsed to wrong values; now none do. Malformed and
  unexpected-format fixtures added. PR #3.
- 2026-10-14 · R1: Provider-reference dedup is scoped to the channel and includes
  deleted rows. PR #2, tag `v2026-10-14-2`.
- 2026-10-14: CI workflow and operating docs. PR #1, tag `v2026-10-14-1`. Also fixed
  two problems that were already there: `gradlew` was not executable, and a
  demo-mode test raced the app's startup purge on Linux.
