package com.antor.nearbychat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class CryptoUtils {

    private static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;

    public static String encrypt(String textToEncrypt, String password) {
        if (password == null || password.isEmpty()) {
            return textToEncrypt;
        }
        try {
            byte[] key = generateKey(password);
            byte[] textBytes = textToEncrypt.getBytes(ISO_8859_1);
            byte[] result = new byte[textBytes.length];

            // ✅ Enhanced XOR with key rotation and bit mixing
            int keyIndex = 0;
            byte prevByte = 0;

            for (int i = 0; i < textBytes.length; i++) {
                // Mix current byte with previous encrypted byte for diffusion
                byte mixedKey = (byte) (key[keyIndex] ^ prevByte);

                // Apply XOR encryption with mixed key
                result[i] = (byte) (textBytes[i] ^ mixedKey);

                // Store encrypted byte for next iteration
                prevByte = result[i];

                // Rotate through key with non-linear progression
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
            byte[] key = generateKey(password);
            byte[] textBytes = textToDecrypt.getBytes(ISO_8859_1);
            byte[] result = new byte[textBytes.length];

            // ✅ Reverse the encryption process
            int keyIndex = 0;
            byte prevByte = 0;

            for (int i = 0; i < textBytes.length; i++) {
                // Recreate the same mixed key used during encryption
                byte mixedKey = (byte) (key[keyIndex] ^ prevByte);

                // Apply XOR decryption
                result[i] = (byte) (textBytes[i] ^ mixedKey);

                // Store encrypted byte (not decrypted) for next iteration
                prevByte = textBytes[i];

                // Same key rotation as encryption
                keyIndex = (keyIndex + 1 + (i % 3)) % key.length;
            }

            return new String(result, ISO_8859_1);

        } catch (Exception e) {
            e.printStackTrace();
            return textToDecrypt;
        }
    }

    private static byte[] generateKey(String password) throws Exception {
        // ✅ SHA-256 provides 256-bit (32-byte) key
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(password.getBytes(StandardCharsets.UTF_8));
    }
}