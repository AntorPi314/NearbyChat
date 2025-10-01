// Updated ChatAdapter.java with click listeners

package com.antor.nearbychat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private List<MessageModel> messageList;
    private Context context;
    private final MessageClickListener clickListener;
    private final MessageClickListener longClickListener;

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray();

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

        holder.senderId.setText(main.getDisplayName(msg.getSenderId()));
        holder.message.setText(msg.getMessage());
        holder.timestamp.setText(msg.getTimestamp());

        // Load profile picture if exists
        if (holder.profilePic != null) {
            main.loadProfilePictureForAdapter(msg.getSenderId(), holder.profilePic);

            // Click on profile picture to show account creation time
            holder.profilePic.setOnClickListener(v -> {
//                long fullTimestamp = reconstructFullTimestamp(msg.getSenderTimestampBits());
//                String formattedTime = formatTimestamp(fullTimestamp);
//                Toast.makeText(context, "Account Created at: " + formattedTime, Toast.LENGTH_LONG).show();
                ((MainActivity) context).openFriendChat(msg.getSenderId());
            });
        }

        // Click on timestamp to show message send time
        holder.timestamp.setOnClickListener(v -> {
            long fullTimestamp = reconstructFullTimestamp(msg.getMessageTimestampBits());
            String formattedTime = formatTimestamp(fullTimestamp);
            Toast.makeText(context, "Sent at: " + formattedTime, Toast.LENGTH_LONG).show();
        });

        // Only long click on message text triggers options
        holder.message.setOnLongClickListener(v -> {
            longClickListener.onClick(msg);
            return true;
        });

        // Regular click on message (for incomplete messages)
        holder.message.setOnClickListener(v -> clickListener.onClick(msg));
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    @Override
    public int getItemViewType(int position) {
        return messageList.get(position).isSelf() ? 1 : 0;
    }

    // Helper methods
    private long reconstructFullTimestamp(long timestampBits40) {
        long currentMs = System.currentTimeMillis();
        long currentHigh = currentMs & ~((1L << 40) - 1);
        return currentHigh | timestampBits40;
    }

    private String formatTimestamp(long timestampMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a | dd-MM-yyyy", Locale.getDefault());
        return sdf.format(new Date(timestampMs));
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        TextView senderId, message, timestamp;
        ImageView profilePic;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            senderId = itemView.findViewById(R.id.textUserId);
            message = itemView.findViewById(R.id.textMessage);
            timestamp = itemView.findViewById(R.id.textTimestamp);
            profilePic = itemView.findViewById(R.id.profilePicRound);
        }
    }
}