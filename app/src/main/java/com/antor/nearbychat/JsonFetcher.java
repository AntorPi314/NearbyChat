package com.antor.nearbychat;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import android.util.LruCache;

public class JsonFetcher {
    private static final String TAG = "JsonFetcher";

    // In-memory cache (fastest)
    private static final LruCache<String, ParsedJson> jsonCache = new LruCache<>(20);

    public interface JsonCallback {
        void onSuccess(ParsedJson parsed);
        void onError(String error);
    }

    public static class ParsedJson {
        public String message = "";
        public String images = "";
        public String videos = "";
    }

    public static boolean isJsonUrl(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String trimmed = message.trim();

        if (!trimmed.startsWith("g//")) {
            return false;
        }
        String urlPart = trimmed.substring(3);

        if (urlPart.isEmpty() || !urlPart.contains("/")) {
            return false;
        }

        if (urlPart.contains(" ")) {
            return false;
        }
        return true;
    }

    // MD5 helper to create safe filenames
    private static String md5(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(s.getBytes());
            byte[] messageDigest = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String hex = Integer.toHexString(0xFF & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(s.hashCode());
        }
    }

    // Gets the cache file for a given URL
    private static File getCacheFile(Context context, String urlPart) {
        String fileName = md5(urlPart);
        return new File(context.getCacheDir(), "json_cache_" + fileName);
    }

    // Reads the raw JSON string from the disk cache
    private static String readFromDiskCache(File cacheFile) {
        if (!cacheFile.exists()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(cacheFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            Log.d(TAG, "💾 JSON loaded from DISK cache: " + cacheFile.getName());
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error reading disk cache", e);
            return null;
        }
    }

    // Saves the raw JSON string to the disk cache
    private static void saveToDiskCache(File cacheFile, String jsonString) {
        try (FileOutputStream fos = new FileOutputStream(cacheFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos)) {
            writer.write(jsonString);
            Log.d(TAG, "💾 JSON saved to DISK cache: " + cacheFile.getName());
        } catch (Exception e) {
            Log.e(TAG, "Error writing disk cache", e);
        }
    }

    // Updated fetchJson method
    public static void fetchJson(Context context, String gUrl, JsonCallback callback) {
        String urlPart;
        try {
            urlPart = gUrl.trim().substring(3);
        } catch (Exception e) {
            callback.onError("Invalid g// URL");
            return;
        }

        // 1. Check in-memory cache (fastest)
        ParsedJson cachedData = jsonCache.get(urlPart);
        if (cachedData != null) {
            Log.d(TAG, "✅ JSON loaded from MEMORY cache: " + urlPart);
            callback.onSuccess(cachedData);
            return;
        }

        // 2. Check disk cache
        File cacheFile = getCacheFile(context.getApplicationContext(), urlPart);
        String diskJson = readFromDiskCache(cacheFile);
        if (diskJson != null) {
            ParsedJson parsed = parseJsonResponse(diskJson);
            jsonCache.put(urlPart, parsed); // Put in memory for next time
            callback.onSuccess(parsed);
            return;
        }

        // 3. Not in any cache, go to network
        Log.d(TAG, "⚠️ Cache miss, going to NETWORK: " + urlPart);
        new Thread(() -> {
            HttpURLConnection connection = null;
            BufferedReader reader = null;
            String jsonString = null; // Store the raw JSON string

            try {
                String fullUrl = "https://" + urlPart;
                Log.d(TAG, "🌐 Attempting HTTPS: " + fullUrl);

                URL url = new URL(fullUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(true);

                int responseCode = connection.getResponseCode();

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "❌ HTTPS failed (" + responseCode + "), trying HTTP");
                    connection.disconnect();

                    fullUrl = "http://" + urlPart;
                    Log.d(TAG, "🌐 Attempting HTTP: " + fullUrl);

                    url = new URL(fullUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    connection.setInstanceFollowRedirects(true);

                    responseCode = connection.getResponseCode();
                }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream())
                    );
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    jsonString = response.toString(); // Save raw string
                    Log.d(TAG, "✅ JSON received: " + jsonString.substring(0, Math.min(100, jsonString.length())));

                    ParsedJson parsed = parseJsonResponse(jsonString);

                    // Save to both caches
                    saveToDiskCache(cacheFile, jsonString);
                    jsonCache.put(urlPart, parsed);

                    callback.onSuccess(parsed);
                } else {
                    Log.e(TAG, "❌ HTTP error: " + responseCode);
                    callback.onError("HTTP error: " + responseCode);
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Error fetching JSON", e);
                callback.onError("Failed to fetch: " + e.getMessage());
            } finally {
                try {
                    if (reader != null) reader.close();
                    if (connection != null) connection.disconnect();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing connection", e);
                }
            }
        }).start();
    }

    private static ParsedJson parseJsonResponse(String jsonString) {
        ParsedJson result = new ParsedJson();

        try {
            JSONObject json = new JSONObject(jsonString);

            // 1. Check for Message ( "message" or "text" )
            if (json.has("message")) {
                result.message = json.getString("message");
            } else if (json.has("text")) {
                result.message = json.getString("text");
            }

            // 2. Check for Images ( "images" or "image_urls" )
            if (json.has("images")) {
                JSONArray imagesArray = json.getJSONArray("images");
                result.images = jsonArrayToString(imagesArray);
            } else if (json.has("image_urls")) {
                JSONArray imagesArray = json.getJSONArray("image_urls");
                result.images = jsonArrayToString(imagesArray);
            }

            // 3. Check for Videos ( "videos" or "video_urls" )
            if (json.has("videos")) {
                JSONArray videosArray = json.getJSONArray("videos");
                result.videos = jsonArrayToString(videosArray);
            } else if (json.has("video_urls")) {
                JSONArray videosArray = json.getJSONArray("video_urls");
                result.videos = jsonArrayToString(videosArray);
            }

            Log.d(TAG, "📦 Parsed - Message: " + result.message +
                    ", Images: " + result.images +
                    ", Videos: " + result.videos);

        } catch (Exception e) {
            Log.e(TAG, "❌ Error parsing JSON", e);
        }
        return result;
    }

    private static String jsonArrayToString(JSONArray jsonArray) throws org.json.JSONException {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < jsonArray.length(); i++) {
            if (i > 0) stringBuilder.append(",");
            stringBuilder.append(jsonArray.getString(i));
        }
        return stringBuilder.toString();
    }
}