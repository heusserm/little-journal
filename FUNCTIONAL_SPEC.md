# Little Journal — Functional Specification

**What the app does today.** Not a roadmap: everything described here is
implemented and verified. Anything planned but absent is listed under
[Not implemented](#not-implemented).

Last verified against commit on 2026-08-20, with 92 passing tests.

---

## Purpose

A journal you talk to. Speak an entry, have it transcribed on the device, and
file it against the day it is *about* — which is not always the day you wrote
it.

Runs on iOS, Android and desktop from one shared codebase. Everything is stored
locally. No account, no server, no network calls.

---

## Core concepts

| Concept | Meaning |
|---|---|
| **Entry** | One piece of writing. A day can hold any number of them. |
| **Entry date** | The day the entry is *about*. Editable. The calendar groups on this. |
| **Written date** | When it was actually typed or spoken. Set once, never changes. |
| **Mood** | Optional 1–5 rating. |
| **Tags** | Optional lowercase labels, many per entry. |
| **Source** | Whether an entry was typed or dictated. Recorded automatically. |

Separating **entry date** from **written date** is the central idea: sitting
down on Thursday to write about Tuesday produces an entry dated Tuesday, while
still recording that it was written on Thursday.

---

## Screens

The app has three tabs — **Today**, **Calendar**, **Search** — plus two screens
reached by navigation: **Day** and **Editor**. The tab bar is visible on the
three tabs and hidden on Day and Editor.

### Today

The default screen.

- Shows today's date, e.g. *Wed, 19 Aug 2026*.
- A text box for writing. Its placeholder reads **"Talk, or type"** where
  dictation is available, otherwise **"What happened today?"**.
- **Talk / Stop** button — shown **only** where dictation is available.
- **Save** — disabled while the box is empty. Saves to today and clears the box.
- While dictating, unsettled text appears **dimmed below the box** and is never
  saved. Recognizer progress and errors appear as a short status line.
- **Today, N entries** — everything already written for today.
- **On this day** — entries from the same month and day in earlier years.
- Tapping any entry opens the Editor.

### Calendar

- A month grid, **Monday first**, with single-letter weekday headings.
- Header shows *‹ Month Year ›*. The arrows page a month at a time; tapping the
  month name returns to the current month.
- Days that have entries are **tinted**, with up to **three dots** indicating
  how many. Days with more than three still show three.
- Today's number is **bold and accented**.
- The grid scrolls, so the final week is always reachable.
- Tapping a day opens that Day.

### Day

- **‹ Calendar** returns to the grid; **New** starts an entry dated to this day.
- Shows the full date, then every entry for it, oldest first.
- An empty day says **"Nothing written for this day."** rather than showing
  blank space.

### Editor

Opened by tapping an entry, or by **New**.

- **‹ Back**, **Delete** (existing entries only), **Save** (disabled while the
  body is empty).
- **Entry date** stepper: *‹ date ›* moves a day at a time, **Today** jumps to
  today.
- When the date differs from the stored one, a note reads
  **"Moving from &lt;original date&gt;"**.
- Body text area.
- **Mood** — chips 1 to 5. Tapping the selected chip clears it.
- **Tags** — comma separated. Trimmed, lowercased, `#` stripped, duplicates
  removed.
- Saving returns to the Day the entry was opened from.

### Search

- A single field over the full text of every entry.
- Empty: *"Type to search everything you've written."*
- No results: *"No entries match &lt;query&gt;."*
- Results: a count, then matching entries newest first, each showing its date.
- Deleted entries never appear.

### Entry cards

Used on Today, Day and Search. Each shows the body (up to four lines,
ellipsised), the date where context needs it, and a metadata line combining
word count, **spoken** if dictated, mood, and tags as `#tag`.

---

## Dictation

**Available on iOS only.** On Android and desktop the Talk button is absent and
no dictation UI appears.

Pressing **Talk**:

1. Requests **microphone** access, then **speech recognition** access. These are
   separate permissions; both are required.
2. Downloads the language model if the device does not have it. Progress is
   shown. The model is managed by the operating system, not bundled, so this
   costs nothing in app size and happens at most once.
3. Streams audio to the on-device recognizer.

While listening, unsettled text is shown dimmed and replaced as the recognizer
revises it. Settled text is appended to the draft. **Nothing is uploaded**, and
audio is not retained.

### Spoken-time correction

The recognizer writes times badly — "six thirty" arrives as `6.30`, "eleven
forty five" as `1145`. Dictated text is corrected on save:

| Spoken | Stored |
|---|---|
| at 1145 | at 11:45 |
| around 6.30 | around 6:30 |

The rule is deliberately narrow. A number is only read as a time when a
preposition precedes it (*at, around, by, until, till, before, after, since,
from*), the hour is 1–12, and no unit follows. So **"around 1500 dollars" is
left alone**, and so is "I counted 1145 of them".

**Typed text is never altered.** The correction applies only to entries that
came from speech.

---

## Storage

Everything is stored in a local SQLite database.

| Platform | Location |
|---|---|
| iOS / Android | Platform app storage, `littlejournal.db` |
| Desktop | `journal.db` in the project directory; path printed at startup |

Deletes are **soft**: an entry is marked deleted and stops appearing anywhere,
but the row remains so that a future sync can distinguish "deleted" from "never
existed". Every entry also carries a unique id, a last-modified time and the id
of the device that wrote it. Nothing reads those yet — they exist so sync can be
added without migrating anyone's data.

---

## Privacy

No network calls. No account, analytics, crash reporting, advertising or
tracking. Speech is transcribed on the device and audio is never uploaded or
kept. The only permissions requested are microphone and speech recognition, and
only when dictation is first used.

---

## Not implemented

Present in the schema or planned, but with no behaviour behind them today:

- **Daily prompts** — `prompt_id` exists on every entry; nothing writes it.
- **Keeping audio** — `audio_path` exists; no recording is retained.
- **Sync** between devices, and any cloud storage.
- **Export / import.**
- **Encryption at rest.**
- **Full-text search** — search is a substring match, which is fast enough at
  journal scale.
- **Dictation on Android or desktop.**
- **Filtering or browsing by tag or mood** — both are stored and displayed, and
  the queries exist, but no screen uses them.

Nothing here has shipped to any app store.
