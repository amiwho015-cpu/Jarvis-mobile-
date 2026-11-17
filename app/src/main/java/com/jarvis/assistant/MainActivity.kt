package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var langField: EditText
    private lateinit var wakeField: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            setPadding(0, pad, 0, 0)
        }

        fun field(value: String, password: Boolean = false) = EditText(this).apply {
            setText(value)
            setSingleLine(true)
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        fun button(t: String, action: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { action() }
        }

        root.addView(TextView(this).apply {
            text = "Jarvis"
            textSize = 28f
        })

        status = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        root.addView(status)

        root.addView(label("Gemini API key (aistudio.google.com)"))
        keyField = field(prefs.getString("api_key", "") ?: "", true)
        root.addView(keyField)

        root.addView(label("Gemini model"))
        modelField = field(prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash")
        root.addView(modelField)

        root.addView(label("Language (en-US, bn-BD, hi-IN ...)"))
        langField = field(prefs.getString("lang", "en-US") ?: "en-US")
        root.addView(langField)

        root.addView(label("Wake word"))
        wakeField = field(prefs.getString("wake", "jarvis") ?: "jarvis")
        root.addView(wakeField)

        root.addView(button("Save") { save(); status.text = "Saved." })
        root.addView(button("Start Jarvis") { startJarvis() })
        root.addView(button("Stop Jarvis") {
            stopService(Intent(this, JarvisService::class.java))
            refreshStatus()
        })
        root.addView(button("Allow: display over other apps (open app command)") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("Allow: battery unrestricted") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATIONS_SETTINGS))
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = if (JarvisService.running) "Status: running" else "Status: stopped"
    }

    private fun save() {
        getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
            .putString("api_key", keyField.text.toString().trim())
            .putString("model", modelField.text.toString().trim().ifEmpty { "gemini-2.5-flash" })
            .putString("lang", langField.text.toString().trim().ifEmpty { "en-US" })
            .putString("wake", wakeField.text.toString().trim().lowercase().ifEmpty { "jarvis" })
            .apply()
    }

    private fun startJarvis() {
        save()
        if (keyField.text.isBlank()) {
            status.text = "Age Gemini API key boshao."
            return
        }
        val need = mutableListOf<String>()
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
        status.text = "Status: running"
    }
}
