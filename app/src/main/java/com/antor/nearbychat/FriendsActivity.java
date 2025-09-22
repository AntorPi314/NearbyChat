package com.antor.nearbychat;

import android.app.Activity;
import android.app.AlertDialog;
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

public class FriendsActivity extends Activity {

    private static final String TAG = "FriendsActivity";
    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_FRIENDS_LIST = "friendsList";
    private static final String KEY_USER_ID_BITS = "userIdBits";
    private static final String KEY_NAME_MAP = "nameMap";
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray();

    private static final int REQUEST_GALLERY = 400;
    private static final int REQUEST_CAMERA = 401;
    private static final int REQUEST_STORAGE_PERMISSION = 402;
    private static final int REQUEST_CAMERA_PERMISSION = 403;

    private LinearLayout friendsContainer;
    private List<FriendModel> friendsList;
    private String currentUserId;
    private Gson gson;

    // For image picker
    private String currentFriendId;
    private ImageView currentProfilePic;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_friends);

        UiUtilsBlue.setLightSystemBars(this);

        gson = new Gson();
        initializeCurrentUser();
        initializeViews();
        loadFriendsData();
        displayFriends();
    }

    private void initializeCurrentUser() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long userIdBits = prefs.getLong(KEY_USER_ID_BITS, -1);
        if (userIdBits == -1) {
            userIdBits = System.currentTimeMillis() & ((1L << 40) - 1);
            prefs.edit().putLong(KEY_USER_ID_BITS, userIdBits).apply();
        }
        currentUserId = getUserIdString(userIdBits);
    }

    private void initializeViews() {
        friendsContainer = findViewById(R.id.friendsContainer);

        // Back button
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // Add button
        findViewById(R.id.btnAdd).setOnClickListener(v -> showAddFriendDialog());
    }

    private void loadFriendsData() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Load friends list
        String json = prefs.getString(KEY_FRIENDS_LIST, null);
        if (json != null) {
            Type type = new TypeToken<List<FriendModel>>(){}.getType();
            friendsList = gson.fromJson(json, type);
        }
        if (friendsList == null) {
            friendsList = new ArrayList<>();
        }
    }

    private void displayFriends() {
        friendsContainer.removeAllViews();

        // Add "You:" section
        View yourView = createYourSection();
        friendsContainer.addView(yourView);

        // Add separator
        View separator = createSectionSeparator();
        friendsContainer.addView(separator);

        // Add "Friends:" label
        View friendsLabel = createFriendsLabel();
        friendsContainer.addView(friendsLabel);

        // Add friends list
        for (FriendModel friend : friendsList) {
            View friendView = createFriendView(friend);
            friendsContainer.addView(friendView);
        }

        // Show empty message if no friends
        if (friendsList.isEmpty()) {
            View emptyView = createEmptyMessage();
            friendsContainer.addView(emptyView);
        }
    }

    private View createYourSection() {
        LinearLayout yourLayout = new LinearLayout(this);
        yourLayout.setOrientation(LinearLayout.HORIZONTAL);
        yourLayout.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(12));
        yourLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // "You:" label
        TextView youLabel = new TextView(this);
        youLabel.setText("You:");
        youLabel.setTextSize(14);
        youLabel.setTextColor(Color.parseColor("#666666"));
        youLabel.setTypeface(null, android.graphics.Typeface.BOLD);

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelParams.setMargins(0, 0, dpToPx(12), 0);
        youLabel.setLayoutParams(labelParams);

        yourLayout.addView(youLabel);
        yourLayout.addView(createUserView());

        return yourLayout;
    }

    private View createUserView() {
        LinearLayout userLayout = new LinearLayout(this);
        userLayout.setOrientation(LinearLayout.HORIZONTAL);
        userLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Profile image
        ImageView profilePic = new ImageView(this);
        profilePic.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)));
        profilePic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        profilePic.setBackground(ContextCompat.getDrawable(this, R.drawable.circle_bg));
        profilePic.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        loadProfilePicture(currentUserId, profilePic);

        LinearLayout.LayoutParams picParams = new LinearLayout.LayoutParams(
                dpToPx(48), dpToPx(48)
        );
        picParams.setMargins(0, 0, dpToPx(12), 0);
        profilePic.setLayoutParams(picParams);

        // User name
        TextView userName = new TextView(this);
        userName.setText(getDisplayName(currentUserId));
        userName.setTextSize(16);
        userName.setTextColor(Color.parseColor("#333333"));

        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        userName.setLayoutParams(nameParams);

        // Edit button
        ImageView editButton = new ImageView(this);
        editButton.setImageResource(R.drawable.ic_edit);
        editButton.setOnClickListener(v -> showEditUserDialog());
        editButton.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));

        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                dpToPx(40), dpToPx(40)
        );
        editButton.setLayoutParams(editParams);

        // Add views to layout
        userLayout.addView(profilePic);
        userLayout.addView(userName);
        userLayout.addView(editButton);

        return userLayout;
    }

    private View createSectionSeparator() {
        View separator = new View(this);
        separator.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
        ));
        separator.setBackgroundColor(Color.parseColor("#E0E0E0"));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) separator.getLayoutParams();
        params.setMargins(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        return separator;
    }

    private View createFriendsLabel() {
        TextView friendsLabel = new TextView(this);
        friendsLabel.setText("Friends:");
        friendsLabel.setTextSize(14);
        friendsLabel.setTextColor(Color.parseColor("#666666"));
        friendsLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        friendsLabel.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(4));
        return friendsLabel;
    }

    private View createFriendView(FriendModel friend) {
        LinearLayout friendLayout = new LinearLayout(this);
        friendLayout.setOrientation(LinearLayout.HORIZONTAL);
        friendLayout.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        friendLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Profile image
        ImageView profilePic = new ImageView(this);
        profilePic.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)));
        profilePic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        profilePic.setBackground(ContextCompat.getDrawable(this, R.drawable.circle_bg));
        profilePic.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        loadProfilePicture(friend.getId(), profilePic);

        LinearLayout.LayoutParams picParams = new LinearLayout.LayoutParams(
                dpToPx(48), dpToPx(48)
        );
        picParams.setMargins(0, 0, dpToPx(12), 0);
        profilePic.setLayoutParams(picParams);

        // Friend name or ID
        TextView friendName = new TextView(this);
        String displayName = friend.getName() != null && !friend.getName().isEmpty()
                ? friend.getName() : friend.getId();
        friendName.setText(displayName);
        friendName.setTextSize(16);
        friendName.setTextColor(Color.parseColor("#333333"));

        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        friendName.setLayoutParams(nameParams);

        // Edit button
        ImageView editButton = new ImageView(this);
        editButton.setImageResource(R.drawable.ic_edit);
        editButton.setOnClickListener(v -> showEditFriendDialog(friend));
        editButton.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));

        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                dpToPx(40), dpToPx(40)
        );
        editButton.setLayoutParams(editParams);

        // Add views to layout
        friendLayout.addView(profilePic);
        friendLayout.addView(friendName);
        friendLayout.addView(editButton);

        return friendLayout;
    }

    private View createEmptyMessage() {
        TextView emptyText = new TextView(this);
        emptyText.setText("No friends added yet.\nTap + to add friends.");
        emptyText.setTextSize(14);
        emptyText.setTextColor(Color.parseColor("#888888"));
        emptyText.setGravity(android.view.Gravity.CENTER);
        emptyText.setPadding(dpToPx(16), dpToPx(32), dpToPx(16), dpToPx(32));
        return emptyText;
    }

    private void showAddFriendDialog() {
        showFriendDialog(null, false);
    }

    private void showEditFriendDialog(FriendModel friend) {
        showFriendDialog(friend, true);
    }

    private void showEditUserDialog() {
        showUserEditDialog();
    }

    private void showFriendDialog(FriendModel friend, boolean isEdit) {
        try {
            Dialog dialog = new Dialog(this);
            dialog.setContentView(R.layout.dialog_add_friends);

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
            EditText editID = dialog.findViewById(R.id.editID);
            EditText editName = dialog.findViewById(R.id.editName);
            EditText editEncryptionKey = dialog.findViewById(R.id.editEncryptionKey);
            ImageView profilePic = dialog.findViewById(R.id.profilePicRound);
            Button btnCancel = dialog.findViewById(R.id.btnCancel);
            Button btnAdd = dialog.findViewById(R.id.btnAdd);
            Button btnDelete = dialog.findViewById(R.id.btnDelete);

            // Set dialog content based on mode
            if (isEdit && friend != null) {
                title.setText("Edit Friend");
                editID.setText(friend.getId());
                editID.setEnabled(false); // Don't allow ID editing
                editName.setText(friend.getName());
                editEncryptionKey.setText(friend.getEncryptionKey());
                btnAdd.setText("Save");
                btnDelete.setVisibility(View.VISIBLE);
                loadProfilePicture(friend.getId(), profilePic);
                currentFriendId = friend.getId();
            } else {
                title.setText("Add New Friend");
                btnAdd.setText("Add");
                btnDelete.setVisibility(View.GONE);
                currentFriendId = null;
                profilePic.setImageResource(R.drawable.profile_pic_round_vector);
            }

            currentProfilePic = profilePic;

            // Profile pic click listener
            profilePic.setOnClickListener(v -> {
                String friendId = isEdit ? friend.getId() : editID.getText().toString().trim();
                if (!friendId.isEmpty()) {
                    currentFriendId = friendId;
                    showImagePickerDialog(friendId, profilePic);
                } else {
                    Toast.makeText(this, "Enter Friend ID first", Toast.LENGTH_SHORT).show();
                }
            });

            // Button listeners
            btnCancel.setOnClickListener(v -> dialog.dismiss());

            btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Delete Friend")
                        .setMessage("Are you sure you want to delete this friend?")
                        .setPositiveButton("Delete", (d, which) -> {
                            friendsList.remove(friend);
                            saveFriendsData();
                            displayFriends();
                            Toast.makeText(this, "Friend deleted", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });

            btnAdd.setOnClickListener(v -> {
                String friendId = editID.getText().toString().trim();
                String name = editName.getText().toString().trim();
                String encryptionKey = editEncryptionKey.getText().toString().trim();

                if (friendId.isEmpty()) {
                    Toast.makeText(this, "Please enter friend ID", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (friendId.equals(currentUserId)) {
                    Toast.makeText(this, "Cannot add yourself as friend", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (isEdit && friend != null) {
                    // Update existing friend
                    friend.setName(name);
                    friend.setEncryptionKey(encryptionKey);
                } else {
                    // Check if friend already exists
                    for (FriendModel existingFriend : friendsList) {
                        if (existingFriend.getId().equals(friendId)) {
                            Toast.makeText(this, "Friend already exists", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }

                    // Add new friend
                    FriendModel newFriend = new FriendModel(friendId, name, encryptionKey);
                    friendsList.add(newFriend);
                }

                saveFriendsData();
                displayFriends();

                Toast.makeText(this, isEdit ? "Friend updated" : "Friend added", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });

            dialog.show();

        } catch (Exception e) {
            Log.e(TAG, "Error showing friend dialog", e);
            Toast.makeText(this, "Error opening dialog", Toast.LENGTH_SHORT).show();
        }
    }

    private void showUserEditDialog() {
        try {
            Dialog dialog = new Dialog(this);
            dialog.setContentView(R.layout.dialog_edit_name);

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
            EditText editName = dialog.findViewById(R.id.editName);
            EditText editEncryptionKey = dialog.findViewById(R.id.editEncryptionKey);
            TextView textID = dialog.findViewById(R.id.textID);
            ImageView profilePic = dialog.findViewById(R.id.profilePicRound);
            Button btnCancel = dialog.findViewById(R.id.btnCancel);
            Button btnSave = dialog.findViewById(R.id.btnSave);

            // Set current values
            textID.setText(currentUserId);
            editName.setText(getDisplayName(currentUserId));
            editEncryptionKey.setText(getEncryptionKey(currentUserId));
            loadProfilePicture(currentUserId, profilePic);

            // Profile pic click listener
            profilePic.setOnClickListener(v -> showImagePickerDialog(currentUserId, profilePic));

            // Button listeners
            btnCancel.setOnClickListener(v -> dialog.dismiss());

            btnSave.setOnClickListener(v -> {
                String newName = editName.getText().toString().trim();
                String newKey = editEncryptionKey.getText().toString().trim();

                saveDisplayName(currentUserId, newName);
                saveEncryptionKey(currentUserId, newKey);
                displayFriends();

                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });

            dialog.show();

        } catch (Exception e) {
            Log.e(TAG, "Error showing user edit dialog", e);
        }
    }

    private void showImagePickerDialog(String friendId, ImageView profilePic) {
        if (needsStoragePermission()) {
            currentFriendId = friendId;
            currentProfilePic = profilePic;
            requestStoragePermission();
            return;
        }
        showImagePickerDialogInternal(friendId, profilePic);
    }

    private void showImagePickerDialogInternal(String friendId, ImageView profilePic) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Image")
                .setItems(new String[]{"Gallery", "Camera"}, (dialog, which) -> {
                    if (which == 0) {
                        openGallery(friendId, profilePic);
                    } else {
                        openCamera(friendId, profilePic);
                    }
                })
                .show();
    }

    private void openGallery(String friendId, ImageView profilePic) {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        currentFriendId = friendId;
        currentProfilePic = profilePic;
        startActivityForResult(intent, REQUEST_GALLERY);
    }

    private void openCamera(String friendId, ImageView profilePic) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            currentFriendId = friendId;
            currentProfilePic = profilePic;
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
            return;
        }
        openCameraInternal(friendId, profilePic);
    }

    private void openCameraInternal(String friendId, ImageView profilePic) {
        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (intent.resolveActivity(getPackageManager()) != null) {
                currentFriendId = friendId;
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
                if (currentFriendId != null && currentProfilePic != null) {
                    showImagePickerDialogInternal(currentFriendId, currentProfilePic);
                }
            } else {
                Toast.makeText(this, "Storage permission required for gallery access", Toast.LENGTH_SHORT).show();
                if (currentFriendId != null && currentProfilePic != null) {
                    openCamera(currentFriendId, currentProfilePic);
                }
            }
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (currentFriendId != null && currentProfilePic != null) {
                    openCameraInternal(currentFriendId, currentProfilePic);
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
                if (data != null && data.getData() != null && currentProfilePic != null && currentFriendId != null) {
                    Uri imageUri = data.getData();
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                    Bitmap resizedBitmap = ImageConverter.resizeAndCrop(bitmap, 94, 94);
                    Bitmap circularBitmap = ImageConverter.createCircularBitmap(resizedBitmap);
                    currentProfilePic.setImageBitmap(circularBitmap);
                    saveProfilePicture(currentFriendId, resizedBitmap);
                    displayFriends(); // Refresh the display
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
                    if (bitmap != null && currentProfilePic != null && currentFriendId != null) {
                        Bitmap resizedBitmap = ImageConverter.resizeAndCrop(bitmap, 94, 94);
                        Bitmap circularBitmap = ImageConverter.createCircularBitmap(resizedBitmap);
                        currentProfilePic.setImageBitmap(circularBitmap);
                        saveProfilePicture(currentFriendId, resizedBitmap);
                        displayFriends(); // Refresh the display
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

    // Helper methods
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

    private String getDisplayName(String userId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_NAME_MAP, null);
        if (json != null) {
            try {
                Type type = new TypeToken<java.util.Map<String, String>>(){}.getType();
                java.util.Map<String, String> nameMap = gson.fromJson(json, type);
                String name = nameMap.get(userId);
                return (name != null && !name.isEmpty()) ? name : userId;
            } catch (Exception e) {
                Log.e(TAG, "Error getting display name", e);
            }
        }
        return userId;
    }

    private void saveDisplayName(String userId, String name) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_NAME_MAP, "{}");

        try {
            Type type = new TypeToken<java.util.Map<String, String>>(){}.getType();
            java.util.Map<String, String> nameMap = gson.fromJson(json, type);
            if (nameMap == null) nameMap = new java.util.HashMap<>();

            if (name.isEmpty()) {
                nameMap.remove(userId);
            } else {
                nameMap.put(userId, name);
            }

            prefs.edit().putString(KEY_NAME_MAP, gson.toJson(nameMap)).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving display name", e);
        }
    }

    private String getEncryptionKey(String userId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString("encryption_" + userId, "");
    }

    private void saveEncryptionKey(String userId, String key) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString("encryption_" + userId, key).apply();
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
                Log.e(TAG, "Error loading profile picture for " + userId, e);
                imageView.setImageResource(R.drawable.profile_pic_round_vector);
            }
        } else {
            imageView.setImageResource(R.drawable.profile_pic_round_vector);
        }
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

    private void saveFriendsData() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString(KEY_FRIENDS_LIST, gson.toJson(friendsList)).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving friends data", e);
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // FriendModel class
    private static class FriendModel {
        private String id;
        private String name;
        private String encryptionKey;

        public FriendModel(String id, String name, String encryptionKey) {
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