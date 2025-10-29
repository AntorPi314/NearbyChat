package com.antor.nearbychat;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class DataCache {
    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final String KEY_GROUPS_LIST = "groupsList";
    private static final String KEY_FRIENDS_LIST = "friendsList";

    private static List<GroupModel> cachedGroups;
    private static List<FriendModel> cachedFriends;
    private static boolean loaded = false;
    private static final Gson gson = new Gson();

    public static List<GroupModel> getGroups(Context context) {
        if (!loaded || cachedGroups == null) {
            loadData(context);
        }
        return new ArrayList<>(cachedGroups);
    }

    public static List<FriendModel> getFriends(Context context) {
        if (!loaded || cachedFriends == null) {
            loadData(context);
        }
        return new ArrayList<>(cachedFriends);
    }

    public static void saveGroups(Context context, List<GroupModel> groups) {
        cachedGroups = new ArrayList<>(groups);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_GROUPS_LIST, gson.toJson(groups)).apply();
    }

    public static void saveFriends(Context context, List<FriendModel> friends) {
        cachedFriends = new ArrayList<>(friends);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_FRIENDS_LIST, gson.toJson(friends)).apply();
    }

    private static void loadData(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String groupsJson = prefs.getString(KEY_GROUPS_LIST, null);
        if (groupsJson != null) {
            Type type = new TypeToken<ArrayList<GroupModel>>(){}.getType();
            cachedGroups = gson.fromJson(groupsJson, type);
        } else {
            cachedGroups = new ArrayList<>();
        }

        String friendsJson = prefs.getString(KEY_FRIENDS_LIST, null);
        if (friendsJson != null) {
            Type type = new TypeToken<ArrayList<FriendModel>>(){}.getType();
            cachedFriends = gson.fromJson(friendsJson, type);
        } else {
            cachedFriends = new ArrayList<>();
        }

        loaded = true;
    }

    public static void invalidate() {
        loaded = false;
    }
}