# 🦇 Batman WLED Desk Dashboard & Companion (Android 4.2+ Kiosk & Web Evolution)

A complete hardware-software desk companion project that transforms a legacy **Samsung Galaxy Tab 3 Lite (`SM-T111`, Android 4.2.2 Jelly Bean / API 17)** into a dedicated 24/7 desk controller, voice assistant, academic schedule display, and scratchpad for an **ESP32 WLED Batman Logo** (`WS2812B` addressable LEDs).

![Platform](https://img.shields.io/badge/Android-4.2.2%2B%20(API%2017)-3DDC84?logo=android)
![Hardware](https://img.shields.io/badge/Hardware-ESP32%20%2B%20WS2812B-f5a623)
![Network](https://img.shields.io/badge/Wi--Fi%20AP-wledtabletop%20(4.3.2.1)-58a6ff)
![APK Versions](https://img.shields.io/badge/Android%20APKs-v1%20%7C%20v2%20%7C%20v3%20%7C%20v4%20(Offline%20ASR)-a371f7)

---

## 📖 Table of Contents
1. [Project Overview](#-project-overview)
2. [Complete Version History (4 Web Versions + 4 Android App Versions)](#-complete-version-history-4-web-versions--4-android-app-versions)
   - [Web Version 1 (`dashboard.htm` / Tag `v1.0`) — Initial Modern Web Prototype](#web-version-1-dashboardhtm--tag-v10--initial-modern-web-prototype)
   - [Web Version 2 (`dashboard1.htm` / Tag `v2.0`) — Android 4.2 WebKit Compatibility & Timetable](#web-version-2-dashboard1htm--tag-v20--android-42-webkit-compatibility--timetable)
   - [Web Version 3 (`dashboard2.htm` / Tag `v3.0`) — Landscape Split-Screen, Robo Companion & Canvas Colour Wheel](#web-version-3-dashboard2htm--tag-v30--landscape-split-screen-robo-companion--canvas-colour-wheel)
   - [Web Version 4 (`dashboard3.htm` / Tag `v4.0`) — LocalStorage Desk Scratchpad & Android TTS Bridge Hook](#web-version-4-dashboard3htm--tag-v40--localstorage-desk-scratchpad--android-tts-bridge-hook)
   - [Android App Version 1 (`BatmanWledDashboard-v1.apk` / Tag `v5.0`) — Native Kiosk Wrapper, Wi-Fi Auto-Reconnect Watchdog, Intensity Slider & Task Scheduler](#android-app-version-1-batmanwleddashboard-v1apk--tag-v50--native-kiosk-wrapper-wi-fi-auto-reconnect-watchdog-intensity-slider--task-scheduler)
   - [Android App Version 2 (`BatmanWledDashboard-v2.apk` / Tag `v6.0`) — Interactive Talking Robot (Weather, Tasks, Notes, Full Briefing) & Colour/Effect Randomizers](#android-app-version-2-batmanwleddashboard-v2apk--tag-v60--interactive-talking-robot-weather-tasks-notes-full-briefing--coloureffect-randomizers)
   - [Android App Version 3 (`BatmanWledDashboard-v3.apk` / Tag `v7.0`) — Desk & Sleep Countdown Timer (`10m`/`15m`/`30m`/`Custom`) + Voice Command Recognition (`🎤 Talk to Robot`)](#android-app-version-3-batmanwleddashboard-v3apk--tag-v70--desk--sleep-countdown-timer-10m15m30mcustom--voice-command-recognition--talk-to-robot)
   - [Android App Version 4 (`BatmanWledDashboard-v4.apk` / Tag `v8.0`) — 100% Offline CMU PocketSphinx ASR, `"Jipsy Listen"` / `"Wake Up"` Catch-Phrase, 1.8s Silence Auto-Stop & `[Auto-Wake: ON/OFF]` Toggle](#android-app-version-4-batmanwleddashboard-v4apk--tag-v80--100-offline-cmu-pocketsphinx-asr-jipsy-listen--wake-up-catch-phrase-18s-silence-auto-stop--auto-wake-onoff-toggle)
3. [Deep Dive: Solving the Android 4.2 WLED AP Wi-Fi Disconnect & Captive Portal Bug](#-deep-dive-solving-the-android-42-wled-ap-wi-fi-disconnect--captive-portal-bug)
4. [Repository Structure](#-repository-structure)
5. [Building & Installing on Android 4.2](#-building--installing-on-android-42)

---

## 🎯 Project Overview

Controlling an ESP32 running **WLED** (`WS2812B` Batman wall/desk logo) typically requires opening a phone browser and connecting to the ESP32's Access Point (`wledtabletop`). Repurposing an old **Samsung Galaxy Tab 3 Lite (Android 4.2.2)** as a permanent desk controller presented three major engineering challenges:

1. **Legacy WebKit Engine (Android 4.2 / Chrome 18-era WebView)**: Does not support ES6 (`const`, `let`, `async/await`, `fetch`), CSS Grid, CSS Custom Properties (`--var`), modern Flexbox, or the Web Speech API (`window.speechSynthesis`).
2. **Android 4.2 Captive Portal & TouchWiz Wi-Fi Watchdog**: Because the ESP32 WLED Access Point (`wledtabletop` at `4.3.2.1`) has no upstream internet connection and spoofs DNS for its captive portal, Samsung's TouchWiz OS pops up a *"Sign in to Wi-Fi network"* screen and **automatically disconnects from the Wi-Fi after 30–60 seconds**.
3. **Cross-Origin Restrictions, Offline Speech Recognition & Persistent Storage**: Running a dashboard from local device storage while sending JSON `POST` payloads to `http://4.3.2.1/json/state`, recognizing voice commands 100% offline without Google cloud servers, and permanently saving desk notes and tasks.

This repository preserves **all 4 Web Dashboard iterations (`v1.0`–`v4.0`)** and **all 4 Native Android Kiosk App versions (`v5.0` App v1 through `v8.0` App v4)** in both dedicated directories and sequential Git tags.

---

## 🚀 Complete Version History (4 Web Versions + 4 Android App Versions)

| Stage / Tag | Version Name | Artifact / Directory | Key Improvements Over Previous Version |
| :--- | :--- | :--- | :--- |
| **Tag `v1.0`** | **Web Dashboard v1** | [`web-dashboard-versions/v1-dashboard.htm`](web-dashboard-versions/v1-dashboard.htm) | Initial portrait mobile UI using CSS Flexbox/Grid & ES6 `fetch('/json/state')`; Power, Brightness, 6 Colour Swatches, Presets 1–3. |
| **Tag `v2.0`** | **Web Dashboard v2** | [`web-dashboard-versions/v2-dashboard1.htm`](web-dashboard-versions/v2-dashboard1.htm) | Rewritten in ES5 `XMLHttpRequest` + CSS `display: table` for Android 4.2 WebKit; added Tabs, Presets 1–8, and **Mon–Fri Academic Timetable Modal**. |
| **Tag `v3.0`** | **Web Dashboard v3** | [`web-dashboard-versions/v3-dashboard2.htm`](web-dashboard-versions/v3-dashboard2.htm) | Redesigned into **2-Column Landscape Desk Split**; added **SVG Robo Companion** (eyes sync with LED RGB), **HTML5 `<canvas>` Colour Wheel**, and Upcoming Class Voice Reciter. |
| **Tag `v4.0`** | **Web Dashboard v4** | [`web-dashboard-versions/v4-dashboard3.htm`](web-dashboard-versions/v4-dashboard3.htm) | Added **Quick Desk Notes Scratchpad Modal** (`localStorage` persistence) and `window.AndroidBridge.speakText()` hook for native Android TTS. |
| **Tag `v5.0`** | **Android App v1** | [`android-app-versions/v1-kiosk-wifi-watchdog/`](android-app-versions/v1-kiosk-wifi-watchdog/) (`25 KB` APK) | Wrapped in **Fullscreen Android 4.2 Kiosk APK**; added **Wi-Fi Auto-Reconnect Watchdog (`wledtabletop`)**, **Dual XHR + Java HTTP Bridge**, **Effect Intensity (`ix`) Slider**, **36-Wedge Colour Wheel**, and **Task Scheduler**. |
| **Tag `v6.0`** | **Android App v2** | [`android-app-versions/v2-talking-robo-randomizers/`](android-app-versions/v2-talking-robo-randomizers/) (`29 KB` APK) | Upgraded into an **Interactive Talking Robot** (speaks **Patiala Weather**, **Pending Tasks & Notes**, **Next Class**, and **Full Briefing** with animated talking mouth/antenna) + added **`🎲 Random Colour`** & **`✨ Random Effect (Same Colour)`** generators. |
| **Tag `v7.0`** | **Android App v3** | [`android-app-versions/v3-timer-and-voice-commands/`](android-app-versions/v3-timer-and-voice-commands/) (`33 KB` APK) | Added **Desk & Sleep Countdown Timer (`10m`, `15m`, `30m`, + `Custom` minutes)** with optional auto-LED shutoff + **Two-Way Voice Commands (`🎤 Give Voice Command to Robot`)** via native Android `RecognizerIntent` (`RECORD_AUDIO`) & Natural Language Command Parser. |
| **Tag `v8.0`** | **Android App v4** | [`android-app-versions/v4-offline-pocketsphinx-jipsy-autowake/`](android-app-versions/v4-offline-pocketsphinx-jipsy-autowake/) (`4.98 MB` APK) | Embedded **100% Offline CMU PocketSphinx Speech Recognition (`en-us-ptm` + JNI)** so voice commands work on offline `wledtabletop` Wi-Fi; added **`"Jipsy listen"` / `"Wake up"`** hands-free wake word, **1.8s silence auto-stop after last spoken word**, **`[Auto-Wake: ON/OFF]` toggle button**, **Voice Quick Notes dictation**, **Voice Effect Intensity % control**, Always-Visible Desk Timer + Touch Numpad, and custom Batman launcher icon. |

---

### Web Version 1 (`dashboard.htm` / Tag `v1.0`) — Initial Modern Web Prototype
- **File**: [`web-dashboard-versions/v1-dashboard.htm`](web-dashboard-versions/v1-dashboard.htm)
- **Features**:
  - Single-column dark-themed controller for WLED's `/json/state` JSON API.
  - Power toggle button, Brightness slider (`1–255`), 3×2 grid of 6 quick colour swatches, and 3 Preset buttons (`Preset 1–3`).
  - Automatic 4-second state polling (`setInterval(fetchState, 4000)`).
- **Limitations on Android 4.2**:
  - Relied on CSS variables (`--bg`), `display: flex`, `display: grid`, `const`/`let`, `async/await`, and `fetch()`, which failed to render or execute on the Samsung Galaxy Tab 3 Lite's stock Android 4.2 WebKit engine.

---

### Web Version 2 (`dashboard1.htm` / Tag `v2.0`) — Android 4.2 WebKit Compatibility & Timetable
- **File**: [`web-dashboard-versions/v2-dashboard1.htm`](web-dashboard-versions/v2-dashboard1.htm)
- **What was improved**:
  1. **100% Legacy Android 4.2 WebKit Compatibility**: Replaced `fetch()`/`async` with pure ES5 `XMLHttpRequest` (`xhrRequest`) and replaced Flexbox/Grid with `display: table`, `display: table-cell`, and HTML `<table>` grids.
  2. **Tabbed Navigation & 8 Presets**: Split controls into **Palette & Colors** and **Presets Tab** (`P 1` through `P 8`).
  3. **Academic Timetable Modal**: Added `[=] View Daily Timetable` with day selector tabs (`Mon`–`Fri`) displaying lectures, labs, and tutorials with color-coded badges (`Lecture`, `Practical`, `Tutorial`).

---

### Web Version 3 (`dashboard2.htm` / Tag `v3.0`) — Landscape Split-Screen, Robo Companion & Canvas Colour Wheel
- **File**: [`web-dashboard-versions/v3-dashboard2.htm`](web-dashboard-versions/v3-dashboard2.htm)
- **What was improved**:
  1. **2-Column Landscape Desk Layout (`46% / 54%`)**: Optimized for a horizontal desk tablet display with a top utility bar (`Patiala • Punjab • Desk Display`).
  2. **SVG Robo Companion**: Added an animated robot face on the left column whose eyes (`#robo-eye-l`, `#robo-eye-r`) dynamically mirror the live RGB colour of the Batman WLED logo (`data.seg[0].col[0]`).
  3. **HTML5 `<canvas>` Touch Colour Wheel**: Added a 12-wedge radial gradient colour picker with touch/drag events and an `80ms` debounce timer.
  4. **Upcoming Class Calculator & Speech Engine**: Added minute-of-day timestamps (`start`) to the timetable and implemented `reciteClosestSchedule()` to find and announce the next upcoming class.

---

### Web Version 4 (`dashboard3.htm` / Tag `v4.0`) — LocalStorage Desk Scratchpad & Android TTS Bridge Hook
- **File**: [`web-dashboard-versions/v4-dashboard3.htm`](web-dashboard-versions/v4-dashboard3.htm)
- **What was improved**:
  1. **Quick Desk Notes Scratchpad (`localStorage`)**: Added the `[✎] Quick Desk Notes` button and modal (`#notesModal`) to write, save, and clear notes locally on the tablet (`desk_dashboard_notes`).
  2. **Android Native TTS Hook**: Updated `safeSpeak(text)` to invoke `window.AndroidBridge.speakText(text)` when running inside a native Android WebView wrapper.

---

### Android App Version 1 (`BatmanWledDashboard-v1.apk` / Tag `v5.0`) — Native Kiosk Wrapper, Wi-Fi Auto-Reconnect Watchdog, Intensity Slider & Task Scheduler
- **Directory**: [`android-app-versions/v1-kiosk-wifi-watchdog/`](android-app-versions/v1-kiosk-wifi-watchdog/)
- **APK File**: [`BatmanWledDashboard-v1.apk`](android-app-versions/v1-kiosk-wifi-watchdog/BatmanWledDashboard-v1.apk) (`25,116 bytes`)
- **What was improved over Web v4**:
  1. **Standalone Fullscreen Android APK (`minSdkVersion 17`)**: Built [`MainActivity.java`](app/src/main/java/com/batman/wleddashboard/MainActivity.java) with `FLAG_FULLSCREEN` and `FLAG_KEEP_SCREEN_ON` so the tablet never sleeps on the desk.
  2. **Wi-Fi Auto-Reconnect Watchdog (`wledtabletop`)**: Added a background Java watchdog thread that checks Wi-Fi status every 6 seconds and automatically re-associates with `wledtabletop` (`4.3.2.1`) if Samsung's OS drops the connection, plus an on-screen `Wi-Fi: wledtabletop (4.3.2.1) ⚙` settings modal.
  3. **Native Android Text-To-Speech (`TextToSpeech`)**: Wired `window.AndroidBridge.speakText()` to Android's native `Locale.US` TTS engine so the tablet speaks out loud on Android 4.2.
  4. **Dual Persistence (`SharedPreferences` + `localStorage`) & Task Scheduler**: Added a checkable **Task Scheduler** (with time badges) alongside Quick Notes, backed by both HTML5 `localStorage` and Android native `SharedPreferences`.
  5. **WLED Effect Intensity Slider (`ix`) & 36-Wedge Smooth Colour Wheel**: Added real-time control for WLED's effect intensity (`seg: [{ ix: val }]`) and tripled the colour wheel resolution from 12 to 36 wedges.

---

### Android App Version 2 (`BatmanWledDashboard-v2.apk` / Tag `v6.0`) — Interactive Talking Robot (Weather, Tasks, Notes, Full Briefing) & Colour/Effect Randomizers
- **Directory**: [`android-app-versions/v2-talking-robo-randomizers/`](android-app-versions/v2-talking-robo-randomizers/) (and root [`BatmanWledDashboard.apk`](BatmanWledDashboard.apk))
- **APK File**: [`BatmanWledDashboard-v2.apk`](android-app-versions/v2-talking-robo-randomizers/BatmanWledDashboard-v2.apk) (`29,212 bytes`)
- **What was improved over Android App v1**:
  1. **Interactive Talking Robot Companion with Animated Mouth & Antenna**:
     - Added real-time SVG mouth and antenna animation (`animateRobotTalking()`) synchronized with speech duration.
     - Upgraded the single speech button into a **4-Button Voice Assistant Grid** (plus tap-on-face trigger):
       - **`🔊 Next Class`**: Announces the closest upcoming class for today.
       - **`☀ Weather`**: Speaks the current temperature, sky condition, daily high/low, and humidity in **Patiala, Punjab** (combines live Open-Meteo API sync with an offline diurnal hourly temperature model so weather works even while offline on the `wledtabletop` AP!).
       - **`📝 Tasks & Notes`**: Reads out loud the count and details of all pending tasks in the Task Scheduler, followed by the contents of the Quick Desk Notes scratchpad (plus a `🔊 Read Notes` button inside the Scratchpad modal).
       - **`🤖 Full Briefing`**: Delivers a complete morning/afternoon/evening voice briefing combining Patiala weather, next academic class, pending tasks, and quick notes.
  2. **🎲 Random Colour Generator (`setRandomColor()`)**:
     - Generates a vibrant 100%-saturation RGB colour in HSL space (`hslToRgb(Math.random(), 1.0, 0.5)`), updates the robot's eyes and colour preview bar, and sends `{ on: true, seg: [{ col: [rgb] }] }` to the Batman logo **without changing the current WLED effect**.
  3. **✨ Random Effect Generator Keeping Current Colour (`setRandomEffectKeepColor()`)**:
     - Selects from 41 curated WLED animations (`Breathe`, `Sparkle`, `Meteor`, `Twinklefox`, `Aurora`, `Fire 2012`, `Pacifica`, `Heartbeat`, etc.) and explicitly sends `{ on: true, seg: [{ fx: id, pal: 0, col: [currentRgb] }] }` so the Batman logo switches animation while **strictly preserving the current LED colour**.

---

### Android App Version 3 (`BatmanWledDashboard-v3.apk` / Tag `v7.0`) — Desk & Sleep Countdown Timer (`10m`/`15m`/`30m`/`Custom`) + Voice Command Recognition (`🎤 Talk to Robot`)
- **Directory**: [`android-app-versions/v3-timer-and-voice-commands/`](android-app-versions/v3-timer-and-voice-commands/) (and root [`BatmanWledDashboard.apk`](BatmanWledDashboard.apk))
- **APK File**: [`BatmanWledDashboard-v3.apk`](android-app-versions/v3-timer-and-voice-commands/BatmanWledDashboard-v3.apk) (`33,308 bytes`)
- **What was improved over Android App v2**:
  1. **⏱ Desk & Sleep Countdown Timer Tab (`10 Min`, `15 Min`, `30 Min`, + `Custom`)**:
     - Added a dedicated **`⏱ Desk Timer`** tab and a live top-bar countdown badge (`⏱ MM:SS`).
     - Includes fixed one-tap buttons for **`10 Min`**, **`15 Min`**, and **`30 Min`**, plus a **`Custom`** button that opens a custom minute input (`1–999` minutes).
     - Includes a **`Turn off LEDs when done`** checkbox: when checked, the timer acts as a WLED hardware sleep timer that automatically powers off the Batman logo (`{ on: false }`) and announces completion via the Robot's voice when the countdown reaches `00:00`.
  2. **🎤 Two-Way Voice Command System (`🎤 Give Voice Command to Robot`)**:
     - Added `android.permission.RECORD_AUDIO` and native `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (`EXTRA_PREFER_OFFLINE = true`) in [`MainActivity.java`](app/src/main/java/com/batman/wleddashboard/MainActivity.java) (`AndroidBridge.startVoiceRecognition()`), plus an interactive **Robot Voice & Command Center modal** with quick-tap command chips and keyboard-mic support when running offline on the `wledtabletop` AP.
     - Built a natural-language command parser (`executeVoiceCommand()`) that understands and executes:
       - **Timer Voice Commands**: *"Set timer for 10 minutes"*, *"Set timer 25 minutes"*, *"Stop timer"*
       - **Lighting Voice Commands**: *"Turn on lights"*, *"Turn off Batman logo"*, *"Random color"*, *"Random effect"*, *"Set color blue/red/orange/purple/cyan/green/white"*, *"Brightness 80"*, *"Preset 3"*
       - **Task & Note Voice Commands**: *"Add task submit lab report"* (adds directly to Task Scheduler), *"Add note check GPIO 14"* (appends directly to Quick Desk Notes)
       - **Assistant Queries**: *"What is the weather"*, *"Next class"*, *"Read my tasks"*, *"Full briefing"*

---

### Android App Version 4 (`BatmanWledDashboard-v4.apk` / Tag `v8.0`) — 100% Offline CMU PocketSphinx ASR, `"Jipsy Listen"` / `"Wake Up"` Catch-Phrase, 1.8s Silence Auto-Stop & `[Auto-Wake: ON/OFF]` Toggle
- **Directory**: [`android-app-versions/v4-offline-pocketsphinx-jipsy-autowake/`](android-app-versions/v4-offline-pocketsphinx-jipsy-autowake/) (and root [`BatmanWledDashboard.apk`](BatmanWledDashboard.apk))
- **APK File**: [`BatmanWledDashboard-v4.apk`](android-app-versions/v4-offline-pocketsphinx-jipsy-autowake/BatmanWledDashboard-v4.apk) (`4,982,932 bytes`)
- **What was added in Android App v4**:
  1. **🎙️ 100% Offline Embedded Voice Recognition (CMU PocketSphinx JNI + `en-us-ptm` Model)**:
     - On Android 4.2.2 (`SM-T111`), Google's legacy `RecognizerIntent` (`com.google.android.voicesearch:4.0.0`) requires an internet connection and fails whenever the tablet is connected to the offline ESP32 WLED Wi-Fi Access Point (`wledtabletop` at `4.3.2.1`).
     - Embedded **`pocketsphinx-android-5prealpha`** (`pocketsphinx.jar` + `armeabi-v7a/libpocketsphinx_jni.so`), the US-English **`en-us-ptm`** acoustic model, a 188-word custom phonetic dictionary ([`batman.dict`](app/src/main/assets/sync/batman.dict)), and a JSGF command grammar ([`batman.gram`](app/src/main/assets/sync/batman.gram)) directly inside the APK so speech recognition runs **100% offline on the tablet's CPU**.
  2. **🗣️ Hands-Free Wake Phrase (`"Jipsy Listen"` / `"Wake Up"`) with Natural Voice Assistant Delay**:
     - Continuous keyword-spotting (`SEARCH_WAKEUP` via [`wakeup.keys`](app/src/main/assets/sync/wakeup.keys)) listens for **`"Jipsy listen"`**, **`"Jipsy wake up"`**, or **`"Wake up"`**.
     - When heard, Jipsy speaks *"Yes, I am listening."* via native Android TTS, waits **2.5 seconds** (so the microphone never picks up the tablet's own speaker), and opens a **10-second command listening window**.
  3. **🔘 Dedicated `[Auto-Wake: ON]` / `[Auto-Wake: OFF]` Toggle Button**:
     - Added an on-screen **`[Auto-Wake: ON]`** (green) / **`[Auto-Wake: OFF]`** (red) button (`#autoWakeBtn`) directly below the robot companion so you can choose when the tablet should continuously listen for the catch phrase and when it should stop listening completely (`AndroidBridge.setAutoWakeEnabled(boolean)`).
     - Even when Auto-Wake is `OFF`, tapping **`[Mic] Speak Now`** lets you trigger a single voice command on demand.
  4. **📝 Voice Quick Notes Dictation & Effect Intensity Percentage Command**:
     - **Voice Quick Notes**: Speak *"Write note exam tomorrow"*, *"Quick note check sensor pin"*, or *"Add note submit assignment"* to append notes directly into the Quick Scratchpad.
     - **Voice Effect Intensity (`ix`)**: Speak *"Set effect intensity to 80 percent"* or *"Intensity 50 percent"* to set WLED's animation intensity (`0–255`) by voice.
  5. **🦇 Custom Yellow-and-Black Batman Emblem App Icon**:
     - Added custom multi-DPI (`mdpi`, `hdpi`, `xhdpi`, `xxhdpi`) Bat-Signal launcher icons (`@drawable/ic_batman_logo`).
- **What was improved & fixed over Android App v3**:
  1. **⚡ Instant Execution After Last Spoken Word (1.8s Silence Buffer Max Instead of Waiting Full 10 Seconds)**:
     - Fixed the PocketSphinx `onPartialResult()` behavior where identical partial hypotheses emitted every 100ms during silence kept resetting the silence countdown. Now, [`MainActivity.java`](app/src/main/java/com/batman/wleddashboard/MainActivity.java) only resets the **1.8-second silence timer (`END_OF_COMMAND_SILENCE_MS = 1800L`)** when a **new word** is actually detected (`!clean.equals(lastHeardCommandPartial)`), executing your command ~1.8 seconds after your last word.
  2. **🎯 Multi-Accent Phonetic Pronunciations for `"Jipsy"` & `"Listen"`**:
     - Added 5 phonetic variations for `jipsy` (`JH IH P S IY`, `JH IH P Z IY`, `G IH P S IY`, `JH IH B S IY`, `Y IH P S IY`) and 3 for `listen` (`L IH S AH N`, `L IH S IH N`, `L IH Z AH N`) in `batman.dict`, plus tuned keyword thresholds in `wakeup.keys`.
  3. **🛠️ Windows `CRLF` (`\r\n`) to Unix `LF` (`\n`) Asset Sanitizer**:
     - Added `copyTextAssetUnixLf()` in `MainActivity.java` to strip `\r` bytes when extracting PocketSphinx dictionary, grammar, and keyword files on Android.
  4. **⏱️ Always-Visible Desk Timer Bar + Custom Touch Numpad**:
     - Moved the Desk Timer (`10 Min`, `15 Min`, `30 Min`, `Custom`, `Stop`) directly onto the main right-hand panel so it is always visible without switching tabs, and added an on-screen **Touch Numpad Modal** for custom minutes.
  5. **🔒 Fixed Desk Timer Stopping When Screen Turns Off (`PARTIAL_WAKE_LOCK` + Hardware Clock)**:
     - Fixed the issue where turning off the tablet screen (`onPause()`) froze WebView JavaScript `setInterval` timers and paused the countdown.
     - Added a native hardware countdown timer (`startNativeTimerOnUi()`, `tickNativeTimerOnUi()`, `WledOffWorker`) in [`MainActivity.java`](app/src/main/java/com/batman/wleddashboard/MainActivity.java) backed by `SystemClock.elapsedRealtime()` and `PowerManager.PARTIAL_WAKE_LOCK` (`BatmanTimerWakeLock`). Even when the screen is completely OFF, the timer counts down accurately, keeps the `wledtabletop` Wi-Fi watchdog active, turns off the Batman WLED logo directly from Java when `00:00` is reached, speaks the completion alert via Android TTS, and wakes the screen (`ACQUIRE_CAUSES_WAKEUP`).

---

## 📶 Deep Dive: Solving the Android 4.2 WLED AP Wi-Fi Disconnect & Captive Portal Bug

### The Problem
When connecting the Samsung Galaxy Tab 3 Lite (`SM-T111`, Android 4.2.2) to the ESP32's WLED Access Point (**`wledtabletop`**, gateway `4.3.2.1`):
1. **Captive Portal Hijack**: WLED runs a DNS server in AP mode that redirects all domains to `4.3.2.1`. When Android connects to Wi-Fi, it probes `http://clients3.google.com/generate_204`. Receiving WLED's HTML instead of an HTTP `204` response causes Android to raise a **"Sign in to Wi-Fi network (`wledtabletop`)"** notification.
2. **Samsung TouchWiz Wi-Fi Watchdog**: When Samsung's `WifiWatchdogStateMachine` detects that the AP has no internet connectivity (or that the captive portal sign-in was not completed), it **automatically disconnects from `wledtabletop` after 30–60 seconds**.

### Our 3-Layer Engineering Solution

#### Layer 1: OS-Level Captive Portal & Watchdog Disarm (via ADB)
During installation over USB Debugging, we configure three global Android system flags on the tablet:
```powershell
# 1. Disable Google generate_204 captive portal probing (stops "Sign in to network" popup)
adb shell settings put global captive_portal_detection_enabled 0

# 2. Disable Samsung's no-internet Wi-Fi watchdog (stops automatic AP disconnection)
adb shell settings put global wifi_watchdog_on 0

# 3. Keep Wi-Fi radio active at all times (2 = WIFI_SLEEP_POLICY_NEVER)
adb shell settings put global wifi_sleep_policy 2
```

#### Layer 2: Hardware High-Performance Wi-Fi Lock + 6-Second Watchdog in `MainActivity.java`
Inside [`MainActivity.java`](app/src/main/java/com/batman/wleddashboard/MainActivity.java):
- **Hardware Wi-Fi Lock**: Acquires `WifiManager.WIFI_MODE_FULL_HIGH_PERF` (`BatmanWledWifiLock`) so the Broadcom Wi-Fi radio on the SM-T111 never enters power-saving latency mode.
- **Active SSID Watchdog (`WifiCheckWorker`)**: Every 6 seconds, a background thread inspects `WifiManager.getConnectionInfo()`. If the radio was turned off, or if the tablet dropped association with `"wledtabletop"` (or lost its IP lease `ip == 0`), the watchdog scans `wifiManager.getConfiguredNetworks()`, matches `"wledtabletop"`, and executes:
  ```java
  wifiManager.disconnect();
  wifiManager.enableNetwork(config.networkId, true);
  wifiManager.reconnect();
  ```

#### Layer 3: Dual-Stack HTTP Engine (WebView XHR + Native Java `HttpURLConnection`)
When loading a dashboard from `file:///android_asset/index.html`, legacy WebViews can intermittently block cross-origin `XMLHttpRequest` calls to `http://4.3.2.1/json/state`.
- We enabled `setAllowUniversalAccessFromFileURLs(true)` on the `WebSettings`.
- We also added `@JavascriptInterface public String httpRequest(String method, String urlStr, String jsonBody)` in `MainActivity.java` using native `java.net.HttpURLConnection`. If a WebView XHR ever times out or is blocked, `xhrRequest()` in JavaScript transparently falls back to `window.AndroidBridge.httpRequest()`, guaranteeing 100% command delivery to the ESP32.

---

## 📂 Repository Structure

```text
├── BatmanWledDashboard.apk                              # Latest (App v4 / Tag v8.0) signed APK ready to install
├── build_apk.ps1                                        # Standalone PowerShell APK build script
├── README.md                                            # Complete documentation & version history
├── web-dashboard-versions/                              # All 4 historical Web HTML iterations (Tags v1.0 - v4.0)
│   ├── v1-dashboard.htm                                 # Web v1: Initial Flexbox/Grid + Fetch prototype
│   ├── v2-dashboard1.htm                                # Web v2: Android 4.2 table layout + XHR + Timetable modal
│   ├── v3-dashboard2.htm                                # Web v3: Landscape split + SVG Robot + Canvas Colour Wheel
│   └── v4-dashboard3.htm                                # Web v4: LocalStorage Quick Notes + AndroidBridge TTS hook
├── android-app-versions/                                # All 4 Native Android Kiosk App iterations (Tags v5.0 - v8.0)
│   ├── v1-kiosk-wifi-watchdog/                          # Android App v1 (Tag v5.0): Kiosk + Wi-Fi Watchdog + Tasks + Intensity
│   │   ├── BatmanWledDashboard-v1.apk                   # Compiled App v1 APK (25 KB)
│   │   └── index.html                                   # App v1 dashboard asset
│   ├── v2-talking-robo-randomizers/                     # Android App v2 (Tag v6.0): Talking Robot + Weather + Randomizers
│   │   ├── BatmanWledDashboard-v2.apk                   # Compiled App v2 APK (29 KB)
│   │   └── index.html                                   # App v2 dashboard asset
│   ├── v3-timer-and-voice-commands/                     # Android App v3 (Tag v7.0): 10/15/30/Custom Timer + Voice Commands
│   │   ├── BatmanWledDashboard-v3.apk                   # Compiled App v3 APK (33 KB)
│   │   ├── MainActivity.java                            # App v3 Java host with SpeechRecognizer + TTS + Wi-Fi Watchdog
│   │   └── index.html                                   # App v3 dashboard asset
│   └── v4-offline-pocketsphinx-jipsy-autowake/          # Android App v4 (Tag v8.0): 100% Offline PocketSphinx ASR + Jipsy Auto-Wake
│       ├── BatmanWledDashboard-v4.apk                   # Compiled App v4 APK (4.98 MB with embedded acoustic model)
│       ├── README.md                                    # Detailed release notes & improvements for App v4
│       ├── MainActivity.java                            # App v4 Java host with PocketSphinx JNI + 1.8s Silence Buffer + Auto-Wake
│       ├── index.html                                   # App v4 dashboard asset with [Auto-Wake: ON/OFF] toggle
│       └── sync/                                        # PocketSphinx dictionary (batman.dict), grammar (batman.gram), wakeup.keys
└── app/                                                 # Active Android Gradle/CLI Source Tree (v4 / Tag v8.0)
    ├── build.gradle                                     # Android app module config (minSdkVersion 17)
    ├── libs/pocketsphinx.jar                            # CMU PocketSphinx Android library
    └── src/main/
        ├── AndroidManifest.xml                          # Permissions (INTERNET, WIFI, WAKE_LOCK, RECORD_AUDIO) & Kiosk config
        ├── assets/
        │   ├── index.html                               # Active (App v4) dashboard UI
        │   └── sync/                                    # en-us-ptm acoustic model + batman.dict + batman.gram + wakeup.keys
        ├── java/com/batman/wleddashboard/
        │   └── MainActivity.java                        # Native WebView host, Offline PocketSphinx ASR, Wi-Fi Watchdog, TTS & HTTP bridge
        ├── lib/armeabi-v7a/libpocketsphinx_jni.so       # Native ARMv7 PocketSphinx speech recognition library
        └── res/
            ├── drawable-mdpi/ic_batman_logo.png         # Custom yellow-and-black Batman emblem app icon (48x48)
            ├── drawable-hdpi/ic_batman_logo.png         # Custom yellow-and-black Batman emblem app icon (72x72)
            ├── drawable-xhdpi/ic_batman_logo.png        # Custom yellow-and-black Batman emblem app icon (96x96)
            └── drawable-xxhdpi/ic_batman_logo.png       # Custom yellow-and-black Batman emblem app icon (144x144)
```

---

## 🔨 Building & Installing on Android 4.2

### 1. One-Command Build from Source (Windows PowerShell)
```powershell
.\build_apk.ps1
```

### 2. Install & Configure via ADB
```powershell
# Disable captive portal popup & auto-disconnect on Android 4.2
adb shell settings put global captive_portal_detection_enabled 0
adb shell settings put global wifi_watchdog_on 0
adb shell settings put global wifi_sleep_policy 2

# Install either App v1 or App v2 (latest):
adb install -r BatmanWledDashboard.apk
adb shell am start -n com.batman.wleddashboard/.MainActivity
```
