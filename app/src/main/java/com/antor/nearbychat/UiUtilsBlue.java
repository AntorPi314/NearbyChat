package com.antor.nearbychat;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

public class UiUtilsBlue {

    public static void setLightSystemBars(Activity activity) {
        if (activity == null) return;

        Window window = activity.getWindow();

        // Purple status bar (#6C63FF) + White navigation bar
        window.setStatusBarColor(0xFF6270F7);
        window.setNavigationBarColor(0xFFFFFFFF);

        View decorView = window.getDecorView();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController insetsController = window.getInsetsController();
            if (insetsController != null) {
                // এখানে শুধু nav bar কে light করব (dark icons), status bar untouched → white icons থাকবে
                insetsController.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                );
            }
        } else {
            int flags = decorView.getSystemUiVisibility();

            // ❌ এটা add করা যাবে না, কারণ এটা dark করে দেয় →
            // flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;

            // ✅ শুধুমাত্র nav bar icons dark করব (কারণ bg সাদা)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }

            decorView.setSystemUiVisibility(flags);
        }
    }
}
