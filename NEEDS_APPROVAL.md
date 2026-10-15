# Needs approval

Decisions, money or access that only Alpha can give. Each item says what is asked,
why, the cost, and the alternatives. To answer one, write the decision under the
item (an edit on GitHub from the phone works), or tell the daily run in the Claude
app. Resolved items move to the bottom with the decision and the date.

## Open

### A1 · Encrypt the database at rest (ROADMAP R10)
**Ask:** may I add SQLCipher and migrate the existing database to an encrypted one?
- **What:** `net.zetetic:sqlcipher-android` (BSD-style license, free) with Room's
  `SupportFactory`. The key would be random and wrapped by an Android Keystore key.
- **Why:** project rule "encrypt the database at rest where feasible". Today
  `mezgeb.db` is plaintext SQLite. It is protected only by Android's file-based
  encryption and the app sandbox. A rooted or forensically imaged phone reads it.
- **Costs:** about +3 to 4 MB per ABI on a 3 MB APK. Slightly slower queries. A
  native library to keep updated.
- **The hard part:** existing installs need a one-time copy from plaintext to
  encrypted. Afterwards the plaintext file must be **deleted**, or encryption achieves
  nothing. That conflicts with "never delete user data", so it needs your yes. The
  plan is to copy, verify row counts in the encrypted copy, swap, and only then delete
  the old file. On any failure, keep the plaintext database and try again next launch.
- **Alternatives:** (a) encrypt new installs only; (b) do nothing and rely on
  Android's file-based encryption (default on Android 10+, but minSdk is 26).
- **Money:** none.

### A2 · More real message samples (ROADMAP R11)
**Ask:** can you send redacted samples when convenient?
- The corpus has 84 samples from 3 senders and **no Amharic transaction templates**.
  On your phone, 10 of 274 real payments failed to parse.
- A cloud run cannot read your phone, and invented samples would test the parser
  against imagined formats. `tools/redact-samples.ps1` and
  `docs/04-SMS-SAMPLE-COLLECTION.md` describe the process.
- **Money:** none. Until then, parser work stays limited to the formats already
  covered.

### A3 · Protect the `main` branch (recommended)
**Ask:** in GitHub → Settings → Branches, add a rule for `main` that requires the
`CI / build` check to pass before merging.
- The daily run already waits for CI, but this setting enforces it, and repo settings
  are yours to change, not mine.
- **Money:** none (free on public repos). Do **not** require PR reviews, or the
  autonomous merges will be blocked.

### A4 · Release signing and the Play listing (no action needed, just recorded)
Release APKs stay unsigned. No keystore exists, and the no-purchases policy rules out
a Play Console account ($25). When you want to publish, generate a keystore yourself,
keep it out of the repo, and store it as a GitHub Actions secret. A release workflow
can then be added on request.

### A6 · Release tags cannot be pushed from the daily run
**Ask:** none needed now. Decide only if you want the tags.
- On 2026-10-15 the run merged PR #7 and created tags `v2026-10-15-1` (`d79cba9`, #7) and
  `v2026-10-15-2` (`82f4758`, #6). Pushing them failed every time ("remote end hung up"), while
  branch pushes and merges worked. The cloud environment's git proxy seems to refuse
  tag refs. As CLAUDE.md §8 says, I did not look for a workaround.
- **Effect:** you can still roll back. Every merge is a squash commit on `main` with
  its PR number, and the log lists each one. Only the named tags are missing.
- **Options:** (a) do nothing (the log stays the rollback list); (b) create the tags
  yourself from the GitHub web UI (Releases → Draft a new release → new tag on the
  listed commit); (c) allow the routine's environment to push tags, if that setting
  exists for you.
- **Money:** none.

## Resolved

- 2026-10-14 · A5: the daily routine was created with Alpha's approval. It runs
  `claude-opus-5-5` every day at 05:00 UTC (08:00 Nairobi) against this repo, with no
  connectors attached. Alpha switches it to "Automatically approve" and turns on
  notifications. Alpha's decision: Opus only, never Fable (no credit for it).

- 2026-10-14: CLAUDE.md in the repo and AI-made PRs are fine. This replaces the old
  "no AI signals" preference (answered in the setup session).
