package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.*
import org.json.JSONObject
import java.util.*

data class VideoItem(val id: String, val title: String, val description: String)

class MainActivity : AppCompatActivity() {

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private lateinit var micButton: Button
    private lateinit var actionButton: Button
    private lateinit var watchButton: Button

    private var videoQueue = mutableListOf<VideoItem>()
    private var currentIndex = -1
    private var isStepTwo = false
    private var isListening = false // Добавьте флаг в начало класса


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. УСТАНОВКА ЧИСТОГО ЧЕРНОГО ЦВЕТА И ОТКЛЮЧЕНИЕ СЕРОГО НАЛЕТА
        window.apply {
            navigationBarColor = Color.BLACK
            statusBarColor = Color.BLACK

            // Отключаем принудительный системный контраст (scrim) для Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isNavigationBarContrastEnforced = false
                isStatusBarContrastEnforced = false
            }
        }

        setContentView(R.layout.activity_main)

        // 2. НАСТРОЙКА ОТСТУПОВ (чтобы интерфейс не залезал под черные панели)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        micButton = findViewById(R.id.micButton)
        actionButton = findViewById(R.id.actionButton)
        watchButton = findViewById(R.id.watchButton)

       micButton.setOnClickListener {
            if (!isListening) {
                tts.stop()
                speakOut("слушаю", "START_LISTENING")
                isListening = true
            } else isListening = false
        }

        actionButton.setOnClickListener {
            tts.stop()
            handleNext()
        }

        watchButton.setOnClickListener {
            tts.stop()
            if (currentIndex != -1 && videoQueue.isNotEmpty()) {
                openYouTube(videoQueue[currentIndex].id)
            }
        }

        initTTS()
        loadQueue()
    }

    private fun openYouTube(videoId: String) {
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId"))
        try {
            // Пробуем запустить именно через приложение YouTube
            appIntent.setPackage("com.google.android.youtube")
            startActivity(appIntent)
        } catch (e: Exception) {
        }
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("ru")
                val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                tts.setSpeechRate(prefs.getFloat("rate", 1.0f))

                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(p0: String?) {}
                    override fun onError(p0: String?) {}
                    override fun onDone(id: String?) {
                        if (id == "START_LISTENING") {
                            Handler(Looper.getMainLooper()).postDelayed({ startListening() }, 300)
                        }
                    }
                })
            }
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p0: Bundle?) { micButton.setBackgroundColor(Color.YELLOW) }
            override fun onResults(results: Bundle?) {
                val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!data.isNullOrEmpty()) {
                    val firstMatch = data[0] // Берем первую строку
                    processCommand(firstMatch) // Передаем String, как и ожидает метод
                }
                isListening = false
                micButton.setBackgroundColor(Color.WHITE)
            }

            override fun onError(p0: Int) { stopListening(); speakOut("ошибка"); tts.playSilentUtterance(1000, TextToSpeech.QUEUE_ADD, null); micButton.setBackgroundColor(Color.WHITE) }
            override fun onRmsChanged(p0: Float) { micButton.alpha = 0.5f + (p0 / 20f) }
            override fun onEndOfSpeech() { isListening = false; speakOut("конец приёма"); tts.playSilentUtterance(1000, TextToSpeech.QUEUE_ADD, null); micButton.setBackgroundColor(Color.WHITE) }
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)

    }

    private fun processCommand(text: String) {
        val cmd = text.lowercase(Locale.ROOT).trim()
        when {
            cmd.contains("быстрее") -> changeRate(0.2f)
            cmd.contains("медленнее") -> changeRate(-0.2f)
            cmd.contains("повтори") -> { isStepTwo = false; handleNext(true) }
            else -> {
                var query = cmd
                if (cmd.contains("найди")) query = cmd.split("найди").last().trim()
                query = query.replace("много", "").replace("слушаю", "").replace("свежее", "").trim()
                if (query.length > 1) {
                    speakOut("Ищу $cmd")
                    tts.playSilentUtterance(1000, TextToSpeech.QUEUE_ADD, null)
                    searchYoutube(query, cmd.contains("много"), cmd.contains("свежее"))
                } else speakOut("Тема не ясна")
            }
        }
    }

    private fun searchYoutube(query: String, isDeep: Boolean, isNew: Boolean) {
        Thread {
            try {
                val limit = if (isDeep) 10 else 1
                val found = mutableListOf<VideoItem>()
                val client = OkHttpClient()
                var token: String? = ""
                for (i in 1..limit) {
                    val urlBuilder = HttpUrl.Builder()
                        .scheme("https").host("www.googleapis.com").addPathSegments("youtube/v3/search")
                        .addQueryParameter("part", "snippet").addQueryParameter("q", query)
                        .addQueryParameter("type", "video").addQueryParameter("maxResults", "50")
                        .addQueryParameter("relevanceLanguage", "ru").addQueryParameter("key", "AIzaSyDa8a_cPJsVS51glXvmAjcwNBLdx3xpPU8")
                    if (isNew) {
                        urlBuilder.addQueryParameter("order", "date")
                    }
                    if (!token.isNullOrEmpty()) urlBuilder.addQueryParameter("pageToken", token)
                    val request = Request.Builder().url(urlBuilder.build()).build()
                    val response = client.newCall(request).execute()
                    val json = JSONObject(response.body?.string() ?: "")
                    val items = json.getJSONArray("items")
                    for (j in 0 until items.length()) {
                        val item = items.getJSONObject(j)
                        val vId = item.getJSONObject("id").getString("videoId")
                        val snip = item.getJSONObject("snippet")
                        found.add(VideoItem(vId, snip.getString("title"), snip.getString("description")))
                    }
                    token = if (json.has("nextPageToken")) json.getString("nextPageToken") else null
                    if (token == null) break
                }
                if (isDeep) { found.shuffle(); speakOut("выполнен глубокий поиск много по теме"); tts.playSilentUtterance(1000, TextToSpeech.QUEUE_ADD, null) }
                runOnUiThread {
                    if (found.isNotEmpty()) {
                        videoQueue.clear(); videoQueue.addAll(found)
                        currentIndex = -1; isStepTwo = false; handleNext()
                    } else speakOut("Ничего не найдено")
                }
            } catch (e: Exception) { runOnUiThread { speakOut("Ошибка сети") } }
        }.start()
    }

    private fun handleNext(repeat: Boolean = false) {
        if (videoQueue.isEmpty()) return
        if (!isStepTwo) {
            if (!repeat) {
                currentIndex++; if (currentIndex >= videoQueue.size) currentIndex = 0
            }
            speakOut("${currentIndex + 1} из ${videoQueue.size}. ")
            tts.playSilentUtterance(1000, TextToSpeech.QUEUE_ADD, null)
            speakOut("${cleanText(videoQueue[currentIndex].title)}")
            isStepTwo = true; saveQueue()
        } else {
            speakOut("Описание")
            tts.playSilentUtterance(1000, TextToSpeech.QUEUE_ADD, null)
            speakOut("${cleanText(videoQueue[currentIndex].description)}")
            isStepTwo = false
        }
    }

    private fun cleanText(text: String): String = text.replace(Regex("https?://\\S+\\s?|www\\.\\S+\\s?"), " ")
        .replace(Regex("[^а-яА-Яa-zA-Z0-9\\s.,!?\\-]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun changeRate(delta: Float) {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        var rate = (prefs.getFloat("rate", 1.0f) + delta).coerceIn(0.5f, 2.5f)
        tts.setSpeechRate(rate)
        prefs.edit().putFloat("rate", rate).apply()
        speakOut(if (delta > 0) "увеличила скорость чтения" else "уменьшила скорость чтения")
    }

    private fun speakOut(text: String, id: String = "id_" + System.currentTimeMillis()) {
        val b = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        tts.speak(text, TextToSpeech.QUEUE_ADD, b, id)
    }

    private fun saveQueue() {
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
            .putString("q", Gson().toJson(videoQueue)).putInt("i", currentIndex).apply()
    }

    private fun loadQueue() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val json = prefs.getString("q", null)
        if (json != null) {
            videoQueue.addAll(Gson().fromJson(json, object : TypeToken<MutableList<VideoItem>>() {}.type))
            currentIndex = prefs.getInt("i", -1)
        }
    }

    private fun stopListening() {
        // speechRecognizer?.stopListening() // Остановить запись и начать распознавание того, что успели сказать
        // ИЛИ
        speechRecognizer?.cancel() // Если нужно просто всё сбросить без обработки
        isListening = false
        speechRecognizer?.destroy()
        micButton.setBackgroundColor(Color.WHITE) // Возвращаем цвет кнопки
        micButton.alpha = 1.0f
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tts.shutdown()
    }
}
