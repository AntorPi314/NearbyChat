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
import com.antor.nearbychat.PayloadCompress;

public class MessageProcessor {
    private static final String TAG = "MessageProcessor";

    private final Map<String, MessageReassembler> reassemblers = new HashMap<>();
    private final Context context;
    private final ExecutorService processingExecutor;
    private final String myDisplayId;
    private final String myAsciiId;

    public MessageProcessor(Context context, ExecutorService processingExecutor, String myDisplayId) {
        this.context = context;
        this.processingExecutor = processingExecutor;
        this.myDisplayId = myDisplayId;
        this.myAsciiId = MessageHelper.timestampToAsciiId(MessageHelper.displayIdToTimestamp(myDisplayId));
        Log.d(TAG, "MessageProcessor initialized with myDisplayId=" + myDisplayId + " myAsciiId=" + myAsciiId);
    }

    public MessageModel processIncomingData(byte[] data, String myDisplayId) {
        if (data == null || data.length < 13) {
            Log.w(TAG, "Received data is too short.");
            return null;
        }
        int offset = 0;
        String chatType = new String(data, offset, 1, StandardCharsets.ISO_8859_1);
        offset += 1;

        String senderAsciiId = new String(data, offset, 5, StandardCharsets.ISO_8859_1);
        offset += 5;
        long senderIdBits = MessageHelper.asciiIdToTimestamp(senderAsciiId);
        String senderDisplayId = MessageHelper.timestampToDisplayId(senderIdBits);

        if (senderDisplayId.equals(myDisplayId)) {
            Log.d(TAG, "Dropping my own message from " + senderDisplayId);
            return null;
        }

        String messageAsciiId = new String(data, offset, 5, StandardCharsets.ISO_8859_1);
        offset += 5;
        long messageIdBits = MessageHelper.asciiIdToTimestamp(messageAsciiId);
        String messageDisplayId = MessageHelper.timestampToDisplayId(messageIdBits);

        int totalChunks = data[offset] & 0xFF;
        offset += 1;
        int chunkIndex = data[offset] & 0xFF;
        offset += 1;

        String chatId = null;
        byte[] chunkData;

        if ("N".equals(chatType)) {
            chatId = "";
            chunkData = new byte[data.length - offset];
            System.arraycopy(data, offset, chunkData, 0, chunkData.length);
            Log.d(TAG, "Nearby message from " + senderDisplayId);

        } else if ("G".equals(chatType) || "F".equals(chatType)) {

            if (chunkIndex == 0) {
                if (data.length < 18) {
                    Log.w(TAG, "First chunk of G/F chat too short");
                    return null;
                }
                chatId = new String(data, offset, 5, StandardCharsets.ISO_8859_1).trim();
                offset += 5;
                chunkData = new byte[data.length - offset];
                System.arraycopy(data, offset, chunkData, 0, chunkData.length);

                if ("F".equals(chatType)) {
                    Log.d(TAG, "Friend first chunk: sender=" + senderDisplayId + " chatId(friendId)=" + chatId + " myAsciiId=" + myAsciiId);

                    if (!chatId.equals(myAsciiId)) {
                        Log.d(TAG, "✗ DROPPING Friend message - not for me! chatId=" + chatId + " != myAsciiId=" + myAsciiId);
                        return null;
                    }

                    Log.d(TAG, "✓ Friend message IS for me! Converting chatId to senderAsciiId=" + senderAsciiId);
                    chatId = senderAsciiId;
                }
            } else {
                chunkData = new byte[data.length - offset];
                System.arraycopy(data, offset, chunkData, 0, chunkData.length);
            }
        } else {
            Log.w(TAG, "Unknown chat type: " + chatType);
            return null;
        }

        // Single chunk message - process immediately
        if (totalChunks == 1) {
            String payload = new String(chunkData, StandardCharsets.ISO_8859_1);
            return buildMessageModelFromPayload(payload, senderDisplayId, messageDisplayId,
                    senderIdBits, messageIdBits, 1, true, chatType, chatId);
        }

        // Multi-chunk message - use reassembler
        String reassemblerKey = senderDisplayId + "_" + messageDisplayId;
        MessageReassembler reassembler;

        synchronized (reassemblers) {
            reassembler = reassemblers.get(reassemblerKey);

            if (reassembler == null) {
                // Create new reassembler
                reassembler = new MessageReassembler(senderDisplayId, messageDisplayId, chatType, chatId);
                reassemblers.put(reassemblerKey, reassembler);

                if ("F".equals(chatType) && chatId != null) {
                    reassembler.isValidated = true;
                    Log.d(TAG, "Friend chat reassembler created and validated");
                }
            } else {
                // Update existing reassembler with chatId/chatType if available
                if (chatId != null) {
                    if (reassembler.chatId == null) {
                        reassembler.chatId = chatId;
                        Log.d(TAG, "Updated reassembler chatId: " + chatId);
                    }
                    if (reassembler.chatType == null) {
                        reassembler.chatType = chatType;
                    }

                    if ("F".equals(chatType)) {
                        reassembler.isValidated = true;
                        Log.d(TAG, "Friend chat reassembler now validated");
                    }
                }
            }

            // For Friend chat, drop subsequent chunks if not validated
            if ("F".equals(chatType) && !reassembler.isValidated) {
                Log.d(TAG, "✗ DROPPING Friend subsequent chunk - not validated yet");
                reassemblers.remove(reassemblerKey);
                return null;
            }
        }

        // Check if we already have this chunk
        if (reassembler.hasChunk(chunkIndex)) {
            Log.d(TAG, "Already have chunk " + chunkIndex + " for message " + messageDisplayId);
            return null;
        }

        // Add the chunk
        reassembler.addChunk(chunkIndex, totalChunks, chunkData);
        Log.d(TAG, "Added chunk " + chunkIndex + "/" + totalChunks + " for message " + messageDisplayId);

        // Check if message is complete
        if (reassembler.isComplete()) {
            String fullPayload;
            synchronized (reassemblers) {
                fullPayload = reassembler.reassemble();
                reassemblers.remove(reassemblerKey);
            }

            if (fullPayload != null) {
                Log.d(TAG, "✓ Message reassembled successfully: " + messageDisplayId);
                return buildMessageModelFromPayload(fullPayload, senderDisplayId, messageDisplayId,
                        senderIdBits, messageIdBits, totalChunks, true,
                        reassembler.chatType != null ? reassembler.chatType : chatType,
                        reassembler.chatId != null ? reassembler.chatId : chatId);
            }
        } else {
            // Message still incomplete - create partial message
            String partialContent = String.format(Locale.US, "Receiving... (%d/%d)",
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

            // CRITICAL: Use reassembler's stored chatType and chatId
            String msgChatType = reassembler.chatType != null ? reassembler.chatType : chatType;
            String msgChatId = reassembler.chatId != null ? reassembler.chatId : (chatId != null ? chatId : "");

            partialMsg.setChatType(msgChatType);
            partialMsg.setChatId(msgChatId);
            partialMsg.setMissingChunks(reassembler.getMissingChunkIndices());

            Log.d(TAG, "Partial message created: " + partialContent +
                    " | chatType=" + msgChatType + " | chatId=" + msgChatId +
                    " | received=" + reassembler.getReceivedCount() + "/" + totalChunks);

            return partialMsg;
        }

        return null;
    }

    private MessageModel buildMessageModelFromPayload(String payload, String senderDisplayId, String messageDisplayId,
                                                      long senderIdBits, long messageIdBits, int totalChunks,
                                                      boolean isComplete, String chatType, String chatId) {
        String decryptedPayload;

        // Decrypt if needed
        if ("N".equals(chatType)) {
            decryptedPayload = payload;
        } else {
            String password = MessageHelper.getPasswordForChat(context, chatType, chatId, senderDisplayId);
            decryptedPayload = CryptoUtils.decrypt(payload, password);

            if (decryptedPayload == null) {
                Log.w(TAG, "Decryption failed for message from " + senderDisplayId);
                decryptedPayload = "[Decryption Failed]";
            }
        }

        MessageModel newMsg = new MessageModel(
                senderDisplayId,
                decryptedPayload,
                false,
                createFormattedTimestamp(totalChunks, messageIdBits),
                senderIdBits,
                messageIdBits
        );
        newMsg.setMessageId(messageDisplayId);
        newMsg.setIsComplete(isComplete);
        newMsg.setChatType(chatType);
        newMsg.setChatId(chatId != null ? chatId : "");

        Log.d(TAG, "✓ Message model created: from=" + senderDisplayId +
                " | chatType=" + chatType + " | chatId=" + newMsg.getChatId() +
                " | complete=" + isComplete);

        return newMsg;
    }

    public interface TimeoutCallback {
        void onTimeout(MessageModel failedMessage);
    }

    private String createFormattedTimestamp(int chunkCount, long messageIdBits) {
        long fullTimestamp = MessageHelper.reconstructFullTimestamp(messageIdBits);
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a | dd-MM-yyyy", Locale.getDefault());
        String baseTime = sdf.format(new Date(fullTimestamp));
        return baseTime + " | " + chunkCount + "C";
    }

    public void cleanupExpiredReassemblers(long timeoutMs, TimeoutCallback callback) {
        synchronized (reassemblers) {
            Iterator<Map.Entry<String, MessageReassembler>> it = reassemblers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, MessageReassembler> entry = it.next();
                MessageReassembler reassembler = entry.getValue();

                if (reassembler.isExpired(timeoutMs)) {
                    Log.d(TAG, "⏱ Timing out reassembler for message: " + reassembler.messageId);

                    // Create failed message with proper chatType and chatId
                    String msgChatType = reassembler.chatType != null ? reassembler.chatType : "N";
                    String msgChatId = reassembler.chatId != null ? reassembler.chatId : "";

                    String failedContent = String.format(Locale.US, "Failed to receive (%d/%d chunks)",
                            reassembler.getReceivedCount(),
                            reassembler.totalChunks > 0 ? reassembler.totalChunks : 1);

                    MessageModel failedMsg = new MessageModel(
                            reassembler.senderId,
                            failedContent,
                            false,
                            "Timeout | " + createFormattedTimestamp(1, 0),
                            0,
                            0
                    );
                    failedMsg.setMessageId(reassembler.messageId);
                    failedMsg.setIsComplete(false);
                    failedMsg.setFailed(true);
                    failedMsg.setChatType(msgChatType);
                    failedMsg.setChatId(msgChatId);

                    Log.d(TAG, "✗ Failed message created: chatType=" + msgChatType +
                            " | chatId=" + msgChatId + " | content=" + failedContent);

                    if (callback != null) {
                        callback.onTimeout(failedMsg);
                    }
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

        String chatType = null;
        String chatId = null;
        boolean isValidated = false;

        MessageReassembler(String senderId, String messageId, String chatType, String chatId) {
            this.senderId = senderId;
            this.messageId = messageId;
            this.chatType = chatType;
            this.chatId = chatId;
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
            return new String(fullBytes, StandardCharsets.ISO_8859_1);
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