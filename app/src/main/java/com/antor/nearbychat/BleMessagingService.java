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
    private final Set<String> receivedMessages = new HashSet<>();
    private volatile boolean isCycleRunning = false;

    private AppDatabase database;
    private com.antor.nearbychat.Database.MessageDao messageDao;
    private MessageProcessor messageProcessor;

    private static final String TAG = "BleMessagingService";
    private static final String CHANNEL_ID = "nearby_chat_service";
    private static final int NOTIFICATION_ID = 1001;

    private static UUID SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");

    private static int MAX_PAYLOAD_SIZE = 27;
    private static int ADVERTISING_DURATION_MS = 1500;
    private static int CHUNK_TIMEOUT_MS = 120000;
    private static int CHUNK_CLEANUP_INTERVAL_MS = 15000;
    private static int MAX_MESSAGE_SAVED = 2000;
    private static int BROADCAST_ROUNDS = 2;

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
        messageProcessor = new MessageProcessor(this, processingExecutor);
        loadConfigurableSettings();
        initializeData();
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
                sendMessage(message, chatType, chatId);
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
            ADVERTISING_DURATION_MS = prefs.getInt("ADVERTISING_DURATION_MS", 1500);
            CHUNK_TIMEOUT_MS = prefs.getInt("CHUNK_TIMEOUT_MS", 120000);
            CHUNK_CLEANUP_INTERVAL_MS = prefs.getInt("CHUNK_CLEANUP_INTERVAL_MS", 15000);
            MAX_MESSAGE_SAVED = prefs.getInt("MAX_MESSAGE_SAVED", 2000);
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
                .setSmallIcon(R.drawable.ic_nearby_chat)
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
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Nearby Chat Service", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
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
            bleExecutor.schedule(this::bleCycleTask, 100, TimeUnit.MILLISECONDS);
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
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
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
            try {
                if (!hasRequiredPermissions()) return;
                advertiser.stopAdvertising(advertiseCallback);
                isAdvertising = false;
                Log.d(TAG, "Advertising stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping advertising", e);
            }
        }
    }

    private void startScanningInternal() {
        if (scanner == null || isScanning) return;
        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
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
        synchronized (receivedMessages) {
            if (receivedMessages.contains(messageId)) return;
            receivedMessages.add(messageId);
            if (receivedMessages.size() > 2000) {
                receivedMessages.clear();
            }
        }

        processingExecutor.submit(() -> {
            MessageModel msg = messageProcessor.processIncomingData(data, userId);
            if (msg != null) {
                addMessage(msg);
            }
        });
    }

    public void sendMessage(String message, String chatType, String chatId) {
        processingExecutor.submit(() -> {
            try {
                failedChunksCount = 0;

                if (advertisingListener != null) {
                    mainHandler.post(() -> advertisingListener.onAdvertisingStarted());
                }

                MessageConverterForBle converter = new MessageConverterForBle(
                        this, message, chatType, chatId, userId, userIdBits, MAX_PAYLOAD_SIZE);
                converter.process();
                MessageModel msgToSave = converter.getMessageToSave();
                List<byte[]> packets = converter.getBlePacketsToSend();

                currentSendingMessageId = msgToSave != null ? msgToSave.getMessageId() : null;

                if (packets != null && !packets.isEmpty()) {
                    List<byte[]> allPackets = new ArrayList<>();
                    for (int round = 0; round < BROADCAST_ROUNDS; round++) {
                        allPackets.addAll(packets);
                    }
                    totalChunksToSend = allPackets.size();

                    if (msgToSave != null) {
                        addMessage(msgToSave);
                    }

                    final MessageModel finalMsg = msgToSave;

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

                                    if (failedChunksCount > 0 && finalMsg != null) {
                                        Log.d(TAG, "Message sending had " + failedChunksCount + " failures out of " + totalChunksToSend);
                                        markMessageAsFailed(finalMsg);
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
            } catch (Exception e) {
                Log.e(TAG, "Error in sendMessage", e);
                if (advertisingListener != null) {
                    mainHandler.post(() -> advertisingListener.onAdvertisingCompleted());
                }
            }
        });
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

                byte[] header = (REQUEST_MARKER + targetAscii + msgAscii).getBytes(StandardCharsets.ISO_8859_1);
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
        try {
            if (msg.isComplete()) {
                if (messageDao.partialMessageExists(msg.getSenderId(), msg.getMessageId()) > 0) {
                    messageDao.deletePartialMessage(msg.getSenderId(), msg.getMessageId());
                }
                if (messageDao.messageExists(msg.getSenderId(), msg.getMessage(), msg.getTimestamp()) == 0) {
                    messageDao.insertMessage(com.antor.nearbychat.Database.MessageEntity.fromMessageModel(msg));
                    AppDatabase.cleanupOldMessages(this, MAX_MESSAGE_SAVED);
                }
            } else {
                if (messageDao.partialMessageExists(msg.getSenderId(), msg.getMessageId()) > 0) {
                    messageDao.updatePartialMessage(msg.getSenderId(), msg.getMessageId(), msg.getMessage());
                } else {
                    messageDao.insertMessage(com.antor.nearbychat.Database.MessageEntity.fromMessageModel(msg));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving message", e);
        }
        Log.d(TAG, "Saving message: chatType=" + msg.getChatType() + " chatId=" + msg.getChatId() + " from=" + msg.getSenderId() + " isComplete=" + msg.isComplete() + " isFailed=" + msg.isFailed());
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
            Log.d(TAG, "Auto-added new friend: " + friendDisplayId);
        }
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
                .setSmallIcon(R.drawable.ic_nearby_chat)
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