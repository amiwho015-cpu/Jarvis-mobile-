package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    private const val SYSTEM =
        "You are Jarvis, a helpful voice assistant. Answer in 1 to 3 short sentences, " +
            "plain text only (no markdown, no lists, no emojis). " +
            "Reply in the same language the user speaks."

    private fun msg(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    fun ask(apiKey: String, model: String, history: List<Pair<String, String>>, prompt: String): String {
        val contents = JSONArray()
        for ((u, a) in history) {
            contents.put(msg("user", u))
            contents.put(msg("model", a))
        }
        contents.put(msg("user", prompt))

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", contents)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val m = try {
                    JSONObject(text).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    text.take(120)
                }
                return "Gemini error $code. $m"
            }

            val parts = JSONObject(text)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            return parts.getJSONObject(0).getString("text").replace(Regex("[*#`]"), "").trim()
        } finally {
            c.disconnect()
        }
    }
}
