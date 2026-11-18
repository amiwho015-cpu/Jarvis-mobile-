package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var speak: CheckBox
    private val fields = LinkedHashMap<String, EditText>()

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun addField(label: String, key: String, default: String, password: Boolean = false) {
        val l = TextView(this)
        l.text = label
        l.setPadding(0, dp(12), 0, 0)
        root.addView(l)
        val e = EditText(this)
        e.setSingleLine(true)
        e.setText(prefs.s(key, default))
        if (password) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        fields[key] = e
        root.addView(e)
    }

    private fun addButton(text: String, action: () -> Unit) {
        val b = Button(this)
        b.text = text
        b.setOnClickListener { action() }
        root.addView(b)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(16))

        val title = TextView(this)
        title.text = "Jarvis settings"
        title.textSize = 24f
        root.addView(title)

        status = TextView(this)
        status.setPadding(0, dp(8), 0, 0)
        root.addView(status)

        addField("Gemini API key (aistudio.google.com)", "api_key", "", true)
        addField("Gemini model", "model", "gemini-2.5-flash")
        addField("Engine: gemini or local", "engine", "gemini")
        addField("Local engine URL (Ollama / OpenAI compatible)", "base_url", "http://192.168.1.10:11434/v1")
        addField("Local model name", "local_model", "llama3.2")
        addField("Local API key (optional)", "local_key", "", true)
        addField("Language (en-US, bn-BD, hi-IN ...)", "lang", "en-US")
        addField("Wake word (voice service)", "wake", "jarvis")
        addField("What should Jarvis call you? (optional)", "honorific", "")
        addField("City for weather (optional)", "city", "")

        speak = CheckBox(this)
        speak.text = "Speak replies in chat"
        speak.isChecked = prefs.b("speak", true)
        root.addView(speak)

        addButton("Save") { save(); status.text = "Saved." }
        addButton("Start voice service (always listening)") { startVoice() }
        addButton("Stop voice service") {
            stopService(Intent(this, JarvisService::class.java))
            refreshStatus()
        }
        addButton("Allow: display over other apps (open app command)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addButton("Allow: battery unrestricted") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        addButton("Show memory") {
            val m = Toolbox(this, prefs).memoryList()
            status.text = if (m.isEmpty()) "Memory is empty." else m.joinToString("\n") { "- $it" }
        }
        addButton("Clear memory") {
            Toolbox(this, prefs).clearMemory()
            status.text = "Memory cleared."
        }

        val sv = ScrollView(this)
        sv.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = if (JarvisService.running) "Voice service: running" else "Voice service: stopped"
    }

    private fun save() {
        for ((k, e) in fields) {
            var v = e.text.toString().trim()
            if (k == "wake" || k == "engine") v = v.lowercase()
            prefs.put(k, v)
        }
        prefs.putB("speak", speak.isChecked)
    }

    private fun startVoice() {
        save()
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else launchService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchService()
        } else {
            status.text = "Microphone permission lagbe."
        }
    }

    private fun launchService() {
        startForegroundService(Intent(this, JarvisService::class.java))
        status.text = "Voice service: running"
    }
}
