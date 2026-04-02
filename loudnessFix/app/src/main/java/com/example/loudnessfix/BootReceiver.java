package com.example.loudnessfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Запускаем сервис после загрузки системы
            Intent serviceIntent = new Intent(context, LoudnessService.class);
            context.startService(serviceIntent);
        }
    }
}
