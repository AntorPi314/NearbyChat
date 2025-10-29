package com.antor.nearbychat;

import android.app.Activity;
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

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private final List<MessageModel> messageList;
    private final Context context;
    private final MessageClickListener clickListener;
    private final MessageClickListener longClickListener;
    private final Map<String, Bitmap> imageCache = new HashMap<>();
    private static ExecutorService sharedExecutor;
    private final ExecutorService executor;

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

        String displayName = main.getDisplayName(msg.getSenderId());
        holder.senderId.setText(displayName.isEmpty() ? msg.getSenderId() : displayName);
        holder.timestamp.setText(msg.getTimestamp());

        String rawMessage = msg.getMessage();

        if ((!msg.isComplete() || msg.isFailed()) && !rawMessage.startsWith("[u>") && !rawMessage.startsWith("[m>") && !rawMessage.startsWith("[v>")) {
            if (!rawMessage.isEmpty()) {
                holder.message.setVisibility(View.VISIBLE);
                holder.message.setText(rawMessage);
            } else {
                holder.message.setVisibility(View.GONE);
            }
            if (holder.imageContainer != null) {
                holder.imageContainer.setVisibility(View.GONE);
            }
            if (holder.videoContainer != null) {
                holder.videoContainer.setVisibility(View.GONE);
            }
            holder.itemView.setBackgroundColor(Color.TRANSPARENT);
            if (msg.isSelf()) {
                holder.message.setTextColor(msg.isFailed() ? Color.parseColor("#CC0000") : Color.WHITE);
                holder.itemView.setBackgroundColor(msg.isFailed() ? Color.parseColor("#FFCCCC") : Color.TRANSPARENT);
            } else {
                holder.message.setTextColor(msg.isFailed() ? Color.parseColor("#CC0000") : Color.BLACK);
                if (msg.isFailed()) {
                    holder.itemView.setBackgroundColor(Color.parseColor("#FFEEEE"));
                }
            }
        } else {
            PayloadCompress.ParsedPayload parsed = PayloadCompress.parsePayload(rawMessage);

            if (!parsed.message.isEmpty()) {
                holder.message.setVisibility(View.VISIBLE);
                holder.message.setText(parsed.message);
            } else {
                holder.message.setVisibility(View.GONE);
            }
            holder.itemView.setBackgroundColor(Color.TRANSPARENT);
            if (msg.isSelf()) {
                holder.message.setTextColor(Color.WHITE);
            } else {
                holder.message.setTextColor(Color.BLACK);
            }
            if (holder.imageContainer != null) {
                if (!parsed.imageUrls.isEmpty()) {
                    Log.d("ChatAdapter", "📸 Image URLs found: " + parsed.imageUrls); // Debug line

                    String[] urls = parsed.imageUrls.split(",");
                    ArrayList<String> imageUrls = new ArrayList<>();
                    for (String url : urls) {
                        String trimmed = url.trim();
                        if (!trimmed.isEmpty()) {
                            imageUrls.add(trimmed);
                            Log.d("ChatAdapter", "  ✓ Added: " + trimmed); // Debug line
                        }
                    }

                    if (!imageUrls.isEmpty()) {
                        holder.imageContainer.setTag(parsed.imageUrls);
                        holder.imageContainer.setVisibility(View.VISIBLE);
                        holder.imageContainer.removeAllViews();
                        setupImageViews(holder.imageContainer, imageUrls, msg.isSelf());
                        Log.d("ChatAdapter", "✓ Showing " + imageUrls.size() + " images"); // Debug line
                    } else {
                        holder.imageContainer.setTag(null);
                        holder.imageContainer.setVisibility(View.GONE);
                        Log.d("ChatAdapter", "✗ No valid image URLs after splitting"); // Debug line
                    }
                } else {
                    holder.imageContainer.setTag(null);
                    holder.imageContainer.setVisibility(View.GONE);
                    Log.d("ChatAdapter", "✗ No image URLs in parsed payload"); // Debug line
                }
            }
            if (holder.videoContainer != null) {
                if (!parsed.videoUrls.isEmpty()) {
                    Log.d("ChatAdapter", "🎬 Video URLs found: " + parsed.videoUrls); // Debug line

                    String[] urls = parsed.videoUrls.split(",");
                    ArrayList<String> videoUrls = new ArrayList<>();
                    for (String url : urls) {
                        String trimmed = url.trim();
                        if (!trimmed.isEmpty()) {
                            videoUrls.add(trimmed);
                            Log.d("ChatAdapter", "  ✓ Added: " + trimmed); // Debug line
                        }
                    }

                    if (!videoUrls.isEmpty()) {
                        holder.videoContainer.setTag(parsed.videoUrls);
                        holder.videoContainer.setVisibility(View.VISIBLE);
                        holder.videoContainer.removeAllViews();
                        setupVideoViews(holder.videoContainer, videoUrls);
                        Log.d("ChatAdapter", "✓ Showing " + videoUrls.size() + " videos"); // Debug line
                    } else {
                        holder.videoContainer.setTag(null);
                        holder.videoContainer.setVisibility(View.GONE);
                        Log.d("ChatAdapter", "✗ No valid video URLs after splitting"); // Debug line
                    }
                } else {
                    holder.videoContainer.setTag(null);
                    holder.videoContainer.setVisibility(View.GONE);
                    Log.d("ChatAdapter", "✗ No video URLs in parsed payload"); // Debug line
                }
            }
        }
        if (holder.profilePic != null) {
            main.loadProfilePictureForAdapter(msg.getSenderId(), holder.profilePic);
            holder.profilePic.setOnClickListener(v -> main.openFriendChat(msg.getSenderId()));
        }
        holder.itemView.setOnLongClickListener(v -> {
            longClickListener.onClick(msg);
            return true;
        });
        holder.itemView.setOnClickListener(v -> clickListener.onClick(msg));
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
        LinearLayout videoContainer;  // ADD THIS LINE

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            senderId = itemView.findViewById(R.id.textUserId);
            message = itemView.findViewById(R.id.textMessage);
            timestamp = itemView.findViewById(R.id.textTimestamp);
            profilePic = itemView.findViewById(R.id.profilePicRound);
            imageContainer = itemView.findViewById(R.id.imageContainer);
            videoContainer = itemView.findViewById(R.id.videoContainer);  // ADD THIS LINE
        }
    }
}