package com.antor.nearbychat;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Activity;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class GroupsFriendsActivity extends Activity {

    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_GROUPS_LIST = "groupsList";
    private static final String KEY_FRIENDS_LIST = "friendsList";

    private RecyclerView recyclerView;
    private Button groupsBtn, friendsBtn;
    private ImageView btnBack, btnAdd;

    private List<GroupModel> groupsList = new ArrayList<>();
    private List<FriendModel> friendsList = new ArrayList<>();
    private GroupFriendAdapter adapter;
    private boolean showingGroups = true;
    private final Gson gson = new Gson();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_groups_friends);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        loadData();
        setupUI();
        showGroups();
    }

    private void setupUI() {
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        groupsBtn = findViewById(R.id.groupsBtn);
        friendsBtn = findViewById(R.id.friendsBtn);
        btnBack = findViewById(R.id.btnBack);
        btnAdd = findViewById(R.id.btnAdd);

        btnBack.setOnClickListener(v -> finish());

        groupsBtn.setOnClickListener(v -> {
            if (!showingGroups) {
                showGroups();
            }
        });

        friendsBtn.setOnClickListener(v -> {
            if (showingGroups) {
                showFriends();
            }
        });

        btnAdd.setOnClickListener(v -> {
            if (showingGroups) {
                showAddEditGroupDialog(null, -1);
            } else {
                showAddEditFriendDialog(null, -1);
            }
        });
    }

    private void showGroups() {
        showingGroups = true;
        groupsBtn.setEnabled(false);
        friendsBtn.setEnabled(true);

        adapter = new GroupFriendAdapter(this, groupsList, GroupFriendAdapter.ListType.GROUPS,
                new GroupFriendAdapter.OnItemClickListener() {
                    @Override
                    public void onItemClick(int position) {
                        selectGroup(groupsList.get(position));
                    }
                    @Override
                    public void onEditClick(int position) {
                        if (position > 0) { // Can't edit "Nearby Chat"
                            showAddEditGroupDialog(groupsList.get(position), position);
                        }
                    }
                });
        recyclerView.setAdapter(adapter);
    }

    private void showFriends() {
        showingGroups = false;
        friendsBtn.setEnabled(false);
        groupsBtn.setEnabled(true);

        adapter = new GroupFriendAdapter(this, friendsList, GroupFriendAdapter.ListType.FRIENDS,
                new GroupFriendAdapter.OnItemClickListener() {
                    @Override
                    public void onItemClick(int position) {
                        selectFriend(friendsList.get(position));
                    }
                    @Override
                    public void onEditClick(int position) {
                        showAddEditFriendDialog(friendsList.get(position), position);
                    }
                });
        recyclerView.setAdapter(adapter);
    }

    private void selectGroup(GroupModel group) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra("chatType", group.getId().isEmpty() ? "N" : "G");
        resultIntent.putExtra("chatId", group.getId()); // Empty string for Nearby, 5-char for groups
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    private void selectFriend(FriendModel friend) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra("chatType", "F");
        // Convert 8-char display ID to 5-char ASCII for transmission
        long bits = BleMessagingService.displayIdToTimestamp(friend.getDisplayId());
        String asciiId = BleMessagingService.timestampToAsciiId(bits);
        resultIntent.putExtra("chatId", asciiId);
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    private void showAddEditGroupDialog(final GroupModel group, final int position) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_edit_groups);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        TextView title = dialog.findViewById(R.id.dia_title);
        EditText editName = dialog.findViewById(R.id.editName);
        EditText editKey = dialog.findViewById(R.id.editEncryptionKey);
        Button btnDelete = dialog.findViewById(R.id.btnDelete);
        Button btnCancel = dialog.findViewById(R.id.btnCancel);
        Button btnAdd = dialog.findViewById(R.id.btnAdd);

        if (group != null) {
            title.setText("Edit Group");
            btnAdd.setText("Save");
            editName.setText(group.getName());
            editKey.setText(group.getEncryptionKey());
            btnDelete.setVisibility(View.VISIBLE);
        } else {
            title.setText("Add New Group");
            btnAdd.setText("Add");
            btnDelete.setVisibility(View.GONE);
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnDelete.setOnClickListener(v -> {
            groupsList.remove(position);
            saveGroups();
            adapter.notifyDataSetChanged();
            dialog.dismiss();
        });

        btnAdd.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            String key = editKey.getText().toString().trim();

            if (name.isEmpty()) {
                Toast.makeText(this, "Group name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }

            if (group != null) {
                group.setName(name);
                group.setEncryptionKey(key);
            } else {
                String newId = BleMessagingService.timestampToAsciiId(System.currentTimeMillis());
                groupsList.add(new GroupModel(newId, name, key));
            }
            saveGroups();
            adapter.notifyDataSetChanged();
            dialog.dismiss();
        });

        dialog.show();
    }

    private void showAddEditFriendDialog(final FriendModel friend, final int position) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_edit_friend);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        TextView title = dialog.findViewById(R.id.dia_title);
        EditText editName = dialog.findViewById(R.id.editName);
        EditText editId = dialog.findViewById(R.id.editFriendId);
        Button btnDelete = dialog.findViewById(R.id.btnDelete);
        Button btnCancel = dialog.findViewById(R.id.btnCancel);
        Button btnAdd = dialog.findViewById(R.id.btnAdd);

        if (friend != null) {
            title.setText("Edit Friend");
            btnAdd.setText("Save");
            editName.setText(friend.getName());
            editId.setText(friend.getDisplayId());
            editId.setEnabled(false);
            btnDelete.setVisibility(View.VISIBLE);
        } else {
            title.setText("Add New Friend");
            btnAdd.setText("Add");
            btnDelete.setVisibility(View.GONE);
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnDelete.setOnClickListener(v -> {
            friendsList.remove(position);
            saveFriends();
            adapter.notifyDataSetChanged();
            dialog.dismiss();
        });

        btnAdd.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            String id = editId.getText().toString().trim();

            if (name.isEmpty() || id.isEmpty()) {
                Toast.makeText(this, "Fields cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }

            if (id.length() != 8) {
                Toast.makeText(this, "Friend ID must be 8 characters", Toast.LENGTH_SHORT).show();
                return;
            }

            if (friend != null) {
                friend.setName(name);
            } else {
                friendsList.add(new FriendModel(id, name));
            }
            saveFriends();
            adapter.notifyDataSetChanged();
            dialog.dismiss();
        });

        dialog.show();
    }

    private void loadData() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Load Groups
        String groupsJson = prefs.getString(KEY_GROUPS_LIST, null);
        if (groupsJson != null) {
            Type type = new TypeToken<ArrayList<GroupModel>>(){}.getType();
            groupsList = gson.fromJson(groupsJson, type);
        }

        // Always ensure "Nearby Chat" is first
        if (groupsList.isEmpty() || !groupsList.get(0).getId().isEmpty()) {
            groupsList.add(0, new GroupModel("", "Nearby Chat", ""));
        }

        // Load Friends
        String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
        if (friendsJson != null) {
            Type type = new TypeToken<ArrayList<FriendModel>>(){}.getType();
            friendsList = gson.fromJson(friendsJson, type);
        }
    }

    private void saveGroups() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        List<GroupModel> savable = new ArrayList<>(groupsList);
        savable.remove(0); // Don't save "Nearby Chat"
        prefs.edit().putString(KEY_GROUPS_LIST, gson.toJson(savable)).apply();
    }

    private void saveFriends() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_FRIENDS_LIST, gson.toJson(friendsList)).apply();
    }
}