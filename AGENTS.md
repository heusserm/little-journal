# AGENTS.md — Little Journal

Working notes for coding agents. Read this before touching the project; it
records the commands that work and the traps that cost time.

**What it is:** a talk-first journal for iOS and Android. Speak, transcribe
on-device, file the entry against the day it is *about*. Kotlin Multiplatform +
Compose. Local-first, no backend, no accounts, no network.

**Repo:** <https://github.com/heusserm/little-journal> (public)
**Location:** `~/Code/LittleJournal`

**What the app actually does today is specified in
[`FUNCTIONAL_SPEC.md`](FUNCTIONAL_SPEC.md)** — read that before changing
behaviour, and update it when behaviour changes. This file covers how to build
and test; that one covers what the thing does.

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

Generated SQLDelight code: `storage/build/generated/sqldelight/`

## Running everything

One block, in the order that fails fastest:

```bash
./gradlew :storage:jvmTest :app:desktopTest            # 100 Kotlin tests
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' \
  -derivedDataPath iosApp/dd                           # 10 Swift tests
detekt --input app/src,storage/src --config detekt.yml --build-upon-default-config
swiftlint lint --quiet
./gradlew :app:lintDebug                               # Android Lint
python3 tools/crap.py && python3 tools/scrap.py && python3 tools/dry.py
```

All of it is green except four Android Lint advisories about newer dependency
versions. Keep it that way.

## Running the tests

110 tests: 100 Kotlin, 10 Swift. All of them pass; keep it that way.

```bash
# Everything Kotlin — the usual command
./gradlew :storage:jvmTest :app:desktopTest

# One suite, or one test
./gradlew :app:desktopTest --tests "*SpokenText*"
./gradlew :app:desktopTest --tests "*JournalStateTest.a spoken draft*"

# Swift (needs a simulator; the app target builds first)
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' \
  -derivedDataPath iosApp/dd
```

HTML reports land in `storage/build/reports/tests/jvmTest/index.html` and
`app/build/reports/tests/desktopTest/index.html`.

### What the suites cover

| Suite | Tests | Covers |
|---|---:|---|
| `JournalRepositoryTest` | 10 | schema behaviour: tombstones, date moves, counts |
| `JournalRepositoryQueryTest` | 7 | ranges, tags, limits |
| `PersistenceTest` | 5 | file-backed DB survives reopening — the only test proving a relaunch does not wipe the journal |
| `JournalStateTest` | 17 | dictation callbacks, draft handling, time cleanup |
| `JournalStatePagingTest` | 11 | month paging, editing, delete |
| `SpokenTextTest` + `EdgeTest` | 16 | time correction, both directions |
| `ScreenUiTest` | 8 | real screens rendered and driven |
| `DayScreenUiTest` | 5 | day view and navigation into the editor |
| `AppNavigationUiTest` | 4 | the real tab bar |
| `HelpersTest` | 4 | tag parsing, pluralisation, date labels |
| `IosTranscriberTests` (Swift) | 5 | buffer conversion, environment naming |

UI tests use `runComposeUiTest` on the desktop target against a real in-memory
database. `FakeTranscriber` stands in for the platform recognizer, so dictation
logic is testable with no microphone and no device.

## Coverage

```bash
./gradlew :app:koverXmlReport :storage:koverXmlReport   # XML, for tooling
./gradlew :app:koverHtmlReport                          # readable report

# Swift
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' \
  -derivedDataPath iosApp/dd -enableCodeCoverage YES \
  -resultBundlePath iosApp/cov.xcresult
xcrun xccov view --report --json iosApp/cov.xcresult
```

Kotlin is at **93% line / 72% branch**. Generated SQLDelight output is excluded
from the report; counting it flatters storage and hides gaps elsewhere.

`IosTranscriber.swift` sits at **54%**. The rest is unreachable: it touches
`SpeechAnalyzer` or `AVAudioEngine`, neither of which runs in the Simulator.
Treat changes there with more care than the number suggests.

## Linting

Two real linters, both installed via Homebrew rather than as Gradle plugins —
see the trap below for why.

```bash
brew install detekt swiftlint          # once
detekt --input app/src,storage/src --config detekt.yml --build-upon-default-config
swiftlint lint --quiet
```

**Both are clean as of 2026-08-20.** Configuration lives in `detekt.yml` and
`.swiftlint.yml`, and both files record *why* each deviation exists rather than
just silencing rules.

Out of the box detekt reported **108 weighted issues**; roughly 80% were Compose
conventions, not defects. `@Composable` functions are PascalCase by design, and
`dp`/`sp`/colour literals are not magic numbers. Two rounds of tuning brought it
to 2 real findings, both of which were worth fixing. **The tuning matters more
than the tool** — a linter that cries wolf gets ignored, and then it protects
nothing.

Note detekt's default test excludes assume `src/test/`. Kotlin Multiplatform
uses `commonTest` / `desktopTest` / `jvmTest`, so they are listed explicitly in
`detekt.yml`; without that, every backticked test name is a naming violation.

### Android Lint — fixed by upgrading AGP

`:app:lintDebug` used to fail outright: AGP 8.7.3 bundled a Kotlin 2.0 analyzer
against a Kotlin 2.2.20 project and could not read its own compiled metadata.
Upgrading to **AGP 8.13.2 + Gradle 8.14.3** fixed it, and the first successful
run immediately found a bug nothing else could:

```
[Error] MissingClass  AndroidManifest.xml:8
        Class referenced in the manifest, com.xndev.littlejournal.MainActivity,
        not found
```

The manifest said `.MainActivity`, which resolves against the namespace to
`com.xndev.littlejournal.MainActivity`, while the class lives in
`...littlejournal.app`. **The Android app compiled, installed and would have
died with ClassNotFoundException on launch.** No unit test, no Compose test and
no amount of coverage could have caught that; only a manifest-aware linter.

The lesson is worth keeping: a broken analyzer is worse than a missing one,
because its silence reads like approval.

## Code health (CRAP)

CRAP scores how risky a change to a method is, by combining how branchy it is
with how well it is tested:

```
CRAP = complexity² × (1 − coverage)³ + complexity
```

Fully covered code scores its own complexity. Uncovered code is punished
sharply — the cube means partial coverage helps a lot. **Above 30 fails**: that
is code both branchy and unverified, where a change is most likely to break
something quietly.

```bash
./gradlew :app:koverXmlReport :storage:koverXmlReport
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' \
  -derivedDataPath iosApp/dd -enableCodeCoverage YES \
  -resultBundlePath iosApp/cov.xcresult
python3 tools/crap.py
```

**As of 2026-08-20: 85 methods scored, mean 2.1, max 20.0, none over 30.**

Everything scoring above 2 — the rest are at or below their own complexity and
need no attention:

| CRAP | cx | loc | cover | method | file |
|---:|---:|---:|---:|---|---|
| **20.0** | 4 | 17 | 0% | `pump` | IosTranscriber.swift |
| 6.6 | 6 | 9 | 75% | `CurrentScreen` | App.kt |
| 6.0 | 6 | 39 | 100% | `SearchScreen` | SearchScreen.kt |
| 6.0 | 2 | 11 | 0% | `databasePath` | main.kt (desktop) |
| 4.2 | 4 | 7 | 75% | `back` | JournalState.kt |
| 4.2 | 4 | 26 | 78% | `prepareTranscriber` | IosTranscriber.swift |
| 4.0 | 4 | 33 | 100% | `DayCell` | CalendarScreen.kt |
| 4.0 | 4 | 42 | 100% | `EditorScreen` | EditorScreen.kt |
| 4.0 | 4 | 14 | 100% | `commitDraft` | JournalState.kt |
| 4.0 | 4 | 19 | 100% | `save` | JournalState.kt |
| 3.3 | 3 | 18 | 68% | `authorize` | IosTranscriber.swift |
| 3.0 | 3 | 53 | 100% | `CalendarScreen` | CalendarScreen.kt |
| 3.0 | 3 | 28 | 100% | `DayScreen` | DayScreen.kt |
| 3.0 | 3 | 16 | 100% | `LiveTranscript` | TodayScreen.kt |
| 3.0 | 3 | 16 | 100% | `CaptureActions` | TodayScreen.kt |
| 3.0 | 3 | 9 | 100% | `MovedFromNote` | EditorScreen.kt |
| 3.0 | 3 | 7 | 100% | `onFinal` | JournalState.kt |

**`pump` is the one worth knowing about.** It drains recognizer results and
dispatches them to the app, it is completely untested, and it is the only
method in the project scoring in double digits. It cannot be reached from the
Simulator because it needs a live `SpeechTranscriber`. If dictation ever
misbehaves in a way the UI can see — text arriving twice, partials never
settling, results lost — start there.

`databasePath` scores 6 on complexity 2 purely because it is uncovered; it is
eleven lines of desktop-only path resolution and not worth chasing.

Everything else scoring above 2 is fully covered and scores its own complexity,
which is the floor. Nothing there needs work.

**Desktop dev database:** `~/Code/LittleJournal/journal.db` (gitignored). The
path is printed at startup. Override with `-Dlittlejournal.db=/path`. Inspect
it directly — it is plain SQLite:

```bash
sqlite3 journal.db "SELECT entry_date, substr(body,1,50) FROM entry;"
```

## Test quality (SCRAP) and duplication (DRY)

Two more analyzers live in `tools/`. Neither needs a build first.

```bash
python3 tools/scrap.py            # test-suite smells, per file
python3 tools/scrap.py --detail   # per-test breakdown
python3 tools/dry.py              # repeated code in production sources
python3 tools/dry.py --tests --loose --show   # include tests, ignore literals
```

**SCRAP** is the mirror of CRAP: CRAP asks how risky a production method is,
SCRAP asks how much the tests can be trusted. The model, smell set and weights
are adapted from **Robert C. Martin's SCRAP for Speclj**
(<https://github.com/unclebob/scrap>); only the parsing is ours, because Kotlin
and Swift have no reader and must be measured by brace matching.

```
SCRAP      = complexity_score + smell_penalties
complexity = 1 + branches + setup_depth + helper_calls + hidden_lines/8
```

Squaring mirrors CRAP — structure compounds — and saturates so one bad test
cannot swamp a file. Under 10 healthy, 25+ means the suite is probably lying to
you. Reports mean *and* max per file, because one terrible test hides inside a
good average.

Two ideas taken from the original are worth knowing, because both correct
mistakes the obvious implementation makes:

- **Hidden lines.** A five-line test calling a forty-line helper is not a
  five-line test. Helper bodies count toward the caller.
- **Extraction pressure.** Duplication is Jaccard similarity over each test's
  setup/assert feature sets, then clustered — not text comparison. A repeated
  one-line factory call is deliberate isolation and must not be penalised; six
  tests assembling the same fixture differently should be.

Exemptions matter as much as rules: table-driven tests legitimately branch, and
contract tests legitimately assert once over many lines. Flagging those trains
people to ignore the tool.

`--write-baseline` and `--compare` track drift over time.

**As of 2026-08-20: mean SCRAP 6.0 across 110 tests, worst 25. `dry.py` reports
1.2% duplication.** A baseline is committed at `tools/.scrap-baseline.json`.

Both found real problems on their first run, which is the only reason they are
worth keeping:

- SCRAP flagged **four tests with no assertions** — "does not crash" tests that
  would have passed if the method became an empty stub. They now assert real
  post-conditions.
- One of those, once given a post-condition, **crashed the test runner** and
  exposed a genuine bug: tapping Talk then immediately Stop raced startup
  against teardown and installed an audio tap on a torn-down engine. Guarded by
  `shouldAbort` in `IosTranscriber`.
- `dry.py` found the test suite carrying **its own copy of the production
  screen dispatch** — a parallel `CurrentScreenForTest` that could silently
  diverge from `CurrentScreen` and let navigation tests pass while the real app
  was broken. Deleted; the tests use the real one.

A caveat learned the hard way: SCRAP's duplicate-setup rule originally flagged a
single repeated factory call (`val s = state()` at the top of each test). That
is deliberate isolation, not duplication, and penalising it punishes the better
pattern. It now requires a shared prefix of at least two lines.

## Health report

Measured 2026-08-20 with the commands above. Regenerate rather than trust these
numbers; every one of them is reproducible from `tools/`.

| Metric | Value |
|---|---|
| Production methods | 109, across 1,280 lines |
| Tests | **110** — 100 Kotlin, 10 Swift |
| Assertions | 190, 1.8 per test |
| Kotlin line coverage | **95.7%** (605/632) |
| Kotlin branch coverage | 77.0% (234/304) |
| Swift line coverage | 38.6% (90/233) |
| CRAP | mean **2.3**, max 20.0, **none over 30** |
| SCRAP | mean **6.0** (healthy), worst 25 |
| Duplication | 1.8% (16 redundant lines) |
| detekt | **0** |
| SwiftLint | **0** |
| Android Lint | 4 warnings, all "newer version available" |

Where the risk actually is, in one place:

| CRAP | cx | cover | method |
|---:|---:|---:|---|
| 20.0 | 4 | 0% | `prepareTranscriber` — IosTranscriber.swift |
| 20.0 | 4 | 0% | `pump` — IosTranscriber.swift |
| 6.0 | 6 | 100% | `CurrentScreen` — App.kt |

Everything scoring above 6 is in `IosTranscriber.swift`, and it cannot be
tested from the Simulator because there are no speech assets there. Swift
coverage *fell* from 54% to 38.6% when the start/stop race was fixed — the
buggy version was executing startup code after `stop()` had been called, and
that accidental execution was being counted as coverage. Lower number, better
code.

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

**Microphone permission is not speech permission.** They are two separate
grants. `NSSpeechRecognitionUsageDescription` in the plist only supplies the
prompt text — the app must still call
`SFSpeechRecognizer.requestAuthorization`. Without that grant the OS refuses to
subscribe the app to any ASR asset, and the symptom is the deeply misleading
"<bundle-id> is not subscribed to transcription.en".

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

## Advice

Earned the hard way on this codebase. Each of these cost a build cycle or a
wrong answer.

**Disbelieve your own tooling until it agrees with ground truth.** `scrap.py`
reported 109 tests when the runner reported 110 — a parser bug that made a
helper swallow a test. A test-quality tool that cannot find all the tests is
worthless, and nothing about its output looked wrong. Whenever a tool counts
something, find an independent count and compare.

**A broken analyzer is worse than a missing one.** `:app:lint` failed silently
for the whole life of this project, and its silence read like approval. The
first run after fixing it found a manifest bug that would have crashed the
Android app on launch, past 110 tests and 95% coverage.

**Coverage going down can mean the code got better.** Swift coverage fell 54% →
38.6% when the start/stop race was fixed, because the buggy version was
executing startup code after `stop()`. Chasing the number back up would mean
reintroducing the bug.

**A test that cannot fail is worse than no test.** Four "does not crash" tests
had no assertions and would have passed against an empty stub. Giving one a
real post-condition made it crash immediately and exposed a genuine race.

**Fix the code, not the assertion.** `AVAudioConverter` returned 1360 frames
where arithmetic said 1600. The fix was not a wider tolerance; it was pinning
the property that actually matters and recording *why* the naive number is
wrong.

**Tune a linter before trusting it.** detekt out of the box reported 108 issues
here, ~80% of them Compose conventions. A tool that cries wolf gets ignored, and
then it protects nothing. Both config files record *why* each deviation exists.

**Run the app, not only the tests.** The desktop target exposed the month grid
overlapping its own final week within seconds of first launch — a defect no
state-level test could see, because nothing about the state was wrong.

**The Simulator cannot do speech.** Do not debug "not subscribed to
transcription.en" there. It ships no ASR assets and never will.

**Be careful with `git add -A`.** `-resultBundlePath` writes thousands of binary
files; one blind add swept 933 objects into the repo. And `.gitignore` does not
untrack what is already tracked.

**Write down why, not just what.** The comment on `onThisDay` records that
Kotlin's `substring` is 0-based and SQLite's `substr` is 1-based, so the same
offset appears as 5 in Kotlin and 6 in `Entry.sq`. A linter flagged a magic
number; the real finding was a cross-language coupling that would break
silently.

## Related conventions

App Store work for Matt's other apps follows `~/Code/AppStoreListings/STATUS.md`
— read it before any store work. Screenshots live in
`~/Code/AppStoreScreenshots/<size>/<app>_<device>_<screen>.png`, never inside
app repos. Apple ID `the Apple ID recorded in STATUS.md`, Team `A69JRS6V57`.
