package com.odialer.lockvideocover;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpotifyCanvasScraper {
    private static final String TAG = "SpotifyCanvasScraper";
    private static final String SEARCH_URL = "https://api.spotify.com/v1/search";
    private static final String CANVAS_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases";
    private static final String SPOTIFY_APP_UA = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)";

    private static final Pattern CANVAS_URL_REGEX = Pattern.compile("https://[^\"'\\s\\x00-\\x1F]+\\.cnvs\\.mp4");

    private static final String PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query";
    private static final String PATHFINDER_SEARCH_HASH = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428";
    private static final String WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public interface CanvasResultCallback {
        void onSuccess(String videoUrl, String trackUri);
        void onNoCanvas();
        void onError(String message);
    }

    public static void resolveCanvas(Context context, String title, String artist, String album, CanvasResultCallback callback) {
        SpotifyTokenManager.getAccessToken(context, new SpotifyTokenManager.TokenCallback() {
            @Override
            public void onTokenReady(String accessToken, String clientToken) {
                new Thread(() -> {
                    try {
                        String trackUri = searchViaPathfinder(title, artist, album, accessToken, clientToken);
                        if (trackUri == null) {
                            trackUri = searchTrackUriRest(title, artist, accessToken, clientToken);
                        }

                        if (trackUri == null) {
                            Log.d(TAG, "No track hit found on Spotify for: " + title + " - " + artist);
                            callback.onNoCanvas();
                            return;
                        }

                        Log.d(TAG, "Matched Spotify track: " + trackUri + " for: " + title);
                        String videoUrl = fetchCanvasUrl(trackUri, accessToken, clientToken);
                        if (videoUrl != null) {
                            Log.d(TAG, "Found Spotify Canvas URL: " + videoUrl);
                            callback.onSuccess(videoUrl, trackUri);
                        } else {
                            Log.d(TAG, "Track has no canvas clip: " + trackUri);
                            callback.onNoCanvas();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error resolving Spotify canvas: " + e.getMessage(), e);
                        callback.onError(e.getMessage());
                    }
                }).start();
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    private static String searchViaPathfinder(String title, String artist, String album, String token, String clientToken) {
        try {
            String searchTerm = (title + " " + (artist != null ? artist : "")).trim();
            JSONObject vars = new JSONObject();
            vars.put("searchTerm", searchTerm);
            vars.put("offset", 0);
            vars.put("limit", 10);
            vars.put("numberOfTopResults", 5);
            vars.put("includeAudiobooks", false);
            vars.put("includePreReleases", false);

            JSONObject ext = new JSONObject();
            JSONObject pq = new JSONObject();
            pq.put("version", 1);
            pq.put("sha256Hash", PATHFINDER_SEARCH_HASH);
            ext.put("persistedQuery", pq);

            String urlStr = PATHFINDER_URL + "?operationName=searchTracks" +
                    "&variables=" + URLEncoder.encode(vars.toString(), "UTF-8") +
                    "&extensions=" + URLEncoder.encode(ext.toString(), "UTF-8");

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            if (clientToken != null) {
                conn.setRequestProperty("Client-Token", clientToken);
            }
            conn.setRequestProperty("App-Platform", "WebPlayer");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", WEB_UA);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int code = conn.getResponseCode();
            if (code != 200) {
                String err = "";
                try {
                    java.util.Scanner es = new java.util.Scanner(conn.getErrorStream()).useDelimiter("\\A");
                    err = es.hasNext() ? es.next() : "";
                } catch (Exception ignored) {}
                Log.w(TAG, "Pathfinder search failed with HTTP " + code + ": " + err);
                return null;
            }

            java.util.Scanner s = new java.util.Scanner(conn.getInputStream()).useDelimiter("\\A");
            String response = s.hasNext() ? s.next() : "";
            Log.d(TAG, "Pathfinder canvas response: " + response);
            JSONObject json = new JSONObject(response);
            JSONObject data = json.optJSONObject("data");
            if (data == null) return null;
            JSONObject searchV2 = data.optJSONObject("searchV2");
            if (searchV2 == null) return null;
            JSONObject tracksV2 = searchV2.optJSONObject("tracksV2");
            if (tracksV2 == null) return null;
            JSONArray items = tracksV2.optJSONArray("items");
            if (items == null || items.length() == 0) return null;

            JSONObject firstItem = items.getJSONObject(0);
            JSONObject itemObj = firstItem.optJSONObject("item");
            if (itemObj == null) return null;
            JSONObject itemData = itemObj.optJSONObject("data");
            if (itemData == null) return null;

            String uri = itemData.optString("uri", null);
            if (uri == null) {
                String id = itemData.optString("id", null);
                if (id != null) uri = "spotify:track:" + id;
            }
            return uri;
        } catch (Exception e) {
            Log.w(TAG, "Pathfinder search exception: " + e.getMessage());
            return null;
        }
    }

    private static String searchTrackUriRest(String title, String artist, String token, String clientToken) {
        try {
            String query = (title + " " + (artist != null ? artist : "")).trim();
            String urlStr = SEARCH_URL + "?q=" + URLEncoder.encode(query, "UTF-8") + "&type=track&limit=5";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            if (clientToken != null) {
                conn.setRequestProperty("Client-Token", clientToken);
            }
            conn.setRequestProperty("User-Agent", SPOTIFY_APP_UA);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "Spotify search failed with HTTP " + conn.getResponseCode());
                return null;
            }

            java.util.Scanner s = new java.util.Scanner(conn.getInputStream()).useDelimiter("\\A");
            String response = s.hasNext() ? s.next() : "";
            Log.d(TAG, "Pathfinder canvas response: " + response);
            JSONObject json = new JSONObject(response);
            JSONObject tracks = json.optJSONObject("tracks");
            if (tracks == null) return null;
            JSONArray items = tracks.optJSONArray("items");
            if (items == null || items.length() == 0) return null;

            JSONObject firstHit = items.getJSONObject(0);
            return firstHit.optString("uri", null);
        } catch (Exception e) {
            Log.e(TAG, "Search query error: " + e.getMessage());
            return null;
        }
    }

    private static final String PATHFINDER_CANVAS_URL = "https://api-partner.spotify.com/pathfinder/v2/query";
    private static final String PATHFINDER_CANVAS_HASH = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f";

    private static String fetchCanvasUrl(String trackUri, String token, String clientToken) {
        String url = fetchCanvasViaPathfinder(trackUri, token, clientToken);
        if (url != null) return url;
        return fetchCanvasViaCanvaz(trackUri, token, clientToken);
    }

    private static String fetchCanvasViaPathfinder(String trackUri, String token, String clientToken) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("operationName", "canvas");
            JSONObject vars = new JSONObject();
            vars.put("trackUri", trackUri);
            payload.put("variables", vars);

            JSONObject ext = new JSONObject();
            JSONObject pq = new JSONObject();
            pq.put("version", 1);
            pq.put("sha256Hash", PATHFINDER_CANVAS_HASH);
            ext.put("persistedQuery", pq);
            payload.put("extensions", ext);

            byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(PATHFINDER_CANVAS_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            if (clientToken != null) {
                conn.setRequestProperty("Client-Token", clientToken);
            }
            conn.setRequestProperty("App-Platform", "WebPlayer");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", WEB_UA);
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Pathfinder canvas query returned HTTP " + code);
                return null;
            }

            java.util.Scanner s = new java.util.Scanner(conn.getInputStream()).useDelimiter("\\A");
            String response = s.hasNext() ? s.next() : "";
            Log.d(TAG, "Pathfinder canvas response: " + response);
            JSONObject json = new JSONObject(response);
            JSONObject data = json.optJSONObject("data");
            if (data == null) return null;
            JSONObject trackUnion = data.optJSONObject("trackUnion");
            if (trackUnion == null) return null;
            JSONObject canvas = trackUnion.optJSONObject("canvas");
            if (canvas == null) return null;

            String canvasUrl = canvas.optString("url", null);
            if (canvasUrl != null && (canvasUrl.contains(".mp4") || canvasUrl.startsWith("https://"))) {
                Log.d(TAG, "Resolved canvas via Pathfinder v2: " + canvasUrl);
                return canvasUrl;
            }
        } catch (Exception e) {
            Log.w(TAG, "Pathfinder canvas query threw: " + e.getMessage());
        }
        return null;
    }

    private static String fetchCanvasViaCanvaz(String trackUri, String token, String clientToken) {
        try {
            byte[] protoPayload = encodeCanvasRequest(trackUri);

            URL url = new URL(CANVAS_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            if (clientToken != null) {
                conn.setRequestProperty("Client-Token", clientToken);
            }
            conn.setRequestProperty("Content-Type", "application/protobuf");
            conn.setRequestProperty("Accept", "application/protobuf");
            conn.setRequestProperty("User-Agent", SPOTIFY_APP_UA);
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(protoPayload);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Canvas request failed with HTTP " + code);
                return null;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }
            }
            byte[] responseBytes = baos.toByteArray();
            String responseStr = new String(responseBytes, StandardCharsets.ISO_8859_1);

            Matcher matcher = CANVAS_URL_REGEX.matcher(responseStr);
            if (matcher.find()) {
                return matcher.group();
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error fetching canvas bytes: " + e.getMessage());
            return null;
        }
    }

    /**
     * Hand-rolled protobuf encoder for CanvasRequest:
     * message CanvasRequest {
     *   repeated Track tracks = 1;
     * }
     * message Track {
     *   string track_uri = 1;
     * }
     */
    private static byte[] encodeCanvasRequest(String trackUri) throws Exception {
        byte[] uriBytes = trackUri.getBytes(StandardCharsets.UTF_8);

        // Track submessage: tag 1 (field 1, wire type 2 -> (1 << 3) | 2 = 0x0A)
        ByteArrayOutputStream trackStream = new ByteArrayOutputStream();
        trackStream.write(0x0A);
        writeVarint(trackStream, uriBytes.length);
        trackStream.write(uriBytes);
        byte[] trackBytes = trackStream.toByteArray();

        // CanvasRequest message: tag 1 (field 1, wire type 2 -> 0x0A)
        ByteArrayOutputStream reqStream = new ByteArrayOutputStream();
        reqStream.write(0x0A);
        writeVarint(reqStream, trackBytes.length);
        reqStream.write(trackBytes);

        return reqStream.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
