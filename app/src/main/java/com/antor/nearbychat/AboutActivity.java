package com.antor.nearbychat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        UiUtils.setSystemBars(
                this,
                "#181A20",
                false,
                "#181A20",
                false
        );
        setupBackButton();
        setupVersionText();
        setupSocialLinks();
    }

    private void setupBackButton() {
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
    }

    private void setupVersionText() {
        TextView versionText = findViewById(R.id.versionText);
        if (versionText != null) {
            versionText.setText("Version " + getAppVersion());
        }
    }

    private String getAppVersion() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private void setupSocialLinks() {
        findViewById(R.id.facebookIcon).setOnClickListener(v ->
                openUrl("https://www.facebook.com/NearbyChat"));

        findViewById(R.id.telegramIcon).setOnClickListener(v ->
                openUrl("https://t.me/NearbyChat"));

        findViewById(R.id.githubIcon).setOnClickListener(v ->
                openUrl("https://github.com/YourGithubUsername/NearbyChat"));
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
}