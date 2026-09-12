package dev.terminal.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet

/** Reads Unicode coverage from the cmap table of an OpenType font or TTC face. */
internal object OpenTypeCmap {
    private const val TAG_TTCF = 0x74746366
    private const val TAG_CMAP = 0x636d6170
    private const val MAX_CODE_POINT = 0x10ffff

    fun codePointRanges(source: ByteBuffer, ttcIndex: Int): IntArray = runCatching {
        // Font.getBuffer() identifies its font-data range with position and limit.
        // Slice it so OpenType offsets are relative to the start of that range.
        val buffer = source.slice().order(ByteOrder.BIG_ENDIAN)
        val faceOffset = faceOffset(buffer, ttcIndex)
        val tableCount = u16(buffer, faceOffset + 4)
        var cmapOffset = -1
        var cmapLength = 0
        repeat(tableCount) { index ->
            val record = faceOffset + 12 + index * 16
            requireRange(buffer, record, 16)
            if (buffer.getInt(record) == TAG_CMAP) {
                cmapOffset = u32AsInt(buffer, record + 8)
                cmapLength = u32AsInt(buffer, record + 12)
            }
        }
        require(cmapOffset >= 0 && cmapLength >= 4)
        requireRange(buffer, cmapOffset, cmapLength)

        val coverage = BitSet(MAX_CODE_POINT + 1)
        val encodingCount = u16(buffer, cmapOffset + 2)
        requireRangeWithin(cmapOffset, cmapLength, cmapOffset + 4, encodingCount * 8)
        repeat(encodingCount) { index ->
            val record = cmapOffset + 4 + index * 8
            val platform = u16(buffer, record)
            val encoding = u16(buffer, record + 2)
            if (platform == 0 || platform == 3 && (encoding == 0 || encoding == 1 || encoding == 10)) {
                val subtable = cmapOffset + u32AsInt(buffer, record + 4)
                requireRangeWithin(cmapOffset, cmapLength, subtable, 2)
                when (val format = u16(buffer, subtable)) {
                    4, 12, 13 -> {
                        requireRangeWithin(cmapOffset, cmapLength, subtable, if (format == 4) 4 else 8)
                        val length = if (format == 4) u16(buffer, subtable + 2)
                        else u32AsInt(buffer, subtable + 4)
                        requireRangeWithin(cmapOffset, cmapLength, subtable, length)
                        when (format) {
                            4 -> readFormat4(buffer, subtable, coverage)
                            12 -> readFormat12Or13(buffer, subtable, coverage, constantGlyph = false)
                            else -> readFormat12Or13(buffer, subtable, coverage, constantGlyph = true)
                        }
                    }
                }
            }
        }
        compress(coverage)
    }.getOrDefault(IntArray(0))

    private fun faceOffset(buffer: ByteBuffer, ttcIndex: Int): Int {
        require(ttcIndex >= 0)
        requireRange(buffer, 0, 12)
        if (buffer.getInt(0) != TAG_TTCF) {
            require(ttcIndex == 0)
            return 0
        }
        val faceCount = u32AsInt(buffer, 8)
        require(ttcIndex < faceCount)
        return u32AsInt(buffer, 12 + ttcIndex * 4)
    }

    private fun readFormat12Or13(
        buffer: ByteBuffer,
        offset: Int,
        coverage: BitSet,
        constantGlyph: Boolean,
    ) {
        requireRange(buffer, offset, 16)
        val length = u32AsInt(buffer, offset + 4)
        requireRange(buffer, offset, length)
        val groupCount = u32AsInt(buffer, offset + 12)
        require(groupCount <= (length - 16) / 12)
        repeat(groupCount) { index ->
            val group = offset + 16 + index * 12
            var start = u32AsInt(buffer, group)
            val end = u32AsInt(buffer, group + 4)
            val glyph = u32AsInt(buffer, group + 8)
            require(start <= end && end <= MAX_CODE_POINT)
            if (constantGlyph) {
                if (glyph != 0) coverage.set(start, end + 1)
            } else {
                // Glyph zero is .notdef. In a sequential format-12 group only its first mapping
                // needs to be omitted.
                if (glyph == 0) start++
                if (start <= end) coverage.set(start, end + 1)
            }
        }
    }

    private fun readFormat4(buffer: ByteBuffer, offset: Int, coverage: BitSet) {
        requireRange(buffer, offset, 16)
        val length = u16(buffer, offset + 2)
        requireRange(buffer, offset, length)
        val segmentCount = u16(buffer, offset + 6) / 2
        require(segmentCount > 0)
        val endCodes = offset + 14
        val startCodes = endCodes + segmentCount * 2 + 2
        val deltas = startCodes + segmentCount * 2
        val rangeOffsets = deltas + segmentCount * 2
        requireRange(buffer, rangeOffsets, segmentCount * 2)

        var previousEnd = -1
        repeat(segmentCount) { index ->
            val start = u16(buffer, startCodes + index * 2)
            val end = u16(buffer, endCodes + index * 2)
            val delta = buffer.getShort(deltas + index * 2).toInt()
            val rangeOffsetPosition = rangeOffsets + index * 2
            val rangeOffset = u16(buffer, rangeOffsetPosition)
            require(start <= end && start > previousEnd)
            previousEnd = end
            for (codePoint in start..end) {
                if (codePoint == 0xffff) continue
                val glyph = if (rangeOffset == 0) {
                    (codePoint + delta) and 0xffff
                } else {
                    val glyphAddress = rangeOffsetPosition + rangeOffset + (codePoint - start) * 2
                    requireRangeWithin(offset, length, glyphAddress, 2)
                    val rawGlyph = u16(buffer, glyphAddress)
                    if (rawGlyph == 0) 0 else (rawGlyph + delta) and 0xffff
                }
                if (glyph != 0) coverage.set(codePoint)
            }
        }
    }

    private fun compress(coverage: BitSet): IntArray {
        val ranges = ArrayList<Int>()
        var start = coverage.nextSetBit(0)
        while (start >= 0) {
            val endExclusive = coverage.nextClearBit(start)
            ranges += start
            ranges += endExclusive - 1
            start = coverage.nextSetBit(endExclusive)
        }
        return ranges.toIntArray()
    }

    private fun u16(buffer: ByteBuffer, offset: Int): Int {
        requireRange(buffer, offset, 2)
        return buffer.getShort(offset).toInt() and 0xffff
    }

    private fun u32AsInt(buffer: ByteBuffer, offset: Int): Int {
        requireRange(buffer, offset, 4)
        val value = buffer.getInt(offset).toLong() and 0xffffffffL
        require(value <= Int.MAX_VALUE)
        return value.toInt()
    }

    private fun requireRange(buffer: ByteBuffer, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= buffer.limit())
    }

    private fun requireRangeWithin(containerOffset: Int, containerLength: Int, offset: Int, length: Int) {
        require(offset >= containerOffset && length >= 0)
        require(offset.toLong() + length <= containerOffset.toLong() + containerLength)
    }
}
