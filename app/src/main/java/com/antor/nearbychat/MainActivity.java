package com.antor.nearbychat;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.widget.LinearLayout;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import java.util.*;

import com.antor.nearbychat.Message.MessageHelper;

public class MainActivity extends BaseActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_ENABLE_LOCATION = 102;
    private static final int REQUEST_BATTERY_OPTIMIZATION = 103;
    private static final int REQUEST_STORAGE_PERMISSION = 202;
    private static final int REQUEST_CAMERA_PERMISSION = 203;
    private static final int REQUEST_CODE_SELECT_CHAT = 104;
    private static final int REQUEST_GALLERY = 200;
    private static final int REQUEST_CAMERA = 201;
    private static final String TAG = "NearbyChatMain";

    private static final String PREFS_ACTIVE_CHAT = "ActiveChatInfo";
    private static final String KEY_CHAT_TYPE = "chatType";
    private static final String KEY_CHAT_ID = "chatId";

    private String activeChatType = "N";
    private String activeChatId = "";

    private EditText inputMessage;
    private RecyclerView recyclerView;
    private ChatAdapter chatAdapter;
    private TextView textStatus;
    private List<MessageModel> messageList = new ArrayList<>();

    private ImageView appIcon;
    private TextView appTitle;
    private TextView unseenMsgCountView;

    private SharedPreferences prefs;
    private BleMessagingService bleService;
    private boolean isServiceBound = false;
    private AppDatabase database;
    private com.antor.nearbychat.Database.MessageDao messageDao;
    private androidx.lifecycle.LiveData<List<com.antor.nearbychat.Database.MessageEntity>> currentMessagesLiveData = null;

    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_GROUPS_LIST = "groupsList";
    private static final String KEY_FRIENDS_LIST = "friendsList";
    private static final String KEY_USER_ID_BITS = "userIdBits";
    private static final String KEY_NAME_MAP = "nameMap";
    private static final String KEY_BATTERY_OPT_REQUESTED = "batteryOptRequested";

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

    private ImageView sendButton;
    private boolean isAdvertising = false;

    private boolean isKeyboardVisible = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BleMessagingService.LocalBinder binder = (BleMessagingService.LocalBinder) service;
            bleService = binder.getService();
            isServiceBound = true;

            bleService.setAdvertisingStateListener(new BleMessagingService.AdvertisingStateListener() {
                @Override
                public void onAdvertisingStarted() {
                    runOnUiThread(() -> {
                        isAdvertising = true;
                        sendButton.setEnabled(false);
                        sendButton.setColorFilter(Color.GRAY);
                        sendButton.setAlpha(0.5f);
                    });
                }

                @Override
                public void onAdvertisingCompleted() {
                    runOnUiThread(() -> {
                        isAdvertising = false;
                        sendButton.setEnabled(true);
                        sendButton.setColorFilter(null);
                        sendButton.setAlpha(1.0f);
                    });
                }
            });

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
        sendButton = findViewById(R.id.sendButton);
        prefs = getSharedPreferences(PREFS_ACTIVE_CHAT, MODE_PRIVATE);
        loadActiveChat();
        mainHandler = new Handler(Looper.getMainLooper());
        setupUI();
        initializeData();
        setupDatabase();
        markCurrentChatAsRead();
        observeTotalUnreadCount();
        checkBatteryOptimization();
        checkPermissionsAndStartService();
    }
    private void setupUI() {
        final View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int heightDiff = rootView.getRootView().getHeight() - rootView.getHeight();
            boolean keyboardNowVisible = heightDiff > dpToPx(this, 200);

            if (keyboardNowVisible && !isKeyboardVisible) {
                if (messageList != null && !messageList.isEmpty()) {
                    recyclerView.post(() -> recyclerView.smoothScrollToPosition(messageList.size() - 1));
                }
            }
            isKeyboardVisible = keyboardNowVisible;
        });

        inputMessage = findViewById(R.id.inputMessage);
        textStatus = findViewById(R.id.textStatus);
        setupMessageInput();

        recyclerView = findViewById(R.id.chatRecyclerView);
        chatAdapter = new ChatAdapter(messageList, this, this::onMessageClick, this::onMessageLongClick);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(chatAdapter);

        appIcon = findViewById(R.id.appIcon);
        appTitle = findViewById(R.id.appTitle);
        unseenMsgCountView = findViewById(R.id.unseenMsg);
        unseenMsgCountView.setVisibility(View.GONE);

        findViewById(R.id.sendButton).setOnClickListener(this::onSendButtonClick);
        setupAppIconClick();

        LinearLayout titleContainer = findViewById(R.id.titleContainer);
        titleContainer.setOnClickListener(v -> {
            Intent intent = new Intent(this, GroupsFriendsActivity.class);
            startActivityForResult(intent, REQUEST_CODE_SELECT_CHAT);
        });
        appIcon.setOnClickListener(v -> {
            if ("G".equals(activeChatType) && !activeChatId.isEmpty()) {
                showEditGroupDialog();
            } else if ("F".equals(activeChatType) && !activeChatId.isEmpty()) {
                showEditFriendDialog();
            } else {
                Toast.makeText(this, "Only custom groups or friends can be edited.", Toast.LENGTH_SHORT).show();
            }
        });
        updateChatUIForSelection();
    }

    private void setupAppIconClick() {
        findViewById(R.id.threeDotIcon).setOnClickListener(v -> showAccountDialog());
    }

    private void observeTotalUnreadCount() {
        messageDao.getTotalUnreadMessageCount().observe(this, count -> {
            if (unseenMsgCountView == null) return;

            if (count != null && count > 0) {
                unseenMsgCountView.setVisibility(View.VISIBLE);
                unseenMsgCountView.setText(count > 99 ? "99+" : String.valueOf(count));
            } else {
                unseenMsgCountView.setVisibility(View.GONE);
            }
        });
    }

    private void showEditGroupDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String groupsJson = prefs.getString(KEY_GROUPS_LIST, null);
        Type type = new TypeToken<ArrayList<GroupModel>>() {
        }.getType();
        List<GroupModel> groupsList = gson.fromJson(groupsJson, type);
        if (groupsList == null) groupsList = new ArrayList<>();

        GroupModel groupToEdit = null;
        int groupPosition = -1;
        for (int i = 0; i < groupsList.size(); i++) {
            if (groupsList.get(i).getId().equals(activeChatId)) {
                groupToEdit = groupsList.get(i);
                groupPosition = i;
                break;
            }
        }
        if (groupToEdit == null) {
            Toast.makeText(this, "Could not find group to edit.", Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_edit_groups);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        TextView title = dialog.findViewById(R.id.dia_title);
        EditText editName = dialog.findViewById(R.id.editName);
        EditText editKey = dialog.findViewById(R.id.editEncryptionKey);
        Button btnDelete = dialog.findViewById(R.id.btnDelete);
        Button btnCancel = dialog.findViewById(R.id.btnCancel);
        Button btnSave = dialog.findViewById(R.id.btnAdd);

        title.setText("Edit Group");
        btnSave.setText("Save");
        editName.setText(groupToEdit.getName());
        editKey.setText(groupToEdit.getEncryptionKey());
        btnDelete.setVisibility(View.VISIBLE);

        final List<GroupModel> finalGroupsList = groupsList;
        final int finalGroupPosition = groupPosition;
        final GroupModel finalGroupToEdit = groupToEdit;

        TextView groupIdText = dialog.findViewById(R.id.groupID);
        if (groupIdText != null) {
            long bits = MessageHelper.asciiIdToTimestamp(finalGroupToEdit.getId());
            String displayId = getUserIdString(bits);
            groupIdText.setText(displayId);
        }

        ImageView dialogProfilePic = dialog.findViewById(R.id.profilePicRound);
        if (dialogProfilePic != null) {
            dialogProfilePic.setOnClickListener(v -> {
                currentUserId = finalGroupToEdit.getId();
                currentProfilePic = dialogProfilePic;
                showImagePickerDialog(finalGroupToEdit.getId(), dialogProfilePic);
            });
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnDelete.setOnClickListener(v -> {
            finalGroupsList.remove(finalGroupPosition);
            prefs.edit().putString(KEY_GROUPS_LIST, gson.toJson(finalGroupsList)).apply();
            Toast.makeText(this, "Group deleted. Returning to Nearby Chat.", Toast.LENGTH_SHORT).show();
            activeChatType = "N";
            activeChatId = "";
            saveActiveChat();
            updateChatUIForSelection();
            dialog.dismiss();
        });
        btnSave.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            String key = editKey.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Group name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            finalGroupToEdit.setName(name);
            finalGroupToEdit.setEncryptionKey(key);
            finalGroupsList.set(finalGroupPosition, finalGroupToEdit);
            prefs.edit().putString(KEY_GROUPS_LIST, gson.toJson(finalGroupsList)).apply();
            updateChatUIForSelection();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void showEditFriendDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
        Type type = new TypeToken<ArrayList<FriendModel>>() {
        }.getType();
        List<FriendModel> friendsList = gson.fromJson(friendsJson, type);
        if (friendsList == null) friendsList = new ArrayList<>();

        long bits = MessageHelper.asciiIdToTimestamp(activeChatId);
        String displayIdToFind = getUserIdString(bits);

        FriendModel friendToEdit = null;
        int friendPosition = -1;
        for (int i = 0; i < friendsList.size(); i++) {
            if (friendsList.get(i).getDisplayId().equals(displayIdToFind)) {
                friendToEdit = friendsList.get(i);
                friendPosition = i;
                break;
            }
        }
        if (friendToEdit == null) {
            Toast.makeText(this, "Could not find friend to edit.", Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_edit_friend);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        TextView title = dialog.findViewById(R.id.dia_title);
        EditText editName = dialog.findViewById(R.id.editName);
        EditText editId = dialog.findViewById(R.id.editFriendId);
        EditText editKey = dialog.findViewById(R.id.editEncryptionKey);
        Button btnDelete = dialog.findViewById(R.id.btnDelete);
        Button btnCancel = dialog.findViewById(R.id.btnCancel);
        Button btnSave = dialog.findViewById(R.id.btnAdd);

        title.setText("Edit Friend");
        btnSave.setText("Save");
        editName.setText(friendToEdit.getName());
        editId.setText(friendToEdit.getDisplayId());
        editKey.setText(friendToEdit.getEncryptionKey());
        editId.setEnabled(false);
        btnDelete.setVisibility(View.VISIBLE);

        final List<FriendModel> finalFriendsList = friendsList;
        final int finalFriendPosition = friendPosition;
        final FriendModel finalFriendToEdit = friendToEdit;

        ImageView dialogProfilePic = dialog.findViewById(R.id.profilePicRound);
        ProfilePicLoader.loadProfilePicture(this, displayIdToFind, dialogProfilePic);
        if (dialogProfilePic != null) {
            dialogProfilePic.setOnClickListener(v -> {
                currentUserId = displayIdToFind;
                currentProfilePic = dialogProfilePic;
                showImagePickerDialog(displayIdToFind, dialogProfilePic);
            });
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnDelete.setOnClickListener(v -> {
            finalFriendsList.remove(finalFriendPosition);
            prefs.edit().putString(KEY_FRIENDS_LIST, gson.toJson(finalFriendsList)).apply();
            Toast.makeText(this, "Friend deleted. Returning to Nearby Chat.", Toast.LENGTH_SHORT).show();
            activeChatType = "N";
            activeChatId = "";
            saveActiveChat();
            updateChatUIForSelection();
            dialog.dismiss();
        });
        btnSave.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            String key = editKey.getText().toString().trim();

            if (name.isEmpty()) {
                Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            finalFriendToEdit.setName(name);
            finalFriendToEdit.setEncryptionKey(key);

            finalFriendsList.set(finalFriendPosition, finalFriendToEdit);
            prefs.edit().putString(KEY_FRIENDS_LIST, gson.toJson(finalFriendsList)).apply();
            updateChatUIForSelection();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void updateChatUIForSelection() {
        if ("N".equals(activeChatType)) {
            appTitle.setText("Nearby Chat");
            appIcon.setImageResource(R.drawable.nearby);
        } else if ("G".equals(activeChatType)) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String groupsJson = prefs.getString(KEY_GROUPS_LIST, null);
            if (groupsJson != null) {
                Type type = new TypeToken<List<GroupModel>>() {
                }.getType();
                List<GroupModel> groups = gson.fromJson(groupsJson, type);
                String groupName = "Group";
                for (GroupModel g : groups) {
                    if (g.getId().equals(activeChatId)) {
                        groupName = g.getName();
                        break;
                    }
                }
                appTitle.setText(groupName);
            }
            long bits = MessageHelper.asciiIdToTimestamp(activeChatId);
            String displayId = getUserIdString(bits);
            ProfilePicLoader.loadGroupProfilePicture(this, displayId, appIcon);
        } else if ("F".equals(activeChatType)) {
            long bits = MessageHelper.asciiIdToTimestamp(activeChatId);
            String displayId = getUserIdString(bits);

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
            if (friendsJson != null) {
                Type type = new TypeToken<List<FriendModel>>() {
                }.getType();
                List<FriendModel> friends = gson.fromJson(friendsJson, type);
                String friendName = "Friend";
                for (FriendModel f : friends) {
                    if (f.getDisplayId().equals(displayId)) {
                        friendName = f.getName();
                        break;
                    }
                }
                appTitle.setText(friendName);
            }
            ProfilePicLoader.loadProfilePicture(this, displayId, appIcon);
        }
        messageList.clear();
        chatAdapter.notifyDataSetChanged();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            setupDatabase();
            markCurrentChatAsRead();
        }, 100);
    }

    private void loadActiveChat() {
        activeChatType = prefs.getString(KEY_CHAT_TYPE, "N");
        activeChatId = prefs.getString(KEY_CHAT_ID, "");
    }

    private void markCurrentChatAsRead() {
        new Thread(() -> {
            if (database != null && activeChatType != null && activeChatId != null) {
                database.messageDao().markMessagesAsRead(activeChatType, activeChatId);
            }
        }).start();
    }

    private void saveActiveChat() {
        prefs.edit()
                .putString(KEY_CHAT_TYPE, activeChatType)
                .putString(KEY_CHAT_ID, activeChatId)
                .apply();
    }

    private void setupDatabase() {
        database = AppDatabase.getInstance(this);
        messageDao = database.messageDao();

        if (currentMessagesLiveData != null) {
            currentMessagesLiveData.removeObservers(this);
            Log.d(TAG, "Removed old observer");
        }
        Log.d(TAG, "Setting up database for chatType=" + activeChatType + " chatId=" + activeChatId);

        currentMessagesLiveData = messageDao.getMessagesForChat(activeChatType, activeChatId);
        currentMessagesLiveData.observe(this, messages -> {
            Log.d(TAG, "LiveData triggered: received " + (messages != null ? messages.size() : 0) + " messages for " + activeChatType + "/" + activeChatId);

            if (messages != null) {
                boolean wasAtBottom = isAtBottom();

                messageList.clear();
                for (com.antor.nearbychat.Database.MessageEntity entity : messages) {
                    messageList.add(entity.toMessageModel());
                }
                chatAdapter.notifyDataSetChanged();

                if (wasAtBottom && !messageList.isEmpty()) {
                    recyclerView.scrollToPosition(messageList.size() - 1);
                }
                updateChatUI();
                markCurrentChatAsRead();
            }
        });
    }

    private void showAccountDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.account_dialog);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(dialog.getWindow().getAttributes());
        lp.width = dpToPx(this, 280);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        dialog.getWindow().setAttributes(lp);

        TextView textID = dialog.findViewById(R.id.textID);
        ImageView profilePic = dialog.findViewById(R.id.profilePicRound);

        textID.setText(getDisplayName(userId));
        ProfilePicLoader.loadProfilePicture(this, userId, profilePic);

        profilePic.setOnClickListener(v -> {
            currentUserId = userId;
            currentProfilePic = profilePic;
            showImagePickerDialog(userId, profilePic);
        });
        dialog.findViewById(R.id.id_notepad).setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, NotepadActivity.class));
        });
        dialog.findViewById(R.id.id_settings).setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, SettingsActivity.class));
        });
        dialog.findViewById(R.id.id_support_dev).setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, SupportDevActivity.class));
        });
        dialog.findViewById(R.id.id_about).setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, AboutActivity.class));
        });
        dialog.findViewById(R.id.id_restart_app).setOnClickListener(v -> {
            dialog.dismiss();
            restartApp();
        });
        dialog.show();
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
                    Toast.makeText(MainActivity.this, "Message too long (" + s.length() + "/" + MAX_MESSAGE_LENGTH + ")", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void initializeData() {
        initUserId();
        loadNameMap();
    }

    public void openFriendChat(String friendDisplayId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
        List<FriendModel> friends = new ArrayList<>();
        if (friendsJson != null) {
            Type type = new TypeToken<List<FriendModel>>() {
            }.getType();
            friends = gson.fromJson(friendsJson, type);
        }
        boolean exists = friends.stream().anyMatch(f -> f.getDisplayId().equals(friendDisplayId));
        if (!exists) {
            friends.add(new FriendModel(friendDisplayId, getDisplayName(friendDisplayId), ""));
            prefs.edit().putString(KEY_FRIENDS_LIST, gson.toJson(friends)).apply();
            Toast.makeText(this, "Added to Friends: " + getDisplayName(friendDisplayId), Toast.LENGTH_SHORT).show();
        }
        long bits = MessageHelper.displayIdToTimestamp(friendDisplayId);
        String asciiId = MessageHelper.timestampToAsciiId(bits);
        activeChatType = "F";
        activeChatId = asciiId;
        saveActiveChat();
        updateChatUIForSelection();
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
                                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                intent.setData(Uri.parse("package:" + packageName));
                                startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATION);
                            } catch (Exception e) {
                                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                checkAndRequestPermissionsSequentially();
                return;
            }
            if (!bluetoothAdapter.isEnabled()) {
                Toast.makeText(this, "Please turn on Bluetooth", Toast.LENGTH_LONG).show();
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 101);
                return;
            }
            checkAndRequestPermissionsSequentially();
        } catch (SecurityException se) {
            Log.e(TAG, "Bluetooth permission missing", se);
            Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_SHORT).show();
            checkAndRequestPermissionsSequentially();
        }
    }

    private void checkAndRequestPermissionsSequentially() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestSinglePermission(Manifest.permission.BLUETOOTH_CONNECT, "Bluetooth connection required for messaging");
                return;
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestSinglePermission(Manifest.permission.BLUETOOTH_SCAN, "Bluetooth scanning required to find nearby devices");
                return;
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                requestSinglePermission(Manifest.permission.BLUETOOTH_ADVERTISE, "Bluetooth advertising required to be discoverable");
                return;
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showLocationPermissionDialog();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestSinglePermission(Manifest.permission.POST_NOTIFICATIONS, "Notifications required for background service alerts");
                return;
            }
        }
        new Handler(Looper.getMainLooper()).postDelayed(this::startBleService, 500);
    }

    private void requestSinglePermission(String permission, String reason) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
            new AlertDialog.Builder(this).setTitle("Permission Required").setMessage(reason)
                    .setPositiveButton("Grant", (dialog, which) -> ActivityCompat.requestPermissions(this, new String[]{permission}, PERMISSION_REQUEST_CODE))
                    .setNegativeButton("Cancel", (dialog, which) -> Toast.makeText(this, "Permission denied. App may not work properly.", Toast.LENGTH_LONG).show())
                    .show();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{permission}, PERMISSION_REQUEST_CODE);
        }
    }

    private void showLocationPermissionDialog() {
        new AlertDialog.Builder(this).setTitle("Location Permission Required")
                .setMessage("Nearby Chat needs location access to scan for Bluetooth devices. This is required by Android for BLE functionality.")
                .setPositiveButton("Enable Permission", (dialog, which) -> ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_CODE))
                .setNegativeButton("Try Later", (dialog, which) -> Toast.makeText(this, "Location permission required for BLE scanning", Toast.LENGTH_LONG).show())
                .setCancelable(false).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkAndRequestPermissionsSequentially();
            } else {
                Toast.makeText(this, "Permission denied: " + (permissions.length > 0 ? permissions[0] : "Unknown"), Toast.LENGTH_LONG).show();
                new Handler().postDelayed(this::checkAndRequestPermissionsSequentially, 1000);
            }
        } else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (currentUserId != null && currentProfilePic != null)
                    showImagePickerDialogInternal(currentUserId, currentProfilePic);
            } else {
                Toast.makeText(this, "Storage permission required for gallery access", Toast.LENGTH_SHORT).show();
                if (currentUserId != null && currentProfilePic != null)
                    openCamera(currentUserId, currentProfilePic);
            }
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (currentUserId != null && currentProfilePic != null)
                    openCameraInternal(currentUserId, currentProfilePic);
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean hasAllRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                return false;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                return false;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    private void startBleService() {
        if (!hasAllRequiredPermissions()) {
            Toast.makeText(this, "All permissions required for service", Toast.LENGTH_SHORT).show();
            return;
        }
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
            Toast.makeText(this, "Error starting service: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onSendButtonClick(View v) {
        if (isAdvertising) {
            Toast.makeText(this, "Please wait, sending previous message...", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = inputMessage.getText().toString().trim();
        if (msg.isEmpty()) {
            Toast.makeText(this, "Message cannot be empty!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (msg.length() > MAX_MESSAGE_LENGTH) {
            Toast.makeText(this, "Message too long (" + msg.length() + "/" + MAX_MESSAGE_LENGTH + ")", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!validateBluetoothAndService()) {
            return;
        }
        if (isServiceBound && bleService != null) {
            bleService.sendMessage(msg, activeChatType, activeChatId);
            inputMessage.setText("");
            recyclerView.post(() -> {
                if (chatAdapter != null && chatAdapter.getItemCount() > 0) {
                    recyclerView.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
                }
            });
        } else {
            Toast.makeText(this, "Service not ready. Please try again.", Toast.LENGTH_SHORT).show();
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
        if (requestCode == REQUEST_CODE_SELECT_CHAT && resultCode == RESULT_OK && data != null) {
            activeChatType = data.getStringExtra("chatType");
            activeChatId = data.getStringExtra("chatId");
            saveActiveChat();

            Log.d(TAG, "Chat selected: type=" + activeChatType + " id=" + activeChatId);

            updateChatUIForSelection();
            return;
        }
        if (requestCode == REQUEST_ENABLE_LOCATION) {
            if (isLocationEnabled()) requestAllPermissions();
            else Toast.makeText(this, "Location is required", Toast.LENGTH_LONG).show();
        } else if (requestCode == 101) {
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) requestAllPermissions();
            else Toast.makeText(this, "Bluetooth is required", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQUEST_BATTERY_OPTIMIZATION) {
            if (((PowerManager) getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()))
                Toast.makeText(this, "Battery optimization disabled", Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQUEST_GALLERY && resultCode == RESULT_OK) {
            handleGalleryResult(data);
        } else if (requestCode == REQUEST_CAMERA && resultCode == RESULT_OK) {
            handleCameraResult(data);
        }
    }

    private void handleGalleryResult(Intent data) {
        try {
            if (data != null && data.getData() != null && currentProfilePic != null && currentUserId != null) {
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), data.getData());
                Bitmap resizedBitmap = ImageConverter.resizeAndCrop(bitmap, 94, 94);
                currentProfilePic.setImageBitmap(ImageConverter.createCircularBitmap(resizedBitmap));
                saveProfilePicture(currentUserId, resizedBitmap);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing gallery image", e);
        }
    }

    private void handleCameraResult(Intent data) {
        try {
            if (data != null && data.getExtras() != null) {
                Bitmap bitmap = (Bitmap) data.getExtras().get("data");
                if (bitmap != null && currentProfilePic != null && currentUserId != null) {
                    Bitmap resizedBitmap = ImageConverter.resizeAndCrop(bitmap, 94, 94);
                    currentProfilePic.setImageBitmap(ImageConverter.createCircularBitmap(resizedBitmap));
                    saveProfilePicture(currentUserId, resizedBitmap);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing camera image", e);
        }
    }

    private void requestAllPermissions() {
        List<String> permissionsList = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                permissionsList.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                permissionsList.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                permissionsList.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissionsList.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permissionsList.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissionsList.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsList.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            startBleService();
        }
    }

    public void onMessageClick(MessageModel messageModel) {
        if (!messageModel.isComplete() && bleService != null && isServiceBound) {
            List<Integer> missingChunks = messageModel.getMissingChunks();
            if (missingChunks != null && !missingChunks.isEmpty()) {
                bleService.sendMissingPartsRequest(messageModel.getSenderId(), messageModel.getMessageId(), missingChunks);
                Toast.makeText(this, "Requesting missing parts for message: " + messageModel.getMessageId(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void onMessageLongClick(MessageModel msg) {
        if (!msg.isComplete() && !msg.isFailed()) {
            return;
        }
        final List<String> options;

        if (msg.isFailed()) {
            options = new ArrayList<>(Collections.singletonList("Remove"));
        } else {
            options = new ArrayList<>(Arrays.asList("Copy", "Remove"));
            boolean shouldHideRetransmit = "F".equals(activeChatType) && !msg.isSelf();

            if (!shouldHideRetransmit) {
                options.add("Retransmit");
            }
        }
        new AlertDialog.Builder(this).setTitle("Message Options")
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    String selectedOption = options.get(which);
                    switch (selectedOption) {
                        case "Copy":
                            copyMessageToClipboard(msg);
                            break;
                        case "Remove":
                            removeMessage(msg);
                            break;
                        case "Retransmit":
                            if (validateBluetoothAndService()) {
                                Toast.makeText(this, "Retransmitting...", Toast.LENGTH_SHORT).show();
                                if (isServiceBound && bleService != null) {
                                    bleService.retransmitMessage(msg);
                                }
                            }
                            break;
                    }
                }).show();
    }

    private void removeMessage(MessageModel msg) {
        new Thread(() -> {
            try {
                messageDao.deleteMessage(msg.getSenderId(), msg.getMessageId(), msg.getTimestamp());
                runOnUiThread(() -> Toast.makeText(this, "Message removed", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error removing message", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void copyMessageToClipboard(MessageModel msg) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Copied Message", msg.getMessage()));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void restartApp() {
        stopService(new Intent(this, BleMessagingService.class));
        Intent restartIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (restartIntent != null) {
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(restartIntent);
            finish();
            System.exit(0);
        }
    }

    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return lm != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || lm.isLocationEnabled());
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
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        ActivityCompat.requestPermissions(this, new String[]{permission}, REQUEST_STORAGE_PERMISSION);
    }

    private boolean needsStoragePermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED;
    }

    private void showImagePickerDialogInternal(String userId, ImageView profilePic) {
        List<String> options = new ArrayList<>(Arrays.asList("Gallery", "Camera"));
        if (hasCustomProfilePicture(userId)) options.add("Reset to Default");
        new AlertDialog.Builder(this).setTitle("Select Image")
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    switch (options.get(which)) {
                        case "Gallery":
                            openGallery(userId, profilePic);
                            break;
                        case "Camera":
                            openCamera(userId, profilePic);
                            break;
                        case "Reset to Default":
                            resetToGeneratedProfilePic(userId, profilePic);
                            break;
                    }
                }).show();
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
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        openCameraInternal(userId, profilePic);
    }

    private void openCameraInternal(String userId, ImageView profilePic) {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            currentUserId = userId;
            currentProfilePic = profilePic;
            startActivityForResult(intent, REQUEST_CAMERA);
        } else {
            Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasCustomProfilePicture(String userId) {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("profile_" + userId, null) != null;
    }

    private void resetToGeneratedProfilePic(String userId, ImageView imageView) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove("profile_" + userId).apply();
        ProfilePicLoader.loadProfilePicture(this, userId, imageView);
    }

    public void loadProfilePictureForAdapter(String userId, ImageView imageView) {
        ProfilePicLoader.loadProfilePicture(this, userId, imageView);
    }

    private void saveProfilePicture(String userId, Bitmap bitmap) {
        try {
            Bitmap resizedBitmap = ImageConverter.resizeAndCrop(bitmap, 94, 94);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            resizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            String base64Image = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("profile_" + userId, base64Image).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving profile picture", e);
        }
    }

    private String getUserIdString(long bits40) {
        return MessageHelper.timestampToDisplayId(bits40);
    }

    public String getDisplayName(String senderId) {
        return nameMap.getOrDefault(senderId, senderId);
    }

    private boolean isAtBottom() {
        if (recyclerView == null) {
            return true;
        }
        return !recyclerView.canScrollVertically(1);
    }

    private void loadNameMap() {
        try {
            String json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_NAME_MAP, null);
            if (json != null) {
                Type type = new TypeToken<Map<String, String>>() {
                }.getType();
                nameMap = gson.fromJson(json, type);
            }
            if (nameMap == null) nameMap = new HashMap<>();
        } catch (Exception e) {
            nameMap = new HashMap<>();
        }
    }

    private void saveNameMap() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_NAME_MAP, gson.toJson(nameMap)).apply();
    }

    private int dpToPx(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeChatType = prefs.getString(KEY_CHAT_TYPE, "N");
        activeChatId = prefs.getString(KEY_CHAT_ID, "");
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled() && hasAllRequiredPermissions()) {
            startBleService();
        }
    }

    @Override
    public void onBackPressed() {
        if (!"N".equals(activeChatType)) {
            activeChatType = "N";
            activeChatId = "";
            saveActiveChat();
            updateChatUIForSelection();
        } else {
            super.onBackPressed();
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