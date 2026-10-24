package com.odialer.lockvideocover;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility service for turning the screen off on lockscreen using native
 * GLOBAL_ACTION_LOCK_SCREEN (API 28+).
 */
public class LockAccessibilityService extends AccessibilityService {
    private static final String TAG = "LockA11yService";
    private static volatile LockAccessibilityService sInstance = null;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.d(TAG, "LockAccessibilityService connected successfully!");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sInstance == this) {
            sInstance = null;
        }
    }

    public static boolean lockScreen() {
        if (sInstance != null) {
            Log.d(TAG, "Executing GLOBAL_ACTION_LOCK_SCREEN via AccessibilityService");
            return sInstance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }
}
