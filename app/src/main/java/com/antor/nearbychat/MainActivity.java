package com.antor.nearbychat;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.antor.nearbychat.Database.AppDatabase;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends BaseActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_ENABLE_LOCATION = 102;
    private static final int REQUEST_BATTERY_OPTIMIZATION = 103;
    private static final int REQUEST_STORAGE_PERMISSION = 202;
    private static final int REQUEST_CAMERA_PERMISSION = 203;
    private static final int REQUEST_GALLERY = 200;
    private static final int REQUEST_CAMERA = 201;
    private static final String TAG = "NearbyChatMain";

    private EditText inputMessage;
    private RecyclerView recyclerView;
    private ChatAdapter chatAdapter;
    private TextView textStatus;
    private List<MessageModel> messageList = new ArrayList<>();

    private BleMessagingService bleService;
    private boolean isServiceBound = false;
    private AppDatabase database;
    private com.antor.nearbychat.Database.MessageDao messageDao;

    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_USER_ID_BITS = "userIdBits";
    private static final String KEY_NAME_MAP = "nameMap";
    private static final String KEY_BATTERY_OPT_REQUESTED = "batteryOptRequested";
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray();

    private static final int[] BACKGROUND_COLORS = {
            0xFF1ABC9C, 0xFF2ECC71, 0xFF3498DB, 0xFF9B59B6, 0xFFE74C3C, 0xFF2C3E50,
            0xFF16A085, 0xFF27AE60, 0xFF2980B9, 0xFF8E44AD, 0xFFC0392B, 0xFFD35400,
            0xFF34495E, 0xFF7F8C8D, 0xFFE67E22, 0xFF6C7B7F, 0xFF8B4513, 0xFF1F2937,
            0xFF374151, 0xFF4B5563, 0xFF6B7280, 0xFF9CA3AF, 0xFFEF4444, 0xFFF97316,
            0xFF228B22, 0xFF22C55E, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFF06B6D4
    };

    private static final int WARNING_THRESHOLD = 22;
    private static final int MAX_MESSAGE_LENGTH = 500;

    private String currentUserId;
    private ImageView currentProfilePic;
    private long userIdBits;
    private String userId;
    private Map<String, String> nameMap = new HashMap<>();
    private final Gson gson = new Gson();
    private BluetoothAdapter bluetoothAdapter;
    private Handler mainHandler;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BleMessagingService.LocalBinder binder = (BleMessagingService.LocalBinder) service;
            bleService = binder.getService();
            isServiceBound = true;
            Log.d(TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bleService = null;
            isServiceBound = false;
            Log.d(TAG, "Service disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());
        setupUI();
        initializeData();
        setupDatabase();
        checkBatteryOptimization();
        checkPermissionsAndStartService();
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
        textStatus = findViewById(R.id.textStatus);
        setupMessageInput();

        recyclerView = findViewById(R.id.chatRecyclerView);
        chatAdapter = new ChatAdapter(messageList, this, this::onMessageClick, this::onMessageLongClick);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(chatAdapter);

        findViewById(R.id.sendButton).setOnClickListener(this::onSendButtonClick);
        setupAppIconClick();

        ImageView appIcon = findViewById(R.id.appIcon);
        appIcon.setOnClickListener(v -> startActivity(new Intent(this, GroupsActivity.class)));

        TextView appTitle = findViewById(R.id.appTitle);
        appTitle.setOnClickListener(v -> startActivity(new Intent(this, GroupsActivity.class)));
    }

    private void setupAppIconClick() {
        ImageView threeDotIcon = findViewById(R.id.threeDotIcon);
        threeDotIcon.setOnClickListener(v -> showAccountDialog());
    }

    private void setupDatabase() {
        database = AppDatabase.getInstance(this);
        messageDao = database.messageDao();

        messageDao.getAllMessagesLive().observe(this, messages -> {
            if (messages != null) {
                messageList.clear();
                for (com.antor.nearbychat.Database.MessageEntity entity : messages) {
                    messageList.add(entity.toMessageModel());
                }
                chatAdapter.notifyDataSetChanged();
                if (!messageList.isEmpty()) {
                    recyclerView.scrollToPosition(messageList.size() - 1);
                }
                updateChatUI();
            }
        });
    }

    private void showAccountDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.account_dialog);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(dialog.getWindow().getAttributes());
            lp.width = dpToPx(this, 280);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            dialog.getWindow().setAttributes(lp);
        }

        TextView textID = dialog.findViewById(R.id.textID);
        ImageView profilePic = dialog.findViewById(R.id.profilePicRound);
        Button friendsBtn = dialog.findViewById(R.id.id_friends);
        Button groupsBtn = dialog.findViewById(R.id.id_groups);
        Button settingsBtn = dialog.findViewById(R.id.id_settings);
        Button aboutBtn = dialog.findViewById(R.id.id_about);
        Button restartBtn = dialog.findViewById(R.id.id_restart_app);

        textID.setText(getDisplayName(userId));
        loadProfilePicture(userId, profilePic);

        profilePic.setOnClickListener(v -> {
            currentUserId = userId;
            currentProfilePic = profilePic;
            showImagePickerDialog(userId, profilePic);
        });

        friendsBtn.setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, FriendsActivity.class));
        });

        groupsBtn.setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, GroupsActivity.class));
        });

        settingsBtn.setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, SettingsActivity.class));
        });

        aboutBtn.setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, AboutActivity.class));
        });

        restartBtn.setOnClickListener(v -> {
            dialog.dismiss();
            restartApp();
        });

        dialog.show();
    }

    private void setupMessageInput() {
        inputMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() > WARNING_THRESHOLD) {
                    inputMessage.setTextColor(Color.parseColor("#FF0000"));
                } else {
                    inputMessage.setTextColor(Color.parseColor("#000000"));
                }
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

    private void updateChatUI() {
        if (messageList.isEmpty()) {
            textStatus.setText("No messages yet!\nStart the conversation");
            textStatus.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            textStatus.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String packageName = getPackageName();
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean hasAsked = prefs.getBoolean(KEY_BATTERY_OPT_REQUESTED, false);

            if (!pm.isIgnoringBatteryOptimizations(packageName) && !hasAsked) {
                new AlertDialog.Builder(this)
                        .setTitle("Disable Battery Optimization")
                        .setMessage("To keep Nearby Chat running smoothly in the background, please disable battery optimization.")
                        .setPositiveButton("Settings", (dialog, which) -> {
                            try {
                                Intent intent = new Intent();
                                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                intent.setData(Uri.parse("package:" + packageName));
                                startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATION);
                            } catch (Exception e) {
                                Intent intent = new Intent();
                                intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                                startActivity(intent);
                            }
                            prefs.edit().putBoolean(KEY_BATTERY_OPT_REQUESTED, true).apply();
                        })
                        .setNegativeButton("Later", (dialog, which) -> {
                            Toast.makeText(this, "Service may stop in background", Toast.LENGTH_LONG).show();
                            prefs.edit().putBoolean(KEY_BATTERY_OPT_REQUESTED, true).apply();
                        })
                        .setCancelable(false)
                        .show();
            }
        }
    }

    private void checkPermissionsAndStartService() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                            PERMISSION_REQUEST_CODE);
                    return;
                }
            }

            if (!bluetoothAdapter.isEnabled()) {
                Toast.makeText(this, "Please turn on Bluetooth", Toast.LENGTH_LONG).show();
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, 101);
                return;
            }

            checkAndRequestPermissionsSequentially();
        } catch (SecurityException se) {
            Log.e(TAG, "Bluetooth permission missing", se);
            Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkAndRequestPermissionsSequentially() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_ADVERTISE
                        },
                        PERMISSION_REQUEST_CODE);
                return;
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_CODE);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE);
                return;
            }
        }

        Log.d(TAG, "All necessary permissions are granted. Starting service.");
        startBleService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            checkAndRequestPermissionsSequentially();
        } else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (currentUserId != null && currentProfilePic != null) {
                    showImagePickerDialogInternal(currentUserId, currentProfilePic);
                }
            } else {
                Toast.makeText(this, "Storage permission required for gallery access", Toast.LENGTH_SHORT).show();
                if (currentUserId != null && currentProfilePic != null) {
                    openCamera(currentUserId, currentProfilePic);
                }
            }
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (currentUserId != null && currentProfilePic != null) {
                    openCameraInternal(currentUserId, currentProfilePic);
                }
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startBleService() {
        try {
            Intent serviceIntent = new Intent(this, BleMessagingService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
            Toast.makeText(this, "Nearby Chat service started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error starting service", e);
            Toast.makeText(this, "Error starting service", Toast.LENGTH_LONG).show();
        }
    }

    private void onSendButtonClick(View v) {
        String msg = inputMessage.getText().toString().trim();
        if (msg.isEmpty()) {
            Toast.makeText(this, "Message cannot be empty!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (msg.length() > MAX_MESSAGE_LENGTH) {
            Toast.makeText(this, "Message too long (" + msg.length() + "/" + MAX_MESSAGE_LENGTH + ")",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!validateBluetoothAndService()) {
            return;
        }

        if (isServiceBound && bleService != null) {
            bleService.sendMessageInChunks(msg);
            inputMessage.setText("");
        } else {
            Intent serviceIntent = new Intent(this, BleMessagingService.class);
            serviceIntent.putExtra("message_to_send", msg);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            inputMessage.setText("");
        }
    }

    private boolean validateBluetoothAndService() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show();
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

        if (!isServiceBound || bleService == null) {
            startBleService();
            Toast.makeText(this, "Starting service, please try again", Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_LOCATION) {
            if (isLocationEnabled()) {
                requestAllPermissions();
            } else {
                Toast.makeText(this, "Location is required", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == 101) {
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                requestAllPermissions();
            } else {
                Toast.makeText(this, "Bluetooth is required", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_BATTERY_OPTIMIZATION) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String packageName = getPackageName();
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Battery optimization disabled", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_GALLERY && resultCode == RESULT_OK) {
            handleGalleryResult(data);
        } else if (requestCode == REQUEST_CAMERA && resultCode == RESULT_OK) {
            handleCameraResult(data);
        }
    }

    private void handleGalleryResult(Intent data) {
        try {
            if (data != null && data.getData() != null && currentProfilePic != null && currentUserId != null) {
                Uri imageUri = data.getData();
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                Bitmap resizedBitmap = ImageConverter.resizeAndCrop(bitmap, 94, 94);
                Bitmap circularBitmap = ImageConverter.createCircularBitmap(resizedBitmap);
                currentProfilePic.setImageBitmap(circularBitmap);
                saveProfilePicture(currentUserId, resizedBitmap);
            } else {
                Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing gallery image", e);
            Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleCameraResult(Intent data) {
        try {
            if (data != null && data.getExtras() != null) {
                Bundle extras = data.getExtras();
                Bitmap bitmap = (Bitmap) extras.get("data");
                if (bitmap != null && currentProfilePic != null && currentUserId != null) {
                    Bitmap resizedBitmap = ImageConverter.resizeAndCrop(bitmap, 94, 94);
                    Bitmap circularBitmap = ImageConverter.createCircularBitmap(resizedBitmap);
                    currentProfilePic.setImageBitmap(circularBitmap);
                    saveProfilePicture(currentUserId, resizedBitmap);
                } else {
                    Toast.makeText(this, "Error capturing image", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "No image captured", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing camera image", e);
            Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestAllPermissions() {
        List<String> permissionsList = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsList.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsList.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        } else {
            Log.d(TAG, "All permissions already granted, starting service");
            startBleService();
        }
    }

    private void onMessageClick(MessageModel msg) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Copied Message", msg.getMessage()));
                Toast.makeText(this, "Message copied", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copying message", e);
        }
    }

    private void onMessageLongClick(MessageModel msg) {
        try {
            String[] options = msg.isSelf()
                    ? new String[]{"Copy", "Resend"}
                    : new String[]{"Copy", "Forward"};

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Message Options")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            copyMessageToClipboard(msg);
                        } else if (which == 1) {
                            if (msg.isSelf()) {
                                resendMyMessage(msg);
                            } else {
                                forwardMessage(msg);
                            }
                        }
                    })
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing message options", e);
        }
    }

    private void resendMyMessage(MessageModel msg) {
        try {
            if (!validateBluetoothAndService()) return;

            Toast.makeText(this, "Resending...", Toast.LENGTH_SHORT).show();

            if (isServiceBound && bleService != null) {
                bleService.resendMessageWithoutSaving(msg.getMessage(), msg.getMessageId());
            } else {
                Intent serviceIntent = new Intent(this, BleMessagingService.class);
                serviceIntent.putExtra("message_to_resend", msg.getMessage());
                serviceIntent.putExtra("is_resend", true);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resending message", e);
        }
    }

    private void forwardMessage(MessageModel msg) {
        try {
            if (!validateBluetoothAndService()) return;

            Toast.makeText(this, "Forwarding...", Toast.LENGTH_SHORT).show();

            if (isServiceBound && bleService != null) {
                bleService.forwardMessageWithOriginalSender(msg.getMessage(), msg.getSenderId(), msg.getMessageId());
            } else {
                Intent serviceIntent = new Intent(this, BleMessagingService.class);
                serviceIntent.putExtra("message_to_send", msg.getMessage());
                serviceIntent.putExtra("original_sender_id", msg.getSenderId());
                serviceIntent.putExtra("is_forward", true);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error forwarding message", e);
        }
    }

    private void copyMessageToClipboard(MessageModel msg) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Copied Message", msg.getMessage()));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copying message", e);
        }
    }

    private void restartApp() {
        try {
            Intent serviceIntent = new Intent(this, BleMessagingService.class);
            stopService(serviceIntent);

            Intent restartIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (restartIntent != null) {
                restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(restartIntent);
                finish();
                System.exit(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error restarting app", e);
            Toast.makeText(this, "Error restarting app", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isLocationEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } else {
            int mode = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF);
            return mode != Settings.Secure.LOCATION_MODE_OFF;
        }
    }

    private void showImagePickerDialog(String userId, ImageView profilePic) {
        if (needsStoragePermission()) {
            currentUserId = userId;
            currentProfilePic = profilePic;
            requestStoragePermission();
            return;
        }
        showImagePickerDialogInternal(userId, profilePic);
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                    REQUEST_STORAGE_PERMISSION);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
        }
    }

    private boolean needsStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    private void showImagePickerDialogInternal(String userId, ImageView profilePic) {
        String[] options = hasCustomProfilePicture(userId)
                ? new String[]{"Gallery", "Camera", "Reset to Default"}
                : new String[]{"Gallery", "Camera"};

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Image")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openGallery(userId, profilePic);
                    } else if (which == 1) {
                        openCamera(userId, profilePic);
                    } else if (which == 2) {
                        resetToGeneratedProfilePic(userId, profilePic);
                    }
                })
                .show();
    }

    private void openGallery(String userId, ImageView profilePic) {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        currentUserId = userId;
        currentProfilePic = profilePic;
        startActivityForResult(intent, REQUEST_GALLERY);
    }

    private void openCamera(String userId, ImageView profilePic) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            currentUserId = userId;
            currentProfilePic = profilePic;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
            return;
        }
        openCameraInternal(userId, profilePic);
    }

    private void openCameraInternal(String userId, ImageView profilePic) {
        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (intent.resolveActivity(getPackageManager()) != null) {
                currentUserId = userId;
                currentProfilePic = profilePic;
                startActivityForResult(intent, REQUEST_CAMERA);
            } else {
                Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error opening camera", e);
            Toast.makeText(this, "Error opening camera", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap generateProfilePic(String userId) {
        try {
            if (userId == null || userId.length() < 8) {
                return createDefaultProfilePic();
            }

            String text = userId.substring(6, 8);
            char colorChar = userId.charAt(5);
            int colorIndex = getAlphabetIndex(colorChar);
            int bgColor = BACKGROUND_COLORS[colorIndex];
            return createTextBitmap(text, bgColor, 0xFFFFFFFF);
        } catch (Exception e) {
            Log.e(TAG, "Error generating profile pic", e);
            return createDefaultProfilePic();
        }
    }

    private int getAlphabetIndex(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return 0;
    }

    private Bitmap createTextBitmap(String text, int bgColor, int textColor) {
        int size = 94;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        Paint bgPaint = new Paint();
        bgPaint.setAntiAlias(true);
        bgPaint.setColor(bgColor);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint);

        Paint textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setColor(textColor);
        textPaint.setTextSize(size * 0.45f);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
        float textHeight = fontMetrics.bottom - fontMetrics.top;
        float textY = (size + textHeight) / 2f - fontMetrics.bottom;
        canvas.drawText(text, size / 2f, textY, textPaint);
        return bitmap;
    }

    private Bitmap createDefaultProfilePic() {
        return createTextBitmap("??", 0xFF95A5A6, 0xFFFFFFFF);
    }

    private boolean hasCustomProfilePicture(String userId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String base64Image = prefs.getString("profile_" + userId, null);
        return base64Image != null;
    }

    private void resetToGeneratedProfilePic(String userId, ImageView imageView) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().remove("profile_" + userId).apply();
        Bitmap generatedBitmap = generateProfilePic(userId);
        imageView.setImageBitmap(generatedBitmap);
    }

    private void loadProfilePicture(String userId, ImageView imageView) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String base64Image = prefs.getString("profile_" + userId, null);

        if (base64Image != null) {
            try {
                byte[] imageBytes = Base64.decode(base64Image, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                Bitmap circularBitmap = ImageConverter.createCircularBitmap(bitmap);
                imageView.setImageBitmap(circularBitmap);
            } catch (Exception e) {
                Log.e(TAG, "Error loading saved profile picture for " + userId, e);
                Bitmap generatedBitmap = generateProfilePic(userId);
                imageView.setImageBitmap(generatedBitmap);
            }
        } else {
            Bitmap generatedBitmap = generateProfilePic(userId);
            imageView.setImageBitmap(generatedBitmap);
        }
    }

    public void loadProfilePictureForAdapter(String userId, ImageView imageView) {
        loadProfilePicture(userId, imageView);
    }

    private void saveProfilePicture(String userId, Bitmap bitmap) {
        try {
            Bitmap resizedBitmap = ImageConverter.resizeAndCrop(bitmap, 94, 94);
            Bitmap circularBitmap = ImageConverter.createCircularBitmap(resizedBitmap);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            circularBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] imageBytes = baos.toByteArray();
            String base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT);

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString("profile_" + userId, base64Image).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving profile picture", e);
        }
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

    public String getDisplayName(String senderId) {
        try {
            String name = nameMap.get(senderId);
            return (name != null && !name.isEmpty()) ? name : senderId;
        } catch (Exception e) {
            Log.e(TAG, "Error getting display name", e);
            return senderId;
        }
    }

    private void loadNameMap() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String json = prefs.getString(KEY_NAME_MAP, null);
            if (json != null) {
                Type type = new TypeToken<Map<String, String>>() {}.getType();
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

    private int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Starting service from onResume");
            startBleService();
        }
    }

    @Override
    protected void onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        saveNameMap();
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }
}