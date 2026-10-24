package com.odialer.lockvideocover;

import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * Manages custom lockscreen display timeouts:
 * If a Canvas video is playing when the lockscreen turns on,
 * keeps the display awake for exactly 7 seconds and then turns it off.
 */
public class CanvasScreenTimeoutManager {
    private static final String TAG = "CanvasTimeout";
    private static final String PREF_TIMEOUT_ENABLED = "canvas_timeout_enabled";
    private static final String PREF_TIMEOUT_SECONDS = "canvas_timeout_seconds";
    private static final int DEFAULT_TIMEOUT_SECONDS = 7;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock mWakeLock = null;
    private boolean mIsScheduled = false;

    public CanvasScreenTimeoutManager(Context context) {
        mContext = context.getApplicationContext();
    }

    public static boolean isTimeoutEnabled(Context context) {
        return context.getSharedPreferences("lock_video_cover_prefs", Context.MODE_PRIVATE)
                .getBoolean(PREF_TIMEOUT_ENABLED, true);
    }

    public static void setTimeoutEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("lock_video_cover_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_TIMEOUT_ENABLED, enabled).apply();
    }

    public static int getTimeoutSeconds(Context context) {
        return context.getSharedPreferences("lock_video_cover_prefs", Context.MODE_PRIVATE)
                .getInt(PREF_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
    }

    public static void setTimeoutSeconds(Context context, int seconds) {
        context.getSharedPreferences("lock_video_cover_prefs", Context.MODE_PRIVATE)
                .edit().putInt(PREF_TIMEOUT_SECONDS, seconds).apply();
    }

    public synchronized void onLockscreenBecameVisible(boolean isCanvasPlaying) {
        cancelScheduledTimeout();

        if (!isCanvasPlaying || !isTimeoutEnabled(mContext)) {
            Log.d(TAG, "Canvas timeout disabled or media not playing canvas; using system default timeout.");
            return;
        }

        int seconds = getTimeoutSeconds(mContext);
        long timeoutMs = seconds * 1000L;

        Log.d(TAG, "Canvas is playing! Holding lockscreen display on for " + seconds + " seconds...");

        // 1. Hold SCREEN_BRIGHT_WAKE_LOCK to prevent early system dimming/sleep
        try {
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (mWakeLock != null && mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
                mWakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "LockVideoCover:CanvasTimeout"
                );
                mWakeLock.setReferenceCounted(false);
                mWakeLock.acquire(timeoutMs + 500);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not acquire wake lock: " + t.getMessage());
        }

        // 2. Schedule display off at exactly timeoutMs
        mIsScheduled = true;
        mHandler.postDelayed(mSleepRunnable, timeoutMs);
    }

    public synchronized void onLockscreenBecameInvisible() {
        cancelScheduledTimeout();
    }

    private synchronized void cancelScheduledTimeout() {
        if (mIsScheduled) {
            mHandler.removeCallbacks(mSleepRunnable);
            mIsScheduled = false;
        }
        if (mWakeLock != null && mWakeLock.isHeld()) {
            try {
                mWakeLock.release();
            } catch (Throwable ignored) {}
            mWakeLock = null;
        }
    }

    private final Runnable mSleepRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (CanvasScreenTimeoutManager.this) {
                mIsScheduled = false;
                if (mWakeLock != null && mWakeLock.isHeld()) {
                    try { mWakeLock.release(); } catch (Throwable ignored) {}
                    mWakeLock = null;
                }
            }

            // Verify device is still on lockscreen (not unlocked by user into home/app)
            KeyguardManager km = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && !km.isKeyguardLocked()) {
                Log.d(TAG, "Device was unlocked by user; skipping screen off.");
                return;
            }

            Log.d(TAG, "7 seconds elapsed! Turning lockscreen display off now.");
            turnScreenOff(mContext);
        }
    };

    public static void turnScreenOff(Context context) {
        // Attempt 1: Accessibility Service (native API 28+ GLOBAL_ACTION_LOCK_SCREEN)
        if (LockAccessibilityService.lockScreen()) {
            Log.d(TAG, "Screen turned off via AccessibilityService");
            return;
        }

        // Attempt 2: DevicePolicyManager lockNow()
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, LockAdminReceiver.class);
            if (dpm != null && dpm.isAdminActive(admin)) {
                dpm.lockNow();
                Log.d(TAG, "Screen turned off via DevicePolicyManager");
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "DevicePolicyManager lock failed: " + t.getMessage());
        }

        // Attempt 3: Shell input keyevent 26 fallback
        try {
            Runtime.getRuntime().exec(new String[]{"input", "keyevent", "26"});
        } catch (Throwable ignored) {}
    }
}
