package com.antor.nearbychat;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

public class UiUtils {

    public static void setLightSystemBars(Activity activity) {
        if (activity == null) return;

        Window window = activity.getWindow();

        // StatusBar & NavigationBar background → White
        window.setStatusBarColor(0xFFFFFFFF);
        window.setNavigationBarColor(0xFFFFFFFF);

        View decorView = window.getDecorView();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 (API 30) and above
            WindowInsetsController insetsController = window.getInsetsController();
            if (insetsController != null) {
                // Set status bar + navigation bar icons dark (grey)
                insetsController.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                );
            }
        } else {
            // Android 8.0 – Android 10
            int flags = decorView.getSystemUiVisibility();

            // Dark status bar icons
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;

            // Dark navigation bar icons (from API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }

            decorView.setSystemUiVisibility(flags);
        }
    }
}

