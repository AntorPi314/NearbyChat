package com.antor.nearbychat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageViewer extends Activity {
    private static final String TAG = "ImageViewer";
    private static final int PERMISSION_REQUEST_CODE = 300;

    private ViewPager2 viewPager;
    private Bitmap currentBitmap;
    private GestureDetector gestureDetector;
    private ImagePagerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        viewPager = findViewById(R.id.viewPager);
        ArrayList<String> imageUrls = getIntent().getStringArrayListExtra("image_urls");
        int startPosition = getIntent().getIntExtra("start_position", 0);

        if (imageUrls == null || imageUrls.isEmpty()) {
            Toast.makeText(this, "No images to display", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        adapter = new ImagePagerAdapter(this, imageUrls, bitmap -> currentBitmap = bitmap);
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(startPosition, false);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                adapter.preloadAdjacentImages(position);
            }
        });
        adapter.preloadAdjacentImages(startPosition);

        gestureDetector = new GestureDetector(this, new SwipeGestureListener());
    }

    private class SwipeGestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 100;
        private static final int SWIPE_VELOCITY_THRESHOLD = 100;

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (e1 == null || e2 == null) return false;

            float diffY = e2.getY() - e1.getY();
            float diffX = e2.getX() - e1.getX();

            if (Math.abs(diffY) > Math.abs(diffX)) {
                if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffY > 0) {
                        finish();
                        overridePendingTransition(0, android.R.anim.fade_out);
                        return true;
                    }
                }
            }
            return false;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        gestureDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void showSaveDialog() {
        if (currentBitmap == null) {
            Toast.makeText(this, "Image not loaded yet", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Save Image")
                .setMessage("Save this image to Pictures/Nearby?")
                .setPositiveButton("Save", (dialog, which) -> checkPermissionAndSave())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkPermissionAndSave() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_CODE);
        } else {
            saveImageToGallery();
        }
    }

    private void saveImageToGallery() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File nearbyDir = new File(picturesDir, "Nearby");
                if (!nearbyDir.exists() && !nearbyDir.mkdirs()) {
                    runOnUiThread(() ->
                            Toast.makeText(this, "Failed to create directory", Toast.LENGTH_SHORT).show());
                    return;
                }
                String fileName = "IMG_" + System.currentTimeMillis() + ".jpg";
                File imageFile = new File(nearbyDir, fileName);

                try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                    currentBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                }
                runOnUiThread(() ->
                        Toast.makeText(this, "Image saved to Pictures/Nearby", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "Error saving image", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveImageToGallery();
            } else {
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    interface OnImageLoadedListener {
        void onImageLoaded(Bitmap bitmap);
    }

    private class ImagePagerAdapter extends RecyclerView.Adapter<ImagePagerAdapter.ImageViewHolder> {
        private final Activity activity;
        private final List<String> imageUrls;
        private final ExecutorService executor = Executors.newFixedThreadPool(3);
        private final OnImageLoadedListener listener;
        private final ImageCacheManager cacheManager;

        ImagePagerAdapter(Activity activity, List<String> imageUrls, OnImageLoadedListener listener) {
            this.activity = activity;
            this.imageUrls = imageUrls;
            this.listener = listener;
            this.cacheManager = ImageCacheManager.getInstance(activity);
        }

        @NonNull
        @Override
        public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_image_pager, parent, false);
            return new ImageViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
            holder.bind(imageUrls.get(position));
        }

        @Override
        public int getItemCount() {
            return imageUrls.size();
        }

        void preloadAdjacentImages(int currentPosition) {
            if (currentPosition + 1 < imageUrls.size()) {
                preloadImage(imageUrls.get(currentPosition + 1));
            }
            if (currentPosition - 1 >= 0) {
                preloadImage(imageUrls.get(currentPosition - 1));
            }
        }

        private void preloadImage(String url) {
            executor.execute(() -> {
                if (cacheManager.getBitmap(url, false) != null) {
                    return;
                }
                try {
                    String fullUrl = url;
                    if (!fullUrl.startsWith("http://") && !fullUrl.startsWith("https://")) {
                        fullUrl = "https://" + fullUrl;
                    }
                    URL imageUrl = new URL(fullUrl);
                    HttpURLConnection connection = (HttpURLConnection) imageUrl.openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(15000);

                    Bitmap loadedBitmap = BitmapFactory.decodeStream(connection.getInputStream());

                    if (loadedBitmap != null) {
                        cacheManager.putBitmap(url, loadedBitmap, false);
                        Log.d(TAG, "Preloaded image: " + url);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error preloading image", e);
                }
            });
        }

        class ImageViewHolder extends RecyclerView.ViewHolder {
            ImageView imageView;
            ProgressBar progressBar;

            ImageViewHolder(@NonNull View itemView) {
                super(itemView);
                imageView = itemView.findViewById(R.id.pagerImageView);
                progressBar = itemView.findViewById(R.id.pagerProgressBar);
            }

            void bind(String imageUrl) {
                Bitmap cachedBitmap = cacheManager.getBitmap(imageUrl, false);

                if (cachedBitmap != null) {
                    progressBar.setVisibility(View.GONE);
                    imageView.setImageBitmap(cachedBitmap);

                    if (getAdapterPosition() == viewPager.getCurrentItem()) {
                        listener.onImageLoaded(cachedBitmap);
                    }
                    setupLongPress(cachedBitmap);
                    return;
                }
                progressBar.setVisibility(View.VISIBLE);
                imageView.setImageBitmap(null);

                executor.execute(() -> {
                    try {
                        String fullUrl = imageUrl;
                        if (!fullUrl.startsWith("http://") && !fullUrl.startsWith("https://")) {
                            fullUrl = "https://" + fullUrl;
                        }

                        URL url = new URL(fullUrl);
                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                        connection.setConnectTimeout(15000);
                        connection.setReadTimeout(15000);

                        final Bitmap loadedBitmap =
                                BitmapFactory.decodeStream(connection.getInputStream());

                        if (loadedBitmap != null) {
                            cacheManager.putBitmap(imageUrl, loadedBitmap, false);
                        }

                        activity.runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            if (loadedBitmap != null) {
                                imageView.setImageBitmap(loadedBitmap);
                                if (getAdapterPosition() == viewPager.getCurrentItem()) {
                                    listener.onImageLoaded(loadedBitmap);
                                }
                                setupLongPress(loadedBitmap);
                            } else {
                                Toast.makeText(activity, "Failed to load image", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Error loading image", e);
                        activity.runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            Toast.makeText(activity, "Error loading image", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            }

            private void setupLongPress(Bitmap bitmap) {
                itemView.setOnLongClickListener(v -> {
                    listener.onImageLoaded(bitmap);
                    showSaveDialog();
                    return true;
                });
                imageView.setOnLongClickListener(v -> {
                    listener.onImageLoaded(bitmap);
                    showSaveDialog();
                    return true;
                });
            }
        }
    }
}