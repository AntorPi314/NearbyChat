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
            // byte[] textBytes = textToEncrypt.getBytes(StandardCharsets.UTF_8); // <-- THIS WAS THE BUG

            // ================== FIX START ==================
            // The textToEncrypt is the payload from PayloadCompress.
            // We must check if it's uncompressed (UTF-8) or compressed (ISO-8859-1).
            byte[] textBytes;
            if (textToEncrypt.startsWith("[u>")) {
                // Uncompressed payload, use UTF-8
                textBytes = textToEncrypt.getBytes(StandardCharsets.UTF_8);
            } else {
                // Compressed payload (binary data in ISO-8859-1 string), use ISO-8859-1
                textBytes = textToEncrypt.getBytes(StandardCharsets.ISO_8859_1);
            }
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
            // This is correct: get the raw encrypted bytes from the ISO-8859-1 string
            byte[] textBytes = textToDecrypt.getBytes(StandardCharsets.ISO_8859_1);
            byte[] result = new byte[textBytes.length];

            for (int i = 0; i < textBytes.length; i++) {
                result[i] = (byte) (textBytes[i] ^ key[i % key.length]);
            }

            // This is tricky: we don't know if the original was compressed or uncompressed.
            // But PayloadCompress.parsePayload handles this. It first tries to
            // decompress (which uses ISO-8859-1) and if it fails (or finds [u>),
            // it treats it as UTF-8.
            // THEREFORE, the decrypted string should be returned as ISO-8859-1
            // to let PayloadCompress parse the raw bytes.

            // return new String(result, StandardCharsets.UTF_8); // <-- THIS IS A POTENTIAL BUG

            // ================== FIX START ==================
            // We must return the raw decrypted bytes (packed in ISO-8859-1)
            // so that PayloadCompress.parsePayload can correctly process
            // either the compressed data (binary) or the [u>... marker (UTF-8).
            // The receiving side (asciiToBitsMsg) already uses ISO-8859-1.
            return new String(result, StandardCharsets.ISO_8859_1);
            // ================== FIX END ==================

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
