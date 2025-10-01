package com.antor.nearbychat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class CryptoUtils {

    /**
     * Encrypts a string using a password. Returns the original text if the password is null or empty.
     * The encrypted text will have the same length as the original text.
     */
    public static String encrypt(String textToEncrypt, String password) {
        if (password == null || password.isEmpty()) {
            return textToEncrypt; // No encryption if password is not set
        }
        try {
            byte[] key = generateKey(password);
            byte[] textBytes = textToEncrypt.getBytes(StandardCharsets.UTF_8);
            byte[] result = new byte[textBytes.length];

            for (int i = 0; i < textBytes.length; i++) {
                result[i] = (byte) (textBytes[i] ^ key[i % key.length]);
            }
            // Using ISO_8859_1 to preserve byte structure in a string format
            return new String(result, StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            e.printStackTrace();
            return textToEncrypt; // Return original text on error
        }
    }

    /**
     * Decrypts a string using a password. Returns the original text if the password is null or empty.
     */
    public static String decrypt(String textToDecrypt, String password) {
        if (password == null || password.isEmpty()) {
            return textToDecrypt; // No decryption if password is not set
        }
        try {
            byte[] key = generateKey(password);
            // Using ISO_8859_1 as it was used for encoding
            byte[] textBytes = textToDecrypt.getBytes(StandardCharsets.ISO_8859_1);
            byte[] result = new byte[textBytes.length];

            for (int i = 0; i < textBytes.length; i++) {
                result[i] = (byte) (textBytes[i] ^ key[i % key.length]);
            }
            return new String(result, StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return textToDecrypt; // Return original text on error
        }
    }

    /**
     * Generates a secure, fixed-length key from a user-provided password using SHA-256.
     */
    private static byte[] generateKey(String password) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(password.getBytes(StandardCharsets.UTF_8));
    }
}