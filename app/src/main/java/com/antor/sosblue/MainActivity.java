package com.antor.sosblue;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String TAG = "SOSBlue";
    private static final UUID SERVICE_UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb");

    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private TextView chatText;
    private EditText inputMessage;
    private Button sendButton;

    private AdvertiseCallback advertiseCallback;
    private ScanCallback scanCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        chatText = findViewById(R.id.chatText);
        inputMessage = findViewById(R.id.inputMessage);
        sendButton = findViewById(R.id.sendButton);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth not available or not enabled", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!adapter.isMultipleAdvertisementSupported()) {
            Toast.makeText(this, "BLE Advertising not supported on this device", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        advertiser = adapter.getBluetoothLeAdvertiser();
        scanner = adapter.getBluetoothLeScanner();

        requestPermissions();

        sendButton.setOnClickListener(v -> {
            String msg = inputMessage.getText().toString().trim();
            if (!msg.isEmpty()) {
                startAdvertising(msg);
                chatText.append("\nYou: " + msg);
                inputMessage.setText("");
            }
        });
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.BLUETOOTH_ADVERTISE,
                                Manifest.permission.BLUETOOTH_SCAN
                        },
                        PERMISSION_REQUEST_CODE);
                return;
            }
        }

        startScanning();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            startScanning();
        }
    }

    private void startAdvertising(String message) {
        if (advertiser == null) return;

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceData(new ParcelUuid(SERVICE_UUID), message.getBytes(StandardCharsets.UTF_8))
                .build();

        stopAdvertising(); // stop previous
        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                Log.i(TAG, "Advertising started");
            }

            @Override
            public void onStartFailure(int errorCode) {
                Log.e(TAG, "Advertising failed: " + errorCode);
            }
        };

        advertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private void stopAdvertising() {
        if (advertiser != null && advertiseCallback != null) {
            advertiser.stopAdvertising(advertiseCallback);
        }
    }

    private void startScanning() {
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                ScanRecord record = result.getScanRecord();
                if (record != null) {
                    byte[] data = record.getServiceData().get(new ParcelUuid(SERVICE_UUID));
                    if (data != null) {
                        final String received = new String(data, StandardCharsets.UTF_8);
                        runOnUiThread(() -> chatText.append("\nNearby: " + received));
                    }
                }
            }
        };
        scanner.startScan(scanCallback);
    }

    @Override
    protected void onDestroy() {
        stopAdvertising();
        if (scanner != null && scanCallback != null) {
            scanner.stopScan(scanCallback);
        }
        super.onDestroy();
    }
}
