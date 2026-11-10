// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.github.noble6.canonivec.core.Ivec
import io.github.noble6.canonivec.core.PrinterStatus
import io.github.noble6.canonivec.core.StatusFeed
import java.io.IOException
import java.io.OutputStream

/**
 * A Canon printer on USB, talking to the printer-class interface directly
 * (what the Linux canonivec backend does with libusb).
 */
class UsbPrinter private constructor(
    val device: UsbDevice,
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint?,
    private val usb: UsbManager,
) : AutoCloseable {

    @Volatile var lastStatus: PrinterStatus? = null
        private set
    @Volatile var lastStatusAt = 0L
        private set
    var onStatus: ((PrinterStatus) -> Unit)? = null

    private val feed = StatusFeed()
    @Volatile private var reading = false
    private var reader: Thread? = null

    /** IEEE 1284 device ID (GET_DEVICE_ID class request). */
    fun deviceId(): String {
        val buf = ByteArray(1024)
        val cfg = device.getConfiguration(0).id
        val n = conn.controlTransfer(0xa1, 0, cfg, (iface.id shl 8) or iface.alternateSetting, buf, buf.size, 5000)
        if (n < 2) return ""
        var len = ((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff)
        if (len > n || len < 2) len = n
        return String(buf, 2, len - 2, Charsets.ISO_8859_1).trim('\u0000', ' ')
    }

    /** USB printer class SOFT_RESET, addressed to the interface (what Canon's tools send). */
    fun softReset() {
        conn.controlTransfer(0x21, 2, 0, iface.id, null, 0, 5000)
    }

    /** Read the status the printer sends back, in the background. */
    fun startStatusReader() {
        if (epIn == null || reading) return
        reading = true
        reader = Thread({
            val buf = ByteArray(maxOf(epIn.maxPacketSize, 4096))
            while (reading) {
                val n = conn.bulkTransfer(epIn, buf, buf.size, 500)
                if (n > 0) {
                    feed.feed(buf, n)?.let { st ->
                        lastStatus = st
                        lastStatusAt = System.currentTimeMillis()
                        onStatus?.invoke(st)
                    }
                } else {
                    Thread.sleep(300)
                }
            }
        }, "ivec-status").apply { isDaemon = true; start() }
    }

    fun stopStatusReader() {
        reading = false
        reader?.join(2000)
        reader = null
    }

    /** Ask for a status document (the printer also answers every read on its own). */
    fun requestStatus() {
        val b = Ivec.GET_STATUS.toByteArray()
        conn.bulkTransfer(epOut, b, b.size, 2000)
    }

    /**
     * A printer left discarding input by a cancelled job answers nothing;
     * make sure it is listening before a job.
     */
    fun ensureListening() {
        if (epIn == null) return
        val before = lastStatusAt
        requestStatus()
        if (reading) {
            for (i in 0 until 15) {
                if (lastStatusAt != before) return
                Thread.sleep(100)
            }
        } else {
            val buf = ByteArray(4096)
            for (i in 0 until 3) {
                val n = conn.bulkTransfer(epIn, buf, buf.size, 700)
                if (n > 0) { feed.feed(buf, n)?.let { lastStatus = it }; return }
            }
        }
        softReset()
    }

    /**
     * Abort the job on the printer.  Seen on a G3010: a soft reset alone
     * leaves it waiting; CancelJob ends the job but then the printer
     * discards input until the next reset.  So: reset, CancelJob, reset.
     */
    fun cancelOnPrinter(service: String, jobId: String) {
        softReset()
        val b = Ivec.cancelJob(service, jobId).toByteArray()
        conn.bulkTransfer(epOut, b, b.size, 5000)
        Thread.sleep(2000)
        softReset()
    }

    /**
     * Stream to the printer.  Writes use short timeouts so a printer that
     * stops taking data (paper out) does not hang the job; [cancelled] is
     * checked between attempts.
     */
    fun output(cancelled: () -> Boolean): OutputStream = object : OutputStream() {
        private val chunk = 16384

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            val end = off + len
            while (o < end) {
                if (cancelled()) throw CancelledException()
                val n = minOf(chunk, end - o)
                val sent = conn.bulkTransfer(epOut, b, o, n, 2000)
                if (sent > 0) {
                    o += sent
                } else if (!usb.deviceList.containsKey(device.deviceName)) {
                    throw IOException("printer disconnected")
                }
                // 0 / -1: timed out while the printer is busy or waiting; try again
            }
        }
    }

    override fun close() {
        stopStatusReader()
        conn.releaseInterface(iface)
        conn.close()
    }

    class CancelledException : IOException("cancelled")

    companion object {
        const val CANON_VID = 0x04a9
        const val ACTION_PERMISSION = "io.github.noble6.canonivec.USB_PERMISSION"

        private fun printerInterface(d: UsbDevice): UsbInterface? {
            var best: UsbInterface? = null
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                if (f.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
                // prefer the bidirectional one (protocol 2), it carries the status
                if (best == null || f.interfaceProtocol == 2 && best.interfaceProtocol != 2) best = f
            }
            return best
        }

        fun isCanonPrinter(d: UsbDevice) = d.vendorId == CANON_VID && printerInterface(d) != null

        fun list(ctx: Context): List<UsbDevice> =
            usb(ctx).deviceList.values.filter(::isCanonPrinter).sortedBy { it.deviceName }

        fun usb(ctx: Context) = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

        fun requestPermission(ctx: Context, d: UsbDevice) {
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE)
            usb(ctx).requestPermission(d, pi)
        }

        fun open(ctx: Context, d: UsbDevice): UsbPrinter {
            val m = usb(ctx)
            if (!m.hasPermission(d)) throw IOException("no permission to use the printer; open IVEC Print and allow it")
            val f = printerInterface(d) ?: throw IOException("not a printer")
            var out: UsbEndpoint? = null
            var inp: UsbEndpoint? = null
            for (i in 0 until f.endpointCount) {
                val e = f.getEndpoint(i)
                if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (e.direction == UsbConstants.USB_DIR_OUT) out = e else inp = e
            }
            if (out == null) throw IOException("printer has no output endpoint")
            val c = m.openDevice(d) ?: throw IOException("cannot open the printer")
            if (!c.claimInterface(f, true)) { c.close(); throw IOException("printer is busy") }
            c.setInterface(f)
            return UsbPrinter(d, c, f, out, inp, m)
        }
    }
}
