package com.odialer.videoringtone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

public class PhoneStateReceiver extends BroadcastReceiver {
    private static final String TAG = "ODialerVideoRingtone";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            Log.d(TAG, "PhoneStateReceiver state=" + state + " number=" + incomingNumber);
            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                if (!TextUtils.isEmpty(incomingNumber)) {
                    ContactVideoResolver.handleIncomingCall(context.getApplicationContext(), incomingNumber);
                }
            } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                ContactVideoResolver.restoreDefaultAudioRingtone(context.getApplicationContext());
            }
        }
    }
}
