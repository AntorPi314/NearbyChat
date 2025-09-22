package com.antor.nearbychat;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class GroupsActivity extends Activity {

    private static final String TAG = "GroupsActivity";
    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_GROUPS_LIST = "groupsList";
    private static final String KEY_SELECTED_GROUP = "selectedGroup";
    private static final String DEFAULT_GROUP_ID = "default_group";

    private static final int REQUEST_GALLERY = 300;
    private static final int REQUEST_CAMERA = 301;
    private static final int REQUEST_STORAGE_PERMISSION = 302;
    private static final int REQUEST_CAMERA_PERMISSION = 303;

    private LinearLayout groupsContainer;
    private List<GroupModel> groupsList;
    private String selectedGroupId;
    private Gson gson;

    // For image picker
    private String currentGroupId;
    private ImageView currentGroupPic;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_groups);

        UiUtilsBlue.setLightSystemBars(this);

        gson = new Gson();
        initializeViews();
        loadGroupsData();
        setupDefaultGroup();
        displayGroups();
    }

    private void initializeViews() {
        groupsContainer = findViewById(R.id.groupsContainer);

        // Back button
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // Add button
        findViewById(R.id.btnAdd).setOnClickListener(v -> showAddGroupDialog());
    }

    private void loadGroupsData() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Load groups list
        String json = prefs.getString(KEY_GROUPS_LIST, null);
        if (json != null) {
            Type type = new TypeToken<List<GroupModel>>(){}.getType();
            groupsList = gson.fromJson(json, type);
        }
        if (groupsList == null) {
            groupsList = new ArrayList<>();
        }

        // Load selected group
        selectedGroupId = prefs.getString(KEY_SELECTED_GROUP, DEFAULT_GROUP_ID);
    }

    private void setupDefaultGroup() {
        // Check if default group exists
        boolean hasDefault = false;
        for (GroupModel group : groupsList) {
            if (DEFAULT_GROUP_ID.equals(group.getId())) {
                hasDefault = true;
                break;
            }
        }

        // Add default group if doesn't exist
        if (!hasDefault) {
            GroupModel defaultGroup = new GroupModel(DEFAULT_GROUP_ID, "Default", "");
            groupsList.add(0, defaultGroup); // Add at the beginning
        }
    }

    private void displayGroups() {
        groupsContainer.removeAllViews();

        for (GroupModel group : groupsList) {
            View groupView = createGroupView(group);
            groupsContainer.addView(groupView);
        }
    }

    private View createGroupView(GroupModel group) {
        LinearLayout groupLayout = new LinearLayout(this);
        groupLayout.setOrientation(LinearLayout.HORIZONTAL);
        groupLayout.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        groupLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Radio button
        RadioButton radioButton = new RadioButton(this);
        radioButton.setChecked(group.getId().equals(selectedGroupId));
        radioButton.setOnClickListener(v -> selectGroup(group.getId()));

        LinearLayout.LayoutParams radioParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        radioParams.setMargins(0, 0, dpToPx(12), 0);
        radioButton.setLayoutParams(radioParams);

        // Profile image
        ImageView profilePic = new ImageView(this);
        profilePic.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)));
        profilePic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        profilePic.setBackground(ContextCompat.getDrawable(this, R.drawable.circle_bg));
        profilePic.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        loadGroupPicture(group.getId(), profilePic);

        LinearLayout.LayoutParams picParams = new LinearLayout.LayoutParams(
                dpToPx(48), dpToPx(48)
        );
        picParams.setMargins(0, 0, dpToPx(12), 0);
        profilePic.setLayoutParams(picParams);

        // Group name
        TextView groupName = new TextView(this);
        groupName.setText(group.getName());
        groupName.setTextSize(16);
        groupName.setTextColor(Color.parseColor("#333333"));

        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        groupName.setLayoutParams(nameParams);

        // Edit button (only for non-default groups)
        ImageView editButton = new ImageView(this);
        if (DEFAULT_GROUP_ID.equals(group.getId())) {
            editButton.setVisibility(View.INVISIBLE);
        } else {
            editButton.setImageResource(R.drawable.ic_edit);
            editButton.setOnClickListener(v -> showEditGroupDialog(group));
            editButton.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        }

        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                dpToPx(40), dpToPx(40)
        );
        editButton.setLayoutParams(editParams);

        // Add views to layout
        groupLayout.addView(radioButton);
        groupLayout.addView(profilePic);
        groupLayout.addView(groupName);
        groupLayout.addView(editButton);

        return groupLayout;
    }

    private void selectGroup(String groupId) {
        selectedGroupId = groupId;

        // Update SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_SELECTED_GROUP, selectedGroupId).apply();

        // Refresh display
        displayGroups();

        Toast.makeText(this, "Group selected", Toast.LENGTH_SHORT).show();
    }

    private void showAddGroupDialog() {
        showGroupDialog(null, false);
    }

    private void showEditGroupDialog(GroupModel group) {
        showGroupDialog(group, true);
    }

    private void showGroupDialog(GroupModel group, boolean isEdit) {
        try {
            Dialog dialog = new Dialog(this);
            dialog.setContentView(R.layout.dialog_add_edit_groups);

            // Make background transparent and set window attributes
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.copyFrom(dialog.getWindow().getAttributes());
                lp.width = dpToPx(280);
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                dialog.getWindow().setAttributes(lp);
            }

            // Get dialog views
            TextView title = dialog.findViewById(R.id.dia_title);
            EditText editName = dialog.findViewById(R.id.editName);
            EditText editEncryptionKey = dialog.findViewById(R.id.editEncryptionKey);
            ImageView profilePic = dialog.findViewById(R.id.profilePicRound);
            Button btnCancel = dialog.findViewById(R.id.btnCancel);
            Button btnAdd = dialog.findViewById(R.id.btnAdd);

            // Set dialog content based on mode
            if (isEdit && group != null) {
                title.setText("Edit Group");
                editName.setText(group.getName());
                editEncryptionKey.setText(group.getEncryptionKey());
                btnAdd.setText("Save");
                loadGroupPicture(group.getId(), profilePic);
                currentGroupId = group.getId();
            } else {
                title.setText("Add New Group");
                btnAdd.setText("Add");
                currentGroupId = "group_" + System.currentTimeMillis();
                profilePic.setImageResource(R.drawable.profile_pic_round_vector);
            }

            currentGroupPic = profilePic;

            // Profile pic click listener
            profilePic.setOnClickListener(v -> showImagePickerDialog(currentGroupId, profilePic));

            // Button listeners
            btnCancel.setOnClickListener(v -> dialog.dismiss());

            btnAdd.setOnClickListener(v -> {
                String name = editName.getText().toString().trim();
                String encryptionKey = editEncryptionKey.getText().toString().trim();

                if (name.isEmpty()) {
                    Toast.makeText(this, "Please enter group name", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (isEdit && group != null) {
                    // Update existing group
                    group.setName(name);
                    group.setEncryptionKey(encryptionKey);
                } else {
                    // Add new group
                    GroupModel newGroup = new GroupModel(currentGroupId, name, encryptionKey);
                    groupsList.add(newGroup);
                }

                saveGroupsData();
                displayGroups();

                Toast.makeText(this, isEdit ? "Group updated" : "Group added", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });

            dialog.show();

        } catch (Exception e) {
            Log.e(TAG, "Error showing group dialog", e);
            Toast.makeText(this, "Error opening dialog", Toast.LENGTH_SHORT).show();
        }
    }

    private void showImagePickerDialog(String groupId, ImageView profilePic) {
        // Check if we need storage permission
        if (needsStoragePermission()) {
            currentGroupId = groupId;
            currentGroupPic = profilePic;
            requestStoragePermission();
            return;
        }
        showImagePickerDialogInternal(groupId, profilePic);
    }

    private void showImagePickerDialogInternal(String groupId, ImageView profilePic) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Select Image")
                .setItems(new String[]{"Gallery", "Camera"}, (dialog, which) -> {
                    if (which == 0) {
                        openGallery(groupId, profilePic);
                    } else {
                        openCamera(groupId, profilePic);
                    }
                })
                .show();
    }

    private void openGallery(String groupId, ImageView profilePic) {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        currentGroupId = groupId;
        currentGroupPic = profilePic;
        startActivityForResult(intent, REQUEST_GALLERY);
    }

    private void openCamera(String groupId, ImageView profilePic) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            currentGroupId = groupId;
            currentGroupPic = profilePic;
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
            return;
        }
        openCameraInternal(groupId, profilePic);
    }

    private void openCameraInternal(String groupId, ImageView profilePic) {
        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (intent.resolveActivity(getPackageManager()) != null) {
                currentGroupId = groupId;
                currentGroupPic = profilePic;
                startActivityForResult(intent, REQUEST_CAMERA);
            } else {
                Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error opening camera", e);
            Toast.makeText(this, "Error opening camera", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean needsStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED;
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    private void requestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.READ_MEDIA_IMAGES},
                    REQUEST_STORAGE_PERMISSION);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (currentGroupId != null && currentGroupPic != null) {
                    showImagePickerDialogInternal(currentGroupId, currentGroupPic);
                }
            } else {
                Toast.makeText(this, "Storage permission required for gallery access", Toast.LENGTH_SHORT).show();
                if (currentGroupId != null && currentGroupPic != null) {
                    openCamera(currentGroupId, currentGroupPic);
                }
            }
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (currentGroupId != null && currentGroupPic != null) {
                    openCameraInternal(currentGroupId, currentGroupPic);
                }
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_GALLERY && resultCode == RESULT_OK) {
            try {
                if (data != null && data.getData() != null && currentGroupPic != null && currentGroupId != null) {
                    Uri imageUri = data.getData();
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                    Bitmap resizedBitmap = ImageConverter.resizeAndCrop(bitmap, 94, 94);
                    Bitmap circularBitmap = ImageConverter.createCircularBitmap(resizedBitmap);
                    currentGroupPic.setImageBitmap(circularBitmap);
                    saveGroupPicture(currentGroupId, resizedBitmap);
                } else {
                    Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing gallery image", e);
                Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_CAMERA && resultCode == RESULT_OK) {
            try {
                if (data != null && data.getExtras() != null) {
                    Bundle extras = data.getExtras();
                    Bitmap bitmap = (Bitmap) extras.get("data");
                    if (bitmap != null && currentGroupPic != null && currentGroupId != null) {
                        Bitmap resizedBitmap = ImageConverter.resizeAndCrop(bitmap, 94, 94);
                        Bitmap circularBitmap = ImageConverter.createCircularBitmap(resizedBitmap);
                        currentGroupPic.setImageBitmap(circularBitmap);
                        saveGroupPicture(currentGroupId, resizedBitmap);
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

    private void loadGroupPicture(String groupId, ImageView imageView) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String base64Image = prefs.getString("group_pic_" + groupId, null);
        if (base64Image != null) {
            try {
                byte[] imageBytes = Base64.decode(base64Image, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                Bitmap circularBitmap = ImageConverter.createCircularBitmap(bitmap);
                imageView.setImageBitmap(circularBitmap);
            } catch (Exception e) {
                Log.e(TAG, "Error loading group picture for " + groupId, e);
                imageView.setImageResource(R.drawable.profile_pic_round_vector);
            }
        } else {
            imageView.setImageResource(R.drawable.profile_pic_round_vector);
        }
    }

    private void saveGroupPicture(String groupId, Bitmap bitmap) {
        try {
            Bitmap resizedBitmap = ImageConverter.resizeAndCrop(bitmap, 94, 94);
            Bitmap circularBitmap = ImageConverter.createCircularBitmap(resizedBitmap);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            circularBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] imageBytes = baos.toByteArray();
            String base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT);

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString("group_pic_" + groupId, base64Image).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving group picture", e);
        }
    }

    private void saveGroupsData() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString(KEY_GROUPS_LIST, gson.toJson(groupsList)).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving groups data", e);
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // GroupModel class
    private static class GroupModel {
        private String id;
        private String name;
        private String encryptionKey;

        public GroupModel(String id, String name, String encryptionKey) {
            this.id = id;
            this.name = name;
            this.encryptionKey = encryptionKey;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getEncryptionKey() { return encryptionKey; }

        public void setName(String name) { this.name = name; }
        public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
    }
}