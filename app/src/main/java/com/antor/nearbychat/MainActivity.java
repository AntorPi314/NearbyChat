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

    private long userIdBits;
    private String userId;
    private final Set<String> receivedMessages = new HashSet<>();
    private final Gson gson = new Gson();
    private Map<String, String> nameMap = new HashMap<>();

    private static final int REQUEST_ENABLE_LOCATION = 102;

    private BroadcastReceiver bluetoothReceiver;
    private boolean isReceiverRegistered = false;
    private boolean isAppActive = false;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler(Looper.getMainLooper());
        setupUI();
        initializeData();
        setupBluetoothReceiver();
        checkBluetoothAndLocation();
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
        int maxBits = 168;
        int maxChars = maxBits / 8;

        inputMessage.addTextChangedListener(new TextWatcher() {
            private boolean toastShown = false;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() > maxChars) {
                    inputMessage.setTextColor(Color.parseColor("#FF0000"));
                    if (!toastShown) {
                        Toast.makeText(MainActivity.this, "Maximum 168 bits allowed!", Toast.LENGTH_SHORT).show();
                        toastShown = true;
                    }
                } else {
                    inputMessage.setTextColor(Color.parseColor("#000000"));
                    toastShown = false;
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

        if (!validateBluetoothAndPermissions()) {
            return;
        }

        try {
            String fullMsg = toAscii(userIdBits) + ":" + msg;
            startAdvertising(fullMsg);

            String timestamp = getCurrentTimestamp();
            MessageModel newMsg = new MessageModel(userId, msg, true, timestamp);
            addMessage(newMsg);
            inputMessage.setText("");
        } catch (Exception e) {
            Log.e(TAG, "Error sending message", e);
            Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show();
        }
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

            String received = new String(data, StandardCharsets.UTF_8);

            synchronized (receivedMessages) {
                if (receivedMessages.contains(received)) return;
                receivedMessages.add(received);

                if (receivedMessages.size() > 1000) {
                    receivedMessages.clear();
                }
            }
            mainHandler.post(() -> processReceivedMessage(received));
        } catch (Exception e) {
            Log.e(TAG, "Error handling scan result", e);
        }
    }

    private void processReceivedMessage(String received) {
        try {
            if (!received.contains(":")) return;

            String[] parts = received.split(":", 2);
            if (parts.length != 2) return;

            String asciiId = parts[0];
            String message = parts[1];

            long bits = fromAscii(asciiId);
            String displayId = getUserIdString(bits);
            boolean isSelf = displayId.equals(userId);

            String timestamp = getCurrentTimestamp();
            MessageModel newMsg = new MessageModel(displayId, message, isSelf, timestamp);
            addMessage(newMsg);
        } catch (Exception e) {
            Log.e(TAG, "Error processing received message", e);
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

    private void startAdvertising(String message) {
        if (advertiser == null) return;
        try {
            stopAdvertising();
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .setTimeout(10000) // 10 seconds
                    .build();

            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceData(new ParcelUuid(SERVICE_UUID), message.getBytes(StandardCharsets.UTF_8))
                    .build();

            advertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    Log.d(TAG, "Advertising started successfully");
                }
                @Override
                public void onStartFailure(int errorCode) {
                    Log.e(TAG, "Advertising failed: " + errorCode);
                }
            };

            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (Exception e) {
            Log.e(TAG, "Error starting advertising", e);
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

    private void addMessage(MessageModel msg) {
        try {
            messageList.add(msg);
            if (messageList.size() > 200) {
                messageList = new ArrayList<>(messageList.subList(messageList.size() - 200, messageList.size()));
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
                    if (loaded.size() > 200) {
                        loaded = new ArrayList<>(loaded.subList(loaded.size() - 200, loaded.size()));
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
        if (messageList.size() > 50) {
            messageList = new ArrayList<>(messageList.subList(messageList.size() - 50, messageList.size()));
            chatAdapter.notifyDataSetChanged();
        }
    }
}