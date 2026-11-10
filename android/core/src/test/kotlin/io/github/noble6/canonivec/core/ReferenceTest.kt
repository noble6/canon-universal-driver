// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec.core

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Rebuilds streams written by the Linux filter (rastertocanonivec, itself
 * verified byte for byte against Canon's driver) from their decoded pixels
 * and compares the bytes.  The reference streams are made by
 * research/android-refs.sh; without them only the self-contained tests run.
 */
class ReferenceTest {
    private val db = ModelDatabase.parse(File(System.getProperty("models.json")).readText())
    private val refDir = File(System.getProperty("ivec.refs") ?: "")

    private fun ref(name: String): ByteArray? = File(refDir, "$name.bin").takeIf { it.isFile }?.readBytes()

    private fun norm(b: ByteArray) =
        String(b, Charsets.ISO_8859_1).replace(Regex("(datetime>|SetTime=)[0-9]{14}"), "$1T")

    /** PWG pages of an IVEC stream: the blob after each SendData. */
    private fun pages(stream: ByteArray): List<ByteArray> {
        val s = String(stream, Charsets.ISO_8859_1)
        val out = ArrayList<ByteArray>()
        val re = Regex("<ivec:datasize>([0-9]+)</ivec:datasize></ivec:param_set></ivec:contents></cmd>")
        var from = 0
        while (true) {
            val m = re.find(s, from) ?: break
            val start = m.range.last + 1
            val n = m.groupValues[1].toInt()
            out.add(stream.copyOfRange(start, start + n))
            from = start + n
        }
        return out
    }

    private fun u32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private class Decoded(val w: Int, val h: Int, val bpp: Int, val rows: Array<ByteArray>, val bbox: IntArray, val box: IntArray)

    private fun decode(p: ByteArray): Decoded {
        val hd = 4
        val w = u32(p, hd + 372); val h = u32(p, hd + 376); val bpp = u32(p, hd + 388) / 8
        val bbox = IntArray(4) { u32(p, hd + 284 + 4 * it) }
        val box = IntArray(4) { u32(p, hd + 464 + 4 * it) }
        val rows = Array(h) { ByteArray(w * 3) }
        var i = 4 + 1796
        var y = 0
        while (y < h) {
            val rep = (p[i++].toInt() and 0xff) + 1
            val line = ByteArray(w * bpp)
            var x = 0
            while (x < line.size) {
                val c = p[i++].toInt() and 0xff
                if (c < 128) {
                    repeat(c + 1) { System.arraycopy(p, i, line, x, bpp); x += bpp }
                    i += bpp
                } else {
                    val n = (257 - c) * bpp
                    System.arraycopy(p, i, line, x, n); x += n; i += n
                }
            }
            // feed gray pages back as neutral RGB; to_gray maps it to itself
            val rgb = if (bpp == 3) line else ByteArray(w * 3) { line[it / 3] }
            repeat(rep) { rows[y++] = rgb }
        }
        return Decoded(w, h, bpp, rows, bbox, box)
    }

    private fun rebuild(name: String, id: String, size: String, media: String = "plain", gray: Boolean = false,
                        duplex: Duplex = Duplex.NONE, quality: Quality = Quality.NORMAL) {
        val ref = ref(name)
        assumeTrue(ref != null, "reference $name not available")
        val model = assertNotNull(db.byId(id))
        val job = IvecPrintJob(model, JobSettings(model.size(size)!!, model.media(media)!!, gray, duplex, quality,
            "urn:uuid:1"))
        val decoded = pages(ref!!).map(::decode)
        assertEquals(job.outWidth, decoded[0].w)
        assertEquals(job.outHeight, decoded[0].h)
        job.imagingBBox = decoded[0].bbox
        job.imageBox = decoded[0].box
        for (d in decoded) job.addPage { y, rgb -> System.arraycopy(d.rows[y], 0, rgb, 0, rgb.size) }
        val out = ByteArrayOutputStream()
        job.writeTo(out)
        assertEquals(norm(ref), norm(out.toByteArray()), "$name differs")
    }

    @Test fun g3010Color() = rebuild("g3010", "canong3010", "A4")
    @Test fun g3010BorderlessPhoto() = rebuild("g3010-bl", "canong3010", "A4.bl", "glossygold")
    @Test fun g3010GrayHigh() = rebuild("g3010-gray", "canong3010", "A4", gray = true, quality = Quality.HIGH)
    @Test fun g3010Draft() = rebuild("g3010-draft", "canong3010", "A4", quality = Quality.DRAFT)
    @Test fun g3080DuplexLong() = rebuild("g3080-dup", "canong3080", "A4", duplex = Duplex.LONG_EDGE)
    @Test fun g3080GrayShort() = rebuild("g3080-gray", "canong3080", "A4", gray = true, duplex = Duplex.SHORT_EDGE)
    @Test fun g3080AutoGray() = rebuild("g3080-auto", "canong3080", "A4")
    @Test fun e200V1() = rebuild("e200", "canone200", "A4")

    private fun maint(name: String, id: String, op: String, type: String, group: String? = null) {
        val ref = ref(name)
        assumeTrue(ref != null, "reference $name not available")
        val out = ByteArrayOutputStream()
        Maintenance.write(out, db.byId(id)!!, op, type, group, "urn:uuid:1")
        assertEquals(norm(ref!!), norm(out.toByteArray()), "$name differs")
    }

    @Test fun g3010Clean() = maint("m-g3010-clean", "canong3010", "Cleaning", "regular")
    @Test fun g3010Deep() = maint("m-g3010-deep", "canong3010", "Cleaning", "deep", "group1")
    @Test fun g3010Nozzle() = maint("m-g3010-nozzle", "canong3010", "TestPrint", "nozzle_check")
    @Test fun ts8000Clean() = maint("m-ts8000-clean", "canonts8000", "Cleaning", "regular")
    @Test fun ts8000Nozzle() = maint("m-ts8000-nozzle", "canonts8000", "TestPrint", "nozzle_check")
    @Test fun e200Clean() = maint("m-e200-clean", "canone200", "Cleaning", "regular")

    @Test fun ts8000HasNoAlignment() {
        assertEquals(null, Maintenance.alignmentType(db.byId("canonts8000")!!))
        assertTrue(Maintenance.alignmentType(db.byId("canong3010")!!) != null)
    }

    /** The V1/V2 rule agrees with every profiled model whose device ID is known. */
    @Test fun deviceIdRule() {
        var checked = 0
        for (m in db.models) {
            if (m.generic || !m.deviceId.contains("CMD:")) continue
            assertEquals(m.protocol == 1, ModelDatabase.isV1Device(m.deviceId), m.id)
            assertEquals(m.id, db.forDeviceId(m.deviceId).id)
            checked++
        }
        assertTrue(checked > 50)
        assertEquals("generic-v1", db.forDeviceId("MFG:Canon;CMD:IVEC,IVECPLI;MDL:G1000 series;CLS:PRINTER;").id)
        assertEquals("generic", db.forDeviceId("MFG:Canon;CMD:IVEC;SOJ:CHMP;MDL:G9999 series;").id)
    }

    @Test fun status() {
        val feed = StatusFeed()
        val doc = "<?xml version=\"1.0\"?><cmd><ivec:contents><ivec:status>stopped</ivec:status>" +
            "<ivec:status_detail>MediaEmpty</ivec:status_detail><ivec:current_support_code>1000</ivec:current_support_code></ivec:contents></cmd>"
        val b = doc.toByteArray()
        assertEquals(null, feed.feed(b, 40))
        val st = assertNotNull(feed.feed(b.copyOfRange(40, b.size), b.size - 40))
        assertEquals(PrinterStatus.Kind.PAPER_OUT, st.kind)
        assertTrue(st.blocking)
        assertTrue(st.message.contains("1000"))
    }

    /** Encoder round trip on synthetic data with long runs, short runs and repeated lines. */
    @Test fun encoderRoundTrip() {
        val w = 301; val h = 520
        val hdr = PwgHeader("plain", "A4", 600, intArrayOf(595, 842), IntArray(4), IntArray(4), w, h, false)
        val enc = PwgPageEncoder(hdr)
        val rnd = java.util.Random(1)
        val rows = Array(h) { y -> ByteArray(w * 3) { x -> if (y in 100..400) 0x55 else if (x % 7 == 0) rnd.nextInt().toByte() else (x / 30).toByte() } }
        rows.forEach { enc.writeRow(it) }
        val d = decode(enc.finish())
        for (y in 0 until h) assertTrue(rows[y].contentEquals(d.rows[y]), "row $y")
    }
}
