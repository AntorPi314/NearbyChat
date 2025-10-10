package com.antor.nearbychat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;

public class ImageCacheManager {
    private static final String TAG = "ImageCacheManager";
    private static ImageCacheManager instance;

    private final LruCache<String, Bitmap> memoryCache;
    private final File diskCacheDir;
    private final File thumbnailCacheDir;

    private ImageCacheManager(Context context) {
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;

        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        diskCacheDir = new File(context.getCacheDir(), "image_cache");
        thumbnailCacheDir = new File(context.getCacheDir(), "thumbnail_cache");

        if (!diskCacheDir.exists()) diskCacheDir.mkdirs();
        if (!thumbnailCacheDir.exists()) thumbnailCacheDir.mkdirs();
    }

    public static synchronized ImageCacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new ImageCacheManager(context.getApplicationContext());
        }
        return instance;
    }
    public Bitmap getBitmap(String url, boolean isThumbnail) {
        String key = getCacheKey(url, isThumbnail);

        Bitmap memBitmap = memoryCache.get(key);
        if (memBitmap != null) {
            return memBitmap;
        }
        Bitmap diskBitmap = loadFromDisk(key, isThumbnail);
        if (diskBitmap != null) {
            memoryCache.put(key, diskBitmap);
            return diskBitmap;
        }
        return null;
    }

    public void putBitmap(String url, Bitmap bitmap, boolean isThumbnail) {
        if (bitmap == null) return;

        String key = getCacheKey(url, isThumbnail);

        memoryCache.put(key, bitmap);

        new Thread(() -> saveToDisk(key, bitmap, isThumbnail)).start();
    }

    private void saveToDisk(String key, Bitmap bitmap, boolean isThumbnail) {
        try {
            File cacheDir = isThumbnail ? thumbnailCacheDir : diskCacheDir;
            File file = new File(cacheDir, key);

            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, isThumbnail ? 85 : 95, fos);
            fos.flush();
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Error saving to disk: " + e.getMessage());
        }
    }

    private Bitmap loadFromDisk(String key, boolean isThumbnail) {
        try {
            File cacheDir = isThumbnail ? thumbnailCacheDir : diskCacheDir;
            File file = new File(cacheDir, key);

            if (!file.exists()) return null;

            FileInputStream fis = new FileInputStream(file);
            Bitmap bitmap = BitmapFactory.decodeStream(fis);
            fis.close();

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error loading from disk: " + e.getMessage());
            return null;
        }
    }

    private String getCacheKey(String url, boolean isThumbnail) {
        try {
            String input = url + (isThumbnail ? "_thumb" : "_full");
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());

            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(url.hashCode()) + (isThumbnail ? "_thumb" : "_full");
        }
    }

    public void clearCache() {
        memoryCache.evictAll();
        deleteDirectory(diskCacheDir);
        deleteDirectory(thumbnailCacheDir);
        diskCacheDir.mkdirs();
        thumbnailCacheDir.mkdirs();
    }

    private void deleteDirectory(File dir) {
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }
}