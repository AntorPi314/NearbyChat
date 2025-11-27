package com.antor.nearbychat.Message;

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;

import com.antor.nearbychat.CryptoUtils;
import com.antor.nearbychat.MessageModel;
import com.antor.nearbychat.PayloadCompress;

import java.nio.charset.Charset;
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
    private final String myDisplayId;
    private final String myAsciiId;
    private static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;

    public MessageProcessor(Context context, ExecutorService processingExecutor, String myDisplayId) {
        this.context = context;
        this.processingExecutor = processingExecutor;
        this.myDisplayId = myDisplayId;
        this.myAsciiId = MessageHelper.timestampToAsciiId(MessageHelper.displayIdToTimestamp(myDisplayId));
        Log.d(TAG, "MessageProcessor initialized with myDisplayId=" + myDisplayId + " myAsciiId=" + myAsciiId);
    }

    public MessageModel processIncomingData(byte[] data, String myDisplayId) {
        if (data == null || data.length < 13) {
            return null;
        }
        int offset = 0;

        int headerByte = data[offset] & 0xFF; offset += 1;

        int chatTypeId = (headerByte >> 5) & 0b111; // Bits 7-5
        int isReplyVal = (headerByte >> 4) & 0b1;   // Bit 4
        int msgTypeId = headerByte & 0b1111;        // Bits 3-0

        boolean isReply = (isReplyVal == 1);

        String currentChatType = "N";
        if (chatTypeId == 2) currentChatType = "G";
        else if (chatTypeId == 3) currentChatType = "F";

        String senderAsciiId = new String(data, offset, 5, ISO_8859_1); offset += 5;
        String messageAsciiId = new String(data, offset, 5, ISO_8859_1); offset += 5;
        int totalChunks = data[offset] & 0xFF; offset += 1;
        int chunkIndex = data[offset] & 0xFF; offset += 1;

        byte[] chunkData = new byte[data.length - offset];
        System.arraycopy(data, offset, chunkData, 0, chunkData.length);

        long senderIdBits = MessageHelper.asciiIdToTimestamp(senderAsciiId);
        String senderDisplayId = MessageHelper.timestampToDisplayId(senderIdBits);

        if (senderDisplayId.equals(myDisplayId)) return null;

        long messageIdBits = MessageHelper.asciiIdToTimestamp(messageAsciiId);
        String messageDisplayId = MessageHelper.timestampToDisplayId(messageIdBits);

        String chatId = null;
        if (chunkIndex == 0) {
            if ("G".equals(currentChatType) || "F".equals(currentChatType)) {
                if (chunkData.length >= 5) {
                    chatId = new String(chunkData, 0, 5, ISO_8859_1);
                    if ("F".equals(currentChatType)) {
                        if (!chatId.equals(myAsciiId)) return null;
                        chatId = senderAsciiId;
                    }
                }
            }
        }

        String reassemblerKey = senderDisplayId + "_" + messageDisplayId;
        MessageReassembler reassembler;

        synchronized (reassemblers) {
            reassembler = reassemblers.get(reassemblerKey);
            if (reassembler == null) {
                reassembler = new MessageReassembler(senderDisplayId, messageDisplayId, currentChatType, chatId);
                reassembler.msgTypeId = msgTypeId;
                reassemblers.put(reassemblerKey, reassembler);
            }
            if (chatId != null && reassembler.chatId == null) {
                reassembler.chatId = chatId;
            }
        }

        if (reassembler.hasChunk(chunkIndex)) return null;
        reassembler.addChunk(chunkIndex, totalChunks, chunkData);

        if (reassembler.isComplete()) {
            String fullStreamPayload;
            synchronized (reassemblers) {
                fullStreamPayload = reassembler.reassemble();
            }

            if (fullStreamPayload != null) {
                MessageModel completeMsg = buildMessageModelFromStream(
                        fullStreamPayload, senderDisplayId, messageDisplayId,
                        senderIdBits, messageIdBits, totalChunks,
                        currentChatType, reassembler.chatId, isReply, reassembler.msgTypeId
                );
                synchronized (reassemblers) {
                    reassemblers.remove(reassemblerKey);
                }
                return completeMsg;
            }
        }

        String progressMsg = "Receiving Chunk (" + reassembler.getReceivedCount() + "/" + totalChunks + ")";

        MessageModel partialMsg = new MessageModel(
                senderDisplayId,
                progressMsg,
                false,
                createFormattedTimestamp(totalChunks, messageIdBits),
                senderIdBits,
                messageIdBits
        );
        partialMsg.setMessageId(messageDisplayId);
        partialMsg.setChunkCount(totalChunks);
        partialMsg.setIsComplete(false);
        partialMsg.setChatType(currentChatType);
        partialMsg.setChatId(reassembler.chatId != null ? reassembler.chatId : "");

        return partialMsg;
    }


    private MessageModel buildMessageModelFromStream(String streamPayload, String senderDisplayId,
                                                     String messageDisplayId, long senderIdBits,
                                                     long messageIdBits, int totalChunks,
                                                     String chatType, String knownChatId,
                                                     boolean isReply, int msgTypeId) {

        byte[] fullData = streamPayload.getBytes(ISO_8859_1);
        int offset = 0;
        String chatId = knownChatId;

        if ("G".equals(chatType) || "F".equals(chatType)) {
            if (fullData.length >= offset + 5) offset += 5;
        }

        String replyUserAscii = "";
        String replyMsgAscii = "";

        if (isReply) {
            if (fullData.length >= offset + 10) {
                replyUserAscii = new String(fullData, offset, 5, ISO_8859_1);
                offset += 5;
                replyMsgAscii = new String(fullData, offset, 5, ISO_8859_1);
                offset += 5;
            }
        }

        String actualPayload = "";
        if (offset < fullData.length) {
            actualPayload = new String(fullData, offset, fullData.length - offset, ISO_8859_1);
        }

        if (!"N".equals(chatType)) {
            try {
                String password = MessageHelper.getPasswordForChat(context, chatType, chatId, senderDisplayId);
                String decrypted = CryptoUtils.decrypt(actualPayload, password);
                if (decrypted != null) actualPayload = decrypted;
            } catch (Exception e) {
                Log.e(TAG, "Decrypt failed", e);
            }
        }
        if (msgTypeId == 1) {
            actualPayload = "[u>" + actualPayload;
        } else if (msgTypeId == 2) {
            actualPayload = "g//" + actualPayload;
        }

        MessageModel newMsg = new MessageModel(
                senderDisplayId, actualPayload, false,
                createFormattedTimestamp(totalChunks, messageIdBits),
                senderIdBits, messageIdBits
        );
        newMsg.setMessageId(messageDisplayId);
        newMsg.setIsComplete(true);
        newMsg.setChatType(chatType);
        newMsg.setChatId(chatId != null ? chatId : "");

        if (isReply) {
            try {
                if (!replyUserAscii.isEmpty()) {
                    long rUserBits = MessageHelper.asciiIdToTimestamp(replyUserAscii);
                    newMsg.setReplyToUserId(MessageHelper.timestampToDisplayId(rUserBits));
                }
                if (!replyMsgAscii.isEmpty()) {
                    long rMsgBits = MessageHelper.asciiIdToTimestamp(replyMsgAscii);
                    newMsg.setReplyToMessageId(MessageHelper.timestampToDisplayId(rMsgBits));
                }
                newMsg.setReplyToMessagePreview("Loading preview...");
            } catch (Exception e) {
                Log.e(TAG, "Reply parse error", e);
            }
        }
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

    public java.util.concurrent.CompletableFuture<MessageModel> processIncomingDataAsync(byte[] data, String myDisplayId) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            return processIncomingData(data, myDisplayId);
        }, processingExecutor);
    }

    public void cleanupExpiredReassemblers(long timeoutMs, TimeoutCallback callback) {
        synchronized (reassemblers) {
            Iterator<Map.Entry<String, MessageReassembler>> it = reassemblers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, MessageReassembler> entry = it.next();
                MessageReassembler reassembler = entry.getValue();

                if (reassembler.isExpired(timeoutMs)) {
                    Log.d(TAG, "⏱ Timing out reassembler for message: " + reassembler.messageId);

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
        int msgTypeId = 0;
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
            return new String(fullBytes, ISO_8859_1);
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