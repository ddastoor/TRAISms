import json
import pathlib
import re
import sqlite3
import tempfile
import unittest
import xml.etree.ElementTree as ET


REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
APP_DIR = REPO_ROOT / "app"
MAIN_DIR = APP_DIR / "src" / "main"
JAVA_DIR = MAIN_DIR / "java" / "com" / "example"
RES_DIR = MAIN_DIR / "res"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
TOOLS_NS = "{http://schemas.android.com/tools}"


def strip_kotlin_comments(source: str) -> str:
    """Remove block and single-line comments while preserving string literals."""
    pattern = re.compile(
        r'"(?:\\.|[^"\\])*"'
        r"|/\*[\s\S]*?\*/"
        r"|//[^\n]*"
    )
    return pattern.sub(lambda m: m.group(0) if m.group(0).startswith('"') else "", source)


def read_text(rel_path: str) -> str:
    path = REPO_ROOT / rel_path
    if not path.is_file():
        raise AssertionError(f"Expected file does not exist: {rel_path}")
    return path.read_text(encoding="utf-8")


def parse_kotlin_data_class_fields(source: str, class_name: str) -> dict[str, str]:
    """Extract parameter names and default expressions from a Kotlin data class header."""
    code = strip_kotlin_comments(source)
    match = re.search(rf"\bdata\s+class\s+{re.escape(class_name)}\s*\((.*?)\)\s*(\{{|$)", code, re.S)
    if not match:
        raise AssertionError(f"Could not locate data class {class_name}")
    body = match.group(1)
    fields: dict[str, str] = {}
    for prop_match in re.finditer(
        r"\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([^=,)\n]+(?:\s*<[^=)\n]+>)?)\s*(?:=\s*([^,\n]+))?",
        body,
    ):
        name = prop_match.group(1)
        default_expr = (prop_match.group(3) or "").strip()
        fields[name] = default_expr
    return fields


# --- Independent OMA-TS-MMS-ENC / WAP-209 binary PDU reference codec ---

WELL_KNOWN_CT = {
    0x03: "text/plain",
    0x07: "text/x-vCard",
    0x1D: "image/gif",
    0x1E: "image/jpeg",
    0x20: "image/png",
    0x23: "application/vnd.wap.multipart.mixed",
    0x33: "application/vnd.wap.multipart.related",
}
CT_TO_CODE = {v.lower(): k for k, v in WELL_KNOWN_CT.items()}


def encode_uintvar(val: int) -> bytes:
    chunks = []
    v = max(0, val)
    while True:
        chunks.append(v & 0x7F)
        v >>= 7
        if v == 0:
            break
    chunks.reverse()
    out = bytearray()
    for i, c in enumerate(chunks):
        out.append(c | (0x80 if i < len(chunks) - 1 else 0))
    return bytes(out)


def decode_uintvar(buf: bytes, pos: int) -> tuple[int, int]:
    res = 0
    for _ in range(5):
        b = buf[pos]
        pos += 1
        res = (res << 7) | (b & 0x7F)
        if (b & 0x80) == 0:
            break
    return res, pos


def encode_value_length(length: int) -> bytes:
    if length <= 30:
        return bytes([length])
    return bytes([31]) + encode_uintvar(length)


def decode_value_length(buf: bytes, pos: int) -> tuple[int, int]:
    first = buf[pos]
    pos += 1
    if first <= 30:
        return first, pos
    if first == 31:
        return decode_uintvar(buf, pos)
    return 0, pos


def encode_long_integer(val: int) -> bytes:
    if val == 0:
        return bytes([1, 0])
    raw = []
    rem = val
    while rem > 0:
        raw.append(rem & 0xFF)
        rem >>= 8
    raw.reverse()
    return bytes([len(raw)]) + bytes(raw)


def decode_long_integer(buf: bytes, pos: int) -> tuple[int, int]:
    ln = buf[pos]
    pos += 1
    val = 0
    for _ in range(ln):
        val = (val << 8) | buf[pos]
        pos += 1
    return val, pos


def encode_text_string(text: str) -> bytes:
    raw = text.encode("utf-8")
    out = bytearray()
    if raw and raw[0] >= 0x80:
        out.append(0x7F)
    out.extend(raw)
    out.append(0)
    return bytes(out)


def decode_text_string(buf: bytes, pos: int) -> tuple[str, int]:
    if pos < len(buf) and buf[pos] in (0x7F, ord('"')):
        pos += 1
    start = pos
    while pos < len(buf) and buf[pos] != 0:
        pos += 1
    text = buf[start:pos].decode("utf-8")
    if pos < len(buf) and buf[pos] == 0:
        pos += 1
    return text, pos


def encode_encoded_string(text: str) -> bytes:
    raw = text.encode("utf-8")
    if all(b < 0x80 for b in raw):
        return encode_text_string(text)
    payload = bytes([106 | 0x80]) + encode_text_string(text)
    return encode_value_length(len(payload)) + payload


def decode_encoded_string(buf: bytes, pos: int) -> tuple[str, int]:
    if buf[pos] >= 32:
        return decode_text_string(buf, pos)
    ln, pos = decode_value_length(buf, pos)
    end = pos + ln
    # charset short-int
    if buf[pos] >= 0x80:
        pos += 1
    text, pos = decode_text_string(buf, pos)
    return text, max(pos, end)


def build_reference_send_req(
    tx_id: str,
    to_addrs: list[str],
    from_addr: str | None,
    subject: str | None,
    epoch_sec: int,
    parts: list[tuple[str, str, bytes]],
) -> bytes:
    out = bytearray()
    out.extend([0x8C, 0x80])  # M-Send.req
    out.append(0x98)
    out.extend(encode_text_string(tx_id))
    out.extend([0x8D, 0x90])  # MMS 1.0
    out.append(0x85)
    out.extend(encode_long_integer(epoch_sec))
    out.append(0x89)
    if not from_addr:
        out.extend([1, 0x81])
    else:
        fmt = from_addr if ("@" in from_addr or "/TYPE=" in from_addr.upper()) else f"{from_addr}/TYPE=PLMN"
        fb = bytes([0x80]) + encode_encoded_string(fmt)
        out.extend(encode_value_length(len(fb)))
        out.extend(fb)
    for addr in to_addrs:
        fmt = addr if ("@" in addr or "/TYPE=" in addr.upper()) else f"{addr}/TYPE=PLMN"
        out.append(0x97)
        out.extend(encode_encoded_string(fmt))
    if subject:
        out.append(0x96)
        out.extend(encode_encoded_string(subject))
    out.extend([0x86, 0x80, 0x90, 0x81])  # Delivery-Report=Yes, Read-Report=No

    # SMIL + parts
    smil_bytes = b"<smil><body><par dur=\"5000ms\"/></body></smil>"
    all_parts = [("application/smil", "smil.xml", smil_bytes)] + parts
    ct = bytearray([0x33 | 0x80, 0x89])
    ct.extend(encode_text_string("application/smil"))
    ct.append(0x8A)
    ct.extend(encode_text_string("<smil>"))
    out.append(0x84)
    out.extend(encode_value_length(len(ct)))
    out.extend(ct)

    out.extend(encode_uintvar(len(all_parts)))
    for idx, (mime, fname, data) in enumerate(all_parts):
        hdr = bytearray()
        code = CT_TO_CODE.get(mime.lower())
        if mime.startswith("text/") or fname:
            inner = bytearray()
            if code is not None:
                inner.append(code | 0x80)
            else:
                inner.extend(encode_text_string(mime))
            if mime.startswith("text/"):
                inner.extend([0x81, 106 | 0x80])
            if fname:
                inner.append(0x85)
                inner.extend(encode_text_string(fname))
            hdr.extend(encode_value_length(len(inner)))
            hdr.extend(inner)
        else:
            hdr.append((code | 0x80) if code is not None else 0x00)
        hdr.append(0xC0)
        hdr.extend(b'"' + f"<part_{idx}>".encode("utf-8") + b"\x00")
        hdr.append(0x8E)
        hdr.extend(encode_text_string(fname or f"part_{idx}"))
        out.extend(encode_uintvar(len(hdr)))
        out.extend(encode_uintvar(len(data)))
        out.extend(hdr)
        out.extend(data)
    return bytes(out)


def parse_reference_send_req(pdu: bytes) -> dict:
    pos = 0
    msg_type = None
    tx_id = None
    epoch = 0
    from_addr = None
    to_addrs = []
    subject = None

    while pos < len(pdu):
        hdr = pdu[pos]
        pos += 1
        if hdr == 0x8C:
            msg_type = pdu[pos]
            pos += 1
        elif hdr == 0x98:
            tx_id, pos = decode_text_string(pdu, pos)
        elif hdr in (0x8D, 0x86, 0x90):
            pos += 1
        elif hdr == 0x85:
            epoch, pos = decode_long_integer(pdu, pos)
        elif hdr == 0x89:
            ln, pos = decode_value_length(pdu, pos)
            end = pos + ln
            token = pdu[pos]
            if token == 0x80 and pos + 1 < end:
                raw_from, _ = decode_encoded_string(pdu, pos + 1)
                from_addr = raw_from.split("/TYPE=")[0]
            pos = end
        elif hdr == 0x97:
            raw_to, pos = decode_encoded_string(pdu, pos)
            to_addrs.append(raw_to.split("/TYPE=")[0])
        elif hdr == 0x96:
            subject, pos = decode_encoded_string(pdu, pos)
        elif hdr == 0x84:
            if pdu[pos] >= 0x80:
                pos += 1
            elif pdu[pos] < 32:
                ln, pos = decode_value_length(pdu, pos)
                pos += ln
            else:
                _, pos = decode_text_string(pdu, pos)
            break
        else:
            break

    part_count, pos = decode_uintvar(pdu, pos)
    parts = []
    for _ in range(part_count):
        hdr_len, pos = decode_uintvar(pdu, pos)
        data_len, pos = decode_uintvar(pdu, pos)
        hdr_end = pos + hdr_len
        if pdu[pos] >= 0x80:
            ct = WELL_KNOWN_CT.get(pdu[pos] & 0x7F, "application/octet-stream")
        elif pdu[pos] < 32:
            ln, p2 = decode_value_length(pdu, pos)
            if pdu[p2] >= 0x80:
                ct = WELL_KNOWN_CT.get(pdu[p2] & 0x7F, "application/octet-stream")
            else:
                ct, _ = decode_text_string(pdu, p2)
            _ = ln
        else:
            ct, _ = decode_text_string(pdu, pos)
        pos = hdr_end
        data = pdu[pos : pos + data_len]
        pos += data_len
        parts.append((ct, data))

    return {
        "message_type": msg_type,
        "tx_id": tx_id,
        "epoch": epoch,
        "from_addr": from_addr,
        "to_addrs": to_addrs,
        "subject": subject,
        "parts": parts,
    }


# --- Reference Google Messages tapback fallback codec matching RcsInteropCodec.kt ---

VERB_TO_EMOJI = {
    "liked": "👍",
    "loved": "❤️",
    "laughed at": "😂",
    "disliked": "👎",
    "surprised by": "😮",
    "cried at": "😢",
    "emphasized": "‼️",
    "questioned": "❓",
}

REMOVAL_NOUN_TO_EMOJI = {
    "like": "👍",
    "love": "❤️",
    "laugh": "😂",
    "dislike": "👎",
    "reaction": "👍",
}


def ref_encode_reaction_fallback(
    emoji: str,
    target_body: str,
    primary_attachment_mime: str | None = None,
    is_removal: bool = False,
) -> str:
    clean = target_body.strip()
    media_noun = None
    if primary_attachment_mime:
        low = primary_attachment_mime.lower()
        if low.startswith("image/"):
            media_noun = "an image"
        elif low.startswith("video/"):
            media_noun = "a video"
        elif low.startswith("audio/"):
            media_noun = "an audio message"
        else:
            media_noun = "a file"

    snippet = clean[:80]
    if is_removal:
        noun = {"👍": "like", "❤️": "love", "😂": "laugh", "👎": "dislike"}.get(emoji, emoji)
        if clean:
            return f"Removed {emoji} from “{snippet}”" if noun == emoji else f"Removed a {noun} from “{snippet}”"
        return f"Removed {emoji} from {media_noun or 'a message'}"

    verb = {"👍": "Liked", "❤️": "Loved", "😂": "Laughed at", "👎": "Disliked"}.get(emoji)
    if clean:
        return f"{verb} “{snippet}”" if verb else f"Reacted {emoji} to “{snippet}”"
    target_media = media_noun or "a message"
    return f"{verb} {target_media}" if verb else f"Reacted {emoji} to {target_media}"


def ref_parse_reaction_fallback(body: str) -> dict | None:
    trimmed = body.strip()
    if not trimmed:
        return None
    m = re.match(r'^Removed\s+(?:a|an)\s+(like|love|laugh|dislike|reaction)\s+from\s+[“"]([\s\S]+)[”"]\s*$', trimmed, re.I)
    if m:
        return {"emoji": REMOVAL_NOUN_TO_EMOJI[m.group(1).lower()], "quoted": m.group(2), "media": None, "removal": True}
    m = re.match(r'^Removed\s+(\S+)\s+(?:reaction\s+)?from\s+[“"]([\s\S]+)[”"]\s*$', trimmed, re.I)
    if m:
        return {"emoji": m.group(1), "quoted": m.group(2), "media": None, "removal": True}
    m = re.match(r'^(Loved|Liked|Laughed at|Disliked|Surprised by|Cried at|Emphasized|Questioned)\s+[“"]([\s\S]+)[”"]\s*$', trimmed, re.I)
    if m:
        return {"emoji": VERB_TO_EMOJI[m.group(1).lower()], "quoted": m.group(2), "media": None, "removal": False}
    m = re.match(r'^Reacted\s+(\S+)\s+to\s+[“"]([\s\S]+)[”"]\s*$', trimmed, re.I)
    if m:
        return {"emoji": m.group(1), "quoted": m.group(2), "media": None, "removal": False}
    m = re.match(r'^(Loved|Liked|Laughed at|Disliked|Surprised by|Cried at|Emphasized|Questioned)\s+(?:an|a)\s+(image|photo|video|audio message|file|contact|message)\s*$', trimmed, re.I)
    if m:
        return {"emoji": VERB_TO_EMOJI[m.group(1).lower()], "quoted": None, "media": m.group(2).lower(), "removal": False}
    m = re.match(r'^Reacted\s+(\S+)\s+to\s+(?:an|a)\s+(image|photo|video|audio message|file|contact|message)\s*$', trimmed, re.I)
    if m:
        return {"emoji": m.group(1), "quoted": None, "media": m.group(2).lower(), "removal": False}
    return None


class MmsPduAndCarrierSpecTest(unittest.TestCase):
    """Spec-anchored functional and contract tests for Carrier MMS send/receive and PDU encoding."""

    def test_oma_binary_pdu_uintvar_boundaries_and_send_req_round_trip(self) -> None:
        # Boundary values for multi-byte WAP uintvar (127, 128, 16383, 16384)
        for v in (0, 1, 127, 128, 16383, 16384, 262144):
            enc = encode_uintvar(v)
            dec, end_pos = decode_uintvar(enc, 0)
            self.assertEqual(dec, v)
            self.assertEqual(end_pos, len(enc))

        binary_png = b"\x89PNG\r\n\x1a\n" + b"\x00\x01\x02\x03" * 40
        pdu = build_reference_send_req(
            tx_id="tx_group_1",
            to_addrs=["+14155550101", "+14155550102"],
            from_addr="+14155550199",
            subject="Trip Photos 🌴",
            epoch_sec=1_732_000_123,
            parts=[
                ("text/plain", "msg.txt", "See you at 6pm!".encode("utf-8")),
                ("image/png", "beach.png", binary_png),
                ("video/mp4", "clip.mp4", b"\x00\x00\x00\x18ftypmp42"),
            ],
        )
        parsed = parse_reference_send_req(pdu)
        self.assertEqual(parsed["message_type"], 0x80)
        self.assertEqual(parsed["tx_id"], "tx_group_1")
        self.assertEqual(parsed["epoch"], 1_732_000_123)
        self.assertEqual(parsed["from_addr"], "+14155550199")
        self.assertEqual(parsed["to_addrs"], ["+14155550101", "+14155550102"])
        self.assertEqual(parsed["subject"], "Trip Photos 🌴")
        self.assertEqual(len(parsed["parts"]), 4)
        self.assertEqual(parsed["parts"][0][0], "application/smil")
        self.assertEqual(parsed["parts"][1], ("text/plain", b"See you at 6pm!"))
        self.assertEqual(parsed["parts"][2], ("image/png", binary_png))
        self.assertEqual(parsed["parts"][3], ("video/mp4", b"\x00\x00\x00\x18ftypmp42"))

    def test_mms_pdu_codec_kotlin_contract_and_zero_hidden_apis(self) -> None:
        code = strip_kotlin_comments(read_text("app/src/main/java/com/example/data/sms/MmsPduCodec.kt"))
        for enum_entry in (
            "SEND_REQ(0x80)",
            "SEND_CONF(0x81)",
            "NOTIFICATION_IND(0x82)",
            "NOTIFYRESP_IND(0x83)",
            "RETRIEVE_CONF(0x84)",
            "ACKNOWLEDGE_IND(0x85)",
            "DELIVERY_IND(0x86)",
            "READ_REC_IND(0x87)",
            "READ_ORIG_IND(0x88)",
        ):
            self.assertIn(enum_entry, code)

        for fn_name in (
            "fun encodeSendReq(",
            "fun encodeNotifyRespInd(",
            "fun encodeAcknowledgeInd(",
            "fun encodeReadRecInd(",
            "fun buildSmilXml(",
            "fun decodePdu(",
            "fun extractSmilOrderedParts(",
        ):
            self.assertIn(fn_name, code)

        # Must support SMIL media tags + UTF-8 quote prefix 0x7F + zero network/hidden APIs
        for smil_tag in ("<img ", "<video ", "<audio ", "<text ", "<ref "):
            self.assertIn(smil_tag, code)
        self.assertIn("0x7F", code)
        self.assertNotIn("HttpURLConnection", code)
        self.assertNotIn("com.google.android.mms", code)

    def test_file_provider_wap_push_and_status_receiver_manifest_and_code_contracts(self) -> None:
        manifest_root = ET.parse(MAIN_DIR / "AndroidManifest.xml").getroot()
        app_elem = manifest_root.find("application")
        self.assertIsNotNone(app_elem)

        # Non-exported FileProvider for cacheDir/mms_pdu & filesDir/mms_share
        providers = app_elem.findall("provider")
        file_provider = next(
            (p for p in providers if p.attrib.get(f"{ANDROID_NS}name") == "androidx.core.content.FileProvider"),
            None,
        )
        self.assertIsNotNone(file_provider, "Missing androidx.core.content.FileProvider in AndroidManifest.xml")
        self.assertEqual(file_provider.attrib.get(f"{ANDROID_NS}authorities"), "${applicationId}.mmsfileprovider")
        self.assertEqual(file_provider.attrib.get(f"{ANDROID_NS}exported"), "false")
        self.assertEqual(file_provider.attrib.get(f"{ANDROID_NS}grantUriPermissions"), "true")
        fp_meta = file_provider.find("meta-data")
        self.assertIsNotNone(fp_meta)
        self.assertEqual(fp_meta.attrib.get(f"{ANDROID_NS}name"), "android.support.FILE_PROVIDER_PATHS")
        self.assertEqual(fp_meta.attrib.get(f"{ANDROID_NS}resource"), "@xml/mms_file_paths")

        paths_xml = ET.parse(RES_DIR / "xml" / "mms_file_paths.xml").getroot()
        cache_paths = [(el.attrib.get("name"), el.attrib.get("path")) for el in paths_xml.findall("cache-path")]
        files_paths = [(el.attrib.get("name"), el.attrib.get("path")) for el in paths_xml.findall("files-path")]
        self.assertIn(("mms_pdu", "mms_pdu/"), cache_paths)
        self.assertIn(("mms_share", "mms_share/"), files_paths)

        # Non-exported SmsMmsStatusReceiver and BROADCAST_WAP_PUSH MmsWapPushReceiver
        receivers = {el.attrib.get(f"{ANDROID_NS}name"): el for el in app_elem.findall("receiver")}
        status_rx = receivers.get(".data.sms.SmsMmsStatusReceiver")
        self.assertIsNotNone(status_rx)
        self.assertEqual(status_rx.attrib.get(f"{ANDROID_NS}exported"), "false")

        wap_rx = receivers.get(".data.sms.MmsWapPushReceiver")
        self.assertIsNotNone(wap_rx)
        self.assertEqual(wap_rx.attrib.get(f"{ANDROID_NS}permission"), "android.permission.BROADCAST_WAP_PUSH")
        wap_data = wap_rx.find("intent-filter/data")
        self.assertIsNotNone(wap_data)
        self.assertEqual(wap_data.attrib.get(f"{ANDROID_NS}mimeType"), "application/vnd.wap.mms-message")

        # Receiver code contracts
        wap_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/data/sms/MmsWapPushReceiver.kt"))
        self.assertIn("Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION", wap_code)
        self.assertIn('"application/vnd.wap.mms-message"', wap_code)
        self.assertIn("100% Offline Mode", wap_code)
        self.assertIn("handleIncomingMmsWapPush(", wap_code)
        self.assertIn("goAsync()", wap_code)

        status_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/data/sms/SmsMmsStatusReceiver.kt"))
        for token in (
            "ACTION_SMS_SENT",
            "ACTION_SMS_DELIVERED",
            "ACTION_MMS_SENT",
            "ACTION_MMS_DOWNLOADED",
            "SmsManager.EXTRA_MMS_DATA",
            "handleSmsDeliveryStatusCallback(",
            "handleMmsSentResult(",
            "handleDownloadedMmsPdu(",
        ):
            self.assertIn(token, status_code)

        # SystemSmsHelper contracts: immutable PendingIntents, FileProvider URI, carrier package grants, parameterized MMS queries
        helper_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/data/sms/SystemSmsHelper.kt"))
        self.assertIn("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE", helper_code)
        self.assertNotIn("FLAG_MUTABLE", helper_code)
        self.assertIn('"com.android.mms.service"', helper_code)
        self.assertIn('"com.android.phone"', helper_code)
        self.assertIn("smsManager.sendMultimediaMessage(", helper_code)
        self.assertIn("smsManager.downloadMultimediaMessage(", helper_code)
        self.assertIn("smsManager.sendMultipartTextMessage(", helper_code)
        self.assertIn("readSystemMmsInBatches(", helper_code)
        self.assertIn("insertMmsIntoSystemProvider(", helper_code)
        self.assertIn("markSystemMmsRead(", helper_code)
        self.assertIn("deleteMmsFromSystemProvider(", helper_code)
        self.assertIn('File(appContext.noBackupFilesDir, "mms_media")', helper_code)
        self.assertIn('File(appContext.cacheDir, "mms_pdu")', helper_code)


class RcsInteropAndRichUxSpecTest(unittest.TestCase):
    """Spec-anchored tests for Google Messages RCS tapbacks, quoted replies, statuses, and Tink AppSettings."""

    def test_google_messages_tapback_fallback_round_trip_and_edge_cases(self) -> None:
        # All 6 required quick tapbacks encode & decode cleanly
        for emoji in ("👍", "❤️", "😂", "😮", "😢", "😡"):
            encoded = ref_encode_reaction_fallback(emoji, "Meet me at 5 PM")
            parsed = ref_parse_reaction_fallback(encoded)
            self.assertIsNotNone(parsed, f"Failed to parse fallback for {emoji}: {encoded}")
            self.assertEqual(parsed["emoji"], emoji)
            self.assertEqual(parsed["quoted"], "Meet me at 5 PM")
            self.assertFalse(parsed["removal"])

        # Media-only fallback (no body text)
        self.assertEqual(ref_encode_reaction_fallback("👍", "", "image/jpeg"), "Liked an image")
        self.assertEqual(ref_encode_reaction_fallback("😮", "", "video/mp4"), "Reacted 😮 to a video")
        self.assertEqual(ref_parse_reaction_fallback("Liked an image")["emoji"], "👍")
        self.assertEqual(ref_parse_reaction_fallback("Reacted 😮 to a video")["media"], "video")

        # Straight quotes & removal syntax
        straight = ref_parse_reaction_fallback('Loved "Happy Birthday!"')
        self.assertEqual(straight["emoji"], "❤️")
        self.assertEqual(straight["quoted"], "Happy Birthday!")
        removed_like = ref_parse_reaction_fallback(ref_encode_reaction_fallback("👍", "Dinner?", is_removal=True))
        self.assertTrue(removed_like["removal"])
        self.assertEqual(removed_like["emoji"], "👍")
        removed_wow = ref_parse_reaction_fallback(ref_encode_reaction_fallback("😮", "Dinner?", is_removal=True))
        self.assertTrue(removed_wow["removal"])
        self.assertEqual(removed_wow["emoji"], "😮")

        # Verify RcsInteropCodec.kt defines exact quick tapbacks, status formatter, vCard, and reaction toggler
        rcs_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/data/sms/RcsInteropCodec.kt"))
        for emoji in ("👍", "❤️", "😂", "😮", "😢", "😡"):
            self.assertIn(f'"{emoji}"', rcs_code)
        for status_pair in (
            '"SENDING" -> "Sending…"',
            '"SENT" -> "Sent"',
            '"DELIVERED" -> "Delivered"',
            '"READ" -> "Read"',
            '"FAILED" -> "Failed"',
        ):
            self.assertIn(status_pair, rcs_code)
        self.assertIn("fun encodeReactionFallback(", rcs_code)
        self.assertIn("fun parseReactionFallback(", rcs_code)
        self.assertIn("fun toggleReaction(", rcs_code)
        self.assertIn("fun buildVCardContent(", rcs_code)
        self.assertIn("fun parseVCardSummary(", rcs_code)

    def test_repository_and_ui_rich_ux_and_settings_card_contracts(self) -> None:
        repo_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/data/sms/SmsRepository.kt"))
        for token in (
            "observeThreadMessagesWithAttachments(",
            "sendOutgoingMms(",
            "handleIncomingMmsWapPush(",
            "handleDownloadedMmsPdu(",
            "handleMmsSentResult(",
            "handleSmsDeliveryStatusCallback(",
            "downloadMmsByMessageId(",
            "sendReaction(",
            "tryFoldReactionIntoThread(",
            "upgradeDeliveryStatus(",
            'return "GRP_${BackupCryptoEngine.sha256Hex(normalized).take(12)}"',
            'if (rawMms.isRcsBridged) "RCS" else "MMS"',
            "MmsPduCodec.encodeReadRecInd(",
        ):
            self.assertIn(token, repo_code)

        # SettingsScreen RCS & MMS card + preserved onToggleBlockScreenshots parameter
        settings_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/ui/settings/SettingsScreen.kt"))
        for tag in (
            "rcs_mms_settings_card",
            "rcs_chats_switch",
            "auto_download_mms_switch",
            "send_read_receipts_switch",
        ):
            self.assertIn(f'"{tag}"', settings_code)
        self.assertRegex(
            settings_code,
            r"onToggleBlockScreenshots\s*:\s*\(\s*Boolean\s*\)\s*->\s*Unit\s*=\s*\{\s*\}",
        )
        strings_xml = read_text("app/src/main/res/values/strings.xml")
        self.assertIn("RCS &amp; Multimedia Messaging (MMS)", strings_xml)

        # ThreadDetailScreen & ComposeSmsScreen UI tags and preserved message_actions_prompt contract
        thread_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/ui/thread/ThreadDetailScreen.kt"))
        mms_ui_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/ui/thread/MmsUiComponents.kt"))
        compose_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/ui/thread/ComposeSmsScreen.kt"))
        row_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/ui/common/ThreadRowItem.kt"))

        for tag in (
            "reply_context_banner",
            "cancel_reply_button",
            "thread_attach_button",
            "thread_toggle_subject_button",
            "thread_mms_subject_input",
            "message_actions_prompt",
            "message_action_copy",
            "message_action_forward",
        ):
            self.assertIn(f'"{tag}"', thread_code)
        for tag in (
            "attachment_picker_sheet",
            "attach_option_media",
            "attach_option_file",
            "attach_option_contact",
            "attach_option_location",
            "contact_vcard_picker_dialog",
            "location_text_share_dialog",
            "mms_image_fullscreen_dialog",
        ):
            self.assertIn(f'"{tag}"', mms_ui_code)
        for tag in (
            "compose_add_group_recipient_button",
            "compose_group_recipients_row",
            "compose_toggle_subject_button",
            "compose_mms_subject_input",
            "compose_attach_button",
        ):
            self.assertIn(f'"{tag}"', compose_code)
        self.assertIn("thread_transport_badge_", row_code)
        self.assertIn("thread_attachment_icon_", row_code)

    def test_app_settings_and_backup_record_backward_compatible_defaults(self) -> None:
        store_raw = read_text("app/src/main/java/com/example/data/crypto/EncryptedSettingsStore.kt")
        store_code = strip_kotlin_comments(store_raw)
        fields = parse_kotlin_data_class_fields(store_raw, "AppSettings")

        self.assertEqual(fields.get("rcsChatsEnabled"), "true")
        self.assertEqual(fields.get("autoDownloadMms"), "true")
        self.assertEqual(fields.get("sendReadReceipts"), "true")
        self.assertRegex(store_code, r"ignoreUnknownKeys\s*=\s*true")
        self.assertRegex(store_code, r"encodeDefaults\s*=\s*true")

        # Simulate legacy JSON missing the 3 new RCS/MMS fields + containing an obsolete key
        legacy_json = json.dumps(
            {
                "promoAutoDeleteDays": 15,
                "otpAutoDeleteDays": 3,
                "appLockEnabled": True,
                "legacyRemovedFlag": True,
            }
        )
        decoded = json.loads(legacy_json)
        defaults = {"rcsChatsEnabled": True, "autoDownloadMms": True, "sendReadReceipts": True}
        migrated = {k: decoded[k] for k in fields if k in decoded}
        for k, v in defaults.items():
            migrated.setdefault(k, v)
        self.assertNotIn("legacyRemovedFlag", migrated)
        self.assertTrue(migrated["rcsChatsEnabled"])
        self.assertTrue(migrated["autoDownloadMms"])
        self.assertTrue(migrated["sendReadReceipts"])

        backup_raw = read_text("app/src/main/java/com/example/data/crypto/BackupCryptoEngine.kt")
        backup_fields = parse_kotlin_data_class_fields(backup_raw, "BackupMessageRecord")
        for col in ("transportType", "mmsSubject", "replyToSender", "replyToSnippet", "reactionsJson", "groupRecipientsJson"):
            self.assertIn(col, backup_fields)


class RoomMigrationAndDefaultSmsChooserSpecTest(unittest.TestCase):
    """Spec-anchored tests for Room MIGRATION_1_2, Default SMS launch chooser, and security invariants."""

    def test_room_migration_1_to_2_executes_cleanly_on_populated_sqlite_v1_schema(self) -> None:
        db_raw = read_text("app/src/main/java/com/example/data/db/TraiSmsDatabase.kt")
        db_code = strip_kotlin_comments(db_raw)
        self.assertRegex(db_code, r"version\s*=\s*2")
        self.assertRegex(db_code, r"exportSchema\s*=\s*false")
        self.assertIn("LazySqlCipherOpenHelperFactory", db_code)
        self.assertIn(".addMigrations(MIGRATION_1_2)", db_code)
        self.assertNotIn("fallbackToDestructiveMigration", db_code)

        # Extract every db.execSQL(...) SQL string inside MIGRATION_1_2 and run against real sqlite3
        mig_match = re.search(
            r"val\s+MIGRATION_1_2\s*:\s*Migration\s*=\s*object\s*:\s*Migration\s*\(\s*1\s*,\s*2\s*\)\s*\{([\s\S]*?)\n\s*\}",
            db_code,
        )
        self.assertIsNotNone(mig_match, "MIGRATION_1_2 block not found in TraiSmsDatabase.kt")
        mig_body = mig_match.group(1)

        sql_statements: list[str] = []
        for stmt_match in re.finditer(r'db\.execSQL\s*\(\s*(?:"""([\s\S]*?)"""|"([^"]*)")', mig_body):
            raw_sql = stmt_match.group(1) if stmt_match.group(1) is not None else stmt_match.group(2)
            sql_statements.append(raw_sql.strip())
        self.assertGreaterEqual(len(sql_statements), 15, "Expected 13 ALTER TABLE + CREATE TABLE + CREATE INDEX statements")

        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = pathlib.Path(tmpdir) / "v1_vault.db"
            conn = sqlite3.connect(db_path)
            try:
                conn.executescript(
                    """
                    CREATE TABLE sms_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        systemSmsId INTEGER,
                        threadKey TEXT NOT NULL,
                        rawAddress TEXT NOT NULL,
                        contactName TEXT,
                        body TEXT NOT NULL,
                        bodySha256 TEXT NOT NULL,
                        timestampMillis INTEGER NOT NULL,
                        isIncoming INTEGER NOT NULL,
                        isRead INTEGER NOT NULL,
                        isArchived INTEGER NOT NULL,
                        category TEXT NOT NULL,
                        extractedOtp TEXT,
                        confidenceScore REAL NOT NULL,
                        classificationSource TEXT NOT NULL
                    );
                    CREATE TABLE sender_category_rules (
                        normalizedSender TEXT NOT NULL PRIMARY KEY,
                        displaySender TEXT NOT NULL,
                        category TEXT NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    );
                    INSERT INTO sms_messages (
                        systemSmsId, threadKey, rawAddress, contactName, body, bodySha256,
                        timestampMillis, isIncoming, isRead, isArchived, category, extractedOtp,
                        confidenceScore, classificationSource
                    ) VALUES (
                        42, 'VM-HDFCBK-T', 'VM-HDFCBK-T', NULL, '482910 is your OTP', 'abc123',
                        1700000000000, 1, 0, 0, 'OTP', '482910', 0.99, 'TRAI_OTP'
                    );
                    INSERT INTO sender_category_rules (
                        normalizedSender, displaySender, category, updatedAtMillis
                    ) VALUES ('NUM_9876543210', 'Alice', 'PERSONAL', 1700000000000);
                    """
                )
                for sql in sql_statements:
                    conn.execute(sql)
                conn.commit()

                # Verify existing rows and rules survived with expected defaults
                row = conn.execute(
                    "SELECT body, extractedOtp, transportType, deliveryStatus, mmsSizeBytes, isHiddenReaction FROM sms_messages WHERE systemSmsId = 42"
                ).fetchone()
                self.assertEqual(row, ("482910 is your OTP", "482910", "SMS", "NONE", 0, 0))

                rule = conn.execute(
                    "SELECT displaySender, category FROM sender_category_rules WHERE normalizedSender = 'NUM_9876543210'"
                ).fetchone()
                self.assertEqual(rule, ("Alice", "PERSONAL"))

                # Verify mms_attachments table and index exist and accept inserts
                conn.execute(
                    "INSERT INTO mms_attachments (messageId, partIndex, contentType, fileName, localFilePath, contentId, sizeBytes, textContent) "
                    "VALUES (1, 0, 'image/jpeg', 'pic.jpg', '/data/pic.jpg', '<p0>', 1234, NULL)"
                )
                att_count = conn.execute("SELECT COUNT(*) FROM mms_attachments WHERE messageId = 1").fetchone()[0]
                self.assertEqual(att_count, 1)
                idx_rows = [r[1] for r in conn.execute("PRAGMA index_list('mms_attachments')").fetchall()]
                self.assertIn("index_mms_attachments_messageId", idx_rows)
            finally:
                conn.close()

    def test_default_sms_launch_chooser_foreground_and_app_lock_anti_loop_contract(self) -> None:
        helper_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/data/sms/SystemSmsHelper.kt"))
        self.assertIn("RoleManager.ROLE_SMS", helper_code)
        self.assertIn("roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)", helper_code)
        self.assertIn("Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT", helper_code)
        self.assertIn("Telephony.Sms.Intents.EXTRA_PACKAGE_NAME", helper_code)

        vm_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/ui/MainViewModel.kt"))
        self.assertIn("val isSettingsReady: StateFlow<Boolean>", vm_code)
        self.assertIn("_isSettingsReady.value = true", vm_code)

        main_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/MainActivity.kt"))
        # Anti-loop flags and lifecycle tracking
        for token in (
            "isRequestingDefaultSmsRole",
            "hasEnteredStopped",
            "promptedDefaultSmsThisForeground",
            "foregroundEpoch",
            "defaultSmsRoleLauncher",
            "maybePromptDefaultSmsRoleOnLaunch",
        ):
            self.assertIn(token, main_code)

        # onStop must NOT set hasEnteredStopped while chooser is open or rotating
        self.assertRegex(
            main_code,
            r"if\s*\(\s*!isChangingConfigurations\s*&&\s*!isRequestingDefaultSmsRole\s*\)\s*\{\s*hasEnteredStopped\s*=\s*true",
        )
        # onStart must reset promptedDefaultSmsThisForeground and increment foregroundEpoch only after onStop
        self.assertRegex(
            main_code,
            r"if\s*\(\s*hasEnteredStopped\s*\)\s*\{\s*hasEnteredStopped\s*=\s*false\s*;\s*promptedDefaultSmsThisForeground\s*=\s*false|if\s*\(\s*hasEnteredStopped\s*\)\s*\{[\s\S]*?hasEnteredStopped\s*=\s*false[\s\S]*?promptedDefaultSmsThisForeground\s*=\s*false[\s\S]*?foregroundEpoch\s*\+=\s*1",
        )
        # LaunchedEffect must wait for isSettingsReady AND unlock before requesting chooser
        launched_match = re.search(
            r"LaunchedEffect\s*\(\s*isSettingsReady\s*,\s*settings\.appLockEnabled\s*,\s*isSessionUnlocked\s*,\s*foregroundEpoch\s*\)\s*\{([\s\S]*?)\}",
            main_code,
        )
        self.assertIsNotNone(launched_match)
        launched_body = launched_match.group(1)
        self.assertIn("if (!isSettingsReady) return@LaunchedEffect", launched_body)
        self.assertIn("if (settings.appLockEnabled && !isSessionUnlocked) return@LaunchedEffect", launched_body)
        self.assertIn("onRequestDefaultSmsPopup()", launched_body)

    def test_zero_network_permissions_security_hardening_and_screenshot_support_preserved(self) -> None:
        manifest_root = ET.parse(MAIN_DIR / "AndroidManifest.xml").getroot()
        removed = {
            el.attrib.get(f"{ANDROID_NS}name")
            for el in manifest_root.findall("uses-permission")
            if el.attrib.get(f"{TOOLS_NS}node") == "remove"
        }
        self.assertIn("android.permission.INTERNET", removed)
        self.assertIn("android.permission.ACCESS_NETWORK_STATE", removed)

        gradle_txt = read_text("app/build.gradle.kts")
        self.assertIn('maxHeapSize = "3g"', gradle_txt)

        # Ensure no banned HTTP/WebView imports or FLAG_SECURE/FLAG_MUTABLE exist anywhere in main Java/Kotlin sources
        for kt_path in JAVA_DIR.rglob("*.kt"):
            code = strip_kotlin_comments(kt_path.read_text(encoding="utf-8"))
            for banned in (
                "HttpURLConnection",
                "okhttp3",
                "retrofit2",
                "android.webkit.WebView",
                "FLAG_MUTABLE",
                "SecureFlagPolicy.SecureOn",
            ):
                self.assertNotIn(banned, code, f"Forbidden symbol {banned} in {kt_path.relative_to(REPO_ROOT)}")
            self.assertIsNone(
                re.search(r"\b(setFlags|addFlags)\s*\([^)]*FLAG_SECURE", code),
                f"Forbidden FLAG_SECURE set/add in {kt_path.relative_to(REPO_ROOT)}",
            )


if __name__ == "__main__":
    unittest.main()
