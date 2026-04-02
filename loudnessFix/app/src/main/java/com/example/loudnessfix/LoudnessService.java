package com.example.loudnessfix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class LoudnessService extends Service {
    private static final String CHANNEL_ID = "loudness_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int TARGET_GAIN_MB = 1000;

    private LoudnessEnhancer enhancer;
    private AudioTrack silentTrack;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }
        activateEnhancer();
    }

    private void activateEnhancer() {
        try {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

            AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();

            int bufferSize = AudioTrack.getMinBufferSize(44100,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT) * 2;

            byte[] silence = new byte[bufferSize];

            silentTrack = new AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build();

            silentTrack.write(silence, 0, silence.length);
            silentTrack.write(silence, 0, silence.length);

            int sessionId = silentTrack.getAudioSessionId();

            enhancer = new LoudnessEnhancer(sessionId);
            enhancer.setEnabled(true);
            enhancer.setTargetGain(TARGET_GAIN_MB);

            silentTrack.play();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // Остается активным после перезагрузки
    }

    @Override
    public void onDestroy() {
        if (silentTrack != null) {
            silentTrack.stop();
            silentTrack.release();
        }
        if (enhancer != null) {
            enhancer.setEnabled(false);
            enhancer.release();
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Loudness Fix Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Компенсирует ослабление громкости +10 dB");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Loudness Fix")
            .setContentText("Активен: +10 dB усиления")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
