# Input History: Phase 3 Feature Fix Plan

**Project:** Fossify Keyboard fork (`SimpleKeyboardIME`, `MyKeyboardView`, `InputHistoryRecordingEngine`, `InputHistoryDatabase`).
**Prerequisite:** Phase 1 and Phase 2 are complete and approved (see `plans.md`).
**Audience:** Google AI Studio, with the full repository.

---

## 0. Ground rules

1. **Work in sub-phases (3.0 to 3.6). Stop after each one** and deliver its report. Do not start the next until the owner approves.
2. **Small, reviewable diffs.** Explain what each change fixes and which test proves it.
3. **Never log typed text.** Debug logs (`IHDBG`, debug builds only) may contain event names, lengths, indices, session ids, sequence numbers, timestamps.
4. **Privacy gates stay intact** (password fields, excluded apps, master toggle) and must run before anything is stored.
5. **Real-device verification is required.** JVM unit tests alone are not enough, because the original bugs were IME lifecycle problems. For every sub-phase, state clearly which tests were run **on a device** and which only as unit tests. If something was not run on a device, say "not device-verified".
6. If something cannot be confirmed, say **"unverified"**. Do not present guesses as facts.
7. **Never lose existing user history.** Any schema change needs a real Room `Migration` (see 3.1).

---

## Known state going in (from Phase 2)

Already fixed: the engine saves the session in `onFinishInput` before wiping; Room inserts are in one transaction with error logging; `onWordDeleted` is wired for multi-unit deletes.

Still broken (to be fixed here):

| Issue | Sub-phase |
|---|---|
| Sessions saved by `onFinishInput` are labelled `DISCARDED` unless the keyboard saw a submit, so a message sent with an app's own send button is mislabelled | 3.1 |
| Select-all delete / cut is labelled `SENT` | 3.1 |
| `fallbackToDestructiveMigration()` can wipe all history on any schema change | 3.1 |
| Suggestion tap, autocorrect on space, double-space period, Vietnamese Telex change the field without updating the engine (buffer corrupt, e.g. "helhello ") | 3.2 |
| Selection replace/delete handled as a single backspace | 3.2 |
| Text already in the field at session start is not known to the engine (`charCounter` starts at 0) | 3.3 |
| `liveCharIndices` is a tail-only stack; middle-of-text edits corrupt it | 3.3 |
| Backspacing to 0 then retyping splits one thought into two sessions | 3.4 |
| `onFinishInput` fires whenever the keyboard hides, so one message can be split into several sessions | 3.4 |
| Unsaved buffer is lost on process kill | 3.5 |
| Privacy gaps: OTP/card fields, no-personalized-learning flag, backup, storage encryption, retention, lock | 3.6 |

---

## 3.0 Device verification of the Phase 2 repairs (no new features)

Before building on Phase 2, confirm it works on a real phone. Run and report (pass/fail, with `IHDBG` excerpts, no text):

| # | Scenario | Expected |
|---|---|---|
| D1 | Type a message in a chat app, tap the **app's** send button | One session saved with the full text |
| D2 | Type, press the keyboard's own Enter/Send action | One session saved with the full text |
| D3 | Type, dismiss the keyboard with the back button, reopen it in the same field | Report exactly how many sessions result and what text each holds |
| D4 | Type, switch apps, switch back | Report the sessions created |
| D5 | Type and backspace to zero by hand | One session, deletions present |
| D6 | Check the history screen shows the saved sessions with text and strikethroughs | Text visible |

**Deliverable:** the pass/fail table, plus a list of any behavior that differs from the Phase 2 report. **Stop here for approval.**

---

## 3.1 Data safety and honest statuses

### 3.1.a Remove destructive migration risk
- Replace `fallbackToDestructiveMigration()` with explicit `Migration` objects.
- Export the Room schema (`exportSchema = true`) and add a migration test that opens an old-version database and verifies the data survives.

### 3.1.b Split "live state" from "final result"
Keep these as two separate concepts.

**Live state (in memory only):** `ACTIVE`, `PENDING_EMPTY`.

**Final result (stored in `status`):**

| Status | Meaning |
|---|---|
| `SENT` | The keyboard itself performed or observed a send/IME action (Send, Go, Done, Search) |
| `LEFT_FIELD` | Keyboard closed or field changed with text still present. Probably sent, but not confirmed. Replaces the current misleading `DISCARDED` for this case |
| `DISCARDED` | The user deliberately deleted the text (backspaced to zero by hand, or select-all then delete) |
| `CLEARED_UNKNOWN` | Field became empty and the reason is not known |
| `RECOVERED` | Reserved for crash recovery (3.5) |

Rules:
- Never store `SENT` without evidence. The UI may show `LEFT_FIELD` as "likely sent", but it is stored as `LEFT_FIELD`.
- Select-all delete and cut must stop producing `SENT`. If the engine saw a selection-delete, mark `DISCARDED`.
- Existing rows keep their old status. The UI must handle old and new values.

### 3.1.c Deliverable
Migration and test, the status mapping table, the diffs, and device tests D1 to D6 re-run. **Stop for approval.**

---

## 3.2 Capture sync: make every edit reach the engine

Introduce small, explicit engine events instead of letting the IME edit the field silently. Suggested API (names can change):

```
onReplaceBeforeCursor(deleteCount: Int, newText: String)   // suggestion tap, autocorrect, double-space, telex
onSelectionReplaced(selStart: Int, selEnd: Int, newText: String)   // typing over or deleting a selection
onComposingUpdate(text: String)   // see 3.3
```

Wire them at each site found in Phase 1 and 2:

| Site | Change |
|---|---|
| `onSuggestionClicked` | report the deleted prefix length and the committed word and trailing space |
| `handleSpaceAutoCorrection` | report the deleted typo, the corrected word, the trailing space |
| Autocorrect reversal on Backspace | report the delete and the restored typed word |
| Double-space period | report the deleted space(s) and the committed ". " |
| Vietnamese Telex `setComposingText` | report composing updates (do not append every intermediate state as final text) |
| Selected-text delete on Backspace | report as a selection deletion, not a single character |
| System paste / autofill (no IME call) | detect through selection or text-length change in `onUpdateSelection`, and record an "external insert" event of known length |

Rules:
- Keep the append-only history idea: the engine still records both what was authored and what was removed.
- Every replacement must adjust the buffer and `charCounter` consistently, and write the replaced text to the deletion ledger when it counts as a deletion.
- Count in code units consistently with how Android reports offsets, and handle surrogate pairs and emoji without drift. Add unit tests with emoji and non-Latin text.

### Deliverable
Table of all call sites with before/after behavior, unit tests for each event, and device tests: type "hel" then tap "hello"; autocorrect a typo; double-space; select-all then type; paste; emoji; Telex word. Confirm none produces duplicated or missing text. **Stop for approval.**

---

## 3.3 Real field text as the source of truth, and cursor-aware editing

### 3.3.a Initial text snapshot
- At `onStartInput`, read the existing field text (`getExtractedText` first; fall back to `getTextBeforeCursor` and `getTextAfterCursor` with a sane cap). Handle `null` results.
- Store it as `initial_text` (new column, via migration), separate from text typed in the session.
- Set `charCounter` and the shadow text from the real content.

### 3.3.b Cursor-aware shadow text
Replace the tail-only `liveCharIndices` stack with a **shadow copy of the field** plus the cursor/selection position, updated on each engine event and verified in `onUpdateSelection`:

- insert at cursor, delete before cursor, delete after cursor, replace selection;
- backspace removes the character before the cursor, wherever the cursor is;
- keep the append-only operation log for the deletion ledger and strikethrough display.

### 3.3.c Drift check
On `onUpdateSelection`, compare the shadow text length and cursor to the real values. If they differ:
- log `DRIFT` (lengths only),
- re-sync from the real field (do not guess),
- record the difference as an "unknown edit" event.

Large fields: read only a window around the cursor for drift checks, and the full text only at session start and close.

### 3.3.d Composing text
While text is composing (the word being typed is temporary), keep it in a separate pending piece, and commit it to the shadow text only on space, suggestion tap, or loss of focus. Do not record every intermediate state ("h", "he", "hel").

### Deliverable
Design note, diffs, unit tests, and device tests: edit the middle of a message; edit a message that started non-empty (restored draft, search field); backspace in the middle; type over a selection. **Stop for approval.**

---

## 3.4 Session lifecycle

### 3.4.a Delayed empty check
- When the field becomes empty, do **not** close immediately. Set `PENDING_EMPTY`, save a snapshot of the last full text first, and re-check after a short delay (start at 300 ms, make it a constant that is easy to change).
- If text returns (replace-a-word case), continue the same session.
- If still empty, close with the status from 3.1 (`DISCARDED` for a deliberate delete, `CLEARED_UNKNOWN` otherwise, `SENT` only on a real send action).
- Whitespace-only content counts as empty.

### 3.4.b Fix the Enter/Send timing
- On IME action or Enter, snapshot the text and mark a pending send **before** `performEditorAction`, then finalize on the next lifecycle event (the field clears or the keyboard hides). Do not check `currentLength == 0` while the text is still in the field.
- In multi-line fields, Enter is a newline, not a send. Use `imeOptions` and the action id to tell them apart.

### 3.4.c Session identity and resume
- Identify a session by package plus field (`EditorInfo.fieldId`, `inputType`, `imeOptions`, `fieldName` where available), not by package alone.
- When the keyboard hides (`onFinishInputView`) with text still present, mark the session `PAUSED` in memory. If the same field returns with the same text, **resume the same session** instead of creating a new one. If the field returns empty, finalize the old one as `LEFT_FIELD` and start a new one.
- Switching apps must seal the previous session with an immutable copy before the new one begins (this already works, keep it working).

### Deliverable
Diffs, unit tests with a fake clock for the delay, and device tests: replace-a-word; send with app button; send with Enter; keyboard hide and reshow in the same field; two apps alternately; multi-line field with Enter. **Stop for approval.**

---

## 3.5 Crash and kill safety

- Save a draft row (or update one) at checkpoints: every few seconds of typing activity, on field switch, and on `PENDING_EMPTY`. Do not write on every keystroke.
- Keep checkpoints cheap: one row update, not a full rewrite of the deletion ledger each time (append new deletion events in batches).
- On keyboard start, find unfinished draft rows older than the current session and mark them `RECOVERED` (or finalize as `LEFT_FIELD`).
- Make sure a draft row and its final row are the same row, so history does not show duplicates.

### Deliverable
Diffs, tests, and a device test: type, force-stop the keyboard process, reopen, and confirm the text is recoverable. **Stop for approval.**

---

## 3.6 Privacy and storage hardening

1. Skip recording for fields flagged `IME_FLAG_NO_PERSONALIZED_LEARNING` (incognito), and for OTP/one-time-code, credit-card, and similar fields where the input type or autofill hints identify them. State plainly that not all such fields can be detected.
2. Disable Android backup for the history database (`allowBackup` / `dataExtractionRules` / `fullBackupContent`), so the "never leaves the device" promise holds.
3. Encrypt the history database (for example SQLCipher with a key from the Android Keystore), or explain in a short note why not, and what the trade-offs are.
4. Add an optional auto-delete after N days.
5. Add an optional lock (device credential or biometric) on the history screen.
6. Double-check that no log line, crash report, or export contains typed text.

### Deliverable
Diffs, a short threat-model note (what is protected, what is not), and device tests. **Stop for approval.**

---

## Final report (after 3.6)

1. Updated hypothesis table (H1 to H7) with all items closed or explained.
2. Table of every input path and whether it now reaches the engine.
3. Full test matrix (D1 to D6 and every sub-phase test), marked device-verified or unit-only.
4. A list of known limitations that remain (for example, apps that never report field text, apps that clear the field late on send).
5. Instructions to strip the debug instrumentation.
