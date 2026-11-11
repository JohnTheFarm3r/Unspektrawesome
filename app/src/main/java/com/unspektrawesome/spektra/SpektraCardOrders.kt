package com.unspektrawesome.spektra

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

interface SpektraCardOrderPersistence {
    fun load(): Map<String, List<String>>
    fun save(orders: Map<String, List<String>>)
}

class SharedPreferencesSpektraCardOrderPersistence(private val preferences: SharedPreferences) : SpektraCardOrderPersistence {
    override fun load() = SpektraCardOrderJson.decode(preferences.getString("card_orders", null))
    override fun save(orders: Map<String, List<String>>) {
        preferences.edit().putString("card_orders", SpektraCardOrderJson.encode(orders)).apply()
    }
}

internal object SpektraCardOrderJson {
    fun encode(orders: Map<String, List<String>>): String = JSONObject().apply {
        orders.forEach { (kind, keys) -> put(kind, JSONArray(keys)) }
    }.toString()

    fun decode(json: String?): Map<String, List<String>> = runCatching {
        val objectValue = JSONObject(json ?: "{}")
        buildMap {
            objectValue.keys().forEach { kind ->
                val array = objectValue.optJSONArray(kind) ?: return@forEach
                put(kind, (0 until array.length()).mapNotNull { index ->
                    (array.opt(index) as? String)?.takeIf { it.isNotBlank() }
                }.distinct())
            }
        }
    }.getOrDefault(emptyMap())
}

class SpektraCardOrders(private val persistence: SpektraCardOrderPersistence = object : SpektraCardOrderPersistence {
    override fun load() = emptyMap<String, List<String>>()
    override fun save(orders: Map<String, List<String>>) = Unit
}) {
    private var orders = persistence.load()

    fun orderedKeys(kind: String, available: List<String>): List<String> {
        val saved = orders[kind].orEmpty().filter { it in available }.distinct()
        if (kind == "tabs") {
            val result = saved.toMutableList()
            available.forEachIndexed { index, key ->
                if (key !in result) {
                    val previous = available.take(index).lastOrNull { it in result }
                    result.add(if (previous == null) 0 else result.indexOf(previous) + 1, key)
                }
            }
            return result
        }
        return saved + available.filterNot { it in saved }
    }

    fun save(kind: String, keys: List<String>) {
        val updated = orders + (kind to keys.distinct())
        if (updated == orders) return
        persistence.save(updated)
        orders = updated
    }

    fun saveVisible(kind: String, visibleKeys: List<String>, available: List<String>): List<String> {
        val visible = visibleKeys.filter { it in available }.distinct()
        val reordered = visible.iterator()
        val updated = orderedKeys(kind, available).map { if (it in visible) reordered.next() else it }
        save(kind, updated)
        return updated
    }
}
