package com.antor.nearbychat.Message;

import android.content.Context;
import com.antor.nearbychat.CryptoUtils;
import com.antor.nearbychat.MessageModel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MessageConverterForBle {

    private final int MAX_PAYLOAD_SIZE;
    private static final int USER_ID_LENGTH = 5;
    private static final int MESSAGE_ID_LENGTH = 5;
    private static final int CHAT_TYPE_LENGTH = 1;
    private static final int CHUNK_METADATA_LENGTH = 2;
    private static final int CHAT_ID_LENGTH = 5;

    private static final int BASE_HEADER_SIZE = CHAT_TYPE_LENGTH + USER_ID_LENGTH + MESSAGE_ID_LENGTH + CHUNK_METADATA_LENGTH;

    private static final int FIRST_CHUNK_GF_HEADER_SIZE = BASE_HEADER_SIZE + CHAT_ID_LENGTH;

    private final Context context;
    private final String messageText;
    private final String chatType;
    private final String chatId;
    private final String senderDisplayId;
    private final long senderIdBits;
    private long existingMessageIdBits = -1;

    private MessageModel messageToSave;
    private List<byte[]> blePacketsToSend;

    public MessageConverterForBle(Context context, String messageText, String chatType, String chatId, String senderDisplayId, long senderIdBits, int maxPayloadSize) {
        this.context = context;
        this.messageText = messageText;
        this.chatType = chatType;
        this.chatId = chatId;
        this.senderDisplayId = senderDisplayId;
        this.senderIdBits = senderIdBits;
        this.MAX_PAYLOAD_SIZE = maxPayloadSize;
    }

    public MessageConverterForBle(Context context, MessageModel modelToRetransmit, int maxPayloadSize) {
        this.context = context;
        this.messageText = modelToRetransmit.getMessage();
        this.chatType = modelToRetransmit.getChatType();
        this.chatId = modelToRetransmit.getChatId();
        this.senderDisplayId = modelToRetransmit.getSenderId();
        this.senderIdBits = modelToRetransmit.getSenderTimestampBits();
        this.existingMessageIdBits = modelToRetransmit.getMessageTimestampBits();
        this.MAX_PAYLOAD_SIZE = maxPayloadSize;
    }

    public void process() {
        long messageIdBits;
        if (existingMessageIdBits != -1) {
            messageIdBits = existingMessageIdBits;
        } else {
            messageIdBits = System.currentTimeMillis() & ((1L << 40) - 1);
        }

        String messageAsciiId = MessageHelper.timestampToAsciiId(messageIdBits);
        String senderAsciiId = MessageHelper.timestampToAsciiId(senderIdBits);

        String messagePayload;
        if ("N".equals(chatType)) {
            messagePayload = messageText;
        } else {
            String password = MessageHelper.getPasswordForChat(context, chatType, chatId, senderDisplayId);
            messagePayload = CryptoUtils.encrypt(messageText, password);
        }

        byte[] messageBytes = messagePayload.getBytes(StandardCharsets.UTF_8);

        int firstChunkDataSize;
        int nextChunkDataSize;

        if ("N".equals(chatType)) {
            firstChunkDataSize = MAX_PAYLOAD_SIZE - BASE_HEADER_SIZE;
            nextChunkDataSize = MAX_PAYLOAD_SIZE - BASE_HEADER_SIZE;
        } else {
            firstChunkDataSize = MAX_PAYLOAD_SIZE - FIRST_CHUNK_GF_HEADER_SIZE;
            nextChunkDataSize = MAX_PAYLOAD_SIZE - BASE_HEADER_SIZE;
        }
        List<byte[]> dataChunks = createVariableSizeChunks(messageBytes, firstChunkDataSize, nextChunkDataSize);
        int totalChunks = dataChunks.size();

        if (existingMessageIdBits == -1) {
            this.messageToSave = new MessageModel(
                    senderDisplayId,
                    messageText,
                    true,
                    createFormattedTimestamp(totalChunks, messageIdBits),
                    senderIdBits,
                    messageIdBits
            );
            this.messageToSave.setMessageId(MessageHelper.timestampToDisplayId(messageIdBits));
            this.messageToSave.setChatType(chatType);
            this.messageToSave.setChatId(chatId);
        }

        this.blePacketsToSend = new ArrayList<>();
        String paddedChatId = String.format("%-5s", chatId).substring(0, 5);

        for (int i = 0; i < totalChunks; i++) {
            byte[] chunkData = dataChunks.get(i);
            boolean isFirstChunk = (i == 0);

            byte[] blePacket;
            if ("N".equals(chatType)) {
                blePacket = createNearbyChatPacket(chatType, senderAsciiId, messageAsciiId, i, totalChunks, chunkData);
            } else if (isFirstChunk) {
                blePacket = createFirstChunkGFPacket(chatType, senderAsciiId, messageAsciiId, i, totalChunks, paddedChatId, chunkData);
            } else {
                blePacket = createNextChunkGFPacket(chatType, senderAsciiId, messageAsciiId, i, totalChunks, chunkData);
            }
            this.blePacketsToSend.add(blePacket);
        }
    }

    public MessageModel getMessageToSave() {
        return messageToSave;
    }

    public List<byte[]> getBlePacketsToSend() {
        return blePacketsToSend;
    }

    private byte[] createNearbyChatPacket(String chatType, String senderAsciiId, String messageAsciiId,
                                          int chunkIndex, int totalChunks, byte[] chunkData) {
        byte[] packet = new byte[BASE_HEADER_SIZE + chunkData.length];
        int offset = 0;

        packet[offset++] = (byte) chatType.charAt(0);
        System.arraycopy(senderAsciiId.getBytes(StandardCharsets.ISO_8859_1), 0, packet, offset, USER_ID_LENGTH);
        offset += USER_ID_LENGTH;
        System.arraycopy(messageAsciiId.getBytes(StandardCharsets.ISO_8859_1), 0, packet, offset, MESSAGE_ID_LENGTH);
        offset += MESSAGE_ID_LENGTH;
        packet[offset++] = (byte) totalChunks;
        packet[offset++] = (byte) chunkIndex;
        System.arraycopy(chunkData, 0, packet, offset, chunkData.length);

        return packet;
    }

    private byte[] createFirstChunkGFPacket(String chatType, String senderAsciiId, String messageAsciiId,
                                            int chunkIndex, int totalChunks, String paddedChatId, byte[] chunkData) {
        byte[] packet = new byte[FIRST_CHUNK_GF_HEADER_SIZE + chunkData.length];
        int offset = 0;

        packet[offset++] = (byte) chatType.charAt(0);
        System.arraycopy(senderAsciiId.getBytes(StandardCharsets.ISO_8859_1), 0, packet, offset, USER_ID_LENGTH);
        offset += USER_ID_LENGTH;
        System.arraycopy(messageAsciiId.getBytes(StandardCharsets.ISO_8859_1), 0, packet, offset, MESSAGE_ID_LENGTH);
        offset += MESSAGE_ID_LENGTH;
        packet[offset++] = (byte) totalChunks;
        packet[offset++] = (byte) chunkIndex;
        System.arraycopy(paddedChatId.getBytes(StandardCharsets.ISO_8859_1), 0, packet, offset, CHAT_ID_LENGTH);
        offset += CHAT_ID_LENGTH;
        System.arraycopy(chunkData, 0, packet, offset, chunkData.length);

        return packet;
    }

    private byte[] createNextChunkGFPacket(String chatType, String senderAsciiId, String messageAsciiId,
                                           int chunkIndex, int totalChunks, byte[] chunkData) {
        byte[] packet = new byte[BASE_HEADER_SIZE + chunkData.length];
        int offset = 0;

        packet[offset++] = (byte) chatType.charAt(0);
        System.arraycopy(senderAsciiId.getBytes(StandardCharsets.ISO_8859_1), 0, packet, offset, USER_ID_LENGTH);
        offset += USER_ID_LENGTH;
        System.arraycopy(messageAsciiId.getBytes(StandardCharsets.ISO_8859_1), 0, packet, offset, MESSAGE_ID_LENGTH);
        offset += MESSAGE_ID_LENGTH;
        packet[offset++] = (byte) totalChunks;
        packet[offset++] = (byte) chunkIndex;
        System.arraycopy(chunkData, 0, packet, offset, chunkData.length);

        return packet;
    }

    private List<byte[]> createVariableSizeChunks(byte[] data, int firstChunkSize, int nextChunkSize) {
        List<byte[]> chunks = new ArrayList<>();
        if (data.length == 0) {
            chunks.add(new byte[0]);
            return chunks;
        }

        int offset = 0;
        boolean isFirstChunk = true;

        while (offset < data.length) {
            int chunkSize;
            if (isFirstChunk) {
                chunkSize = Math.min(firstChunkSize, data.length - offset);
                isFirstChunk = false;
            } else {
                chunkSize = Math.min(nextChunkSize, data.length - offset);
            }

            byte[] chunk = new byte[chunkSize];
            System.arraycopy(data, offset, chunk, 0, chunkSize);
            chunks.add(chunk);
            offset += chunkSize;
        }
        return chunks;
    }

    private String createFormattedTimestamp(int chunkCount, long messageIdBits) {
        long fullTimestamp = MessageHelper.reconstructFullTimestamp(messageIdBits);
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a | dd-MM-yyyy", Locale.getDefault());
        String baseTime = sdf.format(new Date(fullTimestamp));
        return baseTime + " | " + chunkCount + "C";
    }
}