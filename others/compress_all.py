#!/usr/bin/env python3

import sys
import re

# ==========================================================
# SECTION 1: HELPER FUNCTIONS (FOR LINKS)
# (compress_inputVideoURL.py ও compress_inputImageURL.py থেকে)
# ==========================================================

def toASCII_links(bitstring):
    """
    (Links) Converts a bitstring into a byte payload using the new logic.
    [1 byte: valid_bits_in_last_byte] + [N bytes: data]
    """
    bit_len = len(bitstring)
    
    if bit_len == 0:
        return bytes([0])

    padding_bits = (8 - (bit_len % 8)) % 8
    lastByte_firstValidBits = 8 - padding_bits
    padded_len = bit_len + padding_bits
    bitstring_padded = bitstring.ljust(padded_len, '0')
    num_bytes = int(bitstring_padded, 2).to_bytes(padded_len // 8, 'big')
    header = bytes([lastByte_firstValidBits])
    return header + num_bytes

def fromASCII_links(payload):
    """
    (Links) Reverses the new toASCII_links process.
    """
    if not payload:
        return ""
        
    lastByte_firstValidBits = payload[0]
    num_bytes = payload[1:]
    
    if lastByte_firstValidBits == 0:
        return ""
        
    if not num_bytes:
        return ""
        
    padded_len = len(num_bytes) * 8
    num = int.from_bytes(num_bytes, 'big')
    bitstring_padded = format(num, f'0{padded_len}b')
    padding_bits = 8 - lastByte_firstValidBits
    original_bit_len = padded_len - padding_bits
    return bitstring_padded[:original_bit_len]

def longest_common_prefix(strs):
    """
    Helper function for simplify_links.
    """
    if not strs:
        return ""
    min_len = min(len(s) for s in strs)
    prefix = ""
    for i in range(min_len):
        ch = strs[0][i]
        if all(s[i] == ch for s in strs):
            prefix += ch
        else:
            break
    return prefix

def simplify_links(input_str):
    """
    Simplifies a block of text links into a compact, single-line string.
    """
    links = [l.strip() for l in input_str.strip().splitlines() if l.strip()]
    domain_dict, order = {}, []
    for link in links:
        proto = ""
        if link.startswith("https://"):
            proto, link = "https://", link[8:]
        elif link.startswith("http://"):
            proto, link = "http://", link[7:]
        
        if '/' in link:
            domain, path = link.split('/', 1)
        else:
            domain, path = link, ""
            
        if domain not in domain_dict:
            order.append(domain)
            domain_dict[domain] = []
        domain_dict[domain].append((path, proto))
    
    res = []
    for domain in order:
        pp = domain_dict[domain]
        paths = [p for p, _ in pp]
        protos = [pr for _, pr in pp]
        proto_marker = "h:" if any(p.startswith("http://") for p in protos) else ""

        if not paths or all(p == "" for p in paths):
            res.append(f"{proto_marker}{domain}<>")
            continue
            
        lcp = longest_common_prefix(paths)
        sfx = [p[len(lcp):] for p in paths]
        
        if lcp:
            res.append(f"{proto_marker}{domain}/{lcp}<{ '|'.join(sfx) }>")
        else:
            res.append(f"{proto_marker}{domain}<{ '|'.join(paths) }>")
            
    return "".join(res)

def desimplify_links(simplified):
    """
    Convert simplified link string back to multi-line https:// links.
    """
    groups = re.findall(r'([^<>]+)<([^>]*)>', simplified)
    output_links = []
    for domain_part, inner in groups:
        proto = "https://"
        if domain_part.startswith("h:"):
            domain_part = domain_part[2:]
            proto = "http://"
        
        if not inner.strip():
            link = f"{proto}{domain_part}"
            link = link.replace(":/", "://")
            output_links.append(link)
            continue

        if "/" in domain_part:
            domain, pre = domain_part.split("/", 1)
            if pre:
                pre += "/"
        else:
            domain, pre = domain_part, ""
            
        parts = inner.split("|")
        for p in parts:
            link = f"{proto}{domain}/{pre}{p}".replace("//", "/")
            link = link.replace(":/", "://")
            output_links.append(link)
    return "\n".join(output_links)


# --- Define Link Models (Shared) ---
linkModel1 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ012345<>|./*"
linkModel2 = "6789:-_$&+,;=%~?"
BIT_LENGTH_M1 = 6
BIT_LENGTH_M2 = 4

# --- Create Link lookup maps (Shared) ---
link_model1_map = {char: i for i, char in enumerate(linkModel1)}
link_model2_map = {char: i for i, char in enumerate(linkModel2)}
link_rev_model1_map = {format(i, f'0{BIT_LENGTH_M1}b'): char for char, i in link_model1_map.items()}
link_rev_model2_map = {format(i, f'0{BIT_LENGTH_M2}b'): char for char, i in link_model2_map.items()}

try:
    link_marker_to_m2_bits = format(link_model1_map['*'], f'0{BIT_LENGTH_M1}b')
except KeyError:
    print("Error: Marker '*' not found in linkModel1. Exiting.", file=sys.stderr)
    sys.exit(1)

def decompress_bitstring_links(bitstring):
    """
    (Links) Reverses the compression loop (model1 + model2 w/ marker).
    """
    result = ""
    i = 0
    while i < len(bitstring):
        if i + BIT_LENGTH_M1 > len(bitstring):
            break
        bits_m1 = bitstring[i : i + BIT_LENGTH_M1]
        
        if bits_m1 == link_marker_to_m2_bits:
            i += BIT_LENGTH_M1
            if i + BIT_LENGTH_M2 > len(bitstring):
                break
            bits_m2 = bitstring[i : i + BIT_LENGTH_M2]
            char = link_rev_model2_map.get(bits_m2, '?')
            result += char
            i += BIT_LENGTH_M2
        else:
            char = link_rev_model1_map.get(bits_m1, '?')
            result += char
            i += BIT_LENGTH_M1
    return result


# ==========================================================
# SECTION 2: HELPER FUNCTIONS (FOR MESSAGE)
# (compress_inputMessage.py)
# ==========================================================

def toASCII_msg(bitstring):
    """
    (Message) Converts a bitstring into bytes.
    Uses a 1-byte header for valid bits in the *last* byte (1-8).
    '0' signifies an empty string.
    """
    bit_len = len(bitstring)
    if bit_len == 0:
        return bytes([0])

    valid_bits_last_byte = bit_len % 8
    if valid_bits_last_byte == 0:
        valid_bits_last_byte = 8
        
    header = bytes([valid_bits_last_byte])
    padded_len = ((bit_len + 7) // 8) * 8
    bitstring_padded = bitstring.ljust(padded_len, '0')
    byte_count = max(1, padded_len // 8)
    num_bytes = int(bitstring_padded, 2).to_bytes(byte_count, 'big')
    
    return header + num_bytes


def fromASCII_msg(data):
    """
    (Message) Recovers the original bitstring from bytes created by toASCII_msg.
    """
    if not data:
        return ""

    valid_bits_last_byte = data[0]
    
    if valid_bits_last_byte == 0:
        return ""
    
    if valid_bits_last_byte < 1 or valid_bits_last_byte > 8:
        print(f"Error: Invalid header byte {valid_bits_last_byte}", file=sys.stderr)
        return "[DECODE_ERROR:INVALID_HEADER]"
        
    bit_bytes = data[1:]
    total_data_bytes = len(bit_bytes)
    
    if total_data_bytes == 0:
        print(f"Error: Header byte {valid_bits_last_byte} but no data", file=sys.stderr)
        return "[DECODE_ERROR:MISSING_DATA]"

    bit_len = (total_data_bytes - 1) * 8 + valid_bits_last_byte
    bitstring_padded = bin(int.from_bytes(bit_bytes, 'big'))[2:].zfill(total_data_bytes * 8)
    return bitstring_padded[:bit_len]

# --- Define Message Models ---
MsgModel1 = "abcdefghijklmnopqrstuvwxyz,\n /#*"
MsgModel2 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ.?!@#*"
MsgModel3 = "0123456789-_=&%+;'()[]{}\"|:\\<^~>"
BIT_LENGTH_MSG = 5

# --- Create Message lookup maps ---
msg_model1_map = {char: i for i, char in enumerate(MsgModel1)}
msg_model2_map = {char: i for i, char in enumerate(MsgModel2)}
msg_model3_map = {char: i for i, char in enumerate(MsgModel3)}

msg_model1_inv_map = {i: char for i, char in enumerate(MsgModel1)}
msg_model2_inv_map = {i: char for i, char in enumerate(MsgModel2)}
msg_model3_inv_map = {i: char for i, char in enumerate(MsgModel3)}

msg_marker_to_m2 = format(msg_model1_map['#'], f'0{BIT_LENGTH_MSG}b')
msg_marker_to_m3 = format(msg_model1_map['*'], f'0{BIT_LENGTH_MSG}b')


def fromBinary_msg(bitstring):
    """
    (Message) Decompresses bitstring using the 3-model logic.
    """
    recovered_text = ""
    i = 0
    current_mode = 1
    
    while i < len(bitstring):
        if i + BIT_LENGTH_MSG > len(bitstring):
            break
            
        chunk = bitstring[i : i + BIT_LENGTH_MSG]
        i += BIT_LENGTH_MSG
        
        if current_mode == 1:
            if chunk == msg_marker_to_m2:
                current_mode = 2
            elif chunk == msg_marker_to_m3:
                current_mode = 3
            else:
                index = int(chunk, 2)
                recovered_text += msg_model1_inv_map.get(index, '?')
        
        elif current_mode == 2:
            index = int(chunk, 2)
            recovered_text += msg_model2_inv_map.get(index, '?')
            current_mode = 1
            
        elif current_mode == 3:
            index = int(chunk, 2)
            recovered_text += msg_model3_inv_map.get(index, '?')
            current_mode = 1
            
    return recovered_text


# ==========================================================
# SECTION 3: COMPRESSION - MESSAGE
# ==========================================================
print("\n=== Compressing Message ===")

inputMessage = """
hi, A1 ok ফ
how are you""".strip()

compressed_bitstring_msg = ""
compression_possible_msg = True
failing_char_msg = ''
payLoad_msg = b"" 

input_chars_msg = len(inputMessage)
input_bytes_utf8_msg = len(inputMessage.encode('utf-8'))
print(f'Input Message ({input_chars_msg} chars / {input_bytes_utf8_msg} bytes): "{inputMessage}"')

for char in inputMessage:
    if char in msg_model1_map:
        index = msg_model1_map[char]
        compressed_bitstring_msg += format(index, f'0{BIT_LENGTH_MSG}b')
    elif char in msg_model2_map:
        compressed_bitstring_msg += msg_marker_to_m2
        index = msg_model2_map[char]
        compressed_bitstring_msg += format(index, f'0{BIT_LENGTH_MSG}b')
    elif char in msg_model3_map:
        compressed_bitstring_msg += msg_marker_to_m3
        index = msg_model3_map[char]
        compressed_bitstring_msg += format(index, f'0{BIT_LENGTH_MSG}b')
    else:
        payLoad_msg = f"[u>{inputMessage}".encode('utf-8')
        compression_possible_msg = False
        failing_char_msg = char
        break

if compression_possible_msg:
    compressed_payLoad_msg_bytes = toASCII_msg(compressed_bitstring_msg)
    compressed_payload_bytes_len = len(compressed_payLoad_msg_bytes)
    
    if compressed_payload_bytes_len > input_bytes_utf8_msg:
        payLoad_msg = f"[u>{inputMessage}".encode('utf-8')
        print(f"Compression inefficient ({compressed_payload_bytes_len} bytes > {input_bytes_utf8_msg} bytes). Using fallback.")
    else:
        payLoad_msg = compressed_payLoad_msg_bytes
        print(f"Compression successful.")
else:
    print(f"Compression Failed. Character '{failing_char_msg}' not in model.")

print(f"Message Payload (len {len(payLoad_msg)}): {payLoad_msg[:60]}...")


# ==========================================================
# SECTION 4: COMPRESSION - IMAGE LINKS
# ==========================================================
print("\n=== Compressing Image Links ===")

inputImageURL = """http://okk.com
https://google.com/product/image/pic1.jpg
https://facebook.com/product/chat/pic22.jpg
https://google.com/product/image/pic2.jpg
https://linkedin.com/person/pic_2.png
https://google.com/product/image/pic3.png
https://facebook.com/product/post/pic14.jpg
https://web.linkedin.com/person/pic1.png
http://mywebsite.com/s/fdee23sd
sub.yourwebsite.com/fsdfasdfasdf
https://example.org/documents/report.png
https://cdn.example.io/assets/video/intro.webm""".strip()

simplified_ImageURL = simplify_links(inputImageURL).strip()
print(f"Simplified Image URLs: {simplified_ImageURL}")

compressed_bitstring_img = ""
compression_possible_img = True
failing_char_img = ''
payLoad_img = b""

for char in simplified_ImageURL:
    if char in link_model1_map:
        index = link_model1_map[char]
        compressed_bitstring_img += format(index, f'0{BIT_LENGTH_M1}b')
    elif char in link_model2_map:
        compressed_bitstring_img += link_marker_to_m2_bits
        index = link_model2_map[char]
        compressed_bitstring_img += format(index, f'0{BIT_LENGTH_M2}b')
    else:
        payLoad_img = f"[m>{simplified_ImageURL}".encode('utf-8')
        compression_possible_img = False
        failing_char_img = char
        break

if compression_possible_img:
    compressed_payLoad_img_bytes = toASCII_links(compressed_bitstring_img)
    payLoad_img = b"[m>" + compressed_payLoad_img_bytes
    print(f"Compression successful.")
else:
    print(f"Compression Failed. Character '{failing_char_img}' not in model.")

print(f"Image Payload (len {len(payLoad_img)}): {payLoad_img[:60]}...")


# ==========================================================
# SECTION 5: COMPRESSION - VIDEO LINKS
# ==========================================================
print("\n=== Compressing Video Links ===")

inputVideoURL = """http://okk.com/vid1.mp4
https://google.com/product/video/vid1.webm
https://facebook.com/product/chat/vid22.mp4
https://google.com/product/video/vid2.webm
https://linkedin.com/person/vid_2.mp4
https://google.com/product/video/vid3.mkv
https://facebook.com/product/post/vid14.mp4
https://web.linkedin.com/person/vid1.mp4
http://mywebsite.com/s/fdee23sd.m3u8
sub.yourwebsite.com/fsdfasdfasdf.mp4
https://example.org/documents/report.webm
https://cdn.example.io/assets/video/intro.webm""".strip()

simplified_VideoURL = simplify_links(inputVideoURL).strip()
print(f"Simplified Video URLs: {simplified_VideoURL}")

compressed_bitstring_vid = ""
compression_possible_vid = True
failing_char_vid = ''
payLoad_vid = b""

for char in simplified_VideoURL:
    if char in link_model1_map:
        index = link_model1_map[char]
        compressed_bitstring_vid += format(index, f'0{BIT_LENGTH_M1}b')
    elif char in link_model2_map:
        compressed_bitstring_vid += link_marker_to_m2_bits
        index = link_model2_map[char]
        compressed_bitstring_vid += format(index, f'0{BIT_LENGTH_M2}b')
    else:
        payLoad_vid = f"[v>{simplified_VideoURL}".encode('utf-8')
        compression_possible_vid = False
        failing_char_vid = char
        break

if compression_possible_vid:
    compressed_payLoad_vid_bytes = toASCII_links(compressed_bitstring_vid)
    payLoad_vid = b"[v>" + compressed_payLoad_vid_bytes
    print(f"Compression successful.")
else:
    print(f"Compression Failed. Character '{failing_char_vid}' not in model.")

print(f"Video Payload (len {len(payLoad_vid)}): {payLoad_vid[:60]}...")


# ==========================================================
# SECTION 6: FINAL PAYLOAD
# ==========================================================
finalPayload = payLoad_msg + payLoad_img + payLoad_vid
print("\n======================================")
print(f"Final Combined Payload (Total len {len(finalPayload)} bytes)")
print(finalPayload)
print("======================================")


# ==========================================================
# SECTION 7: DECOMPRESSION & DESIMPLIFICATION TEST
# ==========================================================
print("\n=== Decompression & De-simplification Test ===")
MARKER_U = b'[u>'
MARKER_M = b'[m>'
MARKER_V = b'[v>'

idx_m = finalPayload.find(MARKER_M)
idx_v = finalPayload.find(MARKER_V)

if idx_m == -1 or idx_v == -1:
    print("FATAL ERROR: Could not find [m> or [v> markers in final payload.")
    sys.exit(1)
    
part_msg = finalPayload[:idx_m]
part_img = finalPayload[idx_m:idx_v] 
part_vid = finalPayload[idx_v:]     

print(f"Found Message part (len {len(part_msg)})")
print(f"Found Image part (len {len(part_img)})")
print(f"Found Video part (len {len(part_vid)})")

print("\n--- Testing Message ---")
recovered_msg = ""
if part_msg.startswith(MARKER_U):
    recovered_msg = part_msg[3:].decode('utf-8')
    print("Mode: Uncompressed (Fallback)")
else:
    try:
        recovered_bits_msg = fromASCII_msg(part_msg)
        recovered_msg = fromBinary_msg(recovered_bits_msg)
        print("Mode: Compressed")
    except Exception as e:
        print(f"Message decompression error: {e}")
        recovered_msg = "[DECODE_ERROR]"
        
print(f'Recovered: "{recovered_msg}"')
if recovered_msg == inputMessage:
    print("Verification: SUCCESS")
else:
    print("Verification: FAILED")
    print(f'Expected: "{inputMessage}"')

print("\n--- Testing Image Links ---")
data_img = part_img[3:] 
recovered_simplified_img = ""
if data_img and 0 <= data_img[0] <= 8:
    try:
        recovered_bits_img = fromASCII_links(data_img)
        recovered_simplified_img = decompress_bitstring_links(recovered_bits_img)
        print("Mode: Compressed")
    except Exception as e:
        print(f"Image decompression error: {e}")
        recovered_simplified_img = "[DECODE_ERROR]"
else:
    recovered_simplified_img = data_img.decode('utf-8')
    print("Mode: Uncompressed (Fallback)")

print(f"Recovered simplified string: {recovered_simplified_img}")
final_links_img = desimplify_links(recovered_simplified_img)
print("\n--- Final Recovered Image Links ---")
print(final_links_img)

if recovered_simplified_img == simplified_ImageURL:
    print("\nVerification: SUCCESS")
else:
    print("\nVerification: FAILED")
    print(f"Expected: {simplified_ImageURL}")
    
print("\n--- Testing Video Links ---")
data_vid = part_vid[3:] 
recovered_simplified_vid = ""
if data_vid and 0 <= data_vid[0] <= 8:
    try:
        recovered_bits_vid = fromASCII_links(data_vid)
        recovered_simplified_vid = decompress_bitstring_links(recovered_bits_vid)
        print("Mode: Compressed")
    except Exception as e:
        print(f"Video decompression error: {e}")
        recovered_simplified_vid = "[DECODE_ERROR]"
else:
    recovered_simplified_vid = data_vid.decode('utf-8')
    print("Mode: Uncompressed (Fallback)")
    
print(f"Recovered simplified string: {recovered_simplified_vid}")
final_links_vid = desimplify_links(recovered_simplified_vid)
print("\n--- Final Recovered Video Links ---")
print(final_links_vid)

if recovered_simplified_vid == simplified_VideoURL:
    print("\nVerification: SUCCESS")
else:
    print("\nVerification: FAILED")
    print(f"Expected: {simplified_VideoURL}")