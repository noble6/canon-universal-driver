// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec.core

import java.io.OutputStream
import kotlin.math.roundToInt

enum class Duplex { NONE, LONG_EDGE, SHORT_EDGE }
enum class Quality { DRAFT, NORMAL, HIGH }

class JobSettings(
    val size: PaperSize,
    val media: Media,
    val gray: Boolean = false,
    val duplex: Duplex = Duplex.NONE,
    val quality: Quality = Quality.NORMAL,
    val uuid: String? = null,
    /** Manual 2-sided: the printer's paper path turns the backs (CNManualBack=Turned). */
    val manualBackTurned: Boolean = false,
)

/**
 * A print job, the job logic of src/ivec/rastertocanonivec.c.  Pages are
 * added as rows of RGB pixels already laid out on the print area
 * ([outWidth] x [outHeight]; pages for which [rotate] is true must be
 * turned 180 degrees by the caller).  Pages are kept compressed in memory
 * and written by [writeTo], or [writeFronts] / [writeBacks] for manual
 * 2-sided printing.
 */
class IvecPrintJob(
    val model: Model,
    val settings: JobSettings,
    private val hostEnv: String = "linux",
    private val clock: () -> String = Ivec::now,
) {
    val grayRequested = model.monoOnly || settings.gray
    /** No duplex unit: fronts, pause for the user to reload the stack, backs. */
    val manualDuplex = model.manualDuplex && settings.duplex != Duplex.NONE
    private val autoDuplex = model.duplex && settings.duplex != Duplex.NONE

    val printQuality: String?
    val draft: Boolean

    init {
        var pq: String? = null
        var d = false
        when (settings.quality) {
            Quality.HIGH -> if (model.hasHigh) pq = "best"
            Quality.DRAFT -> if (model.hasDraft && settings.media.ivec in model.draftMedia) { pq = "fast_draft"; d = true }
            Quality.NORMAL -> {}
        }
        printQuality = pq
        draft = d
    }

    /** Output resolution: draft pages go at the model's draft resolution. */
    val resolution = if (draft) model.draftRes else model.resolution
    val outWidth = (settings.size.printW.toLong() * resolution / model.resolution).toInt()
    val outHeight = (settings.size.printH.toLong() * resolution / model.resolution).toInt()

    /** PWG ImagingBBox (points) and ImageBox (points * dpi); the Linux filter gets them from Ghostscript. */
    var imagingBBox: IntArray
    var imageBox: IntArray

    init {
        val s = settings.size
        val box = if (s.borderless) doubleArrayOf(0.0, 0.0, s.widthPt, s.heightPt) else s.imageable
        imagingBBox = IntArray(4) { box[it].roundToInt() }
        imageBox = IntArray(4) { (box[it] * resolution).toInt() }
    }

    private class Page(val color: ByteArray?, val gray: ByteArray?)
    private val pages = ArrayList<Page>()
    private var jobGray = true

    val pageCount get() = pages.size

    /** Whether page [number] (1-based) must be rendered turned by 180 degrees. */
    fun rotate(number: Int): Boolean =
        if (manualDuplex) {
            // reloaded blank side facing the user, TOP edge first, with pages
            // rendered upright: short-edge backs are turned, long-edge backs
            // not (verified on a G3010 from Android)
            number % 2 == 0 && ((settings.duplex == Duplex.SHORT_EDGE) != settings.manualBackTurned)
        } else {
            model.duplex && settings.duplex == Duplex.LONG_EDGE && number % 2 == 1
        }

    private fun header(gray: Boolean) = PwgHeader(
        mediaType = settings.media.name,
        pageSizeName = settings.size.name.replace(".bl", ".Borderless"),
        resolution = resolution,
        pageSizePt = intArrayOf(settings.size.widthPt.roundToInt(), settings.size.heightPt.roundToInt()),
        imagingBBoxPt = imagingBBox,
        imageBox = imageBox,
        width = outWidth,
        height = outHeight,
        gray = gray,
        duplex = settings.duplex != Duplex.NONE && (model.duplex || model.manualDuplex),
        tumble = settings.duplex == Duplex.SHORT_EDGE && (model.duplex || model.manualDuplex),
    )

    /**
     * Add a page.  [fill] is called for every row y in 0 until [outHeight]
     * and must write outWidth * 3 RGB bytes into the buffer it is given.
     */
    fun addPage(fill: (y: Int, rgb: ByteArray) -> Unit) {
        // "optimization" models get 1-channel pages for grayscale jobs, and a
        // colour job that turns out to have no colour is sent as grayscale
        val wantColor = !(model.optimization && grayRequested)
        val wantGray = model.optimization && (grayRequested || jobGray)
        val ce = if (wantColor) PwgPageEncoder(header(false)) else null
        val ge = if (wantGray) PwgPageEncoder(header(true)) else null
        val rgb = ByteArray(outWidth * 3)
        val g = if (ge != null) ByteArray(outWidth) else null
        var colorful = false
        for (y in 0 until outHeight) {
            java.util.Arrays.fill(rgb, 0xff.toByte())
            fill(y, rgb)
            if (!colorful) colorful = hasColor(rgb)
            ce?.writeRow(rgb)
            if (ge != null) { toGray(rgb, g!!); ge.writeRow(g) }
        }
        if (colorful) jobGray = false
        pages.add(Page(ce?.finish(), ge?.finish()))
    }

    private fun hasColor(rgb: ByteArray): Boolean {
        var i = 0
        while (i < rgb.size) {
            if (rgb[i] != rgb[i + 1] || rgb[i + 1] != rgb[i + 2]) return true
            i += 3
        }
        return false
    }

    /** Canon's luminance for 1-channel pages (Rec. 709 weights, truncated). */
    private fun toGray(rgb: ByteArray, out: ByteArray) {
        for (x in out.indices) {
            val r = rgb[3 * x].toInt() and 0xff
            val gg = rgb[3 * x + 1].toInt() and 0xff
            val b = rgb[3 * x + 2].toInt() and 0xff
            out[x] = ((2126 * r + 7152 * gg + 722 * b) / 10000).toByte()
        }
    }

    private val sendGray get() = model.optimization && (grayRequested || jobGray)
    private val monochrome get() = if (model.optimization && !grayRequested) jobGray else grayRequested

    private fun data(i: Int) = (if (sendGray) pages[i].gray else pages[i].color)!!

    private fun send(out: OutputStream, first: Int, step: Int) {
        val job = Ivec.Job(out, model.protocol, model.datetime, settings.uuid, hostEnv, clock)
        job.startJob()
        job.setConfiguration(settings.size.pwg, settings.media.ivec, settings.size.borderless, monochrome,
            autoDuplex, printQuality)
        var i = first
        while (i < pages.size) {
            job.sendPage(i + step < pages.size, data(i))
            i += step
        }
        job.endJob()
    }

    /** The whole job (any mode except manual 2-sided). */
    fun writeTo(out: OutputStream) {
        check(pages.isNotEmpty()) { "no pages" }
        if (manualDuplex) writeFronts(out) else send(out, 0, 1)
    }

    /** Manual 2-sided: the front sides (pages 1, 3, 5...). */
    fun writeFronts(out: OutputStream) = send(out, 0, 2)

    /** Manual 2-sided: the back sides (pages 2, 4, ...), after the user reloaded the stack. */
    fun writeBacks(out: OutputStream) { if (pages.size > 1) send(out, 1, 2) }
}

/** Maintenance commands, as cmdtocanonivec sends them. */
object Maintenance {
    class Unsupported(msg: String) : Exception(msg)

    /**
     * Write a maintenance job for [op] (Cleaning, RollerCleaning, TestPrint)
     * and [type] (regular, deep, choke, roller, platen, nozzle_check, ...).
     */
    fun write(out: OutputStream, model: Model, op: String, type: String, group: String? = null,
              uuid: String? = null, clock: () -> String = Ivec::now) {
        val g = group ?: if (op == "Cleaning") "all" else null
        if (model.maintStyle == "bjl") {
            // older engines only take the two classic BJL maintenance lines
            val line = when {
                op == "Cleaning" && type == "regular" && (g == null || g == "all") -> "@Cleaning=1ALL"
                op == "TestPrint" && type == "nozzle_check" -> "@TestPrint=NozzleCheck"
                else -> throw Unsupported("$op $type is not available on this printer")
            }
            Ivec.Job(out, model.protocol, model.datetime, uuid, clock = clock).maintenanceBjl(line)
            return
        }
        if (model.maint.isNotEmpty()) {
            val groups = model.maint[op]?.get(type) ?: throw Unsupported("$op $type is not available on this printer")
            val ok = if (groups.isEmpty()) g == null || g == "all" else g == null || g in groups
            if (!ok) throw Unsupported("$op $type $g is not available on this printer")
        }
        Ivec.maintenance(out, model.maintDatetime, model.maintHostEnv, uuid, op, type,
            if (op == "Cleaning") g else null, clock)
    }

    /** Head alignment type this printer accepts from a computer, or null. */
    fun alignmentType(model: Model): String? =
        if (model.maintStyle == "bjl") null
        else listOf("auto_registration", "half_auto_registration", "regi_check").firstOrNull { model.supports("TestPrint", it) }
}
