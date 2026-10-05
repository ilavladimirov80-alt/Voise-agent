package com.example.voiceagent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Руки и глаза агента: читает экран, нажимает, вводит текст, прокручивает.
 * Также рисует плавающую кнопку микрофона и окно подтверждения опасных действий.
 */
class AgentAccessibilityService : AccessibilityService(), DeviceController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var runner: AgentRunner
    private lateinit var wm: WindowManager

    private var job: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var micButton: TextView? = null

    /** Элементы последнего снимка экрана; номер в списке = номер для Claude. */
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    // ---------------------------------------------------------------- жизненный цикл

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runner = AgentRunner(prefs, this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.forLanguageTag("ru-RU"))
                ttsReady = true
            }
        }
        showMicButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        job?.cancel()
        recognizer?.destroy()
        tts?.shutdown()
        micButton?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        micButton = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- голос и задачи

    private fun onMicTapped() {
        when {
            listening -> {
                recognizer?.cancel()
                listening = false
                setStatus("idle")
            }
            job?.isActive == true -> {
                job?.cancel()
                tts?.stop()
                setStatus("idle")
            }
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                say("Нет доступа к микрофону. Откройте приложение и выдайте разрешение.")
            else -> listen()
        }
    }

    private fun listen() {
        tts?.stop()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Распознавание речи недоступно на этом телефоне.")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text.isNullOrBlank()) setStatus("idle") else startTask(text)
            }

            override fun onError(error: Int) {
                listening = false
                setStatus("idle")
                if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                    error != SpeechRecognizer.ERROR_CLIENT
                ) {
                    say("Не удалось распознать речь (код $error).")
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }
        listening = true
        setStatus("listen")
        r.startListening(intent)
    }

    /** Запускает задачу. Вызывается и голосом, и текстовым полем в приложении. */
    fun startTask(text: String) {
        if (job?.isActive == true) return
        if (prefs.apiKey.isBlank()) {
            say("Не задан ключ API. Откройте приложение и введите его.")
            return
        }
        job = scope.launch {
            setStatus("work")
            try {
                say(runner.run(text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                say(if (e.code == 401) "Ключ API не подходит." else "Ошибка сервера, код ${e.code}.")
            } catch (e: Exception) {
                say("Ошибка: ${e.message}")
            } finally {
                setStatus("idle")
            }
        }
    }

    private fun say(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voiceagent")
    }

    // ---------------------------------------------------------------- плавающая кнопка

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setStatus(state: String) {
        micButton?.text = when (state) {
            "listen" -> "👂"
            "work" -> "⏳"
            else -> "🎤"
        }
    }

    private fun showMicButton() {
        if (micButton != null) return
        val btn = TextView(this).apply {
            text = "🎤"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1565C0.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            dp(56), dp(56),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }

        // Кнопку можно перетаскивать; короткое касание — старт/стоп.
        var startX = 0f
        var startY = 0f
        var origX = 0
        var origY = 0
        var moved = false
        btn.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    origX = lp.x
                    origY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = origX - dx.toInt()
                        lp.y = origY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onMicTapped()
                    true
                }
                else -> false
            }
        }
        wm.addView(btn, lp)
        micButton = btn
    }

    // ---------------------------------------------------------------- подтверждение

    /** Окно «Разрешить / Отмена» поверх всего. Агент не может нажать его сам. */
    private suspend fun confirm(question: String): Boolean {
        say("$question Подтвердите на экране.")
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val ctx = this@AgentAccessibilityService
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    setBackgroundColor(0xF2212121.toInt())
                }
                val tv = TextView(ctx).apply {
                    text = question
                    textSize = 18f
                    setTextColor(Color.WHITE)
                }
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                val no = Button(ctx).apply { text = "Отмена" }
                val yes = Button(ctx).apply { text = "Разрешить" }
                row.addView(no, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(yes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                box.addView(tv)
                box.addView(row)

                fun finish(answer: Boolean) {
                    try {
                        wm.removeView(box)
                    } catch (_: Exception) {
                    }
                    if (cont.isActive) cont.resume(answer)
                }
                yes.setOnClickListener { finish(true) }
                no.setOnClickListener { finish(false) }

                val lp = WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.BOTTOM }
                wm.addView(box, lp)
                cont.invokeOnCancellation {
                    try {
                        wm.removeView(box)
                    } catch (_: Exception) {
                    }
                }
            }
        } ?: false
    }

    // ---------------------------------------------------------------- DeviceController

    override suspend fun describeScreen(): String {
        val root = rootInActiveWindow ?: run {
            nodes = emptyList()
            return "Экран недоступен (нет активного окна)."
        }
        val pkg = root.packageName?.toString().orEmpty()
        if (isBlocked(pkg)) {
            nodes = emptyList()
            return "Приложение $pkg в списке защищённых: содержимое скрыто, управлять им нельзя."
        }
        val list = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder("Приложение: $pkg\n")
        collect(root, list, sb)
        nodes = list
        if (list.isEmpty()) sb.append("(нет видимых элементов)")
        return sb.toString()
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, sb: StringBuilder) {
        if (out.size >= MAX_NODES || !n.isVisibleToUser) return

        val text = if (n.isPassword) {
            "•••"
        } else {
            (n.text ?: n.contentDescription)?.toString()?.replace("\n", " ")?.take(80)
        }
        val interesting = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable ||
            !text.isNullOrBlank()
        if (interesting) {
            val flags = ArrayList<String>()
            if (n.isClickable) flags.add("нажимаемый")
            if (n.isEditable) flags.add("поле ввода")
            if (n.isScrollable) flags.add("прокручиваемый")
            if (n.isCheckable) flags.add(if (n.isChecked) "включён" else "выключен")

            sb.append('[').append(out.size).append("] ")
            sb.append(n.className?.toString()?.substringAfterLast('.') ?: "View")
            if (!text.isNullOrBlank()) sb.append(" \"").append(text).append('"')
            if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(", ")).append(')')
            sb.append('\n')
            out.add(n)
        }
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            collect(child, out, sb)
        }
    }

    override suspend fun tap(index: Int): String {
        val node = nodes.getOrNull(index) ?: return "Нет элемента с номером $index."
        if (!node.refresh()) return "Элемент исчез, экран изменился."

        val label = (node.text ?: node.contentDescription)?.toString().orEmpty()
        val resId = node.viewIdResourceName.orEmpty()
        if (RISKY.containsMatchIn(label) || RISKY.containsMatchIn(resId)) {
            val pkg = node.packageName?.toString().orEmpty()
            if (!confirm("Нажать «$label» в $pkg?")) {
                return "Пользователь ОТКЛОНИЛ это действие. Не повторяй его, сообщи пользователю."
            }
        }

        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "Нажал на элемент $index."
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return if (gesture(r.centerX().toFloat(), r.centerY().toFloat(), r.centerX().toFloat(), r.centerY().toFloat(), 50)) {
            "Нажал по координатам элемента $index."
        } else {
            "Не удалось нажать."
        }
    }

    override suspend fun typeText(index: Int, text: String): String {
        val node = nodes.getOrNull(index) ?: return "Нет элемента с номером $index."
        if (!node.refresh()) return "Элемент исчез, экран изменился."
        if (node.isPassword) return "Это поле пароля: вводить в него запрещено."
        if (!node.isEditable) return "Элемент $index не является полем ввода."
        if (CARD.containsMatchIn(text)) return "Ввод номеров карт запрещён."

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "Ввёл текст."
        } else {
            "Не удалось ввести текст."
        }
    }

    override suspend fun scroll(direction: String): String {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        // Палец движется в сторону, противоположную направлению прокрутки.
        val (x1, y1, x2, y2) = when (direction) {
            "down" -> listOf(w / 2, h * 0.7f, w / 2, h * 0.3f)
            "up" -> listOf(w / 2, h * 0.3f, w / 2, h * 0.7f)
            "right" -> listOf(w * 0.8f, h / 2, w * 0.2f, h / 2)
            "left" -> listOf(w * 0.2f, h / 2, w * 0.8f, h / 2)
            else -> return "Неизвестное направление: $direction."
        }
        return if (gesture(x1, y1, x2, y2, 350)) "Прокрутил: $direction." else "Не удалось прокрутить."
    }

    override suspend fun pressKey(key: String): String {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "Неизвестная кнопка: $key."
        }
        return if (performGlobalAction(action)) "Нажал $key." else "Не удалось нажать $key."
    }

    override suspend fun openApp(name: String): String {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val q = name.trim().lowercase()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "Приложение «$name» не найдено."

        val pkg = match.activityInfo.packageName
        if (isBlocked(pkg)) return "Приложение «$name» в списке защищённых, открывать его нельзя."
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "Не удалось открыть «$name»."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return "Открыл «${match.loadLabel(pm)}»."
    }

    // ---------------------------------------------------------------- вспомогательное

    private fun isBlocked(pkg: String) = BLOCKED.any { pkg.contains(it, ignoreCase = true) }

    private suspend fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(x1, y1)
                if (x1 != x2 || y1 != y2) lineTo(x2, y2)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val dispatched = dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) cont.resume(false)
        }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null

        private const val MAX_NODES = 150

        /** Нажатия на элементы с такими подписями требуют подтверждения пользователя. */
        private val RISKY = Regex(
            "отправ|send|оплат|pay|куп|buy|purchase|заказ|order|удал|delet|remov|перевод|transfer|" +
                "подтверд|confirm|разреш|allow|выйти|sign.?out|log.?out|очист|clear|сброс|reset|" +
                "установ|install|позвон|звон|call",
            RegexOption.IGNORE_CASE
        )

        /** Что-то похожее на номер банковской карты (13–19 цифр). */
        private val CARD = Regex("(?:\\d[ -]?){13,19}")

        /** Пакеты, содержимое которых агент не видит и которые не открывает. */
        private val BLOCKED = listOf(
            "bank", "sber", "tinkoff", "tbank", "vtb", "alfabank", "paypal", "wallet",
            "authenticator", "keepass", "bitwarden", "lastpass", "1password",
            "permissioncontroller", "packageinstaller"
        )
    }
}
