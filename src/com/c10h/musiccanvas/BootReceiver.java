package com.c10h.musiccanvas;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "C10H_MusicBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot completed received, starting MediaMonitorService");
            try {
                Intent serviceIntent = new Intent(context, MediaMonitorService.class);
                context.startService(serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start service on boot: " + e.getMessage());
            }
        }
    }
}
