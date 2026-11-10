// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.print.PrintJobInfo
import android.print.PrinterInfo
import android.printservice.PrintService
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import io.github.noble6.canonivec.core.Model

/**
 * "More options" in the print dialog: paper type and print quality, which
 * Android's own dialog has no field for.  The choice is also remembered as
 * the default for the model.
 */
class PrintOptionsActivity : Activity() {
    private var info: PrintJobInfo? = null
    private var media: String? = null
    private var quality: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        info = intent.getParcelableExtra<PrintJobInfo>(PrintService.EXTRA_PRINT_JOB_INFO)
        @Suppress("DEPRECATION")
        val printer = intent.getParcelableExtra<PrinterInfo>(PrintService.EXTRA_PRINTER_INFO)
        val localId = printer?.id?.localId ?: info?.printerId?.localId
        val dev = localId?.let { Printers.find(this, it) }
        val model: Model = dev?.let { Printers.model(this, it) } ?: Printers.db(this).byId("generic")!!
        val prefs = Printers.prefs(this)
        media = info?.getAdvancedStringOption(OPT_MEDIA) ?: prefs.getString("media:${model.id}", null)
        quality = info?.getAdvancedStringOption(OPT_QUALITY) ?: prefs.getString("quality:${model.id}", "normal")

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun heading(t: String) = col.addView(TextView(this).apply {
            text = t; textSize = 16f; setPadding(0, pad, 0, pad / 2)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        heading("Paper type")
        // a radio group of (tag, label) choices; null tag = automatic
        fun group(choices: List<Pair<String?, String>>, selected: String?, onPick: (String?) -> Unit): RadioGroup {
            val g = RadioGroup(this)
            var checked = View.NO_ID
            for ((tag, label) in choices) {
                val b = RadioButton(this).apply { id = View.generateViewId(); text = label; this.tag = tag }
                g.addView(b)
                if (tag == selected) checked = b.id
            }
            if (checked != View.NO_ID) g.check(checked)
            g.setOnCheckedChangeListener { rg, id -> onPick(rg.findViewById<RadioButton>(id)?.tag as String?) }
            return g
        }

        heading("Paper type")
        val mediaChoices = listOf<Pair<String?, String>>(null to "Automatic (plain paper, photo paper for borderless sizes)") +
            model.media.map { it.name to it.text }
        col.addView(group(mediaChoices, media?.takeIf { m -> model.media.any { it.name == m } }) { picked ->
            media = picked
            prefs.edit().apply { if (picked == null) remove("media:${model.id}") else putString("media:${model.id}", picked) }.apply()
            publish()
        })

        heading("Print quality")
        val levels = buildList<Pair<String?, String>> {
            if (model.hasDraft) add("draft" to "Draft (plain paper, faster, less ink)")
            add("normal" to "Normal")
            if (model.hasHigh) add("high" to "High")
        }
        val qg = group(levels, levels.firstOrNull { it.first == quality }?.first ?: "normal") { picked ->
            quality = picked
            prefs.edit().putString("quality:${model.id}", picked).apply()
            publish()
        }
        col.addView(qg)

        if (model.manualDuplex) {
            heading("2-sided printing")
            col.addView(TextView(this).apply {
                text = "This printer has no duplex unit. With 2-sided on, the front sides print first, then " +
                    "IVEC Print shows how to put the stack back and prints the back sides."
            })
        }
        setContentView(ScrollView(this).apply { addView(col) })
        publish()
    }

    private fun publish() {
        val i = info ?: return
        val b = PrintJobInfo.Builder(i)
        media?.let { b.putAdvancedOption(OPT_MEDIA, it) }
        quality?.let { b.putAdvancedOption(OPT_QUALITY, it) }
        setResult(RESULT_OK, Intent().putExtra(PrintService.EXTRA_PRINT_JOB_INFO, b.build()))
    }

    companion object {
        const val OPT_MEDIA = "io.github.noble6.canonivec.media"
        const val OPT_QUALITY = "io.github.noble6.canonivec.quality"
    }
}
