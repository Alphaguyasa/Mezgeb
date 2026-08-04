# Conventions

> Copy this to the repository root as `CONVENTIONS.md` before Session 1.

## Project

**Mezgeb (መዝገብ)** — offline Android app that captures mobile-money payment notifications (telebirr, CBE Birr, bank SMS), parses them into a ledger, tracks customer credit, and reconciles the two. Built for small shops in Ethiopia.

Read-only with respect to money. The app never initiates, sends, or authorizes a payment. Ever.

## Hard constraints — do not violate these

1. **No network code in the `play` or `direct` product flavors.** No Retrofit, no OkHttp, no Ktor client, no Firebase, no analytics SDK, no crash reporter that phones home. If a dependency pulls in a network stack transitively, flag it before adding. The absence of `android.permission.INTERNET` from the manifest is a product feature and a selling point.
2. **SMS permissions may only appear in the `direct` flavor's manifest.** `READ_SMS` and `RECEIVE_SMS` must never appear in `src/main/AndroidManifest.xml`, in a comment, or as placeholder text. Google Play rejects apps that declare these without default-SMS-handler capability, and the policy explicitly covers placeholder declarations. They live exclusively in `src/direct/AndroidManifest.xml`.
3. **Money is always `Long` in minor units (santim).** Never `Double`, never `Float`, never `BigDecimal` in entities. Formatting to display strings happens only in the UI layer via `MoneyFormatter`. A value named `amount` without the `Minor` suffix is a bug.
4. **The `:parser` module is pure Kotlin/JVM.** No Android imports, no `android.*`, no context, no logging via `android.util.Log`. It must run in plain JUnit with no Robolectric. This is what makes the parser testable against the sample corpus.
5. **Raw message bodies never leave the device and are never logged.** No `Log.d(TAG, message.body)`. Redact in any debug output: amount and sender are fine, body is not.

## Stack

- Kotlin 2.x, JDK 17, Gradle version catalogs (`gradle/libs.versions.toml`) — never hard-code dependency versions in build files
- Jetpack Compose, Material 3, single-Activity, Navigation Compose (type-safe routes)
- Room + KSP for persistence, `@TypeConverters` kept minimal
- Hilt for DI
- WorkManager for background import and periodic reconciliation reminders
- DataStore (Preferences) for settings — not SharedPreferences
- kotlinx.serialization for parser rule JSON
- Testing: JUnit5 + kotlin.test in `:parser`, JUnit4 + Robolectric + Turbine + MockK in `:app`

## Module layout

```
:app        Android application. Compose UI, Room, capture services, DI.
:parser     Pure JVM. Rule engine, regex compilation, field extraction,
            normalization, rule induction. Zero Android deps.
```

Inside `:app`, package by feature: `ui/today`, `ui/ledger`, `ui/review`, `ui/customers`, `ui/reconcile`, `ui/reports`, `ui/settings`. Shared infrastructure in `data/`, `di/`, `capture/`, `designsystem/`.

## Conventions

- **Architecture:** MVVM + unidirectional data flow. ViewModel exposes a single `StateFlow<XxxUiState>`; UI sends events through a single `onEvent(XxxEvent)`. No `LiveData`. No mutable state escaping the ViewModel.
- **Repositories** return `Flow` for reads and `suspend` for writes. DAOs are never touched from a ViewModel.
- **Composables** are stateless where possible. Every screen has a `XxxScreen(state, onEvent)` stateless version plus a `XxxRoute(viewModel)` wrapper. Previews target the stateless one.
- **Naming:** `amountMinor`, `occurredAt` (epoch millis, UTC), `receivedAt`, `*Entity` for Room, `*Dto` never (no network), `*UiModel` for UI-shaped data.
- **Errors:** domain operations return `Result<T>` or a sealed `ParseOutcome`. No exceptions for control flow across module boundaries.
- **Strings:** every user-facing string in `strings.xml`. Amharic translations in `values-am/`. No hardcoded text in Composables, including in previews.

## Domain vocabulary — use these exact terms in code

| Term | Meaning |
|---|---|
| **RawMessage** | An unparsed captured notification or SMS, stored verbatim. |
| **Transaction** | A parsed money movement. In or out. Linked to one RawMessage. |
| **Channel** | Where the money moved: `TELEBIRR`, `CBE_BIRR`, `BANK`, `CASH`, `OTHER`. |
| **ParserRule** | A versioned pattern that turns a RawMessage into a Transaction. |
| **Review queue** | RawMessages that no rule matched, awaiting user teaching. |
| **Rule induction** | Generating a candidate ParserRule from user-highlighted spans. |
| **CreditEntry** | Money a customer owes (`CREDIT_GIVEN`) or has repaid (`REPAYMENT`). |
| **Reconciliation session** | A closed period comparing expected vs captured totals. |

Do not introduce synonyms. Not "payment", not "record", not "entry" — `Transaction`.

## Testing expectations

- `:parser` must maintain **≥95% extraction accuracy** on the fixture corpus in `parser/src/test/resources/corpus/`. Every session that touches parsing runs `./gradlew :parser:test` and reports the accuracy number.
- Every DAO gets an instrumentation-free Room in-memory test.
- Every ViewModel gets a Turbine test covering initial state, loading, empty, and error.
- Do not write UI tests for layout. Write them for permission flows and the rule-induction interaction, which are where the real bugs live.

## Working style

- Work session by session from `BUILD-SESSIONS.md`. Do not jump ahead to features from a later session.
- At the end of each session: run `./gradlew build`, run all tests, report what passed and what you stubbed.
- If a session's instructions conflict with a hard constraint above, stop and say so rather than silently choosing.
- Prefer boring, obvious code. This is a project someone will read to evaluate the author.
- When adding a dependency, state why and check it against constraint #1.

## Out of scope — do not build these unprompted

Inventory or stock. Invoicing. Cloud sync or user accounts. Multi-currency beyond ETB. Tax/VAT calculation. Payment initiation. Sending SMS via `SEND_SMS` (reminders use an `Intent` to the default messaging app, which needs no permission). OCR of receipts. Anything involving an LLM API.
