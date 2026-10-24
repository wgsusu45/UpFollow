package com.upfollow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

// Foreground service: automation chalte waqt Android is app ko background me kill/sula na kare.
// Kaam MainActivity karti hai, ye service sirf process ko zinda rakhti hai (notification ke saath).
public class AutomationService extends Service {

    private static final String CHANNEL_ID = "upfollow_automation_channel";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("UpFollow Running")
               .setContentText("Automation background me chal rahi hai")
               .setSmallIcon(android.R.drawable.stat_notify_sync)
               .setOngoing(true);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pf = Build.VERSION.SDK_INT >= 23
                ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.FLAG_UPDATE_CURRENT;
        builder.setContentIntent(PendingIntent.getActivity(this, 0, open, pf));

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, builder.build());
            }
        } catch (Exception e) {
            stopSelf();
        }
        // Kaam activity ke paas hai; system kill kare to khud dobara start na ho
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Automation Service", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Shows live automation stats in status bar");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }
}
