package dev.terminal.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class OpenTypeCmapTest {
    @Test fun format12CoverageIsReturnedAsSortedRanges() {
        val font = ByteBuffer.allocate(80).order(ByteOrder.BIG_ENDIAN)
        font.putInt(0, 0x00010000)
        font.putShort(4, 1.toShort())
        font.putInt(12, 0x636d6170) // cmap
        font.putInt(20, 28)
        font.putInt(24, 52)

        font.putShort(28, 0.toShort())
        font.putShort(30, 1.toShort())
        font.putShort(32, 3.toShort())
        font.putShort(34, 10.toShort())
        font.putInt(36, 12)

        font.putShort(40, 12.toShort())
        font.putShort(42, 0.toShort())
        font.putInt(44, 40)
        font.putInt(48, 0)
        font.putInt(52, 2)
        font.putInt(56, 0x41)
        font.putInt(60, 0x43)
        font.putInt(64, 1)
        font.putInt(68, 0xac00)
        font.putInt(72, 0xac02)
        font.putInt(76, 100)

        assertArrayEquals(
            intArrayOf(0x41, 0x43, 0xac00, 0xac02),
            OpenTypeCmap.codePointRanges(font, 0),
        )

        val padded = ByteBuffer.allocate(font.capacity() + 13)
        padded.position(7)
        padded.put(font.array())
        padded.limit(7 + font.capacity())
        padded.position(7)
        assertArrayEquals(
            intArrayOf(0x41, 0x43, 0xac00, 0xac02),
            OpenTypeCmap.codePointRanges(padded, 0),
        )
    }

    @Test fun format4CoverageIsReturnedWithoutTheSentinel() {
        val font = ByteBuffer.allocate(72).order(ByteOrder.BIG_ENDIAN)
        font.putInt(0, 0x00010000)
        font.putShort(4, 1.toShort())
        font.putInt(12, 0x636d6170) // cmap
        font.putInt(20, 28)
        font.putInt(24, 44)

        font.putShort(28, 0.toShort())
        font.putShort(30, 1.toShort())
        font.putShort(32, 3.toShort())
        font.putShort(34, 1.toShort())
        font.putInt(36, 12)

        font.putShort(40, 4.toShort())
        font.putShort(42, 32.toShort())
        font.putShort(46, 4.toShort()) // segCountX2
        font.putShort(54, 0x43.toShort())
        font.putShort(56, 0xffff.toShort())
        font.putShort(58, 0.toShort())
        font.putShort(60, 0x41.toShort())
        font.putShort(62, 0xffff.toShort())
        font.putShort(64, 1.toShort())
        font.putShort(66, 1.toShort())
        font.putShort(68, 0.toShort())
        font.putShort(70, 0.toShort())

        assertArrayEquals(intArrayOf(0x41, 0x43), OpenTypeCmap.codePointRanges(font, 0))
    }

    @Test fun malformedFontHasNoCoverage() {
        assertArrayEquals(IntArray(0), OpenTypeCmap.codePointRanges(ByteBuffer.allocate(4), 0))
    }
}
