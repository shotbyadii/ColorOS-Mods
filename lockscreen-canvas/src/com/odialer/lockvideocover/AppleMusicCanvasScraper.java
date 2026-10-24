package com.odialer.lockvideocover;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppleMusicCanvasScraper {
    private static final String TAG = "AppleMusicCanvasScraper";
    private static final String AMP_SEARCH_URL = "https://amp-api.music.apple.com/v1/catalog";
    private static final String WEB_PLAYER_URL = "https://music.apple.com/us/browse";

    private static volatile String sCachedToken = null;
    private static volatile long sTokenExpiryMs = 0;

    public interface AppleCanvasCallback {
        void onSuccess(String videoUrl);
        void onNoCanvas();
    }

    public static void resolveCanvas(String title, String artist, AppleCanvasCallback callback) {
        new Thread(() -> {
            try {
                String token = getOrScrapeToken();
                if (token == null) {
                    Log.w(TAG, "Could not obtain Apple Music web token");
                    callback.onNoCanvas();
                    return;
                }

                String storefront = Locale.getDefault().getCountry().toLowerCase(Locale.ROOT);
                if (storefront.length() != 2) storefront = "us";

                String query = (title + " " + (artist != null ? artist : "")).trim();
                String urlStr = AMP_SEARCH_URL + "/" + storefront + "/search?term=" +
                        URLEncoder.encode(query, "UTF-8") + "&types=songs&limit=5&extend=editorialVideo";

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Origin", "https://music.apple.com");
                conn.setRequestProperty("Referer", "https://music.apple.com/");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                if (conn.getResponseCode() != 200) {
                    Log.w(TAG, "Apple search failed with HTTP " + conn.getResponseCode());
                    callback.onNoCanvas();
                    return;
                }

                java.util.Scanner s = new java.util.Scanner(conn.getInputStream()).useDelimiter("\\A");
                String response = s.hasNext() ? s.next() : "";
                JSONObject json = new JSONObject(response);
                JSONObject results = json.optJSONObject("results");
                if (results == null) {
                    callback.onNoCanvas();
                    return;
                }
                JSONObject songs = results.optJSONObject("songs");
                if (songs == null) {
                    callback.onNoCanvas();
                    return;
                }
                JSONArray data = songs.optJSONArray("data");
                if (data == null || data.length() == 0) {
                    callback.onNoCanvas();
                    return;
                }

                for (int i = 0; i < data.length(); i++) {
                    JSONObject song = data.getJSONObject(i);
                    JSONObject attr = song.optJSONObject("attributes");
                    if (attr == null) continue;

                    JSONObject editorialVideo = attr.optJSONObject("editorialVideo");
                    if (editorialVideo != null) {
                        String videoUrl = extractMotionUrl(editorialVideo);
                        if (videoUrl != null) {
                            Log.d(TAG, "Found Apple Music motion artwork URL: " + videoUrl);
                            callback.onSuccess(videoUrl);
                            return;
                        }
                    }
                }

                callback.onNoCanvas();
            } catch (Exception e) {
                Log.e(TAG, "Apple canvas resolution threw: " + e.getMessage());
                callback.onNoCanvas();
            }
        }).start();
    }

    private static String extractMotionUrl(JSONObject editorialVideo) {
        String[] keys = {"motionDetailSquare", "motionDetailRaw", "motionDetailTall", "motionSquareVideo1x1"};
        for (String key : keys) {
            JSONObject obj = editorialVideo.optJSONObject(key);
            if (obj != null) {
                String video = obj.optString("video", null);
                if (video == null) video = obj.optString("videoUrl", null);
                if (video == null) video = obj.optString("hlsUrl", null);
                if (video != null && !video.isEmpty()) {
                    return video;
                }
            }
        }
        return null;
    }

    private static synchronized String getOrScrapeToken() {
        long now = System.currentTimeMillis();
        if (sCachedToken != null && now < sTokenExpiryMs - 60000) {
            return sCachedToken;
        }

        try {
            URL url = new URL(WEB_PLAYER_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            java.util.Scanner s = new java.util.Scanner(conn.getInputStream()).useDelimiter("\\A");
            String html = s.hasNext() ? s.next() : "";

            Pattern p = Pattern.compile("/assets/index(?:-legacy)?[~-][A-Za-z0-9_-]+\\.js");
            Matcher m = p.matcher(html);
            while (m.find()) {
                String jsPath = m.group();
                String jsUrl = "https://music.apple.com" + jsPath;
                URL scriptUrl = new URL(jsUrl);
                HttpURLConnection scriptConn = (HttpURLConnection) scriptUrl.openConnection();
                scriptConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                scriptConn.setConnectTimeout(8000);
                scriptConn.setReadTimeout(8000);

                java.util.Scanner jsScan = new java.util.Scanner(scriptConn.getInputStream()).useDelimiter("\\A");
                String jsContent = jsScan.hasNext() ? jsScan.next() : "";

                Pattern jwtPattern = Pattern.compile("ey[A-Za-z0-9_-]+\\.ey[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
                Matcher jwtMatcher = jwtPattern.matcher(jsContent);
                while (jwtMatcher.find()) {
                    String jwt = jwtMatcher.group();
                    if (jwt.length() > 50) {
                        sCachedToken = jwt;
                        sTokenExpiryMs = System.currentTimeMillis() + (3600 * 1000);
                        return jwt;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Scraping Apple Music token failed: " + e.getMessage());
        }
        return null;
    }
}
