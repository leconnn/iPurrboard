# iPurrboard: plan v2 (phone feedback)

Four bugs found while testing on a real phone, plus three new features. Each item lists the cause
(for bugs), the files, the tasks and a test with the expected result. Bugs come first because two
of them affect every app. The build, install and test workflow, MuMu quirks and conventions in
[`plan.md`](plan.md) section 0 still apply. Record any change of approach in the decision log at
the end.

| # | Item | Type | Size |
|---|---|---|---|
| B1 | Taps blocked on the row above the keyboard | bug | S |
| B2 | Deleting is slow | bug | S |
| B3 | Remove the globe (keyboard switch) strip | change | S |
| B4 | Caps lock stays on after closing the keyboard or sending | bug | S |
| F1 | Appearance mode: Automatic / Light / Dark | feature | S |
| F2 | Number row (setting), always shown for passwords (setting) | feature | M |
| F3 | Copied items: suggestion chip, picked-out details, 1-hour history with pins | feature | L |
| F4 | Emoji suggestions in the bar | feature | M |
| F5 | Text-editing panel (setting) | feature | M |
| F6 | Keyboard height setting | feature | M |
| F7 | Feedback tuning: long-press delay, sound volume, vibration strength | feature | S |
| F8 | Manage learned words | feature | M |
| F9 | Block offensive words | feature | S |
| F10 | "Follow Gboard's layout" setting (key arrangement, iOS look) | feature | L |
| F11 | Toolbar in the bar (emoji, clipboard), collapsing to a dropdown, rearrangeable | feature | M–L |
| P | Speed and efficiency (Gboard-inspired) | optimisation | S–M |
| A | iOS animations (trackpad fade, key release, glass press, bar, one-handed, …) | polish | M |

Order: B4 → B1 → B3 → B2 (with P1, P2) → P3–P6 → A1–A8 → F1 → F7 → F2 → F6 → F10 → F9 → F4 → F8 → F3 → F11 → F5.
The bugs come first, then the cheap optimisations, then small settings. The layout changes (F2,
F6) are grouped, and the panels (F3, F5) come last because they share the new emoji-key menu
from B3. Each item is built, tested on MuMu **and on the phone** (B1 only reproduces where
touches pass through windows, see below), then committed after you approve it.

Gboard findings behind F3–F9 and P are summarised in the decision log (2026-11-07 Gboard study).
Only behaviour, settings text and flag names were studied; no Gboard code is used.

---

## B1: Taps blocked on the row above the keyboard

**Symptom:** while the keyboard is open, nothing in the band just above it can be tapped: the text
field, the send button, or anything else in that row.

**Cause:** `PopupOverlay` is a transparent `PopupWindow` that always covers the 62 pt `HEADROOM`
above the keys, plus the keyboard itself, so bubbles and accent panels can draw there. It's
non-touchable, so touches are meant to pass through it to the app. Since Android 12, however, the
system **blocks touches that pass through a non-touchable window owned by another app** unless that
window is nearly transparent (the "untrusted touch occlusion" rule; the window counts as opaque at
alpha 1.0, however little it draws). IME windows are exempt, but this popup is a sub-window, so the
app never receives touches in that band. That band is about 62 pt, roughly the height of a chat
input row.

**Fix:** only keep the overlay over the app while something is being drawn there.
- When no popup is visible, shrink the overlay to the keyboard's own bounds. It doesn't need to
  cover the app at all then.
- Grow it to include the headroom only while a preview bubble, accent panel or skin-tone panel is
  showing. `KeyboardView` tells the overlay when the first popup appears and when the last one
  ends: on touch down of a character key with Character Preview on, on long-press variants, and
  on the emoji tone panel. The 160 ms ease-out animation finishes before it shrinks.
- During a tap, the app's row is covered for well under a second, the same as iOS's own bubble.
- Use `PopupWindow.update(x, y, w, h)` rather than dismissing and re-showing, so there's no flicker.
- Fallback if resizing proves janky: set the overlay window's `alpha` to 0.8. That passes the
  occlusion rule, but it also fades the bubbles, so use it only if needed.

**Files:** `PopupOverlay.kt` (`setExpanded(Boolean)`, bounds for each state); `KeyboardView.kt`
(tracks "any popup visible" in `drawPopups` and the touch handlers, notifies the host);
`emoji/EmojiPanel.kt` (tone panel visibility).

**Test:**
- **Phone:** open a chat app, then tap the text field and the send button while the keyboard is
  open. Both respond. Character preview bubbles and accent panels still appear above the top row.
- **MuMu:** `adb logcat | grep -i "untrusted touch"`. Before the fix, tapping above the keys logs
  a block; after it, nothing is logged. Tap the field at its coordinates and the cursor moves.
- Glass appearance: there's still no blur over the app band, and bubbles draw correctly.

---

## B2: Deleting is slow

**Symptom:** backspace feels slow, both when tapping and when holding.

**Causes:**
1. **The hold is slow:** auto-repeat waits 500 ms, then deletes one character every 100 ms (10 per
   second) with no acceleration until word deletion starts after 20 repeats (2.5 s). iOS repeats
   noticeably faster.
2. **Each delete costs more than it should:** for every character the service sends two key
   events (`KEYCODE_DEL` down and up). The app then reports the change through
   `onUpdateSelection`, which re-reads the text for auto-shift (`getCursorCapsMode`) and asks for
   a suggestion refresh. On a real phone each of these is a cross-process call, so a held delete
   can fall behind and keep deleting after release.

**Fix:**
- **Timing:** first repeat after 400 ms, then every 60 ms, then whole words after about 1.2 s of
  repeats (`WORD_DELETE_AFTER` about 20 at 60 ms). Put the constants in one place so they're easy
  to tune on the phone.
- **Deleting a character:** use `deleteSurroundingText` instead of key events when there's no
  selection:
  - remove a whole grapheme (emoji, flags, ZWJ sequences), not half a surrogate pair. Find the
    boundary with `BreakIterator.getCharacterInstance()` over `getTextBeforeCursor(16)`.
  - keep `sendKeyEvent(DEL)` only when the app reports no text (`getTextBeforeCursor` returns null
    or empty): some terminals and games need real key events.
- **Fewer calls per delete:** while the delete key is held, skip the per-repeat suggestion refresh
  and do one refresh after release. The auto-shift check stays, because iOS turns shift on as soon
  as you delete back to the start of a sentence.
- **Batching:** wrap each delete in `beginBatchEdit` / `endBatchEdit`, so the app sends one
  selection update instead of several.

**Files:** `KeyboardView.kt` (repeat timing), `IosKeyboardService.kt` (`onDelete`),
`text/WordComposer.kt` (no refresh during a hold).

**Test:**
- **MuMu:** type 40 characters and hold delete for 1 s. Count the characters removed: it should be
  about 10 after the change, against about 5 before. Release, and nothing more is deleted.
- **Emoji:** type 👍🏽 and 🇵🇭, then delete once each. Each disappears whole.
- **Frames:** `gfxinfo` while holding delete shows no janky frames.
- **Phone:** tapping delete quickly 10 times removes exactly 10 characters, without lag.

---

## B3: Remove the globe strip

**Change:** remove the 36 pt strip below the keys that holds the globe (switch keyboard) button.
The keyboard gets 36 pt shorter. Android's navigation bar already has its own keyboard-switch
button.

**Emoji-key menu (new, replaces the globe):** long-pressing the emoji key opens a small iOS-style
menu, like the menu iOS shows when you hold its globe key. You slide to an item and release to
choose it (the same gesture as accent panels). The items are:
- **Clipboard:** F3, shown when Show Copied Items is on.
- **Text Editing:** F5, shown when its setting is on.
- **Keyboard Settings:** opens SettingsActivity.
- **Switch Keyboard:** opens the system picker.

Until F3 and F5 exist, the menu has just the last two items.

**Files:**
- `KeyboardView.kt`:
  - remove `stripPt`, `globeKey`, `drawStrip` and the strip touch handling
  - the view height becomes `barPt + Geometry.HEIGHT`, plus the bottom inset
- `IosKeyboardService.kt`: `onGlobe` stays only for the long-press emoji path. Remove it if
  nothing else uses it.
- `plan.md` facts: on MuMu the keys move down 86 px. Update `TOP` in `tools/adb/kb.sh` and the
  emoji panel coordinates in plan.md.

**Test:**
- **Screenshot:** nothing is drawn below the bottom row, and the bottom inset still clears gesture
  navigation.
- **Emoji key:** a tap opens emoji; a long-press opens the menu. Sliding to "Switch Keyboard" and
  releasing opens the picker, and releasing outside the menu does nothing.
- The regression loop passes with the new `TOP`.

---

## B4: Caps lock stays on

**Symptom:** after caps lock is turned on, it stays on when the keyboard is closed, when it moves
to another field, and after a message is sent.

**Cause:** the shift state lives in `KeyboardView`. `onStartInputView` doesn't reset it, and
`updateAutoShift` deliberately leaves `LOCKED` alone. So caps lock survives everything except a
tap on shift.

**Fix (iOS behaviour):** caps lock ends when the keyboard is dismissed or the field changes.
- **`onStartInputView`** with `restarting == false` (a new field or the keyboard reopened): set
  shift to `OFF`, clear `manualShift`, then run `updateAutoShift` as now.
- **`onFinishInputView`:** the same reset, so the keyboard doesn't flash caps the next time it
  opens.
- **`onReturn`, when the return key performs an editor action** (send, go, search, done):
  - reset shift.
  - many chat apps clear the field without restarting input, and the reset covers that case too.
  - a plain newline (return with no action) keeps caps lock, as on iOS.

**Files:** `IosKeyboardService.kt`.

**Test (MuMu):**
- **Field change:** turn caps lock on (shift twice), type `AB`, then focus another field. It types
  in lowercase with sentence auto-caps.
- **Send:** in an app with a send action, turn caps lock on, type, then tap send. Caps lock is off.
- **Newline:** in a multi-line field, caps lock and return give a newline with caps lock still on.

---

## F1: Appearance mode (Automatic / Light / Dark)

**Goal:** a setting that chooses the keyboard's colours independently of the system:
- **Automatic** (the default) follows the system dark mode, as now.
- **Light** and **Dark** force a mode.

This works with both the Classic and Liquid Glass styles.

**Design:**
- **New pref `themeMode`:** enum `AUTO` / `LIGHT` / `DARK`.
- **Settings:** in the existing *Appearance* section, a row "Mode: Automatic ›" that opens a
  three-way picker, using the same dialog pattern as One-Handed Keyboard.
- **`KeyboardTheme.forContext`:** takes the mode, and `night` = system when `AUTO`, else the
  forced value. The glass tint and blur rules don't change.
- **Settings screen:** follow the same pref (`AppCompatDelegate` isn't available since there's no
  AndroidX). Set `uiMode` with `createConfigurationContext` in `SettingsActivity.attachBaseContext`
  and recreate the activity when the pref changes, so the in-app preview matches the keyboard.
- **Live switch:** the service's prefs listener already calls `applyAppearance()`, which picks up
  the new mode immediately. `onConfigurationChanged` matters only when the mode is `AUTO`.

**Files:** `Prefs.kt`, `Theme.kt`, `IosKeyboardService.kt`, `SettingsActivity.kt`,
`DebugPrefsReceiver.kt` (accepts the enum string).

**Test:** with the system set to light, choose Dark and the keyboard is dark; choose Light and it's
light; choose Automatic and it's light. Flip the system to dark and Automatic follows. Take
screenshots of Classic and Glass in each mode.

---

## F2: Number row

**Goal:** an optional row of `1 2 3 4 5 6 7 8 9 0` above the letters (iOS has none; Gboard has one
as a setting). Off by default.

**Design:**
- **New pref `numberRow`** (default false). Settings: a switch under *General*, after Character
  Preview, with the footer "Shows a row of numbers above the letters."
- **New pref `numberRowPasswords`** (default true, Gboard's "Number row for passwords"). In
  password fields (`FieldTraits.isPassword`) the number row shows even when `numberRow` is off.
  It's shown as a second switch, "Number Row for Passwords", under Number Row.
- **Geometry:** the same 10-key pitch as the QWERTY top row (37.5 pt frames, keys inset 2 pt).
  - **Row height:** 46 pt frames would make the keyboard 54 pt taller, so use a shorter row:
    40 pt frame, 36 pt keycap. The keyboard grows by 44 pt.
  - **Labels:** 21 pt, the size of the 123-plane digits.
  - **Implementation:** `Geometry` gains a `topRowPt` offset (0 or 44) that every plane's key
    rects are shifted by. `Layouts.keys(plane)` prepends the number row when it's enabled.
  - **Which planes:** only the letters plane shows it. The 123 plane already starts with digits,
    as Gboard does.
- **Behaviour:**
  - digits type plainly and don't end the composing word. That matches iOS's 123 plane, where a
    digit ends the word.
  - long-press variants: `1` → `¹ ½ ⅓ ¼`, `2` → `² ⅔`, `3` → `³ ¾`, `0` → `° ⁰`. These are optional
    and use the existing accent panel.
  - character preview bubbles work as on any character key.
- **What the offset must reach:** slide to type and the suggestion bar are unaffected as long as
  the decoder's layout frame (`layoutPoint`) subtracts the offset. The one-handed scaling,
  `PopupOverlay` bounds, the emoji panel height and the `kb.sh` coordinates must all respect it.
- **Emoji panel:** stays at the old height. It fills the keys area, which is now taller; the grid
  gets taller rather than gaining a row of cells.

**Files:** `Prefs.kt`, `SettingsActivity.kt`, `KeyboardLayout.kt` (number row keys, `topRowPt`),
`KeyboardView.kt` (height, layout offset, `layoutPoint`), `tools/adb/kb.sh` (row helpers read the
offset).

**Test:**
- **Passwords:** with `numberRow` off, a password field still shows the row; turn
  `numberRowPasswords` off and it's gone.
- **On:** the row appears and typing `abc123` gives `abc123`. Swiping `hello` still decodes. Letter
  rows are hit exactly with the new offsets: run the regression loop with `numberRow=true`.
- **Off:** the keyboard is the same height as before (screenshot diff).
- **Other modes:** one-handed with the number row on looks right; Glass with the number row on
  looks right.

---


## F3: Copied items (chip, picked-out details, clipboard history)

**Goal:**
- **Chip:** when you've recently copied something, the suggestion bar offers it as a chip, and
  one tap pastes it.
- **Details:** if the copy contains an address, phone number, email, link or one-time code, the
  bar can offer just that part.
- **History:** a clipboard panel keeps recent copies for 1 hour, and pinned items are kept until
  you unpin them. This is Gboard's model.

### What the Gboard study showed (observed in its settings text)
- "Show recently copied text and images in suggestions bar".
- "Show addresses, phone numbers, and other items pulled from recently copied text". The labels
  include Address, Phone number, Post Code and Formatted Address.
- "Keeping them for 1 hour"; "Touch and hold a clip to pin it. Unpinned clips will be deleted
  after 1 hour."; "Keep clipboard history for" (the retention period is a setting).
- **Unknown:** how long the chip stays offered. The flag exists, but its default value is in code
  that wasn't read. Default here: **60 seconds**, as one constant to tune. The chip also goes
  once it's used or once you start typing a word.

### Design
- **`ClipboardWatcher`:**
  - **Source:** listens with `ClipboardManager.addPrimaryClipChangedListener` and also reads the
    clipboard in `onStartInputView`. An IME with input focus may read the clipboard on Android 10+.
  - **What it keeps:** each new clip becomes an item with its text, the time it was copied and
    `pinned=false`.
  - **What it skips:** clips marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`, Android 13+),
    clips while the field is a password field, and our own pastes.
- **Storage:**
  - **Pinned items:** stored in the existing SQLite database in device-protected storage, in a
    new table `clips`.
  - **Unpinned items:** kept in memory and in the same table with a timestamp; anything older
    than the retention period (default 1 hour) is purged on every read.
  - **Text only;** images are out of scope.
- **Chip:** for a fresh clip (younger than 60 s and not yet used), with no word being composed, in
  a field that allows suggestions:
  - **Look:** the bar shows one wide chip: a clipboard glyph, then the text on one line ending
    in "…".
  - **Detail chips:** if detail extraction finds items, up to 2 extra chips appear beside it, such
    as "📞 0917 123 4567" or "🔢 482913".
  - **Tap:** inserts that chip's full text and marks the clip as used.
- **Detail extraction** (`text/ClipEntities.kt`, pure Kotlin, JVM-tested). Pattern-based and
  offline:
  - **Links** and **emails.**
  - **Phone numbers:** 7–15 digits with common separators and an optional `+`.
  - **One-time codes:** 4–8 digits near "code", "OTP", "verification" or "PIN".
  - **Addresses:** a simple heuristic: a line starting with a house number followed by a street
    word (St, Ave, Rd, Street, Blvd, Lane…), plus an optional postcode line. Kept conservative so
    it rarely triggers falsely.
- **Clipboard panel** (opened from the emoji-key menu, B3). It takes the keys area like the
  emoji panel and is drawn by `KeyboardView`:
  - **Layout:** a two-column grid of clip cards with the newest first and pinned items on top,
    an "ABC" button to return, and a header reading "Clipboard".
  - **Actions:** tap a card to paste it; long-press to get Pin/Unpin and Delete; a "Clear"
    button removes everything unpinned.
  - **Empty state:** "No items copied within the last hour".
- **Settings, section *Clipboard*:**
  - **Show Copied Items** (default on): turns the chip and detail chips on or off.
  - **Clipboard History** (default on): the panel and storage. Turning it off deletes unpinned
    items.
  - **Keep History For:** 1 hour (default), 1 day or 1 week. Pinned items are never purged.

**Files:** new `text/ClipboardWatcher.kt`, new `text/ClipEntities.kt`, new `ClipboardPanel.kt`;
also `text/UserWords.kt` (the `clips` table), `IosKeyboardService.kt`, `KeyboardView.kt` (chips
and panel), `text/WordComposer.kt` (bar priority), `Prefs.kt` and `SettingsActivity.kt`.

**Test:**
- **JVM `ClipEntitiesTest`:**
  - a URL in a sentence
  - an email
  - `+63 917 123 4567` and `(555) 123-4567`
  - `"Your verification code is 482913"` → `482913`
  - a street address
  - a plain sentence → no entities
- **JVM:** retention purge and chip freshness, with an injected clock.
- **MuMu:**
  - copy in the settings field, then focus another field: the chip appears and a tap pastes it.
  - copy a text containing a code: a code chip appears.
  - open the panel from the emoji menu; pin, delete and clear all work.
  - nothing shows in a password field or with the setting off.

---

## F4: Emoji suggestions

**Goal:** typing a word with a matching emoji ("pizza", "love", "happy") offers that emoji in the
suggestion bar, as iOS does.

**Design:**
- **Data:** Unicode CLDR emoji annotations for English (`annotations/en.xml` plus
  `annotationsDerived/en.xml`, Unicode licence). A new generator, `tools/emoji/gen_emoji_keywords.py`,
  writes `assets/emoji_keywords.tsv` (keyword → up to 3 emoji). It's restricted to emoji present
  in `emoji.tsv`.
- **Bar:** when the word being typed exactly matches a keyword, the **right** slot shows the emoji.
  A correction in the centre still takes priority.
- **Tap:** replaces the word with the emoji, like iOS. A backspace right after it restores the word
  (the same undo pattern as autocorrect).
- **Skin tones:** uses your saved tone choice for that emoji (Prefs `emojiTones`).
- **Recents:** an inserted emoji goes into Frequently Used.
- **Setting:** "Emoji Suggestions" under *General* (default on).

**Files:** new generator and asset, new `text/EmojiKeywords.kt` (pure, JVM-tested),
`text/Predictor.kt`, `text/WordComposer.kt`, `Prefs.kt` and `SettingsActivity.kt`.

**Test:**
- **JVM:** "pizza" → 🍕; "the" → nothing.
- **MuMu:** type `pizza` and 🍕 shows in the right slot. A tap replaces the word, and a backspace
  brings "pizza" back.

---

## F5: Text-editing panel (setting)

**Goal:** an optional panel for editing without touching the app: arrow keys, select, select all,
cut, copy, paste, and Home/End.

**Design:**
- **Setting:** "Text Editing Panel" under *General* (default off). When it's on, "Text Editing"
  appears in the emoji-key menu (B3).
- **Panel:** takes the keys area, like the emoji panel, in iOS styling (function-key colour,
  glyphs drawn in `Glyphs`):
  - a 4-way arrow cluster, plus Home and End
  - a **Select** toggle: while it's on, the arrows extend the selection (`setSelection` from the
    cursor position tracked in `onUpdateSelection`)
  - Select All, Cut, Copy and Paste, through `performContextMenuAction` with the
    `android.R.id.selectAll` / `cut` / `copy` / `paste` ids
  - Delete, and an "ABC" button to return
  - arrows auto-repeat while held, using the B2 repeat timing
- **Field rules:** in password fields Copy and Cut are disabled.

**Files:** new `EditingPanel.kt`, `KeyboardView.kt`, `IosKeyboardService.kt` (actions), `Theme.kt`
(glyphs), `Prefs.kt` and `SettingsActivity.kt`.

**Test (MuMu):** with text in a field:
- **Movement:** Left×3 moves the cursor; Select plus Left×2 selects 2 characters.
- **Clipboard actions:** Copy, then End, then Paste duplicates them; Select All then Cut empties
  the field.
- **Setting off:** the menu item is hidden.

---

## F6: Keyboard height

**Goal:** a setting to make the keys shorter or taller (Gboard has a height slider).

**Design:**
- **Pref `keyboardHeight`:** Short (0.88), Default (1.0), Tall (1.12) or Extra Tall (1.24).
  Settings: *Appearance* → "Keyboard Height", a picker.
- **Vertical scale only:** key height, row pitch and the number row scale; widths stay iOS-exact.
  The suggestion bar keeps its height.
- **One shared factor:** `Geometry` gets a `vScale` that every y coordinate goes through. Touch
  hit-testing, `layoutPoint` (swipe decoding), the popup positions and bubble size, and the
  emoji/clipboard/editing panels all use it. Do this together with F2's `topRowPt`, so there's
  one vertical transform rather than two.

**Files:** `KeyboardLayout.kt`, `KeyboardView.kt`, `PopupOverlay.kt`, `Prefs.kt`,
`SettingsActivity.kt` and `tools/adb/kb.sh` (row helpers use the scale).

**Test:**
- **Heights:** each setting gives the expected screenshot height. Swiping `hello` still decodes at
  Short and Extra Tall.
- **Regression:** the loop passes at Tall.

---

## F7: Feedback tuning

**Goal:** fine control over touch feedback (Gboard: long-press delay, sound volume, vibration
strength).

**Design** (all under *Keyboard Feedback*):
- **Long-Press Delay:** Short (250 ms), Default (400 ms) or Long (600 ms). Used for accents, the
  trackpad and the emoji-key menu.
- **Sound Volume:** a slider from 0–100 % (default 100), applied as the `SoundPool` volume and
  to the system-click fallback (`AudioManager.playSoundEffect(…, volume)`).
- **Vibration:** Light, Medium (default) or Strong:
  - **Effects:** `VibrationEffect.EFFECT_TICK`, `EFFECT_CLICK` and `EFFECT_HEAVY_CLICK`
    (API 29+), otherwise `createOneShot` at 15, 25 or 35 ms.
  - **Delivery:** uses `Vibrator` directly instead of `performHapticFeedback`, so the strength
    applies even when the system's touch haptics are off. The Haptic switch still turns it off.

**Files:** `Prefs.kt`, `SettingsActivity.kt`, `KeySounds.kt`, `KeyboardView.kt` (delay), and
`IosKeyboardService.kt` (vibration).

**Test (MuMu):**
- **Long-press delay:** at Short, the accent panel opens after a 300 ms hold; at Long it doesn't
  until 600 ms.
- **Volume:** 0 % gives silence.
- **Vibration:** checked on the phone, since MuMu has no vibration.

---

## F8: Manage learned words

**Goal:** see and edit what the keyboard has learned (Gboard: "Delete learned words and data").

**Design:**
- **Settings, *Learned Words* screen (using `IosList`):**
  - **Search and list:** a search field over the list of learned words. Each row shows the word
    and how many times you've used it.
  - **Delete:** tap ✕ to delete that word and its word pairs.
- **Corrections You Undid:** a second list of the rejected corrections. Removing one lets the
  autocorrector fix that word again.
- **Add Word:** adds a word manually, so it's never autocorrected.
- **Reset Learned Data:** a confirmation dialog, then it clears learned words, word pairs and
  rejections. Text replacements and settings are kept.
- **Implementation:** `UserWords` gains `list(query)`, `delete(word)`, `listRejected()`,
  `unreject(word)` and `clearAll()`. The keyboard re-reads them through a Prefs generation
  counter.

**Files:** `text/UserWords.kt`, new `LearnedWordsActivity.kt`, `SettingsActivity.kt`.

**Test (MuMu):**
- type "zorbly" twice and it's learned; delete it in the screen and it's no longer suggested.
- undo an autocorrect, then remove it from Corrections You Undid, and it's corrected again.
- Reset clears everything.

---

## F9: Block offensive words

**Goal:** keep offensive words out of predictions and corrections (Gboard: "Block offensive words
from showing").

**Design:**
- **Word list:** a word list for English (the LDNOOBW list, CC BY 4.0, credited in the README) →
  `assets/dict/blocked_en.txt`.
- **Pref `blockOffensive`:** default on, as in Gboard. *General* → "Block Offensive Words".
- **Effect when on:**
  - a blocked word is never offered as a prediction, completion, next-word prediction, swipe
    result, or correction target
  - typing it literally still works, and the literal slot still shows it
  - if you insert it yourself 2 or more times, it is learned like any word but still not
    predicted. Gboard's exact rule here is unknown, so this is the stricter choice.
- **Implementation:** a `HashSet` filter in `Predictor` and `SwipeDecoder` result filtering;
  `Corrector` skips blocked candidates.

**Files:** asset, `text/Predictor.kt`, `text/Corrector.kt`, `text/SwipeDecoder.kt`, `Prefs.kt`,
`SettingsActivity.kt` and `README.md` credits.

**Test:** JVM: with the filter, a blocked word is never a completion or correction; without it,
it can be. MuMu: a blocked prefix shows no blocked completion.

---

## P: Speed and efficiency (Gboard-inspired)

Chosen from the Gboard study; each one is measured before and after.
- **P1, own copy of the text before the cursor (with B2):**
  - **Why:** Gboard bounds its calls to the app (`wait_ic_call_timeout`,
    `get_text_ic_timeout_tolerant_times`), so slow apps can't stall it.
  - **What:** keep a local mirror of the ~64 characters before the cursor, updated by our own
    commits and deletes. Re-read it from the app only when the selection moves unexpectedly
    (`onUpdateSelection`). Auto-shift, the curly-quote caps check and `WordComposer` read the
    mirror.
  - **Effect:** fewer cross-process calls per key, which matters most on slow apps.
- **P2, adjustable key repeat (with B2):** a single place for the repeat delay and interval, as
  Gboard's `touch_action_repeat_start_delay` / `_interval` do.
- **P3, warm the emoji panel:** after the first keyboard show, load `emoji.tsv` and the Apple emoji
  index on the worker thread so the first emoji-panel open doesn't hitch (Gboard:
  `emoji_keyboard_prewarm_enabled`).
- **P4, SQLite WAL for learned words:** `enableWriteAheadLogging()` on `UserWords`, so
  learning-word writes don't block prediction reads (Gboard: `enable_training_cache_db_wal`).
- **P5, available before first unlock:** add `android:directBootAware="true"` to the IME service
  (Gboard declares it). Prefs and the database are already in device-protected storage. Test: reboot
  MuMu and the keyboard works on the lock-screen PIN field before unlock.
- **P6, precompile after install:** Gboard ships an ART baseline profile. A sideloaded app without
  AndroidX ProfileInstaller doesn't get one, so:
  - add a `compile_app` helper to `kb.sh` that runs `cmd package compile -m speed -f <pkg>`
  - document it in the README
  - measure the cold first-open time before and after.

**Not chosen, unless measurements show a need:**
- **Memory-mapped binary dictionary:** do it only if the dictionary load exceeds about 300 ms on
  the phone.
- **Rendering the static keyboard to a bitmap:** frames are already 0.55 % janky.
- **Caching next-word predictions:** predictions are already off the main thread.

---

## F10: "Follow Gboard's Layout" setting (key arrangement only)

**Goal:** an option to use Gboard's key arrangement while keeping the iOS look (keycaps, fonts,
colours, bubbles, Liquid Glass). It covers Gboard's bottom row, key sizes, number and symbol
pages, and long-press symbol hints on the letter keys.

**Source:** Gboard's layouts are decodable from the APK. They're compiled XML in its own layout
format:
- **Structure:** `framework` → `keyboard` → `if`/`else` blocks on `device`, `keyboard_mode` and
  `enable_number_row` → a `view` of `type="body"` that points to an Android layout and a
  `softkeys` definition.
- **Key sizes:** the Android layouts hold the rows and the key widths (weights); `softkeys` holds
  each key's labels and long-press symbols.
- **Reading them:** `aapt2 dump xmltree` reads them, and the key-size values come from
  `aapt2 dump resources`.

Only measured numbers and key arrangements go into our code, the same as with the iOS layout
file. No Gboard files are copied.

**Steps:**
1. **Decoder:** `tools/gboard/gblayout.py` follows the includes for the phone, normal-mode,
   English QWERTY keyboard. For the letters, numbers and symbols pages (with and without the
   number row) it prints rows, key widths in points (scaled to our 375 pt width), row heights,
   labels and long-press symbols.
2. **Check against screenshots:** screenshots of Gboard on MuMu confirm the result (that means
   installing it there temporarily and switching back afterwards; it's an arm64-only APK, so this
   depends on MuMu's ARM translation).
3. **Layout data:**
   - `KeyboardLayout.kt` gains a second layout set, `Layouts.gboard`, next to the iOS one.
   - `Geometry` becomes per-layout: row positions, key height, and the bottom row.
   - The bottom row becomes `?123 | , | emoji | space | . | return`, using Gboard's widths.
4. **Symbol hints:** small grey labels in the top-right corner of letter keys (q→1 when the
   number row is off, otherwise Gboard's symbol hints). On long-press, the hinted symbol is the
   first item in the accent panel.
5. **One source of key positions:** `KeyProximity` (autocorrect) and `SwipeDecoder` templates take
   their key centres from the active layout instead of fixed iOS numbers. Templates are rebuilt
   when the layout changes, and the cache is cleared.
6. **Number row (F2):** in Gboard layout it uses Gboard's number-row dimensions.
7. **Setting:** *Appearance* → "Key Layout: iOS / Gboard" (default iOS). Switching applies live.

**Files:** new `tools/gboard/gblayout.py`; `KeyboardLayout.kt`, `KeyboardView.kt`,
`text/KeyProximity.kt`, `text/SwipeDecoder.kt`, `Prefs.kt`, `SettingsActivity.kt` and
`tools/adb/kb.sh` (layout-aware coordinates).

**Test:**
- **Screenshots:** with the Gboard layout, key edges match Gboard's within 1 pt, compared on a
  screenshot overlay.
- **Typing:** typing and swiping `hello` work in both layouts; the regression loop passes in both
  (with layout-aware `kb.sh`).
- **JVM:** the swipe decoder's accuracy on synthetic paths is ≥ 90 % top-1 using Gboard key
  centres.

**Order:** after F2 and F6, because it reuses their vertical-geometry refactor.

---

## F11: Toolbar in the suggestion bar (emoji, clipboard), collapsing to a dropdown

**Goal:** when there are no word suggestions in the bar, it shows buttons instead: **Emoji** and
**Clipboard**, plus **Text Editing** when F5 is on. When suggestions appear, the buttons collapse
into a single dropdown button at the left edge. Tapping it slides the buttons back over the
suggestions, and tapping it again (or typing) collapses them.

**Design:**
- **Bar states:**
  - **Idle** (no composing word, no predictions, no copied chip): the buttons sit left-aligned in
    the bar, about 44 pt wide, using iOS glyphs.
  - **Suggesting:** a 32 pt dropdown button (a chevron) at the left edge, and the three
    suggestion slots shift right to share the rest of the width.
  - **Expanded:** the buttons slide in over the suggestions from the left (~200 ms, A9) and the
    chevron rotates. The panel stays open until you tap a button, tap the chevron again, or type
    a key.
- **Priority with F3:** the copied-item chip counts as a suggestion. When it shows, the buttons
  collapse to the chevron.
- **With Predictive Text off:** the bar stays visible (44 pt) as a toolbar-only row, so the
  buttons are always reachable. It's hidden only when the toolbar setting is also off.
- **Actions:**
  - **Emoji:** opens the emoji panel.
  - **Clipboard:** opens the F3 clipboard panel. It's hidden when Clipboard History is off.
  - **Text Editing:** opens the F5 panel. It's shown only when that setting is on.
- **Setting:** *General* → "Toolbar" (default **on** with the Gboard layout and **off** with the iOS
  layout, applied when the layout is chosen; you can change it either way afterwards). The
  emoji-key menu (B3) keeps working whatever this is set to.
- **Field rules:** in password fields, Clipboard and Text Editing are hidden.
- **Rearranging** (Gboard has this: "Drag to reorganize, or place in your shortcut top bar"):
  - **Pref `toolbarOrder`:** an ordered list of button ids (`emoji`, `clipboard`, `editing`), with
    a visible/hidden flag for each. Default: emoji, clipboard, editing. New buttons added in later
    versions go to the end.
  - **Settings → Toolbar → "Toolbar Buttons":** a list with a drag handle (≡) on each row and a
    switch per row to show or hide that button. There's no AndroidX, so dragging is a small custom
    touch handler on the `IosList` rows: lift the row, follow the finger, and the other rows slide
    aside. Up/down arrow buttons on each row act as the accessible fallback.
  - **In the bar:** long-press a toolbar button to enter rearrange mode. The buttons wiggle
    slightly, like iOS home-screen editing, and you drag one sideways to a new spot. Releasing
    saves the order; tapping elsewhere or typing ends the mode.
  - **Chevron panel:** the expanded dropdown uses the same order.
  - **Hidden buttons:** a hidden button stays reachable from the emoji-key menu (B3).

**Files:** `KeyboardView.kt` (bar states, buttons, chevron, hit-testing, rearrange mode),
`ui/IosList.kt` (draggable rows), new `ToolbarSettingsActivity.kt` (or a screen inside
SettingsActivity), `text/WordComposer.kt`
(tells whether the bar has suggestions), `IosKeyboardService.kt` (actions), `Theme.kt` (glyphs:
emoji, clipboard, editing, chevron), `Prefs.kt` and `SettingsActivity.kt`.

**Test (MuMu):**
- **Idle:** focus an empty field (after a sentence end, so no next-word predictions show) and
  the buttons are visible. Tapping Emoji opens the emoji panel.
- **Suggesting:** type `hel` and the buttons collapse into the chevron. Tap the chevron and the
  buttons slide over the suggestions; type `l` and they collapse again.
- **Predictive Text off:** the bar shows the buttons only.
- **Copy something:** the chip shows and the buttons collapse.
- **Password field:** only Emoji is shown.
- **Reorder in settings:** drag Clipboard above Emoji and the bar shows Clipboard first. Hide
  Emoji and it's gone from the bar but still in the emoji-key menu.
- **Reorder in the bar:** long-press Emoji and drag it right of Clipboard; the order persists
  after the keyboard closes and reopens.

**Order:** with F3, right after the clipboard panel exists, because it needs the panel to open.

---

## A: iOS animations

**Goal:** add the small iOS motion touches the keyboard is missing. Each one is short, matched to
iOS by eye from screen recordings, and skipped when Android's animator duration scale is 0
("Remove animations"). All are driven by `postInvalidateOnAnimation` with the existing
uptime-based progress pattern (as in `POPUP_SPRING_MS`), so no Animator objects are created per
frame.

| # | Animation | iOS behaviour (target) | Now |
|---|---|---|---|
| A1 | Trackpad mode | Key labels fade out (~150 ms) to blank keys when the space bar is held, then fade back in on release | instant |
| A2 | Function-key release | Shift, delete, 123 and return fade from the pressed colour back to normal (~100 ms) | snaps |
| A3 | Liquid Glass press | The key swells about 4 % and brightens with a soft glow, easing back on release (spring, ~180 ms) | brightens only |
| A4 | Suggestion bar | A short cross-fade (~120 ms) when slot words change | instant |
| A5 | One-handed | The keyboard slides and shrinks to the side (~250 ms ease-in-out), and back | instant |
| A6 | Emoji categories | Tapping a category glides to that section (`OverScroller.startScroll`, ~300 ms) | check; jumps if not |
| A7 | Shift / caps lock | The arrow fills in and the caps-lock underline draws in with a cross-fade (~100 ms) | instant |
| A8 | Bubble release | The preview bubble shrinks away and fades (~80 ms) after release | disappears |
| A9 | New UI | Emoji-key menu (B3): scale and fade in from the key. Clipboard and editing panels (F3, F5): cross-fade with the keys. Copied chip (F3): fade in. | new |

**Files:** mostly `KeyboardView.kt` (a small `Anim` helper: start time, duration, easing and
`progress()`), plus `emoji/EmojiPanel.kt` (A6) and the new panels (A9).

**Test:**
- **Frames:** `gfxinfo` while exercising each animation shows no janky frames.
- **Remove animations:** with `settings put global animator_duration_scale 0`, everything is
  instant.
- **Looks:** screen recordings compared side by side with iOS reference clips.

A1–A8 are done after the bugs (they touch the same drawing code as B1); A9 is done with each new
panel.

---

## Checklist
All built and checked on MuMu on 2026-11-07, not yet committed (awaiting inspection on MuMu).
- [x] B4 Caps lock reset
- [x] B1 Touch pass-through above the keyboard
- [x] B3 Globe strip removed, emoji-key menu
- [x] B2 Faster delete (+ P1, P2)
- [x] P3–P6 Emoji prewarm, WAL, direct boot, precompile
- [x] A1–A8 iOS animations (A9: menu spring and toolbar slide only, see log)
- [x] F1 Appearance mode
- [x] F7 Feedback tuning (vibration strength not checkable on MuMu)
- [x] F2 Number row (+ passwords)
- [x] F6 Keyboard height
- [x] F9 Block offensive words
- [x] F4 Emoji suggestions
- [x] F8 Manage learned words
- [x] F10 Gboard key layout setting
- [x] F3 Copied items, details, clipboard history
- [x] F11 Toolbar in the bar (emoji, clipboard, dropdown, rearrangeable)
- [x] F5 Text-editing panel

## Decision log
- 2026-11-07: plan written from phone feedback. B1's cause is inferred from the overlay's bounds
  (62 pt headroom over the app) and Android 12's untrusted-touch rule. Confirm it with logcat on
  MuMu before fixing.
- 2026-11-07 (Gboard study): Gboard 18.6.3.1002251336 (arm64-v8a, minSdk 26, target 37) was studied
  through `aapt2` (manifest, `xml/method`, 5,382 English strings, 614 setting keys) and the code's
  string tables (121k strings, own parser). JADX wasn't available, so no code was decompiled and
  flag *defaults* are unknown.
  - **Observed:** the clipboard keeps items for 1 hour with pinning; a copied-item chip; details
    picked out of copies (address, phone, post code); a number row with a separate "for passwords"
    option; a height ratio from 0.5 to 2.0; long-press delay, sound volume and vibration settings;
    "Delete learned words"; "Block offensive words".
  - **Declarations:** `directBootAware`, `supportsInlineSuggestions` (autofill in the bar, not
    chosen), and the `:train` process for ML.
  - **Optimisation flags:** `emoji_keyboard_prewarm_enabled`, `enable_training_cache_db_wal`,
    `wait_ic_call_timeout`, and `touch_action_repeat_start_delay` / `touch_action_repeat_interval`.
  - **Assets:** a shipped ART baseline profile.
  - **Selected by the user:** 1, 2, 4, 5, 7 (as a setting), 8, 9, 12 and 13. The optimisations
    were left to me (P1–P6).
- 2026-11-07: F10 scope is the key arrangement only, with the iOS look kept (user's choice). Gboard's
  layouts are decodable: compiled XML `framework`/`keyboard`/`view` files with `if` blocks on
  device, mode and `enable_number_row`, pointing to Android layouts for key widths and to
  `softkeys` for labels (about 1,668 XML files mention softkey and 380 mention rows). F11 was
  requested together with it: toolbar buttons in an idle bar, collapsing to a dropdown when words
  appear.
- 2026-11-07 (implementation):
  - **B1:** confirmed with `dumpsys input`. On MuMu the overlay is `TRUSTED_OVERLAY` (a privileged
    system app there), so the bug never showed on MuMu. On a phone the overlay is untrusted, and
    `BLOCK_UNTRUSTED` drops touches through it. The fix: the overlay covers only the keyboard, and
    reaches 62 pt above it only while a popup (or its fade-out) is visible. Checked: frame is
    890→742→890 px around a held key.
  - **B2:**
    - **Connection cache:** `CachingInputConnection` caches the 64 characters before the cursor,
      updating them through our own commit, composing and delete calls and dropping them on key
      events and unexpected cursor moves. Auto-capitalisation is computed from it
      (`TextUtils.getCapsMode`), with no `getCursorCapsMode` round trip; curly quotes are
      straightened first, which replaces the old 1-character pre-check.
    - **Deleting:** one grapheme at a time with `deleteSurroundingText` (`android.icu`
      BreakIterator); key events only when the app reports no text.
    - **Repeat timing:** 400 / 60 ms.
    - **Refreshes:** suggestion refreshes pause while delete repeats, then run once 150 ms after.
    - **Result:** a 1 s hold deleted 12 characters, about 5–6 before; flags and ZWJ emoji delete
      whole.
  - **B3:** the emoji-key menu stays open when released on the key without sliding (as iOS's globe
    menu does); tap an item or anywhere else. The menu is now generic (`showMenu`) and reused by
    clipboard cards.
  - **A:**
    - **A1–A8:** done as specced, with uptime-based progress and no Animator objects; all honour
      "Remove animations".
    - **A6:** already existed (300 ms `startScroll`).
    - **A9 done:** the menu spring-in, the toolbar slide and the chevron rotation.
    - **A9 not done:** cross-fading the clipboard and editing panels in and out, and fading the
      copied-text chip in. They open instantly.
    - **Glyphs:** paths and paint are now hoisted, so animated frames allocate nothing.
  - **F2 / F6 / F10:**
    - **One spec:** `KeyboardSpec` (style + number row + height scale) is the single source of key
      rects, row centres and height.
    - **Number row:** only on the letters page; the number and symbol pages stretch to the same
      height.
    - **Slide to type:** maps points into the iOS frame by letter-row centres (`decoderY`) instead of
      per-layout key centres. The letter rows sit at the same x in both styles (centres within
      0.25 pt).
    - **Gboard weights:** the decode was inconclusive. 228 layout resources hold the QWERTY grid
      and Gboard picks between them at runtime, so its standard proportions are used: letters 10 %,
      shift / delete / ?123 / return 15 %, comma / emoji / period 10 %.
    - **Hints:** the long-press hint symbols are Gboard's English defaults.
  - **F9:** LDNOOBW "en", single words only (275), in `assets/dict/blocked_en.txt` via
    `tools/dict/build_blocklist.py`. Filtered in the corrector candidates, predictor completions and
    next words, and swipe results.
  - **F4:**
    - **Data:** CLDR `annotations` and `annotationsDerived` (en) via
      `tools/emoji/gen_emoji_keywords.py`. Ranked by exact name, then name match, then Unicode
      order; stop words removed; at most 40 emoji per keyword; plus a 22-word hand-picked override
      table for common words.
    - **Tapping:** replaces the word; a backspace restores it without marking it rejected.
  - **F8:** `UserWords` is now one instance per process, shared by the keyboard and settings. The
    screen lists learned words that are not in the dictionary (the dictionary loads in the
    background).
  - **F3:**
    - **Storage:** `ClipHistory` is a separate `clips.db` (device-protected, WAL) holding at most 50
      unpinned clips.
    - **Chip age:** taken from `ClipDescription.timestamp`, which turned out to be wall-clock time
      on MuMu (both clocks are accepted), so a keyboard restart doesn't reset it; the chip lasts
      60 s.
    - **Sensitive clips:** clips marked `EXTRA_IS_SENSITIVE` are skipped.
    - **Images:** not supported.
  - **F11:**
    - **Bar:** suggestions are hidden when Predictive Text is off even though the bar stays up for
      the toolbar (autocorrect still uses them internally).
    - **Toolbar Buttons screen:** drag by the handle (the row follows the finger; it drops on
      release) or use the arrows; hidden buttons remain in the emoji-key menu.
  - **F5:** a 4 x 4 grid. Shift and Ctrl meta key events do selection and word jumps;
    `performContextMenuAction` does select all, copy, cut and paste. Copy and Cut are disabled in
    password fields.
  - **Tests:** 67 JVM tests (new: ClipEntities 10, EmojiKeywords 5, two blocked-word tests).
  - **On MuMu:** the regression loop, autocorrect, swipe, and everything above passed.
  - **Frames, all features:** 1.15 % janky, 95th percentile 11 ms.
  - **Open:**
    - an unexplained bar state once seen after `adb input text` ("Help", an empty slot, "Hero");
      not reproducible
    - Gboard's chip duration is unknown
    - the cold-start gain from P6 was not measured
- 2026-11-07 (phone feedback, round 2):
  - **Symbol Hints:** a new setting hides the long-press symbols on Gboard-layout keys. The
    long-press still opens the accents.
  - **Square panel corners:** while a keyboard is up, the app ends at the keyboard's top edge, so
    the area behind rounded corners belongs to no app and showed black on the phone.
  - **Empty field:** no predictions, so the toolbar shows its buttons; typing collapses them into
    the dropdown.
  - **Emoji Button:**
    - **Setting:** On the Keyboard or In the Toolbar, never both.
    - **In the Toolbar:** the emoji key is removed and the space bar takes its place.
    - **Toolbar Buttons screen:** the Emoji row's switch moves it.
    - **Requires the toolbar:** choosing In the Toolbar turns the toolbar on, and with the toolbar
      off the emoji key always returns.
  - **Toolbar look, now Gboard's** (from the user's Gboard screenshots):
    - **Grid button:** sits at the left edge (6 % of the width); the toolbar buttons are spread
      22 % apart after it, replacing the small chevron and the tightly packed buttons.
    - **While typing:** the grid stays at the left, the suggestions follow it, and tapping the
      grid shows or hides the buttons over them.
    - **Empty bar:** the grid opens the keyboard menu, which gives the menu a home when the emoji
      key is in the toolbar.
  - **Gboard-style grid look reverted** at the user's request (they prefer the › dropdown).
  - **Reorder crash hardening:** reported on the phone but not reproducible on MuMu. List rebuilds
    in the Toolbar Buttons screen and the in-bar order save now run after the touch that triggered
    them (`post`), and every reorder is bounds-checked.
  - **Toolbar Buttons screen:** the crash happened when dragging there. The list now rebuilds after
    the touch ends, and drag-and-drop is the only way to reorder (the arrows are gone). Toolbar
    Buttons and Learned Words gain an iOS "‹ Keyboard" back link. Four drag cases ran on MuMu
    with no crash.
  - **Toolbar spacing and animation:** the › chevron is kept, but placed at Gboard's grid spot
    (6 % of the width), with the buttons every 22 % after it as in Gboard.
    - **One 220 ms ease-in-out transition:** when words appear, the buttons slide into the chevron
      while shrinking and fading, the chevron turns from ‹ to ›, and the suggestions fade and slide
      in; opening the dropdown or clearing the field reverses it.
    - **Frames:** 0.43 % janky while typing, deleting and clearing.
  - **Empty field without the toolbar:** "no predictions in an empty field" now applies only with
    the toolbar on; with it off, iOS's starting words ("The", "I", "I'm") show again.
  - **Bar dividers:** drawn only between two words, which removes the stray line in an empty bar.
  - **Rounded Liquid Glass corners restored (20 pt), properly:** `onComputeInsets` reports the
    keyboard's content top one radius lower, so the app draws under the keyboard's top edge and
    shows through the corners instead of black. The touchable region still covers the whole
    keyboard. Classic keeps a square top edge, like iOS's classic keyboard. Checked on MuMu: the
    app is visible outside the curve, and a tap at the bar's top edge still hits the suggestion.
  - **Symbol Hints in both layouts:** the switch now also works with the iOS layout, giving its
    letters Gboard's hold symbols (q→1 … p→0, a→@ …, z→* …). It defaults on with Gboard and off
    with iOS, and resets to that default when the layout is changed. Checked on MuMu: the hints are
    drawn and holding q types 1.
  - **Chevron in an empty field (toolbar on):** it now shows the starting words ("The", "I", "I'm"),
    folding the buttons away with the usual animation; tapping it again brings the buttons back
    over the words. Typing or a new field resets it, so an emptied field shows the buttons again.
  - **Predictions centred with the toolbar on:** the slots are full-width thirds again. The left
    word only moves right of the chevron when it would otherwise touch it.
  - **Settings redesigned** to look like modern iOS Settings:
    - **Main page:** an iPurrboard status card ("Ready to type ✓", or set-up hints), a "Get
      started" section only while setup is incomplete, Try It, and grouped navigation rows with
      coloured icons (Typing, Predictions, Slide to Type | Appearance, Layout | Toolbar, Clipboard,
      Sound & Haptics | Text Replacement, Learned Words).
    - **Pages:** each topic is its own page with a "‹ Settings" back link; choices are checkmark
      lists instead of dialogs.
    - **Styling:** cards are more rounded (22 dp) with taller rows, pressed rows highlight grey,
      and headers are in sentence case.
    - **Toolbar page:** gained the Emoji Button choice and the Text Editing Panel switch.
    - **Toolbar page, one switch per button:** the separate "Emoji button" choice and "Text
      Editing Panel" switch are gone. In the Order list, Emoji's switch moves it between the
      toolbar and the keyboard (keyboard whenever the toolbar is off), and Text Editing's switch
      turns the panel on or off everywhere (toolbar and emoji-key menu). Only Clipboard has a
      plain hide/show switch.
    - **Text editing panel, Gboard layout:** a header in the suggestion bar's place (round back
      button and "Text editing"); tall left and right keys with up / Select / down between them;
      Select all / Copy / Paste on the right; a bottom row with start, end and delete. Word jumps,
      Cut and ABC are gone (back replaces ABC). Copy needs a selection; Paste needs a clip.
    - **Toolbar buttons centred:** the group is centred on the bar at every count (22 % apart),
      instead of starting next to the chevron.
    - **Panel bar and transitions:** emoji, clipboard and text editing open with a 260 ms
      rise-and-fade, and the keys do the same on the way back. While a panel is open the bar
      cross-fades to a back button (the chevron grows a shaft into an arrow), the panel's name,
      and Clear for the clipboard. A panel gets the bar even when the keys have none. The
      clipboard's ABC / delete row and the editing panel's own header are gone.
    - **Animation pass (iOS-faithful):** key presses, plane switches, shift, popups and accent
      panels stay as iOS has them (instant on touch, existing fades). Added:
      - **Copied-text chips:** cross-fade with the suggestion slots (120 ms, like the bar's words).
      - **Menus:** fade in with their spring (100 ms) and shrink slightly and fade out when closed
        (150 ms), like iOS context menus.
      - **Clipboard panel:** momentum scrolling, a rubber band past the ends that springs back, cards
        that glide to new places after Pin / Delete / Clear, new cards fading in, removed ones
        fading out, and the pressed card fading back.
      - **Emoji panel:** a rubber band and bounce at both ends (it already had momentum).
      - **Text editing panel:** keys fade back from the pressed colour (100 ms) and Select's
        highlight cross-fades.
      - **Settings:** iOS push / pop between pages (new page from the right, old page drifting a
        third left, 350 ms), and pages wait for a flipped switch to finish sliding before they
        rebuild.
      - **Not animated, on purpose:** the 123 / ABC switch, return-key labels and the
        emoji tone panel. iOS changes these instantly. Everything honours Remove Animations.
    - **Emoji & clipboard layout option** (Settings > Layout, `Prefs.panelLayout`, defaults to the
      key layout and is reset with it): iOS = horizontal emoji pages with pinned uppercase titles and
      an inset-grouped clipboard list (52 pt rows, hairlines, 2 lines); Gboard = a vertical emoji
      grid as wide as the panel (sentence-case titles, fills a taller keyboard) and the 2-column
      card grid. Both keep momentum, rubber band, category glide and the tone panel.
    - **Split into two settings:** `emojiLayout` and `clipboardLayout` (Settings > Layout, sections
      "Emoji" and "Clipboard"). Each falls back to the earlier shared `panelLayout`, then the key
      layout; choosing a key layout sets both.
    - **Gboard clipboard, closer to Gboard:** "Pinned" and "Recent" headings (strings from the
      APK's string table); cards sized to their text (up to 5 lines, 12 pt padding, 10 pt corners)
      packed into the shorter of two columns; a tilted pin on pinned cards; the tip "Touch and hold a
      clip to pin it. Unpinned clips will be deleted after <retention>." while nothing is pinned; an
      empty state ("Your clipboard is empty"). Glide, fade and rubber-band animations still apply.

## Release-build feedback (round 3)

- [x] **Screenshots in the clipboard:** Settings > Clipboard > Save Recent Screenshots (asks for
      READ_MEDIA_IMAGES). Screenshots in a "Screenshot" folder taken within the retention time are
      added to the history on keyboard open and clipboard open (`clips.db` v2: `image` column,
      `dismissed` table so deleted / cleared ones don't come back). Pasting copies the image into
      the cache and commits it through `ClipImageProvider` with a read grant; fields without image
      support get "Images can't be pasted here". Untested end to end: MuMu has no app that accepts
      images.
- [x] **Predicting the word at the cursor:** a tap after a word, or a backspace into one, makes it
      the composing word again (`setComposingRegion`), so the bar predicts and corrects it like iOS.
      Skipped inside addresses / handles / numbers and mid-word.
- [x] **Trackpad and editing-panel arrows stay in the field:** arrow keys are capped at the
      characters left on that side (grapheme count), and up / down only in multi-line fields towards
      another line; an arrow at the end used to move focus to the next field.
- [x] **Light / Dark in Settings:** every screen re-checks the mode in onResume and recreates if it
      changed while it was underneath the Appearance page.
- [x] **Inline predictions** (the "Show Predictions Inline" switch did nothing): while typing, the
      centre suggestion's completion shows in grey after the cursor as part of the composing text;
      space accepts it (backspace then only removes the space). Not shown for corrections, for under
      2 letters, or for a word picked up again until a letter is typed. `CachingInputConnection`
      keeps the cursor before the grey text and strips it on finish, restoring a tap elsewhere.
- [x] **Laggy Pin / Delete menu:** its fade layer is now the menu's size instead of the whole
      window.
- [x] **Chip spacing:** chip labels collapse line breaks, tabs and repeated spaces to one space
      (the inserted text is unchanged).
- [ ] **Missing animations:** on hold (user asked to wait).
- [ ] **GIFs and Emoji Kitchen stickers:** last.

## Round 4

- [x] **Suggestion bar spacing:** three equal slots between equal side margins (9 % with the
      toolbar, clear of the chevron), so the centre word stays centred; long words shrink to 13 pt
      before being shortened, like iOS.
- [x] **Corrections, Gboard / AOSP style** (Gboard's own models are proprietary; these follow its
      open-source ancestor): a spatial model from where each letter was tapped (offset from the key
      centre, `onCharTap`), cheaper missing last letters (0.3, "worl" -> "world"), and a bonus for
      words learned after the previous word. Tests: unfinished word, tap on a key edge, context.
- [x] **GIFs:** Tenor's API was shut down by Google (no new keys from Jan 2026, off since
      30 June 2026), so GIFs use GIPHY (`Giphy.kt`, key from local.properties via BuildConfig,
      INTERNET permission). `GifPanel`: search field, animated 2-column grid (AnimatedImageDrawable,
      ~30 fps), momentum and rubber band. Search: the keys come back and the bar becomes the
      search field (`gifSearch`); return searches, the back arrow cancels. Sending: downloaded and
      committed as image/gif through ClipImageProvider. Toolbar button and Order switch like Text
      Editing, plus the emoji-key menu. Untested with a real key and an app that takes GIFs.
- [x] **GIPHY call budget:** a free key allows 100 API calls an hour for everyone using the build,
      so responses are cached in memory and in `cacheDir/giphy` (newest 40): trending for 30 min,
      each search (lowercased) for 1 hour. Offline or over the limit, older cached results are used.
- [ ] **Animations:** waiting for the user's instructions.
- [ ] **Emoji Kitchen stickers:** not started.
- [ ] **In-app updates from Firebase:** not started (needs the user's Firebase project).

## Round 5

- [x] **Copied-item chips, Gboard style:** compact chips sized to their content (copied text up to
      60 % of the bar, details 50 %), 8 pt corners, drawn icons (clipboard, #, link, envelope,
      phone, pin) instead of emoji; when they don't fit the copied-text chip shrinks to its icon,
      then trailing details are dropped.
- [x] **Screenshot chip:** with Save Recent Screenshots on, a screenshot taken in the last minute
      shows as a thumbnail chip (only in fields that take images); tapping pastes it. A
      ContentObserver on MediaStore spots new screenshots while the keyboard is open.
- [x] **Next-word data:** `assets/dict/bigrams_en.tsv` (11k words, top 6 next words each) from
      Tatoeba's English sentences (CC BY 2.0 FR), names dropped by how often they're capitalised
      mid-sentence. The user's own pairs come first; completions that usually follow the previous
      word are offered first.
- [x] **Inline predictions:** never for a word that is already complete ("you", "thank").
- [x] **Emoji search:** `assets/emoji_search.tsv` (CLDR names + keywords, 1923 emoji,
      `tools/emoji/gen_emoji_search.py`) and `EmojiSearch` (every word must start a name or
      keyword word; name matches first). The emoji panel's bar has Search Emoji; the search mode
      shared with GIFs shows a results row above the keys (sideways scroll; recents when empty).
- [x] **Voice typing:** `VoicePanel` (waveform from RMS, status, mic button) and SpeechRecognizer with
      partial results as composing text, restarting until stopped (3 silences end it). RECORD_AUDIO
      is requested by Settings (`EXTRA_REQUEST_MIC`). Toolbar switch + emoji-key menu. MuMu has no
      recogniser, so only the panel states were tested.
- [x] **Physical keyboards:** letters, space, backspace and enter from a non-virtual alphabetic
      keyboard go through onText / onSpace / onDelete / onReturn; shortcuts and navigation go to the
      app. With a hardware keyboard attached (config qwerty + exposed) the view shows only the bar.
      Tested on MuMu by writing key events to its keyboard device.
- [x] **GIFs, iOS style:** Settings > Layout > GIFs: two rows scrolling sideways under "Find
      images" (after Messages' #images); Gboard keeps two columns.
- **Test hygiene:** clearing fields with the keyboard's own backspace after autocorrection marks
  words as "don't correct"; tests now clear with adb key events.

## Round 6

- [x] **Emoji Kitchen stickers:** `assets/emoji_kitchen.dat` (722 KB: 619 emoji, 147,000 pairs, each
      u16 + u16 + date index; `tools/emoji/build_kitchen.py` from the community metadata.json) and
      `EmojiKitchen` (the pair typed first, then the emoji with itself, then its partners by
      popularity; URL rebuilt from the date and code points). When the last thing before the cursor
      is a kitchen emoji, the bar shows a sideways-scrolling sticker row instead of words; in the
      emoji panel the row replaces Search Emoji, with a magnifier kept at the right. Pictures are
      downloaded on demand (disk cache of 300, memory 48) and sent as image/png via
      ClipImageProvider; only in fields that take images. Setting: Predictions > Emoji Kitchen
      Stickers (on). Tests: pair URL, either order, counts.
- [x] **Try it field takes images** (API 31+ OnReceiveContentListener) with a preview, so GIFs,
      stickers and screenshots can be tried in Settings; used to test the sticker flow on MuMu.

## Round 7: animations

- [x] **Janky key previews:** the popup overlay window resized on every key press and release (to
      stay off the app's touches when idle), and the bubble was drawn at the new offset before the
      window had moved, so it jumped for a frame and each resize cost frames. Now the overlay grows
      on touch down (`hold()`), stays tall through typing, shrinks 700 ms after the last popup, and
      draws popups from its actual height. Bubbles and accent panels appear instantly (iOS; the glass
      grow-in spring is gone); a released bubble fades in 60 ms, and is dropped at once when the next
      key goes down.
- [x] **Overlay redraws:** only while it shows something (plus one frame to clear), not on every
      keyboard frame (e.g. 30 fps GIFs no longer redraw it).
- [x] **Images fade in** (150 ms) instead of popping: GIFs, sticker thumbnails, clipboard
      screenshots.
- [x] **Allocations:** sticker row and emoji panel search pill reuse their rects.
- Measured on MuMu (typing bursts): 1.6 % and 0.3 % janky frames, 90th percentile 6-16 ms.

- [x] **Reset to iOS Defaults** (Settings, bottom; confirmation dialog): `Prefs.resetToIos()` sets iOS's
      keyboard defaults (haptic off), turns the Gboard extras off (toolbar, number row, symbol
      hints, chips, clipboard history, text editing, GIFs, Emoji Kitchen, screenshots), iOS layouts
      and panels, Liquid Glass, Automatic light/dark. Keeps text replacements, emoji recents and
      tones, learned words and pinned clips. Also fixed `IosList.actionRow`: its empty trailing view
      filled the row and hid the title.

- [x] **iOS alerts everywhere** (`ui/IosAlert`): every dialog (reset, learned words, permissions,
      text replacement) is an iOS alert that follows Appearance. Classic: 270 pt card, 14 pt corners,
      hairline buttons (two side by side, more stacked), preferred bold, destructive red. Liquid
      Glass: 300 pt see-through card with window blur (Android 12+), 34 pt corners, rim, capsule
      buttons with the preferred one filled blue. Appears scaled from 1.12 with a fade, fades out.
- [x] **Consistency:** Text Replacement is its own Settings page (was a dialog list); Learned Words
      has an iOS search capsule with a magnifier and action rows (no chevrons) for Add / Reset;
      Settings toasts are gone (alerts or nothing); the keyboard's toasts are an in-keyboard capsule
      notice (`showNotice`); keyboard menus and notices get the glass rim in Liquid Glass.

- [x] **Animation review fixes:** menus grow 95 % to 100 % on cubic-bezier(0.23, 1, 0.32, 1) in
      180 ms (was 60 % with an overshoot); panel switches continue from the current progress when
      reversed; the alert's backdrop dim fades with the card (in 260 ms, out 160 ms); toolbar,
      clipboard and voice-level glides are time-based (1 - e^(-dt/τ), τ 47 / 47 / 40 ms) so they're
      the same at any refresh rate; a notice already showing keeps its opacity when retriggered;
      Settings push / pop use cubic-bezier(0.32, 0.72, 0, 1) over 380 ms; the swipe trail fades in
      250 ms with ease-out (was 450 ms linear).

- [x] **Animation performance pass:** the alert's backdrop is a scrim view faded on the GPU (was the
      window's dim amount, a window-manager update every frame) in a full-screen window that keeps
      the card centred above the bars and keyboard (a padded holder; margins on a centred child
      shifted it by the whole margin); bubble release fades its colours instead of an offscreen
      layer per keystroke; no per-frame allocations in the accent panel, tone panel, image chips,
      clipboard list thumbnails or the one-handed strip. Typing bursts on MuMu: 0.65 % / 0 % janky,
      90th percentile 5 ms.

- [x] **Worst-case data pass** (debug `DebugDataReceiver`, `SEED_DATA` sets worst / huge / one /
      empty / copy / copy-long):
  - Chip labels shorten with a binary search and never read past 300 characters (a copied 20,000
    character document froze the keyboard for 9.5 s; now it opens in ~0.6 s).
  - Clip history and learned words wait for their saved data before any read or change (a copy
    while the keyboard started could take a saved clip's id or have its count overwritten).
  - Settings values are one line, cut with "…", at most half the row (a replacement's phrase hid
    its shortcut).
  - Clipboard previews: trimmed, and the list flows each clip onto one line (no blank first lines).
  - Learned words keep how they were written (`userwords.db` v2 `shown`): "Wiśniewska", "Jo".
    Capitals typed mid-sentence are kept; a capital at the start of a sentence is ignored.
  - Empty lists show grey, untappable text ("None", "No learned words yet").
  - Learned Words says "Showing 200 of N; search to find the others." past 200 rows.
  - Alerts scroll their text when they don't fit; the buttons stay on screen.

- [x] **Filipino (Tagalog) keyboard + Settings > Languages:**
  - `text/Language`: English (US) and Filipino; QWERTY for both (as iOS and Gboard).
  - Data: `dict/tl.tsv` (23k words from OpenSubtitles + Tatoeba + Leipzig Wikipedia, names dropped,
    hyphenated words counted as parts so "mag" / "pag" aren't corrected), `bigrams_tl.tsv`
    (Tatoeba), `blocked_tl.txt` (LDNOOBW fil + common profanity), `emoji_keywords_tl.tsv` and
    `emoji_search_tl.tsv` (CLDR fil). Counts scaled to the English list's.
  - Multilingual: every enabled keyboard's words are known (others weighted 0.25), so Taglish isn't
    autocorrected; the current one's next words, starter words ("Ako", "Hindi", "Ang") and emoji
    come first. Emoji search matches every enabled language plus English.
  - Switching: the emoji-key menu lists the keyboards with a checkmark (iOS globe menu); the space
    bar shows the name for 1.2 s, then fades to "space"; the hardware language key / Ctrl+Space
    cycle. Dictation uses fil-PH. Long menus squeeze rows (min 30 pt) or sit beside the key.
  - Settings > Languages (main row shows which are on): a switch per language with a one-line
    description (the last one on is locked and dimmed), and "Typing In" checkmarks with two or
    more on. Turning a language on also makes it the one in use. No dialogs.

- [x] **Number row, from Gboard's resources (gboard.apk, resources.arsc + layout XML):** the body
      (every row but the bottom one) is one height on every page; with the number row its row
      weights are 238 (numbers) / 254 / 254 / 254, and the ?123 / =\< pages' three upper rows share
      it equally (333 each). The bottom row is outside the body and never moves. Applied: number
      row band 54 * 238/254 pt; 123 / #+= upper rows spread over the body with keys 16 % taller
      (measured) and even gaps down to the fixed bottom row. Gboard body heights in its theme:
      195 dp without / 234 dp with the number row. iOS has no number row on iPhone (only iPad's
      layouts have one), so this follows Gboard on both layouts.

## Later

- [ ] **More languages:** the `Language` enum plus its data files (tools/dict, tools/emoji); a
      different layout or accents (Spanish ñ key, German umlauts) needs per-language layouts too.
      Non-Latin and CJK need much more.
