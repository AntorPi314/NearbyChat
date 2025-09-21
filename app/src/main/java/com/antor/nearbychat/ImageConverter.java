package com.antor.nearbychat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

public class ImageConverter {

    /**
     * Resize and crop image to specified dimensions with center crop
     * @param bitmap Original bitmap
     * @param targetWidth Target width in pixels
     * @param targetHeight Target height in pixels
     * @return Resized and cropped bitmap
     */
    public static Bitmap resizeAndCrop(Bitmap bitmap, int targetWidth, int targetHeight) {
        if (bitmap == null) return null;

        try {
            int originalWidth = bitmap.getWidth();
            int originalHeight = bitmap.getHeight();

            // Calculate scale to fit the target size (center crop)
            float scaleX = (float) targetWidth / originalWidth;
            float scaleY = (float) targetHeight / originalHeight;
            float scale = Math.max(scaleX, scaleY); // Use max for center crop

            // Calculate new dimensions
            int scaledWidth = Math.round(originalWidth * scale);
            int scaledHeight = Math.round(originalHeight * scale);

            // Scale the bitmap
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);

            // Calculate crop coordinates (center crop)
            int cropX = (scaledWidth - targetWidth) / 2;
            int cropY = (scaledHeight - targetHeight) / 2;

            // Crop the bitmap
            Bitmap croppedBitmap = Bitmap.createBitmap(scaledBitmap,
                    Math.max(0, cropX),
                    Math.max(0, cropY),
                    Math.min(targetWidth, scaledWidth),
                    Math.min(targetHeight, scaledHeight));

            // Clean up
            if (scaledBitmap != bitmap && scaledBitmap != croppedBitmap) {
                scaledBitmap.recycle();
            }

            return croppedBitmap;

        } catch (Exception e) {
            e.printStackTrace();
            return bitmap; // Return original if error
        }
    }

    /**
     * Create circular bitmap
     * @param bitmap Input bitmap
     * @return Circular bitmap
     */
    public static Bitmap createCircularBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;

        try {
            int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
            Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);

            Canvas canvas = new Canvas(output);
            Paint paint = new Paint();
            Rect rect = new Rect(0, 0, size, size);
            RectF rectF = new RectF(rect);

            paint.setAntiAlias(true);
            canvas.drawARGB(0, 0, 0, 0);
            canvas.drawOval(rectF, paint);

            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(bitmap, rect, rect, paint);

            return output;

        } catch (Exception e) {
            e.printStackTrace();
            return bitmap;
        }
    }

    /**
     * Resize bitmap maintaining aspect ratio
     * @param bitmap Original bitmap
     * @param maxSize Maximum size for width or height
     * @return Resized bitmap
     */
    public static Bitmap resizeWithAspectRatio(Bitmap bitmap, int maxSize) {
        if (bitmap == null) return null;

        try {
            int originalWidth = bitmap.getWidth();
            int originalHeight = bitmap.getHeight();

            float scale;
            if (originalWidth > originalHeight) {
                scale = (float) maxSize / originalWidth;
            } else {
                scale = (float) maxSize / originalHeight;
            }

            int newWidth = Math.round(originalWidth * scale);
            int newHeight = Math.round(originalHeight * scale);

            return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);

        } catch (Exception e) {
            e.printStackTrace();
            return bitmap;
        }
    }
}