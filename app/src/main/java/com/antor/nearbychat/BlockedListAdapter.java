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
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class BlockedListAdapter extends RecyclerView.Adapter<BlockedListAdapter.ViewHolder> {

    private Context context;
    private List<BlockActivity.BlockedChatItem> chatList;
    private OnChatClickListener clickListener;
    private OnChatClickListener longClickListener;

    private String activeChatType;
    private String activeChatId;

    public interface OnChatClickListener {
        void onChatClick(BlockActivity.BlockedChatItem chat);
    }

    public BlockedListAdapter(Context context, List<BlockActivity.BlockedChatItem> chatList,
                              OnChatClickListener clickListener, OnChatClickListener longClickListener,
                              String activeChatType, String activeChatId) {
        this.context = context;
        this.chatList = chatList;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
        this.activeChatType = activeChatType;
        this.activeChatId = activeChatId;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_chat_list, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BlockActivity.BlockedChatItem chat = chatList.get(position);

        holder.chatName.setText(chat.name);
        holder.lastMessage.setText(chat.lastMessage);
        holder.timestamp.setText(chat.lastMessageTime);

        ProfilePicLoader.loadProfilePicture(context, chat.displayId, holder.profilePic);

        boolean isActive = chat.type.equals(activeChatType) && chat.id.equals(activeChatId);
        if (isActive) {
            holder.itemView.setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.active_chat_background));
        } else {
            holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }
        holder.itemView.setOnClickListener(v -> clickListener.onChatClick(chat));
        holder.itemView.setOnLongClickListener(v -> {
            longClickListener.onChatClick(chat);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return chatList.size();
    }

    public void updateList(List<BlockActivity.BlockedChatItem> newList, String newActiveChatType, String newActiveChatId) {
        this.chatList = newList;
        this.activeChatType = newActiveChatType;
        this.activeChatId = newActiveChatId;
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView profilePic;
        TextView chatName, lastMessage, timestamp;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            profilePic = itemView.findViewById(R.id.profilePic);
            chatName = itemView.findViewById(R.id.textChatName);
            lastMessage = itemView.findViewById(R.id.textLastMessage);
            timestamp = itemView.findViewById(R.id.textTime);
        }
    }
}