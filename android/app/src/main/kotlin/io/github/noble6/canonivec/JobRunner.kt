// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec

import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.printservice.PrintJob
import android.util.Log
import io.github.noble6.canonivec.core.Duplex
import io.github.noble6.canonivec.core.IvecPrintJob
import io.github.noble6.canonivec.core.JobSettings
import io.github.noble6.canonivec.core.Model
import io.github.noble6.canonivec.core.PaperSize
import io.github.noble6.canonivec.core.PrinterStatus
import io.github.noble6.canonivec.core.Quality
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.abs

/**
 * Runs one print job: lays the document out, sends it to the printer and
 * follows the printer's status until the pages are out.  PrintJob methods
 * must be called on the main thread; the work happens on a worker thread.
 */
class JobRunner(
    private val service: IvecPrintService,
    private val job: PrintJob,
    private val onDone: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var blockedBy: String? = null
    private val key = job.id.toString()

    // read on the main thread before the worker starts
    private val info = job.info
    private val localId = info.printerId?.localId
    private val mediaOption = job.getAdvancedStringOption(PrintOptionsActivity.OPT_MEDIA)
    private val qualityOption = job.getAdvancedStringOption(PrintOptionsActivity.OPT_QUALITY)
    private val data: ParcelFileDescriptor? = job.document.data

    fun start() {
        if (!job.isStarted) job.start()
        Thread(::run, "ivec-job").start()
    }

    fun cancel() {
        cancelled = true
        FlipNotifier.cancel(key)
    }

    private fun ui(f: () -> Unit) = main.post { if (!job.isCompleted && !job.isCancelled && !job.isFailed) f() }

    private fun status(text: String) = ui { job.setStatus(text) }

    private fun run() {
        var file: File? = null
        try {
            file = File.createTempFile("job", ".pdf", service.cacheDir)
            data ?: throw IOException("no document")
            FileInputStream(data.fileDescriptor).use { inp -> file.outputStream().use { inp.copyTo(it) } }
            data.close()

            val dev = localId?.let { Printers.find(service, it) } ?: throw IOException("Printer is not connected")
            val model = Printers.model(service, dev)
            val settings = settings(model)
            val ivec = IvecPrintJob(model, settings, "linux")
            Log.i(TAG, "printing on ${model.id}: ${settings.size.name} ${settings.media.name} gray=${settings.gray} " +
                "duplex=${settings.duplex} quality=${settings.quality}")

            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { pdf ->
                    val r = PageRenderer(pdf, ivec)
                    val sequence = pageSequence(pdf.pageCount, info.copies.coerceAtLeast(1), settings.duplex != Duplex.NONE)
                    sequence.forEachIndexed { i, page ->
                        status("Preparing page ${i + 1} of ${sequence.size}")
                        ui { job.setProgress(0.5f * i / sequence.size) }
                        r.addPage(page, i + 1) { cancelled }
                    }
                    r.recycle()
                }
            }
            if (cancelled) throw UsbPrinter.CancelledException()

            Printers.usbLock.lock()
            try {
                UsbPrinter.open(service, dev).use { p -> send(p, ivec) }
            } finally {
                Printers.usbLock.unlock()
            }
            main.post { if (!job.isCancelled && !job.isCompleted) job.complete() }
        } catch (e: UsbPrinter.CancelledException) {
            main.post { if (!job.isCancelled && !job.isCompleted) job.cancel() }
        } catch (e: Exception) {
            Log.e(TAG, "job failed", e)
            main.post { if (!job.isCancelled && !job.isCompleted) job.fail(e.message ?: e.toString()) }
        } finally {
            FlipNotifier.cancel(key)
            file?.delete()
            main.post(onDone)
        }
    }

    private fun send(p: UsbPrinter, ivec: IvecPrintJob) {
        p.onStatus = { st -> onStatus(st) }
        Printers.rememberDeviceId(p.device, p.deviceId())
        p.startStatusReader()
        p.ensureListening()
        val out = p.output { cancelled }
        val jobId = if (ivec.model.protocol == 1) "00000001" else "00000002"
        try {
            status("Sending to the printer")
            ui { job.setProgress(0.6f) }
            if (ivec.manualDuplex && ivec.pageCount > 1) {
                ivec.writeFronts(out)
                status("Printing the front sides")
                waitIdle(p)
                status("Waiting for you to turn the paper over")
                if (!FlipNotifier.waitForFlip(service, key, info.label)) throw UsbPrinter.CancelledException()
                p.ensureListening()
                status("Printing the back sides")
                ivec.writeBacks(out)
            } else {
                ivec.writeTo(out)
            }
            status("Printing")
            ui { job.setProgress(0.8f) }
            waitIdle(p)
        } catch (e: UsbPrinter.CancelledException) {
            status("Cancelling")
            p.cancelOnPrinter("print", jobId)
            throw e
        }
    }

    /** Follow the printer until it is idle again, so paper-out and jams reach the user. */
    private fun waitIdle(p: UsbPrinter) {
        val start = System.currentTimeMillis()
        var idleSince = 0L
        while (System.currentTimeMillis() - start < 60 * 60 * 1000L) {
            if (cancelled) throw UsbPrinter.CancelledException()
            val st = p.lastStatus
            if (st == null) {
                // no status channel: nothing to follow
                if (System.currentTimeMillis() - start > 5000) return
            } else if (st.kind == PrinterStatus.Kind.READY && System.currentTimeMillis() - start > 3000) {
                if (idleSince == 0L) idleSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - idleSince > 2000) return
            } else {
                idleSince = 0L
            }
            Thread.sleep(500)
        }
    }

    private fun onStatus(st: PrinterStatus) {
        val msg = if (st.blocking) st.message else null
        if (msg == blockedBy) return
        blockedBy = msg
        main.post {
            if (job.isCompleted || job.isCancelled || job.isFailed) return@post
            if (msg != null) job.block(msg) else if (job.isBlocked) job.start()
        }
    }

    private fun settings(model: Model): JobSettings {
        val a = info.attributes
        val size = paperSize(model, a.mediaSize)
        val prefs = Printers.prefs(service)
        val mediaName = mediaOption ?: prefs.getString("media:${model.id}", null)
        val media = mediaName?.let { model.media(it) }
            ?: if (size.borderless) model.media.firstOrNull { it.ivec != "stationery" } ?: model.defaultMediaType()
            else model.defaultMediaType()
        val quality = when (qualityOption ?: prefs.getString("quality:${model.id}", null)) {
            "high" -> Quality.HIGH
            "draft" -> Quality.DRAFT
            else -> Quality.NORMAL
        }
        val duplex = when (a.duplexMode) {
            PrintAttributes.DUPLEX_MODE_LONG_EDGE -> Duplex.LONG_EDGE
            PrintAttributes.DUPLEX_MODE_SHORT_EDGE -> Duplex.SHORT_EDGE
            else -> Duplex.NONE
        }
        return JobSettings(
            size = size,
            media = media,
            gray = a.colorMode == PrintAttributes.COLOR_MODE_MONOCHROME,
            duplex = if (model.duplex || model.manualDuplex) duplex else Duplex.NONE,
            quality = quality,
            uuid = "urn:uuid:" + UUID.randomUUID(),
            manualBackTurned = prefs.getBoolean("backTurned:${model.id}", false),
        )
    }

    private fun paperSize(model: Model, ms: PrintAttributes.MediaSize?): PaperSize {
        if (ms == null) return model.defaultPaper()
        model.size(ms.id)?.let { return it }
        // a size from elsewhere (an app's default): match by dimensions
        val w = minOf(ms.widthMils, ms.heightMils)
        val h = maxOf(ms.widthMils, ms.heightMils)
        return model.sizes.filter { !it.borderless }.firstOrNull {
            abs(IvecPrintService.mils(it.widthPt) - w) < 40 && abs(IvecPrintService.mils(it.heightPt) - h) < 40
        } ?: model.defaultPaper()
    }

    companion object {
        private const val TAG = "IvecPrint"

        /**
         * Page order for [copies] collated copies; in 2-sided mode each copy
         * starts on a new sheet (-1 is a blank page).
         */
        fun pageSequence(pages: Int, copies: Int, duplex: Boolean): List<Int> {
            val out = ArrayList<Int>()
            for (c in 0 until copies) {
                for (i in 0 until pages) out.add(i)
                if (duplex && pages % 2 == 1 && c < copies - 1) out.add(-1)
            }
            return out
        }
    }
}
