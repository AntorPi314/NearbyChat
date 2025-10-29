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

            for (int i = 0; i < textBytes.length; i++) {
                result[i] = (byte) (textBytes[i] ^ key[i % key.length]);
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

            for (int i = 0; i < textBytes.length; i++) {
                result[i] = (byte) (textBytes[i] ^ key[i % key.length]);
            }
            return new String(result, ISO_8859_1);

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