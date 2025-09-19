package com.antor.nearbychat;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import android.content.Context;
import android.os.Handler;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.util.Log;
import android.util.SparseArray;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class BleMessagingService extends Service {

    private static final String TAG = "BleMessagingService";
    private static final String CHANNEL_ID = "nearby_chat_service";
    private static final int NOTIFICATION_ID = 1001;

    // BLE Constants - These will be updated from settings
    private static UUID SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");
    private static final int USER_ID_LENGTH = 5;
    private static final int MESSAGE_ID_LENGTH = 4;
    private static final int CHUNK_METADATA_LENGTH = 2;
    private static final int HEADER_SIZE = USER_ID_LENGTH + MESSAGE_ID_LENGTH + CHUNK_METADATA_LENGTH;

    // Dynamic settings - will be loaded from preferences
    private static int MAX_PAYLOAD_SIZE = 27;
    private static int MAX_CHUNK_DATA_SIZE = MAX_PAYLOAD_SIZE - HEADER_SIZE;
    private static int ADVERTISING_DURATION_MS = 800;
    private static int DELAY_BETWEEN_CHUNKS_MS = 1000;
    private static int CHUNK_TIMEOUT_MS = 60000;
    private static int CHUNK_CLEANUP_INTERVAL_MS = 10000;
    private static int MAX_RECENT_MESSAGES = 1000;
    private static int MAX_RECENT_CHUNKS = 2000;
    private static int MAX_MESSAGE_SAVED = 500;

    // Broadcast Actions
    public static final String ACTION_MESSAGE_RECEIVED = "com.antor.nearbychat.MESSAGE_RECEIVED";
    public static final String ACTION_MESSAGE_SENT = "com.antor.nearbychat.MESSAGE_SENT";
    public static final String ACTION_SERVICE_STATE = "com.antor.nearbychat.SERVICE_STATE";
    public static final String EXTRA_MESSAGE = "extra_message";
    public static final String EXTRA_IS_RUNNING = "extra_is_running";

    // Preferences
    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_USER_ID_BITS = "userIdBits";
    private static final String KEY_CHAT_HISTORY = "chatHistory";
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray();

    // BLE Components
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private AdvertiseCallback advertiseCallback;

    // Service State
    private PowerManager.WakeLock wakeLock;
    private boolean isScanning = false;
    private boolean isServiceRunning = false;
    private Handler mainHandler;
    private Handler chunkHandler;

    // Data Management
    private long userIdBits;
    private String userId;
    private final Set<String> receivedMessages = new HashSet<>();
    private final Map<String, MessageReassembler> reassemblers = new HashMap<>();
    private final Gson gson = new Gson();
    private List<MessageModel> messageList = new ArrayList<>();

    // Bluetooth State Receiver
    private BroadcastReceiver bluetoothReceiver;

    // Binder for local communication
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public BleMessagingService getService() {
            return BleMessagingService.this;
        }
    }

    private void loadConfigurableSettings() {
        try {
            SharedPreferences prefs = getSharedPreferences("NearbyChatSettings", MODE_PRIVATE);

            // Load settings with proper defaults
            MAX_PAYLOAD_SIZE = prefs.getInt("MAX_PAYLOAD_SIZE", 27);
            if (MAX_PAYLOAD_SIZE < 20) MAX_PAYLOAD_SIZE = 20;
            MAX_CHUNK_DATA_SIZE = MAX_PAYLOAD_SIZE - HEADER_SIZE;

            // Load and validate UUID
            String serviceUuidString = prefs.getString("SERVICE_UUID", "0000aaaa-0000-1000-8000-00805f9b34fb");
            try {
                UUID newServiceUuid = UUID.fromString(serviceUuidString);
                SERVICE_UUID = newServiceUuid;
                Log.d(TAG, "Using Service UUID: " + SERVICE_UUID.toString());
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid UUID in preferences, using default", e);
                SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");
            }

            // Load timing settings
            ADVERTISING_DURATION_MS = prefs.getInt("ADVERTISING_DURATION_MS", 800);
            DELAY_BETWEEN_CHUNKS_MS = prefs.getInt("DELAY_BETWEEN_CHUNKS_MS", 1000);
            CHUNK_TIMEOUT_MS = prefs.getInt("CHUNK_TIMEOUT_MS", 60000);
            CHUNK_CLEANUP_INTERVAL_MS = prefs.getInt("CHUNK_CLEANUP_INTERVAL_MS", 10000);

            // Load memory settings
            MAX_RECENT_MESSAGES = prefs.getInt("MAX_RECENT_MESSAGES", 1000);
            MAX_RECENT_CHUNKS = prefs.getInt("MAX_RECENT_CHUNKS", 2000);
            MAX_MESSAGE_SAVED = prefs.getInt("MAX_MESSAGE_SAVED", 500);

            Log.d(TAG, "Settings loaded - UUID: " + SERVICE_UUID + ", Payload: " + MAX_PAYLOAD_SIZE);

        } catch (Exception e) {
            Log.e(TAG, "Error loading settings, using defaults", e);
            // Set defaults if loading fails
            SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");
            MAX_PAYLOAD_SIZE = 27;
            MAX_CHUNK_DATA_SIZE = MAX_PAYLOAD_SIZE - HEADER_SIZE;
        }
    }

    // MessageReassembler class
    private static class MessageReassembler {
        private final String senderId;
        private final String messageId;
        private final SparseArray<byte[]> chunks = new SparseArray<>();
        private final long timestamp;
        private int totalChunks = -1;
        private int receivedCount = 0;

        public MessageReassembler(String senderId, String messageId) {
            this.senderId = senderId;
            this.messageId = messageId;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean addChunk(int chunkIndex, int totalChunks, byte[] chunkData) {
            if (this.totalChunks == -1) {
                this.totalChunks = totalChunks;
            } else if (this.totalChunks != totalChunks) {
                return false;
            }

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
            // Calculate total size first
            int totalSize = 0;
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = chunks.get(i);
                if (chunk != null) {
                    totalSize += chunk.length;
                } else {
                    return null;
                }
            }

            // Combine all bytes
            byte[] fullBytes = new byte[totalSize];
            int offset = 0;
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = chunks.get(i);
                System.arraycopy(chunk, 0, fullBytes, offset, chunk.length);
                offset += chunk.length;
            }

            // Convert to string only once
            return new String(fullBytes, StandardCharsets.UTF_8);
        }

        public boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - timestamp > timeoutMs;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");

        mainHandler = new Handler(Looper.getMainLooper());
        chunkHandler = new Handler(Looper.getMainLooper());

        // Load settings FIRST before initializing anything
        loadConfigurableSettings();

        initializeData();
        acquireWakeLock();
        setupBluetoothReceiver();
        createNotificationChannel();

        // Start periodic cleanup
        chunkHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                cleanupExpiredReassemblers();
                chunkHandler.postDelayed(this, CHUNK_CLEANUP_INTERVAL_MS);
            }
        }, CHUNK_CLEANUP_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");

        // Reload settings each time the service starts (important for restart)
        loadConfigurableSettings();

        if (!isServiceRunning) {
            startForegroundService();
            initializeBle();
            isServiceRunning = true;
            broadcastServiceState(true);
        } else {
            // If service is already running but we got new intent, reinitialize BLE
            // This handles the restart case
            Log.d(TAG, "Service already running, reinitializing BLE with new settings");
            stopBleOperations();
            mainHandler.postDelayed(() -> {
                initializeBle();
            }, 1000);
        }

        // Handle message sending if intent contains data
        if (intent != null && intent.hasExtra("message_to_send")) {
            String message = intent.getStringExtra("message_to_send");
            if (message != null) {
                sendMessageInChunks(message);
            }
        }

        return START_STICKY;
    }

    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Nearby Chat Active")
                .setContentText("Scanning for nearby messages...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE);

        Notification notification = builder.build();

        // FIX for Android 14+ (API 34) and Android 15 (API 35) crash
        if (Build.VERSION.SDK_INT >= 34) {
            // For Android 14+, combine all foreground service types declared in the manifest.
            int foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION |
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10 to 13, use the location type as required for BLE scanning.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            // For older versions, no type is needed.
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Nearby Chat Service",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps Nearby Chat running in background");
        channel.setShowBadge(false);
        channel.setSound(null, null);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "NearbyChatService::WakeLock"
            );
            wakeLock.acquire();
            Log.d(TAG, "WakeLock acquired");
        }
    }

    private void initializeData() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        userIdBits = prefs.getLong(KEY_USER_ID_BITS, -1);
        if (userIdBits == -1) {
            userIdBits = System.currentTimeMillis() & ((1L << 40) - 1);
            prefs.edit().putLong(KEY_USER_ID_BITS, userIdBits).apply();
        }
        userId = getUserIdString(userIdBits);
        loadChatHistory();
    }

    private void setupBluetoothReceiver() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    handleBluetoothStateChange(state);
                }
            }
        };

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bluetoothReceiver, filter);
        }
    }

    private void handleBluetoothStateChange(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_ON:
                Log.d(TAG, "Bluetooth ON - Restarting BLE");
                mainHandler.postDelayed(this::initializeBle, 500);
                break;
            case BluetoothAdapter.STATE_OFF:
                Log.d(TAG, "Bluetooth OFF - Stopping BLE");
                stopBleOperations();
                break;
        }
    }

    private void initializeBle() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth not available or disabled");
            return;
        }

        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Missing required permissions for BLE operations");
            updateNotification("Missing Bluetooth permissions", true);
            return;
        }

        try {
            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            scanner = bluetoothAdapter.getBluetoothLeScanner();

            if (scanner != null) {
                startScanning();
            } else {
                Log.w(TAG, "BLE Scanner not available");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException when initializing BLE - missing permissions", e);
            updateNotification("Missing Bluetooth permissions", true);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing BLE", e);
        }
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void startScanning() {
        if (scanner == null || isScanning) {
            Log.d(TAG, "Scanner null or already scanning");
            return;
        }

        try {
            if (!hasRequiredPermissions()) {
                Log.w(TAG, "Missing required permissions for scanning");
                return;
            }

            scanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    handleScanResult(result);
                }

                @Override
                public void onScanFailed(int errorCode) {
                    Log.e(TAG, "Scan failed: " + errorCode);
                    isScanning = false;
                }
            };

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            // Create filter with the current SERVICE_UUID
            List<ScanFilter> filters = Arrays.asList(
                    new ScanFilter.Builder()
                            .setServiceData(new ParcelUuid(SERVICE_UUID), new byte[0])
                            .build()
            );

            try {
                scanner.startScan(filters, settings, scanCallback);
                isScanning = true;
                Log.d(TAG, "Started scanning with UUID: " + SERVICE_UUID.toString());
                updateNotification("Scanning for messages...", true);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException when starting scan - missing permissions", e);
                isScanning = false;
                updateNotification("Missing permissions for scanning", true);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error starting scan", e);
            isScanning = false;
        }
    }

    private void handleScanResult(ScanResult result) {
        try {
            ScanRecord record = result.getScanRecord();
            if (record == null) return;

            Map<ParcelUuid, byte[]> serviceData = record.getServiceData();
            if (serviceData == null) return;

            byte[] data = serviceData.get(new ParcelUuid(SERVICE_UUID));
            if (data == null || data.length < USER_ID_LENGTH + 6) return;

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
            byte[] userIdBytes = Arrays.copyOfRange(receivedData, 0, USER_ID_LENGTH);
            long bits = fromAsciiBytes(userIdBytes);
            String displayId = getUserIdString(bits);

            byte[] messageIdBytes = Arrays.copyOfRange(receivedData, USER_ID_LENGTH, USER_ID_LENGTH + 4);
            String messageId = new String(messageIdBytes, StandardCharsets.UTF_8).trim();

            int chunkIndex = receivedData[USER_ID_LENGTH + 4] & 0xFF;
            int totalChunks = receivedData[USER_ID_LENGTH + 5] & 0xFF;

            if (chunkIndex < 0 || chunkIndex >= totalChunks || totalChunks <= 0) {
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
                if (!isSelf) {
                    MessageModel newMsg = new MessageModel(displayId, message, false, getCurrentTimestamp());
                    addMessage(newMsg);
                    broadcastMessage(newMsg);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing message", e);
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

            if (reassembler.addChunk(chunkIndex, totalChunks, chunkData)) {
                Log.d(TAG, "Received chunk " + chunkIndex + "/" + totalChunks);
            }

            if (reassembler.isComplete()) {
                String fullMessage = reassembler.reassemble();
                if (fullMessage != null) {
                    boolean isSelf = senderId.equals(userId);
                    if (!isSelf) {
                        MessageModel newMsg = new MessageModel(senderId, fullMessage, false, getCurrentTimestamp());
                        addMessage(newMsg);
                        broadcastMessage(newMsg);
                    }
                }
                reassemblers.remove(reassemblerKey);
            }
        }
    }

    public void sendMessageInChunks(String message) {
        try {
            // Add to local storage
            MessageModel sentMsg = new MessageModel(userId, message, true, getCurrentTimestamp());
            addMessage(sentMsg);
            broadcastMessage(sentMsg);

            // Send via BLE using current settings
            String messageId = generateMessageId();
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            List<byte[]> safeChunks = createSafeUtf8Chunks(messageBytes, MAX_CHUNK_DATA_SIZE);
            int totalChunks = safeChunks.size();

            Log.d(TAG, "Sending message in " + totalChunks + " chunks with UUID: " + SERVICE_UUID.toString());

            for (int i = 0; i < totalChunks; i++) {
                final int chunkIndex = i;
                mainHandler.postDelayed(() -> {
                    try {
                        byte[] chunkData = safeChunks.get(chunkIndex);
                        byte[] chunkPayload = createChunkPayload(messageId, chunkIndex, totalChunks, chunkData);
                        updateAdvertisingData(chunkPayload);
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending chunk", e);
                    }
                }, i * DELAY_BETWEEN_CHUNKS_MS);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sending message", e);
        }
    }

    private List<byte[]> createSafeUtf8Chunks(byte[] data, int maxChunkSize) {
        List<byte[]> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            int chunkSize = Math.min(maxChunkSize, data.length - offset);

            // Ensure we don't break UTF-8 characters
            while (chunkSize > 0 && offset + chunkSize < data.length) {
                byte b = data[offset + chunkSize];
                // Check if we're in the middle of a UTF-8 character
                if ((b & 0x80) != 0 && (b & 0x40) == 0) {
                    chunkSize--; // Move back to avoid breaking character
                } else {
                    break;
                }
            }

            byte[] chunk = new byte[chunkSize];
            System.arraycopy(data, offset, chunk, 0, chunkSize);
            chunks.add(chunk);
            offset += chunkSize;
        }

        return chunks;
    }

    private void updateAdvertisingData(byte[] payload) {
        if (advertiser == null) return;

        try {
            if (!hasRequiredPermissions()) {
                Log.w(TAG, "Missing required permissions for advertising");
                return;
            }

            // stopAdvertising();

            // Use the current SERVICE_UUID for advertising
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceData(new ParcelUuid(SERVICE_UUID), payload)
                    .build();

            advertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    Log.d(TAG, "Advertise success with UUID: " + SERVICE_UUID.toString());
                }

                @Override
                public void onStartFailure(int errorCode) {
                    Log.w(TAG, "Advertise failed: " + errorCode);
                }
            };

            try {
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
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException when starting advertising - missing permissions", e);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error advertising", e);
        }
    }

    private void stopAdvertising() {
        if (advertiser != null && advertiseCallback != null) {
            try {
                if (!hasRequiredPermissions()) {
                    Log.w(TAG, "Missing required permissions for stopping advertising");
                    return;
                }
                advertiser.stopAdvertising(advertiseCallback);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException when stopping advertising - missing permissions", e);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping advertising", e);
            }
        }
    }

    private void stopScanning() {
        if (scanner != null && scanCallback != null) {
            try {
                if (!hasRequiredPermissions()) {
                    Log.w(TAG, "Missing required permissions for stopping scan");
                    return;
                }
                scanner.stopScan(scanCallback);
                isScanning = false;
                Log.d(TAG, "Stopped scanning");
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException when stopping scan - missing permissions", e);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping scan", e);
            }
        }
    }

    private void stopBleOperations() {
        stopAdvertising();
        stopScanning();
    }

    private void cleanupExpiredReassemblers() {
        synchronized (reassemblers) {
            Iterator<Map.Entry<String, MessageReassembler>> it = reassemblers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, MessageReassembler> entry = it.next();
                if (entry.getValue().isExpired(CHUNK_TIMEOUT_MS)) {
                    it.remove();
                }
            }
        }
    }

    private void addMessage(MessageModel msg) {
        messageList.add(msg);
        if (messageList.size() > MAX_MESSAGE_SAVED) {
            messageList = new ArrayList<>(messageList.subList(
                    messageList.size() - MAX_MESSAGE_SAVED,
                    messageList.size()
            ));
        }
        saveChatHistory();
    }

    private void broadcastMessage(MessageModel message) {
        Intent intent = new Intent(ACTION_MESSAGE_RECEIVED);
        intent.putExtra(EXTRA_MESSAGE, gson.toJson(message));
        intent.setPackage(getPackageName());
        sendBroadcast(intent);

        updateNotification("Active - " + messageList.size() + " messages", true);
    }

    private void broadcastServiceState(boolean isRunning) {
        Intent intent = new Intent(ACTION_SERVICE_STATE);
        intent.putExtra(EXTRA_IS_RUNNING, isRunning);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void updateNotification(String text, boolean ongoing) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Nearby Chat")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(ongoing)
                .setShowWhen(false);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
        }
    }

    // Helper methods
    private String generateMessageId() {
        int id = (int) (System.currentTimeMillis() % 10000);
        return String.format("%04d", id);
    }

    private byte[] createChunkPayload(String messageId, int chunkIndex, int totalChunks, byte[] chunkData) {
        try {
            byte[] userIdBytes = toAsciiBytes(userIdBits);
            byte[] messageIdBytes = messageId.getBytes(StandardCharsets.UTF_8);

            if (messageIdBytes.length > 4) {
                messageIdBytes = Arrays.copyOf(messageIdBytes, 4);
            } else if (messageIdBytes.length < 4) {
                byte[] temp = new byte[4];
                System.arraycopy(messageIdBytes, 0, temp, 0, messageIdBytes.length);
                messageIdBytes = temp;
            }

            byte[] payload = new byte[USER_ID_LENGTH + 4 + 2 + chunkData.length];

            System.arraycopy(userIdBytes, 0, payload, 0, USER_ID_LENGTH);
            System.arraycopy(messageIdBytes, 0, payload, USER_ID_LENGTH, 4);
            payload[USER_ID_LENGTH + 4] = (byte) chunkIndex;
            payload[USER_ID_LENGTH + 5] = (byte) totalChunks;
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

    private long fromAsciiBytes(byte[] bytes) {
        if (bytes.length != 5) throw new IllegalArgumentException("Invalid ASCII bytes");
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFF);
        }
        return value;
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

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a | dd-MM-yyyy", Locale.getDefault());
        return sdf.format(new Date());
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
                Type type = new TypeToken<List<MessageModel>>(){}.getType();
                List<MessageModel> loaded = gson.fromJson(json, type);
                if (loaded != null) {
                    if (loaded.size() > MAX_MESSAGE_SAVED) {
                        loaded = new ArrayList<>(loaded.subList(
                                loaded.size() - MAX_MESSAGE_SAVED,
                                loaded.size()
                        ));
                    }
                    messageList.clear();
                    messageList.addAll(loaded);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading chat history", e);
        }
    }

    public List<MessageModel> getAllMessages() {
        return new ArrayList<>(messageList);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        isServiceRunning = false;

        stopBleOperations();

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        if (bluetoothReceiver != null) {
            try {
                unregisterReceiver(bluetoothReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
            }
        }

        if (chunkHandler != null) {
            chunkHandler.removeCallbacksAndMessages(null);
        }

        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        saveChatHistory();
        broadcastServiceState(false);

        super.onDestroy();
    }
}