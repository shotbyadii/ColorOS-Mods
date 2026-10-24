package com.odialer.lockvideocover;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages fetching, caching, and real-time synchronization of time-synced lyrics
 * using the LRCLIB open synced lyrics database (the same backend used by BitChord).
 */
public class SyncedLyricsManager {
    private static final String TAG = "SyncedLyricsManager";
    private static final String USER_AGENT = "LockVideoCover/2.0 (Android; BitChordIntegration)";

    public static class LyricLine {
        public final long timeMs;
        public final String text;

        public LyricLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private static final Pattern LRC_PATTERN =
            Pattern.compile("^\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]\\s*(.*)$");

    // In-memory cache for recent tracks (key: "title_artist")
    private static final LruCache<String, List<LyricLine>> sLyricsCache = new LruCache<>(25);
    private static volatile String sCurrentTrackKey = "";
    private static volatile List<LyricLine> sCurrentLyrics = null;
    private static volatile boolean sIsFetching = false;

    /**
     * Initiates background lyrics retrieval for the given track.
     */
    public static void prepareLyricsForTrack(String title, String artist, long durationMs) {
        if (title == null || title.trim().isEmpty()) {
            sCurrentLyrics = null;
            return;
        }

        final String cleanTitle = cleanSearchQuery(title);
        final String cleanArtist = (artist != null) ? cleanSearchQuery(artist) : "";
        final String cacheKey = (cleanTitle + "_" + cleanArtist).toLowerCase();

        if (cacheKey.equals(sCurrentTrackKey) && sCurrentLyrics != null) {
            return;
        }

        sCurrentTrackKey = cacheKey;

        // Check cache
        List<LyricLine> cached = sLyricsCache.get(cacheKey);
        if (cached != null) {
            sCurrentLyrics = cached;
            Log.d(TAG, "Loaded " + cached.size() + " synced lyric lines from cache for: " + cleanTitle);
            return;
        }

        sCurrentLyrics = null;
        fetchLyricsAsync(cleanTitle, cleanArtist, durationMs, cacheKey);
    }

    private static void fetchLyricsAsync(final String title, final String artist, final long durationMs, final String cacheKey) {
        if (sIsFetching) return;
        sIsFetching = true;

        new Thread(() -> {
            List<LyricLine> parsedLines = null;
            try {
                // Step 1: Try direct GET with duration if available
                String query = title;
                if (!artist.isEmpty()) {
                    query = title + " " + artist;
                }
                String urlStr = "https://lrclib.net/api/search?q=" + URLEncoder.encode(query, "UTF-8");

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);

                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                    }

                    JSONArray arr = new JSONArray(sb.toString());
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        if (obj.has("syncedLyrics") && !obj.isNull("syncedLyrics")) {
                            String syncedLrc = obj.getString("syncedLyrics");
                            if (syncedLrc != null && !syncedLrc.trim().isEmpty()) {
                                parsedLines = parseLrc(syncedLrc);
                                if (!parsedLines.isEmpty()) {
                                    Log.i(TAG, "Successfully fetched and parsed " + parsedLines.size() +
                                            " synced lines for: " + title);
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error querying LRCLIB for '" + title + "': " + e.getMessage());
            } finally {
                sIsFetching = false;
            }

            if (parsedLines != null && !parsedLines.isEmpty()) {
                sLyricsCache.put(cacheKey, parsedLines);
                if (cacheKey.equals(sCurrentTrackKey)) {
                    sCurrentLyrics = parsedLines;
                    LockscreenWallpaperService.requestOverlayRedraw();
                }
            }
        }).start();
    }

    private static List<LyricLine> parseLrc(String lrcContent) {
        List<LyricLine> lines = new ArrayList<>();
        String[] rawLines = lrcContent.split("\r?\n");

        for (String raw : rawLines) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;

            Matcher m = LRC_PATTERN.matcher(trimmed);
            if (m.matches()) {
                try {
                    int minutes = Integer.parseInt(m.group(1));
                    int seconds = Integer.parseInt(m.group(2));
                    int fraction = 0;
                    if (m.group(3) != null) {
                        String fracStr = m.group(3);
                        fraction = Integer.parseInt(fracStr);
                        if (fracStr.length() == 2) {
                            fraction *= 10;
                        } else if (fracStr.length() == 1) {
                            fraction *= 100;
                        }
                    }

                    long timeMs = (minutes * 60L + seconds) * 1000L + fraction;
                    String text = m.group(4);
                    if (text != null && !text.trim().isEmpty()) {
                        lines.add(new LyricLine(timeMs, text.trim()));
                    }
                } catch (Exception ignored) {}
            }
        }

        Collections.sort(lines, Comparator.comparingLong(a -> a.timeMs));
        return lines;
    }

    /**
     * Resolves the current active lyric line for the playback position in milliseconds.
     */
    public static String getCurrentLyric(long positionMs) {
        List<LyricLine> lines = sCurrentLyrics;
        if (lines == null || lines.isEmpty() || positionMs < 0) {
            return null;
        }

        int activeIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= positionMs) {
                activeIdx = i;
            } else {
                break;
            }
        }

        if (activeIdx >= 0 && activeIdx < lines.size()) {
            return lines.get(activeIdx).text;
        }
        return null;
    }

    private static String cleanSearchQuery(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)\\[.*?\\]|\\(.*?\\)", "")
                .replaceAll("(?i)(feat\\.|ft\\.).*", "")
                .trim();
    }
}
