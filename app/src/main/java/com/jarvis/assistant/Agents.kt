package com.jarvis.assistant

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Agents, same idea as OpenJarvis agents:
//   simple    = plain chat, no tools
//   assistant = tool-using agent (ReAct style loop)
//   research  = deep research: web search + page fetch + cross-checking
//   briefing  = spoken morning digest (weather + news + saved notes)
object Agents {
    val names = listOf("assistant", "simple", "research", "briefing")

    private const val RESEARCH =
        "\nYou are in deep research mode: search the web, open the most relevant pages with web_fetch, " +
            "cross-check at least two sources when possible, then give a short answer that names the sources."

    fun run(ctx: Context, p: Prefs, agent: String, history: List<Msg>, text: String): String {
        val box = Toolbox(ctx, p)
        return when (agent) {
            "simple" -> Engines.chat(p, persona(p, box), history + Msg("user", text))
            "research" -> loop(
                p, box, persona(p, box) + RESEARCH,
                box.pick(listOf("web_search", "web_fetch", "remember", "get_time")),
                history, text, 8
            )
            "briefing" -> briefing(p, box)
            else -> loop(
                p, box, persona(p, box),
                box.pick(
                    listOf(
                        "get_time", "calculator", "open_app", "remember", "recall",
                        "forget", "battery", "weather", "news", "web_search"
                    )
                ),
                history, text, 6
            )
        }
    }

    private fun persona(p: Prefs, box: Toolbox): String {
        val sb = StringBuilder()
        sb.append("You are Jarvis, the user's personal AI assistant on their phone. ")
        sb.append("You are loyal, efficient and dry-witted, with a warm British sensibility: ")
        sb.append("polite but never obsequious, witty but never frivolous, calm under pressure. ")
        val h = p.s("honorific")
        if (h.isNotBlank()) sb.append("Address the user as \"$h\" now and then, never in every sentence. ")
        sb.append("Your replies may be read aloud: use 1 to 4 short sentences, plain text, no markdown, no emojis, no lists. ")
        sb.append("Reply in the language the user speaks. ")
        sb.append("Only state facts from the conversation, saved memory or tool results; never invent facts.")
        val mem = box.memoryList()
        if (mem.isNotEmpty()) {
            sb.append("\nKnown facts about the user:\n").append(mem.joinToString("\n") { "- $it" })
        }
        return sb.toString()
    }

    private fun protocol(tools: List<Tool>): String {
        val sb = StringBuilder()
        sb.append("You can use tools. To call a tool, reply with ONLY one JSON object and nothing else, like:\n")
        sb.append("{\"tool\":\"name\",\"args\":{\"key\":\"value\"}}\n")
        sb.append("Available tools:\n")
        for (t in tools) sb.append("- ").append(t.desc).append("\n")
        sb.append("After a tool result arrives, call another tool or give the final answer as plain text (not JSON). ")
        sb.append("Use tools only when they are needed.")
        return sb.toString()
    }

    private fun parseCall(t: String): Pair<String, JSONObject>? {
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try {
            val o = JSONObject(t.substring(a, b + 1))
            val n = o.optString("tool")
            if (n.isEmpty()) null else Pair(n, o.optJSONObject("args") ?: JSONObject())
        } catch (e: Exception) {
            null
        }
    }

    private fun loop(
        p: Prefs, box: Toolbox, system: String, tools: List<Tool>,
        history: List<Msg>, text: String, maxSteps: Int
    ): String {
        val sys = system + "\n\n" + protocol(tools)
        val work = ArrayList<Msg>(history)
        work.add(Msg("user", text))

        for (step in 1..maxSteps) {
            val reply = Engines.chat(p, sys, work)
            val call = parseCall(reply) ?: return reply
            val tool = tools.firstOrNull { it.name == call.first }
            val result = if (tool == null) "Unknown tool ${call.first}." else box.call(tool, call.second)
            work.add(Msg("assistant", reply))
            work.add(
                Msg(
                    "user",
                    "Tool result from ${call.first}:\n$result\n\nNow call another tool or give the final answer in plain text."
                )
            )
        }
        val last = work.removeAt(work.size - 1)
        work.add(Msg("user", last.text + "\n\nGive the final answer now in plain text, without calling any tool."))
        return Engines.chat(p, sys, work)
    }

    private fun briefing(p: Prefs, box: Toolbox): String {
        val sb = StringBuilder()
        sb.append("Date and time: ")
            .append(SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date()))
            .append("\n")
        val city = p.s("city")
        if (city.isNotBlank()) {
            try {
                sb.append("Weather: ").append(box.weather(city)).append("\n")
            } catch (e: Exception) {
            }
        }
        try {
            sb.append("Top news headlines:\n").append(box.news()).append("\n")
        } catch (e: Exception) {
        }
        val mem = box.memoryList()
        if (mem.isNotEmpty()) {
            sb.append("Things the user asked you to remember:\n").append(mem.joinToString("\n") { "- $it" })
        }
        val sys = persona(p, box) +
            "\nYou are giving a spoken morning briefing. Use ONLY the data provided. " +
            "Keep it under 90 words. Skip any source that is missing without mentioning it."
        return Engines.chat(p, sys, listOf(Msg("user", "Here is my data:\n$sb\n\nGive me my briefing.")))
    }
}
