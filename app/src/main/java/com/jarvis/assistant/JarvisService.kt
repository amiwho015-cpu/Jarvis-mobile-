package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_STOP = "com.jarvis.assistant.STOP"
        const val CHANNEL = "jarvis"
        const val NOTIF_ID = 1

        @Volatile
        var running = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var awaiting = false
    private var busy = false
    private var stopAfterSpeak = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val history = ArrayList<Msg>()
    private lateinit var prefs: Prefs
    private var lang = "en-US"
    private var wake = "jarvis"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotif("Listening...")
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }

        tts = TextToSpeech(this, this)

        // Keeps the CPU awake with screen off. Costs battery; released in onDestroy.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:listen").apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        lang = prefs.s("lang", "en-US")
        wake = prefs.s("wake", "jarvis").lowercase().trim()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Speech recognition is not available on this phone.")
            stopAfterSpeak = true
            return START_NOT_STICKY
        }
        running = true
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotif("Say \"$wake\" + your command"))
        listen()
        return START_STICKY
    }

    private fun buildNotif(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build())
            .build()
    }

    // ---------- Text to speech ----------

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale.forLanguageTag(lang)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    handler.post { finishSpeaking() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handler.post { finishSpeaking() }
                }
            })
        }
    }

    private fun say(text: String) {
        handler.post {
            busy = true
            if (!ttsReady) {
                finishSpeaking()
                return@post
            }
            val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.currentTimeMillis())
            if (r != TextToSpeech.SUCCESS) finishSpeaking()
        }
    }

    private fun finishSpeaking() {
        busy = false
        if (stopAfterSpeak) {
            stopSelf()
            return
        }
        listen()
    }

    // ---------- Speech recognition ----------

    private val listenRunnable = Runnable {
        if (!running || busy) return@Runnable
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(listener)
        recognizer = r
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        r.startListening(i)
    }

    private fun listen(delay: Long = 300) {
        handler.removeCallbacks(listenRunnable)
        handler.postDelayed(listenRunnable, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stopSelf()
                return
            }
            if (awaiting &&
                (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)
            ) {
                awaiting = false
            }
            val d = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> 3000L
                else -> 400L
            }
            listen(d)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            handleText(text)
        }
    }

    // ---------- Commands ----------

    private fun handleText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) {
            listen()
            return
        }
        if (awaiting) {
            awaiting = false
            process(t)
            return
        }
        val idx = t.lowercase().indexOf(wake)
        if (idx < 0) {
            listen()
            return
        }
        val cmd = t.substring(idx + wake.length).trim().trimStart(',', '.', '!', '?', ' ')
        if (cmd.isEmpty()) {
            awaiting = true
            say("Yes?")
        } else {
            process(cmd)
        }
    }

    private fun process(cmd: String) {
        busy = true
        Thread {
            val reply = try {
                answer(cmd)
            } catch (e: Exception) {
                "Sorry, something went wrong. ${e.message ?: ""}"
            }
            say(reply)
        }.start()
    }

    private fun answer(cmd: String): String {
        val lower = cmd.lowercase().trim()
        when {
            lower == "stop" || lower == "stop listening" || lower == "goodbye" || lower == "bye" -> {
                stopAfterSpeak = true
                return "Goodbye."
            }
            lower.startsWith("open ") || lower.startsWith("launch ") ->
                return Toolbox(this, prefs).openApp(cmd.substringAfter(' ').trim())
            lower.contains("what time") || lower.contains("the time") || lower == "time" ->
                return "It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            lower.contains("the date") || lower.contains("today's date") || lower.contains("what day") ->
                return "Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
        }

        val agent = if (lower.contains("briefing") || lower.contains("brief me")) {
            "briefing"
        } else {
            val a = prefs.s("agent", "assistant")
            if (a == "briefing") "assistant" else a
        }
        val reply = Agents.run(applicationContext, prefs, agent, history.toList(), cmd)
        history.add(Msg("user", cmd))
        history.add(Msg("assistant", reply))
        while (history.size > 12) history.removeAt(0)
        return reply
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
