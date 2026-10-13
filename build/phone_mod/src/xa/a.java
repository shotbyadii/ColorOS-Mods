package xa;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.android.incallui.Call;
import com.android.incallui.CallList;
import com.android.incallui.ContactInfoCache;
import com.android.incallui.OplusInCallApp;
import com.odialer.videoringtone.ContactVideoResolver;

import java.io.File;

/**
 * Patched replacement for com.android.incallui's LocalVideoUtils (Lxa/a;).
 * Resolves per-contact video ringtones for incoming callers and falls back to
 * default/assigned audio ringtone when no per-contact video is assigned.
 */
public final class a {
    private static final String TAG = "ODialerVideoMod";
    public static final a a = new a();

    private a() {}

    public static final String a(String s) {
        return z.a(s);
    }

    /**
     * Returns the video ringtone URI string for the current incoming caller,
     * or empty string if the caller has no assigned video ringtone.
     */
    public static final String b() {
        try {
            String customUri = resolveCurrentCallerVideoUri();
            if (!TextUtils.isEmpty(customUri)) {
                Log.i(TAG, "Resolved per-contact video ringtone: " + customUri);
                return customUri;
            }
            if (!hasAnyConfiguredPerContactVideos(OplusInCallApp.getAppContext())) {
                return z.b();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in Lxa/a;.b()", t);
        }
        return "";
    }

    public static final Uri c(Uri u) {
        return z.c(u);
    }

    public static final boolean d(String s) {
        if (TextUtils.isEmpty(s)) {
            return false;
        }
        if (s.startsWith("content://") || s.startsWith("file://") || s.startsWith("/")) {
            return true;
        }
        return z.d(s);
    }

    /**
     * Returns true if the current incoming caller should play a local video ringtone.
     */
    public static final boolean e() {
        try {
            String customUri = resolveCurrentCallerVideoUri();
            if (!TextUtils.isEmpty(customUri)) {
                return true;
            }
            if (!hasAnyConfiguredPerContactVideos(OplusInCallApp.getAppContext())) {
                return z.e();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in Lxa/a;.e()", t);
        }
        return false;
    }

    public static final boolean f(Uri u) {
        if (u == null) {
            return false;
        }
        String scheme = u.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            String path = u.getPath();
            return path != null && new File(path).exists();
        }
        return z.f(u);
    }

    private static boolean hasAnyConfiguredPerContactVideos(Context context) {
        try {
            return !ContactVideoResolver.getAllMappingsWithFolderScan(context).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String resolveCurrentCallerVideoUri() {
        Context context = OplusInCallApp.getAppContext();
        if (context == null) {
            return null;
        }
        CallList callList = CallList.getInstance();
        if (callList == null) {
            return null;
        }
        Call call = callList.getIncomingCall();
        if (call == null) {
            call = callList.getFirstCall();
        }
        if (call == null) {
            return null;
        }

        String number = call.getNumber();
        String name = null;
        ContactInfoCache.ContactCacheEntry info = call.getContactInfo();
        if (info != null) {
            if (!TextUtils.isEmpty(info.name)) {
                name = info.name;
            }
            if (TextUtils.isEmpty(number) && !TextUtils.isEmpty(info.number)) {
                number = info.number;
            }
        }

        ContactVideoResolver.ContactMapping matched =
                ContactVideoResolver.resolveForCaller(context, number, name);
        if (matched != null) {
            if (!TextUtils.isEmpty(matched.videoUri)) {
                return matched.videoUri;
            }
            if (!TextUtils.isEmpty(matched.videoPath)) {
                File f = new File(matched.videoPath);
                if (f.exists()) {
                    return Uri.fromFile(f).toString();
                }
            }
        }
        return null;
    }
}
