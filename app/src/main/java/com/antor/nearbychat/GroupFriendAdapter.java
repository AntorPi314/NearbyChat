package com.antor.nearbychat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class GroupFriendAdapter extends RecyclerView.Adapter<GroupFriendAdapter.ViewHolder> {

    public enum ListType { GROUPS, FRIENDS }

    private Context context;
    private List<?> items;
    private ListType listType;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(int position);
        void onEditClick(int position);
    }

    public GroupFriendAdapter(Context context, List<?> items, ListType listType, OnItemClickListener listener) {
        this.context = context;
        this.items = items;
        this.listType = listType;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_group_friend, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position), listener, position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        ImageView profilePic;
        TextView itemName;
        ImageView editIcon;
        LinearLayout itemContainer;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            profilePic = itemView.findViewById(R.id.profilePic);
            itemName = itemView.findViewById(R.id.itemName);
            editIcon = itemView.findViewById(R.id.editIcon);
            itemContainer = itemView.findViewById(R.id.item_container);
        }

        void bind(final Object item, final OnItemClickListener listener, final int position) {
            if (listType == ListType.GROUPS) {
                GroupModel group = (GroupModel) item;
                itemName.setText(group.getName());
                if (group.getId().isEmpty()) { // Nearby Chat
                    profilePic.setImageResource(R.drawable.nearby);
                    editIcon.setVisibility(View.GONE); // Can't edit Nearby Chat
                } else {
                    profilePic.setImageResource(R.drawable.profile_pic_round_vector);
                    editIcon.setVisibility(View.VISIBLE);
                }
            } else { // FRIENDS
                FriendModel friend = (FriendModel) item;
                itemName.setText(friend.getName());
                profilePic.setImageResource(R.drawable.profile_pic_round_vector);
                editIcon.setVisibility(View.VISIBLE);
            }

            itemContainer.setOnClickListener(v -> listener.onItemClick(position));
            editIcon.setOnClickListener(v -> listener.onEditClick(position));
        }
    }
}