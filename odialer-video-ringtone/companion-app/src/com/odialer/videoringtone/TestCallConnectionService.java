package com.odialer.videoringtone;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;

/**
 * Telecom ConnectionService that triggers a real incoming call on the device
 * so the stock Oppo Dialer (com.android.incallui) launches its full incoming call
 * UI and plays the per-contact video ringtone without needing a second phone to call.
 */
public class TestCallConnectionService extends ConnectionService {
    private static final String TAG = "ODialerTestCall";
    public static final String ACCOUNT_ID = "TestCallAccount";
    public static final String EXTRA_CALLER_NUMBER = "extra_caller_number";
    public static final String EXTRA_CALLER_NAME = "extra_caller_name";

    public static PhoneAccountHandle getHandle(Context context) {
        return new PhoneAccountHandle(
                new ComponentName(context, TestCallConnectionService.class),
                ACCOUNT_ID);
    }

    public static PhoneAccount registerOrGetAccount(Context context) {
        TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) return null;
        PhoneAccountHandle handle = getHandle(context);
        try {
            PhoneAccount existing = tm.getPhoneAccount(handle);
            if (existing != null) {
                return existing;
            }
            PhoneAccount account = PhoneAccount.builder(handle, "ODialer Video Ringtone Simulator")
                    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                    .build();
            tm.registerPhoneAccount(account);
            return tm.getPhoneAccount(handle);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register PhoneAccount", e);
            return null;
        }
    }

    public static boolean isAccountEnabled(Context context) {
        try {
            PhoneAccount acc = registerOrGetAccount(context);
            return acc != null && acc.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Triggers a real incoming call through Android Telecom after a short delay
     * (so the user can even lock their screen if they want, or watch it pop up live).
     */
    public static boolean triggerRealIncomingCall(Context context, String phoneNumber, String displayName, int delayMs) {
        final Context appCtx = context.getApplicationContext();
        final String cleanNum = !TextUtils.isEmpty(phoneNumber) ? phoneNumber.trim() : "9876543210";
        final String cleanName = !TextUtils.isEmpty(displayName) ? displayName.trim() : "";

        registerOrGetAccount(appCtx);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                // Pre-apply the per-contact ringtone switch immediately before notifying Telecom
                ContactVideoResolver.handleIncomingCall(appCtx, cleanNum);

                TelecomManager tm = (TelecomManager) appCtx.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    Bundle extras = new Bundle();
                    Uri uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, cleanNum, null);
                    extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, uri);
                    extras.putString(EXTRA_CALLER_NUMBER, cleanNum);
                    extras.putString(EXTRA_CALLER_NAME, cleanName);
                    tm.addNewIncomingCall(getHandle(appCtx), extras);
                    Log.i(TAG, "Successfully invoked addNewIncomingCall for " + cleanNum);
                }
            } catch (Exception e) {
                Log.e(TAG, "addNewIncomingCall failed: " + e.getMessage(), e);
            }
        }, Math.max(0, delayMs));
        return true;
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        Bundle reqExtras = request != null ? request.getExtras() : null;
        String number = "9876543210";
        String name = "";
        if (reqExtras != null) {
            number = reqExtras.getString(EXTRA_CALLER_NUMBER, number);
            name = reqExtras.getString(EXTRA_CALLER_NAME, "");
        }
        if (request != null && request.getAddress() != null && !TextUtils.isEmpty(request.getAddress().getSchemeSpecificPart())) {
            number = request.getAddress().getSchemeSpecificPart();
        }

        // Ensure ringtone URI is switched for this caller
        ContactVideoResolver.handleIncomingCall(getApplicationContext(), number);

        final Context appCtx = getApplicationContext();
        final Handler handler = new Handler(Looper.getMainLooper());

        Connection conn = new Connection() {
            private final Runnable autoHangup = () -> {
                setDisconnected(new DisconnectCause(DisconnectCause.MISSED));
                destroy();
                ContactVideoResolver.restoreDefaultAudioRingtone(appCtx);
            };

            {
                handler.postDelayed(autoHangup, 30000);
            }

            private void cleanupAndRestore(int cause) {
                handler.removeCallbacks(autoHangup);
                setDisconnected(new DisconnectCause(cause));
                destroy();
                handler.postDelayed(() -> ContactVideoResolver.restoreDefaultAudioRingtone(appCtx), 600);
            }

            @Override
            public void onAnswer() {
                onAnswer(0);
            }

            @Override
            public void onAnswer(int videoState) {
                handler.removeCallbacks(autoHangup);
                setActive();
                // Auto end simulated call 4 seconds after answering, or user can press hang up
                handler.postDelayed(() -> cleanupAndRestore(DisconnectCause.LOCAL), 4000);
            }

            @Override
            public void onReject() {
                cleanupAndRestore(DisconnectCause.REJECTED);
            }

            @Override
            public void onReject(String replyMessage) {
                cleanupAndRestore(DisconnectCause.REJECTED);
            }

            @Override
            public void onDisconnect() {
                cleanupAndRestore(DisconnectCause.LOCAL);
            }

            @Override
            public void onAbort() {
                cleanupAndRestore(DisconnectCause.CANCELED);
            }
        };

        Uri address = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);
        conn.setAddress(address, TelecomManager.PRESENTATION_ALLOWED);
        if (!TextUtils.isEmpty(name)) {
            conn.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED);
        }
        conn.setConnectionCapabilities(Connection.CAPABILITY_SUPPORT_HOLD | Connection.CAPABILITY_MUTE);
        conn.setAudioModeIsVoip(false);
        conn.setInitializing();
        conn.setRinging();
        return conn;
    }
}
