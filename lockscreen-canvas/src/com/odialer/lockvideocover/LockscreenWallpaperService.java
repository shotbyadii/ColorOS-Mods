package com.odialer.lockvideocover;

import android.graphics.Bitmap;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

/**
 * 60FPS Hardware Lockscreen Video Wallpaper Engine (OpenGL ES 2.0).
 * Plays Spotify/Apple Music Canvas video loops when music is playing.
 * Plays idle video (Oppo Find X9 live wallpaper) or custom photo when paused/idle.
 * Can display full-screen album art background when a song has no canvas video.
 */
public class LockscreenWallpaperService extends WallpaperService {
    private static final String TAG = "LockWallpaperService";

    public interface VideoUpdateListener {
        void onNewVideoAvailable(String videoPath);
        void onAlbumArtAvailable(Bitmap albumArt);
        void onPlaybackPaused();
        void onPlaybackResumed();
        void onWallpaperChanged();
        void onOverlayUpdateNeeded();
    }

    private static volatile VideoUpdateListener sActiveEngine = null;
    private static volatile String sCurrentVideoPath = null;
    private static volatile Bitmap sCurrentAlbumArt = null;
    private static volatile boolean sIsMediaPlaying = false;
    private static final android.os.Handler sMainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable sPendingPauseRunnable = null;

    public static boolean isCurrentVideoNull() {
        return sCurrentVideoPath == null;
    }

    public static void requestOverlayRedraw() {
        if (sActiveEngine != null) {
            sActiveEngine.onOverlayUpdateNeeded();
        }
    }

    public static void notifyWallpaperChanged() {
        if (sActiveEngine != null) {
            sActiveEngine.onWallpaperChanged();
        }
    }

    public static void updateActiveVideo(String videoPath) {
        if (sPendingPauseRunnable != null) {
            sMainHandler.removeCallbacks(sPendingPauseRunnable);
            sPendingPauseRunnable = null;
        }
        if (videoPath != null && videoPath.equals(sCurrentVideoPath)) {
            return;
        }
        Log.i(TAG, "Updating active video: " + videoPath);
        sCurrentVideoPath = videoPath;
        // Preserve sCurrentAlbumArt for ambient blur backdrop behind video
        sIsMediaPlaying = true;
        if (sActiveEngine != null) {
            sActiveEngine.onNewVideoAvailable(videoPath);
        }
    }

    public static void displayAlbumArtBackground(Bitmap albumArt) {
        if (sPendingPauseRunnable != null) {
            sMainHandler.removeCallbacks(sPendingPauseRunnable);
            sPendingPauseRunnable = null;
        }
        Log.i(TAG, "Displaying static album art on lockscreen background");
        sCurrentVideoPath = null;
        sCurrentAlbumArt = albumArt;
        sIsMediaPlaying = true;
        if (sActiveEngine != null) {
            sActiveEngine.onAlbumArtAvailable(albumArt);
        }
    }

    public static void clearVideoOnly() {
        sCurrentVideoPath = null;
        if (sActiveEngine != null) {
            sActiveEngine.onNewVideoAvailable(null);
        }
    }

    public static void clearActiveVideo() {
        Log.d(TAG, "Clearing active canvas. Restoring idle wallpaper.");
        sCurrentVideoPath = null;
        sCurrentAlbumArt = null;
        sIsMediaPlaying = false;
        if (sActiveEngine != null) {
            sActiveEngine.onPlaybackPaused();
        }
    }

    public static void notifyPlaybackState(boolean isPlaying) {
        if (isPlaying) {
            if (sPendingPauseRunnable != null) {
                sMainHandler.removeCallbacks(sPendingPauseRunnable);
                sPendingPauseRunnable = null;
            }
            boolean wasPlaying = sIsMediaPlaying;
            sIsMediaPlaying = true;
            if (!wasPlaying && sActiveEngine != null) {
                sActiveEngine.onPlaybackResumed();
            }
        } else {
            // Debounce pause by 350ms to prevent track skipping or buffering gaps from glitching into idle video
            if (sPendingPauseRunnable == null) {
                sPendingPauseRunnable = () -> {
                    sPendingPauseRunnable = null;
                    sIsMediaPlaying = false;
                    Log.d(TAG, "Debounced pause triggered");
                    if (sActiveEngine != null) {
                        sActiveEngine.onPlaybackPaused();
                    }
                };
                sMainHandler.postDelayed(sPendingPauseRunnable, 350);
            }
        }
    }

    @Override
    public Engine onCreateEngine() {
        return new CanvasWallpaperEngine();
    }

    private class CanvasWallpaperEngine extends Engine implements VideoUpdateListener {
        private GLVideoWallpaperRenderer mRenderer;
        private CanvasScreenTimeoutManager mTimeoutManager;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            sActiveEngine = this;
            mRenderer = new GLVideoWallpaperRenderer(getApplicationContext());
            mTimeoutManager = new CanvasScreenTimeoutManager(getApplicationContext());
            Log.d(TAG, "Wallpaper Engine created with OpenGL ES 2.0");
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (sActiveEngine == this) {
                sActiveEngine = null;
            }
            if (mTimeoutManager != null) {
                mTimeoutManager.onLockscreenBecameInvisible();
                mTimeoutManager = null;
            }
            if (mRenderer != null) {
                mRenderer.onDestroy();
                mRenderer = null;
            }
            Log.d(TAG, "Wallpaper Engine destroyed");
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            Log.d(TAG, "Surface created");
            if (mRenderer != null) {
                mRenderer.onSurfaceCreated(holder);
                if (sIsMediaPlaying) {
                    if (sCurrentVideoPath != null) {
                        mRenderer.playActiveCanvasVideo(sCurrentVideoPath);
                    } else if (sCurrentAlbumArt != null) {
                        mRenderer.displayAlbumArt(sCurrentAlbumArt);
                    }
                }
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            Log.d(TAG, "Surface changed: " + width + "x" + height);
            if (mRenderer != null) {
                mRenderer.onSurfaceChanged(width, height);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            Log.d(TAG, "Surface destroyed");
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            Log.d(TAG, "Visibility changed: " + visible);
            if (mRenderer != null) {
                mRenderer.onVisibilityChanged(visible);
            }
            if (mTimeoutManager != null) {
                if (visible) {
                    boolean isCanvasPlaying = sIsMediaPlaying && (sCurrentVideoPath != null);
                    mTimeoutManager.onLockscreenBecameVisible(isCanvasPlaying);
                } else {
                    mTimeoutManager.onLockscreenBecameInvisible();
                }
            }
        }

        @Override
        public void onNewVideoAvailable(String videoPath) {
            Log.i(TAG, "New active video requested: " + videoPath);
            if (mRenderer != null) {
                mRenderer.playActiveCanvasVideo(videoPath);
            }
        }

        @Override
        public void onAlbumArtAvailable(Bitmap albumArt) {
            if (mRenderer != null) {
                mRenderer.displayAlbumArt(albumArt);
            }
        }

        @Override
        public void onPlaybackPaused() {
            Log.d(TAG, "Playback paused -> Restoring idle live wallpaper");
            if (mRenderer != null) {
                mRenderer.pausePlayback();
            }
        }

        @Override
        public void onPlaybackResumed() {
            Log.d(TAG, "Playback resumed");
            if (mRenderer != null) {
                mRenderer.resumePlayback();
            }
        }

        @Override
        public void onWallpaperChanged() {
            Log.d(TAG, "Wallpaper changed -> Reloading idle configuration");
            if (mRenderer != null) {
                mRenderer.reloadIdleConfiguration();
            }
        }

        @Override
        public void onOverlayUpdateNeeded() {
            if (mRenderer != null) {
                mRenderer.requestOverlayRedraw();
            }
        }
    }
}
