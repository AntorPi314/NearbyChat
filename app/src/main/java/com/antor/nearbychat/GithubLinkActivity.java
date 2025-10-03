package com.antor.nearbychat;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.content.Context;

import androidx.core.view.WindowCompat;

public class GithubLinkActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_github_link);
        setupUI();
    }

    private void setupUI() {
        final View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int heightDiff = rootView.getRootView().getHeight() - rootView.getHeight();
            boolean keyboardVisible = heightDiff > dpToPx(this, 200);
            if (!keyboardVisible) {
                UiUtils.setLightSystemBars(this);
            }
        });
    }

    private int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}