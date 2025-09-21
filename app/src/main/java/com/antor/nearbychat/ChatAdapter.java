package com.antor.nearbychat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private List<MessageModel> messageList;
    private Context context;
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
        holder.message.setText(msg.getMessage());
        holder.timestamp.setText(msg.getTimestamp());

        // Load profile picture if exists
        if (holder.profilePic != null) {
            main.loadProfilePictureForAdapter(msg.getSenderId(), holder.profilePic);
        }

        holder.itemView.setOnClickListener(v -> clickListener.onClick(msg));
        holder.itemView.setOnLongClickListener(v -> {
            longClickListener.onClick(msg);
            return true;
        });
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