package com.batman.wleddashboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener, Runnable {

    private static final String PREFS_NAME = "BatmanWledPrefs";
    private static final String DEFAULT_SSID = "wledtabletop";
    private static final int REQ_CODE_SPEECH_INPUT = 1001;

    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SharedPreferences prefs;

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
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
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
            if (watchdogHandler != null) {
                watchdogHandler.postDelayed(new JsCallbackRunnable(webView, "if(window.onVoiceCommandFallback){window.onVoiceCommandFallback();}"), 200);
            }
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = false;
        watchdogHandler.removeCallbacks(this);
    }

    @Override
    protected void onDestroy() {
        isRunning = false;
        watchdogHandler.removeCallbacks(this);
        if (wifiLock != null && wifiLock.isHeld()) {
            try {
                wifiLock.release();
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
