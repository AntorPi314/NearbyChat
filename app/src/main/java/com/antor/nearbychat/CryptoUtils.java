package com.antor.nearbychat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

public class CryptoUtils {

    private static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;

    private static final Map<String, CacheEntry> keyCache = Collections.synchronizedMap(new WeakHashMap<>());

    public static String encrypt(String textToEncrypt, String password) {
        if (password == null || password.isEmpty()) {
            return textToEncrypt;
        }
        try {
            byte[] key = getCachedKey(password);
            byte[] textBytes = textToEncrypt.getBytes(ISO_8859_1);
            byte[] result = new byte[textBytes.length];

            int keyIndex = 0;
            byte prevByte = 0;

            for (int i = 0; i < textBytes.length; i++) {
                byte mixedKey = (byte) (key[keyIndex] ^ prevByte);
                result[i] = (byte) (textBytes[i] ^ mixedKey);
                prevByte = result[i];
                keyIndex = (keyIndex + 1 + (i % 3)) % key.length;
            }
            return new String(result, ISO_8859_1);
        } catch (Exception e) {
            e.printStackTrace();
            return textToEncrypt;
        }
    }

    public static String decrypt(String textToDecrypt, String password) {
        if (password == null || password.isEmpty()) {
            return textToDecrypt;
        }
        try {
            byte[] key = getCachedKey(password); // ✅ CHANGED: Use cached key
            byte[] textBytes = textToDecrypt.getBytes(ISO_8859_1);
            byte[] result = new byte[textBytes.length];

            int keyIndex = 0;
            byte prevByte = 0;

            for (int i = 0; i < textBytes.length; i++) {
                byte mixedKey = (byte) (key[keyIndex] ^ prevByte);
                result[i] = (byte) (textBytes[i] ^ mixedKey);
                prevByte = textBytes[i];
                keyIndex = (keyIndex + 1 + (i % 3)) % key.length;
            }
            return new String(result, ISO_8859_1);
        } catch (Exception e) {
            e.printStackTrace();
            return textToDecrypt;
        }
    }

    private static class CacheEntry {
        byte[] key;
        long expiry; // milliseconds

        CacheEntry(byte[] key) {
            this.key = key;
            this.expiry = System.currentTimeMillis() + (30 * 60 * 1000); // 30 minutes
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiry;
        }
    }

    private static byte[] getCachedKey(String password) throws Exception {
        CacheEntry entry = keyCache.get(password);

        if (entry != null && !entry.isExpired()) {
            return entry.key;
        }

        // Generate new key
        byte[] key = generateKey(password);

        if (keyCache.size() < 50) {
            keyCache.put(password, new CacheEntry(key));
        }

        return key;
    }

    private static byte[] generateKey(String password) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(password.getBytes(StandardCharsets.UTF_8));
    }

    public static void clearKeyCache() {
        keyCache.clear();
    }

    static {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60000); // Every minute

                    synchronized (keyCache) {
                        keyCache.entrySet().removeIf(e -> e.getValue().isExpired());
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }
}