package com.antor.nearbychat;

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
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.SparseArray;
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
    private static final String TAG = "NearbyChatMain";
    private static final UUID SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private AdvertiseCallback advertiseCallback;
    private ScanCallback scanCallback;

    private EditText inputMessage;
    private RecyclerView recyclerView;
    private ChatAdapter chatAdapter;
    private List<MessageModel> messageList = new ArrayList<>();

    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_USER_ID_BITS = "userIdBits";
    private static final String KEY_CHAT_HISTORY = "chatHistory";
    private static final String KEY_NAME_MAP = "nameMap";
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray();

    // BLE service data payload limit - Fixed values
    private static final int USER_ID_LENGTH = 5;    // 5 bytes for user ID
    private static final int MESSAGE_ID_LENGTH = 4; // 4 bytes for message ID
    private static final int CHUNK_METADATA_LENGTH = 2; // 2 bytes for chunk index and total
    private static final int HEADER_SIZE = USER_ID_LENGTH + MESSAGE_ID_LENGTH + CHUNK_METADATA_LENGTH; // 11 bytes total
    private static final int MAX_PAYLOAD_SIZE = 27;  // Conservative BLE limit
    private static final int MAX_CHUNK_DATA_SIZE = MAX_PAYLOAD_SIZE - HEADER_SIZE; // 9 bytes per chunk
    private static final int WARNING_THRESHOLD = 22; // Show red text after 22 characters
    private static final int MAX_MESSAGE_LENGTH = 500; // Max total message length
    private static final int MAX_MESSAGE_SAVED = 500;

    private static final int ADVERTISING_DURATION_MS = 800;          // old value: 1000ms
    private static final int DELAY_BETWEEN_CHUNKS_MS = 1000;          // old value: 1200ms
    private static final int CHUNK_TIMEOUT_MS = 60000;                // old: 30 seconds reassembly timeout
    private static final int CHUNK_CLEANUP_INTERVAL_MS = 10000;       // 10 seconds cleanup interval
    private static final int MAX_RECENT_MESSAGES = 1000;              // Recent messages to track
    private static final int MAX_RECENT_CHUNKS = 2000;                // Recent chunks to track

    private long userIdBits;
    private String userId;
    private final Set<String> receivedMessages = new HashSet<>();
    private final Gson gson = new Gson();
    private Map<String, String> nameMap = new HashMap<>();

    // For message chunking
    private final Map<String, MessageReassembler> reassemblers = new HashMap<>();
    private final Handler chunkHandler = new Handler(Looper.getMainLooper());

    private static final int REQUEST_ENABLE_LOCATION = 102;

    private BroadcastReceiver bluetoothReceiver;
    private boolean isReceiverRegistered = false;
    private boolean isAppActive = false;
    private Handler mainHandler;

    // Class to handle message reassembly
    private static class MessageReassembler {
        private final String senderId;
        private final String messageId; // Unique ID for this message
        private final SparseArray<String> chunks = new SparseArray<>();
        private final long timestamp;
        private int totalChunks = -1;
        private int receivedCount = 0;

        public MessageReassembler(String senderId, String messageId) {
            this.senderId = senderId;
            this.messageId = messageId;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean addChunk(int chunkIndex, int totalChunks, String chunkData) {
            if (this.totalChunks == -1) {
                this.totalChunks = totalChunks;
            } else if (this.totalChunks != totalChunks) {
                Log.w(TAG, "Total chunks mismatch for message " + messageId);
                return false;
            }

            // Only add if we don't already have this chunk
            if (chunks.get(chunkIndex) == null) {
                chunks.put(chunkIndex, chunkData);
                receivedCount++;
                return true;
            }
            return false;
        }

        public boolean isComplete() {
            return totalChunks > 0 && receivedCount == totalChunks;
        }

        public String reassemble() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < totalChunks; i++) {
                String chunk = chunks.get(i);
                if (chunk != null) {
                    sb.append(chunk);
                } else {
                    Log.w(TAG, "Missing chunk " + i + " in message " + messageId);
                    return null; // Missing chunk, cannot reassemble
                }
            }
            return sb.toString();
        }

        public boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - timestamp > timeoutMs;
        }

        public String getMessageId() {
            return messageId;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler(Looper.getMainLooper());
        setupUI();
        initializeData();
        setupBluetoothReceiver();
        checkBluetoothAndLocation();

        // Clean up expired reassemblers periodically
        chunkHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                cleanupExpiredReassemblers();
                chunkHandler.postDelayed(this, CHUNK_CLEANUP_INTERVAL_MS);
            }
        }, 10000);
    }

    private void cleanupExpiredReassemblers() {
        synchronized (reassemblers) {
            Iterator<Map.Entry<String, MessageReassembler>> it = reassemblers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, MessageReassembler> entry = it.next();
                if (entry.getValue().isExpired(CHUNK_TIMEOUT_MS)) {
                    Log.d(TAG, "Removed expired reassembler for " + entry.getKey());
                    it.remove();
                }
            }
        }
    }

    private void setupUI() {
        final View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int heightDiff = rootView.getRootView().getHeight() - rootView.getHeight();
            boolean keyboardVisible = heightDiff > dpToPx(this, 200);
            if (!keyboardVisible) {
                UiUtils.setLightSystemBars(this);
            }
        });
        inputMessage = findViewById(R.id.inputMessage);
        setupMessageInput();

        recyclerView = findViewById(R.id.chatRecyclerView);
        chatAdapter = new ChatAdapter(messageList, this, this::onMessageClick, this::onMessageLongClick);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(chatAdapter);

        findViewById(R.id.sendButton).setOnClickListener(this::onSendButtonClick);
    }

    private void setupMessageInput() {
        inputMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() > WARNING_THRESHOLD) {
                    inputMessage.setTextColor(Color.parseColor("#FF0000"));
                } else {
                    inputMessage.setTextColor(Color.parseColor("#000000"));
                }
                // Show character count in a toast or status message if needed
                if (s.length() > MAX_MESSAGE_LENGTH) {
                    Toast.makeText(MainActivity.this,
                            "Message too long (" + s.length() + "/" + MAX_MESSAGE_LENGTH + ")",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void initializeData() {
        initUserId();
        loadNameMap();
        loadChatHistory();
        chatAdapter.notifyDataSetChanged();
        if (!messageList.isEmpty()) {
            recyclerView.scrollToPosition(messageList.size() - 1);
        }
    }

    private void setupBluetoothReceiver() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;

                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                    final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    handleBluetoothStateChange(state);
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(bluetoothReceiver, filter);
            }
            isReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to register Bluetooth receiver", e);
            isReceiverRegistered = false;
        }
    }

    private void handleBluetoothStateChange(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_ON:
                Log.d(TAG, "Bluetooth turned ON");
                if (isAppActive) {
                    mainHandler.postDelayed(() -> checkLocationAndInitBLE(), 500);
                }
                break;



            case BluetoothAdapter.STATE_OFF:
                Log.d(TAG, "Bluetooth turned OFF");
                safelyStopBleOperations();
                break;
            case BluetoothAdapter.STATE_TURNING_OFF:
                Log.d(TAG, "Bluetooth turning OFF");
                safelyStopBleOperations();
                break;
        }
    }

    private void onSendButtonClick(View v) {
        String msg = inputMessage.getText().toString().trim();
        if (msg.isEmpty()) {
            Toast.makeText(this, "Message cannot be empty!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (msg.length() > MAX_MESSAGE_LENGTH) {
            Toast.makeText(this, "Message too long (" + msg.length() + "/" + MAX_MESSAGE_LENGTH + ")", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!validateBluetoothAndPermissions()) {
            return;
        }

        try {
            // Always add to UI and show as sent
            String timestamp = getCurrentTimestamp();
            MessageModel newMsg = new MessageModel(userId, msg, true, timestamp);
            addMessage(newMsg);
            inputMessage.setText("");

            // Send message in chunks with proper sequencing
            sendMessageInChunks(msg);

        } catch (Exception e) {
            Log.e(TAG, "Error processing message", e);
        }
    }

    private String generateMessageId() {
        // 4-byte ID (0-9999 range) ensuring it fits in 4 characters
        int id = (int) (System.currentTimeMillis() % 10000);
        return String.format("%04d", id);
    }

    private void sendMessageInChunks(String message) {
        try {
            String messageId = generateMessageId();
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            int totalChunks = (int) Math.ceil((double) messageBytes.length / MAX_CHUNK_DATA_SIZE);

            Log.d(TAG, "Sending message: " + message.length() + " chars, "
                    + messageBytes.length + " bytes, " + totalChunks + " chunks");

            // startAdvertising(new byte[]{0x00}); // dummy data

            for (int i = 0; i < totalChunks; i++) {
                final int chunkIndex = i;
                mainHandler.postDelayed(() -> {
                    try {
                        int start = chunkIndex * MAX_CHUNK_DATA_SIZE;
                        int end = Math.min(messageBytes.length, start + MAX_CHUNK_DATA_SIZE);

                        byte[] chunkData = Arrays.copyOfRange(messageBytes, start, end);
                        byte[] chunkPayload = createChunkPayload(messageId, chunkIndex, totalChunks, chunkData);

                        updateAdvertisingData(chunkPayload);
                        Log.d(TAG, "Sent chunk " + chunkIndex + "/" + totalChunks
                                + " (" + chunkData.length + " bytes)");
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending chunk " + chunkIndex, e);
                    }
                }, i * DELAY_BETWEEN_CHUNKS_MS);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error preparing message chunks", e);
        }
    }

    private void updateAdvertisingData(byte[] payload) {
        if (advertiser == null) return;
        try {
            stopAdvertising();

            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceData(new ParcelUuid(SERVICE_UUID), payload)
                    .build();

            advertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    Log.d(TAG, "Chunk advertise success (" + payload.length + " bytes)");
                }

                @Override
                public void onStartFailure(int errorCode) {
                    Log.w(TAG, "Chunk advertise failed: " + errorCode);
                }
            };

            advertiser.startAdvertising(
                    new AdvertiseSettings.Builder()
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                            .setConnectable(false)
                            .setTimeout(ADVERTISING_DURATION_MS)
                            .build(),
                    data,
                    advertiseCallback
            );
        } catch (Exception e) {
            Log.e(TAG, "Error updating advertising data", e);
        }
    }




    private byte[] createChunkPayload(String messageId, int chunkIndex, int totalChunks, byte[] chunkData) {
        try {
            // Format: [userID(5)][messageId(4)][chunkIndex(1)][totalChunks(1)][chunkData]
            byte[] userIdBytes = toAsciiBytes(userIdBits);
            byte[] messageIdBytes = messageId.getBytes(StandardCharsets.UTF_8);

            // Ensure messageId is exactly 4 bytes
            if (messageIdBytes.length > 4) {
                messageIdBytes = Arrays.copyOf(messageIdBytes, 4);
            } else if (messageIdBytes.length < 4) {
                byte[] temp = new byte[4];
                System.arraycopy(messageIdBytes, 0, temp, 0, messageIdBytes.length);
                messageIdBytes = temp;
            }

            byte[] payload = new byte[USER_ID_LENGTH + 4 + 2 + chunkData.length];

            // Copy user ID
            System.arraycopy(userIdBytes, 0, payload, 0, USER_ID_LENGTH);

            // Copy message ID
            System.arraycopy(messageIdBytes, 0, payload, USER_ID_LENGTH, 4);

            // Add chunk metadata
            payload[USER_ID_LENGTH + 4] = (byte) chunkIndex;
            payload[USER_ID_LENGTH + 5] = (byte) totalChunks;

            // Add chunk data
            System.arraycopy(chunkData, 0, payload, USER_ID_LENGTH + 6, chunkData.length);

            return payload;
        } catch (Exception e) {
            Log.e(TAG, "Error creating chunk payload", e);
            return new byte[0];
        }
    }

    private byte[] toAsciiBytes(long bits40) {
        byte[] bytes = new byte[5];
        long temp = bits40;
        for (int i = 4; i >= 0; i--) {
            bytes[i] = (byte) (temp & 0xFF);
            temp >>= 8;
        }
        return bytes;
    }

    private boolean validateBluetoothAndPermissions() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show();
            return false;
        }

        try {
            if (!bluetoothAdapter.isEnabled()) {
                Toast.makeText(this, "Turn on Bluetooth", Toast.LENGTH_SHORT).show();
                return false;
            }
        } catch (SecurityException se) {
            Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            Toast.makeText(this, "Turn on Location", Toast.LENGTH_SHORT).show();
            return false;
        }
        List<String> missingPermissions = getMissingPermissions();
        if (!missingPermissions.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                Toast.makeText(this, "Please grant Bluetooth permissions in the popup or restart the app", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Missing permission(s): " + getPermissionNames(missingPermissions), Toast.LENGTH_LONG).show();
            }
            return false;
        }
        return true;
    }

    private List<String> getPermissionNames(List<String> permissions) {
        List<String> names = new ArrayList<>();
        for (String permission : permissions) {
            switch (permission) {
                case Manifest.permission.BLUETOOTH_ADVERTISE:
                    names.add("BLUETOOTH_ADVERTISE");
                    break;
                case Manifest.permission.BLUETOOTH_SCAN:
                    names.add("BLUETOOTH_SCAN");
                    break;
                case Manifest.permission.BLUETOOTH_CONNECT:
                    names.add("BLUETOOTH_CONNECT");
                    break;
                case Manifest.permission.ACCESS_FINE_LOCATION:
                    names.add("ACCESS_FINE_LOCATION");
                    break;
                default:
                    names.add(permission.substring(permission.lastIndexOf('.') + 1));
            }
        }
        return names;
    }

    private List<String> getMissingPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                missing.add("BLUETOOTH_ADVERTISE");
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add("BLUETOOTH_SCAN");
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add("BLUETOOTH_CONNECT");
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add("ACCESS_FINE_LOCATION");
            }
        }
        return missing;
    }

    private int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // ---------- Bluetooth & Location Check ----------
    private void checkBluetoothAndLocation() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasBasicBluetoothPermission()) {
            requestBasicBluetoothPermission();
            return;
        }
        try {
            if (!bluetoothAdapter.isEnabled()) {
                Toast.makeText(this, "Please turn on Bluetooth to use Nearby Chat", Toast.LENGTH_LONG).show();
                return;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception checking Bluetooth state", e);
            return;
        }
        checkLocationAndInitBLE();
    }

    private boolean hasBasicBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBasicBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQUEST_CODE);
        }
    }

    private void checkLocationAndInitBLE() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            promptEnableLocation();
            return;
        }
        initBLE();
    }

    private boolean isLocationEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } else {
            int mode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
            return mode != Settings.Secure.LOCATION_MODE_OFF;
        }
    }

    private void promptEnableLocation() {
        new AlertDialog.Builder(this)
                .setTitle("Enable Location")
                .setMessage("Location is required for BLE scanning on Android 8-11. Please turn it on.")
                .setPositiveButton("Enable", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    startActivityForResult(intent, REQUEST_ENABLE_LOCATION);
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    Toast.makeText(this, "Location required for BLE on this Android version", Toast.LENGTH_LONG).show();
                })
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_LOCATION) {
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled() && isLocationEnabled()) {
                initBLE();
            } else {
                Toast.makeText(this, "Both Bluetooth and Location are required", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- BLE Initialization ----------
    private void initBLE() {
        if (bluetoothAdapter == null) return;
        try {
            if (!bluetoothAdapter.isMultipleAdvertisementSupported()) {
                Toast.makeText(this, "BLE Advertising not supported on this device", Toast.LENGTH_LONG).show();
                return;
            }
            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (advertiser == null || scanner == null) {
                Toast.makeText(this, "BLE components not available", Toast.LENGTH_LONG).show();
                return;
            }
            requestAllPermissions();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing BLE", e);
            Toast.makeText(this, "Failed to initialize BLE", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Permissions ----------
    private void requestAllPermissions() {
        List<String> permissionsList = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] requiredPermissions = {
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
            for (String permission : requiredPermissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    permissionsList.add(permission);
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissionsList.isEmpty()) {
            Log.d(TAG, "Requesting permissions: " + permissionsList);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                new AlertDialog.Builder(this)
                        .setTitle("Bluetooth Permissions Required")
                        .setMessage("Nearby Chat needs Bluetooth permissions to work properly. You'll see permission requests next.")
                        .setPositiveButton("Continue", (dialog, which) -> {
                            ActivityCompat.requestPermissions(this, permissionsList.toArray(new String[0]), PERMISSION_REQUEST_CODE);
                        })
                        .setNegativeButton("Cancel", (dialog, which) -> {
                            Toast.makeText(this, "App cannot work without Bluetooth permissions", Toast.LENGTH_LONG).show();
                        })
                        .setCancelable(false)
                        .show();
            } else {
                ActivityCompat.requestPermissions(this, permissionsList.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            }
        } else {
            Log.d(TAG, "All permissions already granted");
            startScanning();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    startScanning();
                } else {
                    Toast.makeText(this, "Bluetooth must be enabled", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "All permissions are required to use Nearby Chat", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- BLE Operations ----------
    private void startScanning() {
        if (scanner == null || !isAppActive) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_SCAN permission not granted");
                return;
            }
        }
        try {
            stopScanning();
            scanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    handleScanResult(result);
                }

                @Override
                public void onScanFailed(int errorCode) {
                    Log.e(TAG, "Scan failed with error: " + errorCode);
                }
            };
            ScanSettings.Builder settingsBuilder = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                settingsBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
            }
            List<ScanFilter> filters = Arrays.asList(
                    new ScanFilter.Builder()
                            .setServiceData(new ParcelUuid(SERVICE_UUID), new byte[0])
                            .build()
            );
            scanner.startScan(filters, settingsBuilder.build(), scanCallback);
            Log.d(TAG, "Started BLE scanning");
        } catch (Exception e) {
            Log.e(TAG, "Error starting scan", e);
        }
    }

    private void handleScanResult(ScanResult result) {
        try {
            ScanRecord record = result.getScanRecord();
            if (record == null) return;
            Map<ParcelUuid, byte[]> serviceData = record.getServiceData();
            if (serviceData == null) return;
            byte[] data = serviceData.get(new ParcelUuid(SERVICE_UUID));
            if (data == null) return;

            // Debug logging
            Log.d(TAG, "Received data length: " + data.length + " bytes");

            if (data.length < USER_ID_LENGTH + 2)
                return; // Need at least user ID + chunk metadata

            // Use full message as unique ID to prevent duplicates
            String messageId = Arrays.toString(data);
            synchronized (receivedMessages) {
                if (receivedMessages.contains(messageId)) return;
                receivedMessages.add(messageId);
                if (receivedMessages.size() > MAX_RECENT_MESSAGES) {
                    receivedMessages.clear();
                }
            }
            mainHandler.post(() -> processReceivedMessage(data));
        } catch (Exception e) {
            Log.e(TAG, "Error handling scan result", e);
        }
    }

    private void processReceivedMessage(byte[] receivedData) {
        try {
            if (receivedData.length < USER_ID_LENGTH + 6) return;

            byte[] userIdBytes = Arrays.copyOfRange(receivedData, 0, USER_ID_LENGTH);
            long bits = fromAsciiBytes(userIdBytes);
            String displayId = getUserIdString(bits);

            byte[] messageIdBytes = Arrays.copyOfRange(receivedData, USER_ID_LENGTH, USER_ID_LENGTH + 4);
            String messageId = new String(messageIdBytes, StandardCharsets.UTF_8).trim();

            int chunkIndex = receivedData[USER_ID_LENGTH + 4] & 0xFF;
            int totalChunks = receivedData[USER_ID_LENGTH + 5] & 0xFF;

            if (chunkIndex < 0 || chunkIndex >= totalChunks || totalChunks <= 0) {
                Log.w(TAG, "Invalid chunk metadata: " + chunkIndex + "/" + totalChunks);
                return;
            }

            byte[] chunkDataBytes = Arrays.copyOfRange(receivedData, USER_ID_LENGTH + 6, receivedData.length);

            String chunkKey = displayId + "_" + messageId + "_" + chunkIndex;
            synchronized (receivedMessages) {
                if (receivedMessages.contains(chunkKey)) return;
                receivedMessages.add(chunkKey);
                if (receivedMessages.size() > MAX_RECENT_CHUNKS) {
                    receivedMessages.clear();
                }
            }

            if (totalChunks > 1) {
                processMessageChunk(displayId, messageId, chunkIndex, totalChunks, chunkDataBytes);
            } else {
                String message = new String(chunkDataBytes, StandardCharsets.UTF_8);
                boolean isSelf = displayId.equals(userId);
                String timestamp = getCurrentTimestamp();
                MessageModel newMsg = new MessageModel(displayId, message, isSelf, timestamp);
                addMessage(newMsg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing received message", e);
        }
    }


    private void processMessageChunk(String senderId, String messageId, int chunkIndex, int totalChunks, byte[] chunkData) {
        String reassemblerKey = senderId + "_" + messageId;

        synchronized (reassemblers) {
            MessageReassembler reassembler = reassemblers.get(reassemblerKey);
            if (reassembler == null) {
                reassembler = new MessageReassembler(senderId, messageId);
                reassemblers.put(reassemblerKey, reassembler);
            }

            // Convert chunk data to string for storage
            String chunkString = new String(chunkData, StandardCharsets.UTF_8);

            // Add the chunk (only if it's new)
            if (reassembler.addChunk(chunkIndex, totalChunks, chunkString)) {
                Log.d(TAG, "Received chunk " + chunkIndex + "/" + totalChunks + " for message " + messageId);
            }

            if (reassembler.isComplete()) {
                // Message is complete, process it
                String fullMessage = reassembler.reassemble();
                if (fullMessage != null) {
                    boolean isSelf = senderId.equals(userId);
                    String timestamp = getCurrentTimestamp();
                    MessageModel newMsg = new MessageModel(senderId, fullMessage, isSelf, timestamp);
                    addMessage(newMsg);
                    Log.d(TAG, "Successfully reassembled message: " + messageId);
                } else {
                    Log.w(TAG, "Failed to reassemble message: " + messageId);
                }

                // Remove the reassembler
                reassemblers.remove(reassemblerKey);
            }
        }
    }

    private void stopScanning() {
        if (scanner != null && scanCallback != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                        scanner.stopScan(scanCallback);
                    }
                } else {
                    scanner.stopScan(scanCallback);
                }
                Log.d(TAG, "Stopped BLE scanning");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping scan", e);
            } finally {
                scanCallback = null;
            }
        }
    }

    private void startAdvertising(byte[] payload) {
        if (advertiser == null) return;
        try {
            stopAdvertising();

            Log.d(TAG, "Attempting to advertise " + payload.length + " bytes");

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .setTimeout(ADVERTISING_DURATION_MS)
                    .build();

            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceData(new ParcelUuid(SERVICE_UUID), payload)
                    .build();

            advertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    Log.d(TAG, "BLE transmission successful - " + payload.length + " bytes sent");
                }

                @Override
                public void onStartFailure(int errorCode) {
                    // Complete silent fail - just log for debugging
                    Log.w(TAG, "BLE transmission failed silently - Error: " + errorCode +
                            ", Payload: " + payload.length + " bytes");

                    // Only log the specific reason for debugging
                    switch (errorCode) {
                        case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                            Log.d(TAG, "Message was too large for BLE (" + payload.length + " bytes)");
                            break;
                        case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                            Log.d(TAG, "Too many BLE advertisers active");
                            break;
                        case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                            Log.d(TAG, "BLE advertising already started");
                            break;
                        case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                            Log.d(TAG, "BLE internal error");
                            break;
                        case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                            Log.d(TAG, "BLE advertising not supported");
                            break;
                    }
                    // No Toast or user notification - completely silent fail
                    // Message is already shown in UI regardless of BLE success
                }
            };

            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (Exception e) {
            Log.e(TAG, "Exception during BLE advertising", e);
            // Silent fail - message is already shown in UI
        }
    }

    private void stopAdvertising() {
        if (advertiser != null && advertiseCallback != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
                        advertiser.stopAdvertising(advertiseCallback);
                    }
                } else {
                    advertiser.stopAdvertising(advertiseCallback);
                }
                Log.d(TAG, "Stopped advertising");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping advertising", e);
            } finally {
                advertiseCallback = null;
            }
        }
    }

    private void safelyStopBleOperations() {
        try {
            stopAdvertising();
            stopScanning();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping BLE operations", e);
        }
    }

    // ---------- Lifecycle Management ----------
    @Override
    protected void onResume() {
        super.onResume();
        isAppActive = true;
        List<String> missingPermissions = getMissingPermissions();
        if (!missingPermissions.isEmpty()) {
            Log.d(TAG, "Still missing permissions on resume: " + missingPermissions);
            return;
        }
        if (bluetoothAdapter != null && scanner != null) {
            try {
                if (bluetoothAdapter.isEnabled()) {
                    mainHandler.postDelayed(() -> {
                        if (isAppActive && hasAllRequiredPermissions()) {
                            startScanning();
                        }
                    }, 1000);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception on resume", e);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isAppActive = false;
        try {
            stopAdvertising();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping advertising in onPause", e);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            isAppActive = false;
            if (isReceiverRegistered && bluetoothReceiver != null) {
                try {
                    unregisterReceiver(bluetoothReceiver);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Receiver was not registered", e);
                } catch (Exception e) {
                    Log.e(TAG, "Error unregistering receiver", e);
                } finally {
                    isReceiverRegistered = false;
                }
            }
            safelyStopBleOperations();
            saveChatHistory();
            saveNameMap();
            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
            }
            if (chunkHandler != null) {
                chunkHandler.removeCallbacksAndMessages(null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Cleanup error", e);
        }
        super.onDestroy();
    }

    private boolean hasAllRequiredPermissions() {
        return getMissingPermissions().isEmpty();
    }

    // ---------- Data Management ----------
    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a | dd-MM-yyyy", Locale.getDefault());
        return sdf.format(new Date());
    }

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

    private long fromAsciiBytes(byte[] bytes) {
        if (bytes.length != 5) throw new IllegalArgumentException("Invalid ASCII bytes");
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFF);
        }
        return value;
    }

    private void addMessage(MessageModel msg) {
        try {
            messageList.add(msg);
            if (messageList.size() > MAX_MESSAGE_SAVED) {
                messageList = new ArrayList<>(messageList.subList(messageList.size() - MAX_MESSAGE_SAVED, messageList.size()));
            }
            chatAdapter.notifyDataSetChanged();
            recyclerView.scrollToPosition(messageList.size() - 1);
            saveChatHistory();
        } catch (Exception e) {
            Log.e(TAG, "Error adding message", e);
        }
    }

    private void saveChatHistory() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString(KEY_CHAT_HISTORY, gson.toJson(messageList)).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving chat history", e);
        }
    }

    private void loadChatHistory() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String json = prefs.getString(KEY_CHAT_HISTORY, null);
            if (json != null) {
                Type type = new TypeToken<List<MessageModel>>() {
                }.getType();
                List<MessageModel> loaded = gson.fromJson(json, type);
                if (loaded != null) {
                    if (loaded.size() > MAX_MESSAGE_SAVED) {
                        loaded = new ArrayList<>(loaded.subList(loaded.size() - MAX_MESSAGE_SAVED, loaded.size()));
                    }
                    messageList.clear();
                    messageList.addAll(loaded);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading chat history", e);
        }
    }

    private void loadNameMap() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String json = prefs.getString(KEY_NAME_MAP, null);
            if (json != null) {
                Type type = new TypeToken<Map<String, String>>() {
                }.getType();
                nameMap = gson.fromJson(json, type);
            }
            if (nameMap == null) {
                nameMap = new HashMap<>();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading name map", e);
            nameMap = new HashMap<>();
        }
    }

    private void saveNameMap() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString(KEY_NAME_MAP, gson.toJson(nameMap)).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving name map", e);
        }
    }

    // ---------- Message Interactions ----------
    private void onMessageClick(MessageModel msg) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Copied Message", msg.getMessage()));
                Toast.makeText(this, "Message copied", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copying message", e);
            Toast.makeText(this, "Failed to copy message", Toast.LENGTH_SHORT).show();
        }
    }

    private void onMessageLongClick(MessageModel msg) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_name, null);
            builder.setView(dialogView);

            TextView title = dialogView.findViewById(R.id.dialogTitle);
            EditText input = dialogView.findViewById(R.id.editName);

            String existing = nameMap.get(msg.getSenderId());
            input.setText(existing != null ? existing : "");

            builder.setTitle("Set Custom Name")
                    .setPositiveButton("Save", (dialog, which) -> {
                        try {
                            String newName = input.getText().toString().trim();
                            if (newName.isEmpty()) {
                                nameMap.remove(msg.getSenderId());
                            } else {
                                nameMap.put(msg.getSenderId(), newName);
                            }
                            saveNameMap();
                            chatAdapter.notifyDataSetChanged();
                            Toast.makeText(this, "Name updated", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Log.e(TAG, "Error updating name", e);
                            Toast.makeText(this, "Failed to update name", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                    .create()
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing name dialog", e);
        }
    }

    // ---------- Helper Methods ----------
    public String getDisplayName(String senderId) {
        try {
            String name = nameMap.get(senderId);
            return (name != null && !name.isEmpty()) ? name : senderId;
        } catch (Exception e) {
            Log.e(TAG, "Error getting display name", e);
            return senderId;
        }
    }

    // ---------- Memory Management ----------
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE) {
            synchronized (receivedMessages) {
                receivedMessages.clear();
            }
            synchronized (reassemblers) {
                reassemblers.clear();
            }
            if (level >= TRIM_MEMORY_COMPLETE) {
                stopScanning();
            }
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        synchronized (receivedMessages) {
            receivedMessages.clear();
        }
        synchronized (reassemblers) {
            reassemblers.clear();
        }
        if (messageList.size() > 50) {
            messageList = new ArrayList<>(messageList.subList(messageList.size() - 50, messageList.size()));
            chatAdapter.notifyDataSetChanged();
        }
    }
}