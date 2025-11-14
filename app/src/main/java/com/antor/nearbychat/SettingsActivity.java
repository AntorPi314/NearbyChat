package com.antor.nearbychat;

import static androidx.core.util.TypedValueCompat.dpToPx;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.os.Handler;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.Toast;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SettingsActivity extends BaseActivity {

    private static final String TAG = "SettingsActivity";
    private static final int MIN_MS_VALUE = 100;
    private Switch switchAutoImageThumbnails;

    private EditText uuidEditablePart;
    private SharedPreferences prefs;
    private Map<String, EditText> settingInputs = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences("NearbyChatSettings", MODE_PRIVATE);
        setupUI();
        loadSettings();
    }

    private void setupUI() {
        final View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int heightDiff = rootView.getRootView().getHeight() - rootView.getHeight();
            boolean keyboardVisible = heightDiff > dpToPx(200, getResources().getDisplayMetrics());
            if (!keyboardVisible) {
                setTheme(R.style.AppThemeSettings);
            }
        });
        settingInputs.put("MAX_PAYLOAD_SIZE", findViewById(R.id.editMaxPayloadSize));
        uuidEditablePart = findViewById(R.id.editUuidPart1b);

        settingInputs.put("ADVERTISING_DURATION_MS", findViewById(R.id.editAdvertisingDuration));
        settingInputs.put("DELAY_BETWEEN_CHUNKS_MS", findViewById(R.id.editDelayBetweenChunks));
        settingInputs.put("CHUNK_TIMEOUT_MS", findViewById(R.id.editChunkTimeout));
        settingInputs.put("CHUNK_CLEANUP_INTERVAL_MS", findViewById(R.id.editChunkCleanupInterval));
        settingInputs.put("MAX_RECENT_MESSAGES", findViewById(R.id.editMaxRecentMessages));
        settingInputs.put("MAX_RECENT_CHUNKS", findViewById(R.id.editMaxRecentChunks));
        settingInputs.put("MAX_MESSAGE_SAVED", findViewById(R.id.editMaxMessagesSaved));

        settingInputs.put("BROADCAST_ROUNDS", findViewById(R.id.editBroadcastRounds));
        settingInputs.put("SCAN_MODE", findViewById(R.id.editScanMode));
        settingInputs.put("ADVERTISE_MODE", findViewById(R.id.editAdvertiseMode));
        settingInputs.put("TX_POWER_LEVEL", findViewById(R.id.editTxPowerLevel));
        switchAutoImageThumbnails = findViewById(R.id.switchAutoImageThumbnails);



        Button saveButton = findViewById(R.id.saveAndRestartButton);
        saveButton.setOnClickListener(v -> saveSettingsAndRestart());

        Button resetButton = findViewById(R.id.resetDefaultButton);
        resetButton.setOnClickListener(v -> resetToDefaults());

        ImageView backIcon = findViewById(R.id.backIcon);
        backIcon.setOnClickListener(v -> finish());

        ImageView generateUuidIcon = findViewById(R.id.generateUuidIcon);
        generateUuidIcon.setOnClickListener(v -> generateNewUUID());
    }

    private void generateNewUUID() {
        String newUUID = UUID.randomUUID().toString();
        String[] parts = newUUID.split("-");
        if (parts.length == 5) {
            String firstPart = parts[0];
            if (firstPart.length() >= 4) {
                uuidEditablePart.setText(firstPart.substring(0, 4));
            } else {
                uuidEditablePart.setText(firstPart);
            }
        }
    }

    private void restartService() {
        Intent serviceIntent = new Intent(this, BleMessagingService.class);
        stopService(serviceIntent);

        new Handler().postDelayed(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                Toast.makeText(this, "Service restarted with new settings", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Failed to restart service", Toast.LENGTH_SHORT).show();
            }
        }, 2000);
    }

    private void loadSettings() {
        Map<String, Integer> defaultValues = new HashMap<>();
        defaultValues.put("MAX_PAYLOAD_SIZE", 27);
        defaultValues.put("ADVERTISING_DURATION_MS", 900);
        defaultValues.put("DELAY_BETWEEN_CHUNKS_MS", 1000);
        defaultValues.put("CHUNK_TIMEOUT_MS", 300000);
        defaultValues.put("CHUNK_CLEANUP_INTERVAL_MS", 60000);
        defaultValues.put("MAX_RECENT_MESSAGES", 1000);
        defaultValues.put("MAX_RECENT_CHUNKS", 2000);
        defaultValues.put("MAX_MESSAGE_SAVED", 2000);

        defaultValues.put("BROADCAST_ROUNDS", 3);
        defaultValues.put("SCAN_MODE", 2);
        defaultValues.put("ADVERTISE_MODE", 2);
        defaultValues.put("TX_POWER_LEVEL", 3);

        for (Map.Entry<String, EditText> entry : settingInputs.entrySet()) {
            String key = entry.getKey();
            EditText editText = entry.getValue();
            int defaultValue = defaultValues.getOrDefault(key, 0);
            int value = prefs.getInt(key, defaultValue);
            editText.setText(String.valueOf(value));
        }
        String serviceUuidValue = prefs.getString("SERVICE_UUID", "0000aaaa-0000-1000-8000-00805f9b34fb");
        String[] parts = serviceUuidValue.split("-");
        if (parts.length == 5 && parts[0].length() >= 8) {
            uuidEditablePart.setText(parts[0].substring(4));
        } else {
            uuidEditablePart.setText("aaaa");
        }
        boolean autoThumbnails = prefs.getBoolean("AUTO_IMAGE_THUMBNAILS", true);
        switchAutoImageThumbnails.setChecked(autoThumbnails);
    }

    private void saveSettingsAndRestart() {
        SharedPreferences.Editor editor = prefs.edit();
        boolean hasError = false;
        String editableText = uuidEditablePart.getText().toString().trim();
        if (editableText.length() != 4) {
            Toast.makeText(this, "UUID part must be exactly 4 characters", Toast.LENGTH_SHORT).show();
            hasError = true;
        } else {
            String uuidText = "0000" + editableText + "-0000-1000-8000-00805f9b34fb";
            try {
                UUID.fromString(uuidText);
                editor.putString("SERVICE_UUID", uuidText);
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, "Invalid UUID format", Toast.LENGTH_SHORT).show();
                hasError = true;
            }
        }
        for (Map.Entry<String, EditText> entry : settingInputs.entrySet()) {
            String key = entry.getKey();
            EditText editText = entry.getValue();
            String text = editText.getText().toString().trim();
            if (!text.isEmpty()) {
                try {
                    int value = Integer.parseInt(text);

                    if (key.equals("MAX_PAYLOAD_SIZE")) {
                        if (value < 20) {
                            Toast.makeText(this, "Max Payload Size must be at least 20", Toast.LENGTH_SHORT).show();
                            hasError = true;
                            break;
                        }
                    }
                    if (key.equals("BROADCAST_ROUNDS")) {
                        if (value < 1) {
                            Toast.makeText(this, "Broadcast Rounds must be at least 1", Toast.LENGTH_SHORT).show();
                            hasError = true;
                            break;
                        }
                    }

                    if (key.equals("SCAN_MODE") || key.equals("ADVERTISE_MODE")) {
                        if (value < 0 || value > 2) {
                            Toast.makeText(this, key + " must be between 0-2", Toast.LENGTH_SHORT).show();
                            hasError = true;
                            break;
                        }
                    }

                    if (key.equals("TX_POWER_LEVEL")) {
                        if (value < 0 || value > 3) {
                            Toast.makeText(this, "TX Power Level must be between 0-3", Toast.LENGTH_SHORT).show();
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
            }
        }
        editor.putBoolean("AUTO_IMAGE_THUMBNAILS", switchAutoImageThumbnails.isChecked());
        if (!hasError) {
            editor.apply();
            Toast.makeText(this, "Settings saved! Restarting service...", Toast.LENGTH_SHORT).show();
            restartService();
            new Handler().postDelayed(() -> finish(), 1000);
        }
    }

    private void resetToDefaults() {
        uuidEditablePart.setText("aaaa");
        settingInputs.get("MAX_PAYLOAD_SIZE").setText("27");
        settingInputs.get("ADVERTISING_DURATION_MS").setText("900");
        settingInputs.get("DELAY_BETWEEN_CHUNKS_MS").setText("1000");
        settingInputs.get("CHUNK_TIMEOUT_MS").setText("300000");
        settingInputs.get("CHUNK_CLEANUP_INTERVAL_MS").setText("60000");
        settingInputs.get("MAX_RECENT_MESSAGES").setText("1000");
        settingInputs.get("MAX_RECENT_CHUNKS").setText("2000");
        settingInputs.get("MAX_MESSAGE_SAVED").setText("2000");

        settingInputs.get("BROADCAST_ROUNDS").setText("3");
        settingInputs.get("SCAN_MODE").setText("2");
        settingInputs.get("ADVERTISE_MODE").setText("2");
        settingInputs.get("TX_POWER_LEVEL").setText("3");

        switchAutoImageThumbnails.setChecked(true);

        Toast.makeText(this, "Defaults restored. Saving and restarting...", Toast.LENGTH_SHORT).show();
        new Handler().postDelayed(this::saveSettingsAndRestart, 500);
    }
}