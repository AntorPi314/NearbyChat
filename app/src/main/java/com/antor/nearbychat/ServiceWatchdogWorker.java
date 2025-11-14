package com.antor.nearbychat;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;

public class ServiceWatchdogWorker extends Worker {

    private static final String TAG = "ServiceWatchdog";

    public ServiceWatchdogWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        if (!isServiceRunning(context, BleMessagingService.class)) {
            Log.w(TAG, "⚠️ Service is not running! Attempting to restart...");
            try {
                Intent serviceIntent = new Intent(context, BleMessagingService.class);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Log.d(TAG, "✅ Service restart initiated");
                return Result.success();

            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to restart service", e);
                return Result.retry();
            }
        } else {
            Log.d(TAG, "✅ Service is running normally");
            return Result.success();
        }
    }

    private boolean isServiceRunning(Context context, Class<?> serviceClass) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return false;

            List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);

            for (ActivityManager.RunningServiceInfo service : services) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking service status", e);
        }
        return false;
    }
}