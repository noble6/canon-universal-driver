// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec.core

import org.json.JSONArray
import org.json.JSONObject

/** One paper size of a model, as in models/ivec_models.json. */
class PaperSize(
    val name: String,          // PPD keyword, "A4" or "A4.bl"
    val text: String,
    val widthPt: Double,
    val heightPt: Double,
    val imageable: DoubleArray,  // left, bottom, right, top in points
    val printW: Int,           // print area in pixels at the model resolution
    val printH: Int,
    val pwg: String,
    val overhang: IntArray?,   // borderless: top left bottom right, 1/100 mm
) {
    val borderless get() = name.contains(".bl")
}

class Media(val name: String, val text: String, val ivec: String)

class Model(
    val id: String,
    val model: String,
    val deviceId: String,
    val protocol: Int,
    val datetime: Boolean,
    val monoOnly: Boolean,
    val defaultGray: Boolean,
    val color: Boolean,
    val duplex: Boolean,
    val optimization: Boolean,
    val resolution: Int,
    val defaultSize: String,
    val defaultMedia: String,
    val qualityLevels: List<String>,
    val draftRes: Int,
    val draftMedia: List<String>,
    val maintStyle: String,
    val maintDatetime: Boolean,
    val maintHostEnv: Boolean,
    /** operation -> type -> ink groups */
    val maint: Map<String, Map<String, List<String>>>,
    val sizes: List<PaperSize>,
    val media: List<Media>,
) {
    val generic get() = id.startsWith("generic")
    /** No duplex unit: 2-sided printing is done by hand (fronts, flip, backs). */
    val manualDuplex get() = !duplex && !generic
    val hasHigh get() = "best" in qualityLevels
    val hasDraft get() = "fast_draft" in qualityLevels

    fun size(name: String) = sizes.firstOrNull { it.name == name }
    fun media(name: String) = media.firstOrNull { it.name.equals(name, ignoreCase = true) }
    fun defaultPaper() = size(defaultSize) ?: sizes.first()
    fun defaultMediaType() = media(defaultMedia) ?: media.first()

    /** Maintenance this printer accepts from a computer. */
    fun supports(op: String, type: String): Boolean =
        if (maintStyle == "bjl") (op == "Cleaning" && type == "regular") || (op == "TestPrint" && type == "nozzle_check")
        else maint[op]?.containsKey(type) == true

    fun inkGroups(op: String, type: String): List<String> = maint[op]?.get(type) ?: emptyList()

    companion object {
        fun fromJson(o: JSONObject): Model {
            val q = o.optJSONObject("quality") ?: JSONObject()
            val maint = LinkedHashMap<String, Map<String, List<String>>>()
            o.optJSONObject("maint")?.let { mo ->
                for (op in mo.keys()) {
                    val types = LinkedHashMap<String, List<String>>()
                    val to = mo.getJSONObject(op)
                    for (t in to.keys()) types[t] = strings(to.getJSONArray(t))
                    maint[op] = types
                }
            }
            val sizes = o.getJSONArray("sizes").let { a ->
                (0 until a.length()).map { i ->
                    val s = a.getJSONObject(i)
                    val ia = s.getJSONArray("ia")
                    val pr = s.getJSONArray("print")
                    PaperSize(
                        s.getString("name"), s.optString("text", s.getString("name")),
                        s.getDouble("w"), s.getDouble("h"),
                        DoubleArray(4) { ia.getDouble(it) },
                        pr.getInt(0), pr.getInt(1), s.getString("pwg"),
                        s.optJSONArray("overhang")?.let { oh -> IntArray(oh.length()) { oh.getInt(it) } },
                    )
                }
            }
            val media = o.getJSONArray("media").let { a ->
                (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i)
                    Media(m.getString("name"), m.optString("text", m.getString("name")), m.getString("ivec"))
                }
            }
            return Model(
                id = o.getString("id"),
                model = o.getString("model"),
                deviceId = o.optString("device_id"),
                protocol = o.optInt("protocol", 2),
                datetime = o.optBoolean("datetime"),
                monoOnly = o.optBoolean("mono_only"),
                defaultGray = o.optBoolean("default_gray"),
                color = o.optBoolean("color", true),
                duplex = o.optBoolean("duplex"),
                optimization = o.optBoolean("optimization"),
                resolution = o.optInt("resolution", 600),
                defaultSize = o.optString("default_size", "A4"),
                defaultMedia = o.optString("default_media", "plain"),
                qualityLevels = q.optJSONArray("levels")?.let(::strings) ?: emptyList(),
                draftRes = q.optInt("draft_res", o.optInt("resolution", 600)),
                draftMedia = q.optJSONArray("draft_media")?.let(::strings) ?: emptyList(),
                maintStyle = o.optString("maint_style", "ivec"),
                maintDatetime = o.optBoolean("maint_datetime"),
                maintHostEnv = o.optBoolean("maint_hostenv", true),
                maint = maint,
                sizes = sizes,
                media = media,
            )
        }

        private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    }
}

class ModelDatabase(val models: List<Model>) {
    fun byId(id: String) = models.firstOrNull { it.id == id }

    /**
     * The profile for an IEEE 1284 device ID: the model with the same MDL,
     * otherwise one of the two generic profiles.  Older engines (IVECPLI in
     * CMD, or no CHMP in SOJ) need the V1 job framing; this rule matches every
     * profiled model whose device ID is known.
     */
    fun forDeviceId(deviceId: String): Model {
        val mdl = field(deviceId, "MDL")
        if (mdl != null) {
            models.firstOrNull { !it.generic && field(it.deviceId, "MDL").equals(mdl, ignoreCase = true) }?.let { return it }
            models.firstOrNull { !it.generic && it.model.equals(mdl, ignoreCase = true) }?.let { return it }
        }
        return byId(if (isV1Device(deviceId)) "generic-v1" else "generic") ?: models.first()
    }

    companion object {
        fun parse(json: String): ModelDatabase {
            val a = JSONObject(json).getJSONArray("models")
            return ModelDatabase((0 until a.length()).map { Model.fromJson(a.getJSONObject(it)) })
        }

        fun field(deviceId: String, key: String): String? {
            for (part in deviceId.split(';')) {
                val i = part.indexOf(':')
                if (i > 0 && part.substring(0, i).trim().equals(key, ignoreCase = true)) return part.substring(i + 1).trim()
            }
            return null
        }

        fun isV1Device(deviceId: String): Boolean {
            val cmd = field(deviceId, "CMD") ?: ""
            val soj = field(deviceId, "SOJ") ?: ""
            return cmd.contains("IVECPLI") || !soj.contains("CHMP")
        }
    }
}
