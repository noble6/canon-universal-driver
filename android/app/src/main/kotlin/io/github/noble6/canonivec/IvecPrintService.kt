// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import io.github.noble6.canonivec.core.Model
import io.github.noble6.canonivec.core.PaperSize

/** Puts USB-connected Canon IVEC printers in every app's print dialog. */
class IvecPrintService : PrintService() {
    private val runners = HashMap<PrintJob, JobRunner>()

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Discovery()

    override fun onPrintJobQueued(job: PrintJob) {
        val r = JobRunner(this, job) { runners.remove(job) }
        runners[job] = r
        r.start()
    }

    override fun onRequestCancelPrintJob(job: PrintJob) {
        val r = runners[job]
        if (r != null) r.cancel() else job.cancel()
    }

    private inner class Discovery : PrinterDiscoverySession() {
        private var registered = false
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = update()
        }

        override fun onStartPrinterDiscovery(priorityList: List<PrinterId>) {
            if (!registered) {
                val f = IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                    addAction(UsbPrinter.ACTION_PERMISSION)
                }
                registerReceiver(receiver, f, if (android.os.Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0)
                registered = true
            }
            update()
        }

        override fun onStopPrinterDiscovery() {}

        override fun onValidatePrinters(printerIds: List<PrinterId>) = update()

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            val d = Printers.find(this@IvecPrintService, printerId.localId) ?: return
            if (!Printers.hasPermission(this@IvecPrintService, d)) {
                UsbPrinter.requestPermission(this@IvecPrintService, d)
            }
            Thread {
                val info = info(printerId, d, withCaps = true)
                mainExecutor.execute { if (!isDestroyed) addPrinters(listOf(info)) }
            }.start()
        }

        override fun onStopPrinterStateTracking(printerId: PrinterId) {}

        override fun onDestroy() {
            if (registered) unregisterReceiver(receiver)
            registered = false
        }

        private fun update() {
            val ctx = this@IvecPrintService
            Thread {
                val devices = UsbPrinter.list(ctx)
                val infos = devices.map { d ->
                    val id = generatePrinterId(Printers.localId(d))
                    info(id, d, withCaps = trackedPrinters.contains(id))
                }
                mainExecutor.execute {
                    if (isDestroyed) return@execute
                    val now = infos.map { it.id }.toSet()
                    val gone = printers.map { it.id }.filter { it !in now }
                    if (gone.isNotEmpty()) removePrinters(gone)
                    if (infos.isNotEmpty()) addPrinters(infos)
                }
            }.start()
        }

        private fun info(id: PrinterId, d: android.hardware.usb.UsbDevice, withCaps: Boolean): PrinterInfo {
            val ctx = this@IvecPrintService
            val allowed = Printers.hasPermission(ctx, d)
            val model = Printers.model(ctx, d)
            val b = PrinterInfo.Builder(id, Printers.displayName(ctx, d),
                if (allowed) PrinterInfo.STATUS_IDLE else PrinterInfo.STATUS_UNAVAILABLE)
                .setDescription(if (allowed) "USB" else "USB - open IVEC Print to allow access")
                .setInfoIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE))
            if (withCaps && allowed) b.setCapabilities(capabilities(id, model))
            return b.build()
        }
    }

    companion object {
        fun mils(pt: Double) = Math.round(pt * 1000.0 / 72.0).toInt()

        fun label(s: PaperSize): String {
            val t = s.text.substringBefore(" [").trim()
            return t.replace(Regex("\\s*\\(borderless\\)", RegexOption.IGNORE_CASE), " (borderless)")
        }

        fun capabilities(id: PrinterId, model: Model): PrinterCapabilitiesInfo {
            val b = PrinterCapabilitiesInfo.Builder(id)
            val def = model.defaultPaper().name
            for (s in model.sizes)
                b.addMediaSize(PrintAttributes.MediaSize(s.name, label(s), mils(s.widthPt), mils(s.heightPt)), s.name == def)
            b.addResolution(PrintAttributes.Resolution("std", "${model.resolution} dpi", model.resolution, model.resolution), true)
            // pages are fitted into the printable area, so apps may use the whole sheet
            b.setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            if (model.monoOnly || !model.color) {
                b.setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
            } else {
                b.setColorModes(PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME,
                    if (model.defaultGray) PrintAttributes.COLOR_MODE_MONOCHROME else PrintAttributes.COLOR_MODE_COLOR)
            }
            if (model.duplex || model.manualDuplex) {
                b.setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE or PrintAttributes.DUPLEX_MODE_LONG_EDGE or
                    PrintAttributes.DUPLEX_MODE_SHORT_EDGE, PrintAttributes.DUPLEX_MODE_NONE)
            }
            return b.build()
        }
    }
}
