package com.antor.nearbychat;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;

import android.app.Activity;

import androidx.annotation.NonNull;

public class WelcomeActivity extends Activity {

    private static final String PREFS_NAME = "WelcomePrefs";
    private static final String KEY_AGREED = "hasAgreed";

    private ImageView imageGaza;
    private View sosWaterFill;
    private TextView textAgreement;
    private TextView btnAgree;
    private TextView btnDisagree;
    private TextView textSOSBlue;
    private TextView sosTextMask;

    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check if user already agreed
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_AGREED, false)) {
            navigateToMain();
            return;
        }

        setContentView(R.layout.activity_welcome);

        initViews();
        setupMediaPlayer();
        setupClickListeners();
        setupAgreementText();
        startWaterAnimation();
    }

    private void initViews() {
        imageGaza = findViewById(R.id.imageGaza);
        sosWaterFill = findViewById(R.id.sosWaterFill);
        sosTextMask = findViewById(R.id.sosTextMask);
        textSOSBlue = findViewById(R.id.textSOSBlue);
        textAgreement = findViewById(R.id.textAgreement);
        btnAgree = findViewById(R.id.btnAgree);
        btnDisagree = findViewById(R.id.btnDisagree);

        setupGradientText();
    }

    private void setupGradientText() {
        textSOSBlue.post(() -> {
            int width = textSOSBlue.getWidth();
            if (width > 0) {
                android.graphics.LinearGradient gradient = new android.graphics.LinearGradient(
                        0, 0, width, 0,
                        new int[]{
                                Color.parseColor("#1E88E5"),
                                Color.parseColor("#42A5F5"),
                                Color.parseColor("#64B5F6")
                        },
                        null,
                        android.graphics.Shader.TileMode.CLAMP
                );
                textSOSBlue.getPaint().setShader(gradient);
            }
        });
    }

    private void setupMediaPlayer() {
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.song);
            mediaPlayer.setLooping(true);
            mediaPlayer.start();
            isPlaying = true;
        } catch (Exception e) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupClickListeners() {
        imageGaza.setOnClickListener(v -> toggleAudio());

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

    private void toggleAudio() {
        if (mediaPlayer == null) return;

        try {
            if (isPlaying) {
                mediaPlayer.pause();
                isPlaying = false;
            } else {
                mediaPlayer.start();
                isPlaying = true;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Audio error", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupAgreementText() {
        String fullText = "By using SOSBlue, you agree to our Terms of Use and Privacy Policy";
        SpannableString spannable = new SpannableString(fullText);

        int termsStart = fullText.indexOf("Terms of Use");
        int termsEnd = termsStart + "Terms of Use".length();
        int privacyStart = fullText.indexOf("Privacy Policy");
        int privacyEnd = privacyStart + "Privacy Policy".length();

        ClickableSpan termsSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                openUrl("https://antorpi314.github.io/terms");
            }
        };

        ClickableSpan privacySpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                openUrl("https://antorpi314.github.io/policy");
            }
        };

        spannable.setSpan(termsSpan, termsStart, termsEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new ForegroundColorSpan(Color.parseColor("#0D80E0")),
                termsStart, termsEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        spannable.setSpan(privacySpan, privacyStart, privacyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new ForegroundColorSpan(Color.parseColor("#0D80E0")),
                privacyStart, privacyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        textAgreement.setText(spannable);
        textAgreement.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void startWaterAnimation() {
        ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
        animator.setDuration(3000);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);

        animator.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();

            // Water fill animation
            sosWaterFill.setScaleY(value);

            // Clip text to show only water-filled portion
            int clipHeight = (int) (sosTextMask.getHeight() * value);
            sosTextMask.setTranslationY(clipHeight);
        });

        animator.start();
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show();
        }
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mediaPlayer != null && isPlaying) {
            mediaPlayer.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}