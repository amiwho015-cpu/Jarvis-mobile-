package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Msg(val role: String, val text: String)

object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 14) Jarvis/2.0"

    fun get(url: String, timeoutMs: Int = 15000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun postJson(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val st = if (code in 200..299) c.inputStream else c.errorStream
            val text = st?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            c.disconnect()
        }
    }
}

// Engine layer: same idea as OpenJarvis engines. "gemini" = cloud, "local" = any
// OpenAI-compatible server (Ollama, LM Studio, llama.cpp server ...).
object Engines {
    fun chat(p: Prefs, system: String, msgs: List<Msg>): String =
        if (p.s("engine", "gemini").lowercase() == "local") local(p, system, msgs) else gemini(p, system, msgs)

    private fun errorMessage(text: String): String {
        return try {
            val e = JSONObject(text).get("error")
            if (e is JSONObject) e.optString("message") else e.toString()
        } catch (ex: Exception) {
            text.take(150)
        }
    }

    private fun gemini(p: Prefs, system: String, msgs: List<Msg>): String {
        val key = p.s("api_key")
        if (key.isBlank()) throw IllegalStateException("Please set your Gemini API key in Settings.")
        val model = p.s("model", "gemini-2.5-flash")

        val contents = JSONArray()
        for (m in msgs) {
            val role = if (m.role == "assistant") "model" else "user"
            contents.put(
                JSONObject().put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", m.text)))
            )
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)

        val res = Http.postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to key),
            body.toString()
        )
        if (res.first !in 200..299) throw RuntimeException("Gemini error ${res.first}: ${errorMessage(res.second)}")

        val parts = JSONObject(res.second)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString().trim()
    }

    private fun local(p: Prefs, system: String, msgs: List<Msg>): String {
        val base = p.s("base_url", "http://192.168.1.10:11434/v1").trim().trimEnd('/')
        val model = p.s("local_model", "llama3.2")

        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in msgs) arr.put(JSONObject().put("role", m.role).put("content", m.text))
        val body = JSONObject().put("model", model).put("messages", arr).put("stream", false)

        val headers = HashMap<String, String>()
        val k = p.s("local_key")
        if (k.isNotBlank()) headers["Authorization"] = "Bearer $k"

        val res = Http.postJson("$base/chat/completions", headers, body.toString())
        if (res.first !in 200..299) throw RuntimeException("Local engine error ${res.first}: ${errorMessage(res.second)}")

        val content = JSONObject(res.second)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        return content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }
}
