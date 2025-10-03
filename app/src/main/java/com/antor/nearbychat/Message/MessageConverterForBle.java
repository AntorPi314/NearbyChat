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
    private static final int CHUNK_METADATA_LENGTH = 2;
    private static final int HEADER_SIZE = USER_ID_LENGTH + MESSAGE_ID_LENGTH + CHUNK_METADATA_LENGTH;
    private final int MAX_CHUNK_DATA_SIZE;

    private final Context context;
    private final String messageText;
    private final String chatType;
    private final String chatId;
    private final String senderDisplayId;
    private final long senderIdBits;

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
        this.MAX_CHUNK_DATA_SIZE = MAX_PAYLOAD_SIZE - HEADER_SIZE;
    }

    public void process() {
        String password = MessageHelper.getPasswordForChat(context, chatType, chatId, senderDisplayId);

        String encryptedMessage = CryptoUtils.encrypt(messageText, password);
        String paddedChatId = String.format("%-5s", chatId).substring(0, 5);
        String messagePayload = chatType + paddedChatId + encryptedMessage;

        long messageIdBits = System.currentTimeMillis() & ((1L << 40) - 1);
        String messageAsciiId = MessageHelper.timestampToAsciiId(messageIdBits);

        byte[] messageBytes = messagePayload.getBytes(StandardCharsets.UTF_8);
        List<byte[]> dataChunks = createSafeUtf8Chunks(messageBytes, MAX_CHUNK_DATA_SIZE);
        int totalChunks = dataChunks.size();

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

        this.blePacketsToSend = new ArrayList<>();
        String senderAsciiId = MessageHelper.timestampToAsciiId(senderIdBits);
        for (int i = 0; i < totalChunks; i++) {
            byte[] chunkData = dataChunks.get(i);
            byte[] blePacket = createBlePacketPayload(senderAsciiId, messageAsciiId, i, totalChunks, chunkData);
            this.blePacketsToSend.add(blePacket);
        }
    }

    public MessageModel getMessageToSave() {
        return messageToSave;
    }

    public List<byte[]> getBlePacketsToSend() {
        return blePacketsToSend;
    }

    private byte[] createBlePacketPayload(String senderAsciiId, String messageAsciiId, int chunkIndex, int totalChunks, byte[] chunkData) {
        byte[] senderIdBytes = senderAsciiId.getBytes(StandardCharsets.ISO_8859_1);
        byte[] messageIdBytes = messageAsciiId.getBytes(StandardCharsets.ISO_8859_1);

        byte[] payload = new byte[HEADER_SIZE + chunkData.length];

        System.arraycopy(senderIdBytes, 0, payload, 0, USER_ID_LENGTH);
        System.arraycopy(messageIdBytes, 0, payload, USER_ID_LENGTH, MESSAGE_ID_LENGTH);
        payload[USER_ID_LENGTH + MESSAGE_ID_LENGTH] = (byte) chunkIndex;
        payload[USER_ID_LENGTH + MESSAGE_ID_LENGTH + 1] = (byte) totalChunks;
        System.arraycopy(chunkData, 0, payload, HEADER_SIZE, chunkData.length);

        return payload;
    }

    private List<byte[]> createSafeUtf8Chunks(byte[] data, int maxChunkSize) {
        List<byte[]> chunks = new ArrayList<>();
        if (data.length == 0) {
            chunks.add(new byte[0]);
            return chunks;
        }
        int offset = 0;
        while (offset < data.length) {
            int chunkSize = Math.min(maxChunkSize, data.length - offset);
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