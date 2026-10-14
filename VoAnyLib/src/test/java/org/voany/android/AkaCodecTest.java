package org.voany.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class AkaCodecTest {
    @Test public void acceptsOptionalGsmKcOnlyWithExactLength() {
        byte[] data = new byte[53];
        data[0] = (byte)0xdb; data[1] = 8;
        data[10] = 16; data[27] = 16; data[44] = 8;
        assertEquals(0xdb, AkaCodec.validate(data));
        data[44] = 7;
        assertThrows(IllegalArgumentException.class, () -> AkaCodec.validate(data));
    }
    @Test public void acceptsSuccessWithVariableResLength() {
        for (int res = 4; res <= 16; res++) {
            byte[] data = new byte[36 + res];
            data[0] = (byte)0xdb; data[1] = (byte)res;
            data[2 + res] = 16; data[19 + res] = 16;
            assertEquals(0xdb, AkaCodec.validate(data));
        }
    }
    @Test public void acceptsOnlyFourteenByteAuts() {
        byte[] response = new byte[16]; response[0] = (byte)0xdc; response[1] = 14;
        assertEquals(0xdc, AkaCodec.validate(response));
        response[1] = 13;
        assertThrows(IllegalArgumentException.class, () -> AkaCodec.validate(response));
    }
    @Test public void rejectsTruncatedOrTrailingData() {
        for (int length : new int[]{0, 1, 2, 39, 41, 300}) {
            byte[] data = new byte[length];
            if (length >= 2) { data[0] = (byte)0xdb; data[1] = 4; }
            assertThrows(IllegalArgumentException.class, () -> AkaCodec.validate(data));
        }
    }
    @Test public void rejectsInvalidKeyLengthAndUnsignedOverflow() {
        byte[] response = new byte[40]; response[0] = (byte)0xdb; response[1] = 4;
        response[6] = 15; response[23] = 16;
        assertThrows(IllegalArgumentException.class, () -> AkaCodec.validate(response));
        response[1] = (byte)255;
        assertThrows(IllegalArgumentException.class, () -> AkaCodec.validate(response));
    }
    @Test public void hexRequiresExactRandLength() {
        assertArrayEquals(new byte[16], AkaCodec.hex("00000000000000000000000000000000"));
        assertThrows(IllegalArgumentException.class, () -> AkaCodec.hex("00"));
        assertThrows(IllegalArgumentException.class, () -> AkaCodec.hex("zz000000000000000000000000000000"));
    }
}
