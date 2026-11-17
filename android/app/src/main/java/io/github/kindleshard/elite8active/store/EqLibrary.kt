package io.github.kindleshard.elite8active.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A custom EQ curve saved under a name. */
data class SavedEq(val name: String, val gains: List<Int>)

/** Your own EQ presets, kept on the phone (the earbuds only hold the current curve). */
object EqLibrary {
    const val MAX = 12
    const val NAME_MAX_LENGTH = 20

    private val _items = MutableStateFlow<List<SavedEq>>(emptyList())
    val items: StateFlow<List<SavedEq>> = _items

    private fun prefs(context: Context) = context.getSharedPreferences("eq_library", Context.MODE_PRIVATE)

    fun init(context: Context) {
        _items.value = decode(prefs(context).getString("items", "[]") ?: "[]")
    }

    /** Saves [gains] as [name], replacing one with the same name; the newest comes first. */
    fun save(context: Context, name: String, gains: List<Int>) =
        store(context, (listOf(SavedEq(name.trim(), gains)) + _items.value.filter { !it.name.equals(name.trim(), ignoreCase = true) }).take(MAX))

    fun delete(context: Context, item: SavedEq) = store(context, _items.value - item)

    fun matching(gains: List<Int>?): SavedEq? = _items.value.firstOrNull { it.gains == gains }

    private fun store(context: Context, items: List<SavedEq>) {
        prefs(context).edit().putString("items", encode(items)).apply()
        _items.value = items
    }

    fun encode(items: List<SavedEq>): String =
        JSONArray(items.map { JSONObject().put("name", it.name).put("gains", JSONArray(it.gains)) }).toString()

    fun decode(text: String): List<SavedEq> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val g = o.getJSONArray("gains")
            SavedEq(o.getString("name"), (0 until g.length()).map(g::getInt))
        }
    }.getOrDefault(emptyList())
}
