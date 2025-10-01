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

public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.ViewHolder> {

    private final Context context;
    private List<GroupsFriendsActivity.ChatItem> chatItems;
    private final OnChatClickListener clickListener;
    private final OnChatLongClickListener longClickListener;
    private String activeChatType;
    private String activeChatId;

    public interface OnChatClickListener {
        void onChatClick(GroupsFriendsActivity.ChatItem chat);
    }

    public interface OnChatLongClickListener {
        void onChatLongClick(GroupsFriendsActivity.ChatItem chat);
    }

    public ChatListAdapter(Context context, List<GroupsFriendsActivity.ChatItem> chatItems,
                           OnChatClickListener clickListener, OnChatLongClickListener longClickListener,
                           String activeChatType, String activeChatId) {
        this.context = context;
        this.chatItems = chatItems;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
        // ADD THESE
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
        GroupsFriendsActivity.ChatItem item = chatItems.get(position);
        holder.itemName.setText(item.name);
        holder.itemLastMessage.setText(item.lastMessage);
        holder.itemTime.setText(item.lastMessageTime);

        boolean isSelected = item.type.equals(activeChatType) && item.id.equals(activeChatId);
        if (isSelected) {
            holder.itemView.setBackgroundColor(0xFF2196F3); // Blue background
            holder.itemName.setTextColor(0xFFFFFFFF); // White text
            holder.itemLastMessage.setTextColor(0xFFFFFFFF);
            holder.itemTime.setTextColor(0xFFFFFFFF);
        } else {
            holder.itemView.setBackgroundColor(0xFFFFFFFF); // White background
            holder.itemName.setTextColor(0xFF000000); // Black text
            holder.itemLastMessage.setTextColor(0xFF000000);
            holder.itemTime.setTextColor(0xFF888888); // Gray text
        }

        holder.itemView.setOnClickListener(v -> clickListener.onChatClick(item));
        holder.itemView.setOnLongClickListener(v -> {
            longClickListener.onChatLongClick(item);
            return true;
        });

        // **FIX START**: Use the new ProfilePicLoader to avoid ClassCastException.
        if ("N".equals(item.type)) {
            holder.profilePic.setImageResource(R.drawable.nearby);
        } else if ("G".equals(item.type)) {
            // CHANGE THIS: Use loadGroupProfilePicture for groups
            long bits = asciiIdToTimestamp(item.id);
            String displayId = BleMessagingService.timestampToDisplayId(bits);
            ProfilePicLoader.loadGroupProfilePicture(context, displayId, holder.profilePic);
        } else if ("F".equals(item.type) && item.displayId != null && !item.displayId.isEmpty()) {
            ProfilePicLoader.loadProfilePicture(context, item.displayId, holder.profilePic);
        } else {
            holder.profilePic.setImageResource(R.drawable.profile_pic_round_vector);
        }
    }



    @Override
    public int getItemCount() {
        return chatItems.size();
    }

    public void updateList(List<GroupsFriendsActivity.ChatItem> newList, String activeChatType, String activeChatId) {
        chatItems = newList;
        this.activeChatType = activeChatType;
        this.activeChatId = activeChatId;
        notifyDataSetChanged();
    }

    private long asciiIdToTimestamp(String asciiId) {
        if (asciiId == null || asciiId.length() != 5) return 0;
        long bits40 = 0;
        for (int i = 0; i < 5; i++) {
            bits40 = (bits40 << 8) | (asciiId.charAt(i) & 0xFF);
        }
        return bits40;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView profilePic;
        TextView itemName, itemLastMessage, itemTime;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            profilePic = itemView.findViewById(R.id.profilePic);
            itemName = itemView.findViewById(R.id.itemName);
            itemLastMessage = itemView.findViewById(R.id.itemLastMessage);
            itemTime = itemView.findViewById(R.id.itemTime);
        }
    }
}