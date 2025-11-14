package com.antor.nearbychat;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
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
                public void onError(String error) {
                    if (context instanceof Activity) {
                        ((Activity) context).runOnUiThread(() -> {
                            // হোল্ডারটি রিসাইকেল হয়েছে কিনা চেক করুন
                            if (!gUrl.equals(holder.itemView.getTag())) {
                                return; // রিসাইকেল হয়ে গেছে, আপডেট করবেন না
                            }
                            bindText(holder, "[Failed to load JSON]");
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
        int columns = 3;
        int totalVideos = videoUrls.size();
        int rows = (int) Math.ceil(totalVideos / (double) columns);

        for (int row = 0; row < rows; row++) {
            LinearLayout rowLayout = new LinearLayout(context);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            for (int col = 0; col < columns; col++) {
                int index = row * columns + col;
                if (index >= totalVideos) break;

                final String videoUrl = videoUrls.get(index);

                FrameLayout videoFrame = new FrameLayout(context);
                LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(dpToPx(60), dpToPx(60));
                if (col < columns - 1 && index < totalVideos - 1) {
                    frameParams.setMarginEnd(dpToPx(6));
                }
                if (row < rows - 1) {
                    frameParams.bottomMargin = dpToPx(6);
                }
                videoFrame.setLayoutParams(frameParams);

                View blackBg = new View(context);
                FrameLayout.LayoutParams bgParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );
                blackBg.setLayoutParams(bgParams);
                blackBg.setBackgroundColor(0xFF000000);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    videoFrame.setClipToOutline(true);
                    videoFrame.setOutlineProvider(new android.view.ViewOutlineProvider() {
                        @Override
                        public void getOutline(android.view.View view, android.graphics.Outline outline) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dpToPx(8));
                        }
                    });
                }
                ImageView playIcon = new ImageView(context);
                FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(
                        dpToPx(30),
                        dpToPx(30)
                );
                playParams.gravity = android.view.Gravity.CENTER;
                playIcon.setLayoutParams(playParams);
                playIcon.setImageResource(R.drawable.ic_play_video);
                playIcon.setColorFilter(null); // Remove color filter to use original drawable colors

                videoFrame.addView(blackBg);
                videoFrame.addView(playIcon);

                videoFrame.setOnClickListener(v -> openVideoPlayer(videoUrl));
                rowLayout.addView(videoFrame);
            }
            videoContainer.addView(rowLayout);
        }
    }

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
        int maxDisplayImages = 3;
        int totalImages = imageUrls.size();
        int displayCount = Math.min(totalImages, maxDisplayImages);

        for (int i = 0; i < displayCount; i++) {
            final int currentIndex = i;
            String url = imageUrls.get(i);

            if (i == maxDisplayImages - 1 && totalImages > maxDisplayImages) {
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

                loadImageThumbnail(imageView, url, url);
                overlayContainer.setOnClickListener(v -> openImageViewer(imageUrls, currentIndex));
                imageContainer.addView(overlayContainer);

            } else {
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
                loadImageThumbnail(imageView, url, url);
                imageView.setOnClickListener(v -> openImageViewer(imageUrls, currentIndex));
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