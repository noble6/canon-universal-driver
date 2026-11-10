// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.noble6.canonivec.core.Maintenance
import io.github.noble6.canonivec.core.Model
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Connected printers, USB access, profile choice and maintenance. */
class MainActivity : Activity() {
    private lateinit var col: LinearLayout
    private val pad get() = (16 * resources.displayMetrics.density).toInt()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(col) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbPrinter.ACTION_PERMISSION)
        }
        registerReceiver(receiver, f, if (android.os.Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    private fun text(t: CharSequence, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setPadding(0, pad / 4, 0, pad / 4)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

    private fun refresh() {
        col.removeAllViews()
        col.addView(text("IVEC Print", 22f, true))
        col.addView(text("Prints to Canon PIXMA, MAXIFY and G series inkjets connected over USB, from any app's " +
            "Print menu. Turn on \"IVEC Print\" under Printing in the system settings once, then pick your printer " +
            "in the print dialog. Paper type and quality are under More options."))
        col.addView(button("Open printing settings") {
            try { startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })

        val devices = UsbPrinter.list(this)
        if (devices.isEmpty()) {
            col.addView(text("No Canon printer found. Connect it with a USB cable (a USB-C or OTG adapter on " +
                "phones) and switch it on.", bold = true))
            return
        }
        for (d in devices) printerCard(d)
    }

    private fun printerCard(d: UsbDevice) {
        col.addView(text(Printers.displayName(this, d), 19f, true).apply { setPadding(0, pad, 0, 0) })
        if (!Printers.hasPermission(this, d)) {
            col.addView(text("IVEC Print needs permission to use this USB device."))
            col.addView(button("Allow USB access") { UsbPrinter.requestPermission(this, d) })
            return
        }
        val model = Printers.model(this, d)
        val devId = Printers.deviceId(this, d) ?: ""
        val profile = when {
            Printers.forcedModel(this, d) != null -> "${model.model} (chosen by hand)"
            model.id == "generic" -> "Generic (not in the model list)"
            model.id == "generic-v1" -> "Generic for older models (not in the model list)"
            else -> model.model
        }
        col.addView(text("Profile: $profile"))
        val status = text("Status: checking...")
        col.addView(status)
        checkStatus(d, status)

        // profile override, like canonctl setup --model
        val db = Printers.db(this)
        val choices = listOf<Model?>(null) + db.models.sortedBy { it.model.lowercase() }
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            choices.map { it?.let { m -> m.model } ?: "Automatic (from the printer's device ID)" })
        sp.setSelection(choices.indexOfFirst { it?.id == Printers.forcedModel(this, d) }.coerceAtLeast(0), false)
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val newId = choices[pos]?.id
                if (newId != Printers.forcedModel(this@MainActivity, d)) {
                    Printers.setForcedModel(this@MainActivity, d, newId)
                    refresh()
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        col.addView(text("Printer profile", bold = true))
        col.addView(sp)

        maintenance(d, model)

        col.addView(text("Device ID", bold = true))
        col.addView(text(devId.ifEmpty { "(not available)" }, 12f).apply { setTextIsSelectable(true) })
    }

    private fun maintenance(d: UsbDevice, model: Model) {
        col.addView(text("Maintenance", 17f, true).apply { setPadding(0, pad, 0, 0) })
        val groupNames = mapOf("all" to "All inks", "group1" to "Black", "group2" to "Color")
        val cleaningGroups = model.inkGroups("Cleaning", "regular").ifEmpty { listOf("all") }
        val inkSpinner = Spinner(this)
        if (model.maintStyle != "bjl" && cleaningGroups.size > 1) {
            inkSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                cleaningGroups.map { groupNames[it] ?: it })
            col.addView(text("Ink for cleaning"))
            col.addView(inkSpinner)
        }
        fun group(op: String, type: String): String? {
            val g = cleaningGroups.getOrNull(inkSpinner.selectedItemPosition.coerceAtLeast(0)) ?: "all"
            val ok = model.inkGroups(op, type)
            return if (ok.isEmpty() || g in ok) g else "all"
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun add(label: String, op: String, type: String, confirm: String? = null, grouped: Boolean = false) {
            if (!model.supports(op, type)) return
            row.addView(button(label) {
                val go = { run(d, model, label, op, type, if (grouped) group(op, type) else null) }
                if (confirm == null) go()
                else AlertDialog.Builder(this).setTitle(label).setMessage(confirm)
                    .setPositiveButton("Start") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        add("Nozzle check", "TestPrint", "nozzle_check")
        add("Cleaning", "Cleaning", "regular", grouped = true)
        add("Deep cleaning", "Cleaning", "deep", "Deep cleaning uses more ink. Do it only when a nozzle check " +
            "still shows gaps after a normal cleaning.", grouped = true)
        add("Ink flush", "Cleaning", "choke", "Ink flush uses a lot of ink and fills the maintenance cartridge " +
            "faster. Only use it when deep cleaning did not help.", grouped = true)
        add("Roller cleaning", "RollerCleaning", "roller", "Remove all paper from the rear tray first.")
        add("Bottom plate cleaning", "RollerCleaning", "platen", "Fold an A4 or Letter sheet in half lengthwise, " +
            "unfold it and load it in the rear tray with the fold facing away from you.")
        Maintenance.alignmentType(model)?.let { add("Print head alignment", "TestPrint", it) }
        col.addView(row)
        if (model.maintStyle == "bjl") {
            col.addView(text("This printer only takes cleaning and nozzle check from a computer or phone. " +
                "Do the rest from the printer's own menu.", 13f))
        }
    }

    private fun run(d: UsbDevice, model: Model, label: String, op: String, type: String, group: String?) {
        Thread {
            val msg = try {
                val bytes = ByteArrayOutputStream().also {
                    Maintenance.write(it, model, op, type, group, "urn:uuid:" + UUID.randomUUID())
                }.toByteArray()
                if (!Printers.usbLock.tryLock()) throw Exception("The printer is busy with a job")
                try {
                    UsbPrinter.open(this, d).use { p ->
                        p.ensureListening()
                        p.output { false }.write(bytes)
                    }
                } finally {
                    Printers.usbLock.unlock()
                }
                "$label sent"
            } catch (e: Exception) {
                "$label failed: ${e.message}"
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun checkStatus(d: UsbDevice, view: TextView) {
        Thread {
            val msg = if (!Printers.usbLock.tryLock()) "Printing" else try {
                UsbPrinter.open(this, d).use { p ->
                    p.ensureListening()
                    p.lastStatus?.message ?: "Ready (no status channel)"
                }
            } catch (e: Exception) {
                "Unavailable (${e.message})"
            } finally {
                Printers.usbLock.unlock()
            }
            runOnUiThread { view.text = "Status: $msg" }
        }.start()
    }
}
