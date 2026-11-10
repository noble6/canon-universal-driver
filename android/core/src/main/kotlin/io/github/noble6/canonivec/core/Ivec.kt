// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec.core

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * IVEC command generation, a port of src/ivec/ivec.c.  The byte layout
 * (declaration spelling, namespace sets, element order) is what Canon's
 * drivers put on the wire; printers are picky, so keep it exact.
 */
object Ivec {
    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
    private const val XML_DECL2 = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
    private const val NS_IVEC = "xmlns:ivec=\"http://www.canon.com/ns/cmd/2008/07/common/\""
    private const val NS_VCN = "xmlns:vcn=\"http://www.canon.com/ns/cmd/2008/07/canon/\""
    private const val OPEN_CMD = "<cmd $NS_IVEC>"
    private const val OPEN_CMDV = "<cmd $NS_IVEC $NS_VCN>"
    private const val CLOSE = "</ivec:param_set></ivec:contents></cmd>"

    const val GET_STATUS = XML_DECL + OPEN_CMD + "<ivec:contents><ivec:operation>GetStatus</ivec:operation>" +
        "<ivec:param_set servicetype=\"print\"></ivec:param_set></ivec:contents></cmd>"

    fun cancelJob(service: String, jobId: String) = XML_DECL + OPEN_CMD +
        "<ivec:contents><ivec:operation>CancelJob</ivec:operation><ivec:param_set servicetype=\"$service\">" +
        "<ivec:jobID>$jobId</ivec:jobID></ivec:param_set></ivec:contents></cmd>"

    /** Local time as the printers want it, yyyyMMddHHmmss. */
    fun now(): String = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())

    internal fun op(vendorNs: Boolean, operation: String, jobId: String, service: String = "print") =
        XML_DECL + (if (vendorNs) OPEN_CMDV else OPEN_CMD) +
            "<ivec:contents><ivec:operation>$operation</ivec:operation>" +
            "<ivec:param_set servicetype=\"$service\"><ivec:jobID>$jobId</ivec:jobID>"

    internal fun bjl(body: String): ByteArray =
        byteArrayOf(0x1b, '['.code.toByte(), 'K'.code.toByte(), 0x02, 0x00, 0x00, 0x1f) +
            "BJLSTART\n${body}BJLEND\n".toByteArray(Charsets.US_ASCII)

    /**
     * One printer job.  [protocol] 1 or 2; [datetime] makes V2 jobs send
     * SetJobConfiguration with the clock.
     */
    class Job(
        private val out: OutputStream,
        protocol: Int,
        private val datetime: Boolean,
        uuid: String?,
        private val hostEnv: String = "linux",
        private val clock: () -> String = ::now,
    ) {
        val protocol = if (protocol == 1) 1 else 2
        val jobId = if (this.protocol == 1) "00000001" else "00000002"
        private val description = uuid?.removePrefix("urn:uuid:") ?: ""

        private fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))

        private fun shortStartJob() {
            w(op(true, "StartJob", jobId))
            w("<ivec:bidi>0</ivec:bidi><vcn:forcepmdetection>OFF</vcn:forcepmdetection>$CLOSE")
        }

        fun startJob() {
            val now = clock()
            if (protocol == 1) {
                // older engines get their clock set by a separate BJL job first
                shortStartJob()
                w(op(true, "VendorCmd", jobId))
                w("<vcn:ijoperation>ModeShift</vcn:ijoperation><vcn:ijmode>1</vcn:ijmode>$CLOSE")
                out.write(bjl("ControlMode=Common\nSetTime=$now\n"))
                endJob()
                shortStartJob()
                return
            }
            w(op(true, "StartJob", jobId))
            w("<ivec:bidi>0</ivec:bidi><vcn:forcepmdetection>OFF</vcn:forcepmdetection>" +
                "<ivec:jobname/><ivec:username/><ivec:computername/>" +
                "<ivec:job_description><![CDATA[$description]]></ivec:job_description>" +
                "<vcn:host_environment>$hostEnv</vcn:host_environment>$CLOSE")
            if (datetime) {
                w(XML_DECL2 + OPEN_CMD + "<ivec:contents><ivec:operation>SetJobConfiguration</ivec:operation>" +
                    "<ivec:param_set servicetype=\"print\"><ivec:jobID>$jobId</ivec:jobID>" +
                    "<ivec:mismatch_mode>none</ivec:mismatch_mode><ivec:datetime>$now</ivec:datetime>$CLOSE")
            }
        }

        fun setConfiguration(
            papersize: String, papertype: String, borderless: Boolean, monochrome: Boolean,
            duplex: Boolean, printquality: String?,
        ) {
            w(op(false, "SetConfiguration", jobId))
            w("<ivec:papersize>$papersize</ivec:papersize><ivec:papertype>$papertype</ivec:papertype>" +
                "<ivec:borderlessprint>${if (borderless) "ON" else "OFF"}</ivec:borderlessprint>" +
                "<ivec:printcolormode>${if (monochrome) "monochrome" else "color"}</ivec:printcolormode>" +
                "<ivec:duplexprint>${if (duplex) "ON" else "OFF"}</ivec:duplexprint>")
            if (printquality != null) w("<ivec:printquality>$printquality</ivec:printquality>")
            w(CLOSE)
        }

        fun sendPage(morePages: Boolean, pwg: ByteArray) {
            w(op(true, "VendorCmd", jobId))
            w("<vcn:ijoperation>SetPageConfiguration</vcn:ijoperation>" +
                "<vcn:nextpage>${if (morePages) "ON" else "OFF"}</vcn:nextpage>$CLOSE")
            w(op(false, "SendData", jobId))
            w("<ivec:format>PWGRaster</ivec:format><ivec:datasize>${pwg.size}</ivec:datasize>$CLOSE")
            out.write(pwg)
        }

        fun endJob() {
            w(op(false, "EndJob", jobId))
            w(CLOSE)
            out.flush()
        }

        /** Older engines: maintenance as a BJL line such as "@Cleaning=1ALL". */
        fun maintenanceBjl(command: String) {
            // framed like the model's print jobs, minus the V1 clock job
            if (protocol == 1) shortStartJob() else startJob()
            w(op(true, "VendorCmd", jobId))
            w("<vcn:ijoperation>ModeShift</vcn:ijoperation><vcn:ijmode>1</vcn:ijmode>$CLOSE")
            out.write(bjl("$command\n"))
            endJob()
        }
    }

    /** IVEC maintenance job: Cleaning / RollerCleaning / TestPrint with its type and ink group. */
    fun maintenance(
        out: OutputStream, datetime: Boolean, hostEnv: Boolean, uuid: String?, operation: String,
        type: String?, inkgroup: String?, clock: () -> String = ::now,
    ) {
        val id = "00000002"
        val desc = uuid?.removePrefix("urn:uuid:") ?: ""
        val now = clock()
        val sb = StringBuilder()
        // the vendor namespace is only declared when a vcn: element follows
        sb.append(op(hostEnv, "StartJob", id, "maintenance"))
        sb.append("<ivec:bidi>0</ivec:bidi><ivec:jobname/><ivec:username/><ivec:computername/>" +
            "<ivec:job_description><![CDATA[$desc]]></ivec:job_description>")
        if (hostEnv) sb.append("<vcn:host_environment>linux</vcn:host_environment>")
        sb.append(CLOSE)
        if (datetime) {
            sb.append(op(true, "SetJobConfiguration", id, "maintenance"))
            sb.append("<ivec:datetime>$now</ivec:datetime>$CLOSE")
        }
        // the operation's own parameters precede the job id here
        sb.append(XML_DECL2 + OPEN_CMD + "<ivec:contents><ivec:operation>$operation</ivec:operation>" +
            "<ivec:param_set servicetype=\"maintenance\">")
        if (!inkgroup.isNullOrEmpty()) sb.append("<ivec:inkgroup>$inkgroup</ivec:inkgroup>")
        if (!type.isNullOrEmpty()) sb.append("<ivec:type>$type</ivec:type>")
        sb.append("<ivec:jobID>$id</ivec:jobID>$CLOSE")
        sb.append(op(false, "EndJob", id, "maintenance"))
        sb.append(CLOSE)
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
