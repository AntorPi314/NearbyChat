package com.antor.nearbychat;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SupportDevActivity extends Activity {

    private static final String ENC_NAME     = "yvc/zxvqSPos3RK1MHN6g1qnJW+bcctq/5ARlIkz0Ca0";
    private static final String ENC_GITHUB   = "qD10d6/uAlFfIqyrJFhaGXnvE0M+Fxaq+d6RhX3A6jNE0ILTZFvuRNg8utkaFszhrMLKlB4ePCrl";
    private static final String ENC_LINKEDIN = "5Py6xw8nB2E3Bo4/geK6ITO02PBM66cVlzy/m0Q4C23VFZUvivL0qE3MrLHWvD6ezgy0fwgGJvQFsjV6pfBTjdz9faUAFw==";
    private static final String ENC_YOUTUBE  = "wCaNE7KLzxLea7Y5RM37M6ulKYKrrvtVZyNDYu5ksIXLnQ1xnGFgt21jaaysKG4/YgQ4u/AMHt9NN5yVE+0yzSAwXQ==";

    private static final String ENC_USDT = "9uZVEqpFi/YYaISIdbrK7cRG6PMmtPtNKuG/HZ1HsJmwH/EBf71T1PXMu1bTygPc3kbhIS3jJZr86ezAs1A=";
    private static final String ENC_BTC  = "aRDPq1sMNhpD+ppPSgU/1GvRrUciLIkgJy+vXVvApKhMS4Ew+FJxJB5lGpiu5aQvza7x2868UOHZ5EMw5DM=";
    private static final String ENC_ETH  = "77c4SCCG70nKoxFv2Xk26cRzhcw3E6Sy1VIVLvNULxoxp3BjUbLxCiGoE52rk8wabEWaU20W0Kq4riQVs2VicRSZ9yNwBw==";
    private static final String ENC_BNB  = "RqXTpTii9GuMs9m+u+amWPddX7OGZPve/OmTGpRxzQsT8lijPDwsbqF5V2yjBDDHFQkM6A5Lyn8E2p55YW+EQiIrDPECzw==";
    private static final String ENC_LTC  = "XiW3B9TBq8UajTWvdDsS8hYPi3Os9ADRc+K5X0ZkX+y07GUM0HkY6byBmjciQvb40LRouqCcuPbXXwpWIi8=";

    private Spinner cryptoSpinner;
    private TextView cryptoAddress;
    private Map<String, String> cryptoMap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_support_dev);
        UiUtils.setSystemBars(
                this,
                "#181A20",
                false,
                "#181A20",
                false
        );

        setupProfile();
        setupSocialLinks();
        setupCryptoDropdown();
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
    }

    private void setupProfile() {
        ImageView profilePic = findViewById(R.id.profilePic);
        TextView devName = findViewById(R.id.devName);

        try {
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.antor);
            if (bitmap != null) {
                Bitmap circularBitmap = ImageConverter.createCircularBitmap(bitmap);
                profilePic.setImageBitmap(circularBitmap);
            }
        } catch (Exception e) {
            profilePic.setImageResource(R.drawable.antor);
        }

        devName.setText(decryptAesGcmBase64(ENC_NAME));
    }

    private void setupSocialLinks() {
        findViewById(R.id.githubIcon).setOnClickListener(v -> openUrl(decryptAesGcmBase64(ENC_GITHUB)));
        findViewById(R.id.linkedinIcon).setOnClickListener(v -> openUrl(decryptAesGcmBase64(ENC_LINKEDIN)));
        findViewById(R.id.youtubeIcon).setOnClickListener(v -> openUrl(decryptAesGcmBase64(ENC_YOUTUBE)));
    }

    private void setupCryptoDropdown() {
        cryptoSpinner = findViewById(R.id.cryptoSpinner);
        cryptoAddress = findViewById(R.id.cryptoAddress);

        cryptoMap = new LinkedHashMap<>();
        cryptoMap.put("USDT (Tron - TRC20)", decryptAesGcmBase64(ENC_USDT));
        cryptoMap.put("Bitcoin (BTC)", decryptAesGcmBase64(ENC_BTC));
        cryptoMap.put("Ethereum (ETH - ERC20)", decryptAesGcmBase64(ENC_ETH));
        cryptoMap.put("BNB (BSC - BEP20)", decryptAesGcmBase64(ENC_BNB));
        cryptoMap.put("Litecoin (LTC)", decryptAesGcmBase64(ENC_LTC));

        String[] cryptoNames = cryptoMap.keySet().toArray(new String[0]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, cryptoNames);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        cryptoSpinner.setAdapter(adapter);

        String defaultName = "USDT (Tron - TRC20)";
        cryptoAddress.setText(cryptoMap.get(defaultName));

        cryptoSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = cryptoNames[position];
                String addr = cryptoMap.get(selected);
                cryptoAddress.setText(addr != null ? addr : "");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // set selection to default
        for (int i = 0; i < cryptoNames.length; i++) {
            if (cryptoNames[i].equals(defaultName)) {
                cryptoSpinner.setSelection(i);
                break;
            }
        }

        findViewById(R.id.copyButton).setOnClickListener(v -> {
            String address = cryptoAddress.getText().toString();
            if (!address.isEmpty()) {
                copyToClipboard(address);
                Toast.makeText(this, "Address copied!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No address to copy", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Crypto Address", text);
        clipboard.setPrimaryClip(clip);
    }

    private void openUrl(String url) {
        try {
            if (url == null || url.isEmpty()) {
                Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show();
        }
    }

    // -----------------------
    // Key handling (obfuscated in-code)
    // -----------------------
    // The original AES-256 key bytes were XOR'd with mask 0xAA and stored as hex.
    // We reconstruct by hex->bytes then XOR with 0xAA again.
    private static final String OBFUSCATED_KEY_HEX = "90d5b87302e6bbf4318ac522ab69d7e090d5b87302e6bbf4318ac522ab69d7e0";
    private static final byte KEY_MASK = (byte)0xAA;

    private static byte[] getKeyBytes() {
        byte[] ob = hexStringToByteArray(OBFUSCATED_KEY_HEX);
        byte[] key = new byte[ob.length];
        for (int i = 0; i < ob.length; i++) key[i] = (byte)(ob[i] ^ KEY_MASK);
        return key;
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private String decryptAesGcmBase64(String encryptedBase64) {
        try {
            if (encryptedBase64 == null || encryptedBase64.isEmpty()) return "";
            byte[] keyBytes = getKeyBytes();
            if (keyBytes == null || keyBytes.length == 0) return "";

            byte[] all = Base64.decode(encryptedBase64, Base64.NO_WRAP);
            if (all.length < 12 + 16) return "";

            final int ivLen = 12;
            byte[] iv = new byte[ivLen];
            System.arraycopy(all, 0, iv, 0, ivLen);
            byte[] cipherAndTag = new byte[all.length - ivLen];
            System.arraycopy(all, ivLen, cipherAndTag, 0, cipherAndTag.length);

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
            byte[] plain = cipher.doFinal(cipherAndTag);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }
}
