package com.antor.nearbychat;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PayloadCompress {

    // Markers matching Python script
    private static final String MARKER_UNICODE = "[u>";
    private static final String MARKER_IMAGE = "[m>";
    private static final String MARKER_VIDEO = "[v>";

    // ==========================
    // Message Models (5-bit each)
    // ==========================
    private static final String MSG_MODEL_1 = "abcdefghijklmnopqrstuvwxyz,\n /#*";
    private static final String MSG_MODEL_2 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ.?!@#*";
    private static final String MSG_MODEL_3 = "0123456789-_=&%+;'()[]{}\"|:\\<^~>";

    private static final Map<Character, String> msg_map1 = new HashMap<>();
    private static final Map<String, Character> msg_rev1 = new HashMap<>();
    private static final Map<Character, String> msg_map2 = new HashMap<>();
    private static final Map<String, Character> msg_rev2 = new HashMap<>();
    private static final Map<Character, String> msg_map3 = new HashMap<>();
    private static final Map<String, Character> msg_rev3 = new HashMap<>();

    // ==========================
    // Link Models
    // ==========================
    private static final String LINK_MODEL_1 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ012345<>|./*";
    private static final String LINK_MODEL_2 = "6789:-_$&+,;=%~?";

    private static final Map<Character, String> map6 = new HashMap<>();
    private static final Map<String, Character> rev_map6 = new HashMap<>();
    private static final Map<Character, String> map4 = new HashMap<>();
    private static final Map<String, Character> rev_map4 = new HashMap<>();

    private static final Pattern LINK_PATTERN = Pattern.compile("([^<>]+)<([^>]*)>");

    static {
        // Initialize message model maps
        for (int i = 0; i < MSG_MODEL_1.length(); i++) {
            char c = MSG_MODEL_1.charAt(i);
            String bits = String.format("%5s", Integer.toBinaryString(i)).replace(' ', '0');
            msg_map1.put(c, bits);
            msg_rev1.put(bits, c);
        }

        for (int i = 0; i < MSG_MODEL_2.length(); i++) {
            char c = MSG_MODEL_2.charAt(i);
            String bits = String.format("%5s", Integer.toBinaryString(i)).replace(' ', '0');
            msg_map2.put(c, bits);
            msg_rev2.put(bits, c);
        }

        for (int i = 0; i < MSG_MODEL_3.length(); i++) {
            char c = MSG_MODEL_3.charAt(i);
            String bits = String.format("%5s", Integer.toBinaryString(i)).replace(' ', '0');
            msg_map3.put(c, bits);
            msg_rev3.put(bits, c);
        }

        // Initialize link model maps
        for (int i = 0; i < LINK_MODEL_1.length(); i++) {
            char c = LINK_MODEL_1.charAt(i);
            String bits = String.format("%6s", Integer.toBinaryString(i)).replace(' ', '0');
            map6.put(c, bits);
            rev_map6.put(bits, c);
        }

        for (int i = 0; i < LINK_MODEL_2.length(); i++) {
            char c = LINK_MODEL_2.charAt(i);
            String bits = String.format("%4s", Integer.toBinaryString(i)).replace(' ', '0');
            map4.put(c, bits);
            rev_map4.put(bits, c);
        }
    }

    // ==========================
    // Message Compression / Decompression
    // ==========================
    public static String compressMessage(String msg) {
        if (msg == null || msg.isEmpty()) {
            return "";
        }

        StringBuilder bits = new StringBuilder();
        String escapeHash = msg_map1.get('#');
        String escapeStar = msg_map1.get('*');

        for (char c : msg.toCharArray()) {
            if (msg_map1.containsKey(c)) {
                bits.append(msg_map1.get(c));
            } else if (msg_map2.containsKey(c)) {
                bits.append(escapeHash);
                bits.append(msg_map2.get(c));
            } else if (msg_map3.containsKey(c)) {
                bits.append(escapeStar);
                bits.append(msg_map3.get(c));
            } else {
                return null; // Unsupported character
            }
        }

        return bitsToAsciiMsg(bits.toString());
    }

    public static String decompressMessage(String asciiData) {
        if (asciiData == null || asciiData.isEmpty()) {
            return "";
        }

        String bits = asciiToBitsMsg(asciiData);

        StringBuilder result = new StringBuilder();
        int i = 0;
        int bitsLen = bits.length();

        while (i < bitsLen) {
            if (i + 5 > bitsLen) {
                break;
            }

            String block = bits.substring(i, i + 5);
            Character ch = msg_rev1.get(block);

            if (ch != null && ch == '#') {
                if (i + 10 <= bitsLen) {
                    Character nextCh = msg_rev2.get(bits.substring(i + 5, i + 10));
                    if (nextCh != null) {
                        result.append(nextCh);
                    }
                    i += 10;
                } else {
                    break;
                }
            } else if (ch != null && ch == '*') {
                if (i + 10 <= bitsLen) {
                    Character nextCh = msg_rev3.get(bits.substring(i + 5, i + 10));
                    if (nextCh != null) {
                        result.append(nextCh);
                    }
                    i += 10;
                } else {
                    break;
                }
            } else {
                if (ch != null) {
                    result.append(ch);
                }
                i += 5;
            }
        }

        return result.toString();
    }

    // ==========================
    // Helper: Longest Common Prefix
    // ==========================
    private static String longestCommonPrefix(List<String> strs) {
        if (strs == null || strs.isEmpty()) {
            return "";
        }

        String prefix = strs.get(0);
        for (int i = 1; i < strs.size(); i++) {
            while (!strs.get(i).startsWith(prefix)) {
                prefix = prefix.substring(0, prefix.length() - 1);
                if (prefix.isEmpty()) {
                    return "";
                }
            }
        }

        int lastSlash = prefix.lastIndexOf('/');
        if (lastSlash != -1) {
            prefix = prefix.substring(0, lastSlash + 1);
        } else {
            return "";
        }

        return prefix;
    }

    // ==========================
    // Simplify Links
    // ==========================
    public static String simplifyLinks(String inputStr) {
        if (inputStr == null || inputStr.trim().isEmpty()) {
            return "";
        }

        String[] lines = inputStr.trim().split("[\\s,]+");

        Map<String, List<PathProto>> domainDict = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();

        for (String link : lines) {
            link = link.trim();
            if (link.isEmpty()) continue;

            String proto;
            String rest;

            if (link.startsWith("https://")) {
                proto = "https://";
                rest = link.substring(8);
            } else if (link.startsWith("http://")) {
                proto = "http://";
                rest = link.substring(7);
            } else {
                proto = "https://";
                rest = link;
            }

            int slashIdx = rest.indexOf('/');
            String domain;
            String path;

            if (slashIdx != -1) {
                domain = rest.substring(0, slashIdx);
                path = rest.substring(slashIdx + 1);
            } else {
                domain = rest;
                path = "";
            }

            if (!domainDict.containsKey(domain)) {
                order.add(domain);
                domainDict.put(domain, new ArrayList<>());
            }

            domainDict.get(domain).add(new PathProto(path, proto));
        }

        StringBuilder res = new StringBuilder();
        for (String domain : order) {
            List<PathProto> pp = domainDict.get(domain);
            List<String> paths = new ArrayList<>();
            List<String> protos = new ArrayList<>();

            for (PathProto item : pp) {
                paths.add(item.path);
                protos.add(item.proto);
            }

            boolean allEmpty = true;
            for (String p : paths) {
                if (!p.isEmpty()) {
                    allEmpty = false;
                    break;
                }
            }

            if (allEmpty) {
                boolean hasHttp = false;
                for (String pr : protos) {
                    if (pr.equals("http://")) {
                        hasHttp = true;
                        break;
                    }
                }
                String protoMarker = hasHttp ? "h:" : "";
                res.append(protoMarker).append(domain).append("<>");
                continue;
            }

            String lcp = longestCommonPrefix(paths);
            if (!lcp.isEmpty()) {
                List<String> suffixes = new ArrayList<>();
                for (String p : paths) {
                    suffixes.add(p.substring(lcp.length()));
                }
                res.append(domain).append("/").append(lcp).append("<");
                res.append(String.join("|", suffixes));
                res.append(">");
            } else {
                res.append(domain).append("<");
                res.append(String.join("|", paths));
                res.append(">");
            }
        }

        return res.toString();
    }

    // ==========================
    // Compress / Decompress Links
    // ==========================
    public static String compressLink(String s) {
        if (s == null || s.isEmpty()) return "";

        StringBuilder bits = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (map6.containsKey(c)) {
                bits.append(map6.get(c));
            } else if (map4.containsKey(c)) {
                bits.append("1");
                bits.append(map4.get(c));
            } else {
                throw new IllegalArgumentException("Character '" + c + "' not supported");
            }
        }

        return linkBitsToAscii(bits.toString());
    }

    public static String decompressLink(String asciiData) {
        if (asciiData == null || asciiData.isEmpty()) return "";

        String bits = asciiToLinkBits(asciiData);

        StringBuilder out = new StringBuilder();
        int i = 0;
        int bitsLen = bits.length();

        while (i < bitsLen) {
            if (i < bitsLen && bits.charAt(i) == '1') {
                if (i + 5 <= bitsLen) {
                    String code4 = bits.substring(i + 1, i + 5);
                    Character ch4 = rev_map4.get(code4);

                    if (ch4 != null) {
                        out.append(ch4);
                        i += 5;
                        continue;
                    }
                }

                if (i + 6 <= bitsLen) {
                    String code6 = bits.substring(i, i + 6);
                    Character ch6 = rev_map6.get(code6);
                    if (ch6 != null) {
                        out.append(ch6);
                        i += 6;
                        continue;
                    }
                }

                i++;

            } else {
                if (i + 6 <= bitsLen) {
                    String code6 = bits.substring(i, i + 6);
                    Character ch6 = rev_map6.get(code6);
                    if (ch6 != null) {
                        out.append(ch6);
                        i += 6;
                        continue;
                    }
                }
                i++;
            }
        }

        return out.toString();
    }

    // Add this method to PayloadCompress.java class:

    public static String buildPayload(String message, String imageUrls, String videoUrls) {
        StringBuilder payload = new StringBuilder();

        // Handle message
        if (message != null && !message.isEmpty()) {
            String compressed = compressMessage(message);
            if (compressed != null) {
                // Successfully compressed - NO marker, already ISO-8859-1 encoded
                payload.append(compressed);
            } else {
                // Contains unicode/emoji - add [u> marker
                // ================== CRITICAL FIX ==================
                // We need to convert the UTF-8 string to raw bytes,
                // then pack those bytes into an ISO-8859-1 string
                // so they can survive the encryption/transmission process
                try {
                    byte[] utf8Bytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    String packedUtf8 = new String(utf8Bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                    payload.append(MARKER_UNICODE).append(packedUtf8);
                } catch (Exception e) {
                    // Fallback: just append as-is (will likely fail)
                    payload.append(MARKER_UNICODE).append(message);
                }
                // ================== FIX END ==================
            }
        }

        // Handle images
        if (imageUrls != null && !imageUrls.isEmpty()) {
            String simplified = simplifyLinks(imageUrls);
            String compressed = compressLink(simplified);
            payload.append(MARKER_IMAGE).append(compressed);
        }

        // Handle videos
        if (videoUrls != null && !videoUrls.isEmpty()) {
            String simplified = simplifyLinks(videoUrls);
            String compressed = compressLink(simplified);
            payload.append(MARKER_VIDEO).append(compressed);
        }

        return payload.toString();
    }

    public static ParsedPayload parsePayload(String payload) {
        ParsedPayload result = new ParsedPayload();

        if (payload == null || payload.isEmpty()) {
            return result;
        }

        int idx = 0;
        int len = payload.length();

        // Check for message (either compressed or unicode)
        if (idx < len) {
            if (payload.startsWith(MARKER_UNICODE, idx)) {
                // Unicode/emoji message
                idx += MARKER_UNICODE.length();
                int nextMarker = findNextMarkerIndex(payload, idx);

                String isoString; // This string contains raw UTF-8 bytes packed as ISO-8859-1
                if (nextMarker != -1) {
                    isoString = payload.substring(idx, nextMarker);
                    idx = nextMarker;
                } else {
                    isoString = payload.substring(idx);
                    // We are at the end, but still need to process the found string
                }

                // ================== FIX START ==================
                // The `isoString` is currently an ISO-8859-1 string holding raw UTF-8 bytes.
                // We must convert it back to bytes, then re-interpret those bytes as UTF-8.
                try {
                    byte[] utf8Bytes = isoString.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                    result.message = new String(utf8Bytes, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    // Fallback in case of error
                    result.message = "[UTF-8 Decode Error]";
                }
                // ================== FIX END ==================

                if (nextMarker == -1) {
                    return result; // We're done if this was the last part
                }

            } else if (payload.startsWith(MARKER_IMAGE, idx) || payload.startsWith(MARKER_VIDEO, idx)) {
                // No message, skip
            } else {
                // Compressed message (no marker)
                int nextMarker = findNextMarkerIndex(payload, idx);
                if (nextMarker != -1) {
                    String compressedMsg = payload.substring(idx, nextMarker);
                    result.message = decompressMessage(compressedMsg);
                    idx = nextMarker;
                } else {
                    String compressedMsg = payload.substring(idx);
                    result.message = decompressMessage(compressedMsg);
                    return result;
                }
            }
        }

        // Check for images
        if (idx < len && payload.startsWith(MARKER_IMAGE, idx)) {
            idx += MARKER_IMAGE.length();
            int nextMarker = findNextMarkerIndex(payload, idx);
            if (nextMarker != -1) {
                String compressedLinks = payload.substring(idx, nextMarker);
                String simplified = decompressLink(compressedLinks);
                result.imageUrls = desimplifyLinks(simplified);
                idx = nextMarker;
            } else {
                String compressedLinks = payload.substring(idx);
                String simplified = decompressLink(compressedLinks);
                result.imageUrls = desimplifyLinks(simplified);
                return result;
            }
        }

        // Check for videos
        if (idx < len && payload.startsWith(MARKER_VIDEO, idx)) {
            idx += MARKER_VIDEO.length();
            String compressedLinks = payload.substring(idx);
            String simplified = decompressLink(compressedLinks);
            result.videoUrls = desimplifyLinks(simplified);
        }

        return result;
    }

    private static int findNextMarkerIndex(String s, int startIdx) {
        int posImage = s.indexOf(MARKER_IMAGE, startIdx);
        int posVideo = s.indexOf(MARKER_VIDEO, startIdx);

        if (posImage == -1) return posVideo;
        if (posVideo == -1) return posImage;
        return Math.min(posImage, posVideo);
    }

    private static String linkBitsToAscii(String bits) {
        if (bits.isEmpty()) return "";

        int validBitsInLastByte = bits.length() % 8;
        if (validBitsInLastByte == 0) validBitsInLastByte = 8;

        int padding = (8 - (bits.length() % 8)) % 8;
        StringBuilder paddedBits = new StringBuilder(bits);
        for (int i = 0; i < padding; i++) {
            paddedBits.append('0');
        }

        byte[] bytes = new byte[paddedBits.length() / 8 + 1];

        for (int i = 0; i < bytes.length - 1; i++) {
            String byteStr = paddedBits.substring(i * 8, (i + 1) * 8);
            bytes[i] = (byte) Integer.parseInt(byteStr, 2);
        }

        bytes[bytes.length - 1] = (byte) validBitsInLastByte;

        return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static String asciiToLinkBits(String binaryString) {
        if (binaryString == null || binaryString.isEmpty()) return "";

        byte[] bytes = binaryString.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        if (bytes.length < 2) return "";

        int lastValidBitsCount = bytes[bytes.length - 1] & 0xFF;

        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < bytes.length - 1; i++) {
            String byteStr = String.format("%8s", Integer.toBinaryString(bytes[i] & 0xFF))
                    .replace(' ', '0');
            bits.append(byteStr);
        }

        if (bits.length() > 0) {
            int totalBits = (bytes.length - 2) * 8 + lastValidBitsCount;
            if (totalBits > bits.length()) {
                totalBits = bits.length();
            }
            bits.setLength(totalBits);
        }

        return bits.toString();
    }

    public static class ParsedPayload {
        public String message = "";
        public String imageUrls = "";
        public String videoUrls = "";
    }

    public static String desimplifyLinks(String simplified) {
        if (simplified == null || simplified.trim().isEmpty()) {
            return "";
        }

        Matcher matcher = LINK_PATTERN.matcher(simplified);
        List<String> outputLinks = new ArrayList<>();

        while (matcher.find()) {
            String domain = matcher.group(1);
            String inner = matcher.group(2);

            String proto = "https://";
            if (domain.startsWith("h:")) {
                domain = domain.substring(2);
                proto = "http://";
            }

            int slashIdx = domain.indexOf('/');
            String domainPart;
            String pre;

            if (slashIdx != -1) {
                domainPart = domain.substring(0, slashIdx);
                pre = domain.substring(slashIdx + 1);
            } else {
                domainPart = domain;
                pre = "";
            }

            if (inner == null || inner.isEmpty()) {
                if (!pre.isEmpty()) {
                    outputLinks.add(proto + domainPart + "/" + pre);
                } else {
                    outputLinks.add(proto + domainPart);
                }
                continue;
            }

            String[] parts = inner.split("\\|");
            for (String p : parts) {
                if (p.isEmpty()) continue;

                String link;
                if (!pre.isEmpty()) {
                    link = proto + domainPart + "/" + pre + (p.startsWith("/") ? p : "/" + p);
                } else {
                    link = proto + domainPart + "/" + p;
                }

                link = link.replace("//", "/").replace(":/", "://");
                outputLinks.add(link);
            }
        }

        return String.join(",", outputLinks);
    }

    // ==========================
    // Helper Class
    // ==========================
    private static class PathProto {
        String path;
        String proto;

        PathProto(String path, String proto) {
            this.path = path;
            this.proto = proto;
        }
    }

    // ==========================
    // Message: Binary String to ASCII Bytes Conversion
    // ==========================
    private static String bitsToAsciiMsg(String bits) {
        if (bits.isEmpty()) {
            return new String(new byte[]{0}, java.nio.charset.StandardCharsets.ISO_8859_1);
        }

        int validBitsInLastByte = bits.length() % 8;
        if (validBitsInLastByte == 0) {
            validBitsInLastByte = 8;
        }

        int padding = (8 - (bits.length() % 8)) % 8;
        StringBuilder paddedBits = new StringBuilder(bits);
        for (int i = 0; i < padding; i++) {
            paddedBits.append('0');
        }

        byte[] bytes = new byte[paddedBits.length() / 8 + 1];

        for (int i = 0; i < bytes.length - 1; i++) {
            String byteStr = paddedBits.substring(i * 8, (i + 1) * 8);
            bytes[i] = (byte) Integer.parseInt(byteStr, 2);
        }

        bytes[bytes.length - 1] = (byte) validBitsInLastByte;

        return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static String asciiToBitsMsg(String binaryString) {
        if (binaryString == null || binaryString.isEmpty()) {
            return "";
        }

        byte[] bytes = binaryString.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        if (bytes.length < 2) {
            return "";
        }

        int lastValidBitsCount = bytes[bytes.length - 1] & 0xFF;

        if (lastValidBitsCount == 0) {
            return "";
        }

        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < bytes.length - 1; i++) {
            String byteStr = String.format("%8s", Integer.toBinaryString(bytes[i] & 0xFF))
                    .replace(' ', '0');
            bits.append(byteStr);
        }

        if (bits.length() > 0) {
            int totalBits = (bytes.length - 2) * 8 + lastValidBitsCount;
            if (totalBits > bits.length()) {
                totalBits = bits.length();
            }
            bits.setLength(totalBits);
        }

        return bits.toString();
    }
}