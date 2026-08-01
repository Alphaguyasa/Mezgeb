# Mezgeb — build notes

Running log of anything stubbed, deferred, or decided differently from the pack.

---

## Session 1 — Scaffold and design system (2026-08-01)

### Verification results

| Check | Result |
|---|---|
| `:app:assemblePlayDebug` | pass |
| `:app:assembleDirectDebug` | pass |
| `:app:testPlayDebugUnitTest` | pass (2 tests, `TodayViewModelTest`) |
| `:parser:test` | **fails by design** — see "Intentional failure" below |
| SMS tokens in `app/src/main/` | clean (0 occurrences) |
| SMS tokens in merged `playDebug` manifest | clean (0) |
| SMS tokens in merged `directDebug` manifest | clean (0) |
| `INTERNET` in either merged manifest | clean (0) |
| Android references in `parser/src/main/` | clean (0) |
| `:parser` compile classpath | `kotlin-stdlib`, `kotlinx-serialization-json` only |

The only `uses-permission` in either merged manifest is
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature-level permission that
androidx.core defines for itself. It is not a Play-restricted permission.

### Intentional failure

`parser/src/test/.../PlaceholderTest.kt` fails on purpose, as Session 1 step 2
requires, to prove the JUnit 5 test task actually executes. **`./gradlew build` will
therefore fail until Session 2 deletes it.** Use `./gradlew :app:assemblePlayDebug
:app:assembleDirectDebug :app:testPlayDebugUnitTest` for a green run in the meantime.

### Deviations from the pack

1. **compileSdk / targetSdk raised 36 → 37.** The wizard project did not build as
   generated: `androidx.core:core:1.19.0` and `androidx.lifecycle:*:2.11.0` both
   require compiling against API 37 or later. The instruction to keep the wizard's
   SDK versions was not compatible with the dependency versions the wizard itself
   chose. AGP downloaded platform 37 automatically. AGP, Gradle wrapper and Kotlin
   were left untouched as instructed.

2. **`org.jetbrains.kotlin.android` is deliberately NOT applied to `:app`.** AGP 9
   ships built-in Kotlin support and registers the `kotlin` extension itself;
   applying the standalone plugin fails with an extension-name collision. `:parser`
   still applies `kotlin("jvm")` normally.

3. **`android.disallowKotlinSourceSets=false` in `gradle.properties`.** AGP 9's
   built-in Kotlin rejects source directories registered through the
   `kotlin.sourceSets` DSL, and the KSP Gradle plugin still registers its generated
   sources that way. This is the opt-out AGP's own error message prescribes. Revisit
   when KSP supports built-in Kotlin.

4. **JDK 17 is the language level, not the daemon JVM.** The Gradle daemon runs on
   the JDK 21 bundled with Android Studio (`gradle/gradle-daemon-jvm.properties`
   pins `toolchainVersion=21`). Both modules compile to JVM target 17 as .md
   requires. No JDK 17 toolchain download is needed.

5. **Noto Sans Ethiopic is bundled** (`app/src/main/res/font/`, ~1.1 MB, OFL licence
   in `app/licenses/`). Android's font fallback does cover Ethiopic, so this is
   belt-and-braces — but several OEM ROMs common in this market ship trimmed font
   sets, and the architecture doc already flags those vendors as a risk area. A
   ledger rendering as tofu boxes is worse than useless. Counts against the Session
   12 APK budget of 15 MB.

6. **Deleted from the wizard scaffold:** `ui/theme/` (superseded by `designsystem/`),
   the sample `colors.xml` palette, `backup_rules.xml` and `data_extraction_rules.xml`
   (unreferenced once `allowBackup="false"`; removing them means nothing can
   silently re-opt the app into cloud backup), and the two example test classes.

### Stubbed / deferred

- Every one of the seven destinations renders an `EmptyState` placeholder. Real
  screens begin in Session 5.
- `TodayViewModel` exists only to prove the Hilt → `hiltViewModel()` → `StateFlow`
  chain works end to end. It holds no real state.
- `AppModule` provides only a `Clock`. Database, repository and capture bindings
  arrive in Sessions 3 and 4.
- `MoneyFormatter` has no unit tests yet — the amount-formatting edge cases are
  covered as part of Session 2's normalizer work, where the parsing side lives.
- Room, WorkManager, DataStore, Paging, biometric, Robolectric and MockK are in the
  version catalog but not yet on `:app`'s dependency list. They get added by the
  session that first needs them, per .md's "state why when adding a dependency".

### Known low-priority risk

`kotlinx-serialization-json:1.11.0` pulls `kotlin-stdlib` up to 2.3.20 while the
compiler is 2.2.10. A stdlib newer than the compiler is the supported direction (the
reverse is not), and everything compiles and runs, so this is left alone rather than
churned. Worth re-checking if odd metadata errors ever appear.

### Not verified

Compose previews were not rendered — that needs Android Studio, which cannot be
driven from the CLI. `TodayScreen.kt` carries an `am`-locale preview
(`TodayScreenAmharicPreview`) specifically so the Ethiopic render can be eyeballed;
**that check is still outstanding.** Neither flavor has been installed on a device.
