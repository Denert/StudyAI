package com.example.loudnessfix;

import android.app.Activity;
import android.content.Intent;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int TARGET_GAIN_MB = 1000; // +10 dB
    private static final int SESSION_ID = 0; // Используем session 0 (глобальный микшер)

    private LoudnessEnhancer enhancer;
    private boolean isActive = false;
    private TextView statusText;
    private Button actionButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createUI();

        // Проверяем, не активен ли эффект из предыдущей сессии
        checkAndReapplyEffect();
    }

    private void createUI() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        // Заголовок
        TextView title = new TextView(this);
        title.setText("Loudness Enhancer Fix PX6 v3");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 30);
        layout.addView(title);

        // Описание
        TextView description = new TextView(this);
        description.setText("Компенсация -10 dB через LoudnessEnhancer\n"
                          + "Версия 3 - принудительная установка gain\n\n"
                          + "Использует session 0 (глобальный микшер)");
        description.setTextSize(14);
        description.setPadding(0, 0, 0, 30);
        layout.addView(description);

        // Статус
        statusText = new TextView(this);
        statusText.setText("Статус: Не активен");
        statusText.setTextSize(16);
        statusText.setPadding(0, 0, 0, 30);
        layout.addView(statusText);

        // Кнопка включения/выключения
        actionButton = new Button(this);
        actionButton.setText("Активировать +10 dB");
        actionButton.setOnClickListener(v -> {
            if (isActive) {
                deactivateEnhancer();
            } else {
                activateEnhancer();
            }
        });
        layout.addView(actionButton);

        // Кнопка запуска сервиса (для автостарта)
        Button serviceButton = new Button(this);
        serviceButton.setText("Запустить фоновый сервис");
        serviceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startLoudnessService();
                Toast.makeText(MainActivity.this,
                    "Фоновый сервис запущен",
                    Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(serviceButton);

        setContentView(layout);
    }

    private void activateEnhancer() {
        try {
            // Создаем эффект с session 0 (глобальный)
            enhancer = new LoudnessEnhancer(SESSION_ID);

            // ВАЖНО: сначала включаем эффект
            enhancer.setEnabled(true);

            // Задержка для инициализации
            Thread.sleep(100);

            // Устанавливаем усиление
            enhancer.setTargetGain(TARGET_GAIN_MB);

            // Проверяем, что усиление установилось
            float currentGain = enhancer.getTargetGain();

            isActive = true;
            statusText.setText("Статус: АКТИВЕН (+10 dB)\nGain: " + currentGain + " mB");
            actionButton.setText("Деактивировать");

            String message = "Loudness Enhancer активирован\n"
                           + "Усиление: +10 dB (" + currentGain + " mB)\n"
                           + "Session: " + SESSION_ID;

            Toast.makeText(this, message, Toast.LENGTH_LONG).show();

            // Логируем успех
            android.util.Log.d("LoudnessFix", "Effect activated, gain: " + currentGain);

        } catch (Exception e) {
            String error = "Ошибка: " + e.getMessage();
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            android.util.Log.e("LoudnessFix", error, e);
        }
    }

    private void deactivateEnhancer() {
        if (enhancer != null) {
            enhancer.setEnabled(false);
            enhancer.release();
            enhancer = null;
        }

        isActive = false;
        statusText.setText("Статус: Не активен");
        actionButton.setText("Активировать +10 dB");

        Toast.makeText(this, "Loudness Enhancer деактивирован", Toast.LENGTH_SHORT).show();
        android.util.Log.d("LoudnessFix", "Effect deactivated");
    }

    private void checkAndReapplyEffect() {
        // Проверяем, был ли эффект активен (можно сохранять в SharedPreferences)
        // и пересоздаем его при необходимости
    }

    private void startLoudnessService() {
        Intent intent = new Intent(this, LoudnessService.class);
        startService(intent);
    }

    @Override
    protected void onDestroy() {
        deactivateEnhancer();
        super.onDestroy();
    }
}
