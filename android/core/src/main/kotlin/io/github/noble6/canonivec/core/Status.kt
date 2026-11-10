// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec.core

/** A GetStatusResponse from the printer (it answers every read of the bulk-in pipe with one). */
data class PrinterStatus(val status: String, val detail: String, val supportCode: String) {
    enum class Kind { READY, PRINTING, PAPER_OUT, JAM, COVER_OPEN, INK, ATTENTION }

    val kind: Kind get() = when {
        detail == "MediaEmpty" -> Kind.PAPER_OUT
        detail == "MediaJam" -> Kind.JAM
        detail == "DoorOpen" -> Kind.COVER_OPEN
        detail == "MarkerSupplyAttention" -> Kind.INK
        status == "stopped" || status == "notready" || detail == "AttentionRequired" -> Kind.ATTENTION
        status == "idle" -> Kind.READY
        else -> Kind.PRINTING
    }

    /** True when the job cannot go on until the user does something. */
    val blocking get() = kind in setOf(Kind.PAPER_OUT, Kind.JAM, Kind.COVER_OPEN, Kind.ATTENTION)

    val message: String get() {
        val code = if (supportCode.isNotEmpty() && supportCode != "0") " (support code $supportCode)" else ""
        return when (kind) {
            Kind.PAPER_OUT -> "Out of paper$code. Load paper, then press the printer's Black or Color (OK) button to continue."
            Kind.JAM -> "Paper jam$code. Remove the paper, then press the printer's Stop/OK button."
            Kind.COVER_OPEN -> "A cover is open$code. Close it to continue."
            Kind.INK -> "Check the ink$code."
            Kind.ATTENTION -> "The printer needs attention$code. Check its lamps or display."
            Kind.READY -> "Ready"
            Kind.PRINTING -> "Printing"
        }
    }

    companion object {
        private fun lastValue(buf: String, tag: String): String? {
            val i = buf.lastIndexOf(tag)
            if (i < 0) return null
            val e = buf.indexOf('<', i + tag.length)
            return if (e < 0) null else buf.substring(i + tag.length, e)
        }

        fun parse(buf: String): PrinterStatus? {
            val status = lastValue(buf, "<ivec:status>") ?: return null
            return PrinterStatus(
                status,
                lastValue(buf, "<ivec:status_detail>") ?: "",
                lastValue(buf, "<ivec:current_support_code>") ?: lastValue(buf, "<ivec:error_code>") ?: "",
            )
        }
    }
}

/** Collects bulk-in reads and yields each complete status document. */
class StatusFeed {
    private val acc = StringBuilder()

    fun feed(data: ByteArray, len: Int): PrinterStatus? {
        if (acc.length + len > 65536) acc.setLength(0)
        acc.append(String(data, 0, len, Charsets.ISO_8859_1))
        val end = acc.lastIndexOf("</cmd>")
        if (end < 0) return null
        val st = PrinterStatus.parse(acc.substring(0, end + 6))
        acc.delete(0, end + 6)
        return st
    }
}
