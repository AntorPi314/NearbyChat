package com.antor.nearbychat.Message;

import android.content.Context;
import android.content.SharedPreferences;

import com.antor.nearbychat.FriendModel;
import com.antor.nearbychat.GroupModel;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MessageHelper {
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray();
    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_GROUPS_LIST = "groupsList";
    private static final String KEY_FRIENDS_LIST = "friendsList";

    public static String generateAsciiId() {
        return timestampToAsciiId(System.currentTimeMillis());
    }

    public static String timestampToAsciiId(long timestamp) {
        long bits40 = timestamp & ((1L << 40) - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 4; i >= 0; i--) {
            int byteValue = (int) ((bits40 >> (i * 8)) & 0xFF);
            sb.append((char) byteValue);
        }
        return sb.toString();
    }

    public static long asciiIdToTimestamp(String asciiId) {
        if (asciiId == null || asciiId.length() != 5) return 0;
        long bits40 = 0;
        for (int i = 0; i < 5; i++) {
            bits40 = (bits40 << 8) | (asciiId.charAt(i) & 0xFF);
        }
        return bits40;
    }

    public static String timestampToDisplayId(long timestamp) {
        long bits40 = timestamp & ((1L << 40) - 1);
        StringBuilder sb = new StringBuilder();
        long temp = bits40;
        for (int i = 0; i < 8; i++) {
            int index = (int) (temp & 0b11111);
            sb.append(ALPHABET[index]);
            temp >>= 5;
        }
        return sb.reverse().toString();
    }

    public static long displayIdToTimestamp(String displayId) {
        if (displayId == null || displayId.length() != 8) return 0;
        long bits40 = 0;
        for (int i = 0; i < 8; i++) {
            char c = displayId.charAt(i);
            int index = -1;
            for (int j = 0; j < ALPHABET.length; j++) {
                if (ALPHABET[j] == c) {
                    index = j;
                    break;
                }
            }
            if (index == -1) index = 0;
            bits40 = (bits40 << 5) | index;
        }
        return bits40;
    }

    public static long reconstructFullTimestamp(long timestampBits40) {
        long currentMs = System.currentTimeMillis();
        long currentHigh = currentMs & ~((1L << 40) - 1);
        long reconstructed = currentHigh | timestampBits40;
        if (reconstructed > currentMs + 1000) {
            return reconstructed - (1L << 40);
        }
        return reconstructed;
    }

    public static String formatTimestamp(long timestampMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a | dd-MM-yyyy", Locale.getDefault());
        return sdf.format(new Date(timestampMs));
    }

    public static String getPasswordForChat(Context context, String chatType, String chatId, String myUserId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Gson gson = new Gson();

        if ("G".equals(chatType)) {
            String groupsJson = prefs.getString(KEY_GROUPS_LIST, null);
            if (groupsJson != null) {
                Type type = new TypeToken<List<GroupModel>>() {}.getType();
                List<GroupModel> groups = gson.fromJson(groupsJson, type);
                for (GroupModel g : groups) {
                    if (g.getId().equals(chatId)) {
                        return g.getEncryptionKey().isEmpty() ? g.getId() : g.getEncryptionKey();
                    }
                }
            }
            return chatId;
        } else if ("F".equals(chatType)) {
            String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
            if (friendsJson != null) {
                Type type = new TypeToken<List<FriendModel>>() {}.getType();
                List<FriendModel> friends = gson.fromJson(friendsJson, type);
                String friendDisplayId = timestampToDisplayId(asciiIdToTimestamp(chatId));

                for (FriendModel f : friends) {
                    if (f.getDisplayId().equals(friendDisplayId)) {
                        return f.getEncryptionKey().isEmpty() ? myUserId : f.getEncryptionKey();
                    }
                }
            }
            return myUserId;
        }
        return "";
    }
}