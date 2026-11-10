// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import io.github.noble6.canonivec.core.IvecPrintJob

/**
 * Lays PDF pages out on the printer's print area and feeds them to the job
 * row by row.  Pages are rendered in horizontal bands so a 600 dpi page
 * never has to fit in memory at once.
 *
 * Layout matches the Linux filter: the page is fitted into the printable
 * area (keeping its aspect), and on borderless sizes it fills the whole
 * borderless area, which runs a little past the paper edges.
 */
class PageRenderer(private val pdf: PdfRenderer, private val job: IvecPrintJob) {
    private val outW = job.outWidth
    private val outH = job.outHeight
    private val band = maxOf(16, minOf(512, (24 shl 20) / (outW * 4)))
    private val bitmap = Bitmap.createBitmap(outW, band, Bitmap.Config.ARGB_8888)
    private val px = IntArray(outW * band)

    val pageCount get() = pdf.pageCount

    /** Add PDF page [index] as printed page [number] (1-based), or a blank page when index is -1. */
    fun addPage(index: Int, number: Int, cancelled: () -> Boolean) {
        if (index < 0) {
            job.addPage { _, _ -> }
            return
        }
        val page = pdf.openPage(index)
        try {
            val m = layout(page.width.toFloat(), page.height.toFloat(), job.rotate(number))
            var bandTop = -1
            job.addPage { y, rgb ->
                if (cancelled()) throw UsbPrinter.CancelledException()
                if (bandTop < 0 || y >= bandTop + band) {
                    bandTop = y
                    bitmap.eraseColor(Color.WHITE)
                    val bm = Matrix(m)
                    bm.postTranslate(0f, -bandTop.toFloat())
                    page.render(bitmap, null, bm, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    bitmap.getPixels(px, 0, outW, 0, 0, outW, band)
                }
                val base = (y - bandTop) * outW
                var o = 0
                for (x in 0 until outW) {
                    val c = px[base + x]
                    val a = c ushr 24
                    var r = (c shr 16) and 0xff
                    var g = (c shr 8) and 0xff
                    var b = c and 0xff
                    if (a != 255) {
                        // composite on white paper
                        r = (r * a + 255 * (255 - a)) / 255
                        g = (g * a + 255 * (255 - a)) / 255
                        b = (b * a + 255 * (255 - a)) / 255
                    }
                    rgb[o] = r.toByte(); rgb[o + 1] = g.toByte(); rgb[o + 2] = b.toByte()
                    o += 3
                }
            }
        } finally {
            page.close()
        }
    }

    /** Page points to print-area pixels. */
    private fun layout(pw: Float, ph: Float, turn180: Boolean): Matrix {
        val m = Matrix()
        var w = pw
        var h = ph
        // a landscape page on portrait paper is turned a quarter
        if ((pw > ph) != (outW > outH) && pw != ph) {
            m.postRotate(90f)
            m.postTranslate(ph, 0f)
            w = ph; h = pw
        }
        val kx = outW / w
        val ky = outH / h
        val k = if (job.settings.size.borderless) maxOf(kx, ky) else minOf(kx, ky)
        m.postScale(k, k)
        m.postTranslate((outW - w * k) / 2f, (outH - h * k) / 2f)
        if (turn180) m.postRotate(180f, outW / 2f, outH / 2f)
        return m
    }

    fun recycle() = bitmap.recycle()
}
