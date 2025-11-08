package com.antor.nearbychat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.antor.nearbychat.Database.AppDatabase;
import com.antor.nearbychat.Message.MessageHelper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BlockActivity extends Activity {

    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_BLOCKED_LIST = "blockedList";

    private EditText searchBar;
    private RecyclerView recyclerView;
    private BlockedListAdapter adapter;
    private List<String> blockedUserIds = new ArrayList<>();
    private List<BlockedChatItem> allChats = new ArrayList<>();

    private String activeChatType = "N";
    private String activeChatId = "";

    private Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_block);

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        if (getWindow() != null) {
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            getWindow().setGravity(Gravity.BOTTOM);
        }
        setFinishOnTouchOutside(true);

        Intent intent = getIntent();
        activeChatType = intent.getStringExtra("currentChatType");
        activeChatId = intent.getStringExtra("currentChatId");

        if (activeChatType == null) activeChatType = "N";
        if (activeChatId == null) activeChatId = "";

        setupUI();
        loadBlockedList();
        loadAndDisplayBlockedChats();
    }

    private void setupUI() {
        searchBar = findViewById(R.id.searchBar);
        recyclerView = findViewById(R.id.recyclerView);

        searchBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                filterChats(s.toString());
            }
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadBlockedList();
        loadAndDisplayBlockedChats();
    }

    private void loadBlockedList() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_BLOCKED_LIST, null);
        if (json != null) {
            Type type = new TypeToken<List<String>>(){}.getType();
            blockedUserIds = gson.fromJson(json, type);
        }
        if (blockedUserIds == null) {
            blockedUserIds = new ArrayList<>();
        }
    }

    private void saveBlockedList() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_BLOCKED_LIST, gson.toJson(blockedUserIds)).apply();
    }

    private void loadAndDisplayBlockedChats() {
        executor.execute(() -> {
            List<BlockedChatItem> loadedChats = new ArrayList<>();
            com.antor.nearbychat.Database.MessageDao dao = AppDatabase.getInstance(this).messageDao();

            for (String userId : blockedUserIds) {
                String friendChatId = MessageHelper.timestampToAsciiId(
                        MessageHelper.displayIdToTimestamp(userId)
                );

                com.antor.nearbychat.Database.MessageEntity lastMsg =
                        dao.getLastMessageForChat("F", friendChatId);

                loadedChats.add(new BlockedChatItem(
                        friendChatId,
                        getUserDisplayName(userId),
                        "F",
                        lastMsg != null ? formatLastMessage(lastMsg) : "No messages yet",
                        getLastMessageTime(lastMsg),
                        userId,
                        lastMsg != null ? lastMsg.timestampMillis : 0
                ));
            }

            // Sort by last message time
            Collections.sort(loadedChats, (a, b) ->
                    Long.compare(b.lastMessageTimestamp, a.lastMessageTimestamp));

            runOnUiThread(() -> {
                allChats.clear();
                allChats.addAll(loadedChats);

                adapter = new BlockedListAdapter(this, allChats,
                        this::onChatClick, this::onChatLongClick,
                        activeChatType, activeChatId);

                recyclerView.setAdapter(adapter);
            });
        });
    }

    private String getUserDisplayName(String userId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Check name map
        String nameMapJson = prefs.getString("nameMap", null);
        if (nameMapJson != null) {
            Type type = new TypeToken<java.util.Map<String, String>>(){}.getType();
            java.util.Map<String, String> nameMap = gson.fromJson(nameMapJson, type);
            if (nameMap != null && nameMap.containsKey(userId)) {
                String name = nameMap.get(userId);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        }

        // Check friends list
        String friendsJson = prefs.getString("friendsList", null);
        if (friendsJson != null) {
            Type type = new TypeToken<List<FriendModel>>(){}.getType();
            List<FriendModel> friends = gson.fromJson(friendsJson, type);
            if (friends != null) {
                for (FriendModel f : friends) {
                    if (f.getDisplayId().equals(userId)) {
                        String friendName = f.getName();
                        if (friendName != null && !friendName.isEmpty()) {
                            return friendName;
                        }
                        break;
                    }
                }
            }
        }

        return userId;
    }

    private String formatLastMessage(com.antor.nearbychat.Database.MessageEntity lastMsg) {
        if (lastMsg == null) {
            return "No messages yet";
        }

        try {
            PayloadCompress.ParsedPayload parsed = PayloadCompress.parsePayload(lastMsg.message);

            boolean hasText = !parsed.message.isEmpty();
            boolean hasMedia = !parsed.imageUrls.isEmpty() || !parsed.videoUrls.isEmpty();

            String prefix = lastMsg.isSelf ? "You: " : "";

            if (hasText && hasMedia) {
                String truncatedText = parsed.message.length() > 30
                        ? parsed.message.substring(0, 30) + "..."
                        : parsed.message;
                return prefix + truncatedText;
            } else if (hasText) {
                String truncatedText = parsed.message.length() > 30
                        ? parsed.message.substring(0, 30) + "..."
                        : parsed.message;
                return prefix + truncatedText;
            } else if (hasMedia) {
                return lastMsg.isSelf ? "You sent a media" : "Sent a media";
            } else {
                return "No content";
            }
        } catch (Exception e) {
            String rawMsg = lastMsg.message;
            if (rawMsg.length() > 30) {
                rawMsg = rawMsg.substring(0, 30) + "...";
            }
            return (lastMsg.isSelf ? "You: " : "") + rawMsg;
        }
    }

    private String getLastMessageTime(com.antor.nearbychat.Database.MessageEntity lastMsg) {
        if (lastMsg == null) {
            return "";
        }
        long messageTimestamp = lastMsg.timestampMillis;

        java.util.Calendar messageCal = java.util.Calendar.getInstance();
        messageCal.setTimeInMillis(messageTimestamp);

        java.util.Calendar nowCal = java.util.Calendar.getInstance();

        if (nowCal.get(java.util.Calendar.YEAR) == messageCal.get(java.util.Calendar.YEAR) &&
                nowCal.get(java.util.Calendar.DAY_OF_YEAR) == messageCal.get(java.util.Calendar.DAY_OF_YEAR)) {
            return new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
                    .format(new java.util.Date(messageTimestamp));
        }

        nowCal.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (nowCal.get(java.util.Calendar.YEAR) == messageCal.get(java.util.Calendar.YEAR) &&
                nowCal.get(java.util.Calendar.DAY_OF_YEAR) == messageCal.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "Yesterday";
        }

        long diff = System.currentTimeMillis() - messageTimestamp;
        if (diff < 7 * 24 * 60 * 60 * 1000) {
            return new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
                    .format(new java.util.Date(messageTimestamp));
        }

        if (java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) ==
                messageCal.get(java.util.Calendar.YEAR)) {
            return new java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
                    .format(new java.util.Date(messageTimestamp));
        }

        return new java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault())
                .format(new java.util.Date(messageTimestamp));
    }

    private void onChatClick(BlockedChatItem chat) {
        // Open MainActivity with this blocked chat
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("chatType", chat.type);
        intent.putExtra("chatId", chat.id);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void onChatLongClick(BlockedChatItem chat) {
        String[] options = {"Unblock", "Clear History", "Delete Chat"};

        new AlertDialog.Builder(this)
                .setTitle("Chat Options")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: // Unblock
                            unblockUser(chat);
                            break;
                        case 1: // Clear History
                            clearHistory(chat);
                            break;
                        case 2: // Delete Chat
                            deleteChat(chat);
                            break;
                    }
                })
                .show();
    }

    private void unblockUser(BlockedChatItem chat) {
        new AlertDialog.Builder(this)
                .setTitle("Unblock User")
                .setMessage("Unblock " + chat.name + "?")
                .setPositiveButton("Unblock", (dialog, which) -> {
                    blockedUserIds.remove(chat.displayId);
                    saveBlockedList();

                    // ===== START: Add user back to friends list and switch activity =====
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    String friendsJson = prefs.getString("friendsList", null);
                    Type type = new TypeToken<List<FriendModel>>(){}.getType();
                    List<FriendModel> friends = gson.fromJson(friendsJson, type);
                    if (friends == null) {
                        friends = new ArrayList<>();
                    }

                    boolean exists = false;
                    for (FriendModel f : friends) {
                        if (f.getDisplayId().equals(chat.displayId)) {
                            exists = true;
                            break;
                        }
                    }

                    if (!exists) {
                        // Use the name stored in the chat item, which is the display name
                        friends.add(new FriendModel(chat.displayId, chat.name, ""));
                        prefs.edit().putString("friendsList", gson.toJson(friends)).apply();
                        DataCache.invalidate(); // Invalidate cache so GroupsFriendsActivity re-reads
                    }

                    Toast.makeText(this, "User unblocked", Toast.LENGTH_SHORT).show();

                    // Close this activity
                    finish();

                    // Open GroupsFriendsActivity
                    Intent intent = new Intent(BlockActivity.this, GroupsFriendsActivity.class);
                    intent.putExtra("currentChatType", activeChatType);
                    intent.putExtra("currentChatId", activeChatId);
                    startActivity(intent);
                    // ===== END: Add user back to friends list and switch activity =====

                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearHistory(BlockedChatItem chat) {
        new AlertDialog.Builder(this)
                .setTitle("Clear History")
                .setMessage("Delete all messages with " + chat.name + "?")
                .setPositiveButton("Clear", (dialog, which) -> {
                    executor.execute(() -> {
                        AppDatabase.getInstance(this).messageDao()
                                .deleteMessagesForChat(chat.type, chat.id);
                        runOnUiThread(() -> {
                            loadAndDisplayBlockedChats();
                            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteChat(BlockedChatItem chat) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Chat")
                .setMessage("Delete chat with " + chat.name + "?\n\nThis will unblock the user and delete all messages.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    blockedUserIds.remove(chat.displayId);
                    saveBlockedList();

                    executor.execute(() -> {
                        AppDatabase.getInstance(this).messageDao()
                                .deleteMessagesForChat(chat.type, chat.id);
                        runOnUiThread(() -> {
                            loadAndDisplayBlockedChats();
                            Toast.makeText(this, "Chat deleted", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void filterChats(String query) {
        if (adapter == null) return;
        if (query.isEmpty()) {
            adapter.updateList(allChats, activeChatType, activeChatId);
            return;
        }
        List<BlockedChatItem> filtered = new ArrayList<>();
        for (BlockedChatItem chat : allChats) {
            if (chat.name.toLowerCase().contains(query.toLowerCase()) ||
                    chat.lastMessage.toLowerCase().contains(query.toLowerCase())) {
                filtered.add(chat);
            }
        }
        adapter.updateList(filtered, activeChatType, activeChatId);
    }

    public static class BlockedChatItem {
        String id, name, type, lastMessage, lastMessageTime, displayId;
        long lastMessageTimestamp;

        public BlockedChatItem(String id, String name, String type, String lastMessage,
                               String lastMessageTime, String displayId, long lastMessageTimestamp) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.lastMessage = lastMessage;
            this.lastMessageTime = lastMessageTime;
            this.displayId = displayId;
            this.lastMessageTimestamp = lastMessageTimestamp;
        }
    }
}