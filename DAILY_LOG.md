# Daily log

One entry per run, newest first. Each entry ends with the REPORT table from CLAUDE.md
§13 and a line saying what the next run will do.

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
