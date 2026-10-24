package com.odialer.videoringtone;

import android.net.Uri;
import android.telecom.Call;
import android.telecom.CallScreeningService;
import android.util.Log;

public class VideoRingtoneScreeningService extends CallScreeningService {
    private static final String TAG = "ODialerVideoRingtone";

    @Override
    public void onScreenCall(Call.Details callDetails) {
        try {
            int dir = callDetails.getCallDirection();
            if (dir == Call.Details.DIRECTION_INCOMING) {
                Uri handle = callDetails.getHandle();
                String number = handle != null ? handle.getSchemeSpecificPart() : "";
                Log.i(TAG, "onScreenCall incoming number=" + number);
                ContactVideoResolver.handleIncomingCall(getApplicationContext(), number);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onScreenCall", e);
        } finally {
            CallResponse response = new CallResponse.Builder()
                    .setDisallowCall(false)
                    .setRejectCall(false)
                    .setSilenceCall(false)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                    .build();
            respondToCall(callDetails, response);
        }
    }
}
