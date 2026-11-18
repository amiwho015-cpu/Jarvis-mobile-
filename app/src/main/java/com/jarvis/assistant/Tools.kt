package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Tool(val name: String, val desc: String, val run: (JSONObject) -> String)

// Tool set, same idea as OpenJarvis tools: small functions an agent can call.
class Toolbox(private val ctx: Context, private val prefs: Prefs) {

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---------- memory ----------
    fun memoryList(): List<String> {
        return try {
            val a = JSONArray(prefs.s("memory", "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveMemory(items: List<String>) {
        prefs.put("memory", JSONArray(items).toString())
    }

    fun clearMemory() {
        prefs.put("memory", "[]")
    }

    // ---------- helpers ----------
    private fun stripHtml(h: String): String =
        h.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun openApp(name: String): String {
        if (name.isBlank()) return "Which app?"
        val q = name.lowercase().trim()
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(main, 0)
            .firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "I could not find an app called $name."
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "I could not open $name."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(launch)
            val label = match.loadLabel(pm)
            if (Settings.canDrawOverlays(ctx)) {
                "Opening $label."
            } else {
                "Opening $label. If nothing happens, allow display over other apps in Jarvis settings."
            }
        } catch (e: Exception) {
            "I could not open $name."
        }
    }

    private fun wcode(c: Int): String = when (c) {
        0 -> "clear sky"
        1, 2, 3 -> "partly cloudy"
        45, 48 -> "fog"
        in 51..57 -> "drizzle"
        in 61..67 -> "rain"
        in 71..77 -> "snow"
        in 80..82 -> "rain showers"
        in 95..99 -> "thunderstorm"
        else -> "mixed weather"
    }

    fun weather(cityIn: String): String {
        val city = cityIn.ifBlank { prefs.s("city") }
        if (city.isBlank()) return "No city set. Ask the user for a city or set one in Settings."
        val g = JSONObject(Http.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + enc(city)))
        val r = g.optJSONArray("results") ?: return "City not found: $city"
        val o = r.getJSONObject(0)
        val lat = o.getDouble("latitude")
        val lon = o.getDouble("longitude")
        val w = JSONObject(
            Http.get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&forecast_days=1&timezone=auto"
            )
        )
        val cur = w.getJSONObject("current")
        val d = w.getJSONObject("daily")
        return "$city: now ${cur.getDouble("temperature_2m")} C, ${wcode(cur.getInt("weather_code"))}. " +
            "Today high ${d.getJSONArray("temperature_2m_max").getDouble(0)} C, " +
            "low ${d.getJSONArray("temperature_2m_min").getDouble(0)} C, " +
            "rain chance ${d.getJSONArray("precipitation_probability_max").optInt(0)} percent."
    }

    fun news(): String {
        val x = Http.get("https://feeds.bbci.co.uk/news/rss.xml")
        val titles = Regex("(?s)<item>.*?<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>")
            .findAll(x).map { it.groupValues[1].trim() }.take(6).toList()
        if (titles.isEmpty()) return "No headlines available."
        return titles.joinToString("\n") { "- $it" }
    }

    fun search(q: String): String {
        if (q.isBlank()) return "Empty query."
        try {
            val html = Http.get("https://html.duckduckgo.com/html/?q=" + enc(q))
            val re = Regex(
                "(?s)class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?class=\"result__snippet\"[^>]*>(.*?)</a>"
            )
            val out = ArrayList<String>()
            for (m in re.findAll(html)) {
                val href = m.groupValues[1]
                val u = Regex("uddg=([^&]+)").find(href)
                val url = if (u != null) URLDecoder.decode(u.groupValues[1], "UTF-8") else href
                out.add("${out.size + 1}. ${stripHtml(m.groupValues[2])}\n$url\n${stripHtml(m.groupValues[3])}")
                if (out.size >= 5) break
            }
            if (out.isNotEmpty()) return out.joinToString("\n\n")
        } catch (e: Exception) {
            // fall through to Wikipedia
        }
        val j = JSONObject(
            Http.get("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=" + enc(q))
        )
        val arr = j.getJSONObject("query").getJSONArray("search")
        if (arr.length() == 0) return "No results."
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.getString("title")
            out.add("${i + 1}. $t\nhttps://en.wikipedia.org/wiki/${t.replace(' ', '_')}\n${stripHtml(o.optString("snippet"))}")
        }
        return out.joinToString("\n\n")
    }

    fun fetch(url: String): String {
        if (!url.startsWith("http")) return "Only http(s) URLs are supported."
        return stripHtml(Http.get(url, 20000)).take(3000)
    }

    // ---------- tool registry ----------
    private val tools: List<Tool> = listOf(
        Tool("get_time", "get_time(): current date and time") { _ ->
            SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        },
        Tool("calculator", "calculator(expression): evaluate arithmetic like \"12*(3+4)\"") { a ->
            Calc.eval(a.optString("expression"))
        },
        Tool("open_app", "open_app(name): open an installed app by name") { a ->
            openApp(a.optString("name"))
        },
        Tool("remember", "remember(text): save a fact about the user for later") { a ->
            val t = a.optString("text").trim()
            if (t.isEmpty()) {
                "Nothing to remember."
            } else {
                saveMemory(memoryList() + t)
                "Saved."
            }
        },
        Tool("recall", "recall(): list saved facts about the user") { _ ->
            val m = memoryList()
            if (m.isEmpty()) "Memory is empty." else m.joinToString("\n") { "- $it" }
        },
        Tool("forget", "forget(text): delete saved facts that contain the text") { a ->
            val q = a.optString("text").lowercase().trim()
            if (q.isEmpty()) {
                "Say what to forget."
            } else {
                val before = memoryList()
                val after = before.filter { !it.lowercase().contains(q) }
                saveMemory(after)
                "Removed ${before.size - after.size} item(s)."
            }
        },
        Tool("battery", "battery(): phone battery level and charging state") { _ ->
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            "Battery $pct percent, " + (if (charging) "charging" else "not charging")
        },
        Tool("weather", "weather(city): today's weather (city optional, defaults to the saved city)") { a ->
            weather(a.optString("city"))
        },
        Tool("news", "news(): top news headlines") { _ -> news() },
        Tool("web_search", "web_search(query): search the web, returns titles, urls, snippets") { a ->
            search(a.optString("query"))
        },
        Tool("web_fetch", "web_fetch(url): download a web page and return its text") { a ->
            fetch(a.optString("url"))
        }
    )

    fun pick(names: List<String>): List<Tool> = tools.filter { it.name in names }

    fun call(tool: Tool, args: JSONObject): String {
        return try {
            tool.run(args).take(3500)
        } catch (e: Exception) {
            "Tool error: ${e.message}"
        }
    }
}

object Calc {
    fun eval(expr: String): String {
        val s = expr.replace(" ", "").replace("×", "*").replace("÷", "/").replace(",", "")
        return try {
            val v = Parser(s).parse()
            if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        } catch (e: Exception) {
            "Could not evaluate: ${e.message}"
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun parse(): Double {
            val v = expr()
            if (i != s.length) throw IllegalArgumentException("bad expression")
            return v
        }

        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double {
            var v = pow()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]
                val r = pow()
                v = when (op) {
                    '*' -> v * r
                    '/' -> v / r
                    else -> v % r
                }
            }
            return v
        }

        fun pow(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return Math.pow(b, pow())
            }
            return b
        }

        fun unary(): Double {
            if (i < s.length && s[i] == '-') {
                i++
                return -unary()
            }
            if (i < s.length && s[i] == '+') {
                i++
                return unary()
            }
            return atom()
        }

        fun atom(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i >= s.length || s[i] != ')') throw IllegalArgumentException("missing )")
                i++
                return v
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalArgumentException("bad expression")
            return s.substring(st, i).toDouble()
        }
    }
}
