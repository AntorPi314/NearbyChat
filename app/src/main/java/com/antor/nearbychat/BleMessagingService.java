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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
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

import com.antor.nearbychat.Database.AppDatabase;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class BleMessagingService extends Service {

    private static final String TAG = "BleMessagingService";
    private static final String CHANNEL_ID = "nearby_chat_service";
    private static final int NOTIFICATION_ID = 1001;

    private static UUID SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");
    private static final int USER_ID_LENGTH = 5;
    private static final int MESSAGE_ID_LENGTH = 4;
    private static final int CHUNK_METADATA_LENGTH = 2;
    private static final int HEADER_SIZE = USER_ID_LENGTH + MESSAGE_ID_LENGTH + CHUNK_METADATA_LENGTH;

    private static int MAX_PAYLOAD_SIZE = 27;
    private static int MAX_CHUNK_DATA_SIZE = MAX_PAYLOAD_SIZE - HEADER_SIZE;
    private static int ADVERTISING_DURATION_MS = 800;
    private static int DELAY_BETWEEN_CHUNKS_MS = 1000;
    private static int CHUNK_TIMEOUT_MS = 60000;
    private static int CHUNK_CLEANUP_INTERVAL_MS = 10000;
    private static int MAX_RECENT_MESSAGES = 1000;
    private static int MAX_RECENT_CHUNKS = 2000;
    private static int MAX_MESSAGE_SAVED = 500;
    private static final int SCAN_RESTART_DELAY = 100;
    private static final int RETRY_COUNT = 2;
    private static final int RETRY_DELAY = 500;

    public static final String ACTION_MESSAGE_RECEIVED = "com.antor.nearbychat.MESSAGE_RECEIVED";
    public static final String ACTION_MESSAGE_SENT = "com.antor.nearbychat.MESSAGE_SENT";
    public static final String ACTION_SERVICE_STATE = "com.antor.nearbychat.SERVICE_STATE";
    public static final String EXTRA_MESSAGE = "extra_message";
    public static final String EXTRA_IS_RUNNING = "extra_is_running";

    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_USER_ID_BITS = "userIdBits";
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;

    private PowerManager.WakeLock wakeLock;
    private Handler mainHandler;
    private AppDatabase database;
    private com.antor.nearbychat.Database.MessageDao messageDao;

    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private final AtomicBoolean isAdvertising = new AtomicBoolean(false);
    private boolean isServiceRunning = false;
    private boolean isSending = false;
    private boolean isReceiving = false;

    private long userIdBits;
    private String userId;
    private final Gson gson = new Gson();

    private final Map<String, MessageReassembler> reassemblers = new ConcurrentHashMap<>();
    private final Map<String, ChunkInfo> chunkTracker = new ConcurrentHashMap<>();
    private final Set<String> completedMessages = Collections.synchronizedSet(new HashSet<>());
    private final Queue<ChunkData> sendQueue = new LinkedList<>();
    private BroadcastReceiver bluetoothReceiver;

    private final IBinder binder = new LocalBinder();

    private static class ChunkInfo {
        final String messageId;
        final int chunkIndex;
        final long timestamp;
        int receiveCount = 0;

        ChunkInfo(String messageId, int chunkIndex) {
            this.messageId = messageId;
            this.chunkIndex = chunkIndex;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static class ChunkData {
        final String messageId;
        final int chunkIndex;
        final int totalChunks;
        final byte[] data;
        final long senderBits;
        int retryCount = 0;

        ChunkData(String messageId, int chunkIndex, int totalChunks, byte[] data, long senderBits) {
            this.messageId = messageId;
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            this.data = data;
            this.senderBits = senderBits;
        }
    }

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

        public synchronized boolean addChunk(int chunkIndex, int totalChunks, byte[] chunkData) {
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
            int totalSize = 0;
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = chunks.get(i);
                if (chunk != null) {
                    totalSize += chunk.length;
                } else {
                    return null;
                }
            }

            byte[] fullBytes = new byte[totalSize];
            int offset = 0;
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = chunks.get(i);
                System.arraycopy(chunk, 0, fullBytes, offset, chunk.length);
                offset += chunk.length;
            }

            return new String(fullBytes, StandardCharsets.UTF_8);
        }

        public boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - timestamp > timeoutMs;
        }
    }

    public class LocalBinder extends Binder {
        public BleMessagingService getService() {
            return BleMessagingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");

        mainHandler = new Handler(Looper.getMainLooper());
        database = AppDatabase.getInstance(this);
        messageDao = database.messageDao();

        loadConfigurableSettings();
        initializeData();
        acquireWakeLock();
        setupBluetoothReceiver();
        createNotificationChannel();

        startPeriodicCleanup();
        startQueueProcessor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");

        loadConfigurableSettings();

        if (!isServiceRunning) {
            startForegroundService();
            initializeBle();
            isServiceRunning = true;
            broadcastServiceState(true);
        } else {
            restartBle();
        }

        handleIntentMessage(intent);
        return START_STICKY;
    }

    private void startQueueProcessor() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                processSendQueue();
                mainHandler.postDelayed(this, 50);
            }
        });
    }

    private void processSendQueue() {
        if (sendQueue.isEmpty() || isAdvertising.get()) {
            return;
        }

        ChunkData chunk = sendQueue.poll();
        if (chunk != null) {
            sendChunkWithRetry(chunk);
        }
    }

    private void sendChunkWithRetry(ChunkData chunk) {
        byte[] payload = createChunkPayloadWithSender(
                chunk.messageId,
                chunk.chunkIndex,
                chunk.totalChunks,
                chunk.data,
                chunk.senderBits
        );

        advertiseData(payload, () -> {
            if (chunk.retryCount < RETRY_COUNT) {
                chunk.retryCount++;
                mainHandler.postDelayed(() -> sendQueue.offer(chunk), RETRY_DELAY);
            }
        });
    }

    private void advertiseData(byte[] payload, Runnable onComplete) {
        if (advertiser == null || !hasRequiredPermissions()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        if (!isAdvertising.compareAndSet(false, true)) {
            mainHandler.postDelayed(() -> advertiseData(payload, onComplete), 100);
            return;
        }

        pauseScanning();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceData(new ParcelUuid(SERVICE_UUID), payload)
                .build();

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(ADVERTISING_DURATION_MS)
                .build();

        AdvertiseCallback callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                Log.d(TAG, "Advertise started");
                mainHandler.postDelayed(() -> {
                    stopAdvertising(this);
                    isAdvertising.set(false);
                    resumeScanning();
                    if (onComplete != null) onComplete.run();
                }, ADVERTISING_DURATION_MS);
            }

            @Override
            public void onStartFailure(int errorCode) {
                Log.e(TAG, "Advertise failed: " + errorCode);
                isAdvertising.set(false);
                resumeScanning();
                if (onComplete != null) onComplete.run();
            }
        };

        try {
            advertiser.startAdvertising(settings, data, callback);
        } catch (Exception e) {
            Log.e(TAG, "Error starting advertising", e);
            isAdvertising.set(false);
            resumeScanning();
            if (onComplete != null) onComplete.run();
        }
    }

    private void pauseScanning() {
        if (scanner != null && scanCallback != null && isScanning.get()) {
            try {
                scanner.stopScan(scanCallback);
                isScanning.set(false);
            } catch (Exception e) {
                Log.e(TAG, "Error pausing scan", e);
            }
        }
    }

    private void resumeScanning() {
        mainHandler.postDelayed(() -> {
            if (!isScanning.get() && !isAdvertising.get()) {
                startScanning();
            }
        }, SCAN_RESTART_DELAY);
    }

    private void restartBle() {
        stopBleOperations();
        mainHandler.postDelayed(this::initializeBle, 1000);
    }

    private void handleIntentMessage(Intent intent) {
        if (intent == null) return;

        if (intent.hasExtra("message_to_send")) {
            String message = intent.getStringExtra("message_to_send");
            if (message != null) {
                sendMessageInChunks(message);
            }
        } else if (intent.hasExtra("message_to_resend")) {
            String message = intent.getStringExtra("message_to_resend");
            if (message != null) {
                resendMessageWithoutSaving(message, null);
            }
        }
    }

    private void loadConfigurableSettings() {
        try {
            SharedPreferences prefs = getSharedPreferences("NearbyChatSettings", MODE_PRIVATE);

            MAX_PAYLOAD_SIZE = Math.max(20, prefs.getInt("MAX_PAYLOAD_SIZE", 27));
            MAX_CHUNK_DATA_SIZE = MAX_PAYLOAD_SIZE - HEADER_SIZE;

            String uuidString = prefs.getString("SERVICE_UUID", "0000aaaa-0000-1000-8000-00805f9b34fb");
            try {
                SERVICE_UUID = UUID.fromString(uuidString);
            } catch (IllegalArgumentException e) {
                SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");
            }

            ADVERTISING_DURATION_MS = prefs.getInt("ADVERTISING_DURATION_MS", 800);
            DELAY_BETWEEN_CHUNKS_MS = prefs.getInt("DELAY_BETWEEN_CHUNKS_MS", 1000);
            CHUNK_TIMEOUT_MS = prefs.getInt("CHUNK_TIMEOUT_MS", 60000);
            CHUNK_CLEANUP_INTERVAL_MS = prefs.getInt("CHUNK_CLEANUP_INTERVAL_MS", 10000);
            MAX_RECENT_MESSAGES = prefs.getInt("MAX_RECENT_MESSAGES", 1000);
            MAX_RECENT_CHUNKS = prefs.getInt("MAX_RECENT_CHUNKS", 2000);
            MAX_MESSAGE_SAVED = prefs.getInt("MAX_MESSAGE_SAVED", 500);

        } catch (Exception e) {
            Log.e(TAG, "Error loading settings", e);
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
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "NearbyChatService::WakeLock"
            );
            wakeLock.acquire();
        }
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
                mainHandler.postDelayed(this::initializeBle, 500);
                break;
            case BluetoothAdapter.STATE_OFF:
                stopBleOperations();
                break;
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
                .setSmallIcon(R.drawable.ic_nearby_chat)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE);

        Notification notification = builder.build();

        if (Build.VERSION.SDK_INT >= 34) {
            int foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION |
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void initializeBle() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth not available or disabled");
            return;
        }

        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Missing required permissions");
            return;
        }

        try {
            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            scanner = bluetoothAdapter.getBluetoothLeScanner();

            if (scanner != null) {
                startScanning();
            }
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
        if (scanner == null || !isScanning.compareAndSet(false, true)) {
            return;
        }

        try {
            if (!hasRequiredPermissions()) {
                isScanning.set(false);
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
                    isScanning.set(false);
                    mainHandler.postDelayed(() -> startScanning(), 3000);
                }
            };

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            List<ScanFilter> filters = Arrays.asList(
                    new ScanFilter.Builder()
                            .setServiceData(new ParcelUuid(SERVICE_UUID), new byte[0])
                            .build()
            );

            scanner.startScan(filters, settings, scanCallback);
            Log.d(TAG, "Started scanning");
            updateNotification("Scanning for messages...", true);

        } catch (Exception e) {
            Log.e(TAG, "Error starting scan", e);
            isScanning.set(false);
        }
    }

    private void handleScanResult(ScanResult result) {
        try {
            ScanRecord record = result.getScanRecord();
            if (record == null) return;

            Map<ParcelUuid, byte[]> serviceData = record.getServiceData();
            if (serviceData == null) return;

            byte[] data = serviceData.get(new ParcelUuid(SERVICE_UUID));
            if (data == null || data.length < HEADER_SIZE + 1) return;

            processReceivedChunk(data);

        } catch (Exception e) {
            Log.e(TAG, "Error handling scan result", e);
        }
    }

    private void processReceivedChunk(byte[] data) {
        try {
            byte[] userIdBytes = Arrays.copyOfRange(data, 0, USER_ID_LENGTH);
            long senderBits = fromAsciiBytes(userIdBytes);
            String senderId = getUserIdString(senderBits);

            byte[] messageIdBytes = Arrays.copyOfRange(data, USER_ID_LENGTH, USER_ID_LENGTH + MESSAGE_ID_LENGTH);
            String messageId = new String(messageIdBytes, StandardCharsets.UTF_8).trim();

            int chunkIndex = data[USER_ID_LENGTH + MESSAGE_ID_LENGTH] & 0xFF;
            int totalChunks = data[USER_ID_LENGTH + MESSAGE_ID_LENGTH + 1] & 0xFF;

            if (chunkIndex < 0 || chunkIndex >= totalChunks || totalChunks <= 0) {
                return;
            }

            String chunkKey = senderId + "_" + messageId + "_" + chunkIndex;
            ChunkInfo info = chunkTracker.get(chunkKey);

            if (info == null) {
                info = new ChunkInfo(messageId, chunkIndex);
                chunkTracker.put(chunkKey, info);
            } else if (info.receiveCount > 0) {
                return;
            }

            info.receiveCount++;

            byte[] chunkData = Arrays.copyOfRange(data, HEADER_SIZE, data.length);

            if (totalChunks == 1) {
                String message = new String(chunkData, StandardCharsets.UTF_8);
                if (!senderId.equals(userId)) {
                    MessageModel msg = new MessageModel(senderId, message, false, getCurrentTimestamp(1));
                    addMessage(msg);
                }
            } else {
                processMultiChunkMessage(senderId, messageId, chunkIndex, totalChunks, chunkData);
            }

            isReceiving = true;
            updateNotification("", true);
            mainHandler.postDelayed(() -> {
                isReceiving = false;
                updateNotification("", true);
            }, 2000);

        } catch (Exception e) {
            Log.e(TAG, "Error processing chunk", e);
        }
    }

    private void processMultiChunkMessage(String senderId, String messageId, int chunkIndex, int totalChunks, byte[] chunkData) {
        String reassemblerKey = senderId + "_" + messageId;

        MessageReassembler reassembler = reassemblers.computeIfAbsent(reassemblerKey,
                k -> new MessageReassembler(senderId, messageId));

        if (reassembler.addChunk(chunkIndex, totalChunks, chunkData)) {
            Log.d(TAG, "Chunk " + chunkIndex + "/" + totalChunks + " received");
        }

        if (reassembler.isComplete()) {
            String fullMessage = reassembler.reassemble();
            if (fullMessage != null && !senderId.equals(userId)) {
                String completeKey = senderId + "_" + messageId;
                if (!completedMessages.contains(completeKey)) {
                    completedMessages.add(completeKey);
                    MessageModel msg = new MessageModel(senderId, fullMessage, false, getCurrentTimestamp(totalChunks));
                    addMessage(msg);

                    if (completedMessages.size() > MAX_RECENT_MESSAGES) {
                        cleanupOldCompletedMessages();
                    }
                }
            }
            reassemblers.remove(reassemblerKey);
        }
    }

    public void sendMessageInChunks(String message) {
        try {
            isSending = true;
            updateNotification("", true);

            String messageId = generateMessageId();
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            List<byte[]> safeChunks = createSafeUtf8Chunks(messageBytes, MAX_CHUNK_DATA_SIZE);
            int totalChunks = safeChunks.size();

            MessageModel sentMsg = new MessageModel(userId, message, true, getCurrentTimestamp(totalChunks));
            addMessage(sentMsg);

            for (int i = 0; i < totalChunks; i++) {
                ChunkData chunk = new ChunkData(messageId, i, totalChunks, safeChunks.get(i), userIdBits);
                sendQueue.offer(chunk);

                if (i > 0 && i % 3 == 0) {
                    mainHandler.postDelayed(() -> {}, DELAY_BETWEEN_CHUNKS_MS / 2);
                }
            }

            mainHandler.postDelayed(() -> {
                isSending = false;
                updateNotification("", true);
            }, (totalChunks * DELAY_BETWEEN_CHUNKS_MS) + ADVERTISING_DURATION_MS);

        } catch (Exception e) {
            Log.e(TAG, "Error sending message", e);
            isSending = false;
            updateNotification("", true);
        }
    }

    public void forwardMessageWithOriginalSender(String message, String originalSenderId, String originalMessageId) {
        try {
            isSending = true;
            updateNotification("", true);

            String messageId = (originalMessageId != null && !originalMessageId.isEmpty()) ?
                    originalMessageId : generateMessageId();

            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            List<byte[]> safeChunks = createSafeUtf8Chunks(messageBytes, MAX_CHUNK_DATA_SIZE);
            int totalChunks = safeChunks.size();

            long originalSenderBits = getUserIdBits(originalSenderId);

            for (int i = 0; i < totalChunks; i++) {
                ChunkData chunk = new ChunkData(messageId, i, totalChunks, safeChunks.get(i), originalSenderBits);
                sendQueue.offer(chunk);
            }

            mainHandler.postDelayed(() -> {
                isSending = false;
                updateNotification("", true);
            }, (totalChunks * DELAY_BETWEEN_CHUNKS_MS) + ADVERTISING_DURATION_MS);

        } catch (Exception e) {
            Log.e(TAG, "Error forwarding message", e);
            isSending = false;
            updateNotification("", true);
        }
    }

    public void resendMessageWithoutSaving(String message, String originalMessageId) {
        try {
            isSending = true;
            updateNotification("", true);

            String messageId = (originalMessageId != null && !originalMessageId.isEmpty()) ?
                    originalMessageId : generateMessageId();

            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            List<byte[]> safeChunks = createSafeUtf8Chunks(messageBytes, MAX_CHUNK_DATA_SIZE);
            int totalChunks = safeChunks.size();

            Log.d(TAG, "Resending message with original message ID: " + messageId);

            for (int i = 0; i < totalChunks; i++) {
                ChunkData chunk = new ChunkData(messageId, i, totalChunks, safeChunks.get(i), userIdBits);
                sendQueue.offer(chunk);
            }

            mainHandler.postDelayed(() -> {
                isSending = false;
                updateNotification("", true);
            }, (totalChunks * DELAY_BETWEEN_CHUNKS_MS) + ADVERTISING_DURATION_MS);

        } catch (Exception e) {
            Log.e(TAG, "Error resending message", e);
            isSending = false;
            updateNotification("", true);
        }
    }

    private List<byte[]> createSafeUtf8Chunks(byte[] data, int maxChunkSize) {
        List<byte[]> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            int chunkSize = Math.min(maxChunkSize, data.length - offset);

            while (chunkSize > 0 && offset + chunkSize < data.length) {
                byte b = data[offset + chunkSize];
                if ((b & 0x80) != 0 && (b & 0x40) == 0) {
                    chunkSize--;
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

    private byte[] createChunkPayloadWithSender(String messageId, int chunkIndex, int totalChunks, byte[] chunkData, long senderBits) {
        try {
            byte[] userIdBytes = toAsciiBytes(senderBits);
            byte[] messageIdBytes = messageId.getBytes(StandardCharsets.UTF_8);

            if (messageIdBytes.length > MESSAGE_ID_LENGTH) {
                messageIdBytes = Arrays.copyOf(messageIdBytes, MESSAGE_ID_LENGTH);
            } else if (messageIdBytes.length < MESSAGE_ID_LENGTH) {
                byte[] temp = new byte[MESSAGE_ID_LENGTH];
                System.arraycopy(messageIdBytes, 0, temp, 0, messageIdBytes.length);
                messageIdBytes = temp;
            }

            byte[] payload = new byte[HEADER_SIZE + chunkData.length];

            System.arraycopy(userIdBytes, 0, payload, 0, USER_ID_LENGTH);
            System.arraycopy(messageIdBytes, 0, payload, USER_ID_LENGTH, MESSAGE_ID_LENGTH);
            payload[USER_ID_LENGTH + MESSAGE_ID_LENGTH] = (byte) chunkIndex;
            payload[USER_ID_LENGTH + MESSAGE_ID_LENGTH + 1] = (byte) totalChunks;
            System.arraycopy(chunkData, 0, payload, HEADER_SIZE, chunkData.length);

            return payload;
        } catch (Exception e) {
            Log.e(TAG, "Error creating chunk payload", e);
            return new byte[0];
        }
    }

    private void stopAdvertising(AdvertiseCallback callback) {
        if (advertiser != null && callback != null) {
            try {
                if (hasRequiredPermissions()) {
                    advertiser.stopAdvertising(callback);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping advertising", e);
            }
        }
    }

    private void stopScanning() {
        if (scanner != null && scanCallback != null && isScanning.get()) {
            try {
                if (hasRequiredPermissions()) {
                    scanner.stopScan(scanCallback);
                    isScanning.set(false);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping scan", e);
            }
        }
    }

    private void stopBleOperations() {
        stopScanning();
        isAdvertising.set(false);
        sendQueue.clear();
    }

    private void startPeriodicCleanup() {
        Runnable cleanupRunnable = new Runnable() {
            @Override
            public void run() {
                cleanupExpiredReassemblers();
                cleanupOldChunks();
                mainHandler.postDelayed(this, CHUNK_CLEANUP_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(cleanupRunnable, CHUNK_CLEANUP_INTERVAL_MS);
    }

    private void cleanupExpiredReassemblers() {
        Iterator<Map.Entry<String, MessageReassembler>> it = reassemblers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MessageReassembler> entry = it.next();
            if (entry.getValue().isExpired(CHUNK_TIMEOUT_MS)) {
                it.remove();
            }
        }
    }

    private void cleanupOldChunks() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ChunkInfo>> it = chunkTracker.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ChunkInfo> entry = it.next();
            if (now - entry.getValue().timestamp > CHUNK_TIMEOUT_MS) {
                it.remove();
            }
        }

        if (chunkTracker.size() > MAX_RECENT_CHUNKS) {
            List<Map.Entry<String, ChunkInfo>> entries = new ArrayList<>(chunkTracker.entrySet());
            entries.sort((a, b) -> Long.compare(a.getValue().timestamp, b.getValue().timestamp));
            int toRemove = chunkTracker.size() - MAX_RECENT_CHUNKS;
            for (int i = 0; i < toRemove && i < entries.size(); i++) {
                chunkTracker.remove(entries.get(i).getKey());
            }
        }
    }

    private void cleanupOldCompletedMessages() {
        if (completedMessages.size() > MAX_RECENT_MESSAGES) {
            int toRemove = completedMessages.size() - MAX_RECENT_MESSAGES;
            Iterator<String> it = completedMessages.iterator();
            int removed = 0;
            while (it.hasNext() && removed < toRemove) {
                it.next();
                it.remove();
                removed++;
            }
        }
    }

    private void addMessage(MessageModel msg) {
        new Thread(() -> {
            try {
                if (messageDao.messageExists(msg.getSenderId(), msg.getMessage(), msg.getTimestamp()) == 0) {
                    com.antor.nearbychat.Database.MessageEntity entity =
                            com.antor.nearbychat.Database.MessageEntity.fromMessageModel(msg);
                    messageDao.insertMessage(entity);
                    AppDatabase.cleanupOldMessages(this, MAX_MESSAGE_SAVED);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error saving message", e);
            }
        }).start();
    }

    private void broadcastServiceState(boolean isRunning) {
        Intent intent = new Intent(ACTION_SERVICE_STATE);
        intent.putExtra(EXTRA_IS_RUNNING, isRunning);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void updateNotification(String text, boolean ongoing) {
        String statusText = buildNotificationStatus();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Nearby Chat")
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_nearby_chat)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(ongoing)
                .setShowWhen(false);

        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        builder.setContentIntent(pendingIntent);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
        }
    }

    private String buildNotificationStatus() {
        List<String> statuses = new ArrayList<>();

        if (isSending) {
            statuses.add("Sending...");
        }
        if (isReceiving) {
            statuses.add("Receiving...");
        }

        if (statuses.isEmpty()) {
            return "Active";
        } else {
            return String.join(", ", statuses);
        }
    }

    private String generateMessageId() {
        int id = (int) (System.currentTimeMillis() % 10000);
        return String.format("%04d", id);
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

    private long getUserIdBits(String userId) {
        if (userId == null || userId.length() != 8) {
            return System.currentTimeMillis() & ((1L << 40) - 1);
        }

        long bits = 0;
        for (int i = 0; i < 8; i++) {
            char c = userId.charAt(i);
            int index = -1;
            for (int j = 0; j < ALPHABET.length; j++) {
                if (ALPHABET[j] == c) {
                    index = j;
                    break;
                }
            }
            if (index == -1) index = 0;
            bits = (bits << 5) | index;
        }
        return bits;
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

    private String getCurrentTimestamp(int chunkCount) {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a | dd-MM-yyyy", Locale.getDefault());
        String baseTime = sdf.format(new Date());
        return baseTime + " | " + chunkCount + "C";
    }

    public List<MessageModel> getAllMessages() {
        try {
            List<com.antor.nearbychat.Database.MessageEntity> entities = messageDao.getAllMessages();
            List<MessageModel> messages = new ArrayList<>();
            for (com.antor.nearbychat.Database.MessageEntity entity : entities) {
                messages.add(entity.toMessageModel());
            }
            return messages;
        } catch (Exception e) {
            Log.e(TAG, "Error getting messages", e);
            return new ArrayList<>();
        }
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

        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        broadcastServiceState(false);
        super.onDestroy();
    }
}