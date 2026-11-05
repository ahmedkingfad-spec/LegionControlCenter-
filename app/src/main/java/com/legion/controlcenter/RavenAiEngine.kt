package com.legion.controlcenter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class RavenChatMessage(
    val sender: String,
    val text: String,
    val isUser: Boolean
)

class RavenAiEngine(private val context: Context, private val controller: SystemController) {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _statusText = MutableStateFlow("Raven AI جاهزة (عربي / EN)")
    val statusText: StateFlow<String> = _statusText

    private val _messages = MutableStateFlow<List<RavenChatMessage>>(
        listOf(
            RavenChatMessage(
                "Raven",
                "أهلاً بك يا بطل في Legion Control Center! أنا Raven، اضغط على المايك وكلمني بالعربي أو الإنجليزي للتحكم في التابلت أو الألعاب.",
                false
            )
        )
    )
    val messages: StateFlow<List<RavenChatMessage>> = _messages

    private val modelCandidates = listOf(
        "gemini-3.5-flash-lite",
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash",
        "gemini-2.0-flash-lite"
    )

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setPitch(1.28f)
                tts?.setSpeechRate(1.03f)
                selectFemaleVoice(Locale("ar"))
            }
        }
    }

    private fun selectFemaleVoice(locale: Locale) {
        val engine = tts ?: return
        try {
            engine.language = locale
            val femaleVoice = engine.voices?.firstOrNull { v ->
                v.locale.language == locale.language &&
                    (v.name.contains("female", true) ||
                        v.name.contains("sfg", true) ||
                        v.name.contains("network", true) ||
                        v.name.contains("oda", true) ||
                        v.name.contains("laila", true))
            }
            if (femaleVoice != null) {
                engine.voice = femaleVoice
            }
        } catch (_: Exception) {
        }
    }

    fun speak(text: String) {
        val engine = tts ?: return
        val isArabic = text.any { it in '\u0600'..'\u06FF' }
        selectFemaleVoice(if (isArabic) Locale("ar") else Locale.US)
        engine.setPitch(1.28f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "raven_utt_${System.currentTimeMillis()}")
    }

    fun startVoiceRecognition(languageTag: String = "ar-EG") {
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
                }
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                        _statusText.value = "Raven بتسمعك الآن... اتكلم (عربي / English)"
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        _isListening.value = false
                        _statusText.value = "جاري تحليل صوتك..."
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        _statusText.value = "اضغط على المايك وتحدث مجدداً"
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()?.trim()
                        if (!spoken.isNullOrEmpty()) {
                            _statusText.value = "سمعتك: $spoken"
                            onUserVoiceOrTextCallback?.invoke(spoken)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            _statusText.value = "بتقول: $partial"
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("ar-EG", "ar-SA", "en-US"))
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                _isListening.value = false
                _statusText.value = "تأكد من إعطاء صلاحية المايكروفون"
            }
        }
    }

    fun stopVoiceRecognition() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
            }
            _isListening.value = false
        }
    }

    var onUserVoiceOrTextCallback: ((String) -> Unit)? = null

    suspend fun processCommand(userInput: String, apiKey: String) {
        _messages.value = _messages.value + RavenChatMessage("أنت", userInput, true)
        _statusText.value = "Raven بتنفذ..."

        val localActionReply = executeDirectLocalIntentIfMatched(userInput)

        if (apiKey.isBlank()) {
            val reply = localActionReply
                ?: "نفذت الأمر المحلي! لو عايزني أدردش بذكاء Gemini 3.5 Flash-Lite الكامل، ضيف الـ API Key من زر الإعدادات."
            _messages.value = _messages.value + RavenChatMessage("Raven", reply, false)
            _statusText.value = "Raven جاهزة"
            speak(reply)
            return
        }

        val systemPrompt = """
            أنتِ Raven (ريفن)، المساعدة الذكية الخاصة بـ Legion Y700 Control Center.
            شخصيتك: بنوتة جيمر ذكية، لطيفة، سريعة البديهة، تتحدثين باللهجة المصرية أو العربية الفصحى الخفيفة أو الإنجليزية حسب لغة المستخدم.
            أجيبي باختصار (جملة أو جملتين بحد أقصى لأن صوتك يقرأ الرد).
            إذا طلب المستخدم التحكم في التابلت، أرفقي في نهاية ردك كود JSON واحد فقط بهذا الشكل:
            {"cmd":"ACTION_NAME","val":NUMBER_OR_STRING}
            الأوامر المتاحة في ACTION_NAME:
            - "BRIGHTNESS": val من 0 إلى 100
            - "VOLUME": val من 0 إلى 100
            - "WIFI": true أو false
            - "BLUETOOTH": true أو false
            - "DND": true أو false
            - "ROTATE": true أو false
            - "EYE_PROTECTION": true أو false
            - "SCREENSHOT": true
            - "SPLIT_SCREEN": true
            - "PERF_MODE": "Power Saving" أو "Balanced" أو "Performance" أو "Extreme"
            - "REFRESH_RATE": 60 أو 90 أو 120 أو 144
            - "FPS_MONITOR": true أو false
            - "KEY_MAPPING": true أو false
            - "MACRO_TAP": true أو false
            - "FLOATING_NOTES": true أو false
            - "MEDIA_PLAY_PAUSE": true
            - "MEDIA_NEXT": true
        """.trimIndent()

        var rawReply: String? = null
        var usedModel = modelCandidates.first()

        for (candidate in modelCandidates) {
            val res = callGeminiApi(candidate, apiKey.trim(), systemPrompt, userInput)
            if (res != null) {
                rawReply = res
                usedModel = candidate
                break
            }
        }

        if (rawReply == null) {
            val fallback = localActionReply ?: "حصلت مشكلة في الاتصال بـ Gemini API، تأكد من الـ API Key أو الإنترنت."
            _messages.value = _messages.value + RavenChatMessage("Raven", fallback, false)
            _statusText.value = "Raven جاهزة"
            speak(fallback)
            return
        }

        val cleanText = parseAndExecuteJsonCommand(rawReply)
        _messages.value = _messages.value + RavenChatMessage("Raven ($usedModel)", cleanText, false)
        _statusText.value = "متصل بـ $usedModel"
        speak(cleanText)
    }

    private fun executeDirectLocalIntentIfMatched(text: String): String? {
        val t = text.lowercase(Locale.ROOT)
        return when {
            t.contains("لقطة شاشة") || t.contains("سكرين شوت") || t.contains("screenshot") -> {
                controller.takeScreenshot()
                "أخذت لقطة شاشة فوراً يا بطل!"
            }
            t.contains("أعلى إضاءة") || t.contains("السطوع 100") || t.contains("max brightness") -> {
                controller.setBrightness(1f)
                "رفعت إضاءة الشاشة لأعلى درجة 100%!"
            }
            t.contains("وطي الإضاءة") || t.contains("السطوع 20") || t.contains("low brightness") -> {
                controller.setBrightness(0.2f)
                "هديت إضاءة الشاشة لـ 20% عشان عينيك!"
            }
            t.contains("علي الصوت") || t.contains("الصوت 100") || t.contains("max volume") -> {
                controller.setVolume(1f)
                "عليت الصوت للآخر!"
            }
            t.contains("اكتم الصوت") || t.contains("صامت") || t.contains("mute") -> {
                controller.setVolume(0f)
                "كتمت الصوت تماماً!"
            }
            t.contains("144") -> {
                controller.setRefreshRate(144)
                "فعلت معدل تحديث 144 هرتز لأقصى سلاسة!"
            }
            t.contains("120") -> {
                controller.setRefreshRate(120)
                "ظبطت الشاشة على 120 هرتز!"
            }
            t.contains("اكستريم") || t.contains("extreme") || t.contains("أقصى أداء") -> {
                controller.setPerformanceMode("Extreme")
                "شغلت وضع Extreme! التابلت دلوقتي في أقصى قوته للألعاب."
            }
            t.contains("فريمات") || t.contains("fps") || t.contains("مراقب الأداء") -> {
                OverlayService.instance?.togglePerformanceMonitor()
                "شغلت عداد الفريمات والحرارة العائم على الشاشة!"
            }
            t.contains("حماية العين") || t.contains("eye") -> {
                OverlayService.instance?.toggleEyeProtection()
                "غيرت وضع حماية العين!"
            }
            else -> null
        }
    }

    private fun parseAndExecuteJsonCommand(response: String): String {
        val jsonRegex = Regex("""\{[^}]*"cmd"[^}]*\}""")
        val match = jsonRegex.find(response)
        if (match != null) {
            try {
                val obj = JSONObject(match.value)
                when (obj.optString("cmd")) {
                    "BRIGHTNESS" -> controller.setBrightness((obj.optInt("val", 70) / 100f).coerceIn(0f, 1f))
                    "VOLUME" -> controller.setVolume((obj.optInt("val", 70) / 100f).coerceIn(0f, 1f))
                    "WIFI" -> controller.toggleWifi()
                    "BLUETOOTH" -> controller.toggleBluetooth()
                    "DND" -> controller.toggleDnd()
                    "ROTATE" -> controller.toggleAutoRotate()
                    "EYE_PROTECTION" -> OverlayService.instance?.toggleEyeProtection()
                    "SCREENSHOT" -> controller.takeScreenshot()
                    "SPLIT_SCREEN" -> controller.toggleSplitScreen()
                    "PERF_MODE" -> controller.setPerformanceMode(obj.optString("val", "Performance"))
                    "REFRESH_RATE" -> controller.setRefreshRate(obj.optInt("val", 120))
                    "FPS_MONITOR" -> OverlayService.instance?.togglePerformanceMonitor()
                    "KEY_MAPPING" -> OverlayService.instance?.toggleKeyMappingOverlay()
                    "MACRO_TAP" -> OverlayService.instance?.toggleMacroOverlay()
                    "FLOATING_NOTES" -> OverlayService.instance?.toggleFloatingWindow()
                    "MEDIA_PLAY_PAUSE" -> MediaNotificationListener.playPause(context)
                    "MEDIA_NEXT" -> MediaNotificationListener.next(context)
                }
            } catch (_: Exception) {
            }
        }
        return jsonRegex.replace(response, "").trim()
    }

    private suspend fun callGeminiApi(
        modelName: String,
        apiKey: String,
        systemInstruction: String,
        userPrompt: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 10000
                readTimeout = 12000
                doOutput = true
            }

            val payload = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                })
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                }))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 180)
                })
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val candidates = json.optJSONArray("candidates")
                val first = candidates?.optJSONObject(0)
                val parts = first?.optJSONObject("content")?.optJSONArray("parts")
                parts?.optJSONObject(0)?.optString("text")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
    }
}
