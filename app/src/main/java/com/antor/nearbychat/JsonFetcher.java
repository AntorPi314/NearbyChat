package com.antor.nearbychat;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class JsonFetcher {
    private static final String TAG = "JsonFetcher";

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

    public static void fetchJson(String gUrl, JsonCallback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            BufferedReader reader = null;

            try {
                String urlPart = gUrl.trim().substring(3);

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
                    String jsonString = response.toString();
                    Log.d(TAG, "✅ JSON received: " + jsonString.substring(0, Math.min(100, jsonString.length())));

                    ParsedJson parsed = parseJsonResponse(jsonString);
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
            if (json.has("message")) {
                result.message = json.getString("message");
            }

            if (json.has("images")) {
                JSONArray imagesArray = json.getJSONArray("images");
                StringBuilder imageUrls = new StringBuilder();

                for (int i = 0; i < imagesArray.length(); i++) {
                    if (i > 0) imageUrls.append(",");
                    imageUrls.append(imagesArray.getString(i));
                }
                result.images = imageUrls.toString();
            }
            if (json.has("videos")) {
                JSONArray videosArray = json.getJSONArray("videos");
                StringBuilder videoUrls = new StringBuilder();

                for (int i = 0; i < videosArray.length(); i++) {
                    if (i > 0) videoUrls.append(",");
                    videoUrls.append(videosArray.getString(i));
                }
                result.videos = videoUrls.toString();
            }

            Log.d(TAG, "📦 Parsed - Message: " + result.message +
                    ", Images: " + result.images +
                    ", Videos: " + result.videos);

        } catch (Exception e) {
            Log.e(TAG, "❌ Error parsing JSON", e);
        }
        return result;
    }
}