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

    public interface OnChatClickListener {
        void onChatClick(GroupsFriendsActivity.ChatItem chat);
    }

    public interface OnChatLongClickListener {
        void onChatLongClick(GroupsFriendsActivity.ChatItem chat);
    }

    public ChatListAdapter(Context context, List<GroupsFriendsActivity.ChatItem> chatItems,
                           OnChatClickListener clickListener, OnChatLongClickListener longClickListener) {
        this.context = context;
        this.chatItems = chatItems;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
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

        holder.itemView.setOnClickListener(v -> clickListener.onChatClick(item));
        holder.itemView.setOnLongClickListener(v -> {
            longClickListener.onChatLongClick(item);
            return true;
        });

        // **FIX START**: Use the new ProfilePicLoader to avoid ClassCastException.
        if ("N".equals(item.type)) {
            holder.profilePic.setImageResource(R.drawable.nearby);
        } else if ("G".equals(item.type)) {
            // Placeholder for group icon, or you can implement custom group pics later
            holder.profilePic.setImageResource(R.drawable.profile_pic_round_vector);
        } else if ("F".equals(item.type) && item.displayId != null && !item.displayId.isEmpty()) {
            ProfilePicLoader.loadProfilePicture(context, item.displayId, holder.profilePic);
        } else {
            holder.profilePic.setImageResource(R.drawable.profile_pic_round_vector);
        }
        // **FIX END**
    }

    @Override
    public int getItemCount() {
        return chatItems.size();
    }

    public void updateList(List<GroupsFriendsActivity.ChatItem> newList) {
        chatItems = newList;
        notifyDataSetChanged();
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