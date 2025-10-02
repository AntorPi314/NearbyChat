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

import java.util.*;

public class BleMessagingService extends Service {

    private AppDatabase database;
    private com.antor.nearbychat.Database.MessageDao messageDao;
    private MessageProcessor messageProcessor;

    private static final String TAG = "BleMessagingService";
    private static final String CHANNEL_ID = "nearby_chat_service";
    private static final int NOTIFICATION_ID = 1001;

    private static UUID SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");
    private static final int USER_ID_LENGTH = 5;
    private static final int MESSAGE_ID_LENGTH = 5;
    private static final int CHUNK_METADATA_LENGTH = 2;
    private static final int HEADER_SIZE = USER_ID_LENGTH + MESSAGE_ID_LENGTH + CHUNK_METADATA_LENGTH;

    private static int MAX_PAYLOAD_SIZE = 27;
    private static int ADVERTISING_DURATION_MS = 1600;
    private static int DELAY_BETWEEN_CHUNKS_MS = 1800;
    private static int CHUNK_TIMEOUT_MS = 60000;
    private static int CHUNK_CLEANUP_INTERVAL_MS = 10000;
    private static int MAX_MESSAGE_SAVED = 500;

    private static final String REQUEST_MARKER = "??";
    private static final int REQUEST_HEADER_SIZE = USER_ID_LENGTH + MESSAGE_ID_LENGTH + 2;
    private static final int MAX_MISSING_CHUNKS = 30;
    private static final int MAX_REQUEST_MESSAGE_SIZE = REQUEST_HEADER_SIZE + MAX_PAYLOAD_SIZE;

    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_USER_ID_BITS = "userIdBits";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private AdvertiseCallback advertiseCallback;

    private PowerManager.WakeLock wakeLock;
    private boolean isScanning = false;
    private boolean isServiceRunning = false;
    private Handler mainHandler;
    private boolean isReceiving = false;
    private boolean isSending = false;
    private volatile boolean isAdvertising = false;

    private long userIdBits;
    private String userId;
    private BroadcastReceiver bluetoothReceiver;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 3;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public BleMessagingService getService() {
            return BleMessagingService.this;
        }
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
                Log.e(TAG, "Invalid UUID in preferences, using default", e);
                SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");
            }
            ADVERTISING_DURATION_MS = prefs.getInt("ADVERTISING_DURATION_MS", 1600);
            DELAY_BETWEEN_CHUNKS_MS = prefs.getInt("DELAY_BETWEEN_CHUNKS_MS", 1800);
            CHUNK_TIMEOUT_MS = prefs.getInt("CHUNK_TIMEOUT_MS", 60000);
            CHUNK_CLEANUP_INTERVAL_MS = prefs.getInt("CHUNK_CLEANUP_INTERVAL_MS", 10000);
            MAX_MESSAGE_SAVED = prefs.getInt("MAX_MESSAGE_SAVED", 500);
            Log.d(TAG, "Settings loaded - UUID: " + SERVICE_UUID + ", Payload: " + MAX_PAYLOAD_SIZE);
        } catch (Exception e) {
            Log.e(TAG, "Error loading settings, using defaults", e);
            SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");
            MAX_PAYLOAD_SIZE = 27;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        mainHandler = new Handler(Looper.getMainLooper());
        database = AppDatabase.getInstance(this);
        messageDao = database.messageDao();
        messageProcessor = new MessageProcessor(this);
        loadConfigurableSettings();
        initializeData();
        acquireWakeLock();
        setupBluetoothReceiver();
        createNotificationChannel();

        Runnable cleanupRunnable = new Runnable() {
            @Override
            public void run() {
                if (messageProcessor != null) {
                    messageProcessor.cleanupExpiredReassemblers(CHUNK_TIMEOUT_MS);
                }
                mainHandler.postDelayed(this, CHUNK_CLEANUP_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(cleanupRunnable, CHUNK_CLEANUP_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        if (!hasAllRequiredServicePermissions()) {
            Log.e(TAG, "Missing required permissions, cannot start foreground service");
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
                Log.e(TAG, "SecurityException starting foreground service", e);
                stopSelf();
                return START_NOT_STICKY;
            }
        } else {
            Log.d(TAG, "Service already running, reinitializing BLE with new settings");
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

    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
            int foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Nearby Chat Service", NotificationManager.IMPORTANCE_LOW);
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
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NearbyChatService::WakeLock");
            wakeLock.acquire();
            Log.d(TAG, "WakeLock acquired");
        }
    }

    private void restartScanning() {
        Log.d(TAG, "Restarting scanning...");
        stopScanning();
        mainHandler.postDelayed(() -> {
            if (!isScanning) {
                startScanning();
            }
        }, 1000);
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
                    if (retryCount < MAX_RETRIES) {
                        retryCount++;
                        mainHandler.postDelayed(() -> {
                            Log.d(TAG, "Retrying scan, attempt: " + retryCount);
                            startScanning();
                        }, 3000);
                    }
                }
            };
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setServiceData(new ParcelUuid(SERVICE_UUID), new byte[0]).build());
            scanner.startScan(filters, settings, scanCallback);
            isScanning = true;
            Log.d(TAG, "Started scanning with UUID: " + SERVICE_UUID.toString());
            updateNotification("Scanning for messages...", true);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException when starting scan - missing permissions", e);
            isScanning = false;
            updateNotification("Missing permissions for scanning", true);
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
            if (data == null) return;

            isReceiving = true;
            updateNotification("", true);
            mainHandler.postDelayed(() -> {
                isReceiving = false;
                updateNotification("", true);
            }, 2000);

            MessageModel newMessage = messageProcessor.processIncomingData(data, userId);
            if (newMessage != null) {
                addMessage(newMessage);
                Log.d(TAG, "Complete message received and processed from " + newMessage.getSenderId());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling scan result", e);
        }
    }

    public void sendMessage(String message, String chatType, String chatId) {
        try {
            isSending = true;
            updateNotification("", true);

            MessageConverterForBle converter = new MessageConverterForBle(this, message, chatType, chatId, userId, userIdBits, MAX_PAYLOAD_SIZE);
            converter.process();

            MessageModel messageToSave = converter.getMessageToSave();
            List<byte[]> packetsToSend = converter.getBlePacketsToSend();

            if (messageToSave != null) {
                addMessage(messageToSave);
            }

            if (packetsToSend == null || packetsToSend.isEmpty()) {
                Log.e(TAG, "No packets generated to send.");
                isSending = false;
                updateNotification("", true);
                return;
            }

            Log.d(TAG, "Sending message in " + packetsToSend.size() + " chunks.");
            for (int i = 0; i < packetsToSend.size(); i++) {
                final int chunkIndex = i;
                final byte[] packet = packetsToSend.get(i);
                mainHandler.postDelayed(() -> {
                    try {
                        updateAdvertisingData(packet);
                        if (chunkIndex == packetsToSend.size() - 1) {
                            mainHandler.postDelayed(() -> {
                                isSending = false;
                                updateNotification("", true);
                                Log.d(TAG, "All chunks sent, restarting scanning");
                                restartScanning();
                            }, ADVERTISING_DURATION_MS + 500);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending chunk " + chunkIndex, e);
                    }
                }, i * DELAY_BETWEEN_CHUNKS_MS);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in sendMessage", e);
            isSending = false;
            updateNotification("", true);
        }
    }

    public void sendMissingPartsRequest(String targetUserId, String messageId, List<Integer> missingChunkIndices) {
    }

    private void updateAdvertisingData(byte[] payload) {
        if (advertiser == null) return;
        try {
            if (!hasRequiredPermissions()) {
                Log.w(TAG, "Missing required permissions for advertising");
                return;
            }
            if (isAdvertising && advertiseCallback != null) {
                try {
                    advertiser.stopAdvertising(advertiseCallback);
                    isAdvertising = false;
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping previous advertising", e);
                }
            }
            AdvertiseData data = new AdvertiseData.Builder().addServiceData(new ParcelUuid(SERVICE_UUID), payload).build();
            advertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    isAdvertising = true;
                    Log.d(TAG, "Advertise success for payload size: " + payload.length);
                }
                @Override
                public void onStartFailure(int errorCode) {
                    isAdvertising = false;
                    Log.e(TAG, "Advertise failed with error: " + errorCode);
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
            mainHandler.postDelayed(() -> {
                if (isAdvertising) {
                    stopAdvertising();
                    isAdvertising = false;
                }
            }, ADVERTISING_DURATION_MS + 500);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException when starting advertising", e);
        } catch (Exception e) {
            Log.e(TAG, "Error advertising", e);
        }
    }

    private void stopAdvertising() {
        if (advertiser != null && advertiseCallback != null) {
            try {
                if (!hasRequiredPermissions()) {
                    Log.w(TAG, "Missing permissions for stopping advertising");
                    return;
                }
                advertiser.stopAdvertising(advertiseCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping advertising", e);
            }
        }
    }

    private void stopScanning() {
        if (scanner != null && scanCallback != null) {
            try {
                if (!hasRequiredPermissions()) {
                    Log.w(TAG, "Missing permissions for stopping scan");
                    return;
                }
                scanner.stopScan(scanCallback);
                isScanning = false;
                Log.d(TAG, "Stopped scanning");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping scan", e);
            }
        }
    }

    private void stopBleOperations() {
        stopAdvertising();
        stopScanning();
    }

    private void addMessage(MessageModel msg) {
        new Thread(() -> {
            try {
                if (messageDao.messageExists(msg.getSenderId(), msg.getMessage(), msg.getTimestamp()) == 0) {
                    com.antor.nearbychat.Database.MessageEntity entity = com.antor.nearbychat.Database.MessageEntity.fromMessageModel(msg);
                    messageDao.insertMessage(entity);
                    AppDatabase.cleanupOldMessages(this, MAX_MESSAGE_SAVED);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error saving message to database", e);
            }
        }).start();
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
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(pendingIntent);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
        }
    }

    private String buildNotificationStatus() {
        List<String> statuses = new ArrayList<>();
        if (isSending) statuses.add("Sending...");
        if (isReceiving) statuses.add("Receiving...");
        if (statuses.isEmpty()) {
            return "Active";
        } else {
            return String.join(", ", statuses);
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
        super.onDestroy();
    }
}