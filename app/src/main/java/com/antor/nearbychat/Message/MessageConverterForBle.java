package com.antor.nearbychat.Message;

import android.content.Context;
import android.util.Log;

import com.antor.nearbychat.CryptoUtils;
import com.antor.nearbychat.MessageModel;
import com.antor.nearbychat.PayloadCompress;

import java.nio.charset.Charset;
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
    private final String chatType;
    private final String chatId;
    private final String senderDisplayId;
    private final long senderIdBits;
    private long existingMessageIdBits = -1;

    private final String payloadToSend;

    private MessageModel messageToSave;
    private List<byte[]> blePacketsToSend;
    private static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;

    public MessageConverterForBle(Context context, String payloadToSend, String chatType,
                                  String chatId, String senderDisplayId, long senderIdBits,
                                  int maxPayloadSize) {
        this.context = context;
        this.payloadToSend = payloadToSend;
        this.chatType = chatType;
        this.chatId = chatId;
        this.senderDisplayId = senderDisplayId;
        this.senderIdBits = senderIdBits;
        this.MAX_PAYLOAD_SIZE = maxPayloadSize;
    }

    public MessageConverterForBle(Context context, MessageModel modelToRetransmit, int maxPayloadSize) {
        this.context = context;
        this.payloadToSend = modelToRetransmit.getMessage();
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

        String rUserId = "";
        String rMsgId = "";
        boolean isReplyDetected = false;
        String tempPayload = payloadToSend;

        if (tempPayload.startsWith("[r>") && tempPayload.length() >= 13) {
            try {
                String replyUserAscii = tempPayload.substring(3, 8);
                String replyMsgAscii = tempPayload.substring(8, 13);

                rUserId = MessageHelper.timestampToDisplayId(MessageHelper.asciiIdToTimestamp(replyUserAscii));
                rMsgId = MessageHelper.timestampToDisplayId(MessageHelper.asciiIdToTimestamp(replyMsgAscii));

                tempPayload = tempPayload.substring(13);
                isReplyDetected = true;
            } catch (Exception e) {
                Log.e("MsgConverter", "Error parsing reply", e);
            }
        }

        int msgTypeId = 0;
        String contentToEncrypt = tempPayload;

        if (contentToEncrypt.startsWith("g//")) {
            String urlPart = contentToEncrypt.substring(3);

            String compressed5Bit = PayloadCompress.compressJsonUrl5Bit(urlPart);
            int size5Bit = compressed5Bit != null ? compressed5Bit.getBytes(ISO_8859_1).length : Integer.MAX_VALUE;

            String simplified = PayloadCompress.simplifyLinks(urlPart);
            String compressed6Bit = null;
            int size6Bit = Integer.MAX_VALUE;

            try {
                compressed6Bit = PayloadCompress.compressLink(simplified);
                size6Bit = compressed6Bit.getBytes(ISO_8859_1).length;
            } catch (Exception e) {
                Log.w("MsgConverter", "6-bit compression failed", e);
            }

            if (compressed5Bit != null && size5Bit <= size6Bit) {
                msgTypeId = 2; // 0010 - 5-bit model
                contentToEncrypt = compressed5Bit;
                Log.d("MsgConverter", "Using msgTypeId=2 (5-bit) for JSON URL: " + size5Bit + " bytes");
            } else if (compressed6Bit != null) {
                msgTypeId = 3; // 0011 - 6-bit model
                contentToEncrypt = compressed6Bit;
                Log.d("MsgConverter", "Using msgTypeId=3 (6-bit) for JSON URL: " + size6Bit + " bytes");
            } else {
                msgTypeId = 1;
                contentToEncrypt = urlPart;
                Log.w("MsgConverter", "Both compressions failed, using Unicode fallback");
            }

        } else if (contentToEncrypt.startsWith("[u>")) {
            msgTypeId = 1; // 0001 - Unicode
            contentToEncrypt = contentToEncrypt.substring(3);
        } else if (contentToEncrypt.startsWith("[m>")) {
            msgTypeId = 14; // 1110 - Media (Images)
            contentToEncrypt = contentToEncrypt.substring(3);
        } else if (contentToEncrypt.startsWith("[v>")) {
            msgTypeId = 15; // 1111 - Media (Videos)
            contentToEncrypt = contentToEncrypt.substring(3);
        }

        int chatTypeId = 0;
        if ("N".equals(chatType)) chatTypeId = 1;
        else if ("G".equals(chatType)) chatTypeId = 2;
        else if ("F".equals(chatType)) chatTypeId = 3;

        int replyBit = isReplyDetected ? 1 : 0;

        byte headerByte = (byte) ((chatTypeId << 5) | (replyBit << 4) | msgTypeId);

        String messagePayload;
        if ("N".equals(chatType)) {
            messagePayload = contentToEncrypt;
        } else {
            try {
                String password = MessageHelper.getPasswordForChat(context, chatType, chatId, senderDisplayId);
                messagePayload = CryptoUtils.encrypt(contentToEncrypt, password);
                if (messagePayload == null) messagePayload = "";
            } catch (Exception e) {
                messagePayload = "";
            }
        }

        java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
        try {
            if ("G".equals(chatType) || "F".equals(chatType)) {
                String paddedId = (chatId == null) ? "     " : String.format("%-5s", chatId).substring(0, 5);
                stream.write(paddedId.getBytes(ISO_8859_1));
            }
            if (isReplyDetected) {
                String replyUserAscii = MessageHelper.timestampToAsciiId(MessageHelper.displayIdToTimestamp(rUserId));
                String replyMsgAscii = MessageHelper.timestampToAsciiId(MessageHelper.displayIdToTimestamp(rMsgId));

                stream.write(replyUserAscii.getBytes(ISO_8859_1));
                stream.write(replyMsgAscii.getBytes(ISO_8859_1));
            }

            stream.write(messagePayload.getBytes(ISO_8859_1));

        } catch (Exception e) {
            Log.e("MsgConverter", "Stream build error", e);
        }

        byte[] fullStreamData = stream.toByteArray();

        int dataPerChunk = MAX_PAYLOAD_SIZE - BASE_HEADER_SIZE;
        if (dataPerChunk <= 0) dataPerChunk = 1;

        int totalChunks = (int) Math.ceil((double) fullStreamData.length / dataPerChunk);
        if (totalChunks == 0) totalChunks = 1;
        if (totalChunks > 255) totalChunks = 255;

        this.blePacketsToSend = new ArrayList<>();

        for (int i = 0; i < totalChunks; i++) {
            int start = i * dataPerChunk;
            int length = Math.min(dataPerChunk, fullStreamData.length - start);

            byte[] chunkData = new byte[length];
            System.arraycopy(fullStreamData, start, chunkData, 0, length);

            byte[] packet = createByteHeaderPacket(headerByte, senderAsciiId, messageAsciiId,
                    i, totalChunks, chunkData);
            this.blePacketsToSend.add(packet);
        }

        if (existingMessageIdBits == -1) {
            String finalLocalMessage = tempPayload;

            this.messageToSave = new MessageModel(
                    senderDisplayId, finalLocalMessage, true,
                    createFormattedTimestamp(totalChunks, messageIdBits),
                    senderIdBits, messageIdBits
            );
            this.messageToSave.setMessageId(MessageHelper.timestampToDisplayId(messageIdBits));
            this.messageToSave.setChatType(chatType);
            this.messageToSave.setChatId(chatId);

            if (isReplyDetected) {
                this.messageToSave.setReplyToUserId(rUserId);
                this.messageToSave.setReplyToMessageId(rMsgId);
                this.messageToSave.setReplyToMessagePreview("Loading reply...");
            }
        }
    }


    private byte[] createByteHeaderPacket(byte headerByte, String senderAscii, String msgAscii,
                                          int chunkIndex, int totalChunks, byte[] data) {

        byte[] packet = new byte[BASE_HEADER_SIZE + data.length];
        int offset = 0;

        packet[offset++] = headerByte;
        System.arraycopy(senderAscii.getBytes(ISO_8859_1), 0, packet, offset, USER_ID_LENGTH);
        offset += USER_ID_LENGTH;
        System.arraycopy(msgAscii.getBytes(ISO_8859_1), 0, packet, offset, MESSAGE_ID_LENGTH);
        offset += MESSAGE_ID_LENGTH;
        packet[offset++] = (byte) totalChunks;
        packet[offset++] = (byte) chunkIndex;

        System.arraycopy(data, 0, packet, offset, data.length);
        return packet;
    }


    private byte[] createGenericPacket(String type, String senderAscii, String msgAscii,
                                       int chunkIndex, int totalChunks, byte[] data) {

        byte[] packet = new byte[BASE_HEADER_SIZE + data.length];
        int offset = 0;

        packet[offset++] = (byte) type.charAt(0);
        System.arraycopy(senderAscii.getBytes(ISO_8859_1), 0, packet, offset, USER_ID_LENGTH);
        offset += USER_ID_LENGTH;
        System.arraycopy(msgAscii.getBytes(ISO_8859_1), 0, packet, offset, MESSAGE_ID_LENGTH);
        offset += MESSAGE_ID_LENGTH;
        packet[offset++] = (byte) totalChunks;
        packet[offset++] = (byte) chunkIndex;

        System.arraycopy(data, 0, packet, offset, data.length);
        return packet;
    }


    private byte[] createNearbyChatReplyPacket(String chatType, String senderAsciiId,
                                               String messageAsciiId, int chunkIndex, int totalChunks,
                                               String replyUserAscii, String replyMsgAscii, byte[] chunkData) {

        byte[] packet = new byte[BASE_HEADER_SIZE + 10 + chunkData.length];
        int offset = 0;

        packet[offset++] = (byte) chatType.charAt(0);
        System.arraycopy(senderAsciiId.getBytes(ISO_8859_1), 0, packet, offset, USER_ID_LENGTH);
        offset += USER_ID_LENGTH;
        System.arraycopy(messageAsciiId.getBytes(ISO_8859_1), 0, packet, offset, MESSAGE_ID_LENGTH);
        offset += MESSAGE_ID_LENGTH;
        packet[offset++] = (byte) totalChunks;
        packet[offset++] = (byte) chunkIndex;

        System.arraycopy(replyUserAscii.getBytes(ISO_8859_1), 0, packet, offset, 5);
        offset += 5;
        System.arraycopy(replyMsgAscii.getBytes(ISO_8859_1), 0, packet, offset, 5);
        offset += 5;

        System.arraycopy(chunkData, 0, packet, offset, chunkData.length);

        return packet;
    }

    private byte[] createFirstChunkGFReplyPacket(String chatType, String senderAsciiId,
                                                 String messageAsciiId, int chunkIndex, int totalChunks,
                                                 String paddedChatId, String replyUserAscii,
                                                 String replyMsgAscii, byte[] chunkData) {

        byte[] packet = new byte[FIRST_CHUNK_GF_HEADER_SIZE + 10 + chunkData.length];
        int offset = 0;

        packet[offset++] = (byte) chatType.charAt(0);
        System.arraycopy(senderAsciiId.getBytes(ISO_8859_1), 0, packet, offset, USER_ID_LENGTH);
        offset += USER_ID_LENGTH;
        System.arraycopy(messageAsciiId.getBytes(ISO_8859_1), 0, packet, offset, MESSAGE_ID_LENGTH);
        offset += MESSAGE_ID_LENGTH;
        packet[offset++] = (byte) totalChunks;
        packet[offset++] = (byte) chunkIndex;
        System.arraycopy(paddedChatId.getBytes(ISO_8859_1), 0, packet, offset, CHAT_ID_LENGTH);
        offset += CHAT_ID_LENGTH;

        System.arraycopy(replyUserAscii.getBytes(ISO_8859_1), 0, packet, offset, 5);
        offset += 5;
        System.arraycopy(replyMsgAscii.getBytes(ISO_8859_1), 0, packet, offset, 5);
        offset += 5;

        System.arraycopy(chunkData, 0, packet, offset, chunkData.length);

        return packet;
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
        System.arraycopy(senderAsciiId.getBytes(ISO_8859_1), 0, packet, offset, USER_ID_LENGTH);
        offset += USER_ID_LENGTH;
        System.arraycopy(messageAsciiId.getBytes(ISO_8859_1), 0, packet, offset, MESSAGE_ID_LENGTH);
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
        System.arraycopy(senderAsciiId.getBytes(ISO_8859_1), 0, packet, offset, USER_ID_LENGTH);
        offset += USER_ID_LENGTH;
        System.arraycopy(messageAsciiId.getBytes(ISO_8859_1), 0, packet, offset, MESSAGE_ID_LENGTH);
        offset += MESSAGE_ID_LENGTH;
        packet[offset++] = (byte) totalChunks;
        packet[offset++] = (byte) chunkIndex;
        System.arraycopy(paddedChatId.getBytes(ISO_8859_1), 0, packet, offset, CHAT_ID_LENGTH);
        offset += CHAT_ID_LENGTH;
        System.arraycopy(chunkData, 0, packet, offset, chunkData.length);

        return packet;
    }

    private byte[] createNextChunkGFPacket(String chatType, String senderAsciiId, String messageAsciiId,
                                           int chunkIndex, int totalChunks, byte[] chunkData) {
        byte[] packet = new byte[BASE_HEADER_SIZE + chunkData.length];
        int offset = 0;

        packet[offset++] = (byte) chatType.charAt(0);
        System.arraycopy(senderAsciiId.getBytes(ISO_8859_1), 0, packet, offset, USER_ID_LENGTH);
        offset += USER_ID_LENGTH;
        System.arraycopy(messageAsciiId.getBytes(ISO_8859_1), 0, packet, offset, MESSAGE_ID_LENGTH);
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
