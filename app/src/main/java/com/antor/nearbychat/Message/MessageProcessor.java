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
import java.util.concurrent.ExecutorService;

public class MessageProcessor {
    private static final String TAG = "MessageProcessor";

    private final Map<String, MessageReassembler> reassemblers = new HashMap<>();
    private final Context context;
    private final ExecutorService processingExecutor;

    public MessageProcessor(Context context, ExecutorService processingExecutor) {
        this.context = context;
        this.processingExecutor = processingExecutor;
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
            String payload = new String(chunkData, StandardCharsets.UTF_8);
            return buildMessageModelFromPayload(payload, senderDisplayId, messageDisplayId,
                    senderIdBits, messageIdBits, 1, true);
        }
        String reassemblerKey = senderDisplayId + "_" + messageDisplayId;
        MessageReassembler reassembler;
        synchronized (reassemblers) {
            reassembler = reassemblers.get(reassemblerKey);
            if (reassembler == null) {
                reassembler = new MessageReassembler(senderDisplayId, messageDisplayId);
                reassemblers.put(reassemblerKey, reassembler);
            }
        }
        if (reassembler.hasChunk(chunkIndex)) {
            return null;
        }
        reassembler.addChunk(chunkIndex, totalChunks, chunkData);

        if (reassembler.isComplete()) {
            String fullPayload;
            synchronized (reassemblers) {
                fullPayload = reassembler.reassemble();
                reassemblers.remove(reassemblerKey);
            }
            if (fullPayload != null) {
                Log.d(TAG, "Message reassembled successfully: " + messageDisplayId);
                return buildMessageModelFromPayload(fullPayload, senderDisplayId, messageDisplayId,
                        senderIdBits, messageIdBits, totalChunks, true);
            }
        } else {
            String partialContent = String.format(Locale.US, "[Receiving... (%d/%d)]",
                    reassembler.getReceivedCount(), totalChunks);

            MessageModel partialMsg = new MessageModel(
                    senderDisplayId,
                    partialContent,
                    false,
                    createFormattedTimestamp(totalChunks, messageIdBits),
                    senderIdBits,
                    messageIdBits
            );
            partialMsg.setMessageId(messageDisplayId);
            partialMsg.setIsComplete(false);
            partialMsg.setChatType("P");
            partialMsg.setChatId(reassemblerKey);
            partialMsg.setMissingChunks(reassembler.getMissingChunkIndices());
            return partialMsg;
        }
        return null;
    }

    private MessageModel buildMessageModelFromPayload(String payload, String senderDisplayId, String messageDisplayId,
                                                      long senderIdBits, long messageIdBits, int totalChunks, boolean isComplete) {
        if (payload == null || payload.length() < 6) {
            Log.e(TAG, "Invalid message payload after reassembly.");
            return null;
        }

        String chatType = payload.substring(0, 1);
        String chatId = payload.substring(1, 6).trim();
        String encryptedMessage = payload.substring(6);

        String password = MessageHelper.getPasswordForChat(context, chatType, chatId, senderDisplayId);
        String decryptedMessage = CryptoUtils.decrypt(encryptedMessage, password);

        if (decryptedMessage == null) {
            Log.w(TAG, "Decryption failed for message from " + senderDisplayId);
            decryptedMessage = "[Decryption Failed]";
        }
        MessageModel newMsg = new MessageModel(
                senderDisplayId,
                decryptedMessage,
                false,
                createFormattedTimestamp(totalChunks, messageIdBits),
                senderIdBits,
                messageIdBits
        );
        newMsg.setMessageId(messageDisplayId);
        newMsg.setIsComplete(isComplete);
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
        final String senderId;
        final String messageId;
        private final SparseArray<byte[]> chunks = new SparseArray<>();
        private final long creationTimestamp;
        private int totalChunks = -1;
        private int receivedCount = 0;

        MessageReassembler(String senderId, String messageId) {
            this.senderId = senderId;
            this.messageId = messageId;
            this.creationTimestamp = System.currentTimeMillis();
        }
        synchronized boolean addChunk(int chunkIndex, int totalChunks, byte[] chunkData) {
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
        synchronized boolean hasChunk(int chunkIndex) {
            return chunks.get(chunkIndex) != null;
        }

        synchronized boolean isComplete() {
            return totalChunks > 0 && receivedCount == totalChunks;
        }
        synchronized int getReceivedCount() {
            return receivedCount;
        }

        synchronized String reassemble() {
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

        synchronized List<Integer> getMissingChunkIndices() {
            if (isComplete() || totalChunks <= 0) {
                return new ArrayList<>();
            }
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                if (chunks.get(i) == null) {
                    missing.add(i);
                }
            }
            return missing;
        }

        boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - creationTimestamp > timeoutMs;
        }
    }
}