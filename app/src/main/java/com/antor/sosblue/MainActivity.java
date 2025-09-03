package com.antor.sosblue;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

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
    private RecyclerView recyclerView;
    private ChatAdapter chatAdapter;
    private List<MessageModel> messageList = new ArrayList<>();

    private AdvertiseCallback advertiseCallback;
    private ScanCallback scanCallback;

    private static final String PREFS_NAME = "SOSBluePrefs";
    private static final String KEY_USER_ID_BITS = "userIdBits";
    private static final String KEY_CHAT_HISTORY = "chatHistory";
    private static final String KEY_NAME_MAP = "nameMap"; // For ID -> Name mapping
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray();

    private long userIdBits;
    private String userId;

    private Set<String> receivedMessages = new HashSet<>();
    private Gson gson = new Gson();
    private Map<String, String> nameMap = new HashMap<>(); // ID -> custom name

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputMessage = findViewById(R.id.inputMessage);
        recyclerView = findViewById(R.id.chatRecyclerView);
        chatAdapter = new ChatAdapter(messageList, this, this::onMessageClick, this::onMessageLongClick);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(chatAdapter);

        initUserId();
        loadNameMap();
        loadChatHistory();

        // show loaded history
        chatAdapter.notifyDataSetChanged();
        if (!messageList.isEmpty()) recyclerView.scrollToPosition(messageList.size() - 1);

        // BLE setup
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

        findViewById(R.id.sendButton).setOnClickListener(v -> {
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

    // ---------- Timestamp ----------
    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm a | dd-MM-yyyy", Locale.getDefault());
        return sdf.format(new Date());
    }

    // ---------- UserId ----------
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
        for (byte b : bytes) value = (value << 8) | (b & 0xFF);
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
        if (requestCode == PERMISSION_REQUEST_CODE) startScanning();
    }

    // ---------- Advertising ----------
    private void startAdvertising(String message) {
        if (advertiser == null) return;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false).build();
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceData(new ParcelUuid(SERVICE_UUID), message.getBytes(StandardCharsets.UTF_8))
                .build();
        stopAdvertising();
        advertiseCallback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) { Log.i(TAG,"Advertising started"); }
            @Override public void onStartFailure(int errorCode) { Log.e(TAG,"Advertising failed: "+errorCode); }
        };
        advertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private void stopAdvertising() {
        if (advertiser != null && advertiseCallback != null) advertiser.stopAdvertising(advertiseCallback);
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
                                    String[] parts = received.split(":",2);
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
        if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback);
        saveChatHistory();
        saveNameMap();
        super.onDestroy();
    }

    // ---------- Chat Save/Load ----------
    private void addMessage(MessageModel msg) {
        messageList.add(msg);
        if (messageList.size() > 200) {
            messageList = new ArrayList<>(messageList.subList(messageList.size()-200, messageList.size()));
        }
        chatAdapter.notifyDataSetChanged();
        recyclerView.scrollToPosition(messageList.size()-1);
        saveChatHistory();
    }

    private void saveChatHistory() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_CHAT_HISTORY, gson.toJson(messageList)).apply();
    }

    private void loadChatHistory() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_CHAT_HISTORY,null);
        if (json != null) {
            Type type = new TypeToken<List<MessageModel>>(){}.getType();
            List<MessageModel> loaded = gson.fromJson(json,type);
            if (loaded != null) {
                if (loaded.size()>200) loaded = new ArrayList<>(loaded.subList(loaded.size()-200,loaded.size()));
                messageList.clear();
                messageList.addAll(loaded);
            }
        }
    }

    // ---------- Name Map ----------
    private void loadNameMap() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_NAME_MAP, null);
        if (json != null) {
            Type type = new TypeToken<Map<String,String>>(){}.getType();
            nameMap = gson.fromJson(json, type);
        }
        if (nameMap==null) nameMap = new HashMap<>();
    }

    private void saveNameMap() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_NAME_MAP, gson.toJson(nameMap)).apply();
    }

    // ---------- Message click / long click ----------
    private void onMessageClick(MessageModel msg) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Copied Message", msg.getMessage()));
        Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show();
    }

    private void onMessageLongClick(MessageModel msg) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_name, null);
        builder.setView(dialogView);

        TextView title = dialogView.findViewById(R.id.dialogTitle);
        EditText input = dialogView.findViewById(R.id.editName);
        String existing = nameMap.get(msg.getSenderId());
        input.setText(existing != null ? existing : "");

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty()) nameMap.remove(msg.getSenderId());
            else nameMap.put(msg.getSenderId(), newName);
            saveNameMap();
            chatAdapter.notifyDataSetChanged();
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = builder.create();
        dialog.show();
    }


    // ---------- Helper for Adapter ----------
    public String getDisplayName(String senderId) {
        String name = nameMap.get(senderId);
        return (name != null && !name.isEmpty()) ? name : senderId;
    }
}
