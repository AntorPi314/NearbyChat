package com.antor.nearbychat.Message;

import android.content.Context;
import android.content.SharedPreferences;

import com.antor.nearbychat.FriendModel;
import com.antor.nearbychat.GroupModel;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Utility class for message-related helper functions.
 * Handles ID generation, timestamp conversion, and password retrieval.
 */
public class MessageHelper {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray();
    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_GROUPS_LIST = "groupsList";
    private static final String KEY_FRIENDS_LIST = "friendsList";

    /**
     * Generates a 5-character ASCII ID from the current time.
     */
    public static String generateAsciiId() {
        return timestampToAsciiId(System.currentTimeMillis());
    }

    /**
     * Converts the lower 40 bits of a timestamp into a 5-character ASCII string.
     */
    public static String timestampToAsciiId(long timestamp) {
        long bits40 = timestamp & ((1L << 40) - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 4; i >= 0; i--) {
            int byteValue = (int) ((bits40 >> (i * 8)) & 0xFF);
            sb.append((char) byteValue);
        }
        return sb.toString();
    }

    /**
     * Converts a 5-character ASCII ID back into the lower 40 bits of a timestamp.
     */
    public static long asciiIdToTimestamp(String asciiId) {
        if (asciiId == null || asciiId.length() != 5) return 0;
        long bits40 = 0;
        for (int i = 0; i < 5; i++) {
            bits40 = (bits40 << 8) | (asciiId.charAt(i) & 0xFF);
        }
        return bits40;
    }

    /**
     * Converts the lower 40 bits of a timestamp into a user-friendly 8-character display ID.
     */
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

    /**
     * Converts an 8-character display ID back into the lower 40 bits of a timestamp.
     */
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

    /**
     * Reconstructs the full millisecond timestamp from the lower 40 bits.
     * It assumes the message was sent recently.
     */
    public static long reconstructFullTimestamp(long timestampBits40) {
        long currentMs = System.currentTimeMillis();
        long currentHigh = currentMs & ~((1L << 40) - 1);
        long reconstructed = currentHigh | timestampBits40;
        // If the reconstructed time is in the future, assume it's from the previous 40-bit cycle
        if (reconstructed > currentMs + 1000) {
            return reconstructed - (1L << 40);
        }
        return reconstructed;
    }

    /**
     * Formats a millisecond timestamp into a human-readable string.
     */
    public static String formatTimestamp(long timestampMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a | dd-MM-yyyy", Locale.getDefault());
        return sdf.format(new Date(timestampMs));
    }

    /**
     * Retrieves the correct encryption password for a given chat type and ID.
     *
     * @param context   The application context to access SharedPreferences.
     * @param chatType  "N", "G", or "F".
     * @param chatId    The 5-char ASCII ID of the group or friend.
     * @param myUserId  The current user's 8-char display ID (used for friend chat key fallback).
     * @return The encryption password, or an empty string for Nearby chat.
     */
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
            return chatId; // Fallback to groupId
        } else if ("F".equals(chatType)) {
            String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
            if (friendsJson != null) {
                Type type = new TypeToken<List<FriendModel>>() {}.getType();
                List<FriendModel> friends = gson.fromJson(friendsJson, type);
                String friendDisplayId = timestampToDisplayId(asciiIdToTimestamp(chatId));

                for (FriendModel f : friends) {
                    if (f.getDisplayId().equals(friendDisplayId)) {
                        // If key is empty, the password is the SENDER's ID (your own ID)
                        return f.getEncryptionKey().isEmpty() ? myUserId : f.getEncryptionKey();
                    }
                }
            }
            // Fallback for friends is the sender's own ID
            return myUserId;
        }
        return ""; // No password for Nearby chat
    }
}