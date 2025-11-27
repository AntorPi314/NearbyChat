package com.antor.nearbychat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class QRCodeActivity extends Activity {

    private ImageView qrCodeImage;
    private TextView qrInfoText;
    private ImageView btnBack;
    private ImageView btnShare;
    private String qrData;
    private String qrType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_code_new);

        qrCodeImage = findViewById(R.id.qrCodeImageNew);
        qrInfoText = findViewById(R.id.qrInfoTextNew);
        btnBack = findViewById(R.id.btnBackQR);
        btnShare = findViewById(R.id.btnShareQR);

        qrData = getIntent().getStringExtra("qr_data");
        qrType = getIntent().getStringExtra("qr_type");
        String displayName = getIntent().getStringExtra("display_name");

        if (qrData != null && !qrData.isEmpty()) {
            generateQRCode(qrData);
            qrInfoText.setText("Scan this QR code to add\n" +
                    (displayName != null ? displayName : qrType));
        } else {
            Toast.makeText(this, "Invalid QR data", Toast.LENGTH_SHORT).show();
            finish();
        }

        btnBack.setOnClickListener(v -> finish());

        btnShare.setOnClickListener(v -> {
            Toast.makeText(this, "Share feature coming soon", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnCloseNew).setOnClickListener(v -> finish());
    }

    private void generateQRCode(String data) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(data, BarcodeFormat.QR_CODE, 512, 512);

            int width = matrix.getWidth();
            int height = matrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }

            qrCodeImage.setImageBitmap(bitmap);
        } catch (WriterException e) {
            Toast.makeText(this, "Error generating QR code", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
}