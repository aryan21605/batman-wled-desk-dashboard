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

    private static final String PREFS_NAME = "BatmanWledPrefs";
    private static final String DEFAULT_SSID = "wledtabletop";
    private static final String SEARCH_WAKEUP = "wakeup";
    private static final String SEARCH_BATMAN_CMD = "batman_cmd";
    private static final int REQ_CODE_SPEECH_INPUT = 1001;

    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SharedPreferences prefs;

    private SpeechRecognizer offlineRecognizer;
    private volatile boolean offlineAsrReady = false;
    private volatile String currentSearchMode = "";
    private ResumeWakeupRunnable resumeWakeupRunnable;

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
                if (!dest.exists() || dest.length() == 0) {
                    copyAssetFile(am, "sync/en-us-ptm/" + name, dest);
                }
            }

            File dictFile = new File(syncDir, "batman.dict");
            copyAssetFile(am, "sync/batman.dict", dictFile);

            File gramFile = new File(syncDir, "batman.gram");
            copyAssetFile(am, "sync/batman.gram", gramFile);

            File wakeupFile = new File(syncDir, "wakeup.keys");
            copyAssetFile(am, "sync/wakeup.keys", wakeupFile);

            SpeechRecognizer rec = SpeechRecognizerSetup.defaultSetup()
                    .setAcousticModel(ptmDir)
                    .setDictionary(dictFile)
                    .setKeywordThreshold(1e-20f)
                    .getRecognizer();
            rec.addListener(this);
            rec.addKeywordSearch(SEARCH_WAKEUP, wakeupFile);
            rec.addGrammarSearch(SEARCH_BATMAN_CMD, gramFile);

            this.offlineRecognizer = rec;
            this.offlineAsrReady = true;
            scheduleResumeWakeup(400);
        } catch (Exception e) {
            this.offlineAsrReady = false;
        }
    }

    private void copyAssetFile(AssetManager am, String assetPath, File outFile) throws Exception {
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

    private void sendJsToWebView(String jsCode) {
        if (watchdogHandler != null) {
            watchdogHandler.post(new JsCallbackRunnable(webView, jsCode));
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
            offlineRecognizer.cancel();
            currentSearchMode = SEARCH_WAKEUP;
            offlineRecognizer.startListening(SEARCH_WAKEUP);
            sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('WAKEUP_READY', 'Hands-Free Active: Say \"Hey Batman\" or \"Hello Robot\"');}");
        } catch (Exception ignored) {
        }
    }

    public void switchToCommandListeningOnUi(boolean fromWakeWord) {
        if (!offlineAsrReady || offlineRecognizer == null) return;
        try {
            if (watchdogHandler != null && resumeWakeupRunnable != null) {
                watchdogHandler.removeCallbacks(resumeWakeupRunnable);
            }
            if (tts != null) {
                tts.stop();
            }
            offlineRecognizer.cancel();
            currentSearchMode = SEARCH_BATMAN_CMD;
            offlineRecognizer.startListening(SEARCH_BATMAN_CMD, 7000);
            String prompt = fromWakeWord
                    ? "Heard \"Hey Batman\"! Speak your command now (e.g. \"Turn on lights\", \"Set timer 15 minutes\")..."
                    : "Listening Offline... Speak your command now!";
            sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('LISTENING', '" + prompt.replace("'", "\\'") + "');}");
        } catch (Exception ignored) {
        }
    }

    private String normalizeNumbersInSpeech(String raw) {
        if (raw == null) return "";
        String s = " " + raw.toLowerCase() + " ";
        s = s.replace(" hey batman ", " ")
                .replace(" hello robot ", " ")
                .replace(" fifteen ", " 15 ")
                .replace(" twenty ", " 20 ")
                .replace(" thirty ", " 30 ")
                .replace(" forty ", " 40 ")
                .replace(" fifty ", " 50 ")
                .replace(" sixty ", " 60 ")
                .replace(" ninety ", " 90 ")
                .replace(" hundred ", " 100 ")
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
            sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('SPEAKING', 'Hearing your command...');}");
        }
    }

    @Override
    public void onEndOfSpeech() {
        if (offlineRecognizer != null && SEARCH_BATMAN_CMD.equals(currentSearchMode)) {
            currentSearchMode = "";
            offlineRecognizer.stop();
        }
    }

    @Override
    public void onPartialResult(Hypothesis hypothesis) {
        if (hypothesis == null) return;
        String partial = hypothesis.getHypstr();
        if (partial == null) return;
        String lower = partial.trim().toLowerCase();
        if (lower.length() == 0) return;

        if (SEARCH_WAKEUP.equals(currentSearchMode)) {
            if (lower.contains("hey batman") || lower.contains("hello robot")) {
                switchToCommandListeningOnUi(true);
            }
            return;
        }

        if (SEARCH_BATMAN_CMD.equals(currentSearchMode)) {
            String clean = normalizeNumbersInSpeech(lower).replace("'", "");
            if (clean.length() > 0) {
                sendJsToWebView("if(window.onOfflineMicState){window.onOfflineMicState('PARTIAL', '" + clean + "');}");
            }
        }
    }

    @Override
    public void onResult(Hypothesis hypothesis) {
        if (hypothesis != null && hypothesis.getHypstr() != null) {
            String raw = hypothesis.getHypstr().trim().toLowerCase();
            if (raw.equals("hey batman") || raw.equals("hello robot")) {
                return;
            }
            String spoken = normalizeNumbersInSpeech(raw).replace("'", "");
            if (spoken.length() > 0) {
                sendJsToWebView("if(window.onVoiceCommandResult){window.onVoiceCommandResult('" + spoken + "');}");
                scheduleResumeWakeup(4000);
                return;
            }
        }
        scheduleResumeWakeup(500);
    }

    @Override
    public void onError(Exception error) {
        currentSearchMode = "";
        scheduleResumeWakeup(2000);
    }

    @Override
    public void onTimeout() {
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
                if (offlineRecognizer != null) {
                    currentSearchMode = "";
                    offlineRecognizer.cancel();
                }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                long speakDurationMs = Math.max(2800L, Math.min(18000L, (long) text.length() * 75L));
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
                    currentSearchMode = "";
                    offlineRecognizer.stop();
                    return;
                }
                switchToCommandListeningOnUi(false);
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
            scheduleResumeWakeup(600);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = false;
        watchdogHandler.removeCallbacks(this);
        if (watchdogHandler != null && resumeWakeupRunnable != null) {
            watchdogHandler.removeCallbacks(resumeWakeupRunnable);
        }
        if (offlineRecognizer != null) {
            currentSearchMode = "";
            offlineRecognizer.cancel();
        }
    }

    @Override
    protected void onDestroy() {
        isRunning = false;
        watchdogHandler.removeCallbacks(this);
        if (watchdogHandler != null && resumeWakeupRunnable != null) {
            watchdogHandler.removeCallbacks(resumeWakeupRunnable);
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
