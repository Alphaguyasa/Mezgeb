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

### A5 · Create the daily routine (Phase 2)
**Ask:** approve creating the cloud routine, or create it yourself at
https://claude.ai/code/routines:
- Name: Mezgeb daily lead developer. Repo: `Alphaguyasa/Mezgeb`.
- Schedule: `0 5 * * *` UTC, which is 08:00 Africa/Nairobi.
- Model: Claude Fable 5.1 (`claude-fable-5-1`), a higher tier than Opus. If it is not
  offered on your plan, use the newest Opus. Never upgrade a plan for it.
- Prompt: "You are the autonomous lead developer of Mezgeb. Read CLAUDE.md,
  ROADMAP.md, DAILY_LOG.md, and NEEDS_APPROVAL.md. Follow every policy in CLAUDE.md.
  Do the daily loop, then send the report."
- Then turn on push notifications and switch it to **Automatically approve**,
  because scheduled runs have nobody to approve actions.
- **Money:** none.

### A4 · Release signing and the Play listing (no action needed, just recorded)
Release APKs stay unsigned. No keystore exists, and the no-purchases policy rules out
a Play Console account ($25). When you want to publish, generate a keystore yourself,
keep it out of the repo, and store it as a GitHub Actions secret. A release workflow
can then be added on request.

## Resolved

- 2026-10-14: CLAUDE.md in the repo and AI-made PRs are fine. This replaces the old
  "no AI signals" preference (answered in the setup session).
