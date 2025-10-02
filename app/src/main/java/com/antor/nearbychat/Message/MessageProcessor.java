package com.antor.nearbychat.Message;

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;

import com.antor.nearbychat.CryptoUtils;
import com.antor.nearbychat.MessageModel;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MessageProcessor {
    private static final String TAG = "MessageProcessor";

    private final Map<String, MessageReassembler> reassemblers = new HashMap<>();
    private final Context context;

    public MessageProcessor(Context context) {
        this.context = context;
    }

    public MessageModel processIncomingData(byte[] data, String myDisplayId) {
        if (data == null || data.length < 12) {
            Log.w(TAG, "Received data is too short.");
            return null;
        }

        String senderAsciiId = new String(data, 0, 5, StandardCharsets.ISO_8859_1);
        long senderIdBits = MessageHelper.asciiIdToTimestamp(senderAsciiId);
        String senderDisplayId = MessageHelper.timestampToDisplayId(senderIdBits);

        if (senderDisplayId.equals(myDisplayId)) {
            return null;
        }

        String messageAsciiId = new String(data, 5, 5, StandardCharsets.ISO_8859_1);
        long messageIdBits = MessageHelper.asciiIdToTimestamp(messageAsciiId);
        String messageDisplayId = MessageHelper.timestampToDisplayId(messageIdBits);

        int chunkIndex = data[10] & 0xFF;
        int totalChunks = data[11] & 0xFF;

        byte[] chunkData = new byte[data.length - 12];
        System.arraycopy(data, 12, chunkData, 0, data.length - 12);

        if (totalChunks == 1) {
            return buildMessageModelFromPayload(new String(chunkData, StandardCharsets.UTF_8),
                    senderDisplayId, messageDisplayId, senderIdBits, messageIdBits, 1);
        }

        String reassemblerKey = senderDisplayId + "_" + messageDisplayId;
        MessageReassembler reassembler = reassemblers.get(reassemblerKey);
        if (reassembler == null) {
            reassembler = new MessageReassembler(senderDisplayId, messageDisplayId);
            reassemblers.put(reassemblerKey, reassembler);
        }

        boolean chunkAdded = reassembler.addChunk(chunkIndex, totalChunks, chunkData);
        if (chunkAdded) {
            Log.d(TAG, "Added chunk " + (chunkIndex + 1) + "/" + totalChunks + " for message " + messageDisplayId);
        }

        if (reassembler.isComplete()) {
            String fullPayload = reassembler.reassemble();
            reassemblers.remove(reassemblerKey); // Clean up
            if (fullPayload != null) {
                return buildMessageModelFromPayload(fullPayload, senderDisplayId, messageDisplayId,
                        senderIdBits, messageIdBits, totalChunks);
            }
        }
        
        // TODO: You can add logic here to create and return a partial MessageModel if desired
        
        return null;
    }

    private MessageModel buildMessageModelFromPayload(String payload, String senderDisplayId, String messageDisplayId,
                                                      long senderIdBits, long messageIdBits, int totalChunks) {
        if (payload == null || payload.length() < 6) {
            Log.e(TAG, "Invalid message payload after reassembly.");
            return null;
        }

        String chatType = payload.substring(0, 1);
        String chatId = payload.substring(1, 6).trim();
        String encryptedMessage = payload.substring(6);

        String password = MessageHelper.getPasswordForChat(context, chatType, chatId, senderDisplayId);

        String decryptedMessage = CryptoUtils.decrypt(encryptedMessage, password);

        MessageModel newMsg = new MessageModel(
                senderDisplayId,
                decryptedMessage,
                false,
                createFormattedTimestamp(totalChunks, messageIdBits),
                senderIdBits,
                messageIdBits
        );
        newMsg.setMessageId(messageDisplayId);
        newMsg.setIsComplete(true);
        newMsg.setChatType(chatType);

        if ("F".equals(chatType)) {
             newMsg.setChatId(MessageHelper.timestampToAsciiId(senderIdBits));
        } else {
             newMsg.setChatId(chatId);
        }
        
        Log.d(TAG, "Successfully processed message from " + senderDisplayId);
        return newMsg;
    }

    private String createFormattedTimestamp(int chunkCount, long messageIdBits) {
        long fullTimestamp = MessageHelper.reconstructFullTimestamp(messageIdBits);
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a | dd-MM-yyyy", Locale.getDefault());
        String baseTime = sdf.format(new Date(fullTimestamp));
        return baseTime + " | " + chunkCount + "C";
    }

    /**
     * Periodically cleans up reassemblers that have not received a chunk recently.
     * @param timeoutMs The timeout in milliseconds.
     */
    public void cleanupExpiredReassemblers(long timeoutMs) {
        synchronized (reassemblers) {
            Iterator<Map.Entry<String, MessageReassembler>> it = reassemblers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, MessageReassembler> entry = it.next();
                if (entry.getValue().isExpired(timeoutMs)) {
                    Log.d(TAG, "Timing out reassembler for message: " + entry.getValue().messageId);
                    it.remove();
                }
            }
        }
    }

    private static class MessageReassembler {
        private final String senderId;
        private final String messageId;
        private final SparseArray<byte[]> chunks = new SparseArray<>();
        private final long creationTimestamp;
        private int totalChunks = -1;
        private int receivedCount = 0;

        public MessageReassembler(String senderId, String messageId) {
            this.senderId = senderId;
            this.messageId = messageId;
            this.creationTimestamp = System.currentTimeMillis();
        }

        public boolean addChunk(int chunkIndex, int totalChunks, byte[] chunkData) {
            if (this.totalChunks == -1) {
                this.totalChunks = totalChunks;
            } else if (this.totalChunks != totalChunks) {
                Log.w(TAG, "Inconsistent total chunk count for message " + messageId);
                return false;
            }

            if (chunks.get(chunkIndex) == null) {
                chunks.put(chunkIndex, chunkData);
                receivedCount++;
                return true;
            }
            return false;
        }

        public boolean isComplete() {
            return totalChunks > 0 && receivedCount == totalChunks;
        }

        public String reassemble() {
            if (!isComplete()) return null;

            int totalSize = 0;
            for (int i = 0; i < totalChunks; i++) {
                totalSize += chunks.get(i).length;
            }

            byte[] fullBytes = new byte[totalSize];
            int offset = 0;
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = chunks.get(i);
                System.arraycopy(chunk, 0, fullBytes, offset, chunk.length);
                offset += chunk.length;
            }

            return new String(fullBytes, StandardCharsets.UTF_8);
        }

        public boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - creationTimestamp > timeoutMs;
        }
    }
}