package com.antor.sosblue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

public class MessageAdapter extends ArrayAdapter<Message> {
    public MessageAdapter(Context context, List<Message> messages) {
        super(context, 0, messages);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Message message = getItem(position);

        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(
                    message.isSent() ? R.layout.item_message_sent : R.layout.item_message_received,
                    parent, false);
        }

        TextView content = convertView.findViewById(R.id.messageContent);
        TextView sender = convertView.findViewById(R.id.messageSender);

        content.setText(message.getContent());
        sender.setText(message.getSender());

        return convertView;
    }
}