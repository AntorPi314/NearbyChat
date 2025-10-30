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
import android.net.Uri;
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

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.antor.nearbychat.Database.AppDatabase;
import com.antor.nearbychat.Message.MessageConverterForBle;
import com.antor.nearbychat.Message.MessageHelper;
import com.antor.nearbychat.Message.MessageProcessor;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BleMessagingService extends Service {

    private ScheduledExecutorService bleExecutor;
    private ExecutorService processingExecutor;
    private final ConcurrentLinkedQueue<byte[]> sendingQueue = new ConcurrentLinkedQueue<>();
    private final android.util.LruCache<String, Boolean> receivedMessages = new android.util.LruCache<>(2000);
    private volatile boolean isCycleRunning = false;

    private AppDatabase database;
    private com.antor.nearbychat.Database.MessageDao messageDao;
    private MessageProcessor messageProcessor;

    private static final String TAG = "BleMessagingService";
    private static final String CHANNEL_ID = "nearby_chat_service";
    private static final int NOTIFICATION_ID = 1001;

    private static UUID SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");

    private static int MAX_PAYLOAD_SIZE = 27;
    private static int ADVERTISING_DURATION_MS = 900;
    private static int CHUNK_TIMEOUT_MS = 300000;
    private static int CHUNK_CLEANUP_INTERVAL_MS = 60000;
    private static int MAX_MESSAGE_SAVED = 2000;
    private static int BROADCAST_ROUNDS = 3;
    private static int SCAN_MODE = 2;
    private static int ADVERTISE_MODE = 2;
    private static int TX_POWER_LEVEL = 3;

    private static final String REQUEST_MARKER = "??";
    private static final int MAX_MISSING_CHUNKS = 30;

    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_USER_ID_BITS = "userIdBits";

    public interface MessageTimeoutCallback {
        void onMessageTimeout(MessageModel failedMessage);
    }

    private MessageTimeoutCallback timeoutCallback;

    public void setMessageTimeoutCallback(MessageTimeoutCallback callback) {
        this.timeoutCallback = callback;
    }

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private AdvertiseCallback advertiseCallback;

    private PowerManager.WakeLock wakeLock;
    private boolean isScanning = false;
    private boolean isServiceRunning = false;
    private Handler mainHandler;
    private volatile boolean isAdvertising = false;

    private long userIdBits;
    private String userId;
    private BroadcastReceiver bluetoothReceiver;
    private final IBinder binder = new LocalBinder();

    private String currentSendingMessageId;
    private volatile int failedChunksCount = 0;
    private volatile int totalChunksToSend = 0;
    private static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;

    private ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed with error code: " + errorCode);
            isScanning = false;
        }
    };

    public class LocalBinder extends Binder {
        public BleMessagingService getService() {
            return BleMessagingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        bleExecutor = Executors.newSingleThreadScheduledExecutor();
        processingExecutor = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());
        database = AppDatabase.getInstance(this);
        messageDao = database.messageDao();

        loadConfigurableSettings();
        initializeData();

        messageProcessor = new MessageProcessor(this, processingExecutor, userId);

        acquireWakeLock();
        setupBluetoothReceiver();
        createNotificationChannel();

        processingExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CHUNK_CLEANUP_INTERVAL_MS);
                    if (messageProcessor != null) {
                        messageProcessor.cleanupExpiredReassemblers(CHUNK_TIMEOUT_MS, (failedMsg) -> {
                            addMessage(failedMsg);
                            if (timeoutCallback != null) {
                                mainHandler.post(() -> timeoutCallback.onMessageTimeout(failedMsg));
                            }
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        if (!hasAllRequiredServicePermissions()) {
            Log.e(TAG, "Missing required permissions");
            stopSelf();
            return START_NOT_STICKY;
        }
        loadConfigurableSettings();
        if (!isServiceRunning) {
            try {
                startForegroundService();
                initializeBle();
                isServiceRunning = true;
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException starting service", e);
                stopSelf();
                return START_NOT_STICKY;
            }
        } else {
            stopBleOperations();
            mainHandler.postDelayed(this::initializeBle, 1000);
        }
        if (intent != null && intent.hasExtra("message_to_send")) {
            String message = intent.getStringExtra("message_to_send");
            String chatType = intent.getStringExtra("chat_type");
            String chatId = intent.getStringExtra("chat_id");
            if (message != null && chatType != null && chatId != null) {
                String payload = PayloadCompress.buildPayload(message, null, null);
                sendMessage(payload, chatType, chatId);
            }
        }
        return START_STICKY;
    }

    private void loadConfigurableSettings() {
        try {
            SharedPreferences prefs = getSharedPreferences("NearbyChatSettings", MODE_PRIVATE);
            MAX_PAYLOAD_SIZE = prefs.getInt("MAX_PAYLOAD_SIZE", 27);
            if (MAX_PAYLOAD_SIZE < 20) MAX_PAYLOAD_SIZE = 20;

            String serviceUuidString = prefs.getString("SERVICE_UUID", "0000aaaa-0000-1000-8000-00805f9b34fb");
            try {
                SERVICE_UUID = UUID.fromString(serviceUuidString);
            } catch (IllegalArgumentException e) {
                SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");
            }

            ADVERTISING_DURATION_MS = prefs.getInt("ADVERTISING_DURATION_MS", 900);
            CHUNK_TIMEOUT_MS = prefs.getInt("CHUNK_TIMEOUT_MS", 300000);
            CHUNK_CLEANUP_INTERVAL_MS = prefs.getInt("CHUNK_CLEANUP_INTERVAL_MS", 60000);
            MAX_MESSAGE_SAVED = prefs.getInt("MAX_MESSAGE_SAVED", 2000);

            BROADCAST_ROUNDS = prefs.getInt("BROADCAST_ROUNDS", 3);
            if (BROADCAST_ROUNDS < 1) BROADCAST_ROUNDS = 1;

            SCAN_MODE = prefs.getInt("SCAN_MODE", 2);
            if (SCAN_MODE < 0) SCAN_MODE = 0;
            if (SCAN_MODE > 2) SCAN_MODE = 2;

            ADVERTISE_MODE = prefs.getInt("ADVERTISE_MODE", 2);
            if (ADVERTISE_MODE < 0) ADVERTISE_MODE = 0;
            if (ADVERTISE_MODE > 2) ADVERTISE_MODE = 2;

            TX_POWER_LEVEL = prefs.getInt("TX_POWER_LEVEL", 3);
            if (TX_POWER_LEVEL < 0) TX_POWER_LEVEL = 0;
            if (TX_POWER_LEVEL > 3) TX_POWER_LEVEL = 3;

        } catch (Exception e) {
            Log.e(TAG, "Error loading settings", e);
        }
    }

    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Nearby Chat Active")
                .setContentText("Scanning for messages...")
                .setSmallIcon(R.drawable.notify)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false);
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Nearby Chat Service",
                NotificationManager.IMPORTANCE_LOW
        );
        serviceChannel.setShowBadge(false);
        serviceChannel.setSound(null, null);

        Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.notification_sound);
        android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .build();

        NotificationChannel messageChannel = new NotificationChannel(
                "message_notifications",
                "New Messages",
                NotificationManager.IMPORTANCE_HIGH
        );
        messageChannel.setShowBadge(true);
        messageChannel.enableVibration(true);
        messageChannel.setVibrationPattern(new long[]{0, 500, 200, 500});
        messageChannel.setSound(soundUri, audioAttributes);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
            manager.createNotificationChannel(messageChannel);
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NearbyChatService::WakeLock");
            wakeLock.acquire();
        }
    }

    private void initializeData() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        userIdBits = prefs.getLong(KEY_USER_ID_BITS, -1);
        if (userIdBits == -1) {
            userIdBits = System.currentTimeMillis() & ((1L << 40) - 1);
            prefs.edit().putLong(KEY_USER_ID_BITS, userIdBits).apply();
        }
        userId = MessageHelper.timestampToDisplayId(userIdBits);
    }

    private void setupBluetoothReceiver() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    if (state == BluetoothAdapter.STATE_ON) {
                        mainHandler.postDelayed(() -> initializeBle(), 500);
                    } else if (state == BluetoothAdapter.STATE_OFF) {
                        stopBleOperations();
                    }
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

    private void initializeBle() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        if (!hasRequiredPermissions()) return;
        try {
            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (scanner != null && !isCycleRunning) {
                startBleCycle();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in initializeBle", e);
        }
    }

    public void cancelAdvertising() {
        stopAdvertising();
        mainHandler.removeCallbacksAndMessages(null);

        if (advertisingListener != null) {
            mainHandler.post(() -> advertisingListener.onAdvertisingCompleted());
        }
        currentSendingMessageId = null;
        failedChunksCount = 0;
        totalChunksToSend = 0;

        Log.d(TAG, "Advertising cancelled by user");
    }

    private void startBleCycle() {
        if (isCycleRunning || bleExecutor.isShutdown()) return;
        isCycleRunning = true;
        Log.d(TAG, "Starting BLE cycle");
        bleExecutor.submit(this::bleCycleTask);
    }

    private void bleCycleTask() {
        if (!isServiceRunning) {
            isCycleRunning = false;
            return;
        }
        try {
            if (!hasRequiredPermissions() || !bluetoothAdapter.isEnabled()) {
                bleExecutor.schedule(this::bleCycleTask, 5000, TimeUnit.MILLISECONDS);
                return;
            }
            if (!isScanning) {
                startScanningInternal();
                updateNotification("Scanning...", true);
            }
            bleExecutor.schedule(this::bleCycleTask, 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.e(TAG, "Error in cycle", e);
            bleExecutor.schedule(this::bleCycleTask, 1000, TimeUnit.MILLISECONDS);
        }
    }

    private void startAdvertising(byte[] payload) {
        if (advertiser == null) return;
        try {
            if (!hasRequiredPermissions()) return;
            stopAdvertising();

            int advertiseMode;
            switch (ADVERTISE_MODE) {
                case 0: advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER; break;
                case 1: advertiseMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED; break;
                case 2:
                default: advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY; break;
            }
            int txPowerLevel;
            switch (TX_POWER_LEVEL) {
                case 0: txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW; break;
                case 1: txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_LOW; break;
                case 2: txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM; break;
                case 3:
                default: txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH; break;
            }
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(advertiseMode)
                    .setTxPowerLevel(txPowerLevel)
                    .setConnectable(false)
                    .setTimeout(0)
                    .build();

            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceData(new ParcelUuid(SERVICE_UUID), payload)
                    .build();

            advertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settings) {
                    isAdvertising = true;
                    Log.d(TAG, "Advertise started: " + payload.length + " bytes");
                    mainHandler.postDelayed(() -> {
                        stopAdvertising();
                        isAdvertising = false;
                        Log.d(TAG, "Advertise completed");
                    }, ADVERTISING_DURATION_MS);
                }
                @Override
                public void onStartFailure(int errorCode) {
                    isAdvertising = false;
                    failedChunksCount++;
                    Log.e(TAG, "Advertise failed (chunk " + failedChunksCount + "/" + totalChunksToSend + "): " + errorCode);
                }
            };
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (Exception e) {
            isAdvertising = false;
            failedChunksCount++;
            Log.e(TAG, "Error advertising", e);
        }
    }

    private void stopAdvertising() {
        if (advertiser != null && advertiseCallback != null) {
            if (!hasRequiredPermissions()) return;
            try {
                advertiser.stopAdvertising(advertiseCallback);
                isAdvertising = false;
                Log.d(TAG, "Advertising stopped");
            } catch (SecurityException se) {
                Log.e(TAG, "Missing required Bluetooth permissions", se);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping advertising", e);
            }
        }
    }

    private void startScanningInternal() {
        if (scanner == null || isScanning) return;
        try {
            int scanMode;
            switch (SCAN_MODE) {
                case 0: scanMode = ScanSettings.SCAN_MODE_LOW_POWER; break;
                case 1: scanMode = ScanSettings.SCAN_MODE_BALANCED; break;
                case 2:
                default: scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY; break;
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(scanMode)
                    .build();

            List<ScanFilter> filters = Collections.singletonList(
                    new ScanFilter.Builder()
                            .setServiceData(new ParcelUuid(SERVICE_UUID), new byte[0], new byte[0])
                            .build()
            );
            scanner.startScan(filters, settings, scanCallback);
            isScanning = true;
            Log.d(TAG, "Scanning started");
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception scanning", e);
        }
    }

    private void stopScanningInternal() {
        if (scanner != null && isScanning) {
            try {
                scanner.stopScan(scanCallback);
                isScanning = false;
                Log.d(TAG, "Scanning stopped");
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception stopping scan", e);
            }
        }
    }

    private void handleScanResult(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null) return;
        byte[] data = record.getServiceData(new ParcelUuid(SERVICE_UUID));
        if (data == null) return;
        String messageId = Arrays.toString(data);
        if (receivedMessages.get(messageId) != null) return;
        receivedMessages.put(messageId, true);

        processingExecutor.submit(() -> {
            MessageModel msg = messageProcessor.processIncomingData(data, userId);
            if (msg != null) {
                addMessage(msg);
            }
        });
    }

    public void sendMessage(String payload, String chatType, String chatId) {
        processingExecutor.submit(() -> {
            try {
                MessageConverterForBle converter = new MessageConverterForBle(
                        this, payload, chatType, chatId, userId, userIdBits, MAX_PAYLOAD_SIZE);
                converter.process();
                MessageModel msgToSave = converter.getMessageToSave();
                List<byte[]> packets = converter.getBlePacketsToSend();

                if (msgToSave != null) {
                    addMessage(msgToSave);
                }
                if ("F".equals(chatType) && !chatId.isEmpty()) {
                    autoAddFriendIfSending(chatId);
                }
                schedulePacketsForAdvertising(packets, msgToSave);

            } catch (Exception e) {
                Log.e(TAG, "Error in sendMessage", e);
                if (advertisingListener != null) {
                    mainHandler.post(() -> advertisingListener.onAdvertisingCompleted());
                }
            }
        });
    }

    private void autoAddFriendIfSending(String friendAsciiId) {
        try {
            long bits = MessageHelper.asciiIdToTimestamp(friendAsciiId);
            String friendDisplayId = MessageHelper.timestampToDisplayId(bits);

            SharedPreferences prefs = getSharedPreferences("NearbyChatPrefs", MODE_PRIVATE);
            String friendsJson = prefs.getString("friendsList", null);
            Gson gson = new Gson();
            Type type = new TypeToken<List<FriendModel>>() {}.getType();
            List<FriendModel> friends = gson.fromJson(friendsJson, type);
            if (friends == null) {
                friends = new ArrayList<>();
            }
            boolean exists = false;
            for (FriendModel f : friends) {
                if (f.getDisplayId().equals(friendDisplayId)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                friends.add(new FriendModel(friendDisplayId, "", ""));
                prefs.edit().putString("friendsList", gson.toJson(friends)).apply();
                DataCache.invalidate();
                Log.d(TAG, "Auto-added friend when sending: " + friendDisplayId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error auto-adding friend when sending", e);
        }
    }

    public void retransmitMessage(MessageModel messageToRetransmit) {
        processingExecutor.submit(() -> {
            try {
                MessageConverterForBle converter = new MessageConverterForBle(
                        this, messageToRetransmit, MAX_PAYLOAD_SIZE);
                converter.process();
                List<byte[]> packets = converter.getBlePacketsToSend();

                schedulePacketsForAdvertising(packets, null);

            } catch (Exception e) {
                Log.e(TAG, "Error in retransmitMessage", e);
                if (advertisingListener != null) {
                    mainHandler.post(() -> advertisingListener.onAdvertisingCompleted());
                }
            }
        });
    }

    private void schedulePacketsForAdvertising(List<byte[]> packets, MessageModel originalMessage) {
        failedChunksCount = 0;

        if (advertisingListener != null) {
            mainHandler.post(() -> advertisingListener.onAdvertisingStarted());
        }
        currentSendingMessageId = (originalMessage != null) ? originalMessage.getMessageId() : "retransmit";

        if (packets != null && !packets.isEmpty()) {
            List<byte[]> allPackets = new ArrayList<>();
            for (int round = 0; round < BROADCAST_ROUNDS; round++) {
                allPackets.addAll(packets);
            }
            totalChunksToSend = allPackets.size();

            for (int i = 0; i < allPackets.size(); i++) {
                final byte[] packet = allPackets.get(i);
                final int index = i;
                final boolean isLast = (i == allPackets.size() - 1);

                mainHandler.postDelayed(() -> {
                    startAdvertising(packet);
                    Log.d(TAG, "Sent chunk " + (index + 1) + "/" + allPackets.size());

                    if (isLast && advertisingListener != null) {
                        mainHandler.postDelayed(() -> {
                            advertisingListener.onAdvertisingCompleted();

                            if (failedChunksCount > 0 && originalMessage != null) {
                                Log.d(TAG, "Message sending had " + failedChunksCount + " failures out of " + totalChunksToSend);
                                markMessageAsFailed(originalMessage);
                            }
                            currentSendingMessageId = null;
                            failedChunksCount = 0;
                            totalChunksToSend = 0;
                        }, ADVERTISING_DURATION_MS + 500);
                    }
                }, i * 1000L);
            }
            Log.d(TAG, "Scheduled " + allPackets.size() + " packets (" + BROADCAST_ROUNDS + " rounds)");
        } else {
            if (advertisingListener != null) {
                mainHandler.post(() -> advertisingListener.onAdvertisingCompleted());
            }
            currentSendingMessageId = null;
        }
    }

    private void markMessageAsFailed(MessageModel msg) {
        processingExecutor.submit(() -> {
            try {
                msg.setFailed(true);

                messageDao.deleteMessage(msg.getSenderId(), msg.getMessageId(), msg.getTimestamp());
                messageDao.insertMessage(com.antor.nearbychat.Database.MessageEntity.fromMessageModel(msg));

                Log.d(TAG, "Marked message as failed: " + msg.getMessageId());

                if (timeoutCallback != null) {
                    mainHandler.post(() -> timeoutCallback.onMessageTimeout(msg));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error marking message as failed", e);
            }
        });
    }

    public void sendMissingPartsRequest(String targetUserId, String messageId, List<Integer> missingChunks) {
        processingExecutor.submit(() -> {
            try {
                long targetBits = MessageHelper.displayIdToTimestamp(targetUserId);
                String targetAscii = MessageHelper.timestampToAsciiId(targetBits);
                long msgBits = MessageHelper.displayIdToTimestamp(messageId);
                String msgAscii = MessageHelper.timestampToAsciiId(msgBits);

                byte[] header = (REQUEST_MARKER + targetAscii + msgAscii).getBytes(ISO_8859_1);
                int count = Math.min(missingChunks.size(), MAX_MISSING_CHUNKS);
                byte[] payload = new byte[header.length + count];
                System.arraycopy(header, 0, payload, 0, header.length);
                for (int i = 0; i < count; i++) {
                    payload[header.length + i] = missingChunks.get(i).byteValue();
                }
                mainHandler.post(() -> {
                    startAdvertising(payload);
                    Log.d(TAG, "Sent request for " + count + " chunks");
                });
            } catch (Exception e) {
                Log.e(TAG, "Error creating request", e);
            }
        });
    }

    private void stopBleOperations() {
        stopAdvertising();
        stopScanningInternal();
    }

    private void addMessage(MessageModel msg) {
        if (!msg.isSelf()) {
            msg.setRead(false);
            if ("F".equals(msg.getChatType())) {
                autoAddFriendIfNeeded(msg.getSenderId());
            }
        }

        if (!msg.isSelf() && msg.isComplete() && !msg.isFailed()) {
            showNotificationIfNeeded(msg);
        }

        try {
            if (msg.isComplete()) {
                if (messageDao.partialMessageExists(msg.getSenderId(), msg.getMessageId()) > 0) {
                    messageDao.deletePartialMessage(msg.getSenderId(), msg.getMessageId());
                    Log.d(TAG, "Deleted partial message before saving complete: " + msg.getMessageId());
                }
                if (messageDao.messageExists(msg.getSenderId(), msg.getMessage(), msg.getTimestamp()) == 0) {
                    messageDao.insertMessage(com.antor.nearbychat.Database.MessageEntity.fromMessageModel(msg));
                    AppDatabase.cleanupOldMessages(this, MAX_MESSAGE_SAVED);
                    Log.d(TAG, "✓ Saved complete message: " + msg.getMessageId());
                }
            } else {
                if (messageDao.partialMessageExists(msg.getSenderId(), msg.getMessageId()) > 0) {
                    messageDao.updatePartialMessage(msg.getSenderId(), msg.getMessageId(), msg.getMessage());
                    Log.d(TAG, "Updated partial message: " + msg.getMessageId() + " -> " + msg.getMessage());
                } else {
                    messageDao.insertMessage(com.antor.nearbychat.Database.MessageEntity.fromMessageModel(msg));
                    Log.d(TAG, "Inserted partial message: " + msg.getMessageId() + " -> " + msg.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving message", e);
        }

        Log.d(TAG, "💾 Saving message: chatType=" + msg.getChatType() +
                " | chatId=" + msg.getChatId() +
                " | from=" + msg.getSenderId() +
                " | messageId=" + msg.getMessageId() +
                " | isComplete=" + msg.isComplete() +
                " | isFailed=" + msg.isFailed() +
                " | content=" + msg.getMessage().substring(0, Math.min(50, msg.getMessage().length())));
    }

    private void autoAddFriendIfNeeded(String friendDisplayId) {
        SharedPreferences prefs = getSharedPreferences("NearbyChatPrefs", MODE_PRIVATE);
        String friendsJson = prefs.getString("friendsList", null);
        Gson gson = new Gson();
        Type type = new TypeToken<List<FriendModel>>() {}.getType();
        List<FriendModel> friends = gson.fromJson(friendsJson, type);
        if (friends == null) {
            friends = new ArrayList<>();
        }
        boolean exists = friends.stream().anyMatch(f -> f.getDisplayId().equals(friendDisplayId));

        if (!exists) {
            friends.add(new FriendModel(friendDisplayId, friendDisplayId, ""));
            prefs.edit().putString("friendsList", gson.toJson(friends)).apply();
            DataCache.invalidate();
            Log.d(TAG, "Auto-added new friend: " + friendDisplayId);
        }
    }

    private void showNotificationIfNeeded(MessageModel msg) {
        SharedPreferences prefs = getSharedPreferences("NearbyChatPrefs", MODE_PRIVATE);
        String key = msg.getChatType() + ":" + msg.getChatId();
        boolean isNotificationEnabled = prefs.getBoolean("notification_" + key, false);

        if (!isNotificationEnabled) {
            Log.d(TAG, "Notification disabled for chat: " + key);
            return;
        }
        SharedPreferences activePrefs = getSharedPreferences("ActiveChatInfo", MODE_PRIVATE);
        String activeChatType = activePrefs.getString("chatType", "N");
        String activeChatId = activePrefs.getString("chatId", "");

        if (msg.getChatType().equals(activeChatType) && msg.getChatId().equals(activeChatId)) {
            Log.d(TAG, "User is in the chat, skipping notification");
            return;
        }
        showMessageNotification(msg);
    }

    private void showMessageNotification(MessageModel msg) {
        try {
            PayloadCompress.ParsedPayload parsed = PayloadCompress.parsePayload(msg.getMessage());
            String messagePreview = parsed.message.isEmpty() ? "New message" : parsed.message;
            if (messagePreview.length() > 50) {
                messagePreview = messagePreview.substring(0, 50) + "...";
            }

            // ✅ CHANGED: Load title based on chat type
            String notificationTitle;
            String notificationText;

            if ("G".equals(msg.getChatType())) {
                // Group message: Show group name as title, sender + message as text
                notificationTitle = getGroupName(msg.getChatId());
                String senderName = getSenderDisplayName(msg.getSenderId());
                notificationText = senderName + ": " + messagePreview;
            } else {
                // Friend/Nearby message: Show sender name as title
                notificationTitle = getSenderDisplayName(msg.getSenderId());
                notificationText = messagePreview;
            }

            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            notificationIntent.putExtra("chatType", msg.getChatType());
            notificationIntent.putExtra("chatId", msg.getChatId());

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    (msg.getChatType() + msg.getChatId()).hashCode(),
                    notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "message_notifications")
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationText)
                    .setSmallIcon(R.drawable.notify)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);

            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                int notificationId = (msg.getChatType() + msg.getChatId()).hashCode();
                notificationManager.notify(notificationId, builder.build());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing notification", e);
        }
    }

    private String getGroupName(String groupId) {
        try {
            SharedPreferences prefs = getSharedPreferences("NearbyChatPrefs", MODE_PRIVATE);
            String groupsJson = prefs.getString("groupsList", null);

            if (groupsJson != null) {
                Gson gson = new Gson();
                Type type = new TypeToken<List<GroupModel>>(){}.getType();
                List<GroupModel> groups = gson.fromJson(groupsJson, type);

                if (groups != null) {
                    for (GroupModel g : groups) {
                        if (g.getId().equals(groupId)) {
                            String name = g.getName();
                            if (name != null && !name.isEmpty()) {
                                return name;
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading group name", e);
        }
        try {
            long bits = MessageHelper.asciiIdToTimestamp(groupId);
            return MessageHelper.timestampToDisplayId(bits);
        } catch (Exception e) {
            return groupId;
        }
    }

    private String getSenderDisplayName(String senderId) {
        try {
            // First check name map
            SharedPreferences prefs = getSharedPreferences("NearbyChatPrefs", MODE_PRIVATE);
            String nameMapJson = prefs.getString("nameMap", null);

            if (nameMapJson != null) {
                Gson gson = new Gson();
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> nameMap = gson.fromJson(nameMapJson, type);

                if (nameMap != null && nameMap.containsKey(senderId)) {
                    String name = nameMap.get(senderId);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }

            // Then check friends list
            String friendsJson = prefs.getString("friendsList", null);
            if (friendsJson != null) {
                Gson gson = new Gson();
                Type type = new TypeToken<List<FriendModel>>(){}.getType();
                List<FriendModel> friends = gson.fromJson(friendsJson, type);

                if (friends != null) {
                    for (FriendModel f : friends) {
                        if (f.getDisplayId().equals(senderId)) {
                            String friendName = f.getName();
                            if (friendName != null && !friendName.isEmpty()) {
                                return friendName;
                            }
                            break;
                        }
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error loading sender name", e);
        }
        return senderId;
    }

    public interface AdvertisingStateListener {
        void onAdvertisingStarted();
        void onAdvertisingCompleted();
    }

    private AdvertisingStateListener advertisingListener;

    public void setAdvertisingStateListener(AdvertisingStateListener listener) {
        this.advertisingListener = listener;
    }

    private void updateNotification(String text, boolean ongoing) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Nearby Chat")
                .setContentText(text)
                .setSmallIcon(R.drawable.notify)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(ongoing)
                .setShowWhen(false);
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(pendingIntent);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, builder.build());
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAllRequiredServicePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                return false;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                return false;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        isServiceRunning = false;
        isCycleRunning = false;
        bleExecutor.shutdownNow();
        processingExecutor.shutdownNow();
        stopBleOperations();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (bluetoothReceiver != null) {
            try {
                unregisterReceiver(bluetoothReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
            }
        }
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}