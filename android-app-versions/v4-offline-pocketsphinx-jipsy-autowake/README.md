# 🦇 Android App Version 4 (`BatmanWledDashboard-v4.apk` / Tag `v8.0`)
**100% Offline CMU PocketSphinx Speech Recognition • `"Jipsy Listen"` / `"Wake Up"` Hands-Free Wake Word • 1.8s Silence Auto-Stop • `[Auto-Wake: ON/OFF]` Toggle • Voice Quick Notes & Effect Intensity Control**

---

## 📦 Files in This Version
- **[`BatmanWledDashboard-v4.apk`](BatmanWledDashboard-v4.apk)** (`4.98 MB` — embeds the complete US-English `en-us-ptm` acoustic model, `libpocketsphinx_jni.so` for `armeabi-v7a`, phonetic dictionary, and JSGF grammar for 100% offline speech recognition)
- **[`MainActivity.java`](MainActivity.java)** — Native Android 4.2 (`API 17`) host with embedded CMU PocketSphinx continuous wake-word engine, 1.8s end-of-speech silence detector, native Android TTS (`com.samsung.SMT` / `com.google.android.tts`), Wi-Fi Auto-Reconnect Watchdog, and Dual HTTP Bridge
- **[`index.html`](index.html)** — Landscape split-screen dashboard with `[Auto-Wake: ON/OFF]` toggle button, `[Mic] Speak Now`, `[Cmd] List`, Always-Visible Desk Timer (`10m`/`15m`/`30m`/`Custom` numpad), Quick Notes & Task Scheduler, and Patiala Weather engine
- **[`sync/wakeup.keys`](sync/wakeup.keys)** — Keyword-spotting thresholds for `"jipsy listen"`, `"jipsy wake up"`, `"wake up"`, and `"jipsy"`
- **[`sync/batman.dict`](sync/batman.dict)** — Custom CMU phonetic dictionary (188 entries) including 5 accent/pronunciation variants for `"jipsy"` and 3 for `"listen"`
- **[`sync/batman.gram`](sync/batman.gram)** — JSGF grammar covering all WLED lighting, colour, brightness, effect intensity, preset, timer, quick note dictation, task scheduling, and briefing commands

---

## ✨ What Was Added (New Features in Version 4)

### 1. 🎙️ 100% Offline Embedded Voice Recognition (CMU PocketSphinx JNI)
- **Why it was needed**: On Android 4.2.2 (`SM-T111`), Google's built-in `RecognizerIntent` (`com.google.android.voicesearch:4.0.0`) does not support downloadable offline speech packs and fails whenever the tablet is connected to the offline ESP32 WLED Wi-Fi Access Point (`wledtabletop` at `4.3.2.1`).
- **What was added**:
  - Embedded **`pocketsphinx-android-5prealpha`** (`pocketsphinx.jar` + native `armeabi-v7a/libpocketsphinx_jni.so`) and the full **`en-us-ptm`** acoustic model directly inside the APK.
  - Added automatic asset extraction in `MainActivity.initPocketSphinxBlocking()` that copies the acoustic model, dictionary, grammar, and wake-word list to internal storage on startup—working **100% offline with zero internet required**.

### 2. 🗣️ Hands-Free Catch Phrase: `"Jipsy Listen"` & `"Wake Up"`
- Continuous low-power keyword spotting (`SEARCH_WAKEUP`) listens for:
  - **`"Jipsy listen"`**
  - **`"Jipsy wake up"`**
  - **`"Wake up"`**
- When triggered, Jipsy replies out loud via native Android TTS (*"Yes, I am listening."*), waits **2.5 seconds** (so the microphone never hears the tablet's own speaker), and then opens the command microphone (`SEARCH_BATMAN_CMD`) for up to **10 seconds**.

### 3. 🔘 Dedicated `[Auto-Wake: ON]` / `[Auto-Wake: OFF]` Toggle Button
- Added a one-tap toggle button (`#autoWakeBtn`) directly below the robot companion on the main dashboard:
  - **`[Auto-Wake: ON]` (Green)**: Continuously listens for the `"Jipsy listen"` / `"Wake up"` catch phrase in the background.
  - **`[Auto-Wake: OFF]` (Red)**: Immediately stops background catch-phrase listening when privacy or silence is desired. You can still tap **`[Mic] Speak Now`** at any time to speak a single command manually, or tap **`[Auto-Wake: OFF]`** again to re-enable continuous listening.
  - Persisted in Android `SharedPreferences` (`auto_wake_enabled`) across app restarts.

### 4. 📝 Voice Dictation for Quick Notes & Effect Intensity Percentage
- **Voice Quick Notes Dictation**: Speak *"Write note exam tomorrow"*, *"Quick note check sensor pin"*, or *"Add note submit assignment"* to automatically append bulleted notes into the Quick Scratchpad (`localStorage` + `SharedPreferences`).
- **Voice Effect Intensity Control**: Speak *"Set effect intensity to 80 percent"* or *"Intensity 50 percent"* to dynamically adjust WLED's animation intensity (`seg: [{ ix: val }]`) and sync the on-screen Effect Intensity slider.

### 5. 🦇 Custom Yellow-and-Black Batman Emblem App Icon
- Added multi-density (`mdpi`, `hdpi`, `xhdpi`, `xxhdpi`) custom Bat-Signal launcher icons (`@drawable/ic_batman_logo`) on the Android home screen and app drawer.

---

## 🔧 What Was Improved & Fixed Over Version 3

### 1. ⚡ Instant Command Execution After Last Word (**1.8s Silence Buffer Max**, Instead of Waiting Full 10 Seconds)
- **Bug Fixed**: CMU PocketSphinx's `RecognizerThread` emits `onPartialResult(Hypothesis)` every ~100ms with the *exact same* hypothesis text even while the user is silent after finishing a sentence. Previously, every `onPartialResult` call reset the silence timer, forcing the user to wait the entire 10-second window before a command executed.
- **Improvement**: `onPartialResult()` in `MainActivity.java` now compares `!clean.equals(lastHeardCommandPartial)` and **only resets the 1.8-second silence countdown (`END_OF_COMMAND_SILENCE_MS = 1800L`) when a new word is actually spoken**. As soon as you stop speaking your last word, the command executes automatically within **1.8 seconds**.

### 2. 🎯 Multi-Accent Phonetic Dictionary & Higher Wake-Word Sensitivity
- Added **5 phonetic pronunciations** for `jipsy` (`JH IH P S IY`, `JH IH P Z IY`, `G IH P S IY`, `JH IH B S IY`, `Y IH P S IY`) and **3 pronunciations** for `listen` (`L IH S AH N`, `L IH S IH N`, `L IH Z AH N`) in `batman.dict`.
- Tuned per-phrase keyword spotting thresholds in `wakeup.keys` (`jipsy listen /1e-3/`, `jipsy wake up /1e-3/`, `wake up /1e-5/`) and shortened the post-TTS cooldown so `"Jipsy listen"` re-arms promptly after the robot finishes speaking.

### 3. 🛠️ Unix `LF` (`\n`) Sanitizer for PocketSphinx C Parser
- Added `copyTextAssetUnixLf()` in `MainActivity.java` to strip Windows `\r` (`CRLF`) bytes when extracting `batman.dict`, `batman.gram`, `wakeup.keys`, and `feat.params` onto the tablet's filesystem, preventing C-level `dict.c` / `kws_search.c` parsing errors.

### 4. ⏱️ Always-Visible Desk Timer Bar + Custom Touch Numpad
- Promoted the Desk Timer (`10 Min`, `15 Min`, `30 Min`, `Custom`, `Stop`, and `Auto-Off LEDs`) from a hidden tab to an **always-visible control card** on the right panel, and replaced the native keyboard input with an on-screen **Touch Numpad Modal** so the Android keyboard never covers the kiosk screen.
