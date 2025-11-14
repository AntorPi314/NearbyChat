package com.antor.nearbychat;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoViewerActivity extends Activity {
    private static final String TAG = "VideoViewerActivity";

    private RecyclerView recyclerView;
    private TextView titleText;
    private Button loadAllButton;
    private VideoGridAdapter adapter;
    private ArrayList<String> videoUrls;
    private ImageCacheManager cacheManager;
    private SharedPreferences metadataPrefs; // Duration cache korar jonno

    private boolean autoLoadThumbnails = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_viewer);

        recyclerView = findViewById(R.id.videoGridRecyclerView);
        titleText = findViewById(R.id.titleAllVideos);
        loadAllButton = findViewById(R.id.btnLoadAllThumbnails);

        videoUrls = getIntent().getStringArrayListExtra("video_urls");
        int startPosition = getIntent().getIntExtra("start_position", 0);

        if (videoUrls == null || videoUrls.isEmpty()) {
            Toast.makeText(this, "No videos to display", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        cacheManager = ImageCacheManager.getInstance(this);
        metadataPrefs = getSharedPreferences("VideoMetadata", MODE_PRIVATE); // Prefs initialize

        titleText.setText("All Videos");

        GridLayoutManager layoutManager = new GridLayoutManager(this, 3);
        recyclerView.setLayoutManager(layoutManager);

        adapter = new VideoGridAdapter(videoUrls, autoLoadThumbnails);
        recyclerView.setAdapter(adapter);

        loadAllButton.setOnClickListener(v -> {
            autoLoadThumbnails = true;
            adapter.setAutoLoadThumbnails(true);
            adapter.notifyDataSetChanged();
            loadAllButton.setVisibility(View.GONE);
        });

        recyclerView.scrollToPosition(startPosition);
        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
    }

    // Milliseconds-ke m:ss format-e convert korar helper
    private String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private class VideoGridAdapter extends RecyclerView.Adapter<VideoGridAdapter.VideoViewHolder> {
        private final ArrayList<String> urls;
        private boolean autoLoad;
        private final ExecutorService executor = Executors.newFixedThreadPool(3);

        VideoGridAdapter(ArrayList<String> urls, boolean autoLoad) {
            this.urls = urls;
            this.autoLoad = autoLoad;
        }

        void setAutoLoadThumbnails(boolean autoLoad) {
            this.autoLoad = autoLoad;
        }

        @NonNull
        @Override
        public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_video_grid, parent, false);
            return new VideoViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
            holder.bind(urls.get(position));
        }

        @Override
        public int getItemCount() {
            return urls.size();
        }

        class VideoViewHolder extends RecyclerView.ViewHolder {
            ImageView thumbnailView;
            TextView durationView; // playIcon-er bodole
            ProgressBar progressBar;
            private boolean canPlay = false;

            VideoViewHolder(@NonNull View itemView) {
                super(itemView);
                thumbnailView = itemView.findViewById(R.id.videoThumbnail);
                durationView = itemView.findViewById(R.id.videoDuration); // notun ID
                progressBar = itemView.findViewById(R.id.progressBar);

                // item square korar logic
                itemView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (itemView.getWidth() > 0) {
                            ViewGroup.LayoutParams params = itemView.getLayoutParams();
                            params.height = itemView.getWidth();
                            itemView.setLayoutParams(params);
                            itemView.getViewTreeObserver().removeOnPreDrawListener(this);
                            return true;
                        }
                        return true;
                    }
                });
            }

            void bind(String videoUrl) {
                canPlay = false;

                Bitmap cachedBitmap = cacheManager.getBitmap(videoUrl + "_video_thumb", true);
                String cachedDuration = metadataPrefs.getString(videoUrl + "_duration", null);

                if (cachedBitmap != null) {
                    thumbnailView.setImageBitmap(cachedBitmap);
                    progressBar.setVisibility(View.GONE);
                    if (cachedDuration != null) {
                        durationView.setText(cachedDuration);
                        durationView.setVisibility(View.VISIBLE);
                    } else {
                        durationView.setVisibility(View.GONE);
                    }
                    canPlay = true;
                    setupClickListener(videoUrl);
                    return;
                }

                if (autoLoad) {
                    progressBar.setVisibility(View.VISIBLE);
                    durationView.setVisibility(View.GONE);
                    thumbnailView.setImageBitmap(null);
                    executor.execute(() -> loadThumbnail(videoUrl));
                } else {
                    thumbnailView.setImageBitmap(null);
                    thumbnailView.setBackgroundColor(0xFF1C1C1C);
                    progressBar.setVisibility(View.GONE);
                    durationView.setVisibility(View.GONE);
                    setupClickListener(videoUrl);
                }
            }

            private void loadThumbnail(String videoUrl) {
                try {
                    String fullUrl = videoUrl;
                    if (!fullUrl.startsWith("http://") && !fullUrl.startsWith("https://")) {
                        fullUrl = "https://" + fullUrl;
                    }

                    android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                    retriever.setDataSource(fullUrl, new HashMap<>());

                    // Thumbnail
                    Bitmap frame = retriever.getFrameAtTime(1000000);

                    // Duration
                    String durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                    retriever.release();

                    final String formattedDuration;
                    if (durationStr != null) {
                        long millis = Long.parseLong(durationStr);
                        formattedDuration = formatDuration(millis);
                        metadataPrefs.edit().putString(videoUrl + "_duration", formattedDuration).apply();
                    } else {
                        formattedDuration = null;
                    }

                    if (frame != null) {
                        int size = itemView.getWidth();
                        if (size <= 0) {
                            size = (int) (120 * getResources().getDisplayMetrics().density);
                        }

                        Bitmap thumbnail = ImageConverter.resizeAndCrop(frame, size, size);
                        cacheManager.putBitmap(videoUrl + "_video_thumb", thumbnail, true);

                        runOnUiThread(() -> {
                            thumbnailView.setImageBitmap(thumbnail);
                            if (formattedDuration != null) {
                                durationView.setText(formattedDuration);
                                durationView.setVisibility(View.VISIBLE);
                            }
                            progressBar.setVisibility(View.GONE);
                            canPlay = true;
                        });
                    } else {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            durationView.setVisibility(View.GONE);
                            canPlay = true;
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error loading thumbnail/duration", e);
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        durationView.setVisibility(View.GONE);
                        canPlay = true;
                    });
                }
            }

            private void setupClickListener(String videoUrl) {
                itemView.setOnClickListener(v -> {
                    if (canPlay) {
                        playVideo(videoUrl);
                    } else {
                        // 1. Play video
                        playVideo(videoUrl);

                        // 2. Show loading UI
                        progressBar.setVisibility(View.VISIBLE);
                        durationView.setVisibility(View.GONE);

                        // 3. Load metadata
                        executor.execute(() -> loadThumbnail(videoUrl));
                    }
                });
            }

            private void playVideo(String videoUrl) {
                try {
                    String fullUrl = videoUrl;
                    if (!fullUrl.startsWith("http://") && !fullUrl.startsWith("https://")) {
                        fullUrl = "https://" + fullUrl;
                    }
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(fullUrl), "video/*");
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    if (intent.resolveActivity(getPackageManager()) != null) {
                        startActivity(Intent.createChooser(intent, "Play video with"));
                    } else {
                        Toast.makeText(VideoViewerActivity.this, "No video player found", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error playing video", e);
                    Toast.makeText(VideoViewerActivity.this, "Error playing video", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}