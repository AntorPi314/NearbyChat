package com.antor.nearbychat;

import android.app.Activity;
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

    private static final int CAMERA_PERMISSION_REQUEST = 300;
    private ImageView qrCodeImage;
    private TextView qrInfoText;
    private String qrData;
    private String qrType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_code);

        qrCodeImage = findViewById(R.id.qrCodeImage);
        qrInfoText = findViewById(R.id.qrInfoText);

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

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
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