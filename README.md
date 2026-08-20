# Little Journal

A cross-platform (iOS + Android) journal built around **talking, not typing**.
Speak for as long as you like; it transcribes entirely on your device, files the
entry against the day it's *about*, and lets you find it again later by date,
tag, or text.

Think Notes, but where dictation doesn't give up when you pause to think, and
where the calendar is a first-class way in.

**Author:** Matthew Heusser — matt@xndev.com
Written by Matthew Heusser with help from Claude Code.

---

## Decisions (locked)

| Area | Decision |
|---|---|
| **Platforms** | Kotlin Multiplatform + Compose (iOS + Android) |
| **Dictation** | On-device. Apple `SpeechAnalyzer` on iOS 26+; Android's `SpeechRecognizer` on the other side |
| **Storage** | Local-first SQLDelight. No backend, no account, no network |
| **Sync** | None in v1 — but the schema is sync-ready from day one |
| **Backup** | Export to a file the user owns, in *their* cloud. We never hold their data |
| **Encryption** | Deferred. Revisit when files start leaving the device |
| **minSdk** | 26 — which costs us SQLite upsert, see below |

The privacy stance is deliberate and load-bearing: **a journal is the worst
possible thing to hold on someone else's behalf.** Running a server full of
other people's diaries means breach liability, deletion requests, and an App
Store privacy declaration that can never again say *Data Not Collected*. Local
storage plus user-controlled export gets most of the durability at none of that
cost.

---

## Why on-device dictation is the whole product

The older `SFSpeechRecognizer` cut out after roughly a minute, which is why
dictation in most apps feels like a toy. iOS 26's `SpeechAnalyzer` is built for
long-form audio. Measured on a throwaway spike before any of this was written:

| Input | Time to transcribe | Ratio |
|---|---|---|
| 66 s of speech | 1.2 s | 53× realtime |
| 6.6 min of speech | 5.4 s | **73× realtime** |

Quality at the six-minute mark was indistinguishable from the first sentence —
no drift, no truncation, no timeout. A ten-minute entry transcribes in about
eight seconds.

**`SpeechTranscriber` over `DictationTranscriber`.** Both were tried on identical
audio. `DictationTranscriber` formats numbers better (`6:30` where the other
gives `6.30`), but it dropped a person's name outright and produced run-on
paragraphs. `SpeechTranscriber` keeps proper nouns and sentence structure, and
its number handling is fixable in post — a name your journal silently deleted is
not. Spoken times get a cleanup pass on our side.

**Android will be the weaker half.** There is no equivalent to `SpeechAnalyzer`
on Android, and offline model quality varies by manufacturer. Plan accordingly:
iOS is the flagship.

---

## Data model

The one idea worth understanding:

```
entry_date   the day the entry is ABOUT      <- editable, what the calendar groups on
created_at   when it was actually written    <- never changes
```

Splitting those is what lets you sit down on Thursday and backfill Tuesday.
Moving an entry to another day rewrites `entry_date` and leaves `created_at`
alone. A day holds many entries, not one.

Four columns exist purely so sync can arrive later without migrating real
users' data:

| Column | Why |
|---|---|
| `id` | A UUID, **not** an autoincrement — two offline devices both minting "id 7" would be unmergeable |
| `updated_at` | Last-write-wins needs a watermark |
| `deleted_at` | A tombstone. A row that merely vanished would resurrect from the other device |
| `device_id` | Which device wrote it |

Journals are single-user and append-mostly — nobody else is editing your
Tuesday — so when sync does arrive, **last-write-wins per entry is sufficient**.
No CRDTs, no merge UI. That simplification is available only because of what
this app is.

---

## Directory layout

```
storage/                       Local persistence. Built and tested.
  src/commonMain/sqldelight/     Entry.sq, EntryTag.sq — schema and queries
  src/commonMain/kotlin/         JournalEntry, JournalRepository
  src/jvmMain/                   JDBC driver (tests, desktop)
  src/androidMain/               AndroidSqliteDriver
  src/iosMain/                   NativeSqliteDriver
  src/jvmTest/                   Repository tests

app/                           Compose UI.            NOT BUILT YET
iosApp/                        Xcode wrapper.         NOT BUILT YET
```

---

## Requirements

| Need | Version | For |
|---|---|---|
| **JDK** | 17+ | Gradle build |
| **Android SDK** | compileSdk 35 | Android target |
| **Xcode** | 26+ | iOS target; `SpeechAnalyzer` is iOS 26 only |
| **macOS** | 26+ | Running the speech spike natively |

Kotlin 2.2.20, Compose 1.9.0, AGP 8.7.3, SQLDelight 2.1.0 — all pinned in the
root `build.gradle.kts`. There is no version catalog; plugin versions are
declared once at the root and applied without versions in modules.

---

## Building

Clone and run the tests. Gradle downloads its own toolchain on first run, so
expect a few minutes once:

```bash
git clone https://github.com/heusserm/little-journal.git
cd little-journal
./gradlew :storage:jvmTest
```

Other useful targets:

```bash
./gradlew build                      # everything, all platforms
./gradlew :storage:jvmTest --info    # test output, when something fails
./gradlew :storage:compileKotlinIosArm64   # verify the iOS target compiles
```

The generated SQLDelight interfaces land in
`storage/build/generated/sqldelight/` — worth reading when a query signature
isn't what you expected. HTML test reports are at
`storage/build/reports/tests/jvmTest/index.html`.

### A trap that will bite you

`INSERT OR REPLACE` in `Entry.sq` looks like something to modernise into
`ON CONFLICT … DO UPDATE`. **Don't.** Upsert requires SQLite 3.24+, and
**Android API 26 ships SQLite 3.19** — this is a `minSdk` constraint, not a
dialect setting. Raising `minSdk` to 29 would buy upsert, window functions, and
better JSON, at the cost of Android 8 and 9 devices. That trade hasn't been made
yet.

The `INSERT OR REPLACE` is safe only because `JournalRepository.save` is the
single writer and always supplies every column. `REPLACE` deletes the old row
first, so a partial column list would silently blank `created_at`.

---

## Roadmap

1. ~~Speech spike — prove long-form on-device dictation holds up~~ **done**
2. ~~Local storage — schema, repository, tests~~ **done**
3. Dictation on a real device, in a real voice
4. Compose UI: capture screen, calendar, entry editor, search
5. Daily prompts, deterministic by date
6. Export / import to a user-owned file
7. Sync — only if it earns its way in

---

## Status

**Early.** The storage layer is built and covered by tests. There is no UI yet,
and no iOS or Android app target — dictation has so far been exercised only
through throwaway spikes, one a macOS CLI and one a bare SwiftUI app installed
by hand.

Nothing here is shippable. Nothing here is on the App Store.

---

## Privacy

No network calls. No accounts. No analytics, crash reporting, ads, or tracking
SDKs. Speech is transcribed on-device; audio is never uploaded. The language
model is downloaded and managed by the operating system, not bundled or
proxied by us.

---

## License

PolyForm Noncommercial License 1.0.0 — same terms as EncounterDeck. See
`LICENSE`.
