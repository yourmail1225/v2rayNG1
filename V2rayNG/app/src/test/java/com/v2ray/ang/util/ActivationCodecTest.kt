package com.v2ray.ang.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ActivationCodecTest {

    @Test
    fun emptyInputRoundTrips() {
        assertArrayEquals(ByteArray(0), ActivationCodec.decode(""))
        assertEquals("", ActivationCodec.encode(ByteArray(0)))
    }

    @Test
    fun encodesRfc4648Vectors() {
        assertEquals("TWFu", ActivationCodec.encode("Man".toByteArray(Charsets.UTF_8)))
        assertEquals("TWE=", ActivationCodec.encode("Ma".toByteArray(Charsets.UTF_8)))
        assertEquals("TQ==", ActivationCodec.encode("M".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun decodesRfc4648Vectors() {
        assertEquals("Man", String(ActivationCodec.decode("TWFu"), Charsets.UTF_8))
        assertEquals("Ma", String(ActivationCodec.decode("TWE="), Charsets.UTF_8))
        assertEquals("M", String(ActivationCodec.decode("TQ=="), Charsets.UTF_8))
    }

    @Test
    fun roundTripsArbitraryBytes() {
        listOf(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            byteArrayOf(0, 0, 0, 0),
            ByteArray(31) { (it * 7).toByte() },
        ).forEach { raw ->
            assertArrayEquals(raw, ActivationCodec.decode(ActivationCodec.encode(raw)))
        }
    }

    @Test
    fun ignoresSurroundingWhitespaceWhenDecoding() {
        assertEquals("Man", String(ActivationCodec.decode("  TWFu\n\t"), Charsets.UTF_8))
    }

    @Test
    fun rejectsInvalidCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivationCodec.decode("TWF\$u")
        }
    }

    @Test
    fun rejectsInputThatCannotHoldAWholeByte() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivationCodec.decode("A")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivationCodec.decode("TWF")
        }
    }
}