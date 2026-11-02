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

    /* 10 seconds total listening window for commands */
    private static final int COMMAND_LISTEN_TIMEOUT_MS = 10000;
    /* 3.0 seconds delay after wake word ("Yes, I am listening") before opening command mic */
    private static final long WAKE_GREETING_DELAY_MS = 3000L;
    /* 2.4 seconds pause after last spoken word before auto-executing command */
    private static final long END_OF_COMMAND_SILENCE_MS = 2400L;

    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SharedPreferences prefs;

    private SpeechRecognizer offlineRecognizer;
    private volatile boolean offlineAsrReady = false;
    private volatile String currentSearchMode = "";
    private volatile String lastHeardCommandPartial = "";

    private ResumeWakeupRunnable resumeWakeupRunnable;
    private StartCommandAfterGreetingRunnable startCommandAfterGreetingRunnable;
    private FinalizeCommandRunnable finalizeCommandRunnable;

    private WifiManager wifiManager;
    private WifiManager.WifiLock wifiLock;
    private Handler watchdogHandler;
    private boolean isRunning = false;

    @Override
    public void run() {
        if (!isRunning) return;
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
        watchdogHandler = new Handler(Looper.getMainLooper());
        resumeWakeupRunnable = new ResumeWakeupRunnable(this);
        startCommandAfterGreetingRunnable = new StartCommandAfterGreetingRunnable(this);
        finalizeCommandRunnable = new FinalizeCommandRunnable(this);

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
            currentSearchMode = SEARCH_WAKEUP;
            offlineRecognizer.startListening(SEARCH_WAKEUP);
            Log.i(TAG, "Started KWS wakeup listening (jipsy listen / wake up)");
            sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('WAKEUP_READY', 'Hands-Free Active: Say \"Jipsy listen\" or \"Wake up\"');}");
        } catch (Exception ignored) {
        }
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
            if (clean.length() > 0) {
                lastHeardCommandPartial = clean;
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
            scheduleResumeWakeup(6000L);
            return;
        }
        scheduleResumeWakeup(1500L);
    }

    @Override
    public void onError(Exception error) {
        Log.e(TAG, "ASR Error: " + error.getMessage());
        currentSearchMode = "";
        scheduleResumeWakeup(2500L);
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
        } catch (Exception ignored) {
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
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                long speakDurationMs = Math.max(3500L, Math.min(20000L, (long) text.length() * 80L)) + 3000L;
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
        if (offlineAsrReady) {
            scheduleResumeWakeup(1000L);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = false;
        watchdogHandler.removeCallbacks(this);
        cancelPendingVoiceTimers();
        if (offlineRecognizer != null) {
            currentSearchMode = "";
            offlineRecognizer.cancel();
        }
    }

    @Override
    protected void onDestroy() {
        isRunning = false;
        watchdogHandler.removeCallbacks(this);
        cancelPendingVoiceTimers();
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
