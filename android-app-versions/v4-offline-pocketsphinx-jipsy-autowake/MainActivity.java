package com.batman.wleddashboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.RecognitionListener;
import edu.cmu.pocketsphinx.SpeechRecognizer;
import edu.cmu.pocketsphinx.SpeechRecognizerSetup;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener, Runnable, RecognitionListener {

    private static final String TAG = "BatmanASR";
    private static final String PREFS_NAME = "BatmanWledPrefs";
    private static final String DEFAULT_SSID = "wledtabletop";
    private static final String SEARCH_WAKEUP = "wakeup";
    private static final String SEARCH_BATMAN_CMD = "batman_cmd";
    private static final int REQ_CODE_SPEECH_INPUT = 1001;

    /* 10 seconds max listening window for commands */
    private static final int COMMAND_LISTEN_TIMEOUT_MS = 10000;
    /* 2.5 seconds delay after wake word ("Yes, I am listening") before opening command mic */
    private static final long WAKE_GREETING_DELAY_MS = 2500L;
    /* 1.8 seconds max silence buffer after the last spoken word before auto-executing command */
    private static final long END_OF_COMMAND_SILENCE_MS = 1800L;

    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SharedPreferences prefs;

    private SpeechRecognizer offlineRecognizer;
    private volatile boolean offlineAsrReady = false;
    private volatile boolean autoWakeEnabled = true;
    private volatile String currentSearchMode = "";
    private volatile String lastHeardCommandPartial = "";

    private ResumeWakeupRunnable resumeWakeupRunnable;
    private StartCommandAfterGreetingRunnable startCommandAfterGreetingRunnable;
    private FinalizeCommandRunnable finalizeCommandRunnable;
    private NativeTimerTickRunnable nativeTimerTickRunnable;

    private WifiManager wifiManager;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock timerWakeLock;
    private PowerManager.WakeLock alarmScreenWakeLock;
    private Handler watchdogHandler;
    private boolean isRunning = false;

    /* Native Screen-Off Hardware Countdown Timer State */
    private volatile long nativeTimerEndElapsedMs = 0L;
    private volatile int nativeTimerTotalMins = 0;
    private volatile boolean nativeTimerAutoOffLeds = false;
    private volatile String nativeTimerWledIp = "4.3.2.1";

    @Override
    public void run() {
        if (!isRunning && nativeTimerEndElapsedMs <= 0L) return;
        checkAndKeepWifiAlive();
        watchdogHandler.postDelayed(this, 6000);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            int res = tts.setLanguage(Locale.US);
            ttsReady = (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED);
            tts.setSpeechRate(0.95f);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        autoWakeEnabled = prefs.getBoolean("auto_wake_enabled", true);
        watchdogHandler = new Handler(Looper.getMainLooper());
        resumeWakeupRunnable = new ResumeWakeupRunnable(this);
        startCommandAfterGreetingRunnable = new StartCommandAfterGreetingRunnable(this);
        finalizeCommandRunnable = new FinalizeCommandRunnable(this);
        nativeTimerTickRunnable = new NativeTimerTickRunnable(this);

        setupWifiLock();
        setupTextToSpeech();

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0D0F12);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        String dbPath = getApplicationContext().getDir("database", Context.MODE_PRIVATE).getPath();
        settings.setDatabasePath(dbPath);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowUniversalAccessFromFileURLs(true);
            settings.setAllowFileAccessFromFileURLs(true);
        }

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");

        webView.loadUrl("file:///android_asset/index.html");

        setupOfflinePocketSphinxAsync();
    }

    private void setupOfflinePocketSphinxAsync() {
        Thread t = new Thread(new PocketSphinxInitWorker(this));
        t.start();
    }

    public void initPocketSphinxBlocking() {
        try {
            File syncDir = new File(getFilesDir(), "sync");
            File ptmDir = new File(syncDir, "en-us-ptm");
            if (!ptmDir.exists()) {
                ptmDir.mkdirs();
            }

            String[] ptmFiles = new String[]{
                    "feat.params", "mdef", "means", "noisedict",
                    "README", "sendump", "transition_matrices", "variances"
            };
            AssetManager am = getAssets();
            for (int i = 0; i < ptmFiles.length; i++) {
                String name = ptmFiles[i];
                File dest = new File(ptmDir, name);
                if (name.equals("feat.params") || name.equals("noisedict") || name.equals("README")) {
                    copyTextAssetUnixLf(am, "sync/en-us-ptm/" + name, dest);
                } else if (!dest.exists() || dest.length() == 0) {
                    copyBinaryAssetFile(am, "sync/en-us-ptm/" + name, dest);
                }
            }

            /* Strip any Windows \r characters so PocketSphinx C parser gets pure Unix \n */
            File dictFile = new File(syncDir, "batman.dict");
            copyTextAssetUnixLf(am, "sync/batman.dict", dictFile);

            File gramFile = new File(syncDir, "batman.gram");
            copyTextAssetUnixLf(am, "sync/batman.gram", gramFile);

            File wakeupFile = new File(syncDir, "wakeup.keys");
            copyTextAssetUnixLf(am, "sync/wakeup.keys", wakeupFile);

            SpeechRecognizer rec = SpeechRecognizerSetup.defaultSetup()
                    .setAcousticModel(ptmDir)
                    .setDictionary(dictFile)
                    .setKeywordThreshold(1e-6f)
                    .getRecognizer();
            rec.addListener(this);
            rec.addKeywordSearch(SEARCH_WAKEUP, wakeupFile);
            rec.addGrammarSearch(SEARCH_BATMAN_CMD, gramFile);

            this.offlineRecognizer = rec;
            this.offlineAsrReady = true;
            Log.i(TAG, "PocketSphinx Offline ASR initialized successfully!");
            scheduleResumeWakeup(1000L);
        } catch (Exception e) {
            Log.e(TAG, "PocketSphinx init error: " + e.getMessage(), e);
            this.offlineAsrReady = false;
        }
    }

    private void copyBinaryAssetFile(AssetManager am, String assetPath, File outFile) throws Exception {
        InputStream in = am.open(assetPath);
        FileOutputStream out = new FileOutputStream(outFile);
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        out.flush();
        out.close();
        in.close();
    }

    private void copyTextAssetUnixLf(AssetManager am, String assetPath, File outFile) throws Exception {
        InputStream in = am.open(assetPath);
        FileOutputStream out = new FileOutputStream(outFile);
        byte[] inBuf = new byte[8192];
        byte[] outBuf = new byte[8192];
        int read;
        while ((read = in.read(inBuf)) != -1) {
            int outIdx = 0;
            for (int i = 0; i < read; i++) {
                if (inBuf[i] != '\r') {
                    outBuf[outIdx++] = inBuf[i];
                }
            }
            if (outIdx > 0) {
                out.write(outBuf, 0, outIdx);
            }
        }
        out.flush();
        out.close();
        in.close();
    }

    private void sendJsToWebView(String jsCode) {
        if (watchdogHandler != null) {
            watchdogHandler.post(new JsCallbackRunnable(webView, jsCode));
        }
    }

    private void cancelPendingVoiceTimers() {
        if (watchdogHandler != null) {
            if (resumeWakeupRunnable != null) watchdogHandler.removeCallbacks(resumeWakeupRunnable);
            if (startCommandAfterGreetingRunnable != null) watchdogHandler.removeCallbacks(startCommandAfterGreetingRunnable);
            if (finalizeCommandRunnable != null) watchdogHandler.removeCallbacks(finalizeCommandRunnable);
        }
    }

    public void scheduleResumeWakeup(long delayMs) {
        if (watchdogHandler != null && resumeWakeupRunnable != null) {
            watchdogHandler.removeCallbacks(resumeWakeupRunnable);
            watchdogHandler.postDelayed(resumeWakeupRunnable, delayMs);
        }
    }

    public void startWakeupListeningOnUi() {
        if (!isRunning || !offlineAsrReady || offlineRecognizer == null) return;
        try {
            cancelPendingVoiceTimers();
            offlineRecognizer.cancel();
            lastHeardCommandPartial = "";
            if (!autoWakeEnabled) {
                currentSearchMode = "";
                Log.i(TAG, "Auto-wake disabled by user; wakeup listening paused.");
                sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('WAKEUP_PAUSED', 'Auto-Wake is OFF. Tap [Auto-Wake: OFF] to enable or tap [Mic] to speak.');}");
                return;
            }
            currentSearchMode = SEARCH_WAKEUP;
            offlineRecognizer.startListening(SEARCH_WAKEUP);
            Log.i(TAG, "Started KWS wakeup listening (jipsy listen / wake up)");
            sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('WAKEUP_READY', 'Auto-Wake ON: Say \"Jipsy listen\" or \"Wake up\"');}");
        } catch (Exception ignored) {
        }
    }

    public void setAutoWakeOnUi(boolean enabled) {
        this.autoWakeEnabled = enabled;
        if (prefs != null) {
            prefs.edit().putBoolean("auto_wake_enabled", enabled).apply();
        }
        try {
            cancelPendingVoiceTimers();
            if (offlineRecognizer != null) {
                offlineRecognizer.cancel();
            }
            currentSearchMode = "";
            lastHeardCommandPartial = "";
            if (enabled) {
                startWakeupListeningOnUi();
            } else {
                Log.i(TAG, "User paused continuous catch-phrase listening.");
                sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('WAKEUP_PAUSED', 'Auto-Wake is OFF. Tap [Auto-Wake: OFF] to enable or tap [Mic] to speak.');}");
            }
        } catch (Exception ignored) {
        }
    }

    public void requestToggleAutoWakeFromJs(boolean enabled) {
        if (watchdogHandler != null) {
            watchdogHandler.post(new ToggleAutoWakeRunnable(this, enabled));
        }
    }

    public boolean isAutoWakeActive() {
        return autoWakeEnabled;
    }

    /* Called when "Jipsy listen" / "Wake up" is spoken or when the button is tapped */
    public void onWakeWordTriggeredOnUi(boolean speakGreetingFirst) {
        if (!offlineAsrReady || offlineRecognizer == null) return;
        try {
            cancelPendingVoiceTimers();
            currentSearchMode = "GREETING_DELAY";
            lastHeardCommandPartial = "";
            offlineRecognizer.cancel();

            if (speakGreetingFirst && tts != null && ttsReady) {
                sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('PREPARING', 'Jipsy: \"Yes, I am listening...\" (Wait a moment, then speak)');}");
                tts.speak("Yes, I am listening.", TextToSpeech.QUEUE_FLUSH, null);
                watchdogHandler.postDelayed(startCommandAfterGreetingRunnable, WAKE_GREETING_DELAY_MS);
            } else {
                if (tts != null) {
                    tts.stop();
                }
                sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('PREPARING', 'Get ready to speak command...');}");
                watchdogHandler.postDelayed(startCommandAfterGreetingRunnable, 1200L);
            }
        } catch (Exception ignored) {
        }
    }

    /* Opens the 10-second command listening window after the wake greeting finishes */
    public void openCommandMicNowOnUi() {
        if (!isRunning || !offlineAsrReady || offlineRecognizer == null) return;
        try {
            if (tts != null) {
                tts.stop();
            }
            offlineRecognizer.cancel();
            lastHeardCommandPartial = "";
            currentSearchMode = SEARCH_BATMAN_CMD;
            offlineRecognizer.startListening(SEARCH_BATMAN_CMD, COMMAND_LISTEN_TIMEOUT_MS);
            Log.i(TAG, "Started 10s command grammar listening");
            sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('LISTENING', 'Listening Now (10s)... Speak your command clearly!');}");
        } catch (Exception ignored) {
        }
    }

    /* Called 2.4 seconds after the user stops speaking a valid command */
    public void finalizeCommandNowOnUi() {
        if (offlineRecognizer != null && SEARCH_BATMAN_CMD.equals(currentSearchMode)) {
            currentSearchMode = "";
            offlineRecognizer.stop();
        }
    }

    private String normalizeNumbersInSpeech(String raw) {
        if (raw == null) return "";
        String s = " " + raw.toLowerCase() + " ";
        s = s.replace(" jipsy wake up ", " ")
                .replace(" jipsy listen ", " ")
                .replace(" wake up ", " ")
                .replace(" jipsy ", " ")
                .replace(" fifteen ", " 15 ")
                .replace(" twenty ", " 20 ")
                .replace(" thirty ", " 30 ")
                .replace(" forty ", " 40 ")
                .replace(" fifty ", " 50 ")
                .replace(" sixty ", " 60 ")
                .replace(" seventy ", " 70 ")
                .replace(" eighty ", " 80 ")
                .replace(" ninety ", " 90 ")
                .replace(" hundred ", " 100 ")
                .replace(" zero ", " 0 ")
                .replace(" ten ", " 10 ")
                .replace(" five ", " 5 ")
                .replace(" one ", " 1 ")
                .replace(" two ", " 2 ")
                .replace(" three ", " 3 ")
                .replace(" four ", " 4 ")
                .replace(" six ", " 6 ")
                .replace(" seven ", " 7 ")
                .replace(" eight ", " 8 ");
        return s.trim();
    }

    @Override
    public void onBeginningOfSpeech() {
        if (SEARCH_BATMAN_CMD.equals(currentSearchMode)) {
            if (watchdogHandler != null && finalizeCommandRunnable != null) {
                watchdogHandler.removeCallbacks(finalizeCommandRunnable);
            }
            sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('SPEAKING', 'Hearing your voice... Keep speaking!');}");
        }
    }

    @Override
    public void onEndOfSpeech() {
        if (offlineRecognizer != null && SEARCH_BATMAN_CMD.equals(currentSearchMode)) {
            if (lastHeardCommandPartial != null && lastHeardCommandPartial.length() > 0) {
                if (watchdogHandler != null && finalizeCommandRunnable != null) {
                    watchdogHandler.removeCallbacks(finalizeCommandRunnable);
                    watchdogHandler.postDelayed(finalizeCommandRunnable, END_OF_COMMAND_SILENCE_MS);
                }
            }
        }
    }

    @Override
    public void onPartialResult(Hypothesis hypothesis) {
        if (hypothesis == null) return;
        String partial = hypothesis.getHypstr();
        if (partial == null) return;
        String lower = partial.trim().toLowerCase();
        if (lower.length() == 0) return;

        Log.i(TAG, "Partial [" + currentSearchMode + "]: " + lower);

        if (SEARCH_WAKEUP.equals(currentSearchMode)) {
            if (lower.contains("jipsy") || lower.contains("wake up") || lower.contains("listen")) {
                onWakeWordTriggeredOnUi(true);
            }
            return;
        }

        if (SEARCH_BATMAN_CMD.equals(currentSearchMode)) {
            String clean = normalizeNumbersInSpeech(lower).replace("'", "");
            if (clean.length() > 0 && !clean.equals(lastHeardCommandPartial)) {
                lastHeardCommandPartial = clean;
                Log.i(TAG, "New word heard [" + clean + "] -> resetting 1.8s silence buffer");
                sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('PARTIAL', '" + clean + "');}");
                if (watchdogHandler != null && finalizeCommandRunnable != null) {
                    watchdogHandler.removeCallbacks(finalizeCommandRunnable);
                    watchdogHandler.postDelayed(finalizeCommandRunnable, END_OF_COMMAND_SILENCE_MS);
                }
            }
        }
    }

    @Override
    public void onResult(Hypothesis hypothesis) {
        if (watchdogHandler != null && finalizeCommandRunnable != null) {
            watchdogHandler.removeCallbacks(finalizeCommandRunnable);
        }
        String raw = "";
        if (hypothesis != null && hypothesis.getHypstr() != null) {
            raw = hypothesis.getHypstr().trim().toLowerCase();
        }
        Log.i(TAG, "Final Result: " + raw);
        if (raw.equals("jipsy listen") || raw.equals("jipsy wake up") || raw.equals("wake up") || raw.equals("jipsy")) {
            return;
        }
        String spoken = normalizeNumbersInSpeech(raw).replace("'", "");
        if (spoken.length() == 0 && lastHeardCommandPartial != null && lastHeardCommandPartial.length() > 0) {
            spoken = lastHeardCommandPartial;
        }
        lastHeardCommandPartial = "";

        if (spoken.length() > 0) {
            sendJsToWebView("if(window.onVoiceCommandResult){window.onVoiceCommandResult('" + spoken + "');}");
            scheduleResumeWakeup(3800L);
            return;
        }
        scheduleResumeWakeup(1000L);
    }

    @Override
    public void onError(Exception error) {
        Log.e(TAG, "ASR Error: " + error.getMessage());
        currentSearchMode = "";
        scheduleResumeWakeup(1800L);
    }

    @Override
    public void onTimeout() {
        if (SEARCH_BATMAN_CMD.equals(currentSearchMode) && lastHeardCommandPartial != null && lastHeardCommandPartial.length() > 0) {
            currentSearchMode = "";
            if (offlineRecognizer != null) {
                offlineRecognizer.stop();
            }
            return;
        }
        currentSearchMode = "";
        startWakeupListeningOnUi();
    }

    private void setupWifiLock() {
        try {
            wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BatmanWledWifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
            PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                timerWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatmanTimerWakeLock");
                timerWakeLock.setReferenceCounted(false);
                alarmScreenWakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "BatmanTimerAlarmWake"
                );
                alarmScreenWakeLock.setReferenceCounted(false);
            }
        } catch (Exception ignored) {
        }
    }

    /* ================= NATIVE SCREEN-OFF HARDWARE COUNTDOWN TIMER ================= */
    public void requestStartNativeTimer(int minutes, boolean autoOffLeds, String wledIp) {
        if (watchdogHandler != null) {
            watchdogHandler.post(new StartNativeTimerRunnable(this, minutes, autoOffLeds, wledIp));
        }
    }

    public void requestStopNativeTimer() {
        if (watchdogHandler != null) {
            watchdogHandler.post(new StopNativeTimerRunnable(this));
        }
    }

    public void setNativeTimerAutoOffConfig(boolean autoOffLeds, String wledIp) {
        this.nativeTimerAutoOffLeds = autoOffLeds;
        if (wledIp != null && wledIp.length() > 0) {
            this.nativeTimerWledIp = wledIp;
        }
    }

    public int getNativeTimerRemainingSeconds() {
        long endMs = nativeTimerEndElapsedMs;
        if (endMs <= 0L) return 0;
        long remMs = endMs - SystemClock.elapsedRealtime();
        if (remMs <= 0L) return 0;
        return (int) ((remMs + 999L) / 1000L);
    }

    public void startNativeTimerOnUi(int minutes, boolean autoOffLeds, String wledIp) {
        if (minutes <= 0) return;
        this.nativeTimerTotalMins = minutes;
        this.nativeTimerAutoOffLeds = autoOffLeds;
        if (wledIp != null && wledIp.length() > 0) {
            this.nativeTimerWledIp = wledIp;
        } else if (prefs != null) {
            this.nativeTimerWledIp = prefs.getString("wled_host_ip", "4.3.2.1");
        }
        this.nativeTimerEndElapsedMs = SystemClock.elapsedRealtime() + ((long) minutes * 60000L);

        try {
            if (timerWakeLock != null && !timerWakeLock.isHeld()) {
                timerWakeLock.acquire();
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }

        Log.i(TAG, "Started Native Screen-Off Timer for " + minutes + " mins (autoOffLeds=" + autoOffLeds + ")");
        if (watchdogHandler != null && nativeTimerTickRunnable != null) {
            watchdogHandler.removeCallbacks(nativeTimerTickRunnable);
            watchdogHandler.postDelayed(nativeTimerTickRunnable, 1000L);
        }
    }

    public void stopNativeTimerOnUi() {
        this.nativeTimerEndElapsedMs = 0L;
        if (watchdogHandler != null && nativeTimerTickRunnable != null) {
            watchdogHandler.removeCallbacks(nativeTimerTickRunnable);
        }
        try {
            if (timerWakeLock != null && timerWakeLock.isHeld()) {
                timerWakeLock.release();
            }
        } catch (Exception ignored) {
        }
        Log.i(TAG, "Stopped Native Screen-Off Timer");
    }

    public void tickNativeTimerOnUi() {
        long endMs = nativeTimerEndElapsedMs;
        if (endMs <= 0L) return;

        long remMs = endMs - SystemClock.elapsedRealtime();
        if (remMs <= 0L) {
            /* Timer reached 00:00 (works even if screen is completely OFF!) */
            nativeTimerEndElapsedMs = 0L;
            if (watchdogHandler != null && nativeTimerTickRunnable != null) {
                watchdogHandler.removeCallbacks(nativeTimerTickRunnable);
            }
            Log.i(TAG, "Native Timer FINISHED! Executing completion actions (screen-off capable).");

            /* Briefly wake the screen so the user sees the 00:00 alert */
            try {
                if (alarmScreenWakeLock != null) {
                    alarmScreenWakeLock.acquire(10000L);
                }
            } catch (Exception ignored) {
            }

            /* 1. If Auto-Off LEDs is enabled, turn off WLED directly from native Java thread */
            if (nativeTimerAutoOffLeds) {
                String ipToUse = (nativeTimerWledIp != null && nativeTimerWledIp.length() > 0)
                        ? nativeTimerWledIp
                        : (prefs != null ? prefs.getString("wled_host_ip", "4.3.2.1") : "4.3.2.1");
                Thread offThread = new Thread(new WledOffWorker(ipToUse));
                offThread.start();
            }

            /* 2. Speak the completion alert via Android TTS even if screen was off */
            String finishSpeech = "Attention! Your " + nativeTimerTotalMins + " minute timer has finished!";
            if (nativeTimerAutoOffLeds) {
                finishSpeech += " Turning off your Batman logo now.";
            }
            speakOut(finishSpeech);

            /* 3. Notify WebView UI */
            sendJsToWebView("if(window.onNativeTimerFinished){window.onNativeTimerFinished();}");

            try {
                if (timerWakeLock != null && timerWakeLock.isHeld()) {
                    timerWakeLock.release();
                }
            } catch (Exception ignored) {
            }
            return;
        }

        int remSecs = (int) ((remMs + 999L) / 1000L);
        if (isRunning) {
            sendJsToWebView("if(window.onNativeTimerTick){window.onNativeTimerTick(" + remSecs + ");}");
        }
        if (watchdogHandler != null && nativeTimerTickRunnable != null) {
            watchdogHandler.postDelayed(nativeTimerTickRunnable, 1000L);
        }
    }

    private void setupTextToSpeech() {
        try {
            tts = new TextToSpeech(this, this);
        } catch (Exception ignored) {
        }
    }

    public void checkAndKeepWifiAlive() {
        WifiCheckWorker worker = new WifiCheckWorker(wifiManager, prefs);
        Thread t = new Thread(worker);
        t.start();
    }

    public void speakOut(String text) {
        if (tts != null && ttsReady && text != null) {
            try {
                cancelPendingVoiceTimers();
                if (offlineRecognizer != null) {
                    currentSearchMode = "";
                    offlineRecognizer.cancel();
                }
                sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('SPEAKING_REPLY', 'Jipsy Speaking...');}");
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                long speakDurationMs = Math.max(2200L, Math.min(12000L, (long) text.length() * 68L)) + 1200L;
                scheduleResumeWakeup(speakDurationMs);
            } catch (Exception ignored) {
            }
        }
    }

    public void requestVoiceRecognizerOnUi() {
        if (watchdogHandler != null) {
            watchdogHandler.post(new VoiceLaunchRunnable(this));
        }
    }

    public void executeVoiceRecognizerNow() {
        try {
            if (offlineAsrReady && offlineRecognizer != null) {
                if (SEARCH_BATMAN_CMD.equals(currentSearchMode)) {
                    finalizeCommandNowOnUi();
                    return;
                }
                onWakeWordTriggeredOnUi(true);
                return;
            }

            if (tts != null) {
                tts.stop();
            }
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak command (e.g. 'Timer 15 minutes', 'Lights on', 'Random color')");
            intent.putExtra("android.speech.extra.PREFER_OFFLINE", true);
            startActivityForResult(intent, REQ_CODE_SPEECH_INPUT);
        } catch (Exception e) {
            sendJsToWebView("if(window.onVoiceCommandFallback){window.onVoiceCommandFallback();}");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_SPEECH_INPUT) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (result != null && result.size() > 0 && result.get(0) != null) {
                    String spoken = result.get(0)
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\"", "\\\"")
                            .replace("\n", " ")
                            .replace("\r", " ");
                    if (watchdogHandler != null) {
                        watchdogHandler.postDelayed(
                                new JsCallbackRunnable(webView, "if(window.onVoiceCommandResult){window.onVoiceCommandResult('" + spoken + "');}"),
                                300
                        );
                    }
                    return;
                }
            }
            if (watchdogHandler != null) {
                watchdogHandler.postDelayed(
                        new JsCallbackRunnable(webView, "if(window.onVoiceCommandFallback){window.onVoiceCommandFallback();}"),
                        300
                );
            }
        }
    }

    public SharedPreferences getAppPrefs() {
        return prefs;
    }

    public WifiManager getAppWifiManager() {
        return wifiManager;
    }

    private static class StartCommandAfterGreetingRunnable implements Runnable {
        private final MainActivity activity;

        StartCommandAfterGreetingRunnable(MainActivity act) {
            this.activity = act;
        }

        @Override
        public void run() {
            activity.openCommandMicNowOnUi();
        }
    }

    private static class FinalizeCommandRunnable implements Runnable {
        private final MainActivity activity;

        FinalizeCommandRunnable(MainActivity act) {
            this.activity = act;
        }

        @Override
        public void run() {
            activity.finalizeCommandNowOnUi();
        }
    }

    private static class ResumeWakeupRunnable implements Runnable {
        private final MainActivity activity;

        ResumeWakeupRunnable(MainActivity act) {
            this.activity = act;
        }

        @Override
        public void run() {
            activity.startWakeupListeningOnUi();
        }
    }

    private static class PocketSphinxInitWorker implements Runnable {
        private final MainActivity activity;

        PocketSphinxInitWorker(MainActivity act) {
            this.activity = act;
        }

        @Override
        public void run() {
            activity.initPocketSphinxBlocking();
        }
    }

    private static class VoiceLaunchRunnable implements Runnable {
        private final MainActivity activity;

        VoiceLaunchRunnable(MainActivity act) {
            this.activity = act;
        }

        @Override
        public void run() {
            activity.executeVoiceRecognizerNow();
        }
    }

    private static class ToggleAutoWakeRunnable implements Runnable {
        private final MainActivity activity;
        private final boolean enabled;

        ToggleAutoWakeRunnable(MainActivity act, boolean en) {
            this.activity = act;
            this.enabled = en;
        }

        @Override
        public void run() {
            activity.setAutoWakeOnUi(enabled);
        }
    }

    private static class NativeTimerTickRunnable implements Runnable {
        private final MainActivity activity;

        NativeTimerTickRunnable(MainActivity act) {
            this.activity = act;
        }

        @Override
        public void run() {
            activity.tickNativeTimerOnUi();
        }
    }

    private static class StartNativeTimerRunnable implements Runnable {
        private final MainActivity activity;
        private final int minutes;
        private final boolean autoOffLeds;
        private final String wledIp;

        StartNativeTimerRunnable(MainActivity act, int mins, boolean autoOff, String ip) {
            this.activity = act;
            this.minutes = mins;
            this.autoOffLeds = autoOff;
            this.wledIp = ip;
        }

        @Override
        public void run() {
            activity.startNativeTimerOnUi(minutes, autoOffLeds, wledIp);
        }
    }

    private static class StopNativeTimerRunnable implements Runnable {
        private final MainActivity activity;

        StopNativeTimerRunnable(MainActivity act) {
            this.activity = act;
        }

        @Override
        public void run() {
            activity.stopNativeTimerOnUi();
        }
    }

    private static class WledOffWorker implements Runnable {
        private final String wledIp;

        WledOffWorker(String ip) {
            this.wledIp = (ip != null && ip.length() > 0) ? ip : "4.3.2.1";
        }

        @Override
        public void run() {
            String urlStr = "http://" + wledIp + "/json/state";
            String payload = "{\"on\":false,\"v\":true}";
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(urlStr);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(2500);
                    conn.setReadTimeout(2500);
                    conn.setUseCaches(false);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Connection", "close");
                    conn.setDoOutput(true);
                    byte[] out = payload.getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(out.length);
                    OutputStream os = conn.getOutputStream();
                    os.write(out);
                    os.flush();
                    os.close();
                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        Log.i(TAG, "WledOffWorker: Successfully powered off WLED at " + wledIp);
                        return;
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
                try {
                    Thread.sleep(800L);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static class JsCallbackRunnable implements Runnable {
        private final WebView targetWebView;
        private final String jsCode;

        JsCallbackRunnable(WebView wv, String js) {
            this.targetWebView = wv;
            this.jsCode = js;
        }

        @Override
        public void run() {
            try {
                if (targetWebView != null && jsCode != null) {
                    targetWebView.loadUrl("javascript:" + jsCode);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static class WifiCheckWorker implements Runnable {
        private final WifiManager wifiManager;
        private final SharedPreferences prefs;

        WifiCheckWorker(WifiManager wm, SharedPreferences sp) {
            this.wifiManager = wm;
            this.prefs = sp;
        }

        @Override
        public void run() {
            try {
                if (wifiManager == null) return;
                if (!wifiManager.isWifiEnabled()) {
                    wifiManager.setWifiEnabled(true);
                }

                String targetSsid = prefs.getString("target_ssid", DEFAULT_SSID);
                String quotedTarget = "\"" + targetSsid + "\"";

                WifiInfo info = wifiManager.getConnectionInfo();
                String currentSsid = (info != null && info.getSSID() != null) ? info.getSSID() : "";
                int ip = (info != null) ? info.getIpAddress() : 0;

                boolean connectedToTarget = currentSsid.equalsIgnoreCase(quotedTarget)
                        || currentSsid.equalsIgnoreCase(targetSsid);

                if (!connectedToTarget || ip == 0) {
                    List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
                    if (configs != null) {
                        for (WifiConfiguration config : configs) {
                            if (config.SSID != null && (config.SSID.equalsIgnoreCase(quotedTarget)
                                    || config.SSID.equalsIgnoreCase(targetSsid))) {
                                wifiManager.disconnect();
                                wifiManager.enableNetwork(config.networkId, true);
                                wifiManager.reconnect();
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static class AndroidBridge {
        private final MainActivity activity;

        public AndroidBridge(MainActivity act) {
            this.activity = act;
        }

        @JavascriptInterface
        public void speakText(String text) {
            activity.speakOut(text);
        }

        @JavascriptInterface
        public boolean startVoiceRecognition() {
            activity.requestVoiceRecognizerOnUi();
            return true;
        }

        @JavascriptInterface
        public void setAutoWakeEnabled(boolean enabled) {
            activity.requestToggleAutoWakeFromJs(enabled);
        }

        @JavascriptInterface
        public boolean isAutoWakeEnabled() {
            return activity.isAutoWakeActive();
        }

        @JavascriptInterface
        public void startNativeTimer(int minutes, boolean autoOffLeds, String wledIp) {
            activity.requestStartNativeTimer(minutes, autoOffLeds, wledIp);
        }

        @JavascriptInterface
        public void stopNativeTimer() {
            activity.requestStopNativeTimer();
        }

        @JavascriptInterface
        public void setNativeTimerAutoOff(boolean autoOffLeds, String wledIp) {
            activity.setNativeTimerAutoOffConfig(autoOffLeds, wledIp);
        }

        @JavascriptInterface
        public int getNativeTimerRemainingSecs() {
            return activity.getNativeTimerRemainingSeconds();
        }

        @JavascriptInterface
        public void savePref(String key, String value) {
            if (key == null) return;
            activity.getAppPrefs().edit().putString(key, value != null ? value : "").apply();
        }

        @JavascriptInterface
        public String loadPref(String key, String defaultVal) {
            if (key == null) return defaultVal;
            return activity.getAppPrefs().getString(key, defaultVal);
        }

        @JavascriptInterface
        public String getWifiSsid() {
            try {
                WifiManager wm = activity.getAppWifiManager();
                if (wm != null) {
                    WifiInfo info = wm.getConnectionInfo();
                    if (info != null && info.getSSID() != null) {
                        return info.getSSID().replace("\"", "");
                    }
                }
            } catch (Exception ignored) {
            }
            return "";
        }

        @JavascriptInterface
        public void forceReconnectWifi() {
            activity.checkAndKeepWifiAlive();
        }

        @JavascriptInterface
        public String httpRequest(String method, String urlStr, String jsonBody) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method != null ? method : "GET");
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                conn.setUseCaches(false);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Connection", "close");

                if (jsonBody != null && jsonBody.length() > 0 && "POST".equalsIgnoreCase(method)) {
                    conn.setDoOutput(true);
                    byte[] out = jsonBody.getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(out.length);
                    OutputStream os = conn.getOutputStream();
                    os.write(out);
                    os.flush();
                    os.close();
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    InputStream is = conn.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    return sb.toString();
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            return "";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isRunning = true;
        if (wifiLock != null && !wifiLock.isHeld()) {
            try {
                wifiLock.acquire();
            } catch (Exception ignored) {
            }
        }
        watchdogHandler.removeCallbacks(this);
        watchdogHandler.post(this);
        int remSecs = getNativeTimerRemainingSeconds();
        if (remSecs > 0) {
            sendJsToWebView("if(window.onNativeTimerTick){window.onNativeTimerTick(" + remSecs + ");}");
        }
        if (offlineAsrReady) {
            scheduleResumeWakeup(1000L);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = false;
        /* Keep Wi-Fi watchdog running while screen is off if a Desk Timer is active! */
        if (nativeTimerEndElapsedMs <= 0L) {
            watchdogHandler.removeCallbacks(this);
        }
        cancelPendingVoiceTimers();
        if (offlineRecognizer != null) {
            currentSearchMode = "";
            offlineRecognizer.cancel();
        }
    }

    @Override
    protected void onDestroy() {
        isRunning = false;
        nativeTimerEndElapsedMs = 0L;
        watchdogHandler.removeCallbacks(this);
        if (nativeTimerTickRunnable != null) {
            watchdogHandler.removeCallbacks(nativeTimerTickRunnable);
        }
        cancelPendingVoiceTimers();
        if (timerWakeLock != null && timerWakeLock.isHeld()) {
            try {
                timerWakeLock.release();
            } catch (Exception ignored) {
            }
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            try {
                wifiLock.release();
            } catch (Exception ignored) {
            }
        }
        if (offlineRecognizer != null) {
            try {
                offlineRecognizer.cancel();
                offlineRecognizer.shutdown();
            } catch (Exception ignored) {
            }
        }
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.loadUrl("javascript:if(window.handleAndroidBack){window.handleAndroidBack();}");
        }
    }
}
