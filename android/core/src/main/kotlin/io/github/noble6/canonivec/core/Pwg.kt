// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec.core

import java.io.ByteArrayOutputStream

/** The PWG Raster header fields the IVEC printers are sent (what libcups writes for the Linux filter). */
class PwgHeader(
    val mediaType: String,       // PPD MediaType keyword, "plain"
    val pageSizeName: String,    // "A4", "A4.Borderless"
    val resolution: Int,
    val pageSizePt: IntArray,    // width, height
    val imagingBBoxPt: IntArray, // left, bottom, right, top
    val imageBox: IntArray,      // the same in points * dpi (what Canon's driver fills in)
    val width: Int,
    val height: Int,
    val gray: Boolean,
    val duplex: Boolean = false,
    val tumble: Boolean = false,
) {
    fun bytes(): ByteArray {
        val h = ByteArray(1796)
        fun str(off: Int, s: String) = s.toByteArray(Charsets.US_ASCII).copyInto(h, off, 0, minOf(s.length, 63))
        fun int(off: Int, v: Int) { h[off] = (v ushr 24).toByte(); h[off + 1] = (v ushr 16).toByte(); h[off + 2] = (v ushr 8).toByte(); h[off + 3] = v.toByte() }
        str(0, "PwgRaster")
        str(128, mediaType)
        if (duplex) int(272, 1)
        int(276, resolution); int(280, resolution)
        for (i in 0..3) int(284 + 4 * i, imagingBBoxPt[i])
        int(340, 1)                       // NumCopies
        int(352, pageSizePt[0]); int(356, pageSizePt[1])
        if (tumble) int(368, 1)
        int(372, width); int(376, height)
        int(384, 8)                       // bits per colour
        int(388, if (gray) 8 else 24)
        int(392, width * if (gray) 1 else 3)
        int(400, if (gray) 18 else 19)    // sGray / sRGB
        int(420, if (gray) 1 else 3)
        int(452, 1)                       // TotalPageCount: every page is its own stream
        for (i in 0..3) int(464 + 4 * i, imageBox[i])
        int(480, 0xffffff)                // AlternatePrimary: white
        str(1668, "auto")
        str(1732, pageSizeName)
        return h
    }
}

/**
 * One PWG Raster page ("RaS2" + header + compressed lines), encoded row by
 * row exactly like libcups: identical lines are grouped (up to 256) and each
 * line is run-length coded per pixel, at most 128 pixels per run.
 */
class PwgPageEncoder(header: PwgHeader) {
    private val bpp = if (header.gray) 1 else 3
    private val bpl = header.width * bpp
    private val out = ByteArrayOutputStream(1 shl 16)
    private val prev = ByteArray(bpl)
    private var count = 0
    private var rows = 0
    private val total = header.height

    init {
        out.write("RaS2".toByteArray(Charsets.US_ASCII))
        out.write(header.bytes())
    }

    /** Add the next row: [bpl] bytes starting at [off]. */
    fun writeRow(row: ByteArray, off: Int = 0) {
        check(rows < total) { "too many rows" }
        rows++
        if (count > 0 && count < 256 && regionEquals(row, off)) {
            count++
        } else {
            if (count > 0) flushLine()
            System.arraycopy(row, off, prev, 0, bpl)
            count = 1
        }
        if (rows == total) flushLine()
    }

    private fun regionEquals(row: ByteArray, off: Int): Boolean {
        for (i in 0 until bpl) if (row[off + i] != prev[i]) return false
        return true
    }

    private fun same(a: Int, b: Int): Boolean {
        for (i in 0 until bpp) if (prev[a + i] != prev[b + i]) return false
        return true
    }

    private fun flushLine() {
        if (count == 0) return
        out.write(count - 1)
        val pend = bpl
        val plast = pend - bpp
        var ptr = 0
        while (ptr < pend) {
            val start = ptr
            ptr += bpp
            if (ptr == pend) {
                // a single pixel at the end
                out.write(0)
                out.write(prev, start, bpp)
            } else if (same(start, ptr)) {
                // repeating pixels
                var n = 2
                while (n < 128 && ptr < plast) {
                    if (!same(ptr, ptr + bpp)) break
                    n++; ptr += bpp
                }
                out.write(n - 1)
                out.write(prev, ptr, bpp)
                ptr += bpp
            } else {
                // non-repeating pixels
                var n = 1
                while (n < 128 && ptr < plast) {
                    if (same(ptr, ptr + bpp)) break
                    n++; ptr += bpp
                }
                if (ptr >= plast && n < 128) { n++; ptr += bpp }
                out.write(257 - n)
                out.write(prev, start, n * bpp)
            }
        }
        count = 0
    }

    fun finish(): ByteArray {
        check(rows == total) { "page has $rows of $total rows" }
        return out.toByteArray()
    }
}
