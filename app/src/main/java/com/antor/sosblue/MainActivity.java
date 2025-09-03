package com.antor.sosblue;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String TAG = "SOSBlue";
    private static final UUID SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");

    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;

    private EditText inputMessage;
    private Button sendButton;
    private RecyclerView recyclerView;
    private ChatAdapter chatAdapter;
    private List<MessageModel> messageList = new ArrayList<>();

    private AdvertiseCallback advertiseCallback;
    private ScanCallback scanCallback;

    // UserId storage
    private static final String PREFS_NAME = "SOSBluePrefs";
    private static final String KEY_USER_ID_BITS = "userIdBits";
    private static final String KEY_CHAT_HISTORY = "chatHistory";
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray();

    private long userIdBits;
    private String userId;

    // Duplicate filter
    private Set<String> receivedMessages = new HashSet<>();

    private Gson gson = new Gson();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerView = findViewById(R.id.chatRecyclerView);
        inputMessage = findViewById(R.id.inputMessage);
        sendButton = findViewById(R.id.sendButton);

        chatAdapter = new ChatAdapter(messageList, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(chatAdapter);

        initUserId();
        loadChatHistory();

        // show loaded history
        chatAdapter.notifyDataSetChanged();
        if (!messageList.isEmpty()) {
            recyclerView.scrollToPosition(messageList.size() - 1);
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth not available or not enabled", Toast.LENGTH_LONG).show();
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }

        if (!adapter.isMultipleAdvertisementSupported()) {
            Toast.makeText(this, "BLE Advertising not supported on this device", Toast.LENGTH_LONG).show();
            return;
        }

        advertiser = adapter.getBluetoothLeAdvertiser();
        scanner = adapter.getBluetoothLeScanner();

        requestPermissions();

        // ---------- Send Button ----------
        sendButton.setOnClickListener(v -> {
            String msg = inputMessage.getText().toString().trim();
            if (!msg.isEmpty()) {
                String fullMsg = toAscii(userIdBits) + ":" + msg;
                startAdvertising(fullMsg);

                String timestamp = getCurrentTimestamp();
                MessageModel newMsg = new MessageModel(userId, msg, true, timestamp);
                addMessage(newMsg);

                inputMessage.setText("");
            }
        });
    }

    // ---------- Timestamp Utility ----------
    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm a | dd-MM-yyyy", Locale.getDefault());
        return sdf.format(new Date());
    }

    // ---------- UserId Generate ----------
    private void initUserId() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        userIdBits = prefs.getLong(KEY_USER_ID_BITS, -1);
        if (userIdBits == -1) {
            userIdBits = System.currentTimeMillis() & ((1L << 40) - 1);
            prefs.edit().putLong(KEY_USER_ID_BITS, userIdBits).apply();
        }
        userId = getUserIdString(userIdBits);
    }

    private String getUserIdString(long bits40) {
        StringBuilder sb = new StringBuilder();
        long temp = bits40;
        for (int i = 0; i < 8; i++) {
            int index = (int) (temp & 0b11111);
            sb.append(ALPHABET[index]);
            temp >>= 5;
        }
        return sb.reverse().toString();
    }

    private String toAscii(long bits40) {
        byte[] bytes = new byte[5];
        long temp = bits40;
        for (int i = 4; i >= 0; i--) {
            bytes[i] = (byte) (temp & 0xFF);
            temp >>= 8;
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private long fromAscii(String asciiId) {
        if (asciiId.length() != 5) throw new IllegalArgumentException("Invalid ASCII ID");
        byte[] bytes = asciiId.getBytes(StandardCharsets.ISO_8859_1);
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFF);
        }
        return value;
    }

    // ---------- Permissions ----------
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.BLUETOOTH_ADVERTISE,
                                Manifest.permission.BLUETOOTH_SCAN
                        },
                        PERMISSION_REQUEST_CODE);
                return;
            }
        }
        startScanning();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            startScanning();
        }
    }

    // ---------- Advertising ----------
    private void startAdvertising(String message) {
        if (advertiser == null) return;

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceData(new ParcelUuid(SERVICE_UUID), message.getBytes(StandardCharsets.UTF_8))
                .build();

        stopAdvertising();
        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                Log.i(TAG, "Advertising started");
            }

            @Override
            public void onStartFailure(int errorCode) {
                Log.e(TAG, "Advertising failed: " + errorCode);
            }
        };

        advertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private void stopAdvertising() {
        if (advertiser != null && advertiseCallback != null) {
            advertiser.stopAdvertising(advertiseCallback);
        }
    }

    // ---------- Scanning ----------
    private void startScanning() {
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                ScanRecord record = result.getScanRecord();
                if (record != null) {
                    byte[] data = record.getServiceData().get(new ParcelUuid(SERVICE_UUID));
                    if (data != null) {
                        final String received = new String(data, StandardCharsets.UTF_8);

                        if (!receivedMessages.contains(received)) {
                            receivedMessages.add(received);

                            runOnUiThread(() -> {
                                if (received.contains(":")) {
                                    String[] parts = received.split(":", 2);
                                    String asciiId = parts[0];
                                    String message = parts[1];

                                    long bits = fromAscii(asciiId);
                                    String displayId = getUserIdString(bits);

                                    boolean isSelf = displayId.equals(userId);
                                    String timestamp = getCurrentTimestamp();

                                    MessageModel newMsg = new MessageModel(displayId, message, isSelf, timestamp);
                                    addMessage(newMsg);
                                }
                            });
                        }
                    }
                }
            }
        };
        scanner.startScan(scanCallback);
    }

    @Override
    protected void onDestroy() {
        stopAdvertising();
        if (scanner != null && scanCallback != null) {
            scanner.stopScan(scanCallback);
        }
        saveChatHistory();
        super.onDestroy();
    }

    // ---------- Chat Save/Load ----------
    private void addMessage(MessageModel msg) {
        messageList.add(msg);

        if (messageList.size() > 200) {
            messageList = new ArrayList<>(messageList.subList(messageList.size() - 200, messageList.size()));
        }

        chatAdapter.notifyDataSetChanged();
        recyclerView.scrollToPosition(messageList.size() - 1);
        saveChatHistory();
    }

    private void saveChatHistory() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = gson.toJson(messageList);
        prefs.edit().putString(KEY_CHAT_HISTORY, json).apply();
    }

    private void loadChatHistory() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_CHAT_HISTORY, null);
        if (json != null) {
            Type type = new TypeToken<List<MessageModel>>() {}.getType();
            List<MessageModel> loaded = gson.fromJson(json, type);

            if (loaded != null) {
                if (loaded.size() > 200) {
                    loaded = new ArrayList<>(loaded.subList(loaded.size() - 200, loaded.size()));
                }
                messageList.clear();
                messageList.addAll(loaded);
            }
        }
    }
}
