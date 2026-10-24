package com.odialer.lockvideocover;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SpotifyTokenManager {
    private static final String TAG = "SpotifyTokenManager";
    private static final String PREF_NAME = "spotify_prefs";
    private static final String KEY_SP_DC = "sp_dc";
    private static final String DEFAULT_SP_DC = "AQCCI_HfnQhT-LPRvgGmtBDxgXB6YoZTkJ29M0FNWFpA1UYDrOOThOAk84v1t9T9OkGojZ3SJxAmnFdPuhKFU_GPCynSCQSqjDutOx1kohrkveV3e2t035H3SKIFC0Zou5hVqP-s4P-V6iJYvUvGpJgjkR6L9HFbwEB5ge1JipSQp6kahr4As6-Q1_AH5IE3-VvCVFOE1_05kdMHMPe6EQUzYHNpIc91fy68qvWHgPfVlX7n_izJLEGGSA0u_31nEUj28bnWol4ONw";

    private static volatile String sAccessToken = null;
    private static volatile long sTokenExpiryMs = 0;
    private static volatile String sClientId = null;
    private static volatile String sClientToken = null;
    private static volatile long sClientTokenExpiryMs = 0;
    private static volatile boolean sIsHarvesting = false;

    public interface TokenCallback {
        void onTokenReady(String accessToken, String clientToken);
        void onError(String error);
    }

    public static String getSpDcToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_SP_DC, DEFAULT_SP_DC);
    }

    public static void setSpDcToken(Context context, String spDc) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_SP_DC, spDc).apply();
        invalidate();
    }

    public static void invalidate() {
        sAccessToken = null;
        sTokenExpiryMs = 0;
        sClientId = null;
        sClientToken = null;
        sClientTokenExpiryMs = 0;
    }

    public static synchronized void getAccessToken(Context context, TokenCallback callback) {
        long now = System.currentTimeMillis();
        if (sAccessToken != null && now < (sTokenExpiryMs - 60000)) {
            callback.onTokenReady(sAccessToken, sClientToken);
            return;
        }

        if (sIsHarvesting) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                getAccessToken(context, callback);
            }, 1000);
            return;
        }

        sIsHarvesting = true;
        new Handler(Looper.getMainLooper()).post(() -> {
            harvestTokenViaWebView(context.getApplicationContext(), callback);
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void harvestTokenViaWebView(Context context, TokenCallback callback) {
        String spDc = getSpDcToken(context);
        if (spDc == null || spDc.trim().isEmpty()) {
            sIsHarvesting = false;
            callback.onError("sp_dc token is not configured");
            return;
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setCookie("https://open.spotify.com/", "sp_dc=" + spDc + "; Domain=.spotify.com; Path=/; Secure");
        cookieManager.setCookie("https://accounts.spotify.com/", "sp_dc=" + spDc + "; Domain=.spotify.com; Path=/; Secure");
        cookieManager.flush();

        final WebView[] webViewRef = new WebView[1];
        Handler timeoutHandler = new Handler(Looper.getMainLooper());
        Runnable timeoutRunnable = () -> {
            if (sIsHarvesting) {
                sIsHarvesting = false;
                destroyWebView(webViewRef[0]);
                callback.onError("Spotify token harvest timed out (20s)");
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, 20000);

        try {
            WebView webView = new WebView(context);
            webViewRef[0] = webView;
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
            cookieManager.setAcceptThirdPartyCookies(webView, true);

            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void onTokenPayload(String payload) {
                    try {
                        JSONObject json = new JSONObject(payload);
                        boolean isAnonymous = json.optBoolean("isAnonymous", false);
                        String token = json.optString("accessToken", null);
                        if (token == null || isAnonymous) return;

                        long expiresMs = json.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 3600000);
                        String clientId = json.optString("clientId", null);

                        sAccessToken = token;
                        sTokenExpiryMs = expiresMs;
                        sClientId = clientId;

                        Log.d(TAG, "Successfully harvested Spotify access token! Expires at " + expiresMs);

                        // Now harvest client token in background thread
                        new Thread(() -> {
                            fetchClientToken(token, clientId);
                            sIsHarvesting = false;
                            timeoutHandler.removeCallbacks(timeoutRunnable);
                            destroyWebView(webViewRef[0]);
                            callback.onTokenReady(sAccessToken, sClientToken);
                        }).start();

                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse token payload: " + e.getMessage());
                    }
                }
            }, "LockVideoBridge");

            final String hookScript = "(function () {" +
                    "  if (window.__lvcHook) return;" +
                    "  window.__lvcHook = true;" +
                    "  var report = function (body) { try { LockVideoBridge.onTokenPayload(body); } catch(e){} };" +
                    "  var isToken = function (u) { try { return String(u).indexOf('/api/token') !== -1; } catch(e){ return false; } };" +
                    "  var origFetch = window.fetch;" +
                    "  if (origFetch) {" +
                    "    window.fetch = function(input, init) {" +
                    "      var url = (input && input.url) ? input.url : input;" +
                    "      var res = origFetch.apply(this, arguments);" +
                    "      if (isToken(url)) {" +
                    "        try { res.then(function(r){ r.clone().text().then(report).catch(function(){}); }).catch(function(){}); } catch(e){}" +
                    "      }" +
                    "      return res;" +
                    "    };" +
                    "  }" +
                    "  var origOpen = XMLHttpRequest.prototype.open;" +
                    "  XMLHttpRequest.prototype.open = function(method, url) { this.__u = url; return origOpen.apply(this, arguments); };" +
                    "  var origSend = XMLHttpRequest.prototype.send;" +
                    "  XMLHttpRequest.prototype.send = function() {" +
                    "    var xhr = this;" +
                    "    try { xhr.addEventListener('load', function() { if (isToken(xhr.__u)) { try { report(xhr.responseText); } catch(e){} } }); } catch(e){}" +
                    "    return origSend.apply(this, arguments);" +
                    "  };" +
                    "})();";

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    view.evaluateJavascript(hookScript, null);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    view.evaluateJavascript(hookScript, null);
                }
            });

            webView.loadUrl("https://open.spotify.com/");
        } catch (Exception e) {
            sIsHarvesting = false;
            timeoutHandler.removeCallbacks(timeoutRunnable);
            callback.onError("WebView init failed: " + e.getMessage());
        }
    }

    private static void destroyWebView(WebView webView) {
        if (webView == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                webView.removeJavascriptInterface("LockVideoBridge");
                webView.stopLoading();
                webView.destroy();
            } catch (Exception ignored) {}
        });
    }

    private static void fetchClientToken(String accessToken, String clientId) {
        if (clientId == null) return;
        try {
            JSONObject jsSdkData = new JSONObject();
            jsSdkData.put("device_brand", "unknown");
            jsSdkData.put("device_model", "unknown");
            jsSdkData.put("os", "android");
            jsSdkData.put("os_version", "15");
            jsSdkData.put("device_id", java.util.UUID.randomUUID().toString());
            jsSdkData.put("device_type", "smartphone");

            JSONObject clientData = new JSONObject();
            clientData.put("client_version", "1.2.37.1");
            clientData.put("client_id", clientId);
            clientData.put("js_sdk_data", jsSdkData);

            JSONObject payload = new JSONObject();
            payload.put("client_data", clientData);

            byte[] postData = payload.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL("https://clienttoken.spotify.com/v1/clienttoken");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
            }

            if (conn.getResponseCode() == 200) {
                java.util.Scanner s = new java.util.Scanner(conn.getInputStream()).useDelimiter("\\A");
                String response = s.hasNext() ? s.next() : "";
                JSONObject resJson = new JSONObject(response);
                JSONObject granted = resJson.optJSONObject("granted_token");
                if (granted != null) {
                    sClientToken = granted.optString("token", null);
                    long ttl = granted.optLong("expires_after_seconds", 3600);
                    sClientTokenExpiryMs = System.currentTimeMillis() + (ttl * 1000);
                    Log.d(TAG, "Minted Spotify client token!");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch client token: " + e.getMessage());
        }
    }
}
