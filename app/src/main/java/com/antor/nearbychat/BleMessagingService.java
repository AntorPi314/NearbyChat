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

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

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
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class BleMessagingService extends Service {

    private AppDatabase database;
    private com.antor.nearbychat.Database.MessageDao messageDao;

    private static final String TAG = "BleMessagingService";
    private static final String CHANNEL_ID = "nearby_chat_service";
    private static final int NOTIFICATION_ID = 1001;

    // BLE Constants - These will be updated from settings
    private static UUID SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");
    private static final int USER_ID_LENGTH = 5; // 5 ASCII characters
    private static final int MESSAGE_ID_LENGTH = 5; // 5 ASCII characters (changed from 4)
    private static final int CHUNK_METADATA_LENGTH = 2;
    private static final int HEADER_SIZE = USER_ID_LENGTH + MESSAGE_ID_LENGTH + CHUNK_METADATA_LENGTH; // Now 12 bytes

    private static final String KEY_GROUPS_LIST = "groupsList"; // Or your desired preference key
    private static final String KEY_FRIENDS_LIST = "friendsList"; // Or your desired preference key

    // Dynamic settings - will be loaded from preferences
    private static int MAX_PAYLOAD_SIZE = 27;
    private static int MAX_CHUNK_DATA_SIZE = MAX_PAYLOAD_SIZE - HEADER_SIZE;
    private static int ADVERTISING_DURATION_MS = 1600;
    private static int DELAY_BETWEEN_CHUNKS_MS = 1800;
    private static int CHUNK_TIMEOUT_MS = 60000;
    private static int CHUNK_CLEANUP_INTERVAL_MS = 10000;
    private static int MAX_RECENT_MESSAGES = 1000;
    private static int MAX_RECENT_CHUNKS = 2000;
    private static int MAX_MESSAGE_SAVED = 500;


    private static final int MAX_CHUNK_INDEX = 30;
    private static final String REQUEST_MARKER = "??";

    private static final int REQUEST_HEADER_SIZE = USER_ID_LENGTH + MESSAGE_ID_LENGTH + 2; // 11 bytes
    private static final int MAX_MISSING_CHUNKS = 30;
    // private static final int MAX_PAYLOAD_SIZE = (MAX_MISSING_CHUNKS + 1) / 2; // 15 bytes
    private static final int MAX_REQUEST_MESSAGE_SIZE = REQUEST_HEADER_SIZE + MAX_PAYLOAD_SIZE; // 26 bytes

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
    private boolean isReceiving = false;
    private boolean isSending = false;
    private boolean lastAdvertiseSuccessful = true;
    private volatile boolean isAdvertising = false;

    // Data Management
    private long userIdBits;
    private String userId;
    private final Set<String> receivedMessages = new HashSet<>();
    private final Map<String, MessageReassembler> reassemblers = new HashMap<>();
    private final Gson gson = new Gson();
    private List<MessageModel> messageList = new ArrayList<>();

    // Bluetooth State Receiver
    private BroadcastReceiver bluetoothReceiver;

    private int retryCount = 0;
    private static final int MAX_RETRIES = 3;
    private final Set<String> processedMessageIds = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long> messageTimestamps = Collections.synchronizedMap(new HashMap<>());

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
            ADVERTISING_DURATION_MS = prefs.getInt("ADVERTISING_DURATION_MS", 1600);
            DELAY_BETWEEN_CHUNKS_MS = prefs.getInt("DELAY_BETWEEN_CHUNKS_MS", 1800);
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

    public static String timestampToDisplayId(long timestamp) {
        long bits40 = timestamp & ((1L << 40) - 1);
        StringBuilder sb = new StringBuilder();
        long temp = bits40;
        for (int i = 0; i < 8; i++) {
            int index = (int) (temp & 0b11111);
            sb.append(ALPHABET[index]);
            temp >>= 5;
        }
        return sb.reverse().toString();
    }

    public static String timestampToAsciiId(long timestamp) {
        long bits40 = timestamp & ((1L << 40) - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 4; i >= 0; i--) {
            int byteValue = (int) ((bits40 >> (i * 8)) & 0xFF);
            sb.append((char) byteValue);
        }
        return sb.toString();
    }

    // Add this entire method anywhere inside the BleMessagingService class
    private String getPasswordForChat(String chatType, String chatId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Gson gson = new Gson();

        if ("G".equals(chatType)) {
            String groupsJson = prefs.getString(KEY_GROUPS_LIST, null);
            if (groupsJson != null) {
                Type type = new TypeToken<List<GroupModel>>() {
                }.getType();
                List<GroupModel> groups = gson.fromJson(groupsJson, type);
                for (GroupModel g : groups) {
                    if (g.getId().equals(chatId)) {
                        // If the key is empty, use the groupId as the password
                        return g.getEncryptionKey().isEmpty() ? g.getId() : g.getEncryptionKey();
                    }
                }
            }
            return chatId; // Fallback to groupId
        } else if ("F".equals(chatType)) {
            String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
            if (friendsJson != null) {
                Type type = new TypeToken<List<FriendModel>>() {
                }.getType();
                List<FriendModel> friends = gson.fromJson(friendsJson, type);
                String friendDisplayId = timestampToDisplayId(asciiIdToTimestamp(chatId));

                for (FriendModel f : friends) {
                    if (f.getDisplayId().equals(friendDisplayId)) {
                        // If key is empty, use the SENDER's ID (your own ID) as the password
                        return f.getEncryptionKey().isEmpty() ? userId : f.getEncryptionKey();
                    }
                }
            }
            // Fallback for friends is the sender's own ID
            return userId;
        }
        return ""; // No password for Nearby chat
    }

    private long asciiIdToTimestamp(String asciiId) {
        if (asciiId.length() != 5) return 0;
        long bits40 = 0;
        for (int i = 0; i < 5; i++) {
            bits40 = (bits40 << 8) | (asciiId.charAt(i) & 0xFF);
        }
        return bits40;
    }

    public static long displayIdToTimestamp(String displayId) {
        if (displayId.length() != 8) return 0;
        long bits40 = 0;
        for (int i = 0; i < 8; i++) {
            char c = displayId.charAt(i);
            int index = -1;
            for (int j = 0; j < ALPHABET.length; j++) {
                if (ALPHABET[j] == c) {
                    index = j;
                    break;
                }
            }
            if (index == -1) index = 0;
            bits40 = (bits40 << 5) | index;
        }
        return bits40;
    }

    private long reconstructFullTimestamp(long timestampBits40) {
        long currentMs = System.currentTimeMillis();
        long currentHigh = currentMs & ~((1L << 40) - 1);
        return currentHigh | timestampBits40;
    }

    private String formatTimestamp(long timestampMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a | dd-MM-yyyy", Locale.getDefault());
        return sdf.format(new Date(timestampMs));
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
            if (!isComplete()) return null;

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

        public String getPartialMessage() {
            if (receivedCount == 0) return null;

            // Find consecutive chunks starting from 0
            List<byte[]> consecutiveChunks = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = chunks.get(i);
                if (chunk != null) {
                    consecutiveChunks.add(chunk);
                } else {
                    break; // Stop at first missing chunk
                }
            }

            if (consecutiveChunks.isEmpty()) return null;

            // Combine consecutive chunks
            int totalSize = 0;
            for (byte[] chunk : consecutiveChunks) {
                totalSize += chunk.length;
            }

            byte[] partialBytes = new byte[totalSize];
            int offset = 0;
            for (byte[] chunk : consecutiveChunks) {
                System.arraycopy(chunk, 0, partialBytes, offset, chunk.length);
                offset += chunk.length;
            }

            return new String(partialBytes, StandardCharsets.UTF_8);
        }

        public List<Integer> getMissingChunks() {
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                if (chunks.get(i) == null) {
                    missing.add(i);
                }
            }
            return missing;
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

        database = AppDatabase.getInstance(this);
        messageDao = database.messageDao();

        loadConfigurableSettings();
        initializeData();
        acquireWakeLock();
        setupBluetoothReceiver();
        createNotificationChannel();

        // Start periodic cleanup with Runnable
        Runnable cleanupRunnable = new Runnable() {
            @Override
            public void run() {
                cleanupExpiredReassemblers();
                mainHandler.postDelayed(this, CHUNK_CLEANUP_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(cleanupRunnable, CHUNK_CLEANUP_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");

        // Check permissions before doing anything
        if (!hasAllRequiredServicePermissions()) {
            Log.e(TAG, "Missing required permissions, cannot start foreground service");
            // Stop the service gracefully
            stopSelf();
            return START_NOT_STICKY;
        }

        // Reload settings each time the service starts (important for restart)
        loadConfigurableSettings();

        if (!isServiceRunning) {
            try {
                startForegroundService();
                initializeBle();
                isServiceRunning = true;
                broadcastServiceState(true);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException starting foreground service", e);
                stopSelf();
                return START_NOT_STICKY;
            }
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
        if (intent != null) {
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
                .setSmallIcon(R.drawable.ic_nearby_chat)
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

    private void resetBleState() {
        isScanning = false;
        isAdvertising = false;
        retryCount = 0;
    }

    public void forwardMessageWithOriginalSender(String message, String originalSenderId, String originalMessageId) {
        try {
            isSending = true;
            updateNotification("", true); // Update notification to show "Sending..."

            String messageId = (originalMessageId != null && !originalMessageId.isEmpty()) ?
                    originalMessageId : generateMessageId();

            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            List<byte[]> safeChunks = createSafeUtf8Chunks(messageBytes, MAX_CHUNK_DATA_SIZE);
            int totalChunks = safeChunks.size();

            long originalSenderBits = getUserIdBits(originalSenderId);

            Log.d(TAG, "Forwarding message from " + originalSenderId + " with original message ID: " + messageId);

            for (int i = 0; i < totalChunks; i++) {
                final int chunkIndex = i;
                mainHandler.postDelayed(() -> {
                    try {
                        byte[] chunkData = safeChunks.get(chunkIndex);
                        byte[] chunkPayload = createChunkPayloadWithSender(messageId, chunkIndex, totalChunks, chunkData, originalSenderBits);
                        updateAdvertisingData(chunkPayload);

                        // If this is the last chunk, stop showing "Sending..." status
                        if (chunkIndex == totalChunks - 1) {
                            mainHandler.postDelayed(() -> {
                                isSending = false;
                                updateNotification("", true);

                                // *** ADDED THIS LINE TO RESTART SCANNING ***
                                restartScanning();

                            }, ADVERTISING_DURATION_MS + 500);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error forwarding chunk", e);
                    }
                }, i * DELAY_BETWEEN_CHUNKS_MS);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error forwarding message", e);
            isSending = false;
            updateNotification("", true);
        }
    }

    private byte[] createChunkPayloadWithSender(String messageId, int chunkIndex, int totalChunks, byte[] chunkData, long senderBits) {
        try {
            byte[] userIdBytes = toAsciiBytes(senderBits); // Use original sender's bits
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
            Log.e(TAG, "Error creating chunk payload with sender", e);
            return new byte[0];
        }
    }

    private long getUserIdBits(String userId) {
        // Convert userId string back to bits using the same algorithm
        if (userId == null || userId.length() != 8) {
            return System.currentTimeMillis() & ((1L << 40) - 1); // fallback
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
            if (index == -1) index = 0; // fallback for invalid char
            bits = (bits << 5) | index;
        }
        return bits;
    }

    public void resendMessageWithoutSaving(String message, String originalMessageId) {
        try {
            isSending = true;
            updateNotification("", true); // Update notification to show "Sending..."

            String messageId = (originalMessageId != null && !originalMessageId.isEmpty()) ?
                    originalMessageId : generateMessageId();

            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            List<byte[]> safeChunks = createSafeUtf8Chunks(messageBytes, MAX_CHUNK_DATA_SIZE);
            int totalChunks = safeChunks.size();

            Log.d(TAG, "Resending message with original message ID: " + messageId);

            for (int i = 0; i < totalChunks; i++) {
                final int chunkIndex = i;
                mainHandler.postDelayed(() -> {
                    try {
                        byte[] chunkData = safeChunks.get(chunkIndex);
                        byte[] chunkPayload = createChunkPayload(messageId, chunkIndex, totalChunks, chunkData);
                        updateAdvertisingData(chunkPayload);

                        // If this is the last chunk, stop showing "Sending..." status
                        if (chunkIndex == totalChunks - 1) {
                            mainHandler.postDelayed(() -> {
                                isSending = false;
                                updateNotification("", true);

                                // *** ADDED THIS LINE TO RESTART SCANNING ***
                                restartScanning();

                            }, ADVERTISING_DURATION_MS + 500);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error resending chunk", e);
                    }
                }, i * DELAY_BETWEEN_CHUNKS_MS);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resending message", e);
            isSending = false;
            updateNotification("", true);
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

    // ADD this method:
    private void restartScanning() {
        Log.d(TAG, "Restarting scanning...");
        stopScanning();
        mainHandler.postDelayed(() -> {
            if (!isScanning) {
                startScanning();
            }
        }, 1000); // Increased delay to 1 second
    }

    private void initializeData() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        userIdBits = prefs.getLong(KEY_USER_ID_BITS, -1);
        if (userIdBits == -1) {
            userIdBits = System.currentTimeMillis() & ((1L << 40) - 1);
            prefs.edit().putLong(KEY_USER_ID_BITS, userIdBits).apply();
        }
        userId = timestampToDisplayId(userIdBits); // 8-character display format
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
                    // ADD retry logic:
                    if (retryCount < MAX_RETRIES) {
                        retryCount++;
                        mainHandler.postDelayed(() -> {
                            Log.d(TAG, "Retrying scan, attempt: " + retryCount);
                            startScanning();
                        }, 3000);
                    }
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

    // BleMessagingService.java

    private void handleScanResult(ScanResult result) {
        try {
            ScanRecord record = result.getScanRecord();
            if (record == null) {
                Log.d(TAG, "Scan record is null");
                return;
            }

            Map<ParcelUuid, byte[]> serviceData = record.getServiceData();
            if (serviceData == null) {
                Log.d(TAG, "Service data is null");
                return;
            }

            byte[] data = serviceData.get(new ParcelUuid(SERVICE_UUID));
            if (data == null) {
                Log.d(TAG, "No data for our service UUID");
                return;
            }

            if (data.length < 12) {
                Log.d(TAG, "Data too short: " + data.length + " bytes");
                return;
            }

            Log.d(TAG, "=== RECEIVED BLE DATA ===");
            Log.d(TAG, "Data length: " + data.length + " bytes");
            Log.d(TAG, "Processing message...");

            mainHandler.post(() -> processReceivedMessage(data));

        } catch (Exception e) {
            Log.e(TAG, "Error handling scan result", e);
        }
    }


    private void processReceivedMessage(byte[] receivedData) {
        try {
            isReceiving = true;
            updateNotification("", true);

            mainHandler.postDelayed(() -> {
                isReceiving = false;
                updateNotification("", true);
            }, 2000);

            // Extract user ID and message ID (10 bytes total)
            String asciiUserId = new String(receivedData, 0, 5, StandardCharsets.ISO_8859_1);
            long userIdBits40 = asciiIdToTimestamp(asciiUserId);
            String displayUserId = timestampToDisplayId(userIdBits40);

            String asciiMessageId = new String(receivedData, 5, 5, StandardCharsets.ISO_8859_1);
            long messageIdBits40 = asciiIdToTimestamp(asciiMessageId);
            String displayMessageId = timestampToDisplayId(messageIdBits40);

            int chunkIndex = receivedData[10] & 0xFF;
            int totalChunks = receivedData[11] & 0xFF;

            byte[] chunkDataBytes = Arrays.copyOfRange(receivedData, 12, receivedData.length);

            boolean isSelf = displayUserId.equals(userId);
            if (isSelf) {
                Log.d(TAG, "Ignoring own message");
                return;
            }

            // Check for missing parts request
            if (chunkIndex == 255 && totalChunks == 255) {
                String requestData = new String(chunkDataBytes, StandardCharsets.UTF_8);
                handleMissingPartsRequest(requestData);
                return;
            }

            // Handle single-chunk messages
            if (totalChunks == 1) {
                String fullData = new String(chunkDataBytes, StandardCharsets.UTF_8);

                // Parse 6-char header: 1 char type + 5 char ID
                String chatType = "N";
                String chatId = "";
                String actualMessage = fullData;

                if (fullData.length() >= 6) {
                    chatType = fullData.substring(0, 1);
                    chatId = fullData.substring(1, 6).trim(); // Trim padding spaces
                    actualMessage = fullData.substring(6);
                }

                String chunkKey = displayUserId + "_" + displayMessageId + "_0";
                synchronized (receivedMessages) {
                    if (receivedMessages.contains(chunkKey)) {
                        Log.d(TAG, "Duplicate message");
                        return;
                    }
                    receivedMessages.add(chunkKey);
                    messageTimestamps.put(chunkKey, System.currentTimeMillis());
                }

                if (chatType.equals("F")) {
                    chatId = asciiUserId; // 5-char ASCII format
                }

                MessageModel newMsg = new MessageModel(
                        displayUserId,
                        actualMessage,
                        false,
                        getCurrentTimestamp(1),
                        userIdBits40,
                        messageIdBits40
                );
                newMsg.setMessageId(displayMessageId);
                newMsg.setIsComplete(true);
                newMsg.setChatType(chatType);
                newMsg.setChatId(chatId);
                addMessage(newMsg);
                Log.d(TAG, "Single message received from " + displayUserId);
            } else {
                // Multi-chunk handling
                String chunkKey = displayUserId + "_" + displayMessageId + "_" + chunkIndex;
                synchronized (receivedMessages) {
                    if (receivedMessages.contains(chunkKey)) {
                        Log.d(TAG, "Duplicate chunk");
                        return;
                    }
                    receivedMessages.add(chunkKey);
                    messageTimestamps.put(chunkKey, System.currentTimeMillis());
                }

                processMessageChunk(displayUserId, displayMessageId, chunkIndex, totalChunks,
                        chunkDataBytes, userIdBits40, messageIdBits40);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing message", e);
            isReceiving = false;
            updateNotification("", true);
        }
    }

    private void handleMissingPartsRequest(String requestData) {
        try {
            if (requestData.length() < 13) return; // userId(8) + messageId(4) + encoded(1)

            String requesterId = requestData.substring(0, 8);
            String requestedMessageId = requestData.substring(8, 12);
            char encodedChar = requestData.charAt(12);

            List<Integer> missingChunks = decodeMissingChunks(encodedChar);

            Log.d(TAG, "Missing parts request from " + requesterId + " for message " + requestedMessageId +
                    ", missing chunks: " + missingChunks);

            // Find and resend specific chunks
            new Thread(() -> {
                try {
                    List<com.antor.nearbychat.Database.MessageEntity> messages = messageDao.getAllMessages();
                    for (com.antor.nearbychat.Database.MessageEntity entity : messages) {
                        if (requestedMessageId.equals(entity.messageId) && entity.isSelf) {
                            String originalMessage = entity.message;

                            mainHandler.post(() -> {
                                resendSpecificChunks(originalMessage, requestedMessageId, missingChunks);
                            });
                            break;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling missing parts request", e);
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Error parsing missing parts request", e);
        }
    }

    private void resendSpecificChunks(String message, String messageId, List<Integer> chunksToSend) {
        try {
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            List<byte[]> safeChunks = createSafeUtf8Chunks(messageBytes, MAX_CHUNK_DATA_SIZE);
            int totalChunks = safeChunks.size();

            Log.d(TAG, "Resending chunks " + chunksToSend + " for message " + messageId);

            for (int i = 0; i < chunksToSend.size(); i++) {
                int chunkIndex = chunksToSend.get(i);
                if (chunkIndex < safeChunks.size()) {
                    final int finalIndex = chunkIndex;
                    mainHandler.postDelayed(() -> {
                        try {
                            byte[] chunkData = safeChunks.get(finalIndex);
                            byte[] chunkPayload = createChunkPayload(messageId, finalIndex, totalChunks, chunkData);
                            updateAdvertisingData(chunkPayload);
                            Log.d(TAG, "Resent chunk " + finalIndex + "/" + totalChunks);
                        } catch (Exception e) {
                            Log.e(TAG, "Error resending chunk", e);
                        }
                    }, i * DELAY_BETWEEN_CHUNKS_MS);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error resending specific chunks", e);
        }
    }

    private void handleChunkRequest(String requestMessage, String requesterId) {
        try {
            // Parse: "REQUEST_CHUNKS:messageId:[1, 3, 5]"
            String[] parts = requestMessage.split(":");
            if (parts.length < 3) return;

            String requestedMessageId = parts[1];
            String missingChunksStr = parts[2];

            Log.d(TAG, "Received chunk request for message " + requestedMessageId + " from " + requesterId);

            // Find the original message in database
            new Thread(() -> {
                try {
                    List<com.antor.nearbychat.Database.MessageEntity> messages = messageDao.getAllMessages();
                    for (com.antor.nearbychat.Database.MessageEntity entity : messages) {
                        if (requestedMessageId.equals(entity.messageId) && entity.isSelf) {
                            // Found our sent message, resend the requested chunks
                            String originalMessage = entity.message;

                            mainHandler.post(() -> {
                                Log.d(TAG, "Resending message chunks for: " + requestedMessageId);
                                resendMessageWithoutSaving(originalMessage, requestedMessageId);
                            });
                            break;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling chunk request", e);
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Error parsing chunk request", e);
        }
    }

    private void processMessageChunk(String senderId, String messageId, int chunkIndex,
                                     int totalChunks, byte[] chunkData,
                                     long senderTimestampBits, long messageTimestampBits) {
        String reassemblerKey = senderId + "_" + messageId;

        synchronized (reassemblers) {
            MessageReassembler reassembler = reassemblers.get(reassemblerKey);
            if (reassembler == null) {
                reassembler = new MessageReassembler(senderId, messageId);
                reassemblers.put(reassemblerKey, reassembler);
            }

            boolean chunkAdded = reassembler.addChunk(chunkIndex, totalChunks, chunkData);
            if (chunkAdded) {
                Log.d(TAG, "Received chunk " + chunkIndex + "/" + totalChunks + " for message " + messageId);
            }

            if (reassembler.isComplete()) {
                String fullData = reassembler.reassemble();
                if (fullData != null) {
                    // Parse 6-char header from complete message
                    String chatType = "N";
                    String chatId = "";
                    String actualMessage = fullData;

                    if (fullData.length() >= 6) {
                        chatType = fullData.substring(0, 1);
                        chatId = fullData.substring(1, 6).trim();
                        actualMessage = fullData.substring(6);
                    }

                    if (chatType.equals("F")) {
                        // **FIX START**: Use the 5-char ASCII ID derived from senderTimestampBits.
                        // The original code incorrectly used 'senderId', which is the 8-char display ID.
                        chatId = timestampToAsciiId(senderTimestampBits);
                        // **FIX END**
                    }
                    deletePartialMessage(senderId, messageId);

                    MessageModel newMsg = new MessageModel(
                            senderId,
                            actualMessage,
                            false,
                            getCurrentTimestamp(totalChunks, messageTimestampBits),
                            senderTimestampBits,
                            messageTimestampBits
                    );
                    newMsg.setMessageId(messageId);
                    newMsg.setIsComplete(true);
                    newMsg.setChatType(chatType);
                    newMsg.setChatId(chatId);
                    addMessage(newMsg);
                    Log.d(TAG, "Complete message assembled");
                }
                reassemblers.remove(reassemblerKey);
            } else {
                // ... (rest of the method is unchanged)
                boolean shouldShowPartial = (System.currentTimeMillis() - reassembler.timestamp > 8000) ||
                        (reassembler.receivedCount >= Math.max(1, totalChunks / 2));

                if (shouldShowPartial) {
                    String partialMessage = reassembler.getPartialMessage();
                    if (partialMessage != null && !partialMessage.isEmpty()) {

                        String chatType = "N";
                        String chatId = "";

                        if (partialMessage.length() >= 6) {
                            chatType = partialMessage.substring(0, 1);
                            chatId = partialMessage.substring(1, 6).trim();
                            partialMessage = partialMessage.substring(6);
                        }

                        // Also apply the fix here for consistency with partial messages.
                        if (chatType.equals("F")) {
                            chatId = timestampToAsciiId(senderTimestampBits);
                        }

                        updateOrAddPartialMessage(senderId, messageId, partialMessage,
                                totalChunks, messageTimestampBits, senderTimestampBits,
                                reassembler.getMissingChunks(), chatType, chatId);
                    }
                }
            }
        }
    }

    private void deletePartialMessage(String senderId, String messageId) {
        new Thread(() -> {
            try {
                messageDao.deletePartialMessage(senderId, messageId);
            } catch (Exception e) {
                Log.e(TAG, "Error deleting partial message", e);
            }
        }).start();
    }

    private void updateOrAddPartialMessage(String senderId, String messageId, String partialMessage,
                                           int totalChunks, long messageTimestampBits,
                                           long senderTimestampBits, List<Integer> missingChunks,
                                           String chatType, String chatId) {
        new Thread(() -> {
            try {
                // Check if partial message exists
                if (messageDao.partialMessageExists(senderId, messageId) > 0) {
                    // Update existing partial
                    messageDao.updatePartialMessage(senderId, messageId, partialMessage + "...");
                } else {
                    // Add new partial
                    MessageModel partialMsg = new MessageModel(
                            senderId,
                            partialMessage + "...",
                            false,
                            getCurrentTimestamp(totalChunks, messageTimestampBits),
                            senderTimestampBits,
                            messageTimestampBits
                    );
                    partialMsg.setMessageId(messageId);
                    partialMsg.setIsComplete(false);
                    partialMsg.setMissingChunks(missingChunks);
                    partialMsg.setChatType(chatType);
                    partialMsg.setChatId(chatId);

                    com.antor.nearbychat.Database.MessageEntity entity =
                            com.antor.nearbychat.Database.MessageEntity.fromMessageModel(partialMsg);
                    messageDao.insertMessage(entity);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating partial message", e);
            }
        }).start();
    }

    public void sendMissingPartsRequest(String targetUserId, String messageId, List<Integer> missingChunkIndices) {
        if (targetUserId == null || targetUserId.length() != USER_ID_LENGTH ||
                messageId == null || messageId.length() != MESSAGE_ID_LENGTH) {
            return;
        }

        String header = targetUserId + messageId + REQUEST_MARKER;

        int numIndices = Math.min(missingChunkIndices.size(), MAX_MISSING_CHUNKS);
        byte[] payload = new byte[(numIndices + 1) / 2];
        int payloadIndex = 0;

        for (int i = 0; i < numIndices; i += 2) {
            int index1 = missingChunkIndices.get(i);
            // যদি বিজোড় সংখ্যক ইন্ডেক্স থাকে, তবে দ্বিতীয়টি 0 (0000) হবে।
            int index2 = (i + 1 < numIndices) ? missingChunkIndices.get(i + 1) : 0;

            // 4-bit packing: (Index1_4bits << 4) | Index2_4bits
            int encodedByte = ((index1 & 0x0F) << 4) | (index2 & 0x0F);
            payload[payloadIndex++] = (byte) encodedByte;
        }

        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[headerBytes.length + payload.length];
        System.arraycopy(headerBytes, 0, data, 0, headerBytes.length);
        System.arraycopy(payload, 0, data, headerBytes.length, payload.length);

        Log.d(TAG, "Missing Parts Request Generated. Total length: " + data.length);

        // TODO: আপনার startAdvertisingChunk(data) ফাংশনটি এখানে কল করুন
    }

    private void processReceivedData(byte[] data) {
        if (data == null || data.length < REQUEST_HEADER_SIZE) {
            return;
        }

        // Missing Parts Request Check (Variable length: 12 থেকে 26 বাইট)
        if (data.length >= REQUEST_HEADER_SIZE + 1 && data.length <= MAX_REQUEST_MESSAGE_SIZE) {
            try {
                String requestHeader = new String(data, 0, REQUEST_HEADER_SIZE, StandardCharsets.UTF_8);
                String requestMarker = requestHeader.substring(USER_ID_LENGTH + MESSAGE_ID_LENGTH);

                if (REQUEST_MARKER.equals(requestMarker)) {

                    String requestingUserId = requestHeader.substring(0, USER_ID_LENGTH);
                    String messageId = requestHeader.substring(USER_ID_LENGTH, USER_ID_LENGTH + MESSAGE_ID_LENGTH);

                    Set<Integer> indicesToResend = new HashSet<>();
                    int payloadLength = data.length - REQUEST_HEADER_SIZE;

                    // এনকোড করা বাইটগুলো ডিকোড করা
                    for (int i = 0; i < payloadLength; i++) {
                        int encodedByte = data[REQUEST_HEADER_SIZE + i] & 0xFF;

                        int index1 = (encodedByte >> 4) & 0x0F;
                        int index2 = encodedByte & 0x0F;

                        if (index1 > 0) indicesToResend.add(index1);
                        if (index2 > 0) indicesToResend.add(index2);
                    }

                    Log.d(TAG, "MISSED PARTS REQUEST received for Msg ID: " + messageId +
                            " | Chunks to Resend Count: " + indicesToResend.size());

                    // TODO: এখানে আপনার resendChunks(messageId, new java.util.ArrayList<>(indicesToResend)) ফাংশনটি কল করুন

                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing potential missing parts request", e);
            }
        }

        // Regular Chunk Logic (যদি রিকোয়েস্ট মেসেজ না হয়)
        if (data.length > 0) {
            // আপনার বিদ্যমান নিয়মিত চাঙ্ক হ্যান্ডলিং লজিক এখানে থাকবে
        }
    }

    private String encodeMissingChunks(List<Integer> missingChunks) {
        int encoded = 0;
        for (int chunk : missingChunks) {
            if (chunk < 8) {
                encoded |= (1 << chunk);
            }
        }
        return String.valueOf((char) (encoded + 32)); // Convert to printable ASCII
    }

    private List<Integer> decodeMissingChunks(char encodedChar) {
        List<Integer> missing = new ArrayList<>();
        int encoded = encodedChar - 32;
        for (int i = 0; i < 8; i++) {
            if ((encoded & (1 << i)) != 0) {
                missing.add(i);
            }
        }
        return missing;
    }

    // In BleMessagingService.java

    public void sendMessageInChunks(String messageWithHeader) {
        try {
            Log.d(TAG, "=== SENDING MESSAGE ===");
            Log.d(TAG, "Full message with header (length " + messageWithHeader.length() + "): " + messageWithHeader);

            isSending = true;
            updateNotification("", true);

            if (messageWithHeader.length() < 6) {
                Log.e(TAG, "Invalid message format, header missing.");
                isSending = false;
                return;
            }

            String chatType = messageWithHeader.substring(0, 1);
            String chatId = messageWithHeader.substring(1, 6).trim();
            String actualMessage = messageWithHeader.substring(6);

            // --- START ENCRYPTION LOGIC ---
            String password = getPasswordForChat(chatType, chatId);
            String encryptedMessage = CryptoUtils.encrypt(actualMessage, password);
            String messageToSend = chatType + String.format("%-5s", chatId) + encryptedMessage;
            // --- END ENCRYPTION LOGIC ---

            Log.d(TAG, "Chat Type: " + chatType);
            Log.d(TAG, "Chat ID: [" + chatId + "]");
            Log.d(TAG, "Original Message: " + actualMessage);
            Log.d(TAG, "Encrypted Message: " + encryptedMessage);

            // Generate ID and timestamp ONCE and make them final for the lambda.
            final String messageId = generateMessageId();
            long currentTime = System.currentTimeMillis();
            long messageIdBits40 = currentTime & ((1L << 40) - 1);

            // Use the encrypted message to create the byte array for chunking.
            byte[] messageBytes = messageToSend.getBytes(StandardCharsets.UTF_8);
            List<byte[]> safeChunks = createSafeUtf8Chunks(messageBytes, MAX_CHUNK_DATA_SIZE);
            final int totalChunks = safeChunks.size();

            Log.d(TAG, "Total chunks to send: " + totalChunks);

            MessageModel sentMsg = new MessageModel(
                    userId,
                    actualMessage, // Save the unencrypted message to the local DB
                    true,
                    getCurrentTimestamp(totalChunks, messageIdBits40),
                    userIdBits,
                    messageIdBits40
            );
            sentMsg.setMessageId(timestampToDisplayId(messageIdBits40));
            sentMsg.setChatType(chatType);
            sentMsg.setChatId(chatId);
            addMessage(sentMsg);

            Log.d(TAG, "Sending message in " + totalChunks + " chunks with UUID: " + SERVICE_UUID.toString());

            for (int i = 0; i < totalChunks; i++) {
                final int chunkIndex = i;
                mainHandler.postDelayed(() -> {
                    try {
                        byte[] chunkData = safeChunks.get(chunkIndex);
                        // Now 'messageId' and 'totalChunks' are final.
                        byte[] chunkPayload = createChunkPayload(messageId, chunkIndex, totalChunks, chunkData);
                        Log.d(TAG, "Sending chunk " + chunkIndex + "/" + totalChunks + " (size: " + chunkPayload.length + " bytes)");
                        updateAdvertisingData(chunkPayload);

                        if (chunkIndex == totalChunks - 1) {
                            mainHandler.postDelayed(() -> {
                                isSending = false;
                                updateNotification("", true);
                                Log.d(TAG, "All chunks sent, restarting scanning");
                                restartScanning();
                            }, ADVERTISING_DURATION_MS + 1000);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending chunk " + chunkIndex, e);
                    }
                }, i * DELAY_BETWEEN_CHUNKS_MS);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sending message", e);
            isSending = false;
            updateNotification("", true);
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

            // Stop any existing advertising first
            if (isAdvertising && advertiseCallback != null) {
                try {
                    advertiser.stopAdvertising(advertiseCallback);
                    isAdvertising = false;
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping previous advertising", e);
                }
            }

            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceData(new ParcelUuid(SERVICE_UUID), payload)
                    .build();

            advertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    isAdvertising = true;
                    lastAdvertiseSuccessful = true;
                    Log.d(TAG, "Advertise success for payload size: " + payload.length);
                }

                @Override
                public void onStartFailure(int errorCode) {
                    isAdvertising = false;
                    lastAdvertiseSuccessful = false;
                    Log.e(TAG, "Advertise failed with error: " + errorCode);
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

                // Stop advertising after timeout
                mainHandler.postDelayed(() -> {
                    if (isAdvertising) {
                        stopAdvertising();
                        isAdvertising = false;
                    }
                }, ADVERTISING_DURATION_MS + 500);

            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException when starting advertising", e);
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
        new Thread(() -> {
            try {
                // Check if message already exists to prevent duplicates
                if (messageDao.messageExists(msg.getSenderId(), msg.getMessage(), msg.getTimestamp()) == 0) {
                    com.antor.nearbychat.Database.MessageEntity entity = com.antor.nearbychat.Database.MessageEntity.fromMessageModel(msg);
                    messageDao.insertMessage(entity);

                    // Cleanup old messages periodically
                    AppDatabase.cleanupOldMessages(this, MAX_MESSAGE_SAVED);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error saving message to database", e);
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
        // Build status text based on current operations
        String statusText = buildNotificationStatus();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Nearby Chat")
                .setContentText(statusText) // Use dynamic status instead of fixed text
                .setSmallIcon(R.drawable.ic_nearby_chat) // Changed icon
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(ongoing)
                .setShowWhen(false);

        // Add click intent to open app
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
        long currentMs = System.currentTimeMillis();
        return timestampToAsciiId(currentMs); // Returns 5-character ASCII string
    }

    private byte[] createChunkPayload(String messageId, int chunkIndex, int totalChunks, byte[] chunkData) {
        try {
            // Convert display userId (8 chars) to ASCII userId (5 bytes)
            String asciiUserId = timestampToAsciiId(userIdBits);
            byte[] userIdBytes = asciiUserId.getBytes(StandardCharsets.ISO_8859_1);

            // messageId is already 5 ASCII characters
            byte[] messageIdBytes = messageId.getBytes(StandardCharsets.ISO_8859_1);

            byte[] payload = new byte[5 + 5 + 2 + chunkData.length];

            System.arraycopy(userIdBytes, 0, payload, 0, 5);
            System.arraycopy(messageIdBytes, 0, payload, 5, 5);
            payload[10] = (byte) chunkIndex;
            payload[11] = (byte) totalChunks;
            System.arraycopy(chunkData, 0, payload, 12, chunkData.length);

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

    private String getCurrentTimestamp(int chunkCount, long messageIdBits40) {
        long fullTimestamp = reconstructFullTimestamp(messageIdBits40);
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a | dd-MM-yyyy", Locale.getDefault());
        String baseTime = sdf.format(new Date(fullTimestamp));
        return baseTime + " | " + chunkCount + "C";
    }

    private String getCurrentTimestamp(int chunkCount) {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a | dd-MM-yyyy", Locale.getDefault());
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
            Log.e(TAG, "Error getting messages from database", e);
            return new ArrayList<>();
        }
    }

    private boolean hasAllRequiredServicePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            boolean hasScan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean hasAdvertise = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;

            if (!hasConnect || !hasScan || !hasAdvertise) {
                Log.w(TAG, "Missing Bluetooth permissions - Connect: " + hasConnect + ", Scan: " + hasScan + ", Advertise: " + hasAdvertise);
                return false;
            }
        }

        boolean hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasLocation) {
            Log.w(TAG, "Missing location permission");
            return false;
        }

        return true;
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