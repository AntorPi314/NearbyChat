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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ImageCacheManager {
    private static final String TAG = "ImageCacheManager";
    private static ImageCacheManager instance;

    private static final long MAX_CACHE_SIZE_BYTES = 200 * 1024 * 1024; // 200 MB
    private static final long TARGET_CACHE_SIZE_BYTES = 120 * 1024 * 1024; // 120 MB (after cleanup)

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
            // ✅ NEW: Check and evict old files if needed
            evictIfNeeded();

            File cacheDir = isThumbnail ? thumbnailCacheDir : diskCacheDir;
            File file = new File(cacheDir, key);

            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, isThumbnail ? 85 : 95, fos);
            fos.flush();
            fos.close();

            Log.d(TAG, "Saved to disk: " + key + " (size: " + file.length() / 1024 + " KB)");
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

    private long calculateCacheSize() {
        long totalSize = 0;

        try {
            // Calculate disk cache size
            if (diskCacheDir.exists()) {
                File[] files = diskCacheDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        totalSize += file.length();
                    }
                }
            }

            // Calculate thumbnail cache size
            if (thumbnailCacheDir.exists()) {
                File[] files = thumbnailCacheDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        totalSize += file.length();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calculating cache size", e);
        }

        return totalSize;
    }

    private List<File> getSortedCacheFiles() {
        List<File> allFiles = new ArrayList<>();

        try {
            // Collect disk cache files
            if (diskCacheDir.exists()) {
                File[] files = diskCacheDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        allFiles.add(file);
                    }
                }
            }

            // Collect thumbnail cache files
            if (thumbnailCacheDir.exists()) {
                File[] files = thumbnailCacheDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        allFiles.add(file);
                    }
                }
            }

            // Sort by last modified (oldest first)
            Collections.sort(allFiles, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.compare(f1.lastModified(), f2.lastModified());
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error sorting cache files", e);
        }

        return allFiles;
    }

    private void evictIfNeeded() {
        try {
            long totalSize = calculateCacheSize();

            if (totalSize <= MAX_CACHE_SIZE_BYTES) {
                return; // Cache size is OK
            }

            Log.d(TAG, "Cache size exceeded: " + (totalSize / 1024 / 1024) + " MB. Starting smart eviction...");

            List<CacheEntry> entries = getSmartSortedCacheFiles();
            int deletedCount = 0;

            // Delete least valuable files until we reach target size
            for (CacheEntry entry : entries) {
                if (totalSize <= TARGET_CACHE_SIZE_BYTES) {
                    break;
                }

                long fileSize = entry.file.length();
                if (entry.file.delete()) {
                    totalSize -= fileSize;
                    deletedCount++;
                }
            }

            Log.d(TAG, "Smart eviction complete. Deleted " + deletedCount + " files. New size: " + (totalSize / 1024 / 1024) + " MB");

        } catch (Exception e) {
            Log.e(TAG, "Error during cache eviction", e);
        }
    }

    // ✅ NEW helper class
    private static class CacheEntry {
        File file;
        long score; // Lower score = delete first

        CacheEntry(File file, long lastModified, int accessCount) {
            this.file = file;
            // Score combines recency + frequency
            // More recent = higher score, more accessed = higher score
            this.score = lastModified + (accessCount * 3600000L); // 1 access = 1 hour bonus
        }
    }

    // ✅ NEW method
    private List<CacheEntry> getSmartSortedCacheFiles() {
        List<File> allFiles = new ArrayList<>();
        Map<String, Integer> accessCounts = new HashMap<>(); // You can track this in SharedPreferences

        // Collect files
        if (diskCacheDir.exists()) {
            File[] files = diskCacheDir.listFiles();
            if (files != null) {
                Collections.addAll(allFiles, files);
            }
        }

        if (thumbnailCacheDir.exists()) {
            File[] files = thumbnailCacheDir.listFiles();
            if (files != null) {
                Collections.addAll(allFiles, files);
            }
        }

        // Create scored entries
        List<CacheEntry> entries = new ArrayList<>();
        for (File file : allFiles) {
            int accessCount = accessCounts.getOrDefault(file.getName(), 0);
            entries.add(new CacheEntry(file, file.lastModified(), accessCount));
        }

        // Sort by score (lowest first = delete first)
        Collections.sort(entries, (e1, e2) -> Long.compare(e1.score, e2.score));

        return entries;
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