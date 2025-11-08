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
import android.widget.FrameLayout;
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

import java.util.Map;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ListView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.antor.nearbychat.Database.AppDatabase;
import com.antor.nearbychat.Database.SavedMessageDao;
import com.antor.nearbychat.Database.SavedMessageEntity;
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
    private int inputMode = 0;
    private EditText inputVideoURL;

    private String lastRefreshedChatType = null;
    private String lastRefreshedChatId = null;

    private EditText inputMessage;
    private ImageView switchInputImage;
    private EditText inputImageURL;
    private boolean isImageInputMode = false;
    private RecyclerView recyclerView;
    private ChatAdapter chatAdapter;
    private TextView textStatus;
    private List<MessageModel> messageList = new ArrayList<>();

    private ImageView appIcon;
    private TextView appTitle;
    private TextView unseenMsgCountView;

    private TextView chunkCountView;
    private int currentMaxPayloadSize = 27; // Default value
    private static final int MAX_CHUNKS_LIMIT = 250;

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

    private LinearLayout titleContainer;
    private LinearLayout searchOption;
    private EditText inputSearch;
    private ImageView searchIcon;
    private ImageView closeSearchOptionIcon;
    private boolean isSearchMode = false;
    private androidx.lifecycle.LiveData<List<com.antor.nearbychat.Database.MessageEntity>> searchLiveData = null;


    private ImageView sendButton;
    private ProgressBar sendProgressBar;
    private FrameLayout loadingContainer;
    private FrameLayout sendButtonContainer;
    private boolean isAdvertising = false;

    private boolean isShowingSavedMessages = false;
    private androidx.lifecycle.LiveData<List<com.antor.nearbychat.Database.SavedMessageEntity>> savedMessagesLiveData = null;

    private boolean isKeyboardVisible = false;
    private String currentSearchQuery = "";

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
                        sendButton.setVisibility(View.GONE);
                        loadingContainer.setVisibility(View.VISIBLE);
                    });
                }

                @Override
                public void onAdvertisingCompleted() {
                    runOnUiThread(() -> {
                        isAdvertising = false;
                        sendButton.setVisibility(View.VISIBLE);
                        loadingContainer.setVisibility(View.GONE);
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

        String chatKey = activeChatType + ":" + activeChatId;
        getSharedPreferences("NotificationMessages", MODE_PRIVATE)
                .edit()
                .remove(chatKey)
                .apply();

        sendButton = findViewById(R.id.sendButton);
        prefs = getSharedPreferences(PREFS_ACTIVE_CHAT, MODE_PRIVATE);
        loadActiveChat();
        mainHandler = new Handler(Looper.getMainLooper());
        handleNotificationIntent(getIntent());

        loadChunkSettings(); // <-- ADD THIS LINE

        setupUI();
        initializeData();
        setupDatabase();
        markCurrentChatAsRead();
        observeTotalUnreadCount();
        checkBatteryOptimization();
        checkPermissionsAndStartService();
    }

    // In MainActivity.java

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

        switchInputImage = findViewById(R.id.switchInputImage);
        inputMessage = findViewById(R.id.inputMessage);
        inputImageURL = findViewById(R.id.inputImageURL);
        inputVideoURL = findViewById(R.id.inputVideoURL);

        // ▼▼▼ ADD LISTENER FOR CHUNK COUNT ▼▼▼
        inputImageURL.addTextChangedListener(chunkCalculatorWatcher);
        inputVideoURL.addTextChangedListener(chunkCalculatorWatcher);
        // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

        textStatus = findViewById(R.id.textStatus);
        setupMessageInput();
        setupSearchUI();

        recyclerView = findViewById(R.id.chatRecyclerView);
        switchInputImage.setOnClickListener(v -> toggleInputMode());
        chatAdapter = new ChatAdapter(messageList, this, this::onMessageClick, this::onMessageLongClick);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(chatAdapter);

        appIcon = findViewById(R.id.appIcon);
        appTitle = findViewById(R.id.appTitle);
        unseenMsgCountView = findViewById(R.id.unseenMsg);
        unseenMsgCountView.setVisibility(View.GONE);

        sendButton = findViewById(R.id.sendButton);
        loadingContainer = findViewById(R.id.loadingContainer);
        sendProgressBar = findViewById(R.id.sendProgressBar);
        sendButtonContainer = findViewById(R.id.sendButtonContainer);

        chunkCountView = findViewById(R.id.chunkCount); // <-- ADD THIS LINE

        sendButtonContainer.setOnClickListener(this::onSendButtonClick);

        setupAppIconClick();

        LinearLayout titleContainer = findViewById(R.id.titleContainer);

        titleContainer.setOnClickListener(v -> {
            Intent intent = new Intent(this, GroupsFriendsActivity.class);
            intent.putExtra("currentChatType", activeChatType);
            intent.putExtra("currentChatId", activeChatId);
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
        updateChunkCountUI(); // <-- ADD THIS LINE to initialize count
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent != null && intent.hasExtra("chatType") && intent.hasExtra("chatId")) {
            String notifChatType = intent.getStringExtra("chatType");
            String notifChatId = intent.getStringExtra("chatId");

            Log.d(TAG, "📩 Notification clicked: chatType=" + notifChatType + ", chatId=" + notifChatId);

            if (notifChatType != null && notifChatId != null) {
                activeChatType = notifChatType;
                activeChatId = notifChatId;
                saveActiveChat();

                // UI update করার জন্য delay দিন
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    updateChatUIForSelection();
                }, 500);
            }
        }
    }

    private final TextWatcher chunkCalculatorWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // No action needed
        }
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // No action needed
        }
        @Override
        public void afterTextChanged(Editable s) {
            // Update the chunk count UI whenever text changes in any input
            updateChunkCountUI();
        }
    };

    private boolean isCurrentUserBlocked() {
        if (!"F".equals(activeChatType)) {
            return false;
        }
        try {
            long bits = MessageHelper.asciiIdToTimestamp(activeChatId);
            String displayIdToFind = getUserIdString(bits);

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String json = prefs.getString("blockedList", null);
            if (json == null) {
                return false;
            }
            Type type = new TypeToken<List<String>>(){}.getType();
            List<String> blockedList = gson.fromJson(json, type);

            return blockedList != null && blockedList.contains(displayIdToFind);
        } catch (Exception e) {
            Log.e(TAG, "Error checking if user is blocked", e);
            return false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    private void setupSearchUI() {
        titleContainer = findViewById(R.id.titleContainer);
        searchOption = findViewById(R.id.searchOption);
        inputSearch = findViewById(R.id.inputSearch);
        searchIcon = findViewById(R.id.searchIcon);
        closeSearchOptionIcon = findViewById(R.id.closeSearchOptionIcon);

        searchOption.setVisibility(View.GONE);
        titleContainer.setVisibility(View.VISIBLE);
        searchIcon.setVisibility(View.VISIBLE);

        searchIcon.setOnClickListener(v -> showSearchMode());
        closeSearchOptionIcon.setOnClickListener(v -> hideSearchMode());

        // Real-time search with debouncing
        final Handler searchHandler = new Handler(Looper.getMainLooper());
        final Runnable[] searchRunnable = new Runnable[1];

        inputSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (searchRunnable[0] != null) {
                    searchHandler.removeCallbacks(searchRunnable[0]);
                }

                searchRunnable[0] = () -> {
                    String query = s.toString().trim();
                    currentSearchQuery = query;
                    performSearch(query);
                };

                searchHandler.postDelayed(searchRunnable[0], 300);
            }
        });
        debugDatabaseContent();
    }

    private void toggleInputMode() {
        inputMode = (inputMode + 1) % 3;

        inputMessage.setVisibility(View.GONE);
        inputImageURL.setVisibility(View.GONE);
        inputVideoURL.setVisibility(View.GONE);

        if (inputMode == 0) {
            inputMessage.setVisibility(View.VISIBLE);
            switchInputImage.setImageResource(R.drawable.text);
        } else if (inputMode == 1) {
            inputImageURL.setVisibility(View.VISIBLE);
            switchInputImage.setImageResource(R.drawable.image);
        } else {
            inputVideoURL.setVisibility(View.VISIBLE);
            switchInputImage.setImageResource(R.drawable.video);
        }
    }

    private void performSearch(String query) {
        Log.d(TAG, "🔍 performSearch called with query: '" + query + "'");
        Log.d(TAG, "🔍 Current chat: type=" + activeChatType + ", id=" + activeChatId);

        if (isShowingSavedMessages) {
            Toast.makeText(this, "Cannot search in Saved Messages", Toast.LENGTH_SHORT).show();
            return;
        }

        if (query.isEmpty()) {
            Log.d(TAG, "🔍 Query empty, restoring normal view");
            if (searchLiveData != null) {
                searchLiveData.removeObservers(this);
                searchLiveData = null;
            }
            restoreNormalChatView();
            return;
        }

        // ✅ CHANGED: Get ALL messages from current chat, then filter locally
        if (searchLiveData != null) {
            searchLiveData.removeObservers(this);
        }

        Log.d(TAG, "🔍 Starting search in chat: " + activeChatType + "/" + activeChatId);

        // Get all messages from current chat
        searchLiveData = messageDao.getMessagesForChat(activeChatType, activeChatId);

        searchLiveData.observe(this, messages -> {
            if (messages == null) return;

            Log.d(TAG, "🔍 Total messages received: " + messages.size());

            messageList.clear();

            String lowerQuery = query.toLowerCase();
            int foundCount = 0;

            // ✅ Filter messages by decompressing and searching
            for (com.antor.nearbychat.Database.MessageEntity entity : messages) {
                try {
                    // Decompress the message
                    PayloadCompress.ParsedPayload parsed =
                            PayloadCompress.parsePayload(entity.message);

                    // Search in decompressed text, image URLs, and video URLs
                    boolean matches = false;

                    if (!parsed.message.isEmpty() &&
                            parsed.message.toLowerCase().contains(lowerQuery)) {
                        matches = true;
                    }

                    if (!parsed.imageUrls.isEmpty() &&
                            parsed.imageUrls.toLowerCase().contains(lowerQuery)) {
                        matches = true;
                    }

                    if (!parsed.videoUrls.isEmpty() &&
                            parsed.videoUrls.toLowerCase().contains(lowerQuery)) {
                        matches = true;
                    }

                    if (matches) {
                        messageList.add(entity.toMessageModel());
                        foundCount++;
                        Log.d(TAG, "🔍 Match found: " + parsed.message.substring(0, Math.min(50, parsed.message.length())));
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error decompressing message", e);
                }
            }

            chatAdapter.notifyDataSetChanged();

            if (messageList.isEmpty()) {
                textStatus.setText("No results for \"" + query + "\"");
                textStatus.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
                Log.d(TAG, "🔍 No results found");
            } else {
                textStatus.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                recyclerView.scrollToPosition(0);
                Log.d(TAG, "🔍 Showing " + foundCount + " results out of " + messages.size() + " total messages");
            }
        });
    }

    // Add this method for debugging
    private void debugDatabaseContent() {
        new Thread(() -> {
            try {
                List<com.antor.nearbychat.Database.MessageEntity> allMsgs = messageDao.getAllMessages();
                Log.d(TAG, "🔍 Total messages in DB: " + allMsgs.size());

                for (com.antor.nearbychat.Database.MessageEntity msg : allMsgs) {
                    Log.d(TAG, "🔍 DB Message: chatType=" + msg.chatType +
                            ", chatId=" + msg.chatId +
                            ", message=" + msg.message.substring(0, Math.min(100, msg.message.length())));
                }

                // Now test manual search
                String testQuery = "test"; // Change this to a word you know exists
                runOnUiThread(() -> {
                    messageDao.searchMessagesInChat(activeChatType, activeChatId, testQuery)
                            .observe(this, results -> {
                                Log.d(TAG, "🔍 Manual search results: " + (results != null ? results.size() : "null"));
                                if (results != null) {
                                    for (com.antor.nearbychat.Database.MessageEntity r : results) {
                                        Log.d(TAG, "🔍 Result: " + r.message);
                                    }
                                }
                            });
                });
            } catch (Exception e) {
                Log.e(TAG, "🔍 Debug error", e);
            }
        }).start();
    }

    private void restoreNormalChatView() {
        if (currentMessagesLiveData != null) {
            currentMessagesLiveData.removeObservers(this);
        }

        currentMessagesLiveData = messageDao.getMessagesForChat(activeChatType, activeChatId);

        currentMessagesLiveData.observe(this, messages -> {
            if (messages == null) return;

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
        });
    }

    private void shareApp() {
        try {
            String packageName = getPackageName();
            android.content.pm.ApplicationInfo app = getApplicationContext().getApplicationInfo();
            String apkPath = app.sourceDir;

            java.io.File apkFile = new java.io.File(apkPath);
            android.net.Uri apkUri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".fileprovider",
                        apkFile
                );
            } else {
                apkUri = android.net.Uri.fromFile(apkFile);
            }
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/vnd.android.package-archive");
            shareIntent.putExtra(Intent.EXTRA_STREAM, apkUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Nearby Chat APK");
            shareIntent.putExtra(Intent.EXTRA_TEXT,
                    "Check out Nearby Chat - Offline messaging with Bluetooth!\n\n" +
                            "Facebook: https://www.facebook.com/NearbyChat\n" +
                            "Telegram: https://t.me/NearbyChat");

            startActivity(Intent.createChooser(shareIntent, "Share Nearby Chat APK"));
        } catch (Exception e) {
            Log.e(TAG, "Error sharing APK", e);
            Toast.makeText(this, "Error sharing app", Toast.LENGTH_SHORT).show();
        }
    }

    private void showSearchMode() {
        isSearchMode = true;

        titleContainer.setVisibility(View.GONE);
        searchIcon.setVisibility(View.GONE);

        searchOption.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) searchOption.getLayoutParams();
        params.width = LinearLayout.LayoutParams.MATCH_PARENT;
        params.weight = 1;
        searchOption.setLayoutParams(params);

        inputSearch.setText("");
        inputSearch.requestFocus();

        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(inputSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideSearchMode() {
        isSearchMode = false;
        currentSearchQuery = "";

        searchOption.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) searchOption.getLayoutParams();
        params.width = 0;
        params.weight = 0;
        searchOption.setLayoutParams(params);

        titleContainer.setVisibility(View.VISIBLE);
        searchIcon.setVisibility(View.VISIBLE);

        inputSearch.setText("");

        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(inputSearch.getWindowToken(), 0);
        }

        if (searchLiveData != null) {
            searchLiveData.removeObservers(this);
            searchLiveData = null;
        }

        restoreNormalChatView();
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
        List<GroupModel> groupsList = DataCache.getGroups(this);
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
        final GroupModel g = groupToEdit;
        final int pos = groupPosition;
        final List<GroupModel> listRef = groupsList;

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_edit_groups);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        TextView title = dialog.findViewById(R.id.dia_title);
        ImageView switchNotification = dialog.findViewById(R.id.switchNotification);
        EditText editName = dialog.findViewById(R.id.editName);
        EditText editKey = dialog.findViewById(R.id.editEncryptionKey);
        Button btnCancel = dialog.findViewById(R.id.btnCancel);
        Button btnSave = dialog.findViewById(R.id.btnAdd);
        ImageView qrCodeShow = dialog.findViewById(R.id.qrCodeShow);

        // ▼▼▼ এই লেআউটে কোনো ডিলিট বাটন নেই, তাই XML অনুযায়ী এটি ঠিক আছে ▼▼▼
        // dialog_add_edit_groups.xml-এ 'btnDelete' নামের কোনো আইডি নেই।
        // যদি থাকতো, তাহলে আমরা নিচের লাইনটি যোগ করতাম:
        // Button btnDelete = dialog.findViewById(R.id.btnDelete);
        // if (btnDelete != null) {
        //     btnDelete.setVisibility(View.GONE);
        // }
        // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

        title.setText("Edit Group");
        btnSave.setText("Save");
        editName.setText(g.getName());
        editKey.setText(g.getEncryptionKey());

        TextView groupIdText = dialog.findViewById(R.id.groupID);
        if (groupIdText != null) {
            long bits = MessageHelper.asciiIdToTimestamp(g.getId());
            String displayId = getUserIdString(bits);
            groupIdText.setText(displayId);
        }

        ImageView dialogProfilePic = dialog.findViewById(R.id.profilePicRound);
        if (dialogProfilePic != null) {
            // গ্রুপ প্রোফাইল পিক লোড করার জন্য সঠিক মেথড কল করুন
            long bits = MessageHelper.asciiIdToTimestamp(g.getId());
            String displayId = getUserIdString(bits);
            ProfilePicLoader.loadGroupProfilePicture(this, displayId, dialogProfilePic);

            dialogProfilePic.setOnClickListener(v -> {
                // গ্রুপের ডিসপ্লে আইডি পাস করুন
                long bitsId = MessageHelper.asciiIdToTimestamp(g.getId());
                String displayIdForPic = getUserIdString(bitsId);

                currentUserId = displayIdForPic; // এখানে ডিসপ্লে আইডি ব্যবহার করুন
                currentProfilePic = dialogProfilePic;
                showImagePickerDialog(displayIdForPic, dialogProfilePic);
            });
        }

        if (qrCodeShow != null) {
            qrCodeShow.setVisibility(View.VISIBLE);
            qrCodeShow.setOnClickListener(v -> {
                long bits = MessageHelper.asciiIdToTimestamp(g.getId());
                String displayId = getUserIdString(bits);
                String plainText = displayId + "|" + g.getName() + "|" + g.getEncryptionKey();
                String encryptedData = QREncryption.encrypt(plainText);
                String qrData = "GROUP:" + encryptedData;

                Intent intent = new Intent(this, QRCodeActivity.class);
                intent.putExtra("qr_data", qrData);
                intent.putExtra("qr_type", "group");
                intent.putExtra("display_name", g.getName());
                startActivity(intent);
            });
        }

        boolean isNotificationEnabled = getNotificationState("G", g.getId());
        switchNotification.setImageResource(
                isNotificationEnabled ? R.drawable.ic_enable_notification : R.drawable.ic_disable_notification
        );

        switchNotification.setOnClickListener(v -> {
            boolean newState = !getNotificationState("G", g.getId());
            saveNotificationState("G", g.getId(), newState);

            switchNotification.setImageResource(
                    newState ? R.drawable.ic_enable_notification : R.drawable.ic_disable_notification
            );

            Toast.makeText(this, newState ? "Notifications enabled" : "Notifications disabled",
                    Toast.LENGTH_SHORT).show();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            String key = editKey.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Group name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            g.setName(name);
            g.setEncryptionKey(key);
            listRef.set(pos, g);
            DataCache.saveGroups(this, listRef);
            updateChatUIForSelection();
            dialog.dismiss();
        });

        dialog.show();
    }

    private boolean getNotificationState(String chatType, String chatId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String key = chatType + ":" + chatId;
        return prefs.getBoolean("notification_" + key, false); // Default: false (disabled)
    }

    private void saveNotificationState(String chatType, String chatId, boolean enabled) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String key = chatType + ":" + chatId;
        prefs.edit().putBoolean("notification_" + key, enabled).apply();
    }

    private void showEditFriendDialog() {
        List<FriendModel> friendsList = DataCache.getFriends(this);

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

        // যদি ফ্রেন্ড লিস্টে না থাকে (যেমন, ব্লক করার পর ডায়লগ খোলা)
        if (friendToEdit == null) {
            String name = getDisplayName(displayIdToFind); // নাম ম্যাপ চেক করবে
            if (name.equals(displayIdToFind)) name = ""; // নাম না পেলে খালি রাখুন
            friendToEdit = new FriendModel(displayIdToFind, name, "");
            friendPosition = -1;
        }

        final FriendModel f = friendToEdit;
        final int pos = friendPosition;
        final List<FriendModel> listRef = friendsList;
        final String displayId = displayIdToFind;

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_edit_friend);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        TextView title = dialog.findViewById(R.id.dia_title);
        ImageView switchNotification = dialog.findViewById(R.id.switchNotification);
        EditText editName = dialog.findViewById(R.id.editName);
        EditText editId = dialog.findViewById(R.id.editFriendId);
        EditText editKey = dialog.findViewById(R.id.editEncryptionKey);

        // ▼▼▼ এখান থেকে পরিবর্তন শুরু ▼▼▼
        Button btnBlockUnblock = dialog.findViewById(R.id.btnDelete); // XML-এর btnDelete আইডি ব্যবহার করা হচ্ছে
        // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

        Button btnCancel = dialog.findViewById(R.id.btnCancel);
        Button btnSave = dialog.findViewById(R.id.btnAdd);
        ImageView qrCodeShow = dialog.findViewById(R.id.qrCodeShow);

        title.setText("Edit Friend");
        btnSave.setText("Save");
        editName.setText(f.getName());
        editId.setText(f.getDisplayId());
        editKey.setText(f.getEncryptionKey());
        editId.setEnabled(false);

        // ▼▼▼ এখান থেকে পরিবর্তন শুরু ▼▼▼
        btnBlockUnblock.setVisibility(View.VISIBLE); // বাটনটি সবসময় দেখান

        ImageView dialogProfilePic = dialog.findViewById(R.id.profilePicRound);
        ProfilePicLoader.loadProfilePicture(this, displayId, dialogProfilePic);
        if (dialogProfilePic != null) {
            dialogProfilePic.setOnClickListener(v -> {
                currentUserId = displayId;
                currentProfilePic = dialogProfilePic;
                showImagePickerDialog(displayId, dialogProfilePic);
            });
        }

        // (বাকি কোড যেমন qrCodeShow, switchNotification অপরিবর্তিত)
        if (qrCodeShow != null) {
            qrCodeShow.setVisibility(View.VISIBLE);
            qrCodeShow.setOnClickListener(v -> {
                // নামের জন্য EditText থেকে বর্তমান ভ্যালু নিন
                String currentName = editName.getText().toString().trim();
                if (currentName.isEmpty()) currentName = f.getName();

                String plainText = f.getDisplayId() + "|" + currentName + "|" + editKey.getText().toString().trim();
                String encryptedData = QREncryption.encrypt(plainText);
                String qrData = "FRIEND:" + encryptedData;

                Intent intent = new Intent(this, QRCodeActivity.class);
                intent.putExtra("qr_data", qrData);
                intent.putExtra("qr_type", "friend");
                intent.putExtra("display_name", currentName);
                startActivity(intent);
            });
        }

        String friendChatId = MessageHelper.timestampToAsciiId(
                MessageHelper.displayIdToTimestamp(displayId)
        );

        boolean isNotificationEnabled = getNotificationState("F", friendChatId);
        switchNotification.setImageResource(
                isNotificationEnabled ? R.drawable.ic_enable_notification : R.drawable.ic_disable_notification
        );

        switchNotification.setOnClickListener(v -> {
            boolean newState = !getNotificationState("F", friendChatId);
            saveNotificationState("F", friendChatId, newState);
            switchNotification.setImageResource(
                    newState ? R.drawable.ic_enable_notification : R.drawable.ic_disable_notification
            );
            Toast.makeText(this, newState ? "Notifications enabled" : "Notifications disabled",
                    Toast.LENGTH_SHORT).show();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        // --- নতুন Block/Unblock লজিক ---
        final boolean isBlocked = isUserBlocked(displayId);
        if (isBlocked) {
            btnBlockUnblock.setText("Unblock");
            btnBlockUnblock.setBackgroundColor(Color.parseColor("#007BFF")); // Blue
        } else {
            btnBlockUnblock.setText("Block");
            btnBlockUnblock.setBackgroundColor(Color.parseColor("#DC3545")); // Red
        }

        btnBlockUnblock.setOnClickListener(v -> {
            String currentName = editName.getText().toString().trim();
            if (currentName.isEmpty()) currentName = f.getName(); // স্টোর করা নাম
            if (currentName.isEmpty()) currentName = displayId;  // ফলব্যাক

            if (isBlocked) {
                unblockUser(displayId, currentName);
            } else {
                blockUser(displayId, currentName);
            }
            dialog.dismiss();

            // UI আপডেট করুন (GroupsFriendsActivity-তে যাওয়ার জন্য)
            Intent intent = new Intent(this, GroupsFriendsActivity.class);
            intent.putExtra("currentChatType", activeChatType);
            intent.putExtra("currentChatId", activeChatId);
            startActivityForResult(intent, REQUEST_CODE_SELECT_CHAT);
        });

        // --- পুরনো ডিলিট লজিক সরিয়ে ফেলা হয়েছে ---

        // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

        btnSave.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            String key = editKey.getText().toString().trim();

            f.setName(name);
            f.setEncryptionKey(key);

            boolean exists = false;
            for (int i = 0; i < listRef.size(); i++) {
                if (listRef.get(i).getDisplayId().equals(f.getDisplayId())) {
                    listRef.set(i, f);
                    exists = true;
                    break;
                }
            }
            if (!exists) listRef.add(f);

            DataCache.saveFriends(this, listRef);
            updateChatUIForSelection();
            Toast.makeText(this, "Friend saved", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    // In MainActivity.java, add this NEW public method:

    public void showEditFriendDialogForSender(String senderDisplayId) {
        List<FriendModel> friendsList = DataCache.getFriends(this);

        FriendModel friendToEdit = null;
        int friendPosition = -1;
        for (int i = 0; i < friendsList.size(); i++) {
            if (friendsList.get(i).getDisplayId().equals(senderDisplayId)) {
                friendToEdit = friendsList.get(i);
                friendPosition = i;
                break;
            }
        }

        // If friend isn't in the list, create a temporary model
        if (friendToEdit == null) {
            // Use getDisplayName to check nameMap AND friends list
            String name = getDisplayName(senderDisplayId);
            if (name.equals(senderDisplayId)) { // If name is just the ID
                friendToEdit = new FriendModel(senderDisplayId, "", "");
            } else {
                friendToEdit = new FriendModel(senderDisplayId, name, "");
            }
            friendPosition = -1; // Not in the list
        }

        final FriendModel f = friendToEdit;
        final int pos = friendPosition;
        final List<FriendModel> listRef = friendsList;
        final String displayId = senderDisplayId; // Use the passed-in ID

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_edit_friend);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        TextView title = dialog.findViewById(R.id.dia_title);
        ImageView switchNotification = dialog.findViewById(R.id.switchNotification);
        EditText editName = dialog.findViewById(R.id.editName);
        EditText editId = dialog.findViewById(R.id.editFriendId);
        EditText editKey = dialog.findViewById(R.id.editEncryptionKey);
        Button btnBlockUnblock = dialog.findViewById(R.id.btnDelete);
        Button btnCancel = dialog.findViewById(R.id.btnCancel);
        Button btnSave = dialog.findViewById(R.id.btnAdd);
        ImageView qrCodeShow = dialog.findViewById(R.id.qrCodeShow);

        title.setText("Edit Friend");
        btnSave.setText("Save");
        editName.setText(f.getName());
        editId.setText(f.getDisplayId());
        editKey.setText(f.getEncryptionKey());
        editId.setEnabled(false);
        btnBlockUnblock.setVisibility(View.VISIBLE); // Always show

        ImageView dialogProfilePic = dialog.findViewById(R.id.profilePicRound);
        ProfilePicLoader.loadProfilePicture(this, displayId, dialogProfilePic);
        if (dialogProfilePic != null) {
            dialogProfilePic.setOnClickListener(v -> {
                currentUserId = displayId;
                currentProfilePic = dialogProfilePic;
                showImagePickerDialog(displayId, dialogProfilePic);
            });
        }

        if (qrCodeShow != null) {
            qrCodeShow.setVisibility(View.VISIBLE);
            qrCodeShow.setOnClickListener(v -> {
                String plainText = f.getDisplayId() + "|" + editName.getText().toString().trim() + "|" + editKey.getText().toString().trim();
                String encryptedData = QREncryption.encrypt(plainText);
                String qrData = "FRIEND:" + encryptedData;

                Intent intent = new Intent(this, QRCodeActivity.class);
                intent.putExtra("qr_data", qrData);
                intent.putExtra("qr_type", "friend");
                intent.putExtra("display_name", editName.getText().toString().trim());
                startActivity(intent);
            });
        }

        String friendChatId = MessageHelper.timestampToAsciiId(
                MessageHelper.displayIdToTimestamp(displayId)
        );

        boolean isNotificationEnabled = getNotificationState("F", friendChatId);
        switchNotification.setImageResource(
                isNotificationEnabled ? R.drawable.ic_enable_notification : R.drawable.ic_disable_notification
        );

        switchNotification.setOnClickListener(v -> {
            boolean newState = !getNotificationState("F", friendChatId);
            saveNotificationState("F", friendChatId, newState);

            switchNotification.setImageResource(
                    newState ? R.drawable.ic_enable_notification : R.drawable.ic_disable_notification
            );

            Toast.makeText(this, newState ? "Notifications enabled" : "Notifications disabled",
                    Toast.LENGTH_SHORT).show();
        });

        // BLOCK/UNBLOCK LOGIC
        final boolean isBlocked = isUserBlocked(displayId);
        if (isBlocked) {
            btnBlockUnblock.setText("Unblock");
            btnBlockUnblock.setBackgroundColor(Color.parseColor("#007BFF")); // Blue
        } else {
            btnBlockUnblock.setText("Block");
            btnBlockUnblock.setBackgroundColor(Color.parseColor("#DC3545")); // Red
        }

        btnBlockUnblock.setOnClickListener(v -> {
            String currentName = editName.getText().toString().trim();
            if (currentName.isEmpty()) currentName = f.getName(); // use stored name
            if (currentName.isEmpty()) currentName = displayId;  // use ID as fallback

            if (isBlocked) {
                unblockUser(displayId, currentName);
            } else {
                blockUser(displayId, currentName);
            }
            dialog.dismiss();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            String key = editKey.getText().toString().trim();

            f.setName(name);
            f.setEncryptionKey(key);

            boolean exists = false;
            for (int i = 0; i < listRef.size(); i++) {
                if (listRef.get(i).getDisplayId().equals(f.getDisplayId())) {
                    listRef.set(i, f);
                    exists = true;
                    break;
                }
            }
            if (!exists) listRef.add(f);

            DataCache.saveFriends(this, listRef);

            // If this is the active chat, update the title bar
            if (isChatWithUser(activeChatType, activeChatId, displayId)) {
                updateChatUIForSelection();
            }

            Toast.makeText(this, "Friend saved", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    private boolean isUserBlocked(String displayId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString("blockedList", null);
        if (json == null) return false;
        Type type = new TypeToken<List<String>>(){}.getType();
        List<String> blockedList = gson.fromJson(json, type);
        return blockedList != null && blockedList.contains(displayId);
    }

    private void blockUser(String displayId, String name) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString("blockedList", null);
        List<String> blockedList;
        if (json != null) {
            Type type = new TypeToken<List<String>>(){}.getType();
            blockedList = gson.fromJson(json, type);
        } else {
            blockedList = new ArrayList<>();
        }
        if (!blockedList.contains(displayId)) {
            blockedList.add(displayId);
            prefs.edit().putString("blockedList", gson.toJson(blockedList)).apply();
        }
        // Remove from friends list
        List<FriendModel> friends = DataCache.getFriends(this);
        friends.removeIf(f -> f.getDisplayId().equals(displayId));
        DataCache.saveFriends(this, friends);

        String toastName = (name == null || name.isEmpty()) ? displayId : name;
        Toast.makeText(this, "User " + toastName + " blocked", Toast.LENGTH_SHORT).show();

        // If the blocked user is the current chat, switch to Nearby
        if (isChatWithUser(activeChatType, activeChatId, displayId)) {
            activeChatType = "N";
            activeChatId = "";
            saveActiveChat();
            updateChatUIForSelection();
        }
    }

    private void unblockUser(String displayId, String name) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString("blockedList", null);
        List<String> blockedList;
        if (json != null) {
            Type type = new TypeToken<List<String>>(){}.getType();
            blockedList = gson.fromJson(json, type);
        } else {
            return; // Not blocked anyway
        }
        if (blockedList.contains(displayId)) {
            blockedList.remove(displayId);
            prefs.edit().putString("blockedList", gson.toJson(blockedList)).apply();
        }
        // Add back to friends list
        List<FriendModel> friends = DataCache.getFriends(this);
        boolean exists = friends.stream().anyMatch(f -> f.getDisplayId().equals(displayId));
        if (!exists) {
            String friendName = (name == null || name.isEmpty()) ? displayId : name;
            friends.add(new FriendModel(displayId, friendName, ""));
            DataCache.saveFriends(this, friends);
        }
        String toastName = (name == null || name.isEmpty()) ? displayId : name;
        Toast.makeText(this, "User " + toastName + " unblocked", Toast.LENGTH_SHORT).show();
    }

    // Helper to check if current chat is the user being blocked
    private boolean isChatWithUser(String chatType, String chatId, String displayId) {
        if (!"F".equals(chatType)) return false;
        try {
            long bits = MessageHelper.asciiIdToTimestamp(chatId);
            String currentDisplayId = getUserIdString(bits);
            return currentDisplayId.equals(displayId);
        } catch (Exception e) {
            return false;
        }
    }

    private void updateChatUIForSelection() {

        // ===== START: ইনপুট কন্ট্রোল লজিক আপডেট করুন =====
        boolean inputEnabled = true;
        String hint = "Type your message...";

        if ("N".equals(activeChatType)) {
            appTitle.setText("Nearby Chat");
            appIcon.setImageResource(R.drawable.nearby);

        } else if ("G".equals(activeChatType)) {
            List<GroupModel> groups = DataCache.getGroups(this);
            String groupName = "Group";

            for (GroupModel g : groups) {
                if (g.getId().equals(activeChatId)) {
                    groupName = g.getName();
                    break;
                }
            }

            appTitle.setText(groupName);
            long bits = MessageHelper.asciiIdToTimestamp(activeChatId);
            String displayId = getUserIdString(bits);
            ProfilePicLoader.loadGroupProfilePicture(this, displayId, appIcon);

        } else if ("F".equals(activeChatType)) {
            long bits = MessageHelper.asciiIdToTimestamp(activeChatId);
            String displayId = getUserIdString(bits);
            List<FriendModel> friends = DataCache.getFriends(this);

            String friendName = displayId; // default fallback
            for (FriendModel f : friends) {
                if (f.getDisplayId().equals(displayId)) {
                    if (f.getName() != null && !f.getName().isEmpty()) {
                        friendName = f.getName();
                    }
                    break;
                }
            }

            appTitle.setText(friendName);
            ProfilePicLoader.loadProfilePicture(this, displayId, appIcon);

            // ফ্রেন্ড চ্যাটে চেক করুন ইউজার ব্লকড কিনা
            if (isCurrentUserBlocked()) {
                inputEnabled = false;
                hint = "This user is blocked";
            }
        }

        // "Saved Messages" ভিউ সবকিছুর উপরে override করবে
        if (isShowingSavedMessages) {
            inputEnabled = false;
            hint = "Disable"; // ✅ এখানে পরিবর্তন করা হয়েছে
        }

        // ইনপুট বক্সের state সেট করুন
        if (inputEnabled) {
            enableInputContainer();
        } else {
            disableInputContainer();
            inputMessage.setHint(hint); // কাস্টম hint সেট করুন
        }
        // ===== END: ইনপুট কন্ট্রোল লজিক আপডেট করুন =====

        // ===== START: চ্যাট সুইচ করলে ইনপুট মোড রিসেট করুন =====
        inputMode = 0;
        inputMessage.setVisibility(View.VISIBLE);
        inputImageURL.setVisibility(View.GONE);
        inputVideoURL.setVisibility(View.GONE);
        switchInputImage.setImageResource(R.drawable.text);
        // ===== END: চ্যাট সুইচ করলে ইনপুট মোড রিসেট করুন =====

        messageList.clear();
        chatAdapter.notifyDataSetChanged();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            setupDatabase();
            markCurrentChatAsRead();
        }, 100);


        // ▼▼▼ এই দুটি লাইন শেষে যোগ করা হয়েছে ▼▼▼
        // মনে রাখুন যে এই চ্যাটটি সর্বশেষ রিলোড করা হয়েছে
        lastRefreshedChatType = activeChatType;
        lastRefreshedChatId = activeChatId;
        Log.d(TAG, "updateChatUIForSelection: Refreshed trackers to " + activeChatType + "/" + activeChatId);
        // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲
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

        ImageView qrCodeShow = dialog.findViewById(R.id.qrCodeShow);
        TextView textID = dialog.findViewById(R.id.textID);
        TextView textName = dialog.findViewById(R.id.textName);
        ImageView profilePic = dialog.findViewById(R.id.profilePicRound);

        textID.setText(userId);

        String displayName = nameMap.get(userId);
        if (displayName == null || displayName.isEmpty()) {
            textName.setText("Unknown_Name");
        } else {
            textName.setText(displayName);
        }

        ProfilePicLoader.loadProfilePicture(this, userId, profilePic);

        if (qrCodeShow != null) {
            qrCodeShow.setOnClickListener(v -> {
                dialog.dismiss();
                showMyQRCode();
            });
        }
        profilePic.setOnClickListener(v -> {
            currentUserId = userId;
            currentProfilePic = profilePic;
            showImagePickerDialog(userId, profilePic);
        });
        textName.setOnClickListener(v -> {
            dialog.dismiss();
            showEditMyProfileDialog();
        });
        textID.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("User ID", userId));
                Toast.makeText(this, "ID copied: " + userId, Toast.LENGTH_SHORT).show();
            }
        });
        dialog.findViewById(R.id.id_saved).setOnClickListener(v -> {
            dialog.dismiss();
            showSavedMessages();
        });
        dialog.findViewById(R.id.id_notepad).setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, NotepadActivity.class));
        });
        dialog.findViewById(R.id.id_blocked_list).setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(this, BlockActivity.class);
            intent.putExtra("currentChatType", activeChatType);
            intent.putExtra("currentChatId", activeChatId);
            startActivity(intent);
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
        dialog.findViewById(R.id.id_share_app).setOnClickListener(v -> {
            dialog.dismiss();
            shareApp();
        });
        dialog.findViewById(R.id.id_restart_app).setOnClickListener(v -> {
            dialog.dismiss();
            restartApp();
        });
        dialog.show();
    }

    private void showMyQRCode() {
        String displayName = nameMap.get(userId);
        if (displayName == null || displayName.isEmpty()) {
            displayName = "Unknown_Name";
        }
        String plainText = userId + "|" + displayName + "|";
        String encryptedData = QREncryption.encrypt(plainText);
        String qrData = "FRIEND:" + encryptedData;

        Intent intent = new Intent(this, QRCodeActivity.class);
        intent.putExtra("qr_data", qrData);
        intent.putExtra("qr_type", "friend");
        intent.putExtra("display_name", displayName);
        startActivity(intent);
    }

    private void setupMessageInput() {
        // Remove old listener
        // inputMessage.addTextChangedListener(new TextWatcher() { ... });

        // Add the new listener
        inputMessage.addTextChangedListener(chunkCalculatorWatcher);
    }

    private void initializeData() {
        initUserId();
        loadNameMap();
    }

    public void openFriendChat(String friendDisplayId) {
        long bits = MessageHelper.displayIdToTimestamp(friendDisplayId);
        String asciiId = MessageHelper.timestampToAsciiId(bits);
        activeChatType = "F";
        activeChatId = asciiId;
        saveActiveChat();
        updateChatUIForSelection();
    }

    private void showEditMyProfileDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_edit_me);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        EditText editName = dialog.findViewById(R.id.editName);
        Button btnCancel = dialog.findViewById(R.id.btnCancel);
        Button btnAdd = dialog.findViewById(R.id.btnAdd);

        String currentName = getDisplayName(userId);
        editName.setText(currentName);
        btnAdd.setText("Save");

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnAdd.setOnClickListener(v -> {
            String newName = editName.getText().toString().trim();
            if (newName.isEmpty()) {
                Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            nameMap.put(userId, newName);
            saveNameMap();

            Toast.makeText(this, "Name updated", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
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

    // In MainActivity.java

    // ▼▼▼ ADD THESE THREE NEW METHODS ▼▼▼

    /**
     * Loads chunk-related settings from SharedPreferences.
     */
    private void loadChunkSettings() {
        SharedPreferences prefs = getSharedPreferences("NearbyChatSettings", MODE_PRIVATE);
        currentMaxPayloadSize = prefs.getInt("MAX_PAYLOAD_SIZE", 27);
        if (currentMaxPayloadSize < 20) {
            currentMaxPayloadSize = 20;
        }
    }

    /**
     * Calculates the estimated number of chunks for the current message.
     * This logic mirrors MessageConverterForBle.
     */
    private int calculateCurrentChunkCount() {
        if (inputMessage == null || inputImageURL == null || inputVideoURL == null) {
            return 0; // UI not ready
        }

        String textMsg = inputMessage.getText().toString().trim();
        String imageUrlsRaw = inputImageURL.getText().toString().trim();
        String videoUrlsRaw = inputVideoURL.getText().toString().trim();

        // 1. Build the payload
        String compressedPayload = PayloadCompress.buildPayload(textMsg, imageUrlsRaw, videoUrlsRaw);

        // 2. Encrypt if necessary
        String messagePayload;
        if ("N".equals(activeChatType)) {
            messagePayload = compressedPayload;
        } else {
            // Need 'userId' (my display ID) for friend chat key fallback
            String password = MessageHelper.getPasswordForChat(this, activeChatType, activeChatId, userId);
            messagePayload = CryptoUtils.encrypt(compressedPayload, password);
        }

        if (messagePayload == null || messagePayload.isEmpty()) {
            return 0; // Empty message
        }

        byte[] messageBytes = messagePayload.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        // 3. Define header sizes
        final int BASE_HEADER_SIZE = 13; // CHAT_TYPE(1) + USER_ID(5) + MSG_ID(5) + CHUNKS(2)
        final int FIRST_CHUNK_GF_HEADER_SIZE = 18; // BASE_HEADER_SIZE + CHAT_ID(5)

        // 4. Define data sizes per chunk
        int firstChunkDataSize;
        int nextChunkDataSize;

        if ("N".equals(activeChatType)) {
            firstChunkDataSize = currentMaxPayloadSize - BASE_HEADER_SIZE;
            nextChunkDataSize = currentMaxPayloadSize - BASE_HEADER_SIZE;
        } else {
            firstChunkDataSize = currentMaxPayloadSize - FIRST_CHUNK_GF_HEADER_SIZE;
            nextChunkDataSize = currentMaxPayloadSize - BASE_HEADER_SIZE;
        }

        if (firstChunkDataSize <= 0 || nextChunkDataSize <= 0) {
            // Failsafe if MAX_PAYLOAD_SIZE is set ridiculously low
            return 999;
        }

        // 5. Calculate total chunks
        if (messageBytes.length == 0) {
            return 1; // Empty message is still 1 chunk
        }

        int totalChunks = 0;
        int offset = 0;
        boolean isFirst = true;

        while (offset < messageBytes.length) {
            int chunkSize = (isFirst) ? firstChunkDataSize : nextChunkDataSize;
            offset += chunkSize;
            totalChunks++;
            isFirst = false;
        }

        return totalChunks;
    }

    /**
     * Updates the chunk count TextView and its color.
     */
    private void updateChunkCountUI() {
        if (chunkCountView == null) {
            return; // View not initialized yet
        }

        int chunkCount = calculateCurrentChunkCount();
        chunkCountView.setText(String.valueOf(chunkCount));

        if (chunkCount > MAX_CHUNKS_LIMIT) {
            chunkCountView.setBackgroundResource(R.drawable.bg_round_chunk_red);
        } else {
            chunkCountView.setBackgroundResource(R.drawable.bg_round_chunk);
        }
    }
    // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

    private void onSendButtonClick(View v) {
        if (isAdvertising) {
            if (bleService != null && isServiceBound) {
                bleService.cancelAdvertising();
            }
            return;
        }
        if (isShowingSavedMessages) {
            Toast.makeText(this, "Cannot send messages from Saved view", Toast.LENGTH_SHORT).show();
            return;
        }

        String textMsg = inputMessage.getText().toString().trim();
        String imageUrlsRaw = inputImageURL.getText().toString().trim();
        String videoUrlsRaw = inputVideoURL.getText().toString().trim();

        if (textMsg.isEmpty() && imageUrlsRaw.isEmpty() && videoUrlsRaw.isEmpty()) {
            Toast.makeText(this, "Message cannot be empty!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Validation + send logic (existing code continues...)
        if (!validateAllLinks(imageUrlsRaw, videoUrlsRaw)) {
            Toast.makeText(this, "Invalid link format. Please check URLs.", Toast.LENGTH_LONG).show();
            return;
        }

        // ▼▼▼ REPLACE LENGTH CHECK WITH CHUNK CHECK ▼▼▼
        int chunkCount = calculateCurrentChunkCount();
        if (chunkCount > MAX_CHUNKS_LIMIT) {
            Toast.makeText(this, "Message too large (" + chunkCount + "/" + MAX_CHUNKS_LIMIT + " chunks)", Toast.LENGTH_LONG).show();
            return;
        }
        // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

        String compressedPayload = PayloadCompress.buildPayload(textMsg, imageUrlsRaw, videoUrlsRaw);

        // REMOVED old MAX_MESSAGE_LENGTH check

        if (!validateBluetoothAndService()) {
            return;
        }

        if (isServiceBound && bleService != null) {
            bleService.sendMessage(compressedPayload, activeChatType, activeChatId);

            inputMessage.setText("");
            inputImageURL.setText("");
            inputVideoURL.setText("");

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

        if (requestCode == REQUEST_CODE_SELECT_CHAT) {
            if (resultCode == RESULT_OK && data != null) {
                // একটি নতুন চ্যাট সিলেক্ট করা হয়েছে।
                // আমরা শুধু activeChatType এবং activeChatId আপডেট করবো।
                // onResume() মেথডটি এরপর নিজে থেকেই কল হবে এবং
                // সেখানের নতুন লজিকটি পরিবর্তনটি সনাক্ত করে UI রিলোড করবে।

                activeChatType = data.getStringExtra("chatType");
                activeChatId = data.getStringExtra("chatId");
                saveActiveChat();
                Log.d(TAG, "onActivityResult: New chat selected (" + activeChatType + "/" + activeChatId + "). onResume will handle reload.");

                if (isShowingSavedMessages) {
                    isShowingSavedMessages = false;
                    enableInputContainer();
                    if (savedMessagesLiveData != null) {
                        savedMessagesLiveData.removeObservers(this);
                        savedMessagesLiveData = null;
                    }
                }

                // updateChatUIForSelection(); // <-- আমরা এখান থেকে রিলোড কলটি সরিয়ে দিয়েছি
            } else {
                // resultCode == RESULT_CANCELED (অর্থাৎ Back বা outside-click)
                // কিছুই করার দরকার নেই। onResume() কল হবে,
                // এবং এটি দেখবে যে চ্যাট পরিবর্তন হয়নি, তাই রিলোড স্কিপ করবে।
                Log.d(TAG, "onActivityResult: Chat selection canceled (Back pressed). No reload.");
            }
            return; // REQUEST_CODE_SELECT_CHAT হ্যান্ডল করা হয়েছে
        }

        // অন্যান্য onActivityResult লজিক (Location, Gallery, Camera, etc.)
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

        final List<String> options = new ArrayList<>();

        if (isShowingSavedMessages) {
            options.add("Copy");
            options.add("Edit");
            options.add("Unsave");
            options.add("Retransmit");
        } else if (msg.isFailed()) {
            options.add("Remove");
        } else {
            options.add("Copy");
            options.add("Edit");
            options.add("Save");
            options.add("Remove");

            boolean shouldHideRetransmit = "F".equals(activeChatType) && !msg.isSelf();
            if (!shouldHideRetransmit) {
                options.add("Retransmit");
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Message Options");

        // এটি (শর্ট) ক্লিকের হ্যান্ডলার
        builder.setItems(options.toArray(new String[0]), (dialog, which) -> {
            String selectedOption = options.get(which);
            switch (selectedOption) {
                case "Copy":
                    copyMessageToClipboard(msg); // এটি স্বাভাবিক (শর্ট) ক্লিক
                    break;
                case "Edit":
                    editMessage(msg); // এটি স্বাভাবিক (শর্ট) ক্লিক
                    break;
                case "Save":
                    saveMessage(msg);
                    break;
                case "Unsave":
                    unsaveMessage(msg);
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
        });

        // ডায়ালগটি .show() না করে .create() করুন
        AlertDialog dialog = builder.create();

        // ডায়ালগ থেকে ListView টি নিন
        ListView listView = dialog.getListView();
        if (listView != null) {
            // লং-প্রেস লিসেনার সেট করুন
            listView.setOnItemLongClickListener((parent, view, position, id) -> {
                String selectedOption = options.get(position);

                // ✅ যদি "Copy" অপশনে লং-প্রেস করা হয়
                if (selectedOption.equals("Copy")) {
                    copyMessageAsJson(msg); // JSON কপি করার মেথড
                    dialog.dismiss();       // ডায়ালগটি বন্ধ করুন
                    return true;            // আমরা লং-প্রেসটি সফলভাবে হ্যান্ডল করেছি
                }

                // ✅ নতুন: যদি "Edit" অপশনে লং-প্রেস করা হয়
                if (selectedOption.equals("Edit")) {
                    // এটি g// URL কিনা তা পরীক্ষা করুন
                    PayloadCompress.ParsedPayload parsed = PayloadCompress.parsePayload(msg.getMessage());
                    if (JsonFetcher.isJsonUrl(parsed.message) && parsed.imageUrls.isEmpty() && parsed.videoUrls.isEmpty()) {
                        // এটি একটি g// URL, তাই কন্টেন্ট লোড করুন
                        loadJsonForEditing(parsed.message); // নতুন হেল্পার মেথড
                        dialog.dismiss();
                        return true; // আমরা লং-প্রেসটি সফলভাবে হ্যান্ডল করেছি
                    }
                    // যদি এটি g// URL না হয়, তবে কিছুই করবেন না (return false)
                }

                // অন্য কোনো আইটেমে লং-প্রেস করলে কিছু করবেন না
                return false;
            });
        }

        // এবার ডায়ালগটি দেখান
        dialog.show();
    }

    private void loadJsonForEditing(String gUrl) {
        // ১. লোডিং টোস্ট দেখান
        Toast.makeText(this, "Fetching JSON for editing...", Toast.LENGTH_SHORT).show();

        // ২. JsonFetcher ব্যবহার করে কন্টেন্ট আনুন (এটি cache থেকেও আনতে পারে)
        JsonFetcher.fetchJson(this, gUrl, new JsonFetcher.JsonCallback() {
            @Override
            public void onSuccess(JsonFetcher.ParsedJson fetchedData) {
                // UI থ্রেডে ইনপুট ফিল্ডগুলো আপডেট করুন
                runOnUiThread(() -> {
                    // ৩. ফিল্ডগুলো সেট করুন
                    inputMessage.setText(fetchedData.message);
                    // কমা-কে নতুন লাইন দিয়ে প্রতিস্থাপন করুন, যাতে এডিট করা সহজ হয়
                    inputImageURL.setText(fetchedData.images.replace(",", "\n"));
                    inputVideoURL.setText(fetchedData.videos.replace(",", "\n"));

                    // ৪. সঠিক ইনপুট মোডটি দেখান (editMessage মেথডের লজিক অনুযায়ী)
                    if (fetchedData.message != null && !fetchedData.message.isEmpty()) {
                        // টেক্সট ইনপুট দেখান
                        inputMode = 0;
                        inputMessage.setVisibility(View.VISIBLE);
                        inputImageURL.setVisibility(View.GONE);
                        inputVideoURL.setVisibility(View.GONE);
                        switchInputImage.setImageResource(R.drawable.text);
                        inputMessage.requestFocus();
                        inputMessage.setSelection(inputMessage.getText().length());
                    } else if (fetchedData.images != null && !fetchedData.images.isEmpty()) {
                        // ইমেজ ইনপুট দেখান
                        inputMode = 1;
                        inputMessage.setVisibility(View.GONE);
                        inputImageURL.setVisibility(View.VISIBLE);
                        inputVideoURL.setVisibility(View.GONE);
                        switchInputImage.setImageResource(R.drawable.image);
                        inputImageURL.requestFocus();
                        inputImageURL.setSelection(inputImageURL.getText().length());
                    } else if (fetchedData.videos != null && !fetchedData.videos.isEmpty()) {
                        // ভিডিও ইনপুট দেখান
                        inputMode = 2;
                        inputMessage.setVisibility(View.GONE);
                        inputImageURL.setVisibility(View.GONE);
                        inputVideoURL.setVisibility(View.VISIBLE);
                        switchInputImage.setImageResource(R.drawable.video);
                        inputVideoURL.requestFocus();
                        inputVideoURL.setSelection(inputVideoURL.getText().length());
                    }
                    Toast.makeText(MainActivity.this, "JSON content loaded for editing", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                // ব্যর্থ হলে, শুধু g// URL টিকেই এডিট বক্সে দিন (short-click এর মতো)
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Fetch failed. Loading URL only.", Toast.LENGTH_LONG).show();

                    // short-click এর ফলব্যাক
                    inputMessage.setText(gUrl);
                    inputMode = 0;
                    inputMessage.setVisibility(View.VISIBLE);
                    inputImageURL.setVisibility(View.GONE);
                    inputVideoURL.setVisibility(View.GONE);
                    switchInputImage.setImageResource(R.drawable.text);
                    inputMessage.requestFocus();
                    inputMessage.setSelection(inputMessage.getText().length());
                });
            }
        });
    }

    private void copyMessageAsJson(MessageModel msg) {
        // ১. কাঁচা মেসেজ কন্টেন্ট পার্স করুন
        PayloadCompress.ParsedPayload parsed = PayloadCompress.parsePayload(msg.getMessage());

        // ২. চেক করুন এটি একটি g// URL কিনা
        if (JsonFetcher.isJsonUrl(parsed.message) && parsed.imageUrls.isEmpty() && parsed.videoUrls.isEmpty()) {
            // এটি একটি g// URL, তাই এটি fetch করতে হবে
            Toast.makeText(this, "Fetching JSON...", Toast.LENGTH_SHORT).show();

            // JsonFetcher ব্যবহার করে কন্টেন্ট আনুন (এটি cache থেকেও আনতে পারে)
            JsonFetcher.fetchJson(this, parsed.message, new JsonFetcher.JsonCallback() {
                @Override
                public void onSuccess(JsonFetcher.ParsedJson fetchedData) {
                    // সফল হলে, fetched data থেকে একটি নতুন payload তৈরি করুন
                    PayloadCompress.ParsedPayload payloadToCopy = new PayloadCompress.ParsedPayload();
                    payloadToCopy.message = fetchedData.message;
                    payloadToCopy.imageUrls = fetchedData.images;
                    payloadToCopy.videoUrls = fetchedData.videos;

                    // UI থ্রেডে JSON তৈরি এবং কপি করুন
                    runOnUiThread(() -> buildAndCopyJson(payloadToCopy));
                }

                @Override
                public void onError(String error) {
                    // ব্যর্থ হলে, আসল g// URL টিকেই JSON হিসেবে কপি করুন
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Fetch failed. Copying g// URL as JSON.", Toast.LENGTH_LONG).show();
                        buildAndCopyJson(parsed); // আসল 'parsed' payload কপি করুন
                    });
                }
            });
        } else {
            // এটি একটি সাধারণ মেসেজ, সরাসরি এটি থেকে JSON তৈরি করুন
            buildAndCopyJson(parsed);
        }
    }

    private void buildAndCopyJson(PayloadCompress.ParsedPayload payload) {
        try {
            // ১. JSON অবজেক্ট তৈরি করুন
            JSONObject json = new JSONObject();

            // ২. "message" কী (key) যোগ করুন
            json.put("message", payload.message);

            // ৩. "images" অ্যারে (array) যোগ করুন
            JSONArray imagesArray = new JSONArray();
            if (payload.imageUrls != null && !payload.imageUrls.isEmpty()) {
                String[] urls = payload.imageUrls.split(",");
                for (String url : urls) {
                    String trimmed = url.trim();
                    if (!trimmed.isEmpty()) {
                        imagesArray.put(trimmed);
                    }
                }
            }
            json.put("images", imagesArray);

            // ৪. "videos" অ্যারে (array) যোগ করুন
            JSONArray videosArray = new JSONArray();
            if (payload.videoUrls != null && !payload.videoUrls.isEmpty()) {
                String[] urls = payload.videoUrls.split(",");
                for (String url : urls) {
                    String trimmed = url.trim();
                    if (!trimmed.isEmpty()) {
                        videosArray.put(trimmed);
                    }
                }
            }
            json.put("videos", videosArray);

            // ৫. JSON অবজেক্টটিকে একটি সুন্দর ফরম্যাটেড স্ট্রিং-এ রূপান্তর করুন
            String jsonString = json.toString(2); // ২ স্পেস ইন্ডেন্টেশন

            // ✅ ৬. Escaped slashes (\/) ফিক্স করুন
            String cleanJsonString = jsonString.replace("\\/", "/");

            // ৭. ক্লিপবোর্ডে কপি করুন
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Message JSON", cleanJsonString));
                Toast.makeText(this, "JSON copied to clipboard", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error creating or copying JSON", e);
            Toast.makeText(this, "Failed to copy JSON", Toast.LENGTH_SHORT).show();
        }
    }

    private void editMessage(MessageModel msg) {
        String messageText = msg.getMessage();

        // Reset all inputs first
        inputMessage.setText("");
        inputImageURL.setText("");
        inputVideoURL.setText("");

        // Parse the payload using PayloadCompress
        PayloadCompress.ParsedPayload parsed = PayloadCompress.parsePayload(messageText);

        // ✅ NEW CHECK: If the DECOMPRESSED message is a g// URL,
        // just put the URL in the text field and stop.
        if (JsonFetcher.isJsonUrl(parsed.message) && parsed.imageUrls.isEmpty() && parsed.videoUrls.isEmpty()) {

            inputMessage.setText(parsed.message); // e.g., "g//mocki.io/v1/..."

            // Switch to text mode
            inputMode = 0;
            inputMessage.setVisibility(View.VISIBLE);
            inputImageURL.setVisibility(View.GONE);
            inputVideoURL.setVisibility(View.GONE);
            switchInputImage.setImageResource(R.drawable.text);

            inputMessage.requestFocus();
            inputMessage.setSelection(inputMessage.getText().length());

            Toast.makeText(this, "JSON URL loaded for editing", Toast.LENGTH_SHORT).show();
            return; // ✅ Stop here
        }

        // --- If it's a NORMAL message, do the old logic ---

        // Set the extracted parts to respective EditTexts
        if (!parsed.message.isEmpty()) {
            inputMessage.setText(parsed.message);
        }

        if (!parsed.imageUrls.isEmpty()) {
            // Replace commas with newlines for better editing
            inputImageURL.setText(parsed.imageUrls.replace(",", "\n"));
        }

        if (!parsed.videoUrls.isEmpty()) {
            // Replace commas with newlines
            inputVideoURL.setText(parsed.videoUrls.replace(",", "\n"));
        }

        // Switch to appropriate input mode based on what's available
        if (!parsed.message.isEmpty()) {
            // Show text input
            inputMode = 0;
            inputMessage.setVisibility(View.VISIBLE);
            inputImageURL.setVisibility(View.GONE);
            inputVideoURL.setVisibility(View.GONE);
            switchInputImage.setImageResource(R.drawable.text);

            inputMessage.requestFocus();
            inputMessage.setSelection(inputMessage.getText().length());
        } else if (!parsed.imageUrls.isEmpty()) {
            // Show image input
            inputMode = 1;
            inputMessage.setVisibility(View.GONE);
            inputImageURL.setVisibility(View.VISIBLE);
            inputVideoURL.setVisibility(View.GONE);
            switchInputImage.setImageResource(R.drawable.image);

            inputImageURL.requestFocus();
            inputImageURL.setSelection(inputImageURL.getText().length());
        } else if (!parsed.videoUrls.isEmpty()) {
            // Show video input
            inputMode = 2;
            inputMessage.setVisibility(View.GONE);
            inputImageURL.setVisibility(View.GONE);
            inputVideoURL.setVisibility(View.VISIBLE);
            switchInputImage.setImageResource(R.drawable.video);

            inputVideoURL.requestFocus();
            inputVideoURL.setSelection(inputVideoURL.getText().length());
        }

        Toast.makeText(this, "Message loaded for editing", Toast.LENGTH_SHORT).show();
    }

    /**
     * Checks if a single link string has a basic valid format.
     * We are lenient here: just checking for no spaces and at least one '.' or '/'.
     */
    private boolean isValidLinkFormat(String link) {
        if (link == null || link.isEmpty()) {
            return false;
        }
        // Rule 1: No spaces allowed in a URL
        if (link.contains(" ")) {
            return false;
        }
        // Rule 2: Must contain at least one dot OR one slash
        if (!link.contains(".") && !link.contains("/")) {
            return false;
        }
        // Rule 3: Must be at least 3 chars long (e.g., 'a.b' or '/a')
        if (link.length() < 3) {
            return false;
        }
        return true;
    }

    /**
     * Validates all links from both Image and Video input fields.
     */
    private boolean validateAllLinks(String imageUrlsRaw, String videoUrlsRaw) {
        java.util.List<String> allLinks = new java.util.ArrayList<>();

        // Use the same splitting logic as PayloadCompress
        if (imageUrlsRaw != null && !imageUrlsRaw.isEmpty()) {
            String[] imageLinks = imageUrlsRaw.trim().split("[\\n\\r,]+");
            Collections.addAll(allLinks, imageLinks);
        }

        if (videoUrlsRaw != null && !videoUrlsRaw.isEmpty()) {
            String[] videoLinks = videoUrlsRaw.trim().split("[\\n\\r,]+");
            Collections.addAll(allLinks, videoLinks);
        }

        if (allLinks.isEmpty()) {
            return true; // No links to validate, so it's valid.
        }

        for (String link : allLinks) {
            String trimmedLink = link.trim();
            // We only validate non-empty strings.
            if (!trimmedLink.isEmpty() && !isValidLinkFormat(trimmedLink)) {
                Log.w(TAG, "Invalid link detected: '" + trimmedLink + "'");
                return false; // Found an invalid link
            }
        }

        return true; // All links passed validation
    }



    private void unsaveMessage(MessageModel msg) {
        new Thread(() -> {
            try {
                database.savedMessageDao().deleteSavedMessage(
                        msg.getSenderId(), msg.getMessageId(), msg.getTimestamp());
                runOnUiThread(() -> Toast.makeText(this, "Message unsaved", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error unsaving message", Toast.LENGTH_SHORT).show());
            }
        }).start();
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

    private void saveMessage(MessageModel msg) {
        new Thread(() -> {
            try {
                // Get MessageEntity from database
                com.antor.nearbychat.Database.MessageEntity entity =
                        messageDao.getMessageEntity(msg.getSenderId(), msg.getMessageId(), msg.getTimestamp());

                if (entity != null) {
                    // Check if already saved
                    SavedMessageDao savedDao = database.savedMessageDao();
                    int exists = savedDao.savedMessageExists(msg.getSenderId(), msg.getMessageId(), msg.getTimestamp());

                    if (exists == 0) {
                        // Create copy in saved_messages table
                        SavedMessageEntity savedEntity = SavedMessageEntity.fromMessageEntity(entity);
                        savedDao.insertSavedMessage(savedEntity);
                        runOnUiThread(() -> Toast.makeText(this, "Message saved", Toast.LENGTH_SHORT).show());
                    } else {
                        runOnUiThread(() -> Toast.makeText(this, "Already saved", Toast.LENGTH_SHORT).show());
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error saving message", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showSavedMessages() {
        isShowingSavedMessages = true;

        appTitle.setText("Saved");
        appIcon.setImageResource(R.drawable.saved_blue);

        disableInputContainer();

        if (currentMessagesLiveData != null) {
            currentMessagesLiveData.removeObservers(this);
        }
        if (savedMessagesLiveData != null) {
            savedMessagesLiveData.removeObservers(this);
        }
        savedMessagesLiveData = database.savedMessageDao().getAllSavedMessages();

        savedMessagesLiveData.observe(this, messages -> {
            if (messages != null) {
                messageList.clear();
                for (SavedMessageEntity entity : messages) {
                    messageList.add(entity.toMessageModel());
                }
                chatAdapter.notifyDataSetChanged();

                if (messageList.isEmpty()) {
                    textStatus.setText("No saved messages");
                    textStatus.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.GONE);
                } else {
                    textStatus.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void exitSavedMessages() {
        isShowingSavedMessages = false;
        enableInputContainer();

        if (savedMessagesLiveData != null) {
            savedMessagesLiveData.removeObservers(this);
            savedMessagesLiveData = null;
        }
        updateChatUIForSelection();
    }

    private void disableInputContainer() {
        inputMessage.setEnabled(false);
        inputImageURL.setEnabled(false);
        inputVideoURL.setEnabled(false);
        switchInputImage.setEnabled(false); // ✅ এখানে যোগ করা হয়েছে

        inputMessage.setHintTextColor(Color.parseColor("#CCCCCC"));
        inputImageURL.setHintTextColor(Color.parseColor("#CCCCCC"));
        inputVideoURL.setHintTextColor(Color.parseColor("#CCCCCC"));

        inputMessage.setTextColor(Color.parseColor("#CCCCCC"));
        inputImageURL.setTextColor(Color.parseColor("#CCCCCC"));
        inputVideoURL.setTextColor(Color.parseColor("#CCCCCC"));

        sendButtonContainer.setAlpha(0.6f);
        switchInputImage.setAlpha(0.6f); // ✅ এখানে যোগ করা হয়েছে
    }

    private void enableInputContainer() {
        inputMessage.setEnabled(true);
        inputImageURL.setEnabled(true);
        inputVideoURL.setEnabled(true);
        switchInputImage.setEnabled(true); // ✅ এখানে যোগ করা হয়েছে

        // ===== START: ডিফল্ট hint গুলো রিসেট করুন =====
        inputMessage.setHint("Type your message...");
        inputImageURL.setHint("Paste your image link here...");
        inputVideoURL.setHint("Paste your video link here...");
        // ===== END: ডিফল্ট hint গুলো রিসেট করুন =====

        inputMessage.setHintTextColor(Color.parseColor("#787878"));
        inputImageURL.setHintTextColor(Color.parseColor("#787878"));
        inputVideoURL.setHintTextColor(Color.parseColor("#787878"));

        inputMessage.setTextColor(Color.parseColor("#000000"));
        inputImageURL.setTextColor(Color.parseColor("#000000"));
        inputVideoURL.setTextColor(Color.parseColor("#000000"));

        sendButtonContainer.setAlpha(1.0f);
        switchInputImage.setAlpha(1.0f); // ✅ এখানে যোগ করা হয়েছে
    }

    private void copyMessageToClipboard(MessageModel msg) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            String messageText = msg.getMessage();

            // Parse the message using PayloadCompress
            PayloadCompress.ParsedPayload parsed = PayloadCompress.parsePayload(messageText);

            StringBuilder result = new StringBuilder();
            if (!parsed.message.isEmpty()) {
                result.append(parsed.message);
            }
            if (!parsed.imageUrls.isEmpty()) {
                if (result.length() > 0) result.append("\n\nImages:\n");
                result.append(parsed.imageUrls.replace(",", "\n"));
            }
            if (!parsed.videoUrls.isEmpty()) {
                if (result.length() > 0) result.append("\n\nVideos:\n");
                result.append(parsed.videoUrls.replace(",", "\n"));
            }

            clipboard.setPrimaryClip(ClipData.newPlainText("Copied Message", result.toString()));
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
        String name = nameMap.get(senderId);

        if (name != null && !name.isEmpty()) {
            return name;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
        if (friendsJson != null) {
            Type type = new TypeToken<List<FriendModel>>() {}.getType();
            List<FriendModel> friends = gson.fromJson(friendsJson, type);
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
        return senderId;
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

        // ১. লোড করুন কোন চ্যাটটি সক্রিয় থাকা উচিত
        activeChatType = prefs.getString(KEY_CHAT_TYPE, "N");
        activeChatId = prefs.getString(KEY_CHAT_ID, "");

        SharedPreferences activePrefs = getSharedPreferences(PREFS_ACTIVE_CHAT, MODE_PRIVATE);
        activePrefs.edit()
                .putString(KEY_CHAT_TYPE, activeChatType)
                .putString(KEY_CHAT_ID, activeChatId)
                .apply();

        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled() && hasAllRequiredPermissions()) {
            startBleService();
        }

        // ▼▼▼ মূল পরিবর্তন এখানে ▼▼▼
        // ২. চেক করুন: বর্তমান চ্যাটটিই কি সর্বশেষ রিলোড করা চ্যাট?
        if (activeChatType.equals(lastRefreshedChatType) && activeChatId.equals(lastRefreshedChatId)) {
            // যদি একই হয়, তাহলে সম্পূর্ণ UI রিলোড করার দরকার নেই।
            // ব্যবহারকারী শুধু অন্য স্ক্রিন থেকে ফিরে এসেছে।
            Log.d(TAG, "onResume: Same chat (" + activeChatType + "/" + activeChatId + "), skipping full reload.");

            // শুধু মেসেজগুলো 'read' হিসেবে মার্ক করুন
            markCurrentChatAsRead();
        } else {
            // যদি এটি ভিন্ন চ্যাট হয় (অথবা অ্যাপ প্রথমবার লোড হয়),
            // তাহলে সম্পূর্ণ UI রিলোড করুন।
            Log.d(TAG, "onResume: New chat (" + activeChatType + "/" + activeChatId + "), performing full reload.");
            updateChatUIForSelection();

            // ৩. মনে রাখুন যে এই চ্যাটটি রিলোড করা হয়েছে
            lastRefreshedChatType = activeChatType;
            lastRefreshedChatId = activeChatId;
        }
        // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲
    }

    @Override
    protected void onPause() {
        super.onPause();
        // We SAVE the active chat here so the service knows what's open,
        // instead of clearing it.
        SharedPreferences activePrefs = getSharedPreferences(PREFS_ACTIVE_CHAT, MODE_PRIVATE);
        activePrefs.edit()
                .putString(KEY_CHAT_TYPE, activeChatType)
                .putString(KEY_CHAT_ID, activeChatId)
                .apply();

        // .remove(KEY_CHAT_TYPE) // <-- REMOVED
        // .remove(KEY_CHAT_ID)   // <-- REMOVED

        Log.d(TAG, "App paused. Active chat info SAVED.");
    }

    @Override
    public void onBackPressed() {
        if (isSearchMode) {
            hideSearchMode();
            return;
        }
        if (isShowingSavedMessages) {
            exitSavedMessages();
            return;
        }
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