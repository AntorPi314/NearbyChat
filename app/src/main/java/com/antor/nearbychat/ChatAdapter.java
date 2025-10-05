package com.antor.nearbychat;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.antor.nearbychat.Message.MessageHelper;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private List<MessageModel> messageList;
    private final Context context;
    private final MessageClickListener clickListener;
    private final MessageClickListener longClickListener;

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
        holder.timestamp.setText(msg.getTimestamp());

        String displayMessage = msg.getMessage();
        holder.itemView.setBackgroundColor(Color.TRANSPARENT);

        if (msg.isSelf()) {
            if (msg.isFailed()) {
                holder.message.setTextColor(Color.parseColor("#CC0000"));
                holder.message.setTypeface(null, Typeface.NORMAL);
                holder.itemView.setBackgroundColor(Color.parseColor("#FFCCCC"));
                holder.message.setText(displayMessage);
            } else {
                holder.message.setTextColor(Color.WHITE);
                holder.message.setTypeface(null, Typeface.NORMAL);
                holder.message.setText(displayMessage);
            }
        }
        else {
            if (msg.isFailed()) {
                holder.message.setText("Failed");
                holder.message.setTextColor(Color.parseColor("#999999")); // Grey text
                holder.message.setTypeface(null, Typeface.ITALIC);
                holder.itemView.setBackgroundColor(Color.parseColor("#F5F5F5")); // Very light grey background
            } else if (!msg.isComplete()) {
                holder.message.setText(displayMessage);
                holder.message.setTextColor(Color.GRAY);
                holder.message.setTypeface(null, Typeface.ITALIC);
                holder.itemView.setBackgroundColor(Color.TRANSPARENT);
            } else {
                holder.message.setTextColor(Color.BLACK);
                holder.message.setTypeface(null, Typeface.NORMAL);
                holder.itemView.setBackgroundColor(Color.TRANSPARENT);
                holder.message.setText(displayMessage);
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

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    @Override
    public int getItemViewType(int position) {
        return messageList.get(position).isSelf() ? 1 : 0;
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