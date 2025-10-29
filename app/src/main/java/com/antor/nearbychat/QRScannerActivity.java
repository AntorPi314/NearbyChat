package com.antor.nearbychat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class QRScannerActivity extends Activity {

    private static final int CAMERA_PERMISSION_REQUEST = 301;
    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_GROUPS_LIST = "groupsList";
    private static final String KEY_FRIENDS_LIST = "friendsList";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        } else {
            startScanner();
        }
    }

    private void startScanner() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Scan QR Code");
        integrator.setCameraId(0);
        integrator.setBeepEnabled(true);
        integrator.setBarcodeImageEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() == null) {
                Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                processQRCode(result.getContents());
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void processQRCode(String qrContent) {
        try {
            String encryptedData;
            boolean isFriend = qrContent.startsWith("FRIEND:");
            boolean isGroup = qrContent.startsWith("GROUP:");

            if (!isFriend && !isGroup) {
                Toast.makeText(this, "Invalid QR code format", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            encryptedData = isFriend ? qrContent.substring(7) : qrContent.substring(6);
            String decryptedData = QREncryption.decrypt(encryptedData);

            if (decryptedData == null) {
                Toast.makeText(this, "Invalid or tampered QR code", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            String[] parts = decryptedData.split("\\|", -1);

            if (isFriend) {
                if (parts.length >= 2) {
                    addFriend(parts[0], parts[1], parts.length > 2 ? parts[2] : "");
                } else {
                    Toast.makeText(this, "Invalid Friend QR data", Toast.LENGTH_SHORT).show();
                }
            } else {
                if (parts.length >= 2) {
                    String groupId = parts[0];
                    String groupName = parts[1];
                    String encryptionKey = parts.length > 2 ? parts[2] : "";
                    addGroup(groupId, groupName, encryptionKey);
                } else {
                    Toast.makeText(this, "Invalid Group QR data", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error processing QR code", Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
        finish();
    }

    private void addFriend(String displayId, String name, String encryptionKey) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Gson gson = new Gson();

        String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
        Type type = new TypeToken<ArrayList<FriendModel>>() {
        }.getType();
        List<FriendModel> friends = friendsJson != null ? gson.fromJson(friendsJson, type) : new ArrayList<>();

        if (friends == null) friends = new ArrayList<>();

        boolean exists = false;
        for (FriendModel f : friends) {
            if (f.getDisplayId().equals(displayId)) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            friends.add(new FriendModel(displayId, name, encryptionKey));
            prefs.edit().putString(KEY_FRIENDS_LIST, gson.toJson(friends)).apply();
            DataCache.invalidate();
            Toast.makeText(this, "Friend added: " + name, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Friend already exists", Toast.LENGTH_SHORT).show();
        }
    }

    private void addGroup(String groupDisplayId, String name, String encryptionKey) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Gson gson = new Gson();

        long bits = com.antor.nearbychat.Message.MessageHelper.displayIdToTimestamp(groupDisplayId);
        String groupId = com.antor.nearbychat.Message.MessageHelper.timestampToAsciiId(bits);

        String groupsJson = prefs.getString(KEY_GROUPS_LIST, null);
        Type type = new TypeToken<ArrayList<GroupModel>>() {
        }.getType();
        List<GroupModel> groups = groupsJson != null ? gson.fromJson(groupsJson, type) : new ArrayList<>();

        if (groups == null) groups = new ArrayList<>();

        boolean exists = false;
        for (GroupModel g : groups) {
            if (g.getId().equals(groupId)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            groups.add(new GroupModel(groupId, name, encryptionKey));
            prefs.edit().putString(KEY_GROUPS_LIST, gson.toJson(groups)).apply();
            DataCache.invalidate();
            Toast.makeText(this, "Group added: " + name, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Group already exists", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScanner();
            } else {
                Toast.makeText(this, "Camera permission required to scan QR codes", Toast.LENGTH_SHORT).show();
                finish();
            }
        }

    }
}