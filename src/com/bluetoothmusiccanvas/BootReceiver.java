package com.bluetoothmusiccanvas;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BTMusicBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot completed received, requesting MediaMonitorService rebind");
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    NotificationListenerService.requestRebind(
                            new ComponentName(context, MediaMonitorService.class));
                } else {
                    Intent serviceIntent = new Intent(context, MediaMonitorService.class);
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to rebind/start service on boot: " + e.getMessage());
            }
        }
    }
}
