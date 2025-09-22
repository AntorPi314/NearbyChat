package com.antor.nearbychat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_ENABLE_LOCATION = 102;
    private static final int REQUEST_BATTERY_OPTIMIZATION = 103;
    private static final String TAG = "NearbyChatMain";

    // UI Components
    private EditText inputMessage;
    private RecyclerView recyclerView;
    private ChatAdapter chatAdapter;
    private TextView textStatus;
    private List<MessageModel> messageList = new ArrayList<>();

    // Service Components
    private BleMessagingService bleService;
    private boolean isServiceBound = false;
    private BroadcastReceiver messageReceiver;
    private BroadcastReceiver serviceStateReceiver;

    // Preferences
    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_USER_ID_BITS = "userIdBits";
    private static final String KEY_NAME_MAP = "nameMap";
    private static final String KEY_BATTERY_OPT_REQUESTED = "batteryOptRequested";
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray();

    private static final int WARNING_THRESHOLD = 22;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private boolean permissionsJustGranted = false;
    private static final int REQUEST_STORAGE_PERMISSION = 202;
    private static final int REQUEST_CAMERA_PERMISSION = 203;

    private static final int REQUEST_GALLERY = 200;
    private static final int REQUEST_CAMERA = 201;
    private String currentUserId;
    private ImageView currentProfilePic;

    private long userIdBits;
    private String userId;
    private Map<String, String> nameMap = new HashMap<>();
    private final Gson gson = new Gson();
    private BluetoothAdapter bluetoothAdapter;
    private Handler mainHandler;

    final private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BleMessagingService.LocalBinder binder = (BleMessagingService.LocalBinder) service;
            bleService = binder.getService();
            isServiceBound = true;
            Log.d(TAG, "Service connected");
            loadMessagesFromService();
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
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught exception: " + throwable.getMessage(), throwable);
            runOnUiThread(() -> {
                Toast.makeText(this, "App crashed: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
            });
        });
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler(Looper.getMainLooper());
        setupUI();
        initializeData();
        setupReceivers();
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

        TextView appSubtitle = findViewById(R.id.appSubtitle);
        appSubtitle.setPaintFlags(appSubtitle.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        appSubtitle.setOnClickListener(v -> {
            Intent appSubtitleIntent = new Intent(this, GithubLinkActivity.class);
            startActivity(appSubtitleIntent);
        });
        TextView appTitle = findViewById(R.id.appTitle);
        appTitle.setOnClickListener(v -> {
            Intent appTitleIntent = new Intent(this, AboutActivity.class);
            startActivity(appTitleIntent);
        });
        ImageView save_palestine = findViewById(R.id.save_palestine);
        save_palestine.setOnClickListener(v -> {
            Intent noteIntent = new Intent(this, NoteActivity.class);
            startActivity(noteIntent);
        });
    }

    private void setupAppIconClick() {
        ImageView appIcon = findViewById(R.id.appIcon);
        appIcon.setOnClickListener(v -> showAccountDialog());
    }

    private void showAccountDialog() {
        try {
            Dialog dialog = new Dialog(this);
            dialog.setContentView(R.layout.account_dialog);

            // Make background transparent and set window attributes
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.copyFrom(dialog.getWindow().getAttributes());
                lp.width = dpToPx(this, 280); // Fixed 280dp width
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                dialog.getWindow().setAttributes(lp);
            }

            // Get dialog views
            TextView textID = dialog.findViewById(R.id.textID);
            ImageView profilePic = dialog.findViewById(R.id.profilePicRound);
            Button friendsBtn = dialog.findViewById(R.id.id_friends);
            Button groupsBtn = dialog.findViewById(R.id.id_groups);
            Button settingsBtn = dialog.findViewById(R.id.id_settings);
            Button aboutBtn = dialog.findViewById(R.id.id_about);
            Button restartBtn = dialog.findViewById(R.id.id_restart_app);

            // Set current user info
            textID.setText(getDisplayName(userId));
            loadProfilePicture(userId, profilePic);

            // Profile pic click listener
            profilePic.setOnClickListener(v -> {
                currentUserId = userId;
                currentProfilePic = profilePic;
                showImagePickerDialog(userId, profilePic);
            });

            // Button click listeners
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

        } catch (Exception e) {
            Log.e(TAG, "Error showing account dialog", e);
            Toast.makeText(this, "Error opening account menu", Toast.LENGTH_SHORT).show();
        }
    }

    private void restartApp() {
        try {
            // Stop the service first
            Intent serviceIntent = new Intent(this, BleMessagingService.class);
            stopService(serviceIntent);

            // Restart the app
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

    private void setupReceivers() {
        messageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BleMessagingService.ACTION_MESSAGE_RECEIVED.equals(intent.getAction())) {
                    if (intent.hasExtra(BleMessagingService.EXTRA_MESSAGE)) {
                        String messageJson = intent.getStringExtra(BleMessagingService.EXTRA_MESSAGE);
                        MessageModel message = gson.fromJson(messageJson, MessageModel.class);

                        mainHandler.post(() -> {
                            boolean exists = false;
                            for (MessageModel msg : messageList) {
                                if (msg.getMessage().equals(message.getMessage())
                                        && msg.getSenderId().equals(message.getSenderId())
                                        && msg.getTimestamp().equals(message.getTimestamp())) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                messageList.add(message);
                                chatAdapter.notifyItemInserted(messageList.size() - 1);
                                recyclerView.scrollToPosition(messageList.size() - 1);
                                updateChatUI();
                            }
                        });
                    }
                }
            }
        };

        serviceStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BleMessagingService.ACTION_SERVICE_STATE.equals(intent.getAction())) {
                    boolean isRunning = intent.getBooleanExtra(BleMessagingService.EXTRA_IS_RUNNING, false);
                    Log.d(TAG, "Service state changed: " + (isRunning ? "Running" : "Stopped"));
                }
            }
        };
        IntentFilter messageFilter = new IntentFilter(BleMessagingService.ACTION_MESSAGE_RECEIVED);
        IntentFilter serviceFilter = new IntentFilter(BleMessagingService.ACTION_SERVICE_STATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, messageFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(serviceStateReceiver, serviceFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(messageReceiver, messageFilter);
            registerReceiver(serviceStateReceiver, serviceFilter);
        }
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
                        .setMessage("To keep Nearby Chat running smoothly in the background, please disable battery optimization. This ensures messages are received even when the app is in the background.")
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
                if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
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


    private boolean hasBasicBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBasicBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    PERMISSION_REQUEST_CODE);
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
        }
        else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, show picker
                if (currentUserId != null && currentProfilePic != null) {
                    showImagePickerDialogInternal(currentUserId, currentProfilePic);
                }
            } else {
                Toast.makeText(this, "Storage permission required for gallery access", Toast.LENGTH_SHORT).show();
                // Still allow camera option
                if (currentUserId != null && currentProfilePic != null) {
                    openCamera(currentUserId, currentProfilePic);
                }
            }
        }
        else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (currentUserId != null && currentProfilePic != null) {
                    openCameraInternal(currentUserId, currentProfilePic);
                }
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean isCriticalPermission(String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return permission.equals(Manifest.permission.BLUETOOTH_SCAN) ||
                    permission.equals(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            return permission.equals(Manifest.permission.ACCESS_FINE_LOCATION);
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
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception starting service", e);
            Toast.makeText(this, "Permission error starting service", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Error starting service", e);
            Toast.makeText(this, "Error starting service", Toast.LENGTH_LONG).show();
        }
    }

    private void loadMessagesFromService() {
        if (isServiceBound && bleService != null) {
            List<MessageModel> serviceMessages = bleService.getAllMessages();
            if (serviceMessages != null) { // <-- এখানে একটি null চেক যোগ করা ভালো
                messageList.clear();
                messageList.addAll(serviceMessages);
                chatAdapter.notifyDataSetChanged();
                if (!messageList.isEmpty()) {
                    recyclerView.scrollToPosition(messageList.size() - 1);
                }
            }
            updateChatUI(); // <-- এই নতুন মেথডটি কল করুন
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

    private void promptEnableLocation() {
        new AlertDialog.Builder(this)
                .setTitle("Enable Location")
                .setMessage("Location is required for BLE scanning on Android 8-11. Please turn it on.")
                .setPositiveButton("Enable", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    startActivityForResult(intent, REQUEST_ENABLE_LOCATION);
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    Toast.makeText(this, "Location required for BLE", Toast.LENGTH_LONG).show();
                })
                .setCancelable(false)
                .show();
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
        } else if (requestCode == 101) { // Bluetooth enable request
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
        }
        else if (requestCode == REQUEST_GALLERY && resultCode == RESULT_OK) {
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
        else if (requestCode == REQUEST_CAMERA && resultCode == RESULT_OK) {
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
            Dialog dialog = new Dialog(this);
            dialog.setContentView(R.layout.dialog_edit_name);

            // Make background transparent and set window attributes
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.copyFrom(dialog.getWindow().getAttributes());
                lp.width = dpToPx(this, 280); // Fixed 280dp width
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                dialog.getWindow().setAttributes(lp);
            }

            EditText editName = dialog.findViewById(R.id.editName);
            EditText editEncryptionKey = dialog.findViewById(R.id.editEncryptionKey);
            TextView textID = dialog.findViewById(R.id.textID);
            ImageView profilePic = dialog.findViewById(R.id.profilePicRound);
            Button btnSave = dialog.findViewById(R.id.btnSave);
            Button btnCancel = dialog.findViewById(R.id.btnCancel);

            // Set current values
            textID.setText(msg.getSenderId());
            String existingName = nameMap.get(msg.getSenderId());
            editName.setText(existingName != null ? existingName : "");

            String existingKey = getEncryptionKey(msg.getSenderId());
            editEncryptionKey.setText(existingKey);

            // Load saved profile picture
            loadProfilePicture(msg.getSenderId(), profilePic);

            // Profile pic click listener
            profilePic.setOnClickListener(v -> showImagePickerDialog(msg.getSenderId(), profilePic));

            // Button listeners
            btnCancel.setOnClickListener(v -> dialog.dismiss());

            btnSave.setOnClickListener(v -> {
                String newName = editName.getText().toString().trim();
                String newKey = editEncryptionKey.getText().toString().trim();

                if (newName.isEmpty()) {
                    nameMap.remove(msg.getSenderId());
                } else {
                    nameMap.put(msg.getSenderId(), newName);
                }

                saveEncryptionKey(msg.getSenderId(), newKey);
                saveNameMap();
                chatAdapter.notifyDataSetChanged();

                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });

            dialog.show();

        } catch (Exception e) {
            Log.e(TAG, "Error showing dialog", e);
        }
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


    private void showImagePickerDialog(String userId, ImageView profilePic) {
        // Check if we need storage permission based on Android version
        if (needsStoragePermission()) {
            // Store for later use
            currentUserId = userId;
            currentProfilePic = profilePic;

            // Request appropriate permission based on Android version
            requestStoragePermission();
            return;
        }
        // Permission already granted or not needed, show picker
        showImagePickerDialogInternal(userId, profilePic);
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - Request READ_MEDIA_IMAGES
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                    REQUEST_STORAGE_PERMISSION);
        } else {
            // Android 6-12 - Request READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
        }
    }

    private boolean needsStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - Check for READ_MEDIA_IMAGES
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-12 - Check for READ_EXTERNAL_STORAGE
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED;
        }
        return false; // No permission needed for older versions
    }

    private void showImagePickerDialogInternal(String userId, ImageView profilePic) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Image")
                .setItems(new String[]{"Gallery", "Camera"}, (dialog, which) -> {
                    if (which == 0) {
                        openGallery(userId, profilePic);
                    } else {
                        openCamera(userId, profilePic);
                    }
                })
                .show();
    }

    private void openGallery(String userId, ImageView profilePic) {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        // Store userId and profilePic reference for onActivityResult
        currentUserId = userId;
        currentProfilePic = profilePic;
        startActivityForResult(intent, REQUEST_GALLERY);
    }

    private void openCamera(String userId, ImageView profilePic) {
        // Check camera permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            currentUserId = userId;
            currentProfilePic = profilePic;

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
            return;
        }

        // Permission granted, open camera
        openCameraInternal(userId, profilePic);
    }

    // Encryption key methods
    private String getEncryptionKey(String userId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString("encryption_" + userId, "");
    }

    private void saveEncryptionKey(String userId, String key) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString("encryption_" + userId, key).apply();
    }

    // Profile picture methods
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
                Log.e(TAG, "Error loading profile picture for " + userId, e);
                imageView.setImageResource(R.drawable.profile_pic_round_vector);
            }
        } else {
            imageView.setImageResource(R.drawable.profile_pic_round_vector);
        }
    }

    public void loadProfilePictureForAdapter(String userId, ImageView imageView) {
        loadProfilePicture(userId, imageView);
    }

    private void saveProfilePicture(String userId, Bitmap bitmap) {
        try {
            // First resize, then make circular
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

    private int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a | dd-MM-yyyy", Locale.getDefault());
        return sdf.format(new Date());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (permissionsJustGranted) {
            permissionsJustGranted = false;
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                Log.d(TAG, "Permissions granted, starting service from onResume");
                startBleService();
            } else {
                Toast.makeText(this, "Bluetooth must be enabled to start the service", Toast.LENGTH_SHORT).show();
            }
        }
        if (isServiceBound && bleService != null) {
            loadMessagesFromService();
        }
    }

    @Override
    protected void onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        if (messageReceiver != null) {
            try {
                unregisterReceiver(messageReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering message receiver", e);
            }
        }
        if (serviceStateReceiver != null) {
            try {
                unregisterReceiver(serviceStateReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering service state receiver", e);
            }
        }
        saveNameMap();
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }
}