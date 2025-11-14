package com.antor.nearbychat;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.antor.nearbychat.Database.AppDatabase;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private final List<MessageModel> messageList;
    private final Context context;
    private final MessageClickListener clickListener;
    private final MessageClickListener longClickListener;
    private final Map<String, Bitmap> imageCache = new HashMap<>();
    private static ExecutorService sharedExecutor;
    private final ExecutorService executor;

    private static final int MAX_MESSAGE_LENGTH = 500;

    public interface MessageClickListener {
        void onClick(MessageModel msg);
    }

    public ChatAdapter(List<MessageModel> messageList, Context context,
                       MessageClickListener clickListener,
                       MessageClickListener longClickListener) {
        this.messageList = messageList;
        this.context = context;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;

        if (sharedExecutor == null || sharedExecutor.isShutdown()) {
            sharedExecutor = Executors.newFixedThreadPool(4);
        }
        this.executor = sharedExecutor;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context)
                .inflate(viewType == 1 ? R.layout.item_message_right : R.layout.item_message_left, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        MessageModel msg = messageList.get(position);
        MainActivity main = (MainActivity) context;

        // ---------------------------------------------------------
        // ✅ 1. SMART SENDER ID RECYCLING
        // ---------------------------------------------------------
        String currentSenderId = (String) holder.itemView.getTag(R.id.tag_sender_id);

        if (!msg.getSenderId().equals(currentSenderId)) {
            String displayName = main.getDisplayName(msg.getSenderId());
            holder.senderId.setText(displayName.isEmpty() ? msg.getSenderId() : displayName);
            holder.itemView.setTag(R.id.tag_sender_id, msg.getSenderId());
        }

        holder.timestamp.setText(msg.getTimestamp());

        // ▼▼▼ প্রোফাইল পিকচার লোডিং (সবার আগে) ▼▼▼
        if (holder.profilePic != null) {
            main.loadProfilePictureForAdapter(msg.getSenderId(), holder.profilePic);

            holder.profilePic.setOnClickListener(v -> main.openFriendChat(msg.getSenderId()));

            holder.profilePic.setOnLongClickListener(v -> {
                main.showEditFriendDialogForSender(msg.getSenderId());
                return true;
            });
        }
        // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

        String rawMessage = msg.getMessage();

        // NORMAL MESSAGE (not payload) - e.g., "Receiving...", "Failed..."
        if ((!msg.isComplete() || msg.isFailed()) &&
                !rawMessage.startsWith("[u>") &&
                !rawMessage.startsWith("[m>") &&
                !rawMessage.startsWith("[v>")) {

            bindText(holder, rawMessage);
            bindImages(holder, "", false);
            bindVideos(holder, "");

            return; // ✅ প্রোফাইল পিক লোড হয়ে গেছে, তাই return করা নিরাপদ
        }

        // ---------------------------------------------------------
        // Payload Parsed
        // ---------------------------------------------------------
        PayloadCompress.ParsedPayload parsed = PayloadCompress.parsePayload(rawMessage);

        // ▼▼▼ START: NEW JSON FETCHING LOGIC ▼▼▼

        // Check if this is a JSON fetch request
        if (JsonFetcher.isJsonUrl(parsed.message) &&
                parsed.imageUrls.isEmpty() &&
                parsed.videoUrls.isEmpty()) {

            final String gUrl = parsed.message;
            // ট্যাগ সেট করুন, যাতে রিসাইক্লিং চেক করা যায়
            holder.itemView.setTag(gUrl);

            // লোডিং অবস্থা দেখান
            bindText(holder, "Loading JSON...");
            bindImages(holder, "", false);
            bindVideos(holder, "");

            JsonFetcher.fetchJson(context, gUrl, new JsonFetcher.JsonCallback() {
                @Override
                public void onSuccess(JsonFetcher.ParsedJson fetchedData) {
                    if (context instanceof Activity) {
                        ((Activity) context).runOnUiThread(() -> {
                            // হোল্ডারটি রিসাইকেল হয়েছে কিনা চেক করুন
                            if (!gUrl.equals(holder.itemView.getTag())) {
                                return; // রিসাইকেল হয়ে গেছে, আপডেট করবেন না
                            }
                            // ফেচ করা ডেটা বাইন্ড করুন
                            bindText(holder, fetchedData.message);
                            bindImages(holder, fetchedData.images, msg.isSelf());
                            bindVideos(holder, fetchedData.videos);
                        });
                    }
                }

                @Override
                public void onError(String error) { // <--- 'error' স্ট্রিংটি এখানে আসে
                    if (context instanceof Activity) {
                        ((Activity) context).runOnUiThread(() -> {
                            // হোল্ডারটি রিসাইকেল হয়েছে কিনা চেক করুন
                            if (!gUrl.equals(holder.itemView.getTag())) {
                                return; // রিসাইকেল হয়ে গেছে, আপডেট করবেন না
                            }

                            String displayError = "Failed to load JSON: " + error;
                            bindText(holder, displayError);

                            bindImages(holder, "", false);
                            bindVideos(holder, "");
                        });
                    }
                }
            });

        } else {
            // এটি একটি সাধারণ মেসেজ, সরাসরি ডেটা বাইন্ড করুন
            holder.itemView.setTag(null); // ট্যাগ ক্লিয়ার করুন
            bindText(holder, parsed.message);
            bindImages(holder, parsed.imageUrls, msg.isSelf());
            bindVideos(holder, parsed.videoUrls);
        }
        // ▲▲▲ END: NEW JSON FETCHING LOGIC ▲▲▲


        // ---------------------------------------------------------
        // CLICK LISTENERS
        // ---------------------------------------------------------
        holder.itemView.setOnClickListener(v -> clickListener.onClick(msg));
        holder.itemView.setOnLongClickListener(v -> {
            longClickListener.onClick(msg);
            return true;
        });
    }

    // ▼▼▼ START: NEW HELPER METHODS ▼▼▼

    /**
     * Binds text content to the message TextView.
     */
    private void bindText(ChatViewHolder holder, String message) {
        if (message != null && !message.isEmpty()) {
            holder.message.setVisibility(View.VISIBLE);

            // Clean excessive newlines
            String cleanedMessage = removeExcessiveNewlines(message);
            // Truncate if needed
            String displayMessage = truncateMessage(cleanedMessage);
            holder.message.setText(displayMessage);

            // Click to view full message
            if (cleanedMessage.length() > MAX_MESSAGE_LENGTH) {
                final String fullMessage = cleanedMessage;
                holder.message.setOnClickListener(v -> showFullMessageDialog(fullMessage));
            } else {
                holder.message.setOnClickListener(null); // Remove previous listener
            }

        } else {
            holder.message.setVisibility(View.GONE);
        }
    }

    /**
     * Binds image URLs to the image container.
     */
    private void bindImages(ChatViewHolder holder, String imageUrls, boolean isSelf) {
        if (holder.imageContainer == null) return;

        if (imageUrls != null && !imageUrls.isEmpty()) {
            // Check if view is already built for this set of URLs
            String currentImageTag = (String) holder.imageContainer.getTag(R.id.tag_image_urls);
            if (!imageUrls.equals(currentImageTag)) {
                holder.imageContainer.removeAllViews();
                ArrayList<String> urls = new ArrayList<>();
                for (String u : imageUrls.split(",")) {
                    if (!u.trim().isEmpty()) urls.add(u.trim());
                }
                if (!urls.isEmpty()) {
                    setupImageViews(holder.imageContainer, urls, isSelf);
                }
                holder.imageContainer.setTag(R.id.tag_image_urls, imageUrls);
            }
            holder.imageContainer.setVisibility(View.VISIBLE);
        } else {
            // No images, clean up
            holder.imageContainer.setTag(R.id.tag_image_urls, null);
            holder.imageContainer.setVisibility(View.GONE);
        }
    }

    /**
     * Binds video URLs to the video container.
     */
    private void bindVideos(ChatViewHolder holder, String videoUrls) {
        if (holder.videoContainer == null) return;

        if (videoUrls != null && !videoUrls.isEmpty()) {
            // Check if view is already built for this set of URLs
            String currentVideoTag = (String) holder.videoContainer.getTag(R.id.tag_video_urls);
            if (!videoUrls.equals(currentVideoTag)) {
                holder.videoContainer.removeAllViews();
                ArrayList<String> urls = new ArrayList<>();
                for (String u : videoUrls.split(",")) {
                    if (!u.trim().isEmpty()) urls.add(u.trim());
                }
                if (!urls.isEmpty()) {
                    setupVideoViews(holder.videoContainer, urls);
                }
                holder.videoContainer.setTag(R.id.tag_video_urls, videoUrls);
            }
            holder.videoContainer.setVisibility(View.VISIBLE);
        } else {
            // No videos, clean up
            holder.videoContainer.setTag(R.id.tag_video_urls, null);
            holder.videoContainer.setVisibility(View.GONE);
        }
    }

    // ▲▲▲ END: NEW HELPER METHODS ▲▲▲


    private String removeExcessiveNewlines(String text) {
        if (text == null) return "";
        // Replace 4 or more consecutive newlines with just 3
        return text.replaceAll("\n{4,}", "\n\n\n");
    }

    /**
     * Truncates text to MAX_MESSAGE_LENGTH and adds "...(Click to View Full)"
     */
    private String truncateMessage(String text) {
        if (text == null || text.length() <= MAX_MESSAGE_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_MESSAGE_LENGTH) + "\n........(Click to View Full)";
    }

    /**
     * Shows full message in a custom dialog
     */
    private void showFullMessageDialog(String fullText) {
        // Create dialog
        Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_full_message);

        // Make dialog background transparent with rounded corners
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            );
            dialog.getWindow().setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT
            );

            // Add margin
            android.view.WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
            params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT;
            params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            dialog.getWindow().setAttributes(params);
        }

        // Set full text
        TextView fullMessageText = dialog.findViewById(R.id.fullMessageText);
        if (fullMessageText != null) {
            fullMessageText.setText(fullText);

            // Enable links
            fullMessageText.setAutoLinkMask(android.text.util.Linkify.ALL);
            fullMessageText.setLinksClickable(true);
            fullMessageText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }

        // Close button (TextView style) - with null check
        TextView btnClose = dialog.findViewById(R.id.btnClose);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        // Close icon - with null check
        ImageView btnCloseIcon = dialog.findViewById(R.id.btnCloseIcon);
        if (btnCloseIcon != null) {
            btnCloseIcon.setOnClickListener(v -> dialog.dismiss());
        }

        // If both are null, allow dismissing by touching outside
        if (btnClose == null && btnCloseIcon == null) {
            dialog.setCanceledOnTouchOutside(true);
        }

        dialog.show();
    }

    private void setupVideoViews(LinearLayout videoContainer, ArrayList<String> videoUrls) {
        videoContainer.setOrientation(LinearLayout.HORIZONTAL);
        ImageCacheManager cacheManager = ImageCacheManager.getInstance(context); // Cache manager instance

        int maxDisplayVideos = 3;
        int totalVideos = videoUrls.size();
        int displayCount = Math.min(totalVideos, maxDisplayVideos);

        for (int i = 0; i < displayCount; i++) {
            final int currentIndex = i;
            String url = videoUrls.get(i);

            if (i == maxDisplayVideos - 1 && totalVideos > maxDisplayVideos) {
                // LAST VIDEO WITH OVERLAY (+N)
                FrameLayout overlayContainer = new FrameLayout(context);
                LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(dpToPx(60), dpToPx(60));
                overlayContainer.setLayoutParams(containerParams);

                ImageView imageView = new ImageView(context);
                FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );
                imageView.setLayoutParams(imageParams);
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                imageView.setBackgroundColor(0xFF000000); // Black background

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    overlayContainer.setClipToOutline(true);
                    overlayContainer.setOutlineProvider(new android.view.ViewOutlineProvider() {
                        @Override
                        public void getOutline(android.view.View view, android.graphics.Outline outline) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dpToPx(8));
                        }
                    });
                }
                View scrimView = new View(context);
                FrameLayout.LayoutParams scrimParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );
                scrimView.setLayoutParams(scrimParams);
                scrimView.setBackgroundColor(0x80000000);
                TextView countText = new TextView(context);
                FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                );
                textParams.gravity = android.view.Gravity.CENTER;
                countText.setLayoutParams(textParams);
                countText.setTextColor(0xFFFFFFFF);
                countText.setTextSize(16);
                countText.setTypeface(null, android.graphics.Typeface.BOLD);

                int remaining = totalVideos - (maxDisplayVideos - 1);
                countText.setText(String.format("+%d", remaining));

                overlayContainer.addView(imageView);
                overlayContainer.addView(scrimView);
                overlayContainer.addView(countText);

                // ▼▼▼ ei line-ti add kora hoyeche (image grid-er moto) ▼▼▼
                loadVideoThumbnail(imageView, url, false, null);
                // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

                // ei click-er target thik ache - openVideoViewer
                overlayContainer.setOnClickListener(v -> openVideoViewer(videoUrls, currentIndex));
                videoContainer.addView(overlayContainer);

            } else {
                // NORMAL VIDEO THUMBNAIL (Item 1 or 2)
                FrameLayout videoFrame = new FrameLayout(context);
                LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(dpToPx(60), dpToPx(60));
                if (i < displayCount - 1) {
                    frameParams.setMarginEnd(dpToPx(6));
                }
                videoFrame.setLayoutParams(frameParams);
                videoFrame.setBackgroundResource(R.drawable.bg_round_image);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    videoFrame.setClipToOutline(true);
                    videoFrame.setOutlineProvider(new android.view.ViewOutlineProvider() {
                        @Override
                        public void getOutline(android.view.View view, android.graphics.Outline outline) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dpToPx(8));
                        }
                    });
                }

                ImageView thumbnailView = new ImageView(context);
                FrameLayout.LayoutParams thumbParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );
                thumbnailView.setLayoutParams(thumbParams);
                thumbnailView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnailView.setBackgroundColor(0xFF000000);

                ImageView playIcon = new ImageView(context);
                FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(dpToPx(30), dpToPx(30));
                playParams.gravity = android.view.Gravity.CENTER;
                playIcon.setLayoutParams(playParams);
                playIcon.setImageResource(R.drawable.ic_play_video);
                playIcon.setVisibility(View.VISIBLE);

                android.widget.ProgressBar progressBar = new android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
                FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                );
                progressParams.gravity = android.view.Gravity.CENTER;
                progressBar.setLayoutParams(progressParams);
                progressBar.setVisibility(View.GONE);

                videoFrame.addView(thumbnailView);
                videoFrame.addView(playIcon);
                videoFrame.addView(progressBar);

                // --- Cache check logic (oporibortito) ---
                Bitmap cachedBitmap = cacheManager.getBitmap(url + "_video_thumb", true);

                if (cachedBitmap != null) {
                    thumbnailView.setImageBitmap(cachedBitmap);
                    playIcon.setVisibility(View.VISIBLE);
                    progressBar.setVisibility(View.GONE);
                    videoFrame.setOnClickListener(v -> {
                        openVideoPlayer(url);
                    });
                } else {
                    thumbnailView.setBackgroundColor(0xFF000000);
                    playIcon.setVisibility(View.VISIBLE);
                    progressBar.setVisibility(View.GONE);
                    videoFrame.setOnClickListener(v -> {
                        openVideoPlayer(url);
                        playIcon.setVisibility(View.GONE);
                        progressBar.setVisibility(View.VISIBLE);
                        loadVideoThumbnail(thumbnailView, url, false, () -> {
                            progressBar.setVisibility(View.GONE);
                        });
                    });
                }
                videoContainer.addView(videoFrame);
            }
        }
    }


    private void loadVideoThumbnail(ImageView thumbnailView, String videoUrl, boolean andPlay, Runnable onComplete) {
        ImageCacheManager cacheManager = ImageCacheManager.getInstance(context);
        Bitmap cachedBitmap = cacheManager.getBitmap(videoUrl + "_video_thumb", true);

        if (cachedBitmap != null) {
            thumbnailView.setImageBitmap(cachedBitmap);
            if (andPlay) {
                openVideoPlayer(videoUrl);
            }
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        // Not cached, load in background
        executor.execute(() -> {
            try {
                String fullUrl = videoUrl;
                if (!fullUrl.startsWith("http://") && !fullUrl.startsWith("https://")) {
                    fullUrl = "https://" + fullUrl;
                }

                android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                retriever.setDataSource(fullUrl, new HashMap<>());
                Bitmap frame = retriever.getFrameAtTime(1000000); // 1 second
                retriever.release();

                if (frame != null) {
                    Bitmap thumbnail = ImageConverter.resizeAndCrop(frame, dpToPx(60), dpToPx(60));
                    cacheManager.putBitmap(videoUrl + "_video_thumb", thumbnail, true);

                    ((Activity) context).runOnUiThread(() -> {
                        thumbnailView.setImageBitmap(thumbnail);
                        if (andPlay) {
                            openVideoPlayer(videoUrl);
                        }
                    });
                } else {
                    // Load ব্যর্থ হলেও, andPlay true থাকলে চালানোর চেষ্টা করুন
                    if (andPlay) {
                        ((Activity) context).runOnUiThread(() -> {
                            openVideoPlayer(videoUrl);
                        });
                    }
                }
            } catch (Exception e) {
                Log.e("ChatAdapter", "Error loading video thumbnail", e);
                // Load ব্যর্থ হলেও, andPlay true থাকলে চালানোর চেষ্টা করুন
                if (andPlay) {
                    ((Activity) context).runOnUiThread(() -> {
                        openVideoPlayer(videoUrl);
                    });
                }
            } finally {
                if (onComplete != null) {
                    ((Activity) context).runOnUiThread(onComplete);
                }
            }
        });
    }

    private void openVideoViewer(ArrayList<String> urls, int position) {
        Intent intent = new Intent(context, VideoViewerActivity.class);
        intent.putStringArrayListExtra("video_urls", urls);
        intent.putExtra("start_position", position);
        context.startActivity(intent);
    }

    // Add this method to ChatAdapter.java

    private void openVideoPlayer(String videoUrl) {
        try {
            String fullUrl = videoUrl;
            if (!fullUrl.startsWith("http://") && !fullUrl.startsWith("https://")) {
                fullUrl = "https://" + fullUrl;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(fullUrl), "video/*");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (intent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(Intent.createChooser(intent, "Play video with"));
            } else {
                Toast.makeText(context, "No video player found", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e("ChatAdapter", "Error opening video player", e);
            Toast.makeText(context, "Error playing video", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupImageViews(LinearLayout imageContainer, final ArrayList<String> imageUrls, boolean isSelf) {
        imageContainer.setOrientation(LinearLayout.HORIZONTAL);
        ImageCacheManager cacheManager = ImageCacheManager.getInstance(context);

        // ✅ CHECK SETTINGS
        SharedPreferences prefs = context.getSharedPreferences("NearbyChatSettings", Context.MODE_PRIVATE);
        boolean autoLoadThumbnails = prefs.getBoolean("AUTO_IMAGE_THUMBNAILS", true);

        int maxDisplayImages = 3;
        int totalImages = imageUrls.size();
        int displayCount = Math.min(totalImages, maxDisplayImages);

        for (int i = 0; i < displayCount; i++) {
            final int currentIndex = i;
            String url = imageUrls.get(i);

            if (i == maxDisplayImages - 1 && totalImages > maxDisplayImages) {
                // ✅ LAST IMAGE WITH OVERLAY (+N)
                FrameLayout overlayContainer = new FrameLayout(context);
                LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(dpToPx(60), dpToPx(60));
                overlayContainer.setLayoutParams(containerParams);

                ImageView imageView = new ImageView(context);
                FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );
                imageView.setLayoutParams(imageParams);
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    overlayContainer.setClipToOutline(true);
                    overlayContainer.setOutlineProvider(new android.view.ViewOutlineProvider() {
                        @Override
                        public void getOutline(android.view.View view, android.graphics.Outline outline) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dpToPx(8));
                        }
                    });
                }

                View scrimView = new View(context);
                FrameLayout.LayoutParams scrimParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );
                scrimView.setLayoutParams(scrimParams);
                scrimView.setBackgroundColor(0x80000000);

                TextView countText = new TextView(context);
                FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                );
                textParams.gravity = android.view.Gravity.CENTER;
                countText.setLayoutParams(textParams);
                countText.setTextColor(0xFFFFFFFF);
                countText.setTextSize(16);
                countText.setTypeface(null, android.graphics.Typeface.BOLD);

                int remaining = totalImages - (maxDisplayImages - 1);
                countText.setText(String.format("+%d", remaining));

                overlayContainer.addView(imageView);
                overlayContainer.addView(scrimView);
                overlayContainer.addView(countText);

                // ✅ CONDITIONAL LOADING FOR +N IMAGE
                if (autoLoadThumbnails) {
                    // Auto-load ON: Load thumbnail immediately
                    loadImageThumbnail(imageView, url, url);
                    overlayContainer.setOnClickListener(v -> openImageViewer(imageUrls, currentIndex));

                } else {
                    // Auto-load OFF: Show placeholder
                    imageView.setBackgroundColor(0xFF2D3748);

                    // ✅ Click: Load thumbnail + Open viewer
                    overlayContainer.setOnClickListener(v -> {
                        // Check if already cached
                        Bitmap cachedBitmap = cacheManager.getBitmap(url, true);

                        if (cachedBitmap != null) {
                            // Already cached, just open viewer
                            openImageViewer(imageUrls, currentIndex);
                        } else {
                            // Not cached, load it in background
                            imageView.setBackgroundColor(0xFF4A5568); // Loading state
                            loadImageThumbnail(imageView, url, url);

                            // Open viewer immediately (viewer will load full image)
                            openImageViewer(imageUrls, currentIndex);
                        }
                    });
                }

                imageContainer.addView(overlayContainer);

            } else {
                // NORMAL IMAGE THUMBNAIL (1st and 2nd images)
                ImageView imageView = new ImageView(context);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dpToPx(60), dpToPx(60));
                if (i < displayCount - 1) {
                    params.setMarginEnd(dpToPx(6));
                }
                imageView.setLayoutParams(params);
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                imageView.setBackgroundResource(R.drawable.bg_round_image);

                imageView.setClipToOutline(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    imageView.setOutlineProvider(new android.view.ViewOutlineProvider() {
                        @Override
                        public void getOutline(android.view.View view, android.graphics.Outline outline) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dpToPx(8));
                        }
                    });
                }

                // ✅ CHECK IF AUTO-LOAD IS ENABLED
                if (autoLoadThumbnails) {
                    // OLD BEHAVIOR: Load immediately
                    Bitmap cachedBitmap = cacheManager.getBitmap(url, true);
                    if (cachedBitmap != null) {
                        imageView.setImageBitmap(cachedBitmap);
                    } else {
                        imageView.setBackgroundColor(0xFF2D3748);
                        loadImageThumbnail(imageView, url, url);
                    }
                    imageView.setOnClickListener(v -> openImageViewer(imageUrls, currentIndex));

                } else {
                    // ✅ NEW BEHAVIOR: Don't load, show placeholder with icon
                    imageView.setBackgroundColor(0xFF2D3748);

                    imageView.setImageResource(R.drawable.image);
                    imageView.setColorFilter(0x80FFFFFF);

                    imageView.setOnClickListener(v -> {
                        imageView.setImageDrawable(null);
                        imageView.clearColorFilter();

                        Bitmap cachedBitmap = cacheManager.getBitmap(url, true);
                        if (cachedBitmap != null) {
                            openImageViewer(imageUrls, currentIndex);
                        } else {
                            imageView.setBackgroundColor(0xFF4A5568);
                            loadImageThumbnail(imageView, url, url);

                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                openImageViewer(imageUrls, currentIndex);
                            }, 500);
                        }
                    });
                }

                imageContainer.addView(imageView);
            }
        }
    }

    private void openImageViewer(ArrayList<String> urls, int position) {
        Intent intent = new Intent(context, ImageViewer.class);
        intent.putStringArrayListExtra("image_urls", urls);
        intent.putExtra("start_position", position);
        context.startActivity(intent);
    }

    private void loadImageThumbnail(ImageView imageView, String url, String cacheKey) {
        ImageCacheManager cacheManager = ImageCacheManager.getInstance(context);

        Bitmap cachedBitmap = cacheManager.getBitmap(url, true);
        if (cachedBitmap != null) {
            imageView.setImageBitmap(cachedBitmap);
            return;
        }
        executor.execute(() -> {
            try {
                String fullUrl = url;
                if (!fullUrl.startsWith("http://") && !fullUrl.startsWith("https://")) {
                    fullUrl = "https://" + fullUrl;
                }
                URL imageUrl = new URL(fullUrl);
                HttpURLConnection connection = (HttpURLConnection) imageUrl.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                Bitmap bitmap = BitmapFactory.decodeStream(connection.getInputStream());
                if (bitmap != null) {
                    Bitmap thumbnail = ImageConverter.resizeAndCrop(bitmap, dpToPx(60), dpToPx(60));

                    cacheManager.putBitmap(url, thumbnail, true);

                    ((Activity) context).runOnUiThread(() -> {
                        imageView.setImageBitmap(thumbnail);
                    });
                }
            } catch (Exception e) {
                Log.e("ChatAdapter", "Error loading image thumbnail", e);
            }
        });
    }

    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
    }

    @Override
    public int getItemViewType(int position) {
        return messageList.get(position).isSelf() ? 1 : 0;
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        TextView senderId, message, timestamp;
        ImageView profilePic;
        LinearLayout imageContainer;
        LinearLayout videoContainer;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            senderId = itemView.findViewById(R.id.textUserId);
            message = itemView.findViewById(R.id.textMessage);
            timestamp = itemView.findViewById(R.id.textTimestamp);
            profilePic = itemView.findViewById(R.id.profilePicRound);
            imageContainer = itemView.findViewById(R.id.imageContainer);
            videoContainer = itemView.findViewById(R.id.videoContainer);
        }
    }
}