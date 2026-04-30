package com.deadboy18.ghlpos;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * GHL ECR Protocol v1.0.17
 * Packet building, XOR checksum, response parsing.
 * No Android dependencies — pure Java logic.
 */
public class GHLProtocol {

    public static final byte STX = 0x02;
    public static final byte ETX = 0x03;

    public static final String CMD_SALE = "020";
    public static final String CMD_VOID = "022";
    public static final String CMD_REFUND = "026";
    public static final String CMD_SETTLE = "050";

    public static final Map<String, String> CARD_TYPES = new HashMap<>();
    public static final Map<String, String> CMD_NAMES = new HashMap<>();

    static {
        CARD_TYPES.put("04", "VISA");
        CARD_TYPES.put("05", "MASTERCARD");
        CARD_TYPES.put("06", "DINERS");
        CARD_TYPES.put("07", "AMEX");
        CARD_TYPES.put("08", "MYDEBIT");
        CARD_TYPES.put("09", "JCB");
        CARD_TYPES.put("10", "UNIONPAY");
        CARD_TYPES.put("11", "E-WALLET");

        CMD_NAMES.put(CMD_SALE, "SALE");
        CMD_NAMES.put(CMD_VOID, "VOID");
        CMD_NAMES.put(CMD_REFUND, "REFUND");
        CMD_NAMES.put(CMD_SETTLE, "SETTLEMENT");
    }

    /** Calculate 8-byte XOR check digit per GHL spec */
    public static byte[] calcCheckDigit(byte[] data) {
        int rem = data.length % 8;
        int padLen = rem == 0 ? data.length : data.length + (8 - rem);
        byte[] padded = new byte[padLen];
        System.arraycopy(data, 0, padded, 0, data.length);
        if (rem != 0) {
            for (int i = data.length; i < padLen; i++) padded[i] = (byte) 0xFF;
        }
        byte[] chk = new byte[8];
        for (int i = 0; i < padLen; i += 8) {
            for (int j = 0; j < 8; j++) chk[j] ^= padded[i + j];
        }
        return chk;
    }

    /** Build a TX packet: STX + payload(25) + checkdigit(8) + ETX = 35 bytes */
    public static byte[] buildPacket(String cmd, double amount, int invoice, String cashier) {
        long cents = Math.round(amount * 100);
        String payload = String.format(Locale.US, "%s%012d%06d%4s", cmd, cents, invoice, cashier);
        byte[] pb = payload.getBytes();
        byte[] chk = calcCheckDigit(pb);
        byte[] pkt = new byte[1 + pb.length + 8 + 1];
        pkt[0] = STX;
        System.arraycopy(pb, 0, pkt, 1, pb.length);
        System.arraycopy(chk, 0, pkt, 1 + pb.length, 8);
        pkt[pkt.length - 1] = ETX;
        return pkt;
    }

    /** Convert byte array to hex string like "02 30 32 ..." */
    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    /** Format cents to "12.34" */
    public static String formatMoney(String raw) {
        try {
            String s = raw.replaceAll("[^0-9]", "");
            if (s.isEmpty()) return "0.00";
            return String.format(Locale.US, "%.2f", Long.parseLong(s) / 100.0);
        } catch (Exception e) {
            return "0.00";
        }
    }

    /** Extract a field from the payload */
    public static String getField(byte[] payload, int start, int length) {
        if (payload.length < start + length) return "N/A";
        return new String(payload, start, length).trim();
    }

    /** Format masked card number (first 2 bytes = length) */
    public static String formatCard(String raw) {
        if (raw == null || raw.length() < 2) return raw;
        try {
            int len = Integer.parseInt(raw.substring(0, 2));
            return raw.substring(2, Math.min(2 + len, raw.length())).replace('X', '*');
        } catch (Exception e) {
            return raw;
        }
    }

    /** Parse a complete RX packet into a receipt object */
    public static ParsedResponse parse(byte[] data, String cmd) {
        ParsedResponse r = new ParsedResponse();
        if (data == null || data.length < 11) {
            r.errorCode = "??";
            return r;
        }

        // Strip STX and checkdigit+ETX
        int payloadLen = data.length - 10; // 1(STX) + 8(chk) + 1(ETX)
        byte[] p = new byte[payloadLen];
        System.arraycopy(data, 1, p, 0, payloadLen);
        int pL = p.length;

        r.errorCode = getField(p, 3, 2);
        r.approved = "00".equals(r.errorCode);
        r.txType = CMD_NAMES.containsKey(cmd) ? CMD_NAMES.get(cmd) : "SALE";

        if (pL >= 33) {
            String tc = getField(p, 31, 2);
            r.cardTypeCode = tc;
            r.cardTypeName = CARD_TYPES.containsKey(tc) ? CARD_TYPES.get(tc) : "UNKNOWN";
        }

        r.cardNumber = formatCard(getField(p, 5, 22));
        r.expiry = getField(p, 27, 4);
        r.authCode = getField(p, 33, 8);
        r.grossAmount = formatMoney(getField(p, 41, 12));
        r.netAmount = formatMoney(getField(p, 53, 12));
        r.stan = getField(p, 65, 6);
        r.invoice = getField(p, 71, 6);
        r.cashier = getField(p, 77, 4);
        r.cardName = pL >= 96 ? getField(p, 81, 15) : "N/A";
        r.tid = pL >= 104 ? getField(p, 96, 8) : "N/A";
        r.mid = pL >= 119 ? getField(p, 104, 15) : "N/A";
        r.batch = pL >= 125 ? getField(p, 119, 6) : "N/A";
        r.payloadLength = payloadLen;
        r.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

        return r;
    }

    /** Build receipt text from parsed response */
    public static String buildReceipt(ParsedResponse r) {
        return "MERCHANT ID:  " + r.mid +
                "\nTERMINAL ID:  " + r.tid +
                "\nTIME:         " + r.timestamp +
                "\nBATCH NO:     " + r.batch +
                "\n\n------------------------------" +
                "\nSTAN:         " + r.stan +
                "\nINVOICE:      " + r.invoice +
                "\n------------------------------" +
                "\n\nTRANS TYPE:   " + r.txType +
                "\nCASHIER ID:   " + r.cashier +
                "\n\nCARD NO:      " + r.cardNumber +
                "\nCARD TYPE:    " + r.cardTypeCode + " (" + r.cardTypeName + ")" +
                "\nAUTH CODE:    " + r.authCode +
                "\n\n------------------------------" +
                "\nGROSS AMT:    RM " + r.grossAmount +
                "\nNET AMT:      RM " + r.netAmount +
                "\n------------------------------" +
                "\n\n        THANK YOU!\n";
    }

    /** Parsed response data holder */
    public static class ParsedResponse {
        public boolean approved;
        public String errorCode = "";
        public String txType = "";
        public String cardNumber = "";
        public String cardTypeCode = "";
        public String cardTypeName = "";
        public String expiry = "";
        public String authCode = "";
        public String grossAmount = "0.00";
        public String netAmount = "0.00";
        public String stan = "";
        public String invoice = "";
        public String cashier = "";
        public String cardName = "";
        public String tid = "N/A";
        public String mid = "N/A";
        public String batch = "N/A";
        public String timestamp = "";
        public int payloadLength;
    }
}
