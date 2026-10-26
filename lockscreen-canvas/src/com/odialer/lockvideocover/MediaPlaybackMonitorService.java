package com.odialer.lockvideocover;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.net.Uri;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MediaPlaybackMonitorService extends NotificationListenerService {
    private static final String TAG = "MediaMonitorService";
    public static final String ACTION_STATUS_UPDATE = "com.odialer.lockvideocover.STATUS_UPDATE";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_ARTIST = "extra_artist";
    public static final String EXTRA_STATUS = "extra_status";

    public static volatile String sCurrentTitle = null;
    public static volatile String sCurrentArtist = null;
    public static volatile String sCurrentStatus = null;
    private static volatile MediaPlaybackMonitorService sInstance = null;

    private MediaSessionManager mSessionManager;
    private MediaController mActiveController;
    private String mLastTrackKey = "";
    private Bitmap mLastExtractedAlbumArt = null;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public static class PlaybackSnapshot {
        public String title = "";
        public String artist = "";
        public long durationMs = 0;
        public long positionMs = 0;
        public long lastPositionUpdateTime = 0;
        public float playbackSpeed = 0f;
        public boolean isPlaying = false;
        public boolean isExplicit = false;
        public String packageName = "com.music.bitchord";
    }

    private static final PlaybackSnapshot sSnapshot = new PlaybackSnapshot();

    public static synchronized PlaybackSnapshot getPlaybackSnapshot() {
        if (sInstance != null && sInstance.mActiveController != null) {
            try {
                MediaMetadata meta = sInstance.mActiveController.getMetadata();
                if (meta != null) {
                    sSnapshot.durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    sSnapshot.title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
                    sSnapshot.artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
                }
                PlaybackState state = sInstance.mActiveController.getPlaybackState();
                if (state != null) {
                    sSnapshot.positionMs = state.getPosition();
                    sSnapshot.lastPositionUpdateTime = state.getLastPositionUpdateTime();
                    sSnapshot.playbackSpeed = state.getPlaybackSpeed();
                    sSnapshot.isPlaying = (state.getState() == PlaybackState.STATE_PLAYING);
                }
                sSnapshot.packageName = sInstance.mActiveController.getPackageName();
            } catch (Throwable ignored) {}
        }
        return sSnapshot;
    }

    public static boolean isTopGradientBlendEnabled(Context context) {
        return context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
                .getBoolean("top_gradient_blend", true);
    }

    public static void setTopGradientBlendEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("top_gradient_blend", enabled).apply();
    }

    public static boolean isNowPlayingCardEnabled(Context context) {
        return context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
                .getBoolean("now_playing_card_enabled", true);
    }

    public static void setNowPlayingCardEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("now_playing_card_enabled", enabled).apply();
    }

    public static boolean isWakeOnTrackChangeEnabled(Context context) {
        return context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
                .getBoolean("wake_on_track_change", true);
    }

    public static void setWakeOnTrackChangeEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("wake_on_track_change", enabled).apply();
    }

    public static boolean isInjectionEnabled(Context context) {
        return context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
                .getBoolean("injection_enabled", true);
    }

    public static void setInjectionEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("injection_enabled", enabled).apply();
    }

    public static boolean isAlbumArtOnNoCanvasEnabled(Context context) {
        return context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
                .getBoolean("album_art_on_no_canvas", true);
    }

    public static void setAlbumArtOnNoCanvasEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("album_art_on_no_canvas", enabled).apply();
    }

    private final MediaController.Callback mControllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            handleMetadataChange(metadata);
            PlaybackQualityOverlay.checkStreamUpgrade(MediaPlaybackMonitorService.this);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state != null) {
                boolean isPlaying = isPlayingOrBuffering(state);
                LockscreenWallpaperService.notifyPlaybackState(isPlaying);
                if (isPlaying) {
                    PlaybackQualityOverlay.checkStreamUpgrade(MediaPlaybackMonitorService.this);
                }
            }
        }

        @Override
        public void onSessionEvent(String event, Bundle extras) {
            PlaybackQualityOverlay.checkStreamUpgrade(MediaPlaybackMonitorService.this);
        }

        @Override
        public void onExtrasChanged(Bundle extras) {
            PlaybackQualityOverlay.checkStreamUpgrade(MediaPlaybackMonitorService.this);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionsChangedListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    evaluateActiveSession(controllers);
                }
            };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = this;
        Log.d(TAG, "NotificationListener connected");

        IntentFilter simFilter = new IntentFilter("com.odialer.lockvideocover.SIMULATE_TRACK");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String title = intent.getStringExtra("title");
                    String artist = intent.getStringExtra("artist");
                    if (title != null && !title.trim().isEmpty()) {
                        processTrack(title, artist != null ? artist : "", null, null);
                    }
                }
            }, simFilter, Context.RECEIVER_EXPORTED);
        }

        mSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (mSessionManager != null) {
            ComponentName componentName = new ComponentName(this, MediaPlaybackMonitorService.class);
            try {
                mSessionManager.addOnActiveSessionsChangedListener(mSessionsChangedListener, componentName);
                evaluateActiveSession(mSessionManager.getActiveSessions(componentName));
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException registering session listener: " + e.getMessage());
            }
        }

        android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            try {
                am.registerAudioPlaybackCallback(new android.media.AudioManager.AudioPlaybackCallback() {
                    @Override
                    public void onPlaybackConfigChanged(List<android.media.AudioPlaybackConfiguration> configs) {
                        boolean changed = PlaybackQualityOverlay.updateFromPlaybackConfigs(configs);
                        if (changed) {
                            LockscreenWallpaperService.requestOverlayRedraw();
                        }
                    }
                }, mHandler);
                Log.d(TAG, "AudioPlaybackCallback registered for real-time codec updates");
            } catch (Throwable t) {
                Log.w(TAG, "Could not register AudioPlaybackCallback: " + t.getMessage());
            }
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (mSessionManager != null) {
            mSessionManager.removeOnActiveSessionsChangedListener(mSessionsChangedListener);
        }
        detachCurrentController();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn != null && ("com.music.bitchord".equals(sbn.getPackageName()) ||
                "com.spotify.music".equals(sbn.getPackageName()) ||
                "com.apple.android.music".equals(sbn.getPackageName()))) {

            Notification n = sbn.getNotification();
            Bitmap notifArt = extractNotificationBitmap(n);
            if (n != null && n.extras != null) {
                CharSequence titleCS = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence textCS = n.extras.getCharSequence(Notification.EXTRA_TEXT);

                if (titleCS != null && titleCS.length() > 0) {
                    processTrack(titleCS.toString(), textCS != null ? textCS.toString() : "", null, notifArt);
                }
            }

            if (mSessionManager != null) {
                ComponentName componentName = new ComponentName(this, MediaPlaybackMonitorService.class);
                try {
                    evaluateActiveSession(mSessionManager.getActiveSessions(componentName));
                } catch (SecurityException ignored) {}
            }
            if (mActiveController != null) {
                PlaybackState state = mActiveController.getPlaybackState();
                if (state != null) {
                    LockscreenWallpaperService.notifyPlaybackState(isPlayingOrBuffering(state));
                }
            }
            PlaybackQualityOverlay.checkStreamUpgrade(this);
        }
    }

    private Bitmap extractNotificationBitmap(Notification n) {
        if (n == null || n.extras == null) return null;
        try {
            if (n.extras.containsKey(Notification.EXTRA_PICTURE)) {
                Object pic = n.extras.get(Notification.EXTRA_PICTURE);
                if (pic instanceof Bitmap) {
                    return (Bitmap) pic;
                } else if (pic instanceof android.graphics.drawable.Icon) {
                    return iconToBitmap((android.graphics.drawable.Icon) pic);
                }
            }
            if (n.extras.containsKey(Notification.EXTRA_LARGE_ICON)) {
                Object iconObj = n.extras.get(Notification.EXTRA_LARGE_ICON);
                if (iconObj instanceof Bitmap) {
                    return (Bitmap) iconObj;
                } else if (iconObj instanceof android.graphics.drawable.Icon) {
                    return iconToBitmap((android.graphics.drawable.Icon) iconObj);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error extracting notification bitmap: " + t.getMessage());
        }
        return null;
    }

    private Bitmap iconToBitmap(android.graphics.drawable.Icon icon) {
        if (icon == null) return null;
        try {
            android.graphics.drawable.Drawable d = icon.loadDrawable(this);
            if (d == null) return null;
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                return ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            }
            int w = Math.max(1, d.getIntrinsicWidth());
            int h = Math.max(1, d.getIntrinsicHeight());
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return b;
        } catch (Throwable t) {
            Log.w(TAG, "Error converting icon to bitmap: " + t.getMessage());
            return null;
        }
    }

    private void evaluateActiveSession(List<MediaController> controllers) {
        if (!isInjectionEnabled(this)) {
            detachCurrentController();
            LockscreenWallpaperService.notifyPlaybackState(false);
            return;
        }

        List<MediaController> validControllers = new ArrayList<>();
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (c != null && !getPackageName().equals(c.getPackageName())) {
                    validControllers.add(c);
                }
            }
        }

        if (validControllers.isEmpty()) {
            detachCurrentController();
            LockscreenWallpaperService.notifyPlaybackState(false);
            return;
        }

        MediaController target = null;
        for (MediaController c : validControllers) {
            if ("com.music.bitchord".equals(c.getPackageName())) {
                target = c;
                break;
            }
        }
        if (target == null) {
            for (MediaController c : validControllers) {
                PlaybackState state = c.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    target = c;
                    break;
                }
            }
        }
        if (target == null) {
            target = validControllers.get(0);
        }

        boolean controllerChanged = (mActiveController == null || !mActiveController.getSessionToken().equals(target.getSessionToken()));
        if (controllerChanged) {
            detachCurrentController();
            mActiveController = target;
            mActiveController.registerCallback(mControllerCallback, mHandler);
            Log.i(TAG, "Attached to active media session: " + mActiveController.getPackageName());
        }

        // Always check latest metadata and playback state immediately
        handleMetadataChange(mActiveController.getMetadata());
        PlaybackState state = mActiveController.getPlaybackState();
        if (state != null) {
            boolean isPlaying = isPlayingOrBuffering(state);
            LockscreenWallpaperService.notifyPlaybackState(isPlaying);
        }
    }

    private static boolean isPlayingOrBuffering(PlaybackState state) {
        if (state == null) return false;
        int s = state.getState();
        return s == PlaybackState.STATE_PLAYING ||
               s == PlaybackState.STATE_BUFFERING ||
               s == PlaybackState.STATE_SKIPPING_TO_NEXT ||
               s == PlaybackState.STATE_SKIPPING_TO_PREVIOUS ||
               s == PlaybackState.STATE_FAST_FORWARDING ||
               s == PlaybackState.STATE_REWINDING;
    }

    private void detachCurrentController() {
        if (mActiveController != null) {
            mActiveController.unregisterCallback(mControllerCallback);
            mActiveController = null;
        }
    }

    private void handleMetadataChange(MediaMetadata metadata) {
        if (metadata == null) return;
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);

        Bitmap art = extractArtworkFromMetadata(metadata);
        if (art == null) {
            art = findAlbumArtFromActiveNotifications();
        }

        processTrack(title, artist, album, art, metadata);
    }

    private Bitmap extractArtworkFromMetadata(MediaMetadata metadata) {
        if (metadata == null) return null;
        Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null && metadata.getDescription() != null) {
            art = metadata.getDescription().getIconBitmap();
        }
        if (art != null) return art;

        String uriStr = null;
        if (metadata.getDescription() != null && metadata.getDescription().getIconUri() != null) {
            uriStr = metadata.getDescription().getIconUri().toString();
        }
        if (uriStr == null) uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (uriStr == null) uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
        if (uriStr == null) uriStr = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);

        if (uriStr != null && !uriStr.isEmpty()) {
            if (uriStr.startsWith("content://") || uriStr.startsWith("android.resource://")) {
                try (InputStream is = getContentResolver().openInputStream(Uri.parse(uriStr))) {
                    art = BitmapFactory.decodeStream(is);
                    if (art != null) {
                        Log.i(TAG, "Loaded album art from local content URI: " + uriStr);
                        return art;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed reading local URI: " + t.getMessage());
                }
            }
        }
        return null;
    }

    private void fetchArtworkFallbackAsync(String title, String artist, MediaMetadata metadata, String trackKey) {
        new Thread(() -> {
            Bitmap resolved = null;
            String uriStr = null;
            if (metadata != null) {
                if (metadata.getDescription() != null && metadata.getDescription().getIconUri() != null) {
                    uriStr = metadata.getDescription().getIconUri().toString();
                }
                if (uriStr == null) uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                if (uriStr == null) uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
                if (uriStr == null) uriStr = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            }

            if (uriStr != null && (uriStr.startsWith("http://") || uriStr.startsWith("https://"))) {
                try {
                    String hiResUrl = uriStr.replaceAll("=w\\d+-h\\d+", "=w1400-h1400");
                    URL u = new URL(hiResUrl);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    if (conn.getResponseCode() == 200) {
                        try (InputStream is = conn.getInputStream()) {
                            resolved = BitmapFactory.decodeStream(is);
                            if (resolved != null) {
                                Log.i(TAG, "Successfully downloaded crystal-clear 1400x1400 artwork from YouTube CDN: " + resolved.getWidth() + "x" + resolved.getHeight());
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Error downloading art URI: " + t.getMessage());
                }
            }

            if (resolved == null) {
                resolved = findAlbumArtFromActiveNotifications();
            }

            if (resolved == null || resolved.getWidth() < 600) {
                Bitmap hiRes = AlbumArtAestheticFraming.fetchHighResArtFromWeb(title, artist);
                if (hiRes != null) resolved = hiRes;
            }

            if (resolved != null) {
                final Bitmap finalArt = resolved;
                mHandler.post(() -> {
                    if (trackKey.equals(mLastTrackKey)) {
                        mLastExtractedAlbumArt = finalArt;
                        if (isAlbumArtOnNoCanvasEnabled(MediaPlaybackMonitorService.this) && LockscreenWallpaperService.isCurrentVideoNull()) {
                            Log.i(TAG, "Asynchronously loaded album art applied for: " + title);
                            LockscreenWallpaperService.displayAlbumArtBackground(finalArt);
                        }
                    }
                });
            }
        }).start();
    }

    private Bitmap findAlbumArtFromActiveNotifications() {
        try {
            StatusBarNotification[] sbns = getActiveNotifications();
            if (sbns != null) {
                for (StatusBarNotification sbn : sbns) {
                    if (sbn != null && sbn.getPackageName() != null && sbn.getPackageName().contains("bitchord")) {
                        Bitmap notifArt = extractNotificationBitmap(sbn.getNotification());
                        if (notifArt != null) {
                            Log.i(TAG, "Found album art from active BitChord notification!");
                            return notifArt;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void wakeScreenIfOff() {
        if (!isWakeOnTrackChangeEnabled(this)) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                Log.i(TAG, "Phone screen is off during song shift -> Waking screen now");
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                        "LockVideoCover:TrackShiftWake"
                );
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(3000);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to wake screen on track shift: " + t.getMessage());
        }
    }

    private void processTrack(String rawTitle, String rawArtist, String album, Bitmap art) {
        processTrack(rawTitle, rawArtist, album, art, null);
    }

    private void processTrack(String rawTitle, String rawArtist, String album, Bitmap art, MediaMetadata metadata) {
        if (rawTitle == null || rawTitle.trim().isEmpty()) return;

        final String cleanTitle = cleanNoise(rawTitle);
        final String cleanArtist = (rawArtist != null) ? cleanNoise(rawArtist) : "";

        String trackKey = (cleanTitle + "_" + cleanArtist).trim();
        boolean isNewTrack = !trackKey.equals(mLastTrackKey);

        if (isNewTrack) {
            mLastTrackKey = trackKey;
            mLastExtractedAlbumArt = art;
            Log.i(TAG, "Track changed -> Title: " + rawTitle + " (clean: " + cleanTitle + ") | Artist: " + rawArtist);

            // Wake screen if phone is in off state
            wakeScreenIfOff();

            String activePkg = (mActiveController != null) ? mActiveController.getPackageName() : "com.music.bitchord";
            PlaybackQualityOverlay.notifyTrackStarted(cleanTitle, cleanArtist, activePkg);
            PlaybackQualityOverlay.resetLyricAnimation();
            PlaybackQualityOverlay.checkStreamUpgrade(this);

            long duration = 0;
            if (metadata != null) {
                duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            }
            SyncedLyricsManager.prepareLyricsForTrack(cleanTitle, cleanArtist, duration);

            if (isAlbumArtOnNoCanvasEnabled(this) && mLastExtractedAlbumArt != null) {
                LockscreenWallpaperService.displayAlbumArtBackground(mLastExtractedAlbumArt);
            } else {
                LockscreenWallpaperService.clearVideoOnly();
                if (art == null && isAlbumArtOnNoCanvasEnabled(this)) {
                    fetchArtworkFallbackAsync(cleanTitle, cleanArtist, metadata, trackKey);
                }
            }
        } else {
            // Same track: upgrade artwork if a higher resolution version arrives, or fetch if missing
            if (art != null) {
                if (mLastExtractedAlbumArt == null || art.getWidth() > mLastExtractedAlbumArt.getWidth()) {
                    mLastExtractedAlbumArt = art;
                    if (isAlbumArtOnNoCanvasEnabled(this) && LockscreenWallpaperService.isCurrentVideoNull()) {
                        LockscreenWallpaperService.displayAlbumArtBackground(art);
                    }
                }
            } else if (mLastExtractedAlbumArt == null && isAlbumArtOnNoCanvasEnabled(this)) {
                fetchArtworkFallbackAsync(cleanTitle, cleanArtist, metadata, trackKey);
            }
            return;
        }

        broadcastStatus(rawTitle, rawArtist, "Checking local cache...");

        // 1. Check disk cache
        File cached = CanvasCacheManager.getCachedFileForTrack(this, trackKey);
        if (cached != null) {
            Log.d(TAG, "Loaded canvas from cache: " + cached.getAbsolutePath());
            broadcastStatus(rawTitle, rawArtist, "Playing cached canvas");
            LockscreenWallpaperService.updateActiveVideo(cached.getAbsolutePath());
            return;
        }

        broadcastStatus(rawTitle, rawArtist, "Resolving Spotify Canvas...");

        // 2. Query Spotify Canvas
        SpotifyCanvasScraper.resolveCanvas(this, cleanTitle, cleanArtist, album, new SpotifyCanvasScraper.CanvasResultCallback() {
            @Override
            public void onSuccess(String videoUrl, String trackUri) {
                broadcastStatus(rawTitle, rawArtist, "Downloading Spotify Canvas...");
                File downloaded = CanvasCacheManager.downloadAndCache(MediaPlaybackMonitorService.this, videoUrl, trackKey);
                if (downloaded != null) {
                    broadcastStatus(rawTitle, rawArtist, "Playing Spotify Canvas");
                    LockscreenWallpaperService.updateActiveVideo(downloaded.getAbsolutePath());
                } else {
                    tryAppleMusicFallback(cleanTitle, cleanArtist, rawTitle, rawArtist, trackKey);
                }
            }

            @Override
            public void onNoCanvas() {
                tryAppleMusicFallback(cleanTitle, cleanArtist, rawTitle, rawArtist, trackKey);
            }

            @Override
            public void onError(String message) {
                tryAppleMusicFallback(cleanTitle, cleanArtist, rawTitle, rawArtist, trackKey);
            }
        });
    }

    private void tryAppleMusicFallback(String searchTitle, String searchArtist, String displayTitle, String displayArtist, String trackKey) {
        broadcastStatus(displayTitle, displayArtist, "Searching Apple Music motion art...");
        AppleMusicCanvasScraper.resolveCanvas(searchTitle, searchArtist, new AppleMusicCanvasScraper.AppleCanvasCallback() {
            @Override
            public void onSuccess(String videoUrl) {
                broadcastStatus(displayTitle, displayArtist, "Downloading Apple Music artwork...");
                File downloaded = CanvasCacheManager.downloadAndCache(MediaPlaybackMonitorService.this, videoUrl, trackKey);
                if (downloaded != null) {
                    broadcastStatus(displayTitle, displayArtist, "Playing Apple Music Artwork");
                    LockscreenWallpaperService.updateActiveVideo(downloaded.getAbsolutePath());
                } else {
                    onFallbackNoCanvas(displayTitle, displayArtist);
                }
            }

            @Override
            public void onNoCanvas() {
                onFallbackNoCanvas(displayTitle, displayArtist);
            }
        });
    }

    private void onFallbackNoCanvas(String displayTitle, String displayArtist) {
        if (isAlbumArtOnNoCanvasEnabled(this)) {
            Log.i(TAG, "No canvas for track '" + displayTitle + "'. Ensuring aesthetic album art is displayed!");
            broadcastStatus(displayTitle, displayArtist, "Album art background active (no canvas)");
            if (mLastExtractedAlbumArt == null) {
                mLastExtractedAlbumArt = findAlbumArtFromActiveNotifications();
            }
            if (mLastExtractedAlbumArt != null) {
                LockscreenWallpaperService.displayAlbumArtBackground(mLastExtractedAlbumArt);
            }

            // If current art is low resolution (e.g. downscaled notification icon) or null, fetch crystal-clear 1400x1400 art from web
            if (mLastExtractedAlbumArt == null || mLastExtractedAlbumArt.getWidth() < 600) {
                new Thread(() -> {
                    Bitmap hiRes = AlbumArtAestheticFraming.fetchHighResArtFromWeb(displayTitle, displayArtist);
                    if (hiRes != null) {
                        mLastExtractedAlbumArt = hiRes;
                        Log.i(TAG, "High-res 1400x1400 artwork fetched and applied: " + displayTitle);
                        mHandler.post(() -> LockscreenWallpaperService.displayAlbumArtBackground(hiRes));
                    }
                }).start();
            }
        } else {
            Log.i(TAG, "No canvas for track '" + displayTitle + "'. Restoring idle background.");
            broadcastStatus(displayTitle, displayArtist, "No canvas (idle wallpaper active)");
            LockscreenWallpaperService.clearActiveVideo();
        }
    }

    private static String cleanNoise(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)\\((?:from|official|lyrical|video|audio)[^)]*\\)|\\[[^]]*\\]|\\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void broadcastStatus(String title, String artist, String status) {
        sCurrentTitle = title;
        sCurrentArtist = artist;
        sCurrentStatus = status;

        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_ARTIST, artist);
        intent.putExtra(EXTRA_STATUS, status);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sInstance == this) {
            sInstance = null;
        }
    }
}
