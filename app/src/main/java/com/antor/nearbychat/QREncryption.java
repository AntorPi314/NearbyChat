package com.antor.nearbychat;

import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class QREncryption {
    private static final String SECRET_KEY = "N3@rbyCh@tS3cr3t";
    private static final String INIT_VECTOR = "R@nd0m1n1tV3ct0r";

    public static final String INTEGRITY_CHECK = "|N3@RBY_CH@T_V1|";

    public static String encrypt(String value) {
        try {
            String valueToEncrypt = value + INTEGRITY_CHECK;

            IvParameterSpec iv = new IvParameterSpec(INIT_VECTOR.getBytes("UTF-8"));
            SecretKeySpec skeySpec = new SecretKeySpec(SECRET_KEY.getBytes("UTF-8"), "AES");

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec, iv);

            byte[] encrypted = cipher.doFinal(valueToEncrypt.getBytes());
            return Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static String decrypt(String encrypted) {
        try {
            IvParameterSpec iv = new IvParameterSpec(INIT_VECTOR.getBytes("UTF-8"));
            SecretKeySpec skeySpec = new SecretKeySpec(SECRET_KEY.getBytes("UTF-8"), "AES");

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
            cipher.init(Cipher.DECRYPT_MODE, skeySpec, iv);

            byte[] original = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP));
            String decryptedString = new String(original);

            if (decryptedString.endsWith(INTEGRITY_CHECK)) {
                return decryptedString.substring(0, decryptedString.length() - INTEGRITY_CHECK.length());
            }

        } catch (Exception ex) {
        }
        return null;
    }
}