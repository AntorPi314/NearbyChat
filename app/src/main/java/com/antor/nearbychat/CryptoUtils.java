package com.antor.nearbychat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class CryptoUtils {

    public static String encrypt(String textToEncrypt, String password) {
        if (password == null || password.isEmpty()) {
            return textToEncrypt;
        }
        try {
            byte[] key = generateKey(password);

            // ================== CRITICAL FIX ==================
            // The textToEncrypt comes from PayloadCompress.buildPayload()
            // PayloadCompress returns EVERYTHING as an ISO-8859-1 encoded string:
            // - Compressed messages: binary data as ISO-8859-1
            // - Unicode messages: "[u>" marker + raw UTF-8 bytes packed in ISO-8859-1
            // - Media links: markers + compressed data as ISO-8859-1
            //
            // Therefore, we MUST use ISO-8859-1 to extract the raw bytes
            byte[] textBytes = textToEncrypt.getBytes(StandardCharsets.ISO_8859_1);
            // ================== FIX END ==================

            byte[] result = new byte[textBytes.length];

            for (int i = 0; i < textBytes.length; i++) {
                result[i] = (byte) (textBytes[i] ^ key[i % key.length]);
            }

            // Return the encrypted binary data packed in an ISO-8859-1 string
            return new String(result, StandardCharsets.ISO_8859_1);
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
            byte[] key = generateKey(password);

            // Get the raw encrypted bytes from the ISO-8859-1 string
            byte[] textBytes = textToDecrypt.getBytes(StandardCharsets.ISO_8859_1);
            byte[] result = new byte[textBytes.length];

            for (int i = 0; i < textBytes.length; i++) {
                result[i] = (byte) (textBytes[i] ^ key[i % key.length]);
            }

            // Return decrypted data as ISO-8859-1 string
            // This preserves the raw bytes so PayloadCompress.parsePayload()
            // can correctly handle both compressed and unicode data
            return new String(result, StandardCharsets.ISO_8859_1);

        } catch (Exception e) {
            e.printStackTrace();
            return textToDecrypt;
        }
    }

    private static byte[] generateKey(String password) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(password.getBytes(StandardCharsets.UTF_8));
    }
}