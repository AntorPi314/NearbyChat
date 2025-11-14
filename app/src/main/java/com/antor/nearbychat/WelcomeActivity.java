package com.antor.nearbychat;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;
import android.app.Activity;

public class WelcomeActivity extends Activity {

    private static final String PREFS_NAME = "WelcomePrefs";
    private static final String KEY_AGREED = "hasAgreed";

    private TextView textAgreement;
    private TextView btnAgree;
    private TextView btnDisagree;
    private TextView textTerms;
    private TextView textPrivacyPolicy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_AGREED, false)) {
            navigateToMain();
            return;
        }

        setContentView(R.layout.activity_welcome);

        initViews();
        setupClickListeners();
    }

    private void initViews() {
        textAgreement = findViewById(R.id.textAgreement);
        btnAgree = findViewById(R.id.btnAgree);
        btnDisagree = findViewById(R.id.btnDisagree);
        textTerms = findViewById(R.id.textTerms);
        textPrivacyPolicy = findViewById(R.id.textPrivacyPolicy);
    }

    private void setupClickListeners() {
        textTerms.setOnClickListener(v ->
                openWebView("https://antorpi314.github.io/terms", "Terms of Use"));

        textPrivacyPolicy.setOnClickListener(v ->
                openWebView("https://antorpi314.github.io/policy", "Privacy Policy"));

        btnAgree.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_AGREED, true).apply();
            navigateToMain();
        });

        btnDisagree.setOnClickListener(v -> {
            finishAffinity();
            System.exit(0);
        });
    }

    private void openWebView(String url, String title) {
        Intent intent = new Intent(this, WebviewActivity.class);
        intent.putExtra("url", url);
        intent.putExtra("title", title);
        startActivity(intent);
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        // ✅ ADD these flags to prevent crash
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}