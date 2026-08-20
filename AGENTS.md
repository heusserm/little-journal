# AGENTS.md — Little Journal

Working notes for coding agents. Read this before touching the project; it
records the commands that work and the traps that cost time.

**What it is:** a talk-first journal for iOS and Android. Speak, transcribe
on-device, file the entry against the day it is *about*. Kotlin Multiplatform +
Compose. Local-first, no backend, no accounts, no network.

**Repo:** <https://github.com/heusserm/little-journal> (public)
**Location:** `~/Code/LittleJournal`

---

## Layout

```
storage/                        Persistence. Built, tested.
  src/commonMain/sqldelight/      Entry.sq, EntryTag.sq — schema + queries
  src/commonMain/kotlin/          JournalEntry, JournalRepository
  src/jvmMain/                    JDBC driver + desktopRepository()
  src/androidMain/                AndroidSqliteDriver + androidRepository()
  src/iosMain/                    NativeSqliteDriver + iosRepository()
  src/jvmTest/                    JournalRepositoryTest — 10 tests

app/                            Compose UI. Built.
  src/commonMain/kotlin/          App, JournalState, 4 screens, Format,
                                  Components
  src/androidMain/                MainActivity + AndroidManifest.xml
  src/iosMain/                    MainViewController
  src/desktopMain/                main.kt (dev target)

iosApp/                         Xcode wrapper, XcodeGen from project.yml.
  iosApp/IosTranscriber.swift     SpeechAnalyzer implementation of the Kotlin
                                  Transcriber interface, injected at startup
```

## Commands

```bash
./gradlew :storage:jvmTest                    # the test suite (fast, use often)
./gradlew :app:run                            # desktop app — fastest iteration
./gradlew :app:compileKotlinDesktop           # compile check, ~2s
./gradlew :app:compileKotlinIosArm64          # verify iOS target
./gradlew :storage:compileDebugKotlinAndroid  # verify Android target
./gradlew build                               # everything

# iOS app
cd iosApp && xcodegen generate                # after editing iosApp/project.yml
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'id=<device-udid>' -derivedDataPath iosApp/dd-device \
  -allowProvisioningUpdates build
xcrun devicectl device install app --device <device-udid> \
  "iosApp/dd-device/Build/Products/Debug-iphoneos/Little Journal.app"
```

Test reports: `storage/build/reports/tests/jvmTest/index.html`
Generated SQLDelight code: `storage/build/generated/sqldelight/`

**Desktop dev database:** `~/Code/LittleJournal/journal.db` (gitignored). The
path is printed at startup. Override with `-Dlittlejournal.db=/path`. Inspect
it directly — it is plain SQLite:

```bash
sqlite3 journal.db "SELECT entry_date, substr(body,1,50) FROM entry;"
```

## Toolchain

Kotlin 2.2.20 · Compose 1.9.0 · AGP 8.7.3 · SQLDelight 2.1.0 · JVM 17 ·
compileSdk 35 · minSdk 26. Plugin versions are declared once in the root
`build.gradle.kts` and applied without versions in modules. **There is no
version catalog** — do not add one without asking.

Requires Xcode 26+ for iOS (`SpeechAnalyzer` is iOS 26 only).

## The core design idea

`entry_date` is the day an entry is *about*; `created_at` is when it was
written. Splitting them is what allows backfilling Tuesday on Thursday, and it
is what the calendar groups on. Moving an entry rewrites `entry_date` and
leaves `created_at` alone. **A day holds many entries, not one.**

Four columns exist only so sync can be added later without migrating real
users' data: `id` (UUID, never an autoincrement), `updated_at` (watermark),
`deleted_at` (tombstone), `device_id`. Nothing reads them yet.

Journals are single-user and append-mostly, so when sync arrives,
**last-write-wins per entry is sufficient** — no CRDTs.

## Traps

**No upsert.** `ON CONFLICT ... DO UPDATE` needs SQLite 3.24+; Android API 26
ships 3.19. This is a `minSdk` constraint, not a dialect setting. Writes use
`INSERT OR REPLACE`, which is safe only because `JournalRepository.save` is the
single writer and always supplies every column — `REPLACE` deletes the old row
first, so a partial column list would blank `created_at`.

**Timestamps are truncated to milliseconds on write.** The schema stores epoch
millis; `Clock.System.now()` offers nanoseconds. Without truncation
`save(e) != byId(e.id)`, which would read as spurious churn to any future sync.
A test guards this.

**Material icons are not bundled with `material3`** in Compose Multiplatform.
Navigation uses text glyphs rather than pulling in `materialIconsExtended`.

**`SpeechAnalyzer` is a Swift-only API** — an `actor` exposing `AsyncSequence`.
Kotlin/Native interops through Objective-C headers and **cannot see it**.
Dictation requires a Swift shim implementing a Kotlin-declared interface,
injected into `MainViewController`. There is no way around this.

**The iOS app target must link `-lsqlite3`.** SQLDelight's native driver binds
the *system* SQLite, and the Kotlin framework does not bundle it, so linking
fails with an undefined `_sqlite3_step`. The flag lives in `OTHER_LDFLAGS` in
`iosApp/project.yml`.

**Speech recognition does not work in the iOS Simulator — at all.** The runtime
ships no ASR assets. The log says `GeneralASR is not supported on this platform`
and reports zero available languages, so `AssetInventory` can never install a
model there. Dictation can only be exercised on a physical device. Do not spend
time debugging a "not subscribed to transcription.en" error in the simulator;
it is not your code.

**Compose `Column` does not scroll by default.** The month grid overlapped its
own final week on short windows until `verticalScroll` was added. Run the
desktop target at a small window size before trusting any new layout.

## Dictation research (already done — do not redo)

Measured with a throwaway macOS CLI spike against `say`-generated audio:

- 66 s of speech → 1.2 s (53× realtime); 6.6 min → 5.4 s (73× realtime)
- No quality drift or truncation at the six-minute mark
- **`SpeechTranscriber` beat `DictationTranscriber`.** Dictation formats numbers
  better (`6:30` vs `6.30`) but dropped a person's name and produced run-ons.
  Names and sentence structure matter more; spoken times get fixed in post.
- Language models download on demand via `AssetInventory`, managed by the OS.
  Not bundled — the app needs a first-run download state.

**Untested:** real human speech on a real device. A SwiftUI probe called
**Journal Spike** (`com.xndev.littleJournalSpike`) is installed on Matt's
iPhone 17 Pro but has never been run.

## Status

Storage, UI, the iOS wrapper, dictation, spoken-time cleanup and search are
built and committed. Runs on the iOS simulator and installs on a physical
device.

Not built: daily prompts, export/import, FTS search, Android dictation. Nothing
is shippable and nothing is on the App Store.

**Dictation has never been verified against real human speech.** Everything
measured so far was `say`-generated audio on macOS. The app is installed on
Matt's iPhone 17 Pro awaiting that test.

## Related conventions

App Store work for Matt's other apps follows `~/Code/AppStoreListings/STATUS.md`
— read it before any store work. Screenshots live in
`~/Code/AppStoreScreenshots/<size>/<app>_<device>_<screen>.png`, never inside
app repos. Apple ID `the Apple ID recorded in STATUS.md`, Team `A69JRS6V57`.
