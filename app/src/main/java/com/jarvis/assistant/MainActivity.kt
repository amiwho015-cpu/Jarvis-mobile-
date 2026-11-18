package com.jarvis.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

// Chat screen: text + mic, agent picker at the top.
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val history = ArrayList<Msg>()
    private var tts: TextToSpeech? = null
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val pad = dp(12)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(this)
        title.text = "Jarvis"
        title.textSize = 22f
        top.addView(title, LinearLayout.LayoutParams(0, wrap, 1f))

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Agents.names)
        val idx = Agents.names.indexOf(prefs.s("agent", "assistant"))
        spinner.setSelection(if (idx < 0) 0 else idx)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.put("agent", Agents.names[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        top.addView(spinner)

        val settingsBtn = Button(this)
        settingsBtn.text = "Settings"
        settingsBtn.setOnClickListener {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
        top.addView(settingsBtn)
        root.addView(top, LinearLayout.LayoutParams(match, wrap))

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll = ScrollView(this)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))

        input = EditText(this)
        input.hint = "Message Jarvis"
        input.maxLines = 4

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.addView(input, LinearLayout.LayoutParams(0, wrap, 1f))

        val micBtn = Button(this)
        micBtn.text = "Mic"
        micBtn.setOnClickListener { listenOnce() }
        bottom.addView(micBtn)

        val sendBtn = Button(this)
        sendBtn.text = "Send"
        sendBtn.setOnClickListener { send(input.text.toString()) }
        bottom.addView(sendBtn)
        root.addView(bottom, LinearLayout.LayoutParams(match, wrap))

        setContentView(root)

        addMsg(
            "Hello. Pick an agent at the top: assistant (tools), simple (chat), " +
                "research (web) or briefing (morning digest). Set your API key in Settings first.",
            false
        )

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag(prefs.s("lang", "en-US"))
            }
        }
    }

    private fun addMsg(text: String, user: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(10), dp(8), dp(10), dp(8))
        tv.setTextColor(if (user) 0xFF8AB4F8.toInt() else 0xFFE8EAED.toInt())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.setMargins(0, dp(4), 0, dp(4))
        list.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun send(textIn: String) {
        val text = textIn.trim()
        val agent = prefs.s("agent", "assistant")
        if (text.isEmpty() && agent != "briefing") return
        input.setText("")
        val shown = if (text.isEmpty()) "Morning briefing" else text
        addMsg(shown, true)
        val placeholder = addMsg("...", false)
        val snapshot = ArrayList<Msg>(history)
        val app = applicationContext

        Thread {
            val reply = try {
                Agents.run(app, prefs, agent, snapshot, shown)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            ui.post {
                placeholder.text = reply
                history.add(Msg("user", shown))
                history.add(Msg("assistant", reply))
                while (history.size > 12) history.removeAt(0)
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                if (prefs.b("speak", true)) {
                    tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "reply")
                }
            }
        }.start()
    }

    private fun listenOnce() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.s("lang", "en-US"))
        try {
            startActivityForResult(i, 100)
        } catch (e: ActivityNotFoundException) {
            addMsg("Speech input is not available on this phone.", false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!t.isNullOrBlank()) send(t)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
