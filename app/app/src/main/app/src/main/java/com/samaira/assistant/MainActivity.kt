package com.samaira.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var prefs: android.content.SharedPreferences

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    private val messages = JSONArray()

    private val defaultSystemPrompt = """
You are Samaira, a warm, smart, witty personal voice assistant living on the user's Android phone.
Your replies are spoken aloud, so keep them short and natural, usually 1-2 sentences, no lists or symbols.
Reply in the language the user uses (English, Hindi, or Hinglish).
When asked to do something, use your tools to actually do it, then confirm briefly.
Before calls or messages, the phone opens pre-filled and the user taps the final button, so just do it.
Never handle payments, banking, or passwords. Answer general questions honestly; if unsure, use web_search.
""".trim()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("samaira", Context.MODE_PRIVATE)
        transcript = findViewById(R.id.transcript)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)

        tts = TextToSpeech(this, this)
        resetConversation()

        findViewById<Button>(R.id.sendBtn).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.setText("")
                handleUserText(text)
            }
        }
        findViewById<Button>(R.id.micBtn).setOnClickListener { startListening() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { showSettings() }

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun resetConversation() {
        while (messages.length() > 0) messages.remove(0)
        val sys = prefs.getString("system_prompt", null)?.takeIf { it.isNotBlank() } ?: defaultSystemPrompt
        messages.put(JSONObject().put("role", "system").put("content", sys))
    }

    private fun handleUserText(text: String) {
        val key = prefs.getString("api_key", "") ?: ""
        if (key.isBlank()) {
            append("Samaira", "Please open Settings and paste your API key first.")
            return
        }
        append("You", text)
        messages.put(JSONObject().put("role", "user").put("content", text))

        val client = LlmClient(
            prefs.getString("api_url", "https://api.openai.com/v1/chat/completions")!!,
            key,
            prefs.getString("model", "gpt-4o-mini")!!
        )

        thread {
            try {
                var steps = 0
                while (steps < 6) {
                    val msg = client.send(messages, Tools.definitions)
                    messages.put(msg)
                    val toolCalls = msg.optJSONArray("tool_calls")
                    if (toolCalls != null && toolCalls.length() > 0) {
                        for (i in 0 until toolCalls.length()) {
                            val call = toolCalls.getJSONObject(i)
                            val fn = call.getJSONObject("function")
                            val fname = fn.getString("name")
                            val argsStr = fn.optString("arguments", "{}")
                            val args = try { JSONObject(argsStr) } catch (e: Exception) { JSONObject() }
                            val result = runOnUiAndGet { Tools.execute(this, fname, args) }
                            messages.put(
                                JSONObject()
                                    .put("role", "tool")
                                    .put("tool_call_id", call.getString("id"))
                                    .put("content", result)
                            )
                        }
                        steps++
                        continue
                    } else {
                        val content = msg.optString("content", "")
                        runOnUiThread {
                            append("Samaira", content)
                            speak(content)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { append("Samaira", "Something went wrong: ${e.message}") }
            }
        }
    }

    private fun <T> runOnUiAndGet(block: () -> T): T {
        var result: T? = null
        val lock = Object()
        var done = false
        runOnUiThread {
            synchronized(lock) {
                result = block()
                done = true
                lock.notifyAll()
            }
        }
        synchronized(lock) {
            while (!done) lock.wait()
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun append(who: String, text: String) {
        transcript.append("\n\n$who: $text")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
        }
    }

    private fun speak(text: String) {
        if (text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "samaira")
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_LONG).show()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p0: Bundle?) { Toast.makeText(this@MainActivity, "Listening…", Toast.LENGTH_SHORT).show() }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val said = list?.firstOrNull()
                    if (!said.isNullOrBlank()) handleUserText(said)
                }
                override fun onError(code: Int) { Toast.makeText(this@MainActivity, "Didn't catch that", Toast.LENGTH_SHORT).show() }
                override fun onBeginningOfSpeech() {}
                override fun onEndOfSpeech() {}
                override fun onRmsChanged(p0: Float) {}
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onPartialResults(p0: Bundle?) {}
                override fun onEvent(p0: Int, p1: Bundle?) {}
            })
        }
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        recognizer?.startListening(intent)
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun field(label: String, value: String): EditText {
            val t = TextView(this).apply { text = label }
            val e = EditText(this).apply { setText(value) }
            box.addView(t); box.addView(e)
            return e
        }
        val urlE = field("API URL", prefs.getString("api_url", "https://api.openai.com/v1/chat/completions")!!)
        val keyE = field("API Key", prefs.getString("api_key", "")!!)
        val modelE = field("Model", prefs.getString("model", "gpt-4o-mini")!!)
        val promptE = field("Personality (system prompt)", prefs.getString("system_prompt", defaultSystemPrompt)!!)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("api_url", urlE.text.toString().trim())
                    .putString("api_key", keyE.text.toString().trim())
                    .putString("model", modelE.text.toString().trim())
                    .putString("system_prompt", promptE.text.toString().trim())
                    .apply()
                resetConversation()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }
}
