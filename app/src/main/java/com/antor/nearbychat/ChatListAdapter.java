package com.antor.nearbychat;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.antor.nearbychat.Message.MessageHelper;
import java.util.List;

public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.ChatViewHolder> {

    private final Context context;
    private List<GroupsFriendsActivity.ChatItem> chatList;
    private final OnChatClickListener clickListener;
    private final OnChatClickListener longClickListener;
    private String activeChatType;
    private String activeChatId;

    public interface OnChatClickListener {
        void onChatClick(GroupsFriendsActivity.ChatItem chat);
    }

    public ChatListAdapter(Context context, List<GroupsFriendsActivity.ChatItem> chatList, OnChatClickListener clickListener, OnChatClickListener longClickListener, String activeChatType, String activeChatId) {
        this.context = context;
        this.chatList = chatList;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
        this.activeChatType = activeChatType;
        this.activeChatId = activeChatId;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_chat_list, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        GroupsFriendsActivity.ChatItem chat = chatList.get(position);

        holder.chatName.setText(chat.name);
        holder.lastMessage.setText(chat.lastMessage);
        holder.timestamp.setText(chat.lastMessageTime);

        if ("N".equals(chat.type)) {
            holder.profilePic.setImageResource(R.drawable.nearby);
        } else if ("G".equals(chat.type)) {
            long bits = MessageHelper.asciiIdToTimestamp(chat.id);
            String displayId = MessageHelper.timestampToDisplayId(bits);
            ProfilePicLoader.loadGroupProfilePicture(context, displayId, holder.profilePic);
        } else if ("F".equals(chat.type)) {
            ProfilePicLoader.loadProfilePicture(context, chat.displayId, holder.profilePic);
        }

        boolean isActive = chat.type.equals(activeChatType) && chat.id.equals(activeChatId);
        if (isActive) {
            holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.active_chat_background));
            holder.chatName.setTypeface(null, Typeface.BOLD);
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT);
            holder.chatName.setTypeface(null, Typeface.NORMAL);
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

    public void updateList(List<GroupsFriendsActivity.ChatItem> newList, String newActiveChatType, String newActiveChatId) {
        this.chatList = newList;
        this.activeChatType = newActiveChatType;
        this.activeChatId = newActiveChatId;
        notifyDataSetChanged();
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        ImageView profilePic;
        TextView chatName, lastMessage, timestamp;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            profilePic = itemView.findViewById(R.id.profilePic);
            // Corrected IDs to match common naming conventions and fix the crash
            chatName = itemView.findViewById(R.id.textChatName);
            lastMessage = itemView.findViewById(R.id.textLastMessage);
            timestamp = itemView.findViewById(R.id.textTime);
        }
    }
}