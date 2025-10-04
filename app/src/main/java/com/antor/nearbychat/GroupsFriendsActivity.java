package com.antor.nearbychat;

import android.app.AlertDialog;
import android.app.Dialog;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Activity;

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

public class GroupsFriendsActivity extends Activity {

    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_GROUPS_LIST = "groupsList";
    private static final String KEY_FRIENDS_LIST = "friendsList";

    private EditText searchBar;
    private List<ChatItem> allChats = new ArrayList<>();
    private ChatListAdapter chatAdapter;

    private static final String PREFS_ACTIVE_CHAT = "ActiveChatInfo";
    private static final String KEY_CHAT_TYPE = "chatType";
    private static final String KEY_CHAT_ID = "chatId";
    private String activeChatType = "N";
    private String activeChatId = "";

    private RecyclerView recyclerView;
    private ImageView btnBack, btnAdd;

    private List<GroupModel> groupsList = new ArrayList<>();
    private List<FriendModel> friendsList = new ArrayList<>();
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_groups_friends);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        if (getWindow() != null) {
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            getWindow().setGravity(Gravity.BOTTOM);
        }
        setFinishOnTouchOutside(true);
        loadActiveChat();
        loadData();
        setupUI();
    }

    private void setupUI() {
        searchBar = findViewById(R.id.searchBar);
        recyclerView = findViewById(R.id.recyclerView);
        btnBack = findViewById(R.id.btnBack);
        btnAdd = findViewById(R.id.btnAdd);

        btnBack.setOnClickListener(v -> finish());
        btnAdd.setOnClickListener(v -> showAddDialog());

        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                filterChats(s.toString());
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        loadAndDisplayAllChats();
    }

    private long parseTimeToMillis(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return 0;
        try {
            if (timeStr.contains("|")) {
                return new java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault()).parse(timeStr.split("\\|")[1].trim()).getTime();
            } else if (timeStr.matches("\\d{2}:\\d{2} (AM|PM)")) {
                return System.currentTimeMillis();
            } else if (timeStr.matches("[A-Za-z]{3} \\d{1,2}")) {
                java.util.Date date = new java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()).parse(timeStr);
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(date);
                cal.set(java.util.Calendar.YEAR, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
                return cal.getTimeInMillis();
            } else if (timeStr.matches("[A-Za-z]{3}")) {
                return System.currentTimeMillis() - (24 * 60 * 60 * 1000);
            }
        } catch (Exception e) { e.printStackTrace(); }
        return System.currentTimeMillis();
    }

    private void loadAndDisplayAllChats() {
        executor.execute(() -> {
            List<ChatItem> loadedChats = new ArrayList<>();
            com.antor.nearbychat.Database.MessageDao dao = AppDatabase.getInstance(this).messageDao();

            com.antor.nearbychat.Database.MessageEntity nearbyLastMsg = dao.getLastMessageForChat("N", "");
            int nearbyUnreadCount = dao.getUnreadMessageCountForChat("N", "");
            loadedChats.add(new ChatItem(
                    "", "Nearby Chat", "N",
                    nearbyLastMsg != null ? nearbyLastMsg.message : "No recent messages",
                    getLastMessageTime(nearbyLastMsg), "",
                    nearbyLastMsg != null ? nearbyLastMsg.timestampMillis : 0,
                    nearbyUnreadCount > 0
            ));
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String groupsJson = prefs.getString(KEY_GROUPS_LIST, null);
            if (groupsJson != null) {
                Type type = new TypeToken<ArrayList<GroupModel>>() {}.getType();
                List<GroupModel> groups = gson.fromJson(groupsJson, type);
                for (GroupModel g : groups) {
                    com.antor.nearbychat.Database.MessageEntity lastMsg = dao.getLastMessageForChat("G", g.getId());
                    int unreadCount = dao.getUnreadMessageCountForChat("G", g.getId());
                    loadedChats.add(new ChatItem(
                            g.getId(), g.getName(), "G",
                            lastMsg != null ? lastMsg.message : "No messages yet",
                            getLastMessageTime(lastMsg), "",
                            lastMsg != null ? lastMsg.timestampMillis : 0,
                            unreadCount > 0
                    ));
                }
            }
            String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
            if (friendsJson != null) {
                Type type = new TypeToken<ArrayList<FriendModel>>() {}.getType();
                List<FriendModel> friends = gson.fromJson(friendsJson, type);
                for (FriendModel f : friends) {
                    String asciiId = MessageHelper.timestampToAsciiId(MessageHelper.displayIdToTimestamp(f.getDisplayId()));
                    com.antor.nearbychat.Database.MessageEntity lastMsg = dao.getLastMessageForChat("F", asciiId);
                    int unreadCount = dao.getUnreadMessageCountForChat("F", asciiId);
                    loadedChats.add(new ChatItem(
                            asciiId, f.getName(), "F",
                            lastMsg != null ? lastMsg.message : "No messages yet",
                            getLastMessageTime(lastMsg), f.getDisplayId(),
                            lastMsg != null ? lastMsg.timestampMillis : 0,
                            unreadCount > 0
                    ));
                }
            }
            if (loadedChats.size() > 1) {
                ChatItem nearbyItem = loadedChats.get(0);
                List<ChatItem> restOfChats = new ArrayList<>(loadedChats.subList(1, loadedChats.size()));
                Collections.sort(restOfChats, (a, b) -> Long.compare(b.lastMessageTimestamp, a.lastMessageTimestamp));
                loadedChats.clear();
                loadedChats.add(nearbyItem);
                loadedChats.addAll(restOfChats);
            }

            runOnUiThread(() -> {
                allChats.clear();
                allChats.addAll(loadedChats);
                chatAdapter = new ChatListAdapter(this, allChats, this::onChatClick, this::onChatLongClick, activeChatType, activeChatId);
                recyclerView.setAdapter(chatAdapter);
            });
        });
    }

    private void showAddDialog() {
        new AlertDialog.Builder(this).setTitle("Add New")
                .setItems(new String[]{"Add Group", "Add Friend"}, (dialog, which) -> {
                    if (which == 0) showAddEditGroupDialog(null, -1);
                    else showAddEditFriendDialog(null, -1);
                }).show();
    }

    private String getLastMessageForChat(String chatType, String chatId) {
        try {
            com.antor.nearbychat.Database.MessageEntity lastMsg = AppDatabase.getInstance(this).messageDao().getLastMessageForChat(chatType, chatId);
            return lastMsg != null ? lastMsg.message : "";
        } catch (Exception e) { return ""; }
    }

    private String getLastMessageTimeForChat(String chatType, String chatId) {
        try {
            com.antor.nearbychat.Database.MessageEntity lastMsg = AppDatabase.getInstance(this).messageDao().getLastMessageForChat(chatType, chatId);
            if (lastMsg != null) {
                long time = lastMsg.timestampMillis, now = System.currentTimeMillis(), diff = now - time;
                if (diff < 24 * 60 * 60 * 1000) return new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(new java.util.Date(time));
                if (diff < 7 * 24 * 60 * 60 * 1000) return new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(new java.util.Date(time));
                if (diff < 365L * 24 * 60 * 60 * 1000L) return new java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()).format(new java.util.Date(time));
                return new java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault()).format(new java.util.Date(time));
            }
        } catch (Exception e) { e.printStackTrace(); }
        return "";
    }

    private String getLastMessageTime(com.antor.nearbychat.Database.MessageEntity lastMsg) {
        if (lastMsg != null) {
            long time = lastMsg.timestampMillis, now = System.currentTimeMillis(), diff = now - time;
            if (diff < 24 * 60 * 60 * 1000) return new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(new java.util.Date(time));
            if (diff < 7 * 24 * 60 * 60 * 1000) return new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(new java.util.Date(time));
            if (diff < 365L * 24 * 60 * 60 * 1000L) return new java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()).format(new java.util.Date(time));
            return new java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault()).format(new java.util.Date(time));
        }
        return "";
    }

    private void onChatClick(ChatItem chat) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra("chatType", chat.type);
        resultIntent.putExtra("chatId", chat.id);
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    private void onChatLongClick(ChatItem chat) {
        if (chat.type.equals("N")) {
            Toast.makeText(this, "Cannot delete Nearby Chat", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Delete " + chat.name + "?")
                .setMessage("This will delete the chat and all associated messages.")
                .setPositiveButton("Delete", (dialog, which) -> deleteChat(chat))
                .setNegativeButton("Cancel", null).show();
    }

    private void deleteChat(ChatItem chat) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (chat.type.equals("G")) {
            String groupsJson = prefs.getString(KEY_GROUPS_LIST, null);
            if(groupsJson == null) return;
            List<GroupModel> groups = gson.fromJson(groupsJson, new TypeToken<ArrayList<GroupModel>>() {}.getType());
            groups.removeIf(g -> g.getId().equals(chat.id));
            prefs.edit().putString(KEY_GROUPS_LIST, gson.toJson(groups)).apply();
        } else if (chat.type.equals("F")) {
            String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
            if(friendsJson == null) return;
            List<FriendModel> friends = gson.fromJson(friendsJson, new TypeToken<ArrayList<FriendModel>>() {}.getType());
            friends.removeIf(f -> MessageHelper.timestampToAsciiId(MessageHelper.displayIdToTimestamp(f.getDisplayId())).equals(chat.id));
            prefs.edit().putString(KEY_FRIENDS_LIST, gson.toJson(friends)).apply();
        }
        executor.execute(() -> {
            AppDatabase.getInstance(this).messageDao().deleteMessagesForChat(chat.type, chat.id);
            runOnUiThread(() -> {
                loadAndDisplayAllChats();
                Toast.makeText(this, chat.name + " deleted", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void filterChats(String query) {
        if (chatAdapter == null) return;
        if (query.isEmpty()) {
            chatAdapter.updateList(allChats, activeChatType, activeChatId);
            return;
        }
        List<ChatItem> filtered = new ArrayList<>();
        for (ChatItem chat : allChats) {
            if (chat.name.toLowerCase().contains(query.toLowerCase()) || chat.lastMessage.toLowerCase().contains(query.toLowerCase())) {
                filtered.add(chat);
            }
        }
        chatAdapter.updateList(filtered, activeChatType, activeChatId);
    }

    private void loadActiveChat() {
        SharedPreferences prefs = getSharedPreferences(PREFS_ACTIVE_CHAT, MODE_PRIVATE);
        activeChatType = prefs.getString(KEY_CHAT_TYPE, "N");
        activeChatId = prefs.getString(KEY_CHAT_ID, "");
    }

    public static class ChatItem {
        String id, name, type, lastMessage, lastMessageTime, displayId;
        long lastMessageTimestamp;
        boolean hasUnreadMessages;

        public ChatItem(String id, String name, String type, String lastMessage, String lastMessageTime, String displayId, long lastMessageTimestamp, boolean hasUnreadMessages) {
            this.id = id; this.name = name; this.type = type; this.lastMessage = lastMessage;
            this.lastMessageTime = lastMessageTime; this.displayId = displayId;
            this.lastMessageTimestamp = lastMessageTimestamp; this.hasUnreadMessages = hasUnreadMessages;
        }
    }

    private void showAddEditGroupDialog(final GroupModel group, final int position) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_edit_groups);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        EditText editName = dialog.findViewById(R.id.editName);
        EditText editKey = dialog.findViewById(R.id.editEncryptionKey);
        Button btnDelete = dialog.findViewById(R.id.btnDelete);
        Button btnAdd = dialog.findViewById(R.id.btnAdd);

        ImageView profilePic = dialog.findViewById(R.id.profilePicRound);
        TextView groupIdText = dialog.findViewById(R.id.groupID);

        if (group != null) {
            ((TextView)dialog.findViewById(R.id.dia_title)).setText("Edit Group");
            btnAdd.setText("Save");
            editName.setText(group.getName());
            editKey.setText(group.getEncryptionKey());
            btnDelete.setVisibility(View.VISIBLE);
            if (profilePic != null) {
                profilePic.setVisibility(View.VISIBLE);
                long bits = asciiIdToTimestamp(group.getId());
                String displayId = getUserIdString(bits);
                ProfilePicLoader.loadProfilePicture(this, displayId, profilePic);
            }
            if (groupIdText != null) {
                groupIdText.setVisibility(View.VISIBLE);
                long bits = asciiIdToTimestamp(group.getId());
                String displayId = getUserIdString(bits);
                groupIdText.setText(displayId);
            }
        } else {
            if (profilePic != null) {
                profilePic.setVisibility(View.GONE);
            }
            if (groupIdText != null) {
                groupIdText.setVisibility(View.GONE);
            }
            btnDelete.setVisibility(View.GONE);
        }
        dialog.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        btnDelete.setOnClickListener(v -> {
            groupsList.remove(position);
            saveGroups();
            loadAndDisplayAllChats();
            dialog.dismiss();
        });
        btnAdd.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            if (name.isEmpty()) { Toast.makeText(this, "Group name cannot be empty", Toast.LENGTH_SHORT).show(); return; }
            if (group != null) {
                group.setName(name);
                group.setEncryptionKey(editKey.getText().toString().trim());
            } else {
                groupsList.add(new GroupModel(MessageHelper.timestampToAsciiId(System.currentTimeMillis()), name, editKey.getText().toString().trim()));
            }
            saveGroups();
            loadAndDisplayAllChats();
            dialog.dismiss();
        });
        dialog.show();
    }

    private long asciiIdToTimestamp(String asciiId) {
        if (asciiId == null || asciiId.length() != 5) return 0;
        long bits40 = 0;
        for (int i = 0; i < 5; i++) {
            bits40 = (bits40 << 8) | (asciiId.charAt(i) & 0xFF);
        }
        return bits40;
    }

    private String getUserIdString(long bits40) {
        return MessageHelper.timestampToDisplayId(bits40);
    }

    private void showAddEditFriendDialog(final FriendModel friend, final int position) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_edit_friend);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        EditText editName = dialog.findViewById(R.id.editName);
        EditText editId = dialog.findViewById(R.id.editFriendId);
        EditText editKey = dialog.findViewById(R.id.editEncryptionKey);
        Button btnDelete = dialog.findViewById(R.id.btnDelete);
        Button btnAdd = dialog.findViewById(R.id.btnAdd);
        ImageView profilePic = dialog.findViewById(R.id.profilePicRound);

        if (friend != null) {
            ((TextView)dialog.findViewById(R.id.dia_title)).setText("Edit Friend");
            btnAdd.setText("Save");
            editName.setText(friend.getName());
            editId.setText(friend.getDisplayId());
            editKey.setText(friend.getEncryptionKey());
            editId.setEnabled(false);
            btnDelete.setVisibility(View.VISIBLE);
            if (profilePic != null) {
                profilePic.setVisibility(View.VISIBLE);
                ProfilePicLoader.loadProfilePicture(this, friend.getDisplayId(), profilePic);
            }
        } else {
            if (profilePic != null) {
                profilePic.setVisibility(View.GONE);
            }
            btnDelete.setVisibility(View.GONE);
        }
        dialog.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        btnDelete.setOnClickListener(v -> {
            friendsList.remove(position);
            saveFriends();
            loadAndDisplayAllChats();
            dialog.dismiss();
        });
        btnAdd.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            String id = editId.getText().toString().trim();
            String key = editKey.getText().toString().trim();
            if (name.isEmpty() || id.isEmpty()) { Toast.makeText(this, "Fields cannot be empty", Toast.LENGTH_SHORT).show(); return; }
            if (id.length() != 8) { Toast.makeText(this, "Friend ID must be 8 characters", Toast.LENGTH_SHORT).show(); return; }

            if (friend != null) {
                friend.setName(name);
                friend.setEncryptionKey(key);
            } else {
                friendsList.add(new FriendModel(id, name, key));
            }
            saveFriends();
            loadAndDisplayAllChats();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void loadData() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String groupsJson = prefs.getString(KEY_GROUPS_LIST, null);
        if (groupsJson != null) groupsList = gson.fromJson(groupsJson, new TypeToken<ArrayList<GroupModel>>() {}.getType());
        String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
        if (friendsJson != null) friendsList = gson.fromJson(friendsJson, new TypeToken<ArrayList<FriendModel>>() {}.getType());
    }

    private void saveGroups() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_GROUPS_LIST, gson.toJson(groupsList)).apply();
    }

    private void saveFriends() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_FRIENDS_LIST, gson.toJson(friendsList)).apply();
    }
}