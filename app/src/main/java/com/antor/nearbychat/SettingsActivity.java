package com.antor.nearbychat;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.os.Handler;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SettingsActivity extends Activity {

    private static final String TAG = "SettingsActivity";
    private static final int MIN_MS_VALUE = 100;

    private SharedPreferences prefs;
    private Map<String, EditText> settingInputs = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("NearbyChatSettings", MODE_PRIVATE);
        setupUI();
        loadSettings();
    }

    private void setupUI() {
        settingInputs.put("MAX_PAYLOAD_SIZE", findViewById(R.id.editMaxPayloadSize));
        EditText serviceUuidInput = findViewById(R.id.editServiceUuid);

        settingInputs.put("ADVERTISING_DURATION_MS", findViewById(R.id.editAdvertisingDuration));
        settingInputs.put("DELAY_BETWEEN_CHUNKS_MS", findViewById(R.id.editDelayBetweenChunks));
        settingInputs.put("CHUNK_TIMEOUT_MS", findViewById(R.id.editChunkTimeout));
        settingInputs.put("CHUNK_CLEANUP_INTERVAL_MS", findViewById(R.id.editChunkCleanupInterval));
        settingInputs.put("MAX_RECENT_MESSAGES", findViewById(R.id.editMaxRecentMessages));
        settingInputs.put("MAX_RECENT_CHUNKS", findViewById(R.id.editMaxRecentChunks));
        settingInputs.put("MAX_MESSAGE_SAVED", findViewById(R.id.editMaxMessagesSaved));


        Button saveButton = findViewById(R.id.saveAndRestartButton);
        saveButton.setOnClickListener(v -> saveSettingsAndRestart());

        Button resetButton = findViewById(R.id.resetDefaultButton);
        resetButton.setOnClickListener(v -> resetToDefaults());

        ImageView backIcon = findViewById(R.id.backIcon);
        backIcon.setOnClickListener(v -> finish());
    }

    private void restartService() {
        Intent serviceIntent = new Intent(this, BleMessagingService.class);

        // First stop the service
        stopService(serviceIntent);

        // Wait a moment then start again
        new Handler().postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "Service restarted with new settings", Toast.LENGTH_SHORT).show();
        }, 1000);
    }

    private void loadSettings() {
        // Regular settings
        for (Map.Entry<String, EditText> entry : settingInputs.entrySet()) {
            String key = entry.getKey();
            EditText editText = entry.getValue();
            int value = prefs.getInt(key, -1);
            if (value != -1) {
                editText.setText(String.valueOf(value));
            }
        }

        // UUID setting আলাদা
        EditText serviceUuidInput = findViewById(R.id.editServiceUuid);
        String serviceUuidValue = prefs.getString("SERVICE_UUID", "");
        if (!serviceUuidValue.isEmpty()) {
            serviceUuidInput.setText(serviceUuidValue);
        }
    }

    private void saveSettingsAndRestart() {
        SharedPreferences.Editor editor = prefs.edit();
        boolean hasError = false;

        // UUID validation আগে করুন
        EditText serviceUuidInput = findViewById(R.id.editServiceUuid);
        String uuidText = serviceUuidInput.getText().toString().trim();
        if (!uuidText.isEmpty()) {
            try {
                UUID.fromString(uuidText); // Validation
                editor.putString("SERVICE_UUID", uuidText);
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, "Invalid UUID format", Toast.LENGTH_SHORT).show();
                hasError = true;
            }
        }

        // বাকি settings
        for (Map.Entry<String, EditText> entry : settingInputs.entrySet()) {
            String key = entry.getKey();
            EditText editText = entry.getValue();
            String text = editText.getText().toString();

            if (!text.isEmpty()) {
                try {
                    int value = Integer.parseInt(text);

                    // MAX_PAYLOAD_SIZE validation
                    if (key.equals("MAX_PAYLOAD_SIZE")) {
                        if (value < 20) {
                            Toast.makeText(this, "Max Payload Size must be at least 20", Toast.LENGTH_SHORT).show();
                            hasError = true;
                            break;
                        }
                    }

                    if (key.contains("MS") && value < MIN_MS_VALUE) {
                        Toast.makeText(this, "Time values must be at least " + MIN_MS_VALUE + "ms", Toast.LENGTH_SHORT).show();
                        hasError = true;
                        break;
                    }
                    editor.putInt(key, value);
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Invalid number for " + key, Toast.LENGTH_SHORT).show();
                    hasError = true;
                    break;
                }
            } else {
                editor.remove(key);
            }
        }

        if (!hasError) {
            editor.apply();
            Toast.makeText(this, "Settings saved! Restarting service...", Toast.LENGTH_SHORT).show();
            restartService();
            finish();
        }
    }

    private void resetToDefaults() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.apply();
        Toast.makeText(this, "Settings reset to defaults. Restarting service...", Toast.LENGTH_SHORT).show();
        loadSettings();
        // Stop and restart the service
        Intent serviceIntent = new Intent(this, BleMessagingService.class);
        stopService(serviceIntent);
        startService(serviceIntent);
    }
}