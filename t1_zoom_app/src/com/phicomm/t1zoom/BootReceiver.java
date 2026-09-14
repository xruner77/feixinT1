package com.phicomm.t1zoom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "T1BootReceiver";
    private static long sLastTriggerTime = 0;
    private static final long DEBOUNCE_MS = 3000; // 3-second debounce

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = (intent != null) ? intent.getAction() : "null";
        Log.i(TAG, "Received event: " + action);

        // Debounce: ignore duplicate triggers within 3 seconds
        long now = System.currentTimeMillis();
        if (now - sLastTriggerTime < DEBOUNCE_MS) {
            Log.i(TAG, "Debounced (within " + DEBOUNCE_MS + "ms), skipping: " + action);
            return;
        }
        sLastTriggerTime = now;

        // Start ZoomService for any matching trigger
        Log.i(TAG, "Starting ZoomService from trigger: " + action);
        try {
            Intent serviceIntent = new Intent(context, ZoomService.class);
            serviceIntent.putExtra("trigger_action", action);
            context.startService(serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start ZoomService: " + e.getMessage());
        }
    }
}
