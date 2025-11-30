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
import com.antor.nearbychat.Message.MessageHelper;

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
        String myUserId = main.getCurrentUserId();

        if (msg.isSelf()) {
            if (msg.isFailed()) {
                holder.message.setBackgroundResource(R.drawable.bubble_right_red);
            } else {
                holder.message.setBackgroundResource(R.drawable.bubble_right);
            }
        }

        String senderName = main.getDisplayName(msg.getSenderId());

        holder.senderId.setTextColor(Color.parseColor("#555555"));

        if (msg.isSelf() && msg.isAcknowledged()) {
            holder.senderId.setTextColor(Color.parseColor("#0D80E0"));
        }

        if (msg.isReply()) {
            holder.senderId.setCompoundDrawablesWithIntrinsicBounds(R.drawable.reply, 0, 0, 0);
            holder.senderId.setCompoundDrawablePadding(8);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                holder.senderId.setCompoundDrawableTintList(
                        android.content.res.ColorStateList.valueOf(
                                msg.isSelf() && msg.isAcknowledged()
                                        ? Color.parseColor("#0D80E0")
                                        : Color.parseColor("#888888")
                        )
                );
            }

            String replyToUserId = msg.getReplyToUserId();
            String replyToName = main.getDisplayName(replyToUserId);

            if (msg.isSelf()) {
                if (replyToUserId.equals(myUserId)) {
                    holder.senderId.setText("You replied to yourself");
                } else {
                    holder.senderId.setText("You replied to " + replyToName);
                }
            } else {
                if (replyToUserId.equals(myUserId)) {
                    holder.senderId.setText(senderName + " replied to you");
                    holder.senderId.setTextColor(Color.parseColor("#0D80E0"));
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        holder.senderId.setCompoundDrawableTintList(
                                android.content.res.ColorStateList.valueOf(Color.parseColor("#0D80E0"))
                        );
                    }
                } else if (replyToUserId.equals(msg.getSenderId())) {
                    holder.senderId.setText(senderName + " replied to themself");
                } else {
                    holder.senderId.setText(senderName + " replied to " + replyToName);
                }
            }
        } else {
            holder.senderId.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            holder.senderId.setText(senderName.isEmpty() ? msg.getSenderId() : senderName);
        }

        if (!msg.getSenderId().equals(holder.itemView.getTag(R.id.tag_sender_id))) {
            holder.itemView.setTag(R.id.tag_sender_id, msg.getSenderId());
        }

        holder.timestamp.setText(msg.getTimestamp());

        if (holder.profilePic != null) {
            main.loadProfilePictureForAdapter(msg.getSenderId(), holder.profilePic);
            holder.profilePic.setOnClickListener(v -> main.openFriendChat(msg.getSenderId()));
            holder.profilePic.setOnLongClickListener(v -> {
                main.showEditFriendDialogForSender(msg.getSenderId());
                return true;
            });
        }

        if (msg.isReply() && holder.replyContainer != null) {
            String existingPreview = msg.getReplyToMessagePreview();
            boolean isLoading = existingPreview == null || existingPreview.isEmpty() || existingPreview.startsWith("Loading");

            if (isLoading) {
                holder.replyText.setText("Loading reply...");
                holder.replyContainer.setVisibility(View.VISIBLE);
                executor.execute(() -> {
                    try {
                        AppDatabase db = AppDatabase.getInstance(context);
                        com.antor.nearbychat.Database.MessageEntity entity =
                                db.messageDao().getMessageById(msg.getReplyToMessageId());
                        String foundPreview = "Message unavailable";
                        if (entity != null) {
                            PayloadCompress.ParsedPayload parsed = PayloadCompress.parsePayload(entity.message);
                            foundPreview = parsed.message;
                            if (foundPreview.isEmpty() && (!parsed.imageUrls.isEmpty() || !parsed.videoUrls.isEmpty())) {
                                foundPreview = "Media";
                            }
                            if (foundPreview.length() > 60) foundPreview = foundPreview.substring(0, 60) + "...";
                        }
                        String finalPreview = foundPreview;
                        ((Activity) context).runOnUiThread(() -> {
                            if (msg.getReplyToMessageId().equals(msg.getReplyToMessageId())) {
                                holder.replyText.setText(finalPreview);
                                msg.setReplyToMessagePreview(finalPreview);
                            }
                        });
                    } catch (Exception e) {
                        Log.e("ChatAdapter", "Reply load error", e);
                    }
                });
            } else {
                holder.replyText.setText(existingPreview);
                holder.replyContainer.setVisibility(View.VISIBLE);
            }
            holder.replyContainer.setOnClickListener(v -> {
                String targetMsgId = msg.getReplyToMessageId();
                int targetPos = -1;
                for (int i = 0; i < messageList.size(); i++) {
                    if (messageList.get(i).getMessageId().equals(targetMsgId)) {
                        targetPos = i;
                        break;
                    }
                }
                if (targetPos != -1) {
                    if (context instanceof MainActivity) {
                        ((MainActivity) context).scrollToAndHighlight(targetPos);
                    }
                } else {
                    Toast.makeText(context, "Original message not found nearby", Toast.LENGTH_SHORT).show();
                }
            });
        } else if (holder.replyContainer != null) {
            holder.replyContainer.setVisibility(View.GONE);
        }

        String rawMessage = msg.getMessage();
        if (msg.isFailed() && !"N".equals(msg.getChatType())) {
            try {
                String password = MessageHelper.getPasswordForChat(context, msg.getChatType(), msg.getChatId(), myUserId);
                String decrypted = CryptoUtils.decrypt(rawMessage, password);
                if (decrypted != null && !decrypted.isEmpty()) {
                    rawMessage = decrypted;
                }
            } catch (Exception e) {
                // Decryption failed, show raw
            }
        }

        if (!msg.isComplete() && !rawMessage.startsWith("[u>") && !rawMessage.startsWith("[m>") && !rawMessage.startsWith("[v>")) {
            bindText(holder, rawMessage);
            bindImages(holder, "", false);
            bindVideos(holder, "");
            holder.message.setOnLongClickListener(v -> {
                longClickListener.onClick(msg);
                return true;
            });
            return;
        }

        PayloadCompress.ParsedPayload parsed = PayloadCompress.parsePayload(rawMessage);

        if (JsonFetcher.isJsonUrl(parsed.message) && parsed.imageUrls.isEmpty() && parsed.videoUrls.isEmpty()) {
            final String gUrl = parsed.message;
            holder.itemView.setTag(gUrl);
            bindText(holder, "Loading JSON...");
            bindImages(holder, "", false);
            bindVideos(holder, "");

            JsonFetcher.fetchJson(context, gUrl, new JsonFetcher.JsonCallback() {
                @Override
                public void onSuccess(JsonFetcher.ParsedJson fetchedData) {
                    if (context instanceof Activity) {
                        ((Activity) context).runOnUiThread(() -> {
                            if (!gUrl.equals(holder.itemView.getTag())) return;
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
                            if (!gUrl.equals(holder.itemView.getTag())) return;
                            bindText(holder, "Failed: " + error);
                        });
                    }
                }
            });
        } else {
            holder.itemView.setTag(null);
            bindText(holder, parsed.message);
            bindImages(holder, parsed.imageUrls, msg.isSelf());
            bindVideos(holder, parsed.videoUrls);
        }

        holder.itemView.setOnClickListener(v -> clickListener.onClick(msg));
        holder.itemView.setOnLongClickListener(v -> {
            longClickListener.onClick(msg);
            return true;
        });
        if (holder.message != null) {
            holder.message.setOnLongClickListener(v -> {
                longClickListener.onClick(msg);
                return true;
            });
        }
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        TextView senderId, message, timestamp;
        ImageView profilePic;
        LinearLayout imageContainer;
        LinearLayout videoContainer;
        LinearLayout replyContainer;
        TextView replyText;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            senderId = itemView.findViewById(R.id.textUserId);
            message = itemView.findViewById(R.id.textMessage);
            timestamp = itemView.findViewById(R.id.textTimestamp);
            profilePic = itemView.findViewById(R.id.profilePicRound);
            imageContainer = itemView.findViewById(R.id.imageContainer);
            videoContainer = itemView.findViewById(R.id.videoContainer);
            replyContainer = itemView.findViewById(R.id.replyContainer);
            replyText = itemView.findViewById(R.id.replyText);
        }
    }

    /**
     * Binds image URLs to the image container.
     */
    private void bindImages(ChatViewHolder holder, String imageUrls, boolean isSelf) {
        if (holder.imageContainer == null) return;

        if (imageUrls != null && !imageUrls.isEmpty()) {
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
            holder.imageContainer.setTag(R.id.tag_image_urls, null);
            holder.imageContainer.setVisibility(View.GONE);
        }
    }

    private void bindVideos(ChatViewHolder holder, String videoUrls) {
        if (holder.videoContainer == null) return;

        if (videoUrls != null && !videoUrls.isEmpty()) {
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
            holder.videoContainer.setTag(R.id.tag_video_urls, null);
            holder.videoContainer.setVisibility(View.GONE);
        }
    }

    private String removeExcessiveNewlines(String text) {
        if (text == null) return "";
        return text.replaceAll("\n{4,}", "\n\n\n");
    }

    private String truncateMessage(String text) {
        if (text == null || text.length() <= MAX_MESSAGE_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_MESSAGE_LENGTH) + "\n........(Click to View Full)";
    }

    private void showFullMessageDialog(String fullText) {
        Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_full_message);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            );
            dialog.getWindow().setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT
            );
            android.view.WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
            params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT;
            params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            dialog.getWindow().setAttributes(params);
        }

        TextView fullMessageText = dialog.findViewById(R.id.fullMessageText);
        if (fullMessageText != null) {
            fullMessageText.setText(fullText);

            fullMessageText.setAutoLinkMask(android.text.util.Linkify.ALL);
            fullMessageText.setLinksClickable(true);
            fullMessageText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }

        TextView btnClose = dialog.findViewById(R.id.btnClose);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        ImageView btnCloseIcon = dialog.findViewById(R.id.btnCloseIcon);
        if (btnCloseIcon != null) {
            btnCloseIcon.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnClose == null && btnCloseIcon == null) {
            dialog.setCanceledOnTouchOutside(true);
        }

        dialog.show();
    }

    private void setupVideoViews(LinearLayout videoContainer, ArrayList<String> videoUrls) {
        videoContainer.setOrientation(LinearLayout.HORIZONTAL);
        ImageCacheManager cacheManager = ImageCacheManager.getInstance(context);

        int maxDisplayVideos = 3;
        int totalVideos = videoUrls.size();
        int displayCount = Math.min(totalVideos, maxDisplayVideos);

        for (int i = 0; i < displayCount; i++) {
            final int currentIndex = i;
            String url = videoUrls.get(i);

            if (i == maxDisplayVideos - 1 && totalVideos > maxDisplayVideos) {
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
                imageView.setBackgroundColor(0xFF000000);

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

                loadVideoThumbnail(imageView, url, false, null);

                overlayContainer.setOnClickListener(v -> openVideoViewer(videoUrls, currentIndex));
                videoContainer.addView(overlayContainer);

            } else {
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

        executor.execute(() -> {
            try {
                String fullUrl = videoUrl;
                if (!fullUrl.startsWith("http://") && !fullUrl.startsWith("https://")) {
                    fullUrl = "https://" + fullUrl;
                }

                android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                retriever.setDataSource(fullUrl, new HashMap<>());
                Bitmap frame = retriever.getFrameAtTime(1000000);
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
                    if (andPlay) {
                        ((Activity) context).runOnUiThread(() -> {
                            openVideoPlayer(videoUrl);
                        });
                    }
                }
            } catch (Exception e) {
                Log.e("ChatAdapter", "Error loading video thumbnail", e);
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

        SharedPreferences prefs = context.getSharedPreferences("NearbyChatSettings", Context.MODE_PRIVATE);
        boolean autoLoadThumbnails = prefs.getBoolean("AUTO_IMAGE_THUMBNAILS", true);

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

                if (autoLoadThumbnails) {
                    loadImageThumbnail(imageView, url, url);
                    overlayContainer.setOnClickListener(v -> openImageViewer(imageUrls, currentIndex));

                } else {
                    imageView.setBackgroundColor(0xFF2D3748);

                    overlayContainer.setOnClickListener(v -> {
                        Bitmap cachedBitmap = cacheManager.getBitmap(url, true);

                        if (cachedBitmap != null) {
                            openImageViewer(imageUrls, currentIndex);
                        } else {
                            imageView.setBackgroundColor(0xFF4A5568);
                            loadImageThumbnail(imageView, url, url);

                            openImageViewer(imageUrls, currentIndex);
                        }
                    });
                }
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
                if (autoLoadThumbnails) {
                    Bitmap cachedBitmap = cacheManager.getBitmap(url, true);
                    if (cachedBitmap != null) {
                        imageView.setImageBitmap(cachedBitmap);
                    } else {
                        imageView.setBackgroundColor(0xFF2D3748);
                        loadImageThumbnail(imageView, url, url);
                    }
                    imageView.setOnClickListener(v -> openImageViewer(imageUrls, currentIndex));

                } else {
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

    private void bindText(ChatViewHolder holder, String message) {
        if (message != null && !message.isEmpty()) {
            holder.message.setVisibility(View.VISIBLE);

            String cleanedMessage = removeExcessiveNewlines(message);
            String displayMessage = truncateMessage(cleanedMessage);
            holder.message.setText(displayMessage);

            if (cleanedMessage.length() > MAX_MESSAGE_LENGTH) {
                final String fullMessage = cleanedMessage;
                holder.message.setOnClickListener(v -> showFullMessageDialog(fullMessage));
            } else {
                holder.message.setOnClickListener(null);
            }

        } else {
            holder.message.setVisibility(View.GONE);
        }
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

}