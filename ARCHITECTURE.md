# Mezgeb — Architecture

A condensed version of the design document, describing what was built rather than what
was planned. Where the two differ, the difference is called out. The full spec lives in
`docs/03-ARCHITECTURE.md`; running notes on every deviation and deferral are in
[NOTES.md](NOTES.md).

---

## 1. Why the capture design looks like this

Google Play grants `READ_SMS` / `RECEIVE_SMS` only to apps whose core purpose requires
being the default SMS handler. A finance app reading payment confirmations does not
qualify, and the policy covers dormant placeholder declarations. That single constraint
shapes the whole app.

Capture therefore runs through a `NotificationListenerService`, which needs no restricted
permission — the user grants notification access in system settings and can revoke it
there. The `direct` flavor adds a one-time SMS inbox import for historical backfill, which
notification capture cannot do; the `play` flavor ships without it. The permission appears
in `app/src/direct/AndroidManifest.xml` and nowhere else — and it is `READ_SMS` alone.
`RECEIVE_SMS` was declared for several sessions on the strength of the original brief,
never had a caller, and has been removed: an unused restricted permission still costs an
explanation to a reviewer and to whoever reads the install prompt.

**Both paths converge on one funnel.** `CaptureCoordinator` applies the sender allowlist
and the credential filter *before* any insert:

- messages from personal numbers are never stored — the onboarding screen promises they
  are ignored, and "ignored" has to mean "not kept";
- messages stating a PIN or one-time code are never stored;
- a message from a *financial* sender that no rule can read **is** stored, because that is
  the review queue. "We could not read this" and "this was never ours to keep" are
  different answers, and teach-a-rule depends on the first one existing.

An earlier build applied the allowlist only on the SMS path, so the notification path —
the one that runs on every message a phone receives — stored personal messages labelled
`IGNORED`. Fixed by moving the policy into the shared coordinator; one funnel, one place
for it to be wrong.

**Deduplication.** A payment can arrive as both a notification and an SMS. Three tiers,
in order:

1. **Content hash.** Raw messages are keyed by a hash over the normalized body plus
   sender, so the same text arriving twice is dropped.
2. **Provider reference.** When the two copies are worded differently, the provider's
   transaction id catches them. It is scoped to the channel and includes deleted rows.
3. **Lookalike flag.** With no reference to compare (or one on one side only), a row with
   the same channel, direction, amount and balance within a minute of an existing one is
   recorded but **flagged** (`transactions.possibleDuplicateOfId`, schema v6). It is never
   dropped, because two real payments of one amount inside a minute do happen. The
   ledger shows "possible duplicate", and the shop owner deletes the copy or taps
   "Not a duplicate".

## 2. Modules

```
:app       Android application — Compose UI, Room, capture services, DI
:parser    Pure Kotlin/JVM — rule engine, normalization, rule induction
```

`:parser` has zero Android dependencies. No `android.*`, no `Context`, no `android.util.Log`.
That is what lets the accuracy harness run in plain JUnit against the fixture corpus on
every build, instead of needing a device or Robolectric.

## 3. Layers inside `:app`

MVVM with unidirectional data flow. Each screen exposes a single `StateFlow<XxxUiState>`
and receives a single `onEvent(XxxEvent)`. Repositories return `Flow` for reads and
`suspend` for writes; DAOs are never touched from a ViewModel. Every screen has a
stateless `XxxScreen(state, onEvent)` plus a `XxxRoute(viewModel)` wrapper, so previews
target the stateless one.

Packages are by feature — `ui/today`, `ui/ledger`, `ui/review`, `ui/customers`,
`ui/reconcile`, `ui/reports`, `ui/settings` — with shared infrastructure in `data/`,
`di/`, `capture/`, `designsystem/`.

## 4. Data model

Money is always `Long` in minor units (santim). Never `Double`, never `BigDecimal`.
A field named `amount` without the `Minor` suffix is a bug. Formatting happens only in the
UI layer, via `MoneyFormatter`.

| Entity | Meaning |
|---|---|
| `RawMessage` | An unparsed captured notification or SMS, stored verbatim |
| `Transaction` | A parsed money movement, in or out, linked to one `RawMessage` |
| `ParserRule` | A versioned pattern that turns a `RawMessage` into a `Transaction` |
| `CreditEntry` | Money a customer owes (`CREDIT_GIVEN`) or has repaid (`REPAYMENT`) |
| `ReconciliationSession` | A closed period comparing expected against captured totals |

Rows are soft-deleted; a shop's ledger never loses history. Schema is at **v4**, with
hand-written migrations and no `fallbackToDestructiveMigration` anywhere — a missing
migration must fail loudly in development rather than quietly wipe a ledger in the field.
Migrations are tested by rebuilding the old database from the *committed schema JSON*
rather than from DDL copied into a test, because a test that reconstructs v1 from
anywhere else is testing a fiction.

**`editedAt` vs `touchedAt` (v4).** These were one column, and it meant two things. The
ledger's "edited" marker means "this no longer says what the message said", but
categorising, deleting and restoring all stamped the same field — so filing a payment
under a category made the ledger claim a value had been changed. They are now separate:
`editedAt` covers only parsed-value changes and drives the marker; `touchedAt` covers any
human action and is what reconciliation asks about, since a period's figures move when a
row is filed or restored, not only when an amount is corrected.

## 5. Parser engine

```
RawMessage → sender allowlist → rule match → field extraction → normalization → ParseOutcome
                                    ↓ no match
                              review queue → user highlights spans → rule induction → new ParserRule
```

Rules are versioned JSON, compiled to regex at runtime, defined in `:parser` and shipped
to the app as a generated asset — so the rules the app ships are provably the rules the
accuracy figure was measured against. Two committed copies would eventually disagree, and
the disagreement would be silent.

**Normalizers** are the fiddly, high-value part: amounts with mixed separators, Ethiopian
phone numbers in four written forms normalized to E.164, dates in provider-specific
formats. **Rule induction** generalises the values a user highlighted while anchoring the
literal text around them, and re-parses the backlog when a rule is added.

Outcomes are a sealed `ParseOutcome`, never an exception across the module boundary.

## 6. Background execution

WorkManager runs the `direct` flavor's inbox backfill as a foreground service (a long
backfill must not be killed), the reconciliation reminder, and the review-queue reprocess
after a rule is induced. DataStore holds capture cursors and health timestamps.

`CaptureHealthMonitor` reports `HEALTHY` / `NO_ACCESS` / `SILENT_TOO_LONG` — the last of
which matters because notification access can be revoked silently by the system, and an
app that captures nothing looks identical to a shop that sold nothing.

## 7. Reconciliation

```
capturedIn    = Σ transactions, direction=IN, in period, not deleted, channel ≠ CASH
capturedOut   = Σ transactions, direction=OUT, in period, not deleted
cashRecorded  = Σ transactions, channel=CASH, direction=IN
creditGiven   = Σ credit entries, kind=CREDIT_GIVEN, in period
creditRepaid  = Σ credit entries, kind=REPAYMENT, in period

accountedFor  = capturedIn + cashRecorded + creditGiven − creditRepaid
gap           = expectedTotal − accountedFor
```

> **Deviation from the spec, deliberate.** `docs/03-ARCHITECTURE.md` §7 defines
> `capturedIn` without excluding `CASH`. A cash sale is `direction=IN` *and*
> `channel=CASH`, so it falls inside both terms — implemented literally, a shop that took
> 200 birr by telebirr and 100 in cash is told it accounted for 400 and is short by 100:
> exactly the cash it took. Plausible, precise, and it sends someone hunting for money
> that was never missing. The glossary settles the intent — "captured" means "arrived as a
> message", and a cash entry has no message — so `capturedIn` excludes `CASH`. The first
> test in `ReconcilePeriodUseCaseTest` pins this down.

When `gap ≠ 0` the screen shows a ranked list of explanations rather than a bare number:
unmatched messages in the period (with a partial total where the amount alone was
extractable), uncategorised transactions, possible duplicates (money in flagged by the
lookalike check, which still counts in `capturedIn` and is never subtracted), outstanding
credit, and rows a human touched since the period was opened.

Closing a session snapshots every computed figure into the entity. Later edits do not
retroactively change a closed period — that is the entire point of closing one, and it is
tested.

## 8. Security

- PIN stored as a PBKDF2 hash in Keystore-backed `EncryptedSharedPreferences`, never in
  cleartext. Two iteration counts on purpose: 210,000 for a backup that runs once, 60,000
  for an unlock that runs forty times a day, because a slow lock screen is one people
  turn off. No hash makes ten thousand four-digit possibilities unguessable to someone who
  can run the comparison — PBKDF2 buys time per guess, and the Keystore-backed key is the
  actual barrier.
- Biometric via `androidx.biometric`, with the PIN always available as fallback.
  Exponential backoff after five wrong guesses.
- `FLAG_SECURE` set on the window. The lock is drawn *instead of* the navigation graph,
  not over it — a ledger composed behind a lock screen is one a transition, a resize or a
  recents thumbnail can still show.
- Backups are AES-256-GCM with a PBKDF2-derived key and a random salt. The header is
  deliberately plaintext: restore has to be able to say *why* it refused, and it cannot
  distinguish a wrong passphrase from a wrong file if it must decrypt before it can look.
  GCM means a flipped byte fails the tag instead of restoring a ledger with one wrong
  number in it. Everything is validated before anything is cleared.
- `android:allowBackup="false"` — Google's auto-backup does not get a shop's ledger.
- Zero `Log` / `Timber` / `println` calls in shipped code. Raw message bodies are never
  logged, and there is no crash reporter to send them anywhere.

## 9. The v2 seam

```kotlin
interface ExpectedSalesProvider {
    suspend fun expectedTotalMinor(periodStart: Long, periodEnd: Long): Long?
}
```

v1 ships `ManualEntryExpectedSalesProvider`, backed by the figure a user types on the
reconcile screen. Because the reconciliation engine depends only on this interface,
integrating a point-of-sale source later is a new class and a DI binding, not a refactor.

## 10. Hard constraints

These are enforced, not aspirational:

1. **No network code in either shipped flavor.** No Retrofit, OkHttp, Ktor, Firebase,
   analytics or phone-home crash reporter. The absence of `android.permission.INTERNET`
   from the merged release manifest is verified, and is a product feature.
2. **SMS permissions live only in `src/direct/`** — never in `src/main`, not even as a
   comment or placeholder.
3. **Money is always `Long` minor units.**
4. **`:parser` is pure Kotlin/JVM.**
5. **Raw message bodies never leave the device and are never logged.**
