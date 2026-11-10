// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec

import android.content.Context
import android.hardware.usb.UsbDevice
import io.github.noble6.canonivec.core.Model
import io.github.noble6.canonivec.core.ModelDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Model database, printer identification and the per-printer settings. */
object Printers {
    @Volatile private var database: ModelDatabase? = null
    private val deviceIds = ConcurrentHashMap<String, String>()

    /** One job or maintenance command at a time per app. */
    val usbLock = ReentrantLock()

    fun db(ctx: Context): ModelDatabase = database ?: synchronized(this) {
        database ?: ModelDatabase.parse(ctx.assets.open("ivec_models.json").bufferedReader().use { it.readText() })
            .also { database = it }
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("printers", Context.MODE_PRIVATE)

    /** Stable id of a printer for the print framework. */
    fun localId(d: UsbDevice) = "%04x:%04x".format(d.vendorId, d.productId)

    fun find(ctx: Context, localId: String): UsbDevice? = UsbPrinter.list(ctx).firstOrNull { localId(it) == localId }

    fun hasPermission(ctx: Context, d: UsbDevice) = UsbPrinter.usb(ctx).hasPermission(d)

    /** The printer's IEEE 1284 device ID, read once per connection. */
    fun deviceId(ctx: Context, d: UsbDevice): String? {
        deviceIds[d.deviceName]?.let { return it }
        if (!hasPermission(ctx, d) || !usbLock.tryLock()) return null
        return try {
            UsbPrinter.open(ctx, d).use { it.deviceId() }.takeIf { it.isNotEmpty() }?.also { deviceIds[d.deviceName] = it }
        } catch (e: Exception) {
            null
        } finally {
            usbLock.unlock()
        }
    }

    fun rememberDeviceId(d: UsbDevice, id: String) { if (id.isNotEmpty()) deviceIds[d.deviceName] = id }

    /** Profile chosen by hand (like `canonctl setup --model`), or null for automatic. */
    fun forcedModel(ctx: Context, d: UsbDevice): String? = prefs(ctx).getString("model:${localId(d)}", null)

    fun setForcedModel(ctx: Context, d: UsbDevice, id: String?) {
        prefs(ctx).edit().apply { if (id == null) remove("model:${localId(d)}") else putString("model:${localId(d)}", id) }.apply()
    }

    fun model(ctx: Context, d: UsbDevice): Model {
        val db = db(ctx)
        forcedModel(ctx, d)?.let { id -> db.byId(id)?.let { return it } }
        deviceId(ctx, d)?.let { return db.forDeviceId(it) }
        // before USB access is granted only the product name is known
        val name = d.productName?.trim().orEmpty()
        return db.models.firstOrNull { !it.generic && it.model.equals(name, ignoreCase = true) }
            ?: db.forDeviceId("MFG:Canon;CMD:IVEC;SOJ:CHMP;MDL:$name;")
    }

    fun displayName(ctx: Context, d: UsbDevice): String {
        val m = model(ctx, d)
        return if (m.generic) "Canon ${d.productName ?: "printer"}" else "Canon ${m.model}"
    }
}
