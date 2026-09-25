package com.example.combatguard.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StringCodecTest {
    @Test
    void roundTripMatchesMinecraftStringCodec() {
        String text = "{\"nonce\":\"abc\"}" + "x".repeat(300);
        byte[] encoded = StringCodec.encode(text);
        // 315 bytes needs a two byte VarInt: 0xBB 0x02
        assertEquals((byte) 0xBB, encoded[0]);
        assertEquals((byte) 0x02, encoded[1]);
        assertEquals(text, StringCodec.decode(encoded, 1 << 18));
    }

    @Test
    void rejectsTruncatedPayload() {
        byte[] encoded = StringCodec.encode("hello");
        byte[] truncated = new byte[encoded.length - 2];
        System.arraycopy(encoded, 0, truncated, 0, truncated.length);
        assertNull(StringCodec.decode(truncated, 1 << 18));
    }
}
