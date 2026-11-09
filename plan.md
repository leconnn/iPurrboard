# iOS Keyboard for Android: implementation plan

Six work items, in order. Each lists files, design decisions already made, step-by-step tasks,
and a test script with expected results. Follow it as written; deviations go in "Decision log".

---

## 0. Context (read first)

### Current state (all working, tested on MuMu Android 15)
- `app/src/main/java/dev/leconnn/ioskeyboard/`
  - `KeyboardLayout.kt`: geometry (iOS points, 375 x 216), `Key`, `KeyKind`, `Plane`, `Layouts`, `Accents`.
  - `KeyboardView.kt`: drawing + multi-touch (bubble, accent panel, trackpad, delete repeat,
    123 slide, caps lock). Talks to its host through `KeyboardView.Listener`.
  - `IosKeyboardService.kt`: `InputMethodService`; auto-shift, ". " shortcut, word delete,
    return label, globe, insets (transparent 62 pt headroom above keys for bubbles).
  - `Theme.kt`: `KeyboardTheme` (light/dark colours) + `Glyphs` (shift/delete/globe paths).
  - `KeySounds.kt`: optional Apple .wav in `res/raw` (gitignored), else system click.
  - `SetupActivity.kt`: enable/select buttons + in-app preview (hidden when our IME is active).
- No AndroidX. Plain framework Views, Kotlin via AGP 9.2 built-in Kotlin, Gradle 9.8.1,
  compileSdk 36, minSdk 26. Keep it that way unless an item says otherwise.

### Reference data (gitignored, `reference/ios-extracted/`)
- `layouts/KBLayouts_iPhone.dat` + `layouts/KBLayouts-format-notes.md` (fully decoded format).
- `layouts/decoded/*.txt|json`: decoded Choco/Portrait/Truffle/Landscape QWERTY.
- `layouts/continuous_path_base_layout.plist`: key frames for slide-to-type (375 pt).
- `layouts/Keyboard-default.plist`, `layouts/TextInput_en.bundle/`: accents, labels.
- `sounds/*.wav`, `glyphs/` (extracted icons; key glyphs themselves are DeepMap2, not decoded).
- Decoder tools (tracked): `tools/kbdat/` (`kbtree.py`, `kbflat.py`; run from that folder:
  `python -I kbflat.py ../../reference/ios-extracted/layouts/KBLayouts_iPhone.dat iPhone-PortraitChoco-QWERTY Small-Letters`).

### iOS 27.2 keyboard settings (from KeyboardSettings.bundle, defaults in brackets)
Auto-Capitalization [on], Auto-Correction [on], Predictive Text [on], Show Predictions Inline [on],
Show Math Results [on], Check Spelling [on], Enable Caps Lock [on], Enable Dictation,
Character Preview (`KeyboardAllowPaddle`) [on], "." Shortcut [on], Smart Punctuation [on],
Slide to Type (`KeyboardContinuousPathEnabled`) [on], Delete Slide-to-Type by Word [on],
Keyboard Feedback: Sound [on] / Haptic [on], Stickers [on]. Separate screens: Text Replacement,
One-Handed Keyboard, Keyboards (languages).

### Build / install / test loop
```bash
./gradlew assembleDebug            # ~5 s incremental
./gradlew test                     # JVM unit tests (added in item 3)
adb connect 127.0.0.1:16384        # MuMu instance 0
adb -s 127.0.0.1:16384 install -r app/build/outputs/apk/debug/app-debug.apk
source tools/adb/kb.sh             # helpers: typew, space, del_key, k123, globe, ret, shot, field_text, ime
```
MuMu facts (verified):
- Our app is installed as a system app in `/system/priv-app/IosKeyboard/` (needed to bypass
  MuMu's `MuMuIMETrace` block). `adb install -r` updates it in place. Root + writable system are on.
- `ime set/enable` and `settings put secure ...` are blocked even as root. Enable via Settings UI,
  select via the picker ("Select keyboard" button only works while a text field has focus).
- **Never use `am start -S`** on our package: it kills the IME too and it won't reappear in that
  app until reopened. Use `am start` without `-S`.
- `adb install -r` also kills the IME process. Afterwards bring the keyboard up once in another
  app (Settings search: `am start -a android.settings.SETTINGS`, `focus 290 136`), then go back.
  It can also reset the default keyboard to Sogou: check `ime`; if so, focus a field, open the
  picker ("Select Keyboard", y 450) and tap the iOS Keyboard entry (y 1480).
- MuMu may be closed between sessions: `MuMuManager.exe control -v 0 launch`, wait for
  `is_android_started`, then `adb connect 127.0.0.1:16384`.
- Learned state persists across tests: a word whose autocorrection was undone (e.g. "teh") is
  never corrected again. Use fresh misspellings per test, or clear app data to reset
  (`adb shell pm clear` also clears settings).
- Before typing, loop until `dumpsys input_method` shows `mInputShown=true` (taps right after
  launch can arrive before the window is ready).
- `uiautomator` segfaults on exit (harmless). An empty field reports its hint as text.
- Git Bash: export `MSYS_NO_PATHCONV=1` before adb commands with device paths (kb.sh does this).
- Screen 900 x 1600, 2.4 px/pt. Keys top at y = 996 px since item 4 (36 pt globe strip below the
  keys); the suggestion bar spans 943..996, its centre slot at (450, 943); rows at pt y 30/84/138/192.
- Emoji panel: cell (c, r) centre x = 19.2 + c*100.8 + 50.4, y = 1048.8 + r*74.4 + 37.2; bottom bar
  y = 1467 (ABC x 60, delete x 840, category icons between x 120 and 780). Seed Frequently Used
  with `setp emojiRecents` and a JSON array written with \u escapes (they survive adb).
- MuMu supports cross-window blur (`mBlurEnabled=true`). Battery saver does NOT disable it there;
  use `settings put global disable_window_blurs 1|0` to test the no-blur fallback.

### Conventions
- Match existing style: small files, comment density as now, no new dependencies except
  `junit:junit:4.13.2` for tests (item 3).
- Every setting is read through `Prefs` (item 1); never read SharedPreferences directly elsewhere.
- Disable smart features in password, email, URL, `TYPE_TEXT_FLAG_NO_SUGGESTIONS` and
  non-text fields (one helper: `FieldTraits`, item 3).
- Apple assets stay gitignored; app code must work without them.

---

## Item 1: Settings screen (iOS-style)

**Goal:** an iOS-like settings page with toggles; every existing behaviour obeys its toggle.

### Files
- new `Prefs.kt`: typed wrapper over SharedPreferences in device-protected storage
  (`createDeviceProtectedStorageContext()`, so it works before first unlock).
- new `SettingsActivity.kt`: replaces `SetupActivity` as launcher + `settingsActivity` in `method.xml`.
  Keep setup buttons + preview at the top of it; delete `SetupActivity.kt`.
- new `ui/IosList.kt`: tiny helpers to build grouped, rounded "inset grouped" sections in code
  (section header, row with title + Switch, row with chevron, footer text).
- edit `IosKeyboardService.kt`, `KeyboardView.kt`, `KeySounds.kt`, `AndroidManifest.xml`, `method.xml`.
- new debug-only `DebugPrefsReceiver.kt` (registered in `src/debug/AndroidManifest.xml`) so tests can
  flip prefs: `adb shell am broadcast -a dev.leconnn.ioskeyboard.SET_PREF --es key autoCap --ez value false -p dev.leconnn.ioskeyboard`.

### Prefs (key = iOS id where one exists)
| Pref key | Type/default | Used by |
|---|---|---|
| `KeyboardAutocapitalization` | bool true | `updateAutoShift()` |
| `KeyboardAutocorrection` | bool true | item 5 |
| `KeyboardPrediction` | bool true | suggestion bar visible (item 3/5) |
| `KeyboardInlineCompletion` | bool true | item 5 phase 2 |
| `KeyboardMathExpressionCompletion` | bool true | item 3 |
| `KeyboardCheckSpelling` | bool true | item 5 (underline in composing span only) |
| `KeyboardCapsLock` | bool true | double-tap shift in `KeyboardView.down()` |
| `KeyboardAllowPaddle` | bool true | `drawPreview` (Character Preview) |
| `KeyboardPeriodShortcut` | bool true | `onSpace()` |
| `SmartTyping` | bool true | item 3 |
| `KeyboardContinuousPathEnabled` | bool true | item 6 |
| `KeyboardContinuousPathDeleteWholeWord` | bool true | item 6 |
| `keyboardSound` | bool true | `KeySounds.play` |
| `keyboardHaptic` | bool true | `onFeedback` |
| `ShowStickers` | bool true | item 4 (hidden until stickers exist) |
| `theme` | enum `classic`/`glass`, default `glass` once item 2 lands | item 2 |
| `oneHanded` | enum `off`/`left`/`right` | geometry scale 0.86 + offset |
| `textReplacements` | JSON list `{shortcut, phrase}`, default `[{"omw","On my way!"}]` | item 5 |

### Tasks
1. `Prefs` object: `fun get(ctx)`, properties with getters/setters, `registerListener`.
2. Service: cache a `Prefs` snapshot in `onStartInputView`; listener invalidates the view.
3. Wire existing features: autoCap, capsLock, paddle (preview), period shortcut, sound, haptic.
4. One-handed: in `KeyboardView.onMeasure` keep full width, but transform key rects:
   scale x by 0.86 and shift to left/right edge; draw a chevron button in the free strip that
   switches side (iOS behaviour). Hit testing uses the transformed rects.
5. SettingsActivity sections (iOS order): *General* (Auto-Capitalization, Auto-Correction,
   Check Spelling, Enable Caps Lock, Predictive Text, Show Predictions Inline, Smart Punctuation,
   Show Math Results, Character Preview, "." Shortcut + footer "Double tapping the space bar will
   insert a period followed by a space."), *Slide to Type* (2 toggles), *Keyboard Feedback*
   (Sound, Haptic), *Appearance* (Classic / Liquid Glass), *One-Handed Keyboard* (Off/Left/Right),
   *Text Replacement* (row → simple list editor dialog), *Setup* (Enable, Select).
   iOS look: grouped background (#F2F2F7 light / #000000 dark), rows white/#1C1C1E,
   10 dp corner radius, 44 dp rows, system blue switches tint (#34C759 track like iOS).

### Test (expected)
```bash
source tools/adb/kb.sh
setp(){ adb $D shell am broadcast -a dev.leconnn.ioskeyboard.SET_PREF --es key $1 --ez value $2 -p dev.leconnn.ioskeyboard >/dev/null; }
setp KeyboardPeriodShortcut false; typew hi; space; space; field_text   # "hi  " (no period)
setp KeyboardPeriodShortcut true;  # then "hi. "
setp KeyboardCapsLock false; shift_key; shift_key; typew ab; field_text  # "ab" (2nd tap turns shift off, no lock)
setp KeyboardAllowPaddle false; hold <q> 600 + shot                    # no bubble
```
Done when: every toggle above that exists today changes behaviour live (no restart), settings
screen screenshot looks like iOS grouped list in light + dark.

---

## Item 2: Liquid Glass theme

**Goal:** iOS 26-style glass keyboard: blurred app content behind, translucent keys with
specular rim, rounded floating panel, springy popups. Fallback to tinted translucency when
cross-window blur is unavailable.

### Constraints (decided)
- Real background blur: `Window.setBackgroundBlurRadius()` (API 31+), only if
  `WindowManager.isCrossWindowBlurEnabled`; listen with `addCrossWindowBlurEnabledListener`
  (turns off in battery saver). No refraction of content behind (Android cannot read it).
- Our own layers: tint, rim highlight, inner shadow, key glass, done with Canvas; optional
  AGSL `RuntimeShader` (API 33+) for the specular sweep. Keep a Canvas-only path for API < 33.

### Spike first (30 min, record result in Decision log)
Does background blur cover the whole IME window, including the transparent 62 pt headroom?
- Try: IME `window.window` → `setBackgroundDrawable(InsetDrawable(ColorDrawable(0x01000000), 0, headroomPx, 0, 0))`
  + `setBackgroundBlurRadius(r)`; screenshot over a colourful app (Settings search results).
- If blur leaks into the headroom: move bubbles/accent panel into a separate
  `PopupWindow` (`isClippingEnabled = false`, `TYPE_APPLICATION_ATTACHED_DIALOG` via
  `showAtLocation(view, ...)`), shrink the IME view to the keyboard only, and drop the headroom
  insets logic. Decide and continue.
- Check MuMu: `adb shell settings get global disable_window_blurs` and
  `dumpsys window | grep -i blur`. If unsupported, test the fallback there and the real blur on a phone.

### Files
- `Theme.kt`: add `style: Style { CLASSIC, GLASS }`, plus glass fields: `panelTint` (ARGB with
  alpha), `keyFill` (alpha), `functionKeyFill`, `rimTop`, `rimBottom`, `innerShadow`,
  `blurRadiusPx`, `panelCornerPt = 26f`, `panelInsetPt = 6f`. `forContext(ctx, prefs, blurAvailable)`.
- new `GlassRenderer.kt`: `drawPanel(canvas, rect)`, `drawKey(canvas, rect, pressed, function)`,
  `drawBubble(path)`. Pre-allocate Paints/Shaders (no allocation in onDraw).
- `KeyboardView.kt`: delegate keycap/panel/bubble drawing to the renderer for GLASS; keep CLASSIC path.
- `IosKeyboardService.kt`: window setup (transparent background, blur radius, listener), re-theme on change.
- new `Springs.kt`: tiny critically damped spring for bubble scale (0.6 → 1.0, ~180 ms) and
  accent panel width morph; drive with `postInvalidateOnAnimation`.

### Visual spec (approximate iOS 26; tune against reference screenshots)
- Panel: rounded rect inset 6 pt left/right/bottom, corner radius 26 pt.
  Tint light: `#8CF2F2F7` with blur (alpha 0.55), `#D9F2F2F7` without (0.85).
  Tint dark: `#99202022` with blur (0.60), `#E61C1C1E` without (0.90).
- Keys: white alpha 0.75 (light) / white alpha 0.16 (dark); function keys alpha 0.45 / 0.10.
  1 px top rim gradient white alpha 0.9 → 0; 1 px bottom inner shadow black alpha 0.12.
  Corner radius 8.5 pt (iOS 26 keys are rounder than 5 pt classic).
- Pressed: key brightens (+0.15 alpha) with 80 ms ease-out, no colour flip.
- Bubble: same glass key fill + soft shadow (radius 6 pt, y 2 pt, black 0.18), spring-in scale.
- Return blue stays solid `#007AFF` with rim.

### Tasks
1. Spike (above) → decision.
2. Theme fields + Prefs `theme` + blur availability plumbing.
3. GlassRenderer Canvas path; switch via theme.
4. AGSL specular sweep for keys (API 33+): uniform `iResolution`, gradient band at 30°,
   intensity 0.12; apply as `RenderEffect` on a child layer view if per-key shader is too costly.
   Optional: skip if Canvas look is already convincing.
5. Springs for bubble + accent panel.
6. Fallback on `isCrossWindowBlurEnabled == false` and live switch on listener callback.

### Test
- Screenshots (light, dark) over Settings search results with `theme=glass` and `classic`.
- `adb shell settings put global low_power 1` (battery saver) → keyboard switches to fallback
  without restart; `0` restores.
- Typing tests from item 0 loop still pass (rendering-only change).
- Frame check: `adb shell dumpsys gfxinfo dev.leconnn.ioskeyboard` → janky frames < 5 % while
  typing 30 chars quickly.

---

## Item 3: Suggestion bar foundation, Smart Punctuation, Math Results

Introduces the suggestion bar (needed by math, item 5 and 6) and pure-function text transforms
with JVM tests.

### Files
- `app/build.gradle.kts`: `testImplementation("junit:junit:4.13.2")`.
- new `text/FieldTraits.kt`: from `EditorInfo` → `smartAllowed`, `isPassword`, `isEmailOrUrl`,
  `noSuggestions`, `capsMode`.
- new `text/SmartPunctuation.kt` (pure): `fun transform(before: CharSequence, typed: String): Edit?`
  where `Edit(deleteBefore: Int, insert: String)`.
- new `text/MathCompletion.kt` (pure): `fun resultFor(textBeforeCursor: String): String?`.
- new `SuggestionBar.kt`: drawn inside `KeyboardView` (top 44 pt of keyboard area when
  `KeyboardPrediction` on; total height becomes 216 + 44 pt). 3 slots separated by 0.5 pt dividers;
  centre slot bold when it is the autocorrect choice. `Listener.onSuggestionPicked(index)`.
- `KeyboardView.kt`: layout offset for bar; `setSuggestions(List<Suggestion>)`.
- `IosKeyboardService.kt`: on every text change compute suggestions (math first), commit on tap.
- tests: `app/src/test/java/.../SmartPunctuationTest.kt`, `MathCompletionTest.kt`.

### Smart Punctuation rules (iOS)
- `"`: opening `“` if previous char is start, whitespace, or one of `([{“‘` else closing `”`.
- `'`: opening `‘` under the same rule, else `’` (covers apostrophes: don’t, it’s, ’90s handled as `’`).
- `--` (second hyphen typed right after `-`) → `—`.
- Never in password/email/URL/code-like fields (`FieldTraits.smartAllowed`).
- Long-press variants already offer straight quotes; leave them unchanged.
- Undo: backspace immediately after a transform restores the literal input (one-shot).

### Math Results rules
- Trigger when text before cursor ends with an expression followed by `=`:
  regex on the trailing token run `[0-9.,+\-*/×÷^()% ]+=$`, at least one operator.
- Evaluator: recursive descent, Double, precedence `^` > `* / × ÷ %` > `+ -`, unary minus, parentheses.
- Format: up to 10 significant digits, strip trailing zeros, no exponent below 1e15.
- Show as centre suggestion; tapping inserts the result. (iOS also shows it inline; skip.)

### Tests (JVM, expected)
```
SmartPunctuation: "" + "\"" -> “ ; "say “hi" + "\"" -> ” ; "don" + "'" -> ’ ; "a -" + "-" -> delete 1, "—"
Math: "12*4=" -> "48" ; "(2+3)^2=" -> "25" ; "10/4=" -> "2.5" ; "abc=" -> null ; "5=" -> null
```
adb: in Settings search type `12*4=` (123 plane taps) → screenshot shows "48" in bar; tap it → field `12*4=48`.

---

## Item 4: Emoji keyboard

### Decisions
- Glyphs: system emoji font (Noto Color Emoji on most devices). Apple emoji art is not used.
- Data: Unicode `emoji-test.txt` (Unicode licence) for the latest version on unicode.org →
  generated asset `app/src/main/assets/emoji.tsv` (`category \t emoji \t name \t skinToneBase`).
  Generator: `tools/emoji/gen_emoji.py` (stdlib only), checked-in output.
- Filter at runtime with `Paint.hasGlyph()` so missing emoji never show as tofu.
- iOS categories and order: Frequently Used, Smileys & People, Animals & Nature, Food & Drink,
  Activity, Travel & Places, Objects, Symbols, Flags. Map Unicode groups:
  Smileys & Emotion + People & Body → Smileys & People; Component dropped.
- Layout (iOS): horizontally scrolling grid, 5 rows, ~45 pt cells, section labels at top
  ("FREQUENTLY USED"), bottom bar: `ABC` key, category icons (use our `glyphs/` emoji_* PNG icons
  if present else simple line icons drawn in `Glyphs`), delete key.
- Entry point: Face ID iPhone layout. Bottom row becomes `123 | emoji | space | return`, and a
  30 pt strip below the keys holds globe (left, switch IME, long-press picker) and dictation
  (right, item "Enable Dictation" via `SpeechRecognizer` if available, else hidden). This matches
  iOS on Face ID phones and keeps IME switching. Geometry: More 1..45.5, emoji 47.5..92.5,
  space 94.5..280.5, return 282.5..374 (padded frames, same as now with emoji in the globe slot).

### Files
- new `emoji/EmojiData.kt` (asset loader, lazy, background thread), `emoji/EmojiPanel.kt` (custom
  View with own scroller via `OverScroller`, fling, section snapping on category tap),
  `emoji/Recents.kt` (Prefs-backed, max 32, MRU).
- `KeyboardLayout.kt`: `KeyKind.EMOJI`; bottom strip keys (`GLOBE`, `DICTATION`) in a separate row 4.
- `KeyboardView.kt`: mode switch between keys and `EmojiPanel` (same height), bottom strip drawing.
- `IosKeyboardService.kt`: commit emoji (`commitText`), recents update, dictation hook.
- Skin tones: long-press an emoji with a base modifier → panel with 6 variants (same accent-panel
  code path); remember last tone per base in Prefs.
- Emoji search (iOS 17+ "Search Emoji" field) is phase 2: needs an in-keyboard text field; skip now.

### Test
- Tap emoji key → panel; tap 😀 → field contains 😀; reopen → 😀 under Frequently Used.
- Fling horizontally; tap "Flags" icon → scrolled to flags section (screenshot).
- Long-press 👍 → tone panel; slide + release → 👍🏽.
- Globe in bottom strip still switches IME; long-press shows picker.

---

## Item 4b: Apple emoji art in the keyboard (optional, personal use)

**Goal:** the emoji panel (grid, Frequently Used, skin-tone popup, category icons) can draw Apple's
emoji art instead of the system font, behind a settings switch. Inserted text is unchanged
(plain Unicode); other apps keep drawing it with the system emoji font.

### Decisions
- Source: `System/Library/Fonts/CoreAddition/AppleColorEmoji-160px.ttc` from the user's IPSW (sbix
  PNG strikes; Android cannot load sbix fonts). Apple's licence limits the art to Apple devices,
  so the generated asset is personal-use only: **gitignored, built locally, never committed**.
  The app works without it (switch hidden, system emoji used).
- Glyph lookup by shaping each emoji string with HarfBuzz (`uharfbuzz`) against the font, so ZWJ
  sequences, flags, keycaps and skin tones resolve through the font's own GSUB rules; then take
  the glyph's largest sbix strike PNG (`fontTools`).
- Packed into one file `app/src/main/assets/apple_emoji.bin`, stored uncompressed (`noCompress`) and
  memory-mapped at runtime: header `AEMJ`, version, count, pixel size; index of (UTF-8 key,
  offset, length); then WebP images (96 px, quality 85, about 20-30 MB). Keys are the exact
  strings in `emoji.tsv` (base emoji and tone variants).
- Runtime: `AppleEmojiImages` decodes on demand with an LRU bitmap cache; missing keys fall back
  to the system font for that emoji.

### Files
- new `tools/emoji/extract_apple_emoji.py` (tracked, contains no Apple data): `.ttc` + `emoji.tsv`
  → `apple_emoji.bin`; prints coverage (found / missing).
- new `emoji/AppleEmojiIndex.kt` (pure Kotlin, JVM-tested parser of the index) and
  `emoji/AppleEmojiImages.kt` (asset mmap, decode, cache, draw).
- `Prefs.appleEmoji` (default true); Settings: "Apple Emoji Style" switch in the Appearance
  section, shown only when the asset exists.
- `EmojiPanel`: draw grid cells, tone popup and category icons through `AppleEmojiImages` when on.
- `.gitignore`: `app/src/main/assets/apple_emoji.bin`; `app/build.gradle.kts`: `noCompress += "bin"`.

### Test
- JVM: parse a synthetic index (2 entries) and look up offsets.
- Extraction coverage ≥ 99 % of `emoji.tsv` keys present in the IPSW font.
- MuMu: screenshots with the switch on (Apple art) and off (system emoji); tap an emoji with it on
  → the field gets the same Unicode as with it off; tone popup shows Apple art; regression loop.

---

## Item 5: Predictive text + Auto-Correction

### Decisions
- Word list: hermitdave FrequencyWords `en_50k.txt` (CC BY-SA 4.0, OpenSubtitles) → filtered
  (lowercase alpha + apostrophe, drop profanity list optional) → asset `dict/en.tsv`
  (`word \t count`), ~600 KB. Generator `tools/dict/build_dict.py`. Credit in README.
- Engine runs in-process, loads on a background thread at `onCreate`; until loaded, no suggestions.
- Keys geometry from `Layouts.letters` (decoded Choco centres) for proximity costs.
- Use composing text for the current word (`setComposingText`) so corrections replace cleanly;
  `finishComposingText` on space/punctuation/cursor move (`onUpdateSelection`).

### Files
- new `text/Dictionary.kt`: arrays sorted by word + frequency; prefix lookup by binary search;
  `contains`, `freq`, `completions(prefix, n)`.
- new `text/Corrector.kt` (pure, JVM-testable): candidates within edit distance 2 using
  weighted Damerau-Levenshtein: substitution cost 0.4 for adjacent keys (centre distance
  ≤ 1.3 key pitches), 1.0 otherwise; transposition 0.6; insertion/deletion 0.9.
  Score = ln(freq + 1) − 3.0 · cost. Autocorrect only if: word not in dictionary (or freq < 3)
  AND best score − typed score ≥ 2.0 AND word length ≥ 2. Contractions table:
  im→I'm, dont→don't, cant→can't, wont→won't, ive→I've, id→I'd (only if "id" not followed by
  uppercase intent; keep simple), youre→you're, thats→that's, isnt→isn't, didnt→didn't, i→I.
- new `text/UserWords.kt`: learned words + bigrams (SQLite via framework `SQLiteOpenHelper`,
  device-protected storage). Learn a word after it is committed twice without being reverted.
  Next-word predictions = top bigrams for previous word, else top unigram completions.
- new `text/Predictor.kt`: orchestrates: math (item 3) > text replacement > completions/corrections.
  Returns `[left, centre, right]`: iOS convention, when autocorrect will fire: left = `“typed”`
  (keeps literal), centre = correction (bold). Otherwise centre = most likely completion.
- `IosKeyboardService.kt`: composing state machine:
  - letter typed → append to composing word → `setComposingText`, recompute suggestions.
  - space/punct → if autocorrect choice exists and enabled → commit correction + punct; remember
    `lastAutocorrect = (original, corrected)`.
  - backspace right after an autocorrect → restore original + add to `rejected` (session set,
    and UserWords so it is never corrected again).
  - suggestion tap → commit that word + space.
  - Text Replacement: if composing word equals a shortcut → centre suggestion = phrase; on space,
    replace (iOS does this automatically).
- Check Spelling: underline unknown words in the composing span only (`SuggestionSpan`
  with `FLAG_MISSPELLED` on composing text). Android cannot underline committed text from the IME.
- Inline predictions (phase 2): `setComposingText(typed + completionRemainder)` with a
  `ForegroundColorSpan` grey on the remainder; Tab-like accept on space. Ship behind
  `KeyboardInlineCompletion`, default off until verified in several apps.

### Tests (JVM, expected)
```
Corrector: "teh"->"the", "helo"->"hello", "recieve"->"receive", "thw"->"the" (w adjacent e),
           "im"->"I'm", "hello"->null (valid), "xqzv"->null (no good candidate)
Predictor: after "thank" -> completions include "thanks", "thank you" (bigram after learning)
```
adb (MuMu): type `teh` + space → field `the `; type `teh` + space + delete → `teh` (reverted, no space);
type `omw` + space → `On my way! `; screenshot bar while typing `hel` → completions.

---

## Item 6: Slide to type (QuickPath)

### Decisions
- Algorithm: SHARK2-style template matching (shape + location channels) over the dictionary,
  pruned by first/last key; integrates with Predictor for ranking and the suggestion bar for alternates.
- Templates: ideal path = polyline through key centres of the word's letters (repeated letters
  collapsed), from `Layouts.letters` (same centres as `continuous_path_base_layout.plist`).
- Trail rendering: soft glow line following the finger (iOS draws a fading trail; extracted
  `UIKeyboardContinuousPathEffectGlow` asset exists in `reference/.../glyphs/uikit` for reference only).

### Files
- new `swipe/PathRecorder.kt`: collects points (pt coordinates) with timestamps; decides swipe vs tap.
- new `swipe/SwipeDecoder.kt` (pure, JVM-testable):
  1. Resample input path to N = 50 points equidistant.
  2. Candidate filter: words whose first letter key is within 1.5 pitches of path start and last
     letter key within 1.5 pitches of path end; length window by path length.
  3. Location distance: mean distance between resampled path and template (pt).
  4. Shape distance: same after normalising both to unit bounding box, centred.
  5. Score = ln(freq) − 0.05·loc − 4.0·shape; top 3. Precompute templates lazily per first letter,
     cache resampled arrays (FloatArray) to keep decode < 30 ms for 50k words.
- `KeyboardView.kt`: when a CHAR touch moves > 0.6 key pitch away from its start key within
  the first 400 ms and `KeyboardContinuousPathEnabled`, switch the touch to `Mode.SWIPE`:
  cancel long-press/preview, record path, draw trail; on UP → `listener.onSwipe(points)`.
- `IosKeyboardService.kt`: `onSwipe` → decode → commit best (with leading space if previous char
  is a letter, iOS behaviour), set suggestions to alternates (tapping one replaces the swiped word),
  set `lastSwipedWord` so the next backspace deletes the whole word when
  `KeyboardContinuousPathDeleteWholeWord` is on.
- Auto-capitalisation applies to the first letter of swiped words.

### Tests
- JVM: generate synthetic paths from templates (+ Gaussian noise σ = 6 pt, random timing) for
  100 common words → top-1 accuracy ≥ 90 %, top-3 ≥ 97 %.
- adb (MuMu): `input motionevent DOWN/MOVE.../UP` along h→e→l→o centres → field `hello`;
  then `del_key` → field empty (whole-word delete); with the pref off → `hell`.

---

## Order, dependencies, rough size

| # | Item | Depends on | Size |
|---|---|---|---|
| 1 | Settings screen + Prefs | none | M |
| 2 | Liquid Glass | 1 (theme pref) | M (+ spike) |
| 3 | Suggestion bar, smart punctuation, math | 1 | M |
| 4 | Emoji keyboard + Face ID bottom strip | 1, 3 (bar hidden in emoji mode) | L |
| 4b | Apple emoji art in the keyboard (setting) | 4 | M |
| 5 | Predictive text + autocorrect | 1, 3 | L |
| 6 | Slide to type | 3, 5 (dictionary, predictor) | L |

After each item: build, run its tests + the regression typing loop below, commit (ask first),
update this file's checklist.

### Regression loop (run after every item)
"Try typing here" in SettingsActivity is a sentence-capitalised field at (450, 580) on the main settings page.
```bash
source tools/adb/kb.sh; H="Try typing here"
adb $D shell am start -n $PKG/.SettingsActivity; focus 450 580
typew hello; del_key; ft "$H"                       # Hell
clear_field; shift_key; shift_key; typew abc; ft "$H"   # ABC (caps lock); then shift_key to unlock
clear_field; typew don; k123; tap $(python -c "print(int((82.25+4*52.5)*2.4))") $R3; typew t; ft "$H"  # Don’t (curly: Smart Punctuation)
clear_field; typew hi; space; space; typew ok; ft "$H"  # Hi. Ok
clear_field; typew one; clear_field; typew two; ft "$H" # Two (auto-cap after external clear)
```
Use `reset` (space + clear) between cases: planes persist across clears.
Last full pass: 2026-11-06 (R1–R6, item 1 S0–S10, item 3 M1–M8, P1–P2).

---

## Checklist
- [x] 1 Settings screen (2026-11-06, branch `item1-settings`)
- [x] 2 Liquid Glass (2026-11-06, branch `item2-glass`; spike decision: popups moved to a PopupWindow overlay)
- [x] 3 Suggestion bar / smart punctuation / math (2026-11-06, branch `item3-suggestions`)
- [x] 4 Emoji keyboard (2026-11-06, branch `item4-emoji`; dictation and emoji search deferred)
- [x] 4b Apple emoji art in the keyboard (setting, personal use; 2026-11-06, branch `item4-emoji`)
- [x] 5 Predictive text / autocorrect (2026-11-07, branch `item5-predictions`; inline predictions deferred)
- [x] 6 Slide to type (2026-11-07, branch `item6-swipe`)

## Decision log
- 2026-11-06: Plain Views, no AndroidX; geometry from decoded `iPhone-PortraitChoco` layouts;
  keycap inset 2 pt inside padded frame (from screenshots).
- 2026-11-06 (item 1): `SetupActivity` replaced by `SettingsActivity` (setup rows + preview at top).
  Settings use `Prefs` in device-protected storage; `kb.sh` gained `setp`/`getp`/`focus`/`ft`.
  One-handed: layout scaled to 0.86 width at one edge, chevron in the free strip returns to full
  width (writes the pref). App theme moved to `values`/`values-night` (DayNight parent needs API 29).
  Fixed: auto-capitalisation now re-evaluates in `onUpdateSelection` (external text changes).
  Not verifiable on MuMu: sound and haptic toggles (code paths only).
- 2026-11-06 (item 2): Spike results: `Window.setBackgroundBlurRadius` blurs the whole IME window
  (an InsetDrawable background does not limit it) but does follow a GradientDrawable's rounded
  corners. So the IME window now ends at the keys (`KeyboardView.headroomPt = 0`) and bubbles /
  accent panels draw in `PopupOverlay` (non-touchable PopupWindow child of the IME window,
  positioned with window-relative coordinates, `getLocationInWindow`). The in-app preview keeps
  in-view headroom. Glass panel = window background (tint, 26 pt top corners) + blur 24 dp;
  full width (a side inset would still be blurred). Keys: translucent fill, 8.5 pt radius, 1 px
  top rim gradient; popups opaque (no blur behind them) with a 160 ms easeOutBack spring.
  `onComputeInsets` override removed (defaults are right now). AGSL specular sweep skipped: the
  Canvas look was sufficient; revisit only if wanted. Frame stats while typing: 0.4 % janky,
  p99 7 ms. Light glass letter keys raised to 0.9 alpha for contrast.
- 2026-11-06 (item 3): Suggestion bar lives in `KeyboardView` (`barPt`, 44 pt, above the keys;
  hidden when Predictive Text is off or the field is password / no-suggestions). Suggestions are
  refreshed from `onUpdateSelection` (fires after every change). Only the centre slot is used so
  far (math results). `onText(text, literal)`: long-press variants are literal (no smart rewrite).
  Smart-punctuation undo is a one-shot `SmartUndo` cleared by any other key. Android's caps rule
  ignores curly quotes, so `capsAfterCurlyQuote` re-checks with them straightened. `%` in math is
  postfix percent (iOS calculator), not modulo. JVM tests: 25 passing (`./gradlew testDebugUnitTest`).
  `kb.sh`: `field_text` parses the UI dump as UTF-8 XML; `reset` added.
- 2026-11-06 (item 4): Emoji data from Unicode emoji-test.txt v18.0 (`tools/emoji/gen_emoji.py` →
  `assets/emoji.tsv`, 1923 emoji, 332 with tones; mixed-tone multi-person forms skipped). `EmojiPanel`
  is a helper drawn by `KeyboardView` in the keys area (not a separate View) so theme, scale and
  the popup overlay are shared; gestures starting in the area go to it. Reopens at Frequently Used
  (MRU, 32, in Prefs); chosen tone per base emoji in Prefs. Sections are at least as wide as their
  title. Bottom row is now `123 | emoji | space | return`; the globe moved to a 36 pt strip below
  the keys (Face ID layout). Deferred: dictation (needs a RECORD_AUDIO flow; MuMu has no speech
  recognizer to test with), emoji search field, stickers. The service ignores emojiRecents /
  emojiTones pref writes.
- 2026-11-06 (item 4b): The IPSW's only Apple emoji source is `AppleColorEmoji-160px.ttc` (font 0,
  sbix strikes 40/64/96/160). Images are `emjc` ("emj1" header: u16 width @8, height @10, appendix
  length @12; then LZFSE), plus `dupe` / `flip` entries pointing at another glyph
  (`referenceGlyphName`; flip = mirror). emjc pixel stage, per the emjc-decoder project's notes
  (no licence, so not copied; own implementation in `tools/emoji/emjc.c` + Python fallback):
  alpha plane, one filter byte per row, zigzag residuals (3 per pixel) with appendix biases,
  predictors none/gradient-select/left/up/average, then a base+2-difference transform to RGB.
  The font uses AAT (morx) substitution; HarfBuzz shapes it fine. Build the decoder with
  `zig cc -shared -O2 -o build/emjc/emjc.dll tools/emoji/emjc.c`.
  Result: 3571/3583 keys packed (99.7 %), 9.5 MB, 16 s with WebP method 4. Fixes over the first
  pass: glyphs without a 96 px image fall back to the 160 px strike (scaled), and Apple's two-half
  couple glyphs are composed at their shaped pen positions. Still missing (system emoji used):
  9 code points not in the font plus the text-style ♀️ ♂️ ⚕️. Settings switch "Apple Emoji Style"
  appears only when the asset is in the build; inserted text is identical with it on or off.
- 2026-11-07 (item 5): Dictionary from hermitdave FrequencyWords en_50k (CC BY-SA 4.0, credited in
  the asset header) via `tools/dict/build_dict.py`: OpenSubtitles splits contractions, so fragments
  ("don", "isn", "ll") are dropped, ~60 contractions added with tiered counts, apostrophe-less
  forms ("dont", "im") removed unless real words ("its", "were", "well"), and a curated list of
  common misspellings found in subtitles ("helo", "alot", "untill") removed. 46,694 words.
  Corrector: weighted OSA distance walked over the sorted keys with shared DP rows and prefix
  pruning (~20 ms per word on the JVM). Tuned costs: neighbour 0.5, other 1.0, swap 0.6, extra or
  missed 0.9, missed apostrophe 0.3, missed double letter 0.1; score ln(freq+1) - 3*cost; correct
  unknown words on a gain >= 2.0 (short words: cost <= 1.0) and rare known words (< 2000) on a
  gain >= 3.0; apostrophe-less contractions always map to the contraction. `WordComposer` keeps
  the current word as composing text, refreshes slots synchronously on every key (onUpdateSelection
  can lag), applies corrections / text replacements on space and . , ! ? ; : and return, undoes an
  autocorrection on the next backspace (and records the rejection), and learns words and word
  pairs in `UserWords` (SQLite; insert-or-ignore + update because UPSERT needs Android 11).
  Accented letters from long-press extend the word; smart curly apostrophes stay inside words.
  Check Spelling underlines only the composing word (Android can't style committed text from
  the IME). Deferred: inline (ghost text) predictions. Tests: 19 JVM corrector/predictor cases;
  on MuMu P1-P9, L1-L2, S1-S3 and the regression loop pass.
- 2026-11-07 (item 6): `SwipeDecoder` (pure Kotlin): templates through `KeyProximity` key centres
  (repeats collapsed, apostrophes dropped so contractions decode), both paths resampled to 50
  points, candidates limited to words whose first/last keys are within 1.5 pitches of the path
  ends and whose length is within ~2.2x. Score = ln(freq+1) - 0.12*location (pt) - 12*shape
  (normalised) - 1.5*end distance (pitches); the end term fixed hello/help and the 0.12 location
  weight fixed typing/thing. Synthetic accuracy (100 common words, sigma 6 pt): 95 % top-1,
  100 % top-3; clean paths for hello/typing/keyboard/phone/world asserted. In `KeyboardView`, a
  letter touch that travels 0.6 pitch before the long-press fires becomes `Mode.SWIPE` (letters
  plane, single finger), with a fading accent-coloured trail. `WordComposer.onSwipe` inserts the
  best word with a leading space when needed and an automatic trailing space, shows the two
  alternatives in the bar (tap to swap), deletes the whole word on the next backspace (setting),
  and drops the automatic space before . , ! ? ; :. Testing: `swipe_word` in `kb.sh` sends the
  whole gesture in one `adb shell` (separate calls are too slow: the long-press fires first).
- 2026-11-07 (performance): Profiling showed refresh p90 66 ms (max 254), swipe decode ~104 ms,
  8.5 % janky frames, all on the UI thread. `WordComposer` now runs predictions and swipe decoding
  on one worker thread (newest request wins; `onSlotsChanged` posts results back), computes the
  exact word synchronously at a word boundary only if the worker hasn't caught up, and skips a
  refresh identical to the last one (key + its selection update). Also: 26x26 neighbour table in
  `KeyProximity`, single-pass top-k `completions`, normalised swipe templates cached, 1-char
  `capsAfterCurlyQuote` pre-check, no per-frame allocations in drawing. Result: 0.55 % janky,
  p95 18 ms; 50 JVM tests and the regression loop pass.
