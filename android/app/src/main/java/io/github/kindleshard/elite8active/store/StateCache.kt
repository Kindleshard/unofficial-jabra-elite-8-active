package io.github.kindleshard.elite8active.store

import android.content.Context
import io.github.kindleshard.elite8active.EarbudsState
import io.github.kindleshard.elite8active.gnp.BatteryReport
import io.github.kindleshard.elite8active.gnp.ButtonAssignment
import io.github.kindleshard.elite8active.gnp.Choice
import io.github.kindleshard.elite8active.gnp.EarbudLink
import io.github.kindleshard.elite8active.gnp.Side
import io.github.kindleshard.elite8active.gnp.SoundMode
import io.github.kindleshard.elite8active.gnp.Toggle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last values read from the earbuds, kept on the phone so the app opens straight to its tabs
 * and fills them in as it connects. Only what came from the earbuds is kept, not the connection.
 */
object StateCache {
    private const val FILE = "last_state.json"

    fun load(context: Context): EarbudsState? = runCatching {
        val file = File(context.filesDir, FILE)
        if (file.exists()) decode(JSONObject(file.readText())) else null
    }.getOrNull()

    fun save(context: Context, state: EarbudsState) {
        runCatching { File(context.filesDir, FILE).writeText(encode(state).toString()) }
    }

    fun encode(s: EarbudsState): JSONObject = JSONObject().apply {
        put("name", s.name)
        put("firmware", s.firmware)
        s.link?.let { put("link", JSONObject().put("linked", it.linked).put("primary", it.primary.name)) }
        s.batteryReport?.let { put("battery", JSONObject().put("primary", it.primary).put("other", it.other).put("case", it.case)) }
        put("mode", s.mode?.code)
        put("hearThrough", s.hearThroughLevel)
        put("sidetone", s.sidetoneLevel)
        put("voiceAssistant", s.voiceAssistant)
        s.eqGains?.let { put("eq", JSONArray(it)) }
        put("toggles", JSONObject().apply { s.toggles.forEach { (t, on) -> if (on != null) put(t.name, on) } })
        put("choices", JSONObject().apply { s.choices.forEach { (c, i) -> if (i != null) put(c.name, i) } })
        put(
            "buttons",
            JSONArray(s.buttons.values.flatten().map { JSONArray(listOf(it.menu, it.earbud, it.gesture, it.action)) }),
        )
    }

    /** Null if there's nothing worth showing (the earbuds were never read). */
    fun decode(o: JSONObject): EarbudsState? {
        val name = o.stringOrNull("name") ?: return null
        val link = o.optJSONObject("link")?.let { EarbudLink(it.getBoolean("linked"), Side.valueOf(it.getString("primary"))) }
        val battery = o.optJSONObject("battery")?.let { BatteryReport(it.intOrNull("primary"), it.intOrNull("other"), it.intOrNull("case")) }
        val toggles = o.optJSONObject("toggles")?.let { t -> Toggle.entries.filter { t.has(it.name) }.associateWith { t.getBoolean(it.name) } }
        val choices = o.optJSONObject("choices")?.let { c -> Choice.entries.filter { c.has(it.name) }.associateWith { c.getInt(it.name) } }
        val buttons = o.optJSONArray("buttons")?.let { a ->
            (0 until a.length()).map { i -> a.getJSONArray(i).let { ButtonAssignment(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) } }
        }
        return EarbudsState(
            name = name,
            firmware = o.stringOrNull("firmware"),
            link = link,
            batteryReport = battery,
            mode = o.intOrNull("mode")?.let(SoundMode::of),
            hearThroughLevel = o.intOrNull("hearThrough"),
            sidetoneLevel = o.intOrNull("sidetone"),
            voiceAssistant = o.intOrNull("voiceAssistant"),
            eqGains = o.optJSONArray("eq")?.let { a -> (0 until a.length()).map(a::getInt) },
            toggles = toggles.orEmpty(),
            choices = choices.orEmpty(),
            buttons = buttons.orEmpty().groupBy { it.menu },
        )
    }

    private fun JSONObject.intOrNull(key: String) = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.stringOrNull(key: String) = if (has(key) && !isNull(key)) getString(key) else null
}
