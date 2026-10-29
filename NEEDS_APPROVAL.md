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
- On 2026-10-15 the run merged PRs #7 and #6 and created tags `v2026-10-15-1` (`d79cba9`, #7) and
  `v2026-10-15-2` (`82f4758`, #6). Pushing them failed every time ("remote end hung up"), while
  branch pushes and merges worked. The cloud environment's git proxy seems to refuse
  tag refs. As CLAUDE.md §8 says, I did not look for a workaround.
- 2026-10-16: the same failure for `v2026-10-16-1` (`8451e53`, #10) and
  `v2026-10-16-2` (`b8a038c`, #11). The four missing tags are all created locally only.
- 2026-10-18: the same failure for `v2026-10-18-1` (`9b45d42`, #20) and
  `v2026-10-18-2` (`bfd8b6b`, #19). Six tags are missing now.
- 2026-10-19: the same failure for `v2026-10-19-1` (`9202766`, #23). Seven tags are
  missing now.
- 2026-10-20: the same failure for `v2026-10-20-1` (`58566c8`, #24) and
  `v2026-10-20-2` (`0921345`, #26). Nine tags are missing now.
- 2026-10-21: the same failure (now an explicit HTTP 403) for `v2026-10-21-1`
  (`0f75778`, #29) and `v2026-10-21-2` (`a5b538c`, #28). Eleven tags are missing now.
- 2026-10-22: the same failure for `v2026-10-22-1` (`c56bf67`, #32) and
  `v2026-10-22-2` (`1e79bec`, #31). Thirteen tags are missing now.
- 2026-10-23: the same failure (HTTP 403) for `v2026-10-23-1` (`b2916ba`, #35) and
  `v2026-10-23-2` (`ba42ace`, #34). Fifteen tags are missing now.
- 2026-10-24: the same failure ("remote end hung up") for `v2026-10-24-1`
  (`564010b`, #38). Sixteen tags are missing now.
- 2026-10-26: the same failure ("remote end hung up") for `v2026-10-26-1`
  (`fdd35e4`, #37) and `v2026-10-26-2` (#41). Eighteen tags are missing now.
- 2026-10-26 (second run): the same failure (HTTP 403) for `v2026-10-26-3`
  (`1ba07b2`, #43) and `v2026-10-26-4` (`a116a3b`, #44). Twenty tags are missing now.
- 2026-10-27: the same failure ("remote end hung up", then HTTP 403) for
  `v2026-10-27-1` (`451baa7`, #46) and `v2026-10-27-2` (`c576084`, #47). Twenty-two
  tags are missing now.
- 2026-10-28: the same failure ("remote end hung up") for `v2026-10-28-1`
  (`e74f044`, #50) and `v2026-10-28-2` (`046c849`, #49). Twenty-four tags are
  missing now.
- 2026-10-28 (second run): the same failure ("remote end hung up") for
  `v2026-10-28-3` (`655bcaf`, #52) and `v2026-10-28-4` (`b5571a4`, #53).
  Twenty-six tags are missing now.
- 2026-10-29: the same failure ("remote end hung up") for `v2026-10-29-1`
  (`497571f`, #55) and `v2026-10-29-2` (`fd8b093`, #56). Twenty-eight tags are
  missing now.
- 2026-10-29 (second run): the same failure ("remote end hung up", HTTP 403) for
  `v2026-10-29-3` (`bc3adae`, #58) and `v2026-10-29-4` (`be1ea74`, #59). Thirty tags
  are missing now.
- **Effect:** you can still roll back. Every merge is a squash commit on `main` with
  its PR number, and the log lists each one. Only the named tags are missing.
- **Options:** (a) do nothing (the log stays the rollback list); (b) create the tags
  yourself from the GitHub web UI (Releases → Draft a new release → new tag on the
  listed commit); (c) allow the routine's environment to push tags, if that setting
  exists for you.
- **Money:** none.

### A7 · Who is the author of a merged Dependabot PR?
**Ask:** may the daily run merge Dependabot PRs whose squash commit names
`dependabot[bot]` as its author?
- Since PR #10, Dependabot opens update PRs. It opened four at once on 2026-10-16,
  all major GitHub Actions bumps: #12 `actions/checkout` 4→7, #13 `actions/setup-java`
  4→5, #14 `gradle/actions` 4→6, #15 `actions/upload-artifact` 4→7. Your rule from
  2026-10-15 says every commit shows you as the only author. The run's own commits do.
  A squash merge of a Dependabot PR, however, keeps Dependabot as the author, and the
  run cannot change that without rewriting the commit.
- 2026-10-16: Dependabot also opened #17, the grouped minor and patch update (24
  updates, including Kotlin 2.2.10 → 2.4.21, KSP → 2.3.12, AGP 9.3.1 → 9.4.1,
  Gradle 9.5.0 → 9.8.1). CI is green on it. It waits on this answer too.
- 2026-10-19: Dependabot opened #22 (`actions/setup-java` 4→6, green). #13 (4→5)
  is no longer open.
- 2026-10-26: Dependabot closed #17 and opened #40 in its place: the same grouped
  update, now 26 updates (Gradle 9.8.2, Compose BOM 2026.09.00, Kotlin 2.4.21, KSP
  2.3.12, AGP 9.4.1, Robolectric 4.17). CI is green on it. Open now: #12, #14, #15,
  #22, #40.
- 2026-10-27: CI now warns that four actions target the deprecated Node.js 20
  (`checkout@v4`, `setup-java@v4`, `upload-artifact@v4`, `setup-gradle@v4`). #12, #14,
  #15 and #22 move all four, so answering this also clears that warning.
- **Until you answer:** the run reviews Dependabot PRs (CI, and the transitive tree
  for a network stack) and reports them, but leaves them open and unmerged.
- **Options:** (a) yes, Dependabot may appear as the author of its own update commits;
  (b) no, the run re-creates each update as its own commit on a `claude/` branch and
  closes the Dependabot PR; (c) merge them yourself from the phone.
- **Money:** none.

### A8 · A unique index on `transactions.rawMessageId` (follow-up to R30)
**Ask:** may I add a unique index so the database itself refuses a second
Transaction for one message?
- **Why:** R30 (#53) stops it in code: the capture funnel checks first, and the two
  writes are now one database transaction. An index would make it impossible, not
  just prevented.
- **The catch:** a phone that already has two Transactions for one message (the bug
  R30 fixed) would fail the migration. The migration would have to choose one of the
  two and delete or unlink the other first. That is deleting user data, so it needs
  your yes, and "never delete user data" may mean the answer is no.
- **Options:** (a) yes, keep the older row, soft-delete the newer one and flag it in
  the review queue; (b) a non-unique check instead: a startup scan that only flags
  such pairs for you to resolve; (c) leave it as is (the code guard holds).
- **Money:** none.

## Resolved

- 2026-10-14 · A5: the daily routine was created with Alpha's approval. It runs
  `claude-opus-5-5` every day at 05:00 UTC (08:00 Nairobi) against this repo, with no
  connectors attached. Alpha switches it to "Automatically approve" and turns on
  notifications. Alpha's decision: Opus only, never Fable (no credit for it).

- 2026-10-14: CLAUDE.md in the repo and AI-made PRs are fine. This replaces the old
  "no AI signals" preference (answered in the setup session).
