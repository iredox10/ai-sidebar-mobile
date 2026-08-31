package com.iredox.aisidebar.data

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class UsageEntry(var `in`: Long = 0, var out: Long = 0, var cost: Double = 0.0, var count: Int = 0)
data class UsageTotals(var `in`: Long = 0, var out: Long = 0, var cost: Double = 0.0, var count: Int = 0)
data class UsageAggregate(val totals: UsageTotals, val perModel: Map<String, UsageEntry>)

class UsageStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(provider: String, model: String, promptTokens: Long?, completionTokens: Long?) {
        if (promptTokens == null) return
        val pricing = getPricing(provider, model) ?: return
        if (pricing.first == null) return
        val inCost = (promptTokens / 1_000_000.0) * pricing.first!!
        val outCost = ((completionTokens ?: 0) / 1_000_000.0) * (pricing.second ?: 0.0)
        val cost = inCost + outCost
        val key = "$provider/$model"
        val day = todayKey()
        val raw = prefs.getString(KEY_LOG, null)?.let { JSONObject(it) } ?: JSONObject()
        val dayObj = raw.optJSONObject(day) ?: JSONObject()
        val entry = dayObj.optJSONObject(key)?.let {
            UsageEntry(it.optLong("in"), it.optLong("out"), it.optDouble("cost"), it.optInt("count"))
        } ?: UsageEntry()
        entry.`in` += promptTokens
        entry.out += completionTokens ?: 0
        entry.cost += cost
        entry.count += 1
        dayObj.put(key, JSONObject().apply { put("in", entry.`in`); put("out", entry.out); put("cost", entry.cost); put("count", entry.count) })
        raw.put(day, dayObj)
        // prune to 60 days
        val keys = raw.keys().asSequence().toList().sorted()
        if (keys.size > 60) {
            for (i in 0 until keys.size - 60) raw.remove(keys[i])
        }
        prefs.edit().putString(KEY_LOG, raw.toString()).apply()
    }

    fun aggregate(days: Int): UsageAggregate {
        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }
        cutoff.set(Calendar.HOUR_OF_DAY, 0); cutoff.set(Calendar.MINUTE, 0); cutoff.set(Calendar.SECOND, 0); cutoff.set(Calendar.MILLISECOND, 0)
        val cutKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cutoff.time)
        val raw = prefs.getString(KEY_LOG, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return UsageAggregate(UsageTotals(), emptyMap())
        val totals = UsageTotals()
        val perModel = mutableMapOf<String, UsageEntry>()
        raw.keys().forEach { day ->
            if (day < cutKey) return@forEach
            val dayObj = raw.optJSONObject(day) ?: return@forEach
            dayObj.keys().forEach { key ->
                val e = dayObj.optJSONObject(key) ?: return@forEach
                val inc = UsageEntry(e.optLong("in"), e.optLong("out"), e.optDouble("cost"), e.optInt("count"))
                totals.`in` += inc.`in`; totals.out += inc.out; totals.cost += inc.cost; totals.count += inc.count
                val agg = perModel.getOrPut(key) { UsageEntry() }
                agg.`in` += inc.`in`; agg.out += inc.out; agg.cost += inc.cost; agg.count += inc.count
            }
        }
        return UsageAggregate(totals, perModel)
    }

    fun clear() { prefs.edit().remove(KEY_LOG).apply() }

    fun dayCount(): Int = prefs.getString(KEY_LOG, null)?.let { JSONObject(it).length() } ?: 0

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())

    private fun getPricing(provider: String, model: String): Pair<Double?, Double?>? {
        val list = when (provider.lowercase()) {
            "openai" -> listOf(
                "gpt-4o-mini" to (0.15 to 0.6),
                "gpt-4o" to (2.5 to 10.0),
                "gpt-4.1-mini" to (0.4 to 1.6),
                "gpt-4.1-nano" to (0.1 to 0.4),
                "gpt-4.1" to (2.0 to 8.0),
                "gpt-4" to (30.0 to 60.0),
                "o3" to (2.0 to 8.0),
                "o4" to (1.1 to 4.4),
                "gpt-3.5" to (0.5 to 1.5)
            )
            "deepseek" -> listOf("reasoner" to (0.55 to 2.19))
            "anthropic" -> listOf("opus" to (15.0 to 75.0), "sonnet" to (3.0 to 15.0), "haiku" to (0.8 to 4.0))
            "google" -> listOf("flash-lite" to (0.1 to 0.4), "flash" to (0.1 to 0.4), "pro" to (1.25 to 10.0))
            "openrouter" -> listOf("" to (0.5 to 1.5))
            else -> listOf("" to (null to null))
        }
        val name = model.lowercase()
        for ((match, price) in list) {
            if (match.isEmpty() || name.contains(match)) return price
        }
        return list.lastOrNull()?.second
    }

    companion object {
        const val PREFS = "usage_store"
        const val KEY_LOG = "usage_log"
    }
}
