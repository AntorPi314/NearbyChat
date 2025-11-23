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

    private EditText searchBar;
    private List<ChatItem> allChats = new ArrayList<>();
    private ChatListAdapter chatAdapter;

    private static final String PREFS_ACTIVE_CHAT = "ActiveChatInfo";
    private static final String KEY_CHAT_TYPE = "chatType";
    private static final String KEY_CHAT_ID = "chatId";
    private String activeChatType = "N";
    private String activeChatId = "";

    private RecyclerView recyclerView;
    private ImageView btnAdd, btnQrScanner;

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

        Intent intent = getIntent();
        activeChatType = intent.getStringExtra("currentChatType");
        activeChatId = intent.getStringExtra("currentChatId");

        if (activeChatType == null) activeChatType = "N";
        if (activeChatId == null) activeChatId = "";

        setupUI();
    }

    private void setupUI() {
        searchBar = findViewById(R.id.searchBar);
        recyclerView = findViewById(R.id.recyclerView);
        btnAdd = findViewById(R.id.btnAdd);
        btnQrScanner = findViewById(R.id.qrCodeScanner);

        btnAdd.setOnClickListener(v -> showAddDialog());
        btnQrScanner.setOnClickListener(v -> {
            Intent intent = new Intent(this, QRScannerActivity.class);
            startActivity(intent);
        });

        searchBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

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
        loadData();
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
        } catch (Exception e) {
            e.printStackTrace();
        }
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
                    nearbyLastMsg != null ? formatLastMessage(nearbyLastMsg) : "No recent messages",
                    getLastMessageTime(nearbyLastMsg), "",
                    nearbyLastMsg != null ? nearbyLastMsg.timestampMillis : 0,
                    nearbyUnreadCount > 0
            ));

            List<GroupModel> groups = DataCache.getGroups(this);
            for (GroupModel g : groups) {
                com.antor.nearbychat.Database.MessageEntity lastMsg = dao.getLastMessageForChat("G", g.getId());
                int unreadCount = dao.getUnreadMessageCountForChat("G", g.getId());
                loadedChats.add(new ChatItem(
                        g.getId(), g.getName(), "G",
                        lastMsg != null ? formatLastMessage(lastMsg) : "No messages yet",
                        getLastMessageTime(lastMsg), "",
                        lastMsg != null ? lastMsg.timestampMillis : 0,
                        unreadCount > 0
                ));
            }

            List<FriendModel> friends = DataCache.getFriends(this);
            for (FriendModel f : friends) {
                String asciiId = MessageHelper.timestampToAsciiId(
                        MessageHelper.displayIdToTimestamp(f.getDisplayId())
                );
                com.antor.nearbychat.Database.MessageEntity lastMsg = dao.getLastMessageForChat("F", asciiId);
                int unreadCount = dao.getUnreadMessageCountForChat("F", asciiId);
                loadedChats.add(new ChatItem(
                        asciiId, f.getName(), "F",
                        lastMsg != null ? formatLastMessage(lastMsg) : "No messages yet",
                        getLastMessageTime(lastMsg), f.getDisplayId(),
                        lastMsg != null ? lastMsg.timestampMillis : 0,
                        unreadCount > 0
                ));
            }

            if (loadedChats.size() > 1) {
                ChatItem nearbyItem = loadedChats.get(0);
                List<ChatItem> rest = new ArrayList<>(loadedChats.subList(1, loadedChats.size()));
                Collections.sort(rest, (a, b) -> Long.compare(b.lastMessageTimestamp, a.lastMessageTimestamp));
                loadedChats.clear();
                loadedChats.add(nearbyItem);
                loadedChats.addAll(rest);
            }

            runOnUiThread(() -> {
                allChats.clear();
                allChats.addAll(loadedChats);
                chatAdapter = new ChatListAdapter(this, allChats, this::onChatClick, this::onChatLongClick, activeChatType, activeChatId);
                recyclerView.setAdapter(chatAdapter);
            });
        });
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
        } catch (Exception e) {
            return "";
        }
    }

    private String getLastMessageTimeForChat(String chatType, String chatId) {
        try {
            com.antor.nearbychat.Database.MessageEntity lastMsg = AppDatabase.getInstance(this).messageDao().getLastMessageForChat(chatType, chatId);
            if (lastMsg != null) {
                long time = lastMsg.timestampMillis, now = System.currentTimeMillis(), diff = now - time;
                if (diff < 24 * 60 * 60 * 1000)
                    return new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(new java.util.Date(time));
                if (diff < 7 * 24 * 60 * 60 * 1000)
                    return new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(new java.util.Date(time));
                if (diff < 365L * 24 * 60 * 60 * 1000L)
                    return new java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()).format(new java.util.Date(time));
                return new java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault()).format(new java.util.Date(time));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
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
            return new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(new java.util.Date(messageTimestamp));
        }
        nowCal.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (nowCal.get(java.util.Calendar.YEAR) == messageCal.get(java.util.Calendar.YEAR) &&
                nowCal.get(java.util.Calendar.DAY_OF_YEAR) == messageCal.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "Yesterday";
        }
        long diff = System.currentTimeMillis() - messageTimestamp;
        if (diff < 7 * 24 * 60 * 60 * 1000) {
            return new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(new java.util.Date(messageTimestamp));
        }
        if (java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) == messageCal.get(java.util.Calendar.YEAR)) {
            return new java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()).format(new java.util.Date(messageTimestamp));
        }
        return new java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault()).format(new java.util.Date(messageTimestamp));
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
            String[] options = {"Clear History"};

            new AlertDialog.Builder(this)
                    .setTitle("Chat Options")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            clearHistory(chat);
                        }
                    })
                    .show();
            return;
        }
        String[] options = {"Block", "Clear History", "Delete Chat"};

        new AlertDialog.Builder(this)
                .setTitle("Chat Options")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            blockChat(chat);
                            break;
                        case 1:
                            clearHistory(chat);
                            break;
                        case 2:
                            deleteChat(chat);
                            break;
                    }
                })
                .show();
    }

    private void blockChat(ChatItem chat) {
        String userIdToBlock;

        if ("G".equals(chat.type)) {
            Toast.makeText(this, "Cannot block groups", Toast.LENGTH_SHORT).show();
            return;
        } else if ("F".equals(chat.type)) {
            userIdToBlock = chat.displayId;
        } else {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Block User")
                .setMessage("Block " + chat.name + "?\n\nYou will no longer receive messages from this user.")
                .setPositiveButton("Block", (dialog, which) -> {
                    SharedPreferences prefs = getSharedPreferences("NearbyChatPrefs", MODE_PRIVATE);
                    String json = prefs.getString("blockedList", null);
                    List<String> blockedList;

                    if (json != null) {
                        Type type = new TypeToken<List<String>>(){}.getType();
                        blockedList = new Gson().fromJson(json, type);
                    } else {
                        blockedList = new ArrayList<>();
                    }
                    if (!blockedList.contains(userIdToBlock)) {
                        blockedList.add(userIdToBlock);
                        prefs.edit().putString("blockedList", new Gson().toJson(blockedList)).apply();
                    }
                    List<FriendModel> friends = DataCache.getFriends(this);
                    friends.removeIf(f -> f.getDisplayId().equals(userIdToBlock));
                    DataCache.saveFriends(this, friends);

                    Toast.makeText(this, "User blocked", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearHistory(ChatItem chat) {
        new AlertDialog.Builder(this)
                .setTitle("Clear History")
                .setMessage("Delete all messages in this chat?")
                .setPositiveButton("Clear", (dialog, which) -> {
                    executor.execute(() -> {
                        AppDatabase.getInstance(this).messageDao().deleteMessagesForChat(chat.type, chat.id);
                        runOnUiThread(() -> {
                            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteChat(ChatItem chat) {
        if (chat.type.equals("N")) {
            Toast.makeText(this, "Cannot delete Nearby Chat", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete Chat")
                .setMessage("Delete " + chat.name + "?\n\nThis will delete the chat and all messages.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (chat.type.equals("G")) {
                        List<GroupModel> groups = DataCache.getGroups(this);
                        groups.removeIf(g -> g.getId().equals(chat.id));
                        DataCache.saveGroups(this, groups);
                    } else if (chat.type.equals("F")) {
                        List<FriendModel> friends = DataCache.getFriends(this);
                        friends.removeIf(f ->
                                MessageHelper.timestampToAsciiId(
                                        MessageHelper.displayIdToTimestamp(f.getDisplayId())
                                ).equals(chat.id)
                        );
                        DataCache.saveFriends(this, friends);
                    }
                    executor.execute(() -> {
                        AppDatabase.getInstance(this).messageDao().deleteMessagesForChat(chat.type, chat.id);
                        runOnUiThread(() -> {
                            Toast.makeText(this, chat.name + " deleted", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    public static class ChatItem {
        String id, name, type, lastMessage, lastMessageTime, displayId;
        long lastMessageTimestamp;
        boolean hasUnreadMessages;

        public ChatItem(String id, String name, String type, String lastMessage, String lastMessageTime, String displayId, long lastMessageTimestamp, boolean hasUnreadMessages) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.lastMessage = lastMessage;
            this.lastMessageTime = lastMessageTime;
            this.displayId = displayId;
            this.lastMessageTimestamp = lastMessageTimestamp;
            this.hasUnreadMessages = hasUnreadMessages;
        }
    }

    private void showAddEditGroupDialog(final GroupModel group, final int position) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_edit_groups);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        EditText editName = dialog.findViewById(R.id.editName);
        EditText editKey = dialog.findViewById(R.id.editEncryptionKey);
        Button btnAdd = dialog.findViewById(R.id.btnAdd);

        ImageView switchNotification = dialog.findViewById(R.id.switchNotification);
        ImageView profilePic = dialog.findViewById(R.id.profilePicRound);
        TextView groupIdText = dialog.findViewById(R.id.groupID);
        ImageView qrCodeShow = dialog.findViewById(R.id.qrCodeShow);

        if (group != null) {
            ((TextView) dialog.findViewById(R.id.dia_title)).setText("Edit Group");
            btnAdd.setText("Save");
            editName.setText(group.getName());
            editKey.setText(group.getEncryptionKey());
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
            if (qrCodeShow != null) {
                qrCodeShow.setVisibility(View.VISIBLE);
                qrCodeShow.setOnClickListener(v -> {
                    long bits = asciiIdToTimestamp(group.getId());
                    String displayId = getUserIdString(bits);
                    String qrData = "GROUP:" + displayId + "|" + group.getName() + "|" + group.getEncryptionKey();
                    Intent intent = new Intent(this, QRCodeActivity.class);
                    intent.putExtra("qr_data", qrData);
                    intent.putExtra("qr_type", "group");
                    intent.putExtra("display_name", group.getName());
                    startActivity(intent);
                });
            }
        } else {
            if (switchNotification != null) {
                switchNotification.setVisibility(View.GONE);
            }if (profilePic != null) {
                profilePic.setVisibility(View.GONE);
            }
            if (groupIdText != null) {
                groupIdText.setVisibility(View.GONE);
            }
            if (qrCodeShow != null) {
                qrCodeShow.setVisibility(View.GONE);
            }
        }
        dialog.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        btnAdd.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Group name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            if (group != null) {
                group.setName(name);
                group.setEncryptionKey(editKey.getText().toString().trim());
            } else {
                groupsList.add(new GroupModel(MessageHelper.timestampToAsciiId(System.currentTimeMillis()), name, editKey.getText().toString().trim()));
            }
            saveGroups();
            dialog.dismiss();
        });
        dialog.show();
    }

    private boolean isUserBlocked(String displayId) {
        SharedPreferences prefs = getSharedPreferences("NearbyChatPrefs", MODE_PRIVATE);
        String json = prefs.getString("blockedList", null);
        if (json == null) return false;
        Type type = new TypeToken<List<String>>(){}.getType();
        List<String> blockedList = gson.fromJson(json, type);
        return blockedList != null && blockedList.contains(displayId);
    }

    private void blockUser(String displayId, String name) {
        SharedPreferences prefs = getSharedPreferences("NearbyChatPrefs", MODE_PRIVATE);
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
        List<FriendModel> friends = DataCache.getFriends(this);
        friends.removeIf(f -> f.getDisplayId().equals(displayId));
        DataCache.saveFriends(this, friends);

        String toastName = (name == null || name.isEmpty()) ? displayId : name;
        Toast.makeText(this, "User " + toastName + " blocked", Toast.LENGTH_SHORT).show();

        loadAndDisplayAllChats();
    }

    private void unblockUser(String displayId, String name) {
        SharedPreferences prefs = getSharedPreferences("NearbyChatPrefs", MODE_PRIVATE);
        String json = prefs.getString("blockedList", null);
        List<String> blockedList;
        if (json != null) {
            Type type = new TypeToken<List<String>>(){}.getType();
            blockedList = gson.fromJson(json, type);
        } else {
            return;
        }
        if (blockedList.contains(displayId)) {
            blockedList.remove(displayId);
            prefs.edit().putString("blockedList", gson.toJson(blockedList)).apply();
        }
        List<FriendModel> friends = DataCache.getFriends(this);
        boolean exists = friends.stream().anyMatch(f -> f.getDisplayId().equals(displayId));
        if (!exists) {
            String friendName = (name == null || name.isEmpty()) ? displayId : name;
            friends.add(new FriendModel(displayId, friendName, ""));
            DataCache.saveFriends(this, friends);
        }
        String toastName = (name == null || name.isEmpty()) ? displayId : name;
        Toast.makeText(this, "User " + toastName + " unblocked", Toast.LENGTH_SHORT).show();

        loadAndDisplayAllChats();
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

        Button btnBlockUnblock = dialog.findViewById(R.id.btnDelete);

        Button btnAdd = dialog.findViewById(R.id.btnAdd);
        ImageView switchNotification = dialog.findViewById(R.id.switchNotification);
        ImageView profilePic = dialog.findViewById(R.id.profilePicRound);
        ImageView qrCodeShow = dialog.findViewById(R.id.qrCodeShow);

        if (friend != null) {
            ((TextView) dialog.findViewById(R.id.dia_title)).setText("Edit Friend");
            btnAdd.setText("Save");
            editName.setText(friend.getName());
            editId.setText(friend.getDisplayId());
            editKey.setText(friend.getEncryptionKey());
            editId.setEnabled(false);

            btnBlockUnblock.setVisibility(View.VISIBLE);
            final boolean isBlocked = isUserBlocked(friend.getDisplayId());
            if (isBlocked) {
                btnBlockUnblock.setText("Unblock");
                btnBlockUnblock.setBackgroundColor(Color.parseColor("#007BFF"));
            } else {
                btnBlockUnblock.setText("Block");
                btnBlockUnblock.setBackgroundColor(Color.parseColor("#DC3545"));
            }

            btnBlockUnblock.setOnClickListener(v -> {
                String currentName = editName.getText().toString().trim();
                if (currentName.isEmpty()) currentName = friend.getName();
                if (currentName.isEmpty()) currentName = friend.getDisplayId();

                if (isBlocked) {
                    unblockUser(friend.getDisplayId(), currentName);
                } else {
                    blockUser(friend.getDisplayId(), currentName);
                }
                dialog.dismiss();
            });

            if (profilePic != null) {
                profilePic.setVisibility(View.VISIBLE);
                ProfilePicLoader.loadProfilePicture(this, friend.getDisplayId(), profilePic);
            }
            if (qrCodeShow != null) {
                qrCodeShow.setVisibility(View.VISIBLE);
                qrCodeShow.setOnClickListener(v -> {
                    String currentName = editName.getText().toString().trim();
                    if (currentName.isEmpty()) currentName = friend.getName();

                    String plainText = friend.getDisplayId() + "|" + currentName + "|" + editKey.getText().toString().trim();
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
                    MessageHelper.displayIdToTimestamp(friend.getDisplayId())
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

        } else {
            if (switchNotification != null) {
                switchNotification.setVisibility(View.GONE);
            }
            if (profilePic != null) {
                profilePic.setVisibility(View.GONE);
            }
            if (qrCodeShow != null) {
                qrCodeShow.setVisibility(View.GONE);
            }
            btnBlockUnblock.setVisibility(View.GONE);
        }

        dialog.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());

        btnAdd.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            String id = editId.getText().toString().trim();
            String key = editKey.getText().toString().trim();
            if (id.isEmpty()) {
                Toast.makeText(this, "Friend ID cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            if (id.length() != 8) {
                Toast.makeText(this, "Friend ID must be 8 characters", Toast.LENGTH_SHORT).show();
                return;
            }

            if (friend != null) {
                friend.setName(name);
                friend.setEncryptionKey(key);
                if (position != -1 && position < friendsList.size()) {
                    friendsList.set(position, friend);
                }
            } else {
                friendsList.add(new FriendModel(id, name, key));
            }
            saveFriends();
            dialog.dismiss();
        });
        dialog.show();
    }

    private boolean getNotificationState(String chatType, String chatId) {
        SharedPreferences prefs = getSharedPreferences("NearbyChatPrefs", MODE_PRIVATE);
        String key = chatType + ":" + chatId;
        return prefs.getBoolean("notification_" + key, false);
    }

    private void saveNotificationState(String chatType, String chatId, boolean enabled) {
        SharedPreferences prefs = getSharedPreferences("NearbyChatPrefs", MODE_PRIVATE);
        String key = chatType + ":" + chatId;
        prefs.edit().putBoolean("notification_" + key, enabled).apply();
    }

    private void loadData() {
        groupsList = DataCache.getGroups(this);
        friendsList = DataCache.getFriends(this);
    }

    private void saveGroups() {
        DataCache.saveGroups(this, groupsList);
        CryptoUtils.clearKeyCache();
    }

    private void saveFriends() {
        DataCache.saveFriends(this, friendsList);
        CryptoUtils.clearKeyCache();
    }
}