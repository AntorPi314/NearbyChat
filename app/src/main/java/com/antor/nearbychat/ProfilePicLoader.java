package com.antor.nearbychat;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.util.Base64;
import android.util.Log;
import android.widget.ImageView;

public class ProfilePicLoader {

    private static final String PREFS_NAME = "NearbyChatPrefs";
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray();
    private static final int[] BACKGROUND_COLORS = {
            0xFF1ABC9C, 0xFF2ECC71, 0xFF3498DB, 0xFF9B59B6, 0xFFE74C3C, 0xFF2C3E50,
            0xFF16A085, 0xFF27AE60, 0xFF2980B9, 0xFF8E44AD, 0xFFC0392B, 0xFFD35400,
            0xFF34495E, 0xFF7F8C8D, 0xFFE67E22, 0xFF6C7B7F, 0xFF8B4513, 0xFF1F2937,
            0xFF374151, 0xFF4B5563, 0xFF6B7280, 0xFF9CA3AF, 0xFFEF4444, 0xFFF97316,
            0xFF228B22, 0xFF22C55E, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFF06B6D4
    };

    public static void loadProfilePicture(Context context, String userId, ImageView imageView) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String base64Image = prefs.getString("profile_" + userId, null);

        if (base64Image != null) {
            try {
                byte[] imageBytes = Base64.decode(base64Image, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                Bitmap circularBitmap = ImageConverter.createCircularBitmap(bitmap);
                imageView.setImageBitmap(circularBitmap);
            } catch (Exception e) {
                Log.e("ProfilePicLoader", "Error loading saved profile picture for " + userId, e);
                Bitmap generatedBitmap = generateProfilePic(userId);
                imageView.setImageBitmap(generatedBitmap);
            }
        } else {
            Bitmap generatedBitmap = generateProfilePic(userId);
            imageView.setImageBitmap(generatedBitmap);
        }
    }

    private static Bitmap generateProfilePic(String userId) {
        try {
            if (userId == null || userId.length() < 8) {
                return createDefaultProfilePic();
            }
            String text = userId.substring(6, 8);
            char colorChar = userId.charAt(5);
            int colorIndex = getAlphabetIndex(colorChar);
            int bgColor = BACKGROUND_COLORS[colorIndex % BACKGROUND_COLORS.length];
            return createTextBitmap(text, bgColor, 0xFFFFFFFF);
        } catch (Exception e) {
            Log.e("ProfilePicLoader", "Error generating profile pic", e);
            return createDefaultProfilePic();
        }
    }

    private static int getAlphabetIndex(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return 0;
    }

    private static Bitmap createTextBitmap(String text, int bgColor, int textColor) {
        int size = 94;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        Paint bgPaint = new Paint();
        bgPaint.setAntiAlias(true);
        bgPaint.setColor(bgColor);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint);

        Paint textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setColor(textColor);
        textPaint.setTextSize(size * 0.45f);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
        float textHeight = fontMetrics.bottom - fontMetrics.top;
        float textY = (size + textHeight) / 2f - fontMetrics.bottom;
        canvas.drawText(text, size / 2f, textY, textPaint);
        return bitmap;
    }

    private static Bitmap createDefaultProfilePic() {
        return createTextBitmap("??", 0xFF95A5A6, 0xFFFFFFFF);
    }
}