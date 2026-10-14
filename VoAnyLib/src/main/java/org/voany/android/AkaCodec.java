package org.voany.android;

/** TS 31.102 AUTHENTICATE response. No secret material is included in errors. */
public final class AkaCodec {
    private AkaCodec() {}
    public static int validate(byte[] data) {
        if (data == null || data.length < 2) throw new IllegalArgumentException("Empty AKA response");
        int tag = data[0] & 255;
        if (tag == 0xdc && data.length == 16 && (data[1] & 255) == 14) return tag;
        if (tag == 0xdb) {
            int res = data[1] & 255;
            int ck = 2 + res, ik = ck + 17;
            int end = ik + 17;
            if (res >= 4 && res <= 16 && data.length >= end
                    && (data[ck] & 255) == 16 && (data[ik] & 255) == 16
                    && (data.length == end || (data.length == end + 9 && (data[end] & 255) == 8))) return tag;
        }
        throw new IllegalArgumentException("USIM rejected AKA or returned malformed data");
    }
    public static byte[] hex(String value) {
        if (!value.matches("[0-9a-fA-F]{32}"))
            throw new IllegalArgumentException("Enter exactly 32 hexadecimal characters");
        byte[] result = new byte[16];
        for (int i = 0; i < 16; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
